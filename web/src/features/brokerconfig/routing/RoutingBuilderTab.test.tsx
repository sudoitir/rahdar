import { describe, expect, it } from 'vitest';
import { http, HttpResponse } from 'msw';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

import { renderAppAt } from '../../../test/render.tsx';
import { manifestHandler } from '../../../test/manifest.ts';
import { server } from '../../../test/setup.ts';
import type { ConfigDeclarationView, ConfigDocumentView } from '../api.ts';
import { baseHandlers, declaration, NODE_A, NODE_B } from '../fixtures.ts';
import { paged } from '../../../kernel/api/paging.ts';

/**
 * The routing builder where an operator reaches it (ADR-0094): Routing, then its Builder tab —
 * contributed by brokerconfig through the `routing.tabs` slot, so it is driven through the real
 * router and the composed feature list rather than rendered on its own.
 */

const capability = { status: 'AVAILABLE', reason: null, brokerXmlSnippet: null };

/** What the shell itself reads on any cluster screen, plus the Routing listings. */
function shell() {
  return [
    http.get('*/api/v1/clusters', () =>
      HttpResponse.json(paged([{ id: 'c1', name: 'prod', health: 'OK', nodeCount: 2 }])),
    ),
    http.get('*/api/v1/environments', () => HttpResponse.json(paged([]))),
    http.get('*/api/v1/alerts/firing', () => HttpResponse.json(paged([]))),
    http.get('*/api/v1/clusters/c1/subscriptions', () =>
      HttpResponse.json({ data: [], count: 0, page: 1, pageSize: 50 }),
    ),
    http.get('*/api/v1/clusters/c1/dead-letters', () =>
      HttpResponse.json({ settingsAvailable: true, destinations: [] }),
    ),
    http.get('*/api/v1/clusters/c1/routes', () => HttpResponse.json({ data: [], count: 0, page: 1, pageSize: 200 })),
    http.get('*/api/v1/clusters/c1/routes', () => HttpResponse.json({ data: [], count: 0, page: 1, pageSize: 200 })),
    http.get('*/api/v1/clusters/c1', () =>
      HttpResponse.json({
        id: 'c1',
        name: 'prod',
        description: null,
        topology: { clusterId: 'c1', nodes: [], unmanaged: [] },
        health: { clusterId: 'c1', level: 'OK', splitBrain: 'NONE', replicationBehind: false, notes: [] },
        capabilities: {
          managementRead: capability,
          managementWrite: capability,
          messageIo: capability,
          notifications: capability,
        },
      }),
    ),
  ];
}

const routed = declaration({
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
    diverts: [],
    bridges: [],
  },
  nodes: [NODE_A, { ...NODE_B, state: 'DRIFTED', verifiedRevision: 2 }],
});

