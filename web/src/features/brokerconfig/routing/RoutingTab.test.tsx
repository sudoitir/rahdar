import { useState } from 'react';
import { describe, expect, it } from 'vitest';
import { http, HttpResponse } from 'msw';
import { fireEvent, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

import { renderWithProviders } from '../../../test/render.tsx';
import { server } from '../../../test/setup.ts';
import type { GateVerdict } from '../../../ui/capabilityGate.ts';
import type { ConfigBridgeView, ConfigDeclarationView, ConfigDivertView, ConfigDriftFindingView } from '../api.ts';
import { declaration, NODE_A } from '../fixtures.ts';
import type { Section } from '../words.ts';
import { RoutingTab } from './RoutingTab.tsx';
import { paged } from '../../../kernel/api/paging.ts';

/**
 * The routing builder, queried the way an operator reaches it: by role and
 * accessible name only, never by class or test id. The canvas is an editing
 * surface for the declaration, so what is asserted here is what it *says* — the
 * state of every element in words, the bound it draws within, and the fact that
 * nothing it does reaches a broker.
 */

const ALLOWED: GateVerdict = { kind: 'allowed', uncertain: false };

const DIVERT: ConfigDivertView = {
  name: 'audit-copy',
  address: 'orders.request',
  forwardingAddress: 'orders.audit',
  filter: null,
  exclusive: false,
  routingType: null,
  transformerClassName: 'com.example.Stamp',
  transformerProperties: { tenant: 'eu' },
};

const BRIDGE: ConfigBridgeView = {
  name: 'to-dr',
  queueName: 'orders.request',
  forwardingAddress: 'orders.dr',
  filter: null,
  transformer: { className: 'com.example.Rewrite', properties: { region: 'dr' } },
  staticConnectors: ['dr-connector'],
  discoveryGroupName: null,
  ha: false,
  useDuplicateDetection: true,
  credentialRef: null,
};

function finding(over: Partial<ConfigDriftFindingView>): ConfigDriftFindingView {
  return { kind: 'MISSING', section: null, key: null, detail: '', declared: {}, observed: {}, ...over };
}

function routed(over: Partial<ConfigDeclarationView> = {}): ConfigDeclarationView {
  return declaration({
    document: {
      version: 1,
      addresses: [
        {
          name: 'orders.request',
          routingTypes: ['ANYCAST'],
          queues: [{ name: 'orders.request', routingType: 'ANYCAST', durable: true }],
        },
        { name: 'orders.audit', routingTypes: ['MULTICAST'], queues: [] },
      ],
      addressSettings: [],
      securitySettings: [],
      diverts: [DIVERT],
      bridges: [BRIDGE],
    },
    nodes: [NODE_A],
    ...over,
  });
}

/** The URL, as the screen sees it: what is selected and which editor is open round-trips through here. */
function Harness({ declaration: d, gate = ALLOWED }: { declaration: ConfigDeclarationView; gate?: GateVerdict }) {
  const [search, setSearch] = useState<{
    section?: Section;
    item?: string;
    anchor?: string;
    selected?: string;
  }>({});
  return (
    <RoutingTab
      declaration={d}
      writeGate={gate}
      applyGate={ALLOWED}
      onReview={() => {}}
      openSection={search.section}
      openItem={search.item}
      anchor={search.anchor}
      selected={search.selected}
      onSearch={(patch) => setSearch((prev) => ({ ...prev, ...patch }))}
    />
  );
}

/** The canvas is laid out asynchronously by ELK; nothing is drawn until it lands. */
function element(name: RegExp) {
  return screen.findByRole('button', { name });
}

describe('RoutingTab', () => {
  it('names every element by what it is, what it connects and which state it is in', async () => {
    renderWithProviders(<Harness declaration={routed()} />);

    expect(
      await element(/^Address orders\.request\. routes ANYCAST to 1 queue\. declared and observed on the brokers\.$/),
    ).toBeInTheDocument();
    expect(
      await element(/^Queue orders\.request\. bound to address orders\.request, anycast\. declared and observed/),
    ).toBeInTheDocument();
    expect(
      await element(
        /^Divert audit-copy\. copies messages from address orders\.request to address orders\.audit\. declared and observed/,
      ),
    ).toBeInTheDocument();
    expect(
      await element(
        /^Bridge to-dr\. forwards queue orders\.request to address orders\.dr over dr-connector\. declared and observed/,
      ),
    ).toBeInTheDocument();
    expect(await element(/^Target on another broker orders\.dr\./)).toBeInTheDocument();
  });

  it('is traversed, opened and left with the keyboard alone', async () => {
    const user = userEvent.setup();
    renderWithProviders(<Harness declaration={routed()} />);

    const entry = await screen.findByRole('button', { name: 'Enter the routing graph' });
    await element(/^Address orders\.request\./);

    await user.tab();
    expect(entry).toHaveFocus();

    // Focus enters the graph, and lands on an element that names itself.
    await user.keyboard('{Enter}');
    await waitFor(() => expect(document.activeElement).not.toBe(entry));
    const first = document.activeElement as HTMLElement;
    expect(first.getAttribute('aria-label')).toMatch(/\. .+\. .+\./);
    // Entering opens the element it lands on, so the inspector says where the keyboard is.
    expect(await screen.findByRole('button', { name: /^Edit / })).toBeInTheDocument();
    expect(screen.queryByText('Nothing selected')).not.toBeInTheDocument();

    // Arrow keys move the one tab stop by direction, to the element in line — never down a column
    // for Right, never wrapping. Entry lands top-left, on the queue.
    const focused = () => document.activeElement?.getAttribute('aria-label') ?? '';
    expect(focused()).toMatch(/^Queue orders\.request\./);
    await user.keyboard('{ArrowRight}');
    expect(focused()).toMatch(/^Bridge to-dr\./);
    await user.keyboard('{ArrowLeft}');
    expect(focused()).toMatch(/^Queue orders\.request\./);
    await user.keyboard('{ArrowDown}');
    expect(focused()).toMatch(
      /^Divert audit-copy\. copies messages from address orders\.request to address orders\.audit\./,
    );

    // Enter opens the inspector on the focused element.
    await user.keyboard('{Enter}');
    expect(await screen.findByRole('button', { name: 'Edit divert audit-copy' })).toBeInTheDocument();

    // Escape leaves the graph and puts focus back where it entered.
    await user.keyboard('{Escape}');
    await waitFor(() => expect(entry).toHaveFocus());
    expect(await screen.findByText('Nothing selected')).toBeInTheDocument();
  });

  it('states an unapplied element and an undeclared one in words, and claims no origin', async () => {
    const d = routed({
      nodes: [
        {
          ...NODE_A,
          state: 'DRIFTED',
          findings: [
            finding({ kind: 'MISSING', section: 'DIVERT', key: 'audit-copy', detail: 'not on this node' }),
            finding({
              kind: 'UNDECLARED',
              section: 'DIVERT',
              key: 'stray-copy',
              detail: 'not declared',
              observed: { address: 'orders.request', 'forwarding-address': 'orders.spool' },
            }),
          ],
        },
      ],
    });
    renderWithProviders(<Harness declaration={d} />);

    // Both states are carried by the accessible name, which is text: removing
    // colour from the view removes nothing.
    expect(await element(/^Divert audit-copy\..*declared, not yet applied\.$/)).toBeInTheDocument();
    const stray = await element(
      /^Divert stray-copy\. moves messages from address orders\.request to address orders\.spool\. observed on the brokers, not declared\.$/,
    );

    fireEvent.click(stray);
    expect(
      await screen.findByText(/statement about this declaration, not about where the element came from/),
    ).toBeInTheDocument();
    // No origin is claimed for it anywhere (ADR-0065 D2).
    expect(screen.queryByText(/broker\.xml/)).toBeNull();
  });

  it('states the bound and how much is outside it rather than drawing a subset silently', async () => {
    const addresses = Array.from({ length: 160 }, (_, i) => ({
      name: `orders.${String(i).padStart(3, '0')}`,
      routingTypes: ['ANYCAST' as const],
      queues: [],
    }));
    const d = routed({
      document: {
        version: 1,
        addresses,
        addressSettings: [],
        securitySettings: [],
        diverts: [],
        bridges: [],
      },
    });
    renderWithProviders(<Harness declaration={d} />);

    expect(await screen.findByText(/160 elements, more than the 150 this canvas draws at once/)).toBeInTheDocument();
    expect(screen.getByText(/159 elements are not drawn/)).toBeInTheDocument();
    expect(
      screen.getByText(/Every one of them is on the Configuration screen's declaration sections/),
    ).toBeInTheDocument();
    // The operator chooses what the region is anchored on.
    expect(screen.getByRole('combobox', { name: 'Draw the region around' })).toBeInTheDocument();
  });

  it('adds a queue from the toolbar, in the address editor, with a queue row to name', async () => {
    const user = userEvent.setup();
    renderWithProviders(<Harness declaration={routed()} />);

    await user.click(await screen.findByRole('button', { name: 'Add queue' }));
    const drawer = await screen.findByRole('dialog', { name: 'New address' });
    expect(within(drawer).getByRole('textbox', { name: 'Queue name' })).toHaveValue('');
    expect(within(drawer).getByRole('checkbox', { name: 'Anycast' })).toBeChecked();
  });

  it('keeps "Add queue" visible and explains it when the operator may not write', async () => {
    renderWithProviders(
      <Harness declaration={routed()} gate={{ kind: 'blocked', reason: 'You do not have the permission.' }} />,
    );

    expect(await screen.findByRole('button', { name: 'Add queue' })).toBeDisabled();
    expect(screen.getByRole('button', { name: 'Why adding a queue is unavailable' })).toBeInTheDocument();
  });

  it('round-trips a divert transformer and its properties, and states what it cannot verify', async () => {
    const user = userEvent.setup();
    let saved: { document: { diverts: ConfigDivertView[] } } | null = null;
    const d = routed();
    server.use(
      http.put('*/api/v1/clusters/c1/artemis/config', async ({ request }) => {
        saved = (await request.json()) as typeof saved;
        return HttpResponse.json(d);
      }),
    );
    renderWithProviders(<Harness declaration={d} />);

    fireEvent.click(await element(/^Divert audit-copy\./));
    await user.click(await screen.findByRole('button', { name: /^Edit divert audit-copy$/ }));
    await user.click(await screen.findByRole('button', { name: 'Advanced configuration' }));

    const className = await screen.findByRole('textbox', { name: 'Transformer class' });
    expect(className).toHaveValue('com.example.Stamp');
    // The reachability statement is the field's own description, so it is read
    // out with the field rather than hanging off a hover (ADR-0049 D5).
    expect(className).toHaveAccessibleDescription(
      /cannot check that this class is on each broker’s classpath before the configuration is applied/,
    );
    expect(await screen.findByRole('textbox', { name: 'Value of transformer property tenant' })).toHaveValue('eu');

    await user.type(await screen.findByRole('textbox', { name: 'Add a transformer property' }), 'stamp');
    await user.type(screen.getByRole('textbox', { name: 'Value' }), 'yes');
    await user.click(screen.getByRole('button', { name: 'Add property' }));
    await user.click(screen.getByRole('button', { name: 'Save as revision 4' }));

    await waitFor(() => expect(saved).not.toBeNull());
    expect(saved!.document.diverts[0].transformerClassName).toBe('com.example.Stamp');
    expect(saved!.document.diverts[0].transformerProperties).toEqual({ tenant: 'eu', stamp: 'yes' });
  });

  it('round-trips a bridge transformer and its properties, and offers the nodes’ connector names', async () => {
    const user = userEvent.setup();
    let saved: { document: { bridges: ConfigBridgeView[] } } | null = null;
    const d = routed();
    server.use(
      http.get('*/api/v1/clusters/c1/artemis/config/connectors', () =>
        HttpResponse.json(
          paged([{ nodeId: 'n-a', nodeName: 'broker-1', names: ['dr-connector'], known: true, reason: null }]),
        ),
      ),
      http.get('*/api/v1/clusters/c1/artemis/config/bridge-credentials', () => HttpResponse.json(paged([]))),
      http.put('*/api/v1/clusters/c1/artemis/config', async ({ request }) => {
        saved = (await request.json()) as typeof saved;
        return HttpResponse.json(d);
      }),
    );
    renderWithProviders(<Harness declaration={d} />);

    fireEvent.click(await element(/^Bridge to-dr\./));
    await user.click(await screen.findByRole('button', { name: /^Edit bridge to-dr$/ }));

    expect(await screen.findByRole('textbox', { name: 'From queue' })).toHaveValue('orders.request');
    await user.click(await screen.findByRole('button', { name: 'Advanced configuration' }));

    const className = await screen.findByRole('textbox', { name: 'Transformer class' });
    expect(className).toHaveValue('com.example.Rewrite');
    expect(className).toHaveAccessibleDescription(/cannot check that this class is on each broker’s classpath/);
    expect(await screen.findByRole('textbox', { name: 'Value of transformer property region' })).toHaveValue('dr');

    await user.type(await screen.findByRole('textbox', { name: 'Add a transformer property' }), 'retries');
    await user.type(screen.getByRole('textbox', { name: 'Value' }), '3');
    await user.click(screen.getByRole('button', { name: 'Add property' }));
    await user.click(screen.getByRole('button', { name: 'Save as revision 4' }));

    await waitFor(() => expect(saved).not.toBeNull());
    expect(saved!.document.bridges[0].transformer).toEqual({
      className: 'com.example.Rewrite',
      properties: { region: 'dr', retries: '3' },
    });
    // The credential is a reference; no password is anywhere in what was saved.
    expect(JSON.stringify(saved)).not.toContain('password');
  });

  it("draws the canvas in the console's colour scheme, so its own controls and edges follow it", async () => {
    const { container } = renderWithProviders(<Harness declaration={routed()} />);

    await element(/^Address orders\.request\./);
    // The test harness runs the dark scheme; React Flow takes its mode from the same computed scheme.
    expect(container.querySelector('.react-flow')).toHaveClass('dark');
    expect(container.querySelector('.react-flow')).not.toHaveClass('light');
  });

  it('teaches what the builder draws when nothing is declared, and keeps the control that starts it', async () => {
    const user = userEvent.setup();
    renderWithProviders(
      <Harness
        declaration={declaration({
          document: { version: 1, addresses: [], addressSettings: [], securitySettings: [], diverts: [], bridges: [] },
          nodes: [],
        })}
      />,
    );

    expect(await screen.findByText('Nothing to draw yet')).toBeInTheDocument();
    expect(screen.getByText(/The builder draws this cluster's declared routing/)).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Add queue' }));
    expect(await screen.findByRole('dialog', { name: 'New address' })).toBeInTheDocument();
  });

  it('reads the selected element as terms and values, not as a table or a blob', async () => {
    renderWithProviders(<Harness declaration={routed()} />);

    fireEvent.click(await element(/^Divert audit-copy\./));

    const declared = await screen.findByRole('group', { name: 'audit-copy as declared' });
    expect(within(declared).getByText('from').tagName).toBe('DT');
    expect(within(declared).getByText('orders.request').tagName).toBe('DD');
    expect(within(declared).getByText('transformer tenant')).toBeInTheDocument();
  });

  it('says the bounded region as a notice announced politely, not an error', async () => {
    const addresses = Array.from({ length: 160 }, (_, i) => ({
      name: `orders.${String(i).padStart(3, '0')}`,
      routingTypes: ['ANYCAST' as const],
      queues: [],
    }));
    renderWithProviders(
      <Harness
        declaration={routed({
          document: { version: 1, addresses, addressSettings: [], securitySettings: [], diverts: [], bridges: [] },
        })}
      />,
    );

    await screen.findByText('Showing a bounded region of the graph');
    expect(screen.getAllByRole('status').map((s) => s.textContent)).toContainEqual(
      expect.stringContaining('Showing a bounded region of the graph'),
    );
  });
});
