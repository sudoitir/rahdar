import { describe, expect, it, vi } from 'vitest';
import { http, HttpResponse } from 'msw';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

import { renderAppAt } from '../../test/render.tsx';
import { server } from '../../test/setup.ts';
import type { ConfigApplyOutcomeView } from './api.ts';
import { baseHandlers, declaration, halted, NODE_A, NODE_B, plan } from './fixtures.ts';
import { paged } from '../../kernel/api/paging.ts';
import { holdButton } from '../../test/hold.ts';

/**
 * The Configuration screen as an operator walks it (ADR-0087): one address, one
 * status bar, and an apply that happens over the rows it changes. Driven through
 * the real router, because the screen no longer has a separate apply address and
 * "it is all one screen" is the claim under test.
 */

const capability = { status: 'AVAILABLE', reason: null, brokerXmlSnippet: null };

/** What the shell itself reads on any cluster screen. */
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

/** broker-2 differs on the declared address setting; broker-1 matches the revision. */
const drifted = declaration({
  nodes: [
    { ...NODE_A, state: 'IN_SYNC', verifiedRevision: 3 },
    {
      ...NODE_B,
      state: 'DRIFTED',
      verifiedRevision: null,
      findings: [
        {
          kind: 'DIVERGENT',
          section: 'ADDRESS_SETTING',
          key: 'orders.#',
          detail: 'Replace address setting orders.#',
          declared: { addressFullMessagePolicy: 'PAGE' },
          observed: { addressFullMessagePolicy: 'DROP' },
        },
      ],
    },
  ],
});

function applyHandler(onReal: () => ConfigApplyOutcomeView, seen?: (body: unknown, dryRun: string | null) => void) {
  return http.post('*/api/v1/clusters/c1/artemis/config/apply', async ({ request }) => {
    const url = new URL(request.url);
    const body = await request.json();
    seen?.(body, url.searchParams.get('dryRun'));
    if (url.searchParams.get('dryRun') === 'true') return HttpResponse.json(plan());
    return HttpResponse.json(onReal());
  });
}

async function open(path = '/clusters/c1/configuration') {
  const view = renderAppAt(path);
  await screen.findByText(/Revision 3/);
  return view;
}