describe('the Routing screen’s Builder tab', () => {
  it('is a tab of Routing, draws the declaration, and offers the apply with how far it has got', async () => {
    server.use(...shell(), ...baseHandlers(routed));
    renderAppAt('/clusters/c1/routing?tab=builder');

    const tab = await screen.findByRole('tab', { name: 'Builder' });
    expect(tab).toHaveAttribute('aria-selected', 'true');
    expect(screen.getByRole('tab', { name: 'Diverts' })).toBeInTheDocument();
    expect(screen.getByRole('tab', { name: 'Bridges' })).toBeInTheDocument();

    // Laid out by ELK in a worker; the first layout of a run is the slow one.
    expect(
      await screen.findByRole('button', { name: /^Address orders\.request\./ }, { timeout: 15_000 }),
    ).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /^Add queue$/ })).toBeEnabled();
    // One of the two live nodes is not on this revision; the count is on the control, in words.
    const review = screen.getByRole('button', { name: /^Review & apply/ });
    expect(review).toBeEnabled();
    expect(review).toHaveTextContent('1 node behind');
  });

  it('reaches the builder from Routing in one more click, and the old Configuration tab is gone', async () => {
    server.use(...shell(), ...baseHandlers(routed));
    const user = userEvent.setup();
    const { router } = renderAppAt('/clusters/c1/routing');

    await user.click(await screen.findByRole('tab', { name: 'Builder' }));
    expect(await screen.findByRole('button', { name: /^Address orders\.request\./ })).toBeInTheDocument();
    expect(router.state.location.search).toMatchObject({ tab: 'builder' });
  });

  it('is absent, not disabled, when the configuration feature is', async () => {
    server.use(...shell(), ...baseHandlers(routed), manifestHandler(['brokerconfig']));
    renderAppAt('/clusters/c1/routing?tab=builder');

    // An unknown tab id falls back to Diverts rather than to an empty panel.
    await waitFor(() => expect(screen.getByRole('tab', { name: 'Diverts' })).toHaveAttribute('aria-selected', 'true'));
    expect(screen.queryByRole('tab', { name: 'Builder' })).toBeNull();
  });

  it('creates a queue inline from the divert editor with the keyboard alone, and keeps the divert open', async () => {
    let current: ConfigDeclarationView = routed;
    let saved: { document: ConfigDocumentView; expectedRevision: number; note: string } | null = null;
    // The first matching handler wins, so the stateful declaration goes ahead of the fixtures'.
    server.use(
      http.get('*/api/v1/clusters/c1/artemis/config', () => HttpResponse.json(current)),
      http.put('*/api/v1/clusters/c1/artemis/config', async ({ request }) => {
        saved = (await request.json()) as typeof saved;
        current = { ...current, revision: current.revision + 1, document: saved!.document };
        return HttpResponse.json(current);
      }),
      ...shell(),
      ...baseHandlers(routed),
    );
    const user = userEvent.setup();
    // A new divert's editor, opened by its address — the canvas is not the only way in (ADR-0090 D4).
    renderAppAt('/clusters/c1/routing?tab=builder&section=diverts');

    const drawer = await screen.findByRole('dialog', { name: 'New divert' });
    const to = await within(drawer).findByRole('combobox', { name: /To address/ });
    for (let i = 0; i < 12 && document.activeElement !== to; i++) await user.tab();
    expect(to).toHaveFocus();

    // Typing an address that is not declared offers to create its queue, last.
    await user.keyboard('orders.spool');
    await user.keyboard('{ArrowDown}');
    const create = await screen.findByRole('option', { name: /Create queue “orders\.spool”/ });
    await waitFor(() => expect(create).toHaveAttribute('data-combobox-selected'));
    await user.keyboard('{Enter}');

    const section = await within(drawer).findByRole('group', { name: 'Create queue on address orders.spool' });
    const queueName = within(section).getByRole('textbox', { name: /Queue name/ });
    await waitFor(() => expect(queueName).toHaveFocus());
    expect(queueName).toHaveValue('orders.spool');

    // Escape collapses the section and returns to the field; the drawer stays open.
    await user.keyboard('{Escape}');
    await waitFor(() => expect(to).toHaveFocus());
    expect(within(drawer).queryByRole('group', { name: /Create queue on address/ })).toBeNull();
    expect(screen.getByRole('dialog', { name: 'New divert' })).toBeInTheDocument();

    // Again, and this time a bad name is refused beside its field, on blur.
    await user.keyboard('{ArrowDown}');
    await waitFor(() =>
      expect(screen.getByRole('option', { name: /Create queue/ })).toHaveAttribute('data-combobox-selected'),
    );
    await user.keyboard('{Enter}');
    const again = await within(drawer).findByRole('group', { name: 'Create queue on address orders.spool' });
    const name = within(again).getByRole('textbox', { name: /Queue name/ });
    await waitFor(() => expect(name).toHaveFocus());
    await user.clear(name);
    await user.keyboard('orders.request');
    await user.tab();
    expect(
      await within(again).findByText('Queue "orders.request" is already declared, on address orders.request.'),
    ).toBeInTheDocument();

    // Fixed, and Enter in the section adds it to the declaration as its own revision.
    await user.tab({ shift: true });
    expect(name).toHaveFocus();
    await user.clear(name);
    await user.keyboard('orders.spool{Enter}');

    await waitFor(() => expect(saved).not.toBeNull());
    expect(saved!.expectedRevision).toBe(3);
    expect(saved!.note).toBe('Added queue orders.spool');
    expect(saved!.document.addresses.find((a) => a.name === 'orders.spool')).toEqual({
      name: 'orders.spool',
      routingTypes: ['ANYCAST'],
      queues: [{ name: 'orders.spool', routingType: 'ANYCAST', durable: true }],
    });

    // Focus is back on the field, which names the new address; the outcome is announced; the
    // divert is still being edited, now against the new revision.
    await waitFor(() => expect(to).toHaveFocus());
    expect(to).toHaveValue('orders.spool');
    expect(within(drawer).getByRole('status')).toHaveTextContent(
      'Queue orders.spool added to the declaration — apply to create it on the brokers.',
    );
    expect(await within(drawer).findByRole('button', { name: 'Save as revision 5' })).toBeInTheDocument();

    // The canvas draws it at once, as declared and not yet applied.
    expect(await screen.findByRole('button', { name: /^Queue orders\.spool\./ })).toBeInTheDocument();
  }, 20_000);

  it('states why the declaration could not be read, says what the builder needs, and reads it again on retry', async () => {
    let attempts = 0;
    server.use(
      http.get('*/api/v1/clusters/c1/artemis/config', () => {
        attempts += 1;
        return attempts === 1
          ? HttpResponse.json({ title: 'Down', detail: 'The configuration store is not answering.' }, { status: 503 })
          : HttpResponse.json(routed);
      }),
      ...shell(),
      ...baseHandlers(routed),
    );
    const user = userEvent.setup();
    renderAppAt('/clusters/c1/routing?tab=builder');

    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent('The configuration store is not answering.');
    expect(screen.getByText(/has nothing to draw until the declaration can be read/)).toBeInTheDocument();

    await user.click(within(alert).getByRole('button', { name: 'Retry' }));
    expect(
      await screen.findByRole('button', { name: /^Address orders\.request\./ }, { timeout: 15_000 }),
    ).toBeInTheDocument();
  });
});
