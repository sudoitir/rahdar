import { useState } from 'react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { http, HttpResponse } from 'msw';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { createMemoryHistory, createRootRoute, createRouter, RouterProvider } from '@tanstack/react-router';

import { renderWithProviders } from '../../test/render.tsx';
import { server } from '../../test/setup.ts';
import type { ConfigApplyOutcomeView, ConfigDeclarationView } from './api.ts';
import { cluster, declaration, halted, meHandler, NODE_A, NODE_B, plan } from './fixtures.ts';
import { ReviewApplyDrawer, type ApplyScope } from './ReviewApplyDrawer.tsx';
import { holdButton } from '../../test/hold.ts';

/** What the drawer asked the server, in order. */
type Call = { dryRun: boolean; override: boolean; body: Record<string, unknown> };

let calls: Call[];
let real: () => Response | Promise<Response>;
let dry: () => ConfigApplyOutcomeView;

beforeEach(() => {
  calls = [];
  dry = () => plan();
  real = () => HttpResponse.json(plan({ dryRun: false, outcome: 'APPLIED' }));
  server.use(
    meHandler(),
    http.get('*/api/v1/clusters/c1', () => HttpResponse.json(cluster())),
    http.post('*/api/v1/clusters/c1/artemis/config/apply', async ({ request }) => {
      const url = new URL(request.url);
      const dryRun = url.searchParams.get('dryRun') === 'true';
      calls.push({
        dryRun,
        override: url.searchParams.get('override') === 'true',
        body: (await request.json()) as Record<string, unknown>,
      });
      return dryRun ? HttpResponse.json(dry()) : real();
    }),
  );
});

function Harness({
  declared,
  scope,
  onClose,
}: Readonly<{ declared: ConfigDeclarationView; scope: ApplyScope | null; onClose: () => void }>) {
  const [opened, setOpened] = useState(true);
  return (
    <>
      <button onClick={() => setOpened(true)}>Reopen</button>
      <ReviewApplyDrawer
        declaration={declared}
        scope={scope}
        opened={opened}
        onClose={() => {
          setOpened(false);
          onClose();
        }}
      />
    </>
  );
}

function open(declared: ConfigDeclarationView = declaration(), scope: ApplyScope | null = null) {
  const onClose = vi.fn();
  const rootRoute = createRootRoute({
    component: () => <Harness declared={declared} scope={scope} onClose={onClose} />,
  });
  const router = createRouter({ routeTree: rootRoute, history: createMemoryHistory({ initialEntries: ['/'] }) });
  renderWithProviders(<RouterProvider router={router} />);
  return { user: userEvent.setup(), onClose };
}

const drawer = async (name: RegExp | string = /Review & apply/) => within(await screen.findByRole('dialog', { name }));

/** Opens the drawer and waits for the first plan to be on screen. */
async function planned(declared?: ConfigDeclarationView, scope: ApplyScope | null = null) {
  const opened = open(declared, scope);
  const d = await drawer();
  await d.findByText(/Would apply|Nothing to do/);
  return { ...opened, d };
}

/** A paragraph by its whole text, for a sentence split over several elements. */
const paragraph = (text: RegExp) => screen.getByText((_, el) => el?.tagName === 'P' && text.test(el.textContent ?? ''));

const announcement = () => document.querySelector('[aria-live="polite"]')!.textContent;

const withPlan = (over: Partial<ConfigApplyOutcomeView['plan']>) => {
  const p = plan();
  return plan({ plan: { ...p.plan, ...over } });
};

async function acknowledge(user: ReturnType<typeof userEvent.setup>, d: ReturnType<typeof within>) {
  await user.click(d.getByRole('checkbox', { name: /I understand: message loss policy on broker-1/ }));
}

async function toConfirm(user: ReturnType<typeof userEvent.setup>, d: ReturnType<typeof within>) {
  await acknowledge(user, d);
  await user.click(d.getByRole('button', { name: 'Continue to confirm' }));
  return d.findByRole('button', { name: 'Apply to 2 nodes, canary first' });
}