describe('the configuration screen', () => {
  it('states the revision and how far it has got, and names the item that differs on its own row', async () => {
    server.use(
      ...shell(),
      ...baseHandlers(drifted),
      applyHandler(() => plan()),
    );
    await open('/clusters/c1/configuration?tab=addressSettings');

    // The sentence a save produces: a revision exists, and a broker has not been written.
    expect(await screen.findByText('Revision 3 — applied to 1 of 2 live nodes')).toBeInTheDocument();
    // The item's live state is on the item's own row, in words, with the difference.
    const row = (await screen.findByRole('button', { name: 'Apply this address setting orders.#' })).closest('tr')!;
    expect(within(row).getByText(/differs on broker-2/)).toBeInTheDocument();
    // declared → observed, on the row, so the comparison needs no second screen.
    expect(within(row).getAllByText(/PAGE/).length).toBeGreaterThanOrEqual(1);
    expect(within(row).getByText('DROP').closest('dd')).toHaveTextContent('PAGE → DROP');
  });

  it('reviews and applies over the rows, and renders a partial run as partial', async () => {
    server.use(...shell(), ...baseHandlers(drifted), applyHandler(halted));
    const user = userEvent.setup();
    await open();

    await user.click(await screen.findByRole('button', { name: 'Review & apply' }));
    const drawer = await screen.findByRole('dialog', { name: /Review & apply/ });
    await within(drawer).findByText(/Would apply 2 steps/);

    await user.click(within(drawer).getByRole('checkbox', { name: /I understand: message loss policy on broker-1/ }));
    await user.click(within(drawer).getByRole('button', { name: 'Continue to confirm' }));
    await holdButton(within(drawer).getByRole('button', { name: 'Apply to 2 nodes, canary first' }));

    // Partial is the outcome that matters most, and it is stated before any row.
    expect(await within(drawer).findByText('Halted — applied to some nodes and not others')).toBeInTheDocument();
    expect(within(drawer).getByText(/Nothing was rolled back/)).toBeInTheDocument();
    expect(within(drawer).getAllByText('not attempted').length).toBeGreaterThanOrEqual(1);
  });

  it('folds away the steps the brokers already agree with, and says how many', async () => {
    // A plan is mostly steps that write nothing; the writes are what is reviewed.
    const withAlready = () => {
      const p = plan();
      const already = {
        ...p.nodes[0].steps[0],
        stepId: 'ADDRESS:orders.request:ADD',
        section: 'ADDRESS',
        key: 'orders.request',
        op: 'ADD',
        description: 'Address orders.request exists',
        status: 'ALREADY' as const,
      };
      return {
        ...p,
        nodes: p.nodes.map((n) => ({ ...n, steps: [...n.steps, already] })),
      };
    };
    server.use(
      ...shell(),
      ...baseHandlers(drifted),
      http.post('*/api/v1/clusters/c1/artemis/config/apply', () => HttpResponse.json(withAlready())),
    );
    const user = userEvent.setup();
    await open();

    await user.click(await screen.findByRole('button', { name: 'Review & apply' }));
    const drawer = await screen.findByRole('dialog', { name: /Review & apply/ });

    // Hidden, but counted and one activation away — never silently dropped.
    await within(drawer).findByText(/Would apply/);
    const show = await within(drawer).findByRole('button', { name: 'Show the 2 already as declared' });
    expect(within(drawer).queryByText('Address orders.request exists')).not.toBeInTheDocument();
    await user.click(show);
    expect(within(drawer).getAllByText('Address orders.request exists').length).toBeGreaterThanOrEqual(1);
    await user.click(within(drawer).getByRole('button', { name: 'Hide the 2 already as declared' }));
    expect(within(drawer).queryByText('Address orders.request exists')).not.toBeInTheDocument();
  });

  it('applies one row on its own, naming that item’s steps and nothing else', async () => {
    const seen = vi.fn();
    server.use(
      ...shell(),
      ...baseHandlers(drifted),
      applyHandler(() => plan({ dryRun: false, outcome: 'APPLIED' }), seen),
    );
    const user = userEvent.setup();
    await open('/clusters/c1/configuration?tab=addressSettings');

    await user.click(await screen.findByRole('button', { name: 'Apply this address setting orders.#' }));
    const drawer = await screen.findByRole('dialog', { name: /Review & apply address setting orders.#/ });
    await within(drawer).findByText(/Would apply 2 steps/);

    await waitFor(() => expect(seen).toHaveBeenCalled());
    expect(seen.mock.calls.at(-1)![0]).toMatchObject({
      stepIds: expect.arrayContaining(['ADDRESS_SETTING:orders.#:REPLACE', 'ADDRESS_SETTING:orders.#:ADD']),
    });
    // Only that item's steps: nothing from another section rides along.
    expect((seen.mock.calls.at(-1)![0] as { stepIds: string[] }).stepIds.every((id) => id.includes('orders.#'))).toBe(
      true,
    );
  });

  it('refuses an apply with no node selected instead of sending an empty target set', async () => {
    const seen = vi.fn();
    server.use(
      ...shell(),
      ...baseHandlers(drifted),
      applyHandler(() => plan(), seen),
    );
    const user = userEvent.setup();
    await open();

    await user.click(await screen.findByRole('button', { name: 'Review & apply' }));
    const drawer = await screen.findByRole('dialog', { name: /Review & apply/ });
    await within(drawer).findByText(/Would apply 2 steps/);

    await user.click(within(drawer).getByRole('checkbox', { name: 'broker-1' }));
    await user.click(within(drawer).getByRole('checkbox', { name: 'broker-2' }));

    // The server reads an empty node set as "every node", so an empty selection
    // must never be sent: it would confirm "0 nodes" and rewrite the cluster.
    expect(await within(drawer).findByText(/Select at least one node/)).toBeInTheDocument();
    expect(within(drawer).queryByRole('button', { name: 'Continue to confirm' })).not.toBeInTheDocument();
    expect(seen.mock.calls.every(([body]) => (body as { nodeIds?: string[] }).nodeIds?.length !== 0)).toBe(true);
  });

  it('announces the outcome in a live region that holds the announcement and not the whole form', async () => {
    server.use(...shell(), ...baseHandlers(drifted), applyHandler(halted));
    const user = userEvent.setup();
    await open();

    await user.click(await screen.findByRole('button', { name: 'Review & apply' }));
    const drawer = await screen.findByRole('dialog', { name: /Review & apply/ });
    await within(drawer).findByText(/Would apply 2 steps/);

    await user.click(within(drawer).getByRole('checkbox', { name: /I understand: message loss policy on broker-1/ }));
    await user.click(within(drawer).getByRole('button', { name: 'Continue to confirm' }));
    await holdButton(within(drawer).getByRole('button', { name: 'Apply to 2 nodes, canary first' }));
    await within(drawer).findByText('Halted — applied to some nodes and not others');

    // One small region carrying the stage, so a screen reader is told the
    // outcome rather than re-read the plan every time a checkbox moves.
    const regions = [...drawer.querySelectorAll('[aria-live]')];
    expect(regions.some((r) => /Halted partway/.test(r.textContent ?? ''))).toBe(true);
    // No region wraps the form: ticking a hazard must not re-announce the plan.
    expect(regions.every((r) => !/Hazards|Canary|Nodes/.test(r.textContent ?? ''))).toBe(true);
  });

  it('is operable from the keyboard: escape leaves the drawer and focus returns to the control that opened it', async () => {
    server.use(
      ...shell(),
      ...baseHandlers(drifted),
      applyHandler(() => plan()),
    );
    const user = userEvent.setup();
    await open();

    const trigger = await screen.findByRole('button', { name: 'Review & apply' });
    trigger.focus();
    await user.keyboard('{Enter}');
    const drawer = await screen.findByRole('dialog', { name: /Review & apply/ });
    await within(drawer).findByText(/Would apply 2 steps/);

    await user.keyboard('{Escape}');
    await waitFor(() => expect(screen.queryByRole('dialog', { name: /Review & apply/ })).not.toBeInTheDocument());
    await waitFor(() => expect(trigger).toHaveFocus());
  });

  it('says a save is not applied yet, and offers the review from the same screen', async () => {
    let revision = 3;
    server.use(
      ...shell(),
      // Ahead of the base handlers: the first match wins, and this one moves.
      http.get('*/api/v1/clusters/c1/artemis/config', () =>
        HttpResponse.json(
          revision === 3
            ? drifted
            : declaration({
                revision: 4,
                nodes: [
                  { ...NODE_A, state: 'DRIFTED', verifiedRevision: 3 },
                  { ...NODE_B, state: 'DRIFTED', verifiedRevision: 3 },
                ],
              }),
        ),
      ),
      http.put('*/api/v1/clusters/c1/artemis/config', () => {
        revision = 4;
        return HttpResponse.json(declaration({ revision: 4 }), { status: 201 });
      }),
      ...baseHandlers(drifted),
      applyHandler(() => plan()),
    );
    const user = userEvent.setup();
    await open('/clusters/c1/configuration?tab=addressSettings');

    await user.click(await screen.findByRole('button', { name: 'Edit address setting orders.#' }));
    const editor = await screen.findByRole('dialog', { name: /Address setting orders/ });
    await user.click(within(editor).getByRole('button', { name: /Save as revision 4/ }));

    // The screen the editor returns to is the one that says no broker has it yet.
    expect(await screen.findByText('Revision 4 — applied to 0 of 2 live nodes')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Review & apply' })).toBeEnabled();
  });
});