describe('ReviewApplyDrawer: the plan', () => {
  it('plans with a dry run as soon as it opens, without acknowledgements or a hash', async () => {
    const { d } = await planned();

    expect(calls).toHaveLength(1);
    expect(calls[0]).toMatchObject({
      dryRun: true,
      override: true,
      body: { revision: 3, removeUndeclared: false, acknowledgedHazards: [] },
    });
    expect(calls[0].body.nodeIds).toBeUndefined();
    expect(calls[0].body.expectedPlanHash).toBeUndefined();
    expect(screen.getByRole('dialog', { name: 'Review & apply revision 3' })).toBeInTheDocument();
    expect(paragraph(/^2 steps · 2 nodes · canary broker-1 · 1 hazard, 1 High$/)).toBeInTheDocument();
    expect(d.getByText('1 High hazard to acknowledge')).toBeInTheDocument();
    expect(announcement()).toBe('Plan ready: 2 steps on 2 nodes. Nothing has been written.');
  });

  it('does not plan a cluster with nothing declared', async () => {
    open(declaration({ declared: false, revision: 0 }));
    await drawer();

    await new Promise((r) => setTimeout(r, 50));
    expect(calls).toHaveLength(0);
  });

  it('says a plan could not be made, and that nothing changed', async () => {
    server.use(
      http.post('*/api/v1/clusters/c1/artemis/config/apply', () =>
        HttpResponse.json({ title: 'Invalid', detail: 'The declaration does not validate.' }, { status: 422 }),
      ),
    );
    open();
    const d = await drawer();

    const alert = await d.findByRole('alert');
    expect(alert).toHaveTextContent('Invalid');
    expect(alert).toHaveTextContent('The declaration does not validate.');
    expect(d.getByText('Fix the declaration and come back; nothing was changed.')).toBeInTheDocument();
  });

  it('lays the plan out as sections under the drawer title, so a screen reader can jump between them', async () => {
    const { d } = await planned();

    expect(d.getByRole('heading', { level: 3, name: 'Plan' })).toBeInTheDocument();
    expect(d.getByRole('heading', { level: 3, name: 'Hazards (1) — 1 High' })).toBeInTheDocument();
    // The nodes of the plan are one level further down, never a skipped level.
    expect(d.getAllByRole('heading', { level: 4 }).length).toBeGreaterThan(0);
  });

  it('offers to plan again when the plan failed for a reason trying again can fix', async () => {
    let attempts = 0;
    server.use(
      http.post('*/api/v1/clusters/c1/artemis/config/apply', () => {
        attempts += 1;
        return attempts === 1
          ? HttpResponse.json({ title: 'Down', detail: 'No node answered the plan.' }, { status: 503 })
          : HttpResponse.json(plan());
      }),
    );
    const { user } = open();
    const d = await drawer();

    const alert = await d.findByRole('alert');
    expect(alert).toHaveTextContent('No node answered the plan.');
    await user.click(within(alert).getByRole('button', { name: 'Retry' }));
    await d.findByText(/Would apply/);
    expect(attempts).toBe(2);
  });

  it('says so when every node already matches, and offers no way to continue', async () => {
    dry = () => withPlan({ stepCount: 0, hazards: [] });
    const { d } = await planned();

    expect(d.getByText('No hazards.')).toBeInTheDocument();
    expect(d.getByText('Nothing to apply: every targeted node already matches revision 3.')).toBeInTheDocument();
    expect(d.getByRole('button', { name: 'Continue to confirm' })).toBeDisabled();
    expect(paragraph(/^0 steps · 2 nodes · canary broker-1 · no hazards$/)).toBeInTheDocument();
  });

  it('lists what the plan noticed and did not act on', async () => {
    dry = () =>
      withPlan({
        findings: [
          {
            kind: 'UNDECLARED',
            nodeId: 'n-a',
            nodeName: 'broker-1',
            section: 'DIVERT',
            key: 'stray',
            detail: 'not declared',
          },
        ],
      });
    const { d } = await planned();

    expect(d.getByText('Noticed, not acted on')).toBeInTheDocument();
    expect(d.getByText('broker-1 · divert stray — not declared')).toBeInTheDocument();
  });

  it('says the backups will inherit through replication', async () => {
    const one = declaration({
      nodes: [NODE_A, NODE_B, { ...NODE_B, nodeId: 'n-c', nodeName: 'backup-1', live: false }],
    });
    const { d } = await planned(one);
    expect(d.getByText('backup-1 is a backup and will inherit through replication.')).toBeInTheDocument();
  });

  it('names several backups in the plural', async () => {
    const many = declaration({
      nodes: [
        NODE_A,
        { ...NODE_B, nodeId: 'n-c', nodeName: 'backup-1', live: false },
        { ...NODE_B, nodeId: 'n-d', nodeName: 'backup-2', live: false },
      ],
    });
    const { d } = await planned(many);
    expect(d.getByText('backup-1, backup-2 are backups and will inherit through replication.')).toBeInTheDocument();
  });

  it('plans again on request', async () => {
    const { user, d } = await planned();
    await user.click(d.getByRole('button', { name: 'Plan again' }));
    await waitFor(() => expect(calls).toHaveLength(2));
  });

  it('offers, and re-plans with, removing what Rahdar did not apply', async () => {
    const { user, d } = await planned();
    await user.click(d.getByRole('switch', { name: /Also remove settings and diverts Rahdar did not apply/ }));

    await waitFor(() => expect(calls).toHaveLength(2));
    expect(calls[1].body.removeUndeclared).toBe(true);
  });

  it('scopes an apply to one item: says so, sends its steps, and offers no removal of the rest', async () => {
    const { d } = await planned(declaration(), {
      label: 'address setting orders.#',
      stepIds: ['ADDRESS_SETTING:orders.#:REPLACE'],
    });

    expect(screen.getByRole('dialog', { name: 'Review & apply address setting orders.#' })).toBeInTheDocument();
    expect(d.getByText(/Scoped to address setting orders\.# on every targeted node/)).toBeInTheDocument();
    expect(d.queryByRole('switch', { name: /Also remove settings/ })).not.toBeInTheDocument();
    expect(calls[0].body.stepIds).toEqual(['ADDRESS_SETTING:orders.#:REPLACE']);
  });
});

describe('ReviewApplyDrawer: nodes and canary', () => {
  it('re-plans on the nodes that stay ticked, and sends only those', async () => {
    const { user, d } = await planned();
    await user.click(d.getByRole('checkbox', { name: 'broker-1' }));

    await waitFor(() => expect(calls).toHaveLength(2));
    expect(calls[1].body.nodeIds).toEqual(['n-b']);
    await d.findByText(/1 node ·/);
  });

  it('sends every node as none when all are ticked again', async () => {
    const { user, d } = await planned();
    await user.click(d.getByRole('checkbox', { name: 'broker-1' }));
    await waitFor(() => expect(calls).toHaveLength(2));
    await user.click(d.getByRole('checkbox', { name: 'broker-1' }));

    await waitFor(() => expect(calls).toHaveLength(3));
    expect(calls[2].body.nodeIds).toBeUndefined();
  });

  it('sends the chosen canary, and drops it when its node is unticked', async () => {
    const { user, d } = await planned();
    await user.click(d.getByRole('combobox', { name: /^Canary/ }));
    await user.click(await screen.findByRole('option', { name: 'broker-2' }));

    await waitFor(() => expect(calls).toHaveLength(2));
    expect(calls[1].body.canaryNodeId).toBe('n-b');

    await user.click(d.getByRole('checkbox', { name: 'broker-2' }));
    await waitFor(() => expect(calls).toHaveLength(3));
    expect(calls[2].body.canaryNodeId).toBeUndefined();
  });

  it('announces that nothing is planned when no node is selected', async () => {
    const { user, d } = await planned();
    await user.click(d.getByRole('checkbox', { name: 'broker-1' }));
    await user.click(d.getByRole('checkbox', { name: 'broker-2' }));

    expect(await d.findByText(/Select at least one node/)).toBeInTheDocument();
    expect(announcement()).toBe('No node is selected, so nothing is planned.');
  });
});

describe('ReviewApplyDrawer: confirming', () => {
  it('arms the apply only after every High hazard is acknowledged and the cluster name typed', async () => {
    const { user, d } = await planned();

    await user.click(d.getByRole('button', { name: 'Continue to confirm' }));
    await d.findByText('Confirm');
    expect(d.getByText('1 High hazard not yet acknowledged above.')).toBeInTheDocument();
    expect(d.getByRole('button', { name: 'Apply to 2 nodes, canary first' })).toBeDisabled();
  });

  it('confirms with the nodes named, then applies for real naming the plan it was shown', async () => {
    const { user, d } = await planned();
    await toConfirm(user, d);

    expect(d.getByText(/2 management writes on broker-1, broker-2, canary first/)).toBeInTheDocument();
    await holdButton(d.getByRole('button', { name: 'Apply to 2 nodes, canary first' }));

    await d.findByText('Applied to all 2 live nodes');
    expect(announcement()).toBe('Applied to every targeted node.');
    const run = calls.at(-1)!;
    expect(run).toMatchObject({
      dryRun: false,
      override: false,
      body: {
        revision: 3,
        expectedPlanHash: 'abc123',
        acknowledgedHazards: ['MESSAGE_LOSS_POLICY:n-a:ADDRESS_SETTING:orders.#'],
      },
    });
  });

  it('goes back to the plan from the confirmation', async () => {
    const { user, d } = await planned();
    await toConfirm(user, d);
    await user.click(d.getByRole('button', { name: 'Back to the plan' }));

    expect(await d.findByRole('button', { name: 'Continue to confirm' })).toBeInTheDocument();
    expect(d.queryByText('Confirm')).not.toBeInTheDocument();
  });

  it('says the cluster moved when the confirming plan differs, and drops the acknowledgements', async () => {
    const { user, d } = await planned();
    await acknowledge(user, d);
    dry = () => withPlan({ planHash: 'changed' });
    await user.click(d.getByRole('button', { name: 'Continue to confirm' }));

    expect(await d.findByText('The cluster moved — this is a new plan')).toBeInTheDocument();
    expect(d.queryByText('Confirm')).not.toBeInTheDocument();
    expect(d.getByRole('checkbox', { name: /I understand: message loss policy on broker-1/ })).not.toBeChecked();
  });

  it('requires an override for a plan over the step cap, and sends it', async () => {
    dry = () => plan({ overCap: true, stepCap: 1 });
    const { user, d } = await planned();
    expect(d.getByText(/over the step cap of 1/)).toBeInTheDocument();
    await acknowledge(user, d);
    await user.click(d.getByRole('button', { name: 'Continue to confirm' }));
    await d.findByRole('button', { name: 'Apply to 2 nodes, canary first' });

    expect(d.getByText('The plan has 2 steps, over the cap of 1. Override it above to continue.')).toBeInTheDocument();
    expect(d.getByRole('button', { name: 'Apply to 2 nodes, canary first' })).toBeDisabled();

    await user.click(d.getByRole('button', { name: 'Back to the plan' }));
    await user.click(d.getByRole('switch', { name: /Override the step cap \(2 steps, cap 1\)/ }));
    await user.click(d.getByRole('button', { name: 'Continue to confirm' }));
    await holdButton(d.getByRole('button', { name: 'Apply to 2 nodes, canary first' }));

    await waitFor(() => expect(calls.at(-1)).toMatchObject({ dryRun: false, override: true }));
  });

  it('shows the running stage while the apply is in flight', async () => {
    let release!: () => void;
    const gate = new Promise<void>((r) => (release = r));
    real = async () => {
      await gate;
      return HttpResponse.json(plan({ dryRun: false, outcome: 'APPLIED' }));
    };
    const { user, d } = await planned();
    await toConfirm(user, d);
    await holdButton(d.getByRole('button', { name: 'Apply to 2 nodes, canary first' }));

    await waitFor(() => expect(announcement()).toBe('Applying, canary first.'));
    release();
    await d.findByText('Applied to all 2 live nodes');
  });

  it.each([
    ['https://rahdar/problems/plan-changed', /^The cluster moved since this plan was made\. Plan again and review it/],
    ['https://rahdar/problems/apply-in-progress', /^Wait for it to finish, then plan again\./],
  ])('explains a refusal of type %s', async (type, hint) => {
    real = () => HttpResponse.json({ type, title: 'Refused', detail: 'Refused by the server.' }, { status: 409 });
    const { user, d } = await planned();
    await toConfirm(user, d);
    await holdButton(d.getByRole('button', { name: 'Apply to 2 nodes, canary first' }));

    const alert = await d.findByRole('alert');
    expect(alert).toHaveTextContent('Refused by the server.');
    expect(d.getByText(hint)).toBeInTheDocument();
  });

  it('gives an unknown refusal no hint', async () => {
    real = () => HttpResponse.json({ title: 'Broken', detail: 'Something else.' }, { status: 500 });
    const { user, d } = await planned();
    await toConfirm(user, d);
    await holdButton(d.getByRole('button', { name: 'Apply to 2 nodes, canary first' }));

    const alert = await d.findByRole('alert');
    expect(alert).toHaveTextContent('Something else.');
    expect(alert).not.toHaveTextContent('Plan again');
  });
});

/** Waits for the confirmation's button, which a gated control leaves disabled. */
async function typeName(d: ReturnType<typeof within>) {
  await d.findByRole('button', { name: 'Apply to 2 nodes, canary first' });
}

describe('ReviewApplyDrawer: the gate', () => {
  it('is closed while broker.xml owns the configuration', async () => {
    const { user, d } = await planned(declaration({ applyMode: 'CONFIG_MANAGED' }));
    await acknowledge(user, d);
    await user.click(d.getByRole('button', { name: 'Continue to confirm' }));
    await typeName(d);

    expect(d.getAllByText(/owned by configuration management/).length).toBeGreaterThan(0);
    expect(d.getByRole('button', { name: 'Apply to 2 nodes, canary first' })).toBeDisabled();
  });

  it('is closed for someone without the permission, and says which', async () => {
    server.use(meHandler(['config:read']));
    const { user, d } = await planned();
    await acknowledge(user, d);
    await user.click(d.getByRole('button', { name: 'Continue to confirm' }));
    await typeName(d);

    expect(
      (await d.findAllByText(/You do not have the "Apply declared configuration" permission/)).length,
    ).toBeGreaterThan(0);
  });

  it('is closed when the broker connection is known not to write', async () => {
    server.use(http.get('*/api/v1/clusters/c1', () => HttpResponse.json(cluster('UNAVAILABLE'))));
    const { user, d } = await planned();
    await acknowledge(user, d);
    await user.click(d.getByRole('button', { name: 'Continue to confirm' }));
    await typeName(d);

    expect((await d.findAllByText(/the broker refused a write/)).length).toBeGreaterThan(0);
    expect(d.getByRole('button', { name: 'Apply to 2 nodes, canary first' })).toBeDisabled();
  });

  it('is open but says so when the connection has not been shown to write yet', async () => {
    server.use(http.get('*/api/v1/clusters/c1', () => HttpResponse.json(cluster('UNKNOWN'))));
    const { user, d } = await planned();
    await toConfirm(user, d);

    expect(
      await d.findByText(
        'Whether this connection may write has not been established yet; the broker will say if it refuses.',
      ),
    ).toBeInTheDocument();
  });
});

describe('ReviewApplyDrawer: the result', () => {
  async function applyWith(outcome: () => ConfigApplyOutcomeView) {
    real = () => HttpResponse.json(outcome());
    const opened = await planned();
    await toConfirm(opened.user, opened.d);
    await holdButton(opened.d.getByRole('button', { name: 'Apply to 2 nodes, canary first' }));
    return opened;
  }

  it('tells a partial run that re-running converges, and announces it', async () => {
    const { d } = await applyWith(halted);

    await d.findByText('Halted — applied to some nodes and not others');
    expect(d.getByText(/Re-running the same revision converges/)).toBeInTheDocument();
    expect(announcement()).toBe('Halted partway: some nodes were written and others were not.');
  });

  it('announces a failure that wrote nothing', async () => {
    const { d } = await applyWith(() => ({ ...halted(), outcome: 'FAILED' }));

    await d.findByText('Failed — nothing was applied');
    expect(announcement()).toBe('The apply failed; nothing was written.');
  });

  it('gives a clean apply no re-run note, and offers to plan again', async () => {
    const { user, d } = await applyWith(() => plan({ dryRun: false, outcome: 'APPLIED' }));

    await d.findByText('Applied to all 2 live nodes');
    expect(d.queryByText(/Re-running the same revision converges/)).not.toBeInTheDocument();
    const before = calls.length;
    await user.click(d.getByRole('button', { name: 'Plan again' }));
    await waitFor(() => expect(calls).toHaveLength(before + 1));
    expect(await d.findByRole('button', { name: 'Continue to confirm' })).toBeInTheDocument();
  });

  it('leaves through the way back to the configuration', async () => {
    const { user, d, onClose } = await applyWith(() => plan({ dryRun: false, outcome: 'APPLIED' }));
    await d.findByText('Applied to all 2 live nodes');
    await user.click(d.getByRole('button', { name: 'Back to the configuration' }));

    expect(onClose).toHaveBeenCalled();
  });
});

describe('ReviewApplyDrawer: closing', () => {
  it('holds nothing once closed, and plans afresh when reopened', async () => {
    const { user, d } = await planned();
    await user.click(d.getByRole('checkbox', { name: 'broker-1' }));
    await waitFor(() => expect(calls).toHaveLength(2));

    await user.keyboard('{Escape}');
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
    await user.click(screen.getByRole('button', { name: 'Reopen' }));

    const again = await drawer();
    await again.findByText(/Would apply/);
    expect(calls.at(-1)!.dryRun).toBe(true);
  });
});
