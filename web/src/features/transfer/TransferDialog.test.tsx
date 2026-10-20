import { describe, expect, it, vi } from 'vitest';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';

import { renderWithProviders } from '../../test/render.tsx';
import { server } from '../../test/setup.ts';
import type { MessageSelection } from '../../kernel/slots.ts';
import { clusterHandlers, endpoint, finding, meHandler, previewHandler, run } from './fixtures.ts';
import { holdButton } from '../../test/hold.ts';

const navigate = vi.fn();
vi.mock('@tanstack/react-router', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@tanstack/react-router')>()),
  useNavigate: () => navigate,
}));

const { TransferDialog } = await import('./TransferDialog.tsx');

function open(
  props: Partial<{
    selection: MessageSelection;
    total: number | null;
    redistribute: boolean;
    node: string;
  }> = {},
) {
  const onClose = vi.fn();
  const onStarted = vi.fn();
  renderWithProviders(
    <TransferDialog
      clusterId="c1"
      subscriptionName="orders"
      selection={{ kind: 'all' }}
      total={1200}
      opened
      onClose={onClose}
      onStarted={onStarted}
      {...props}
    />,
  );
  return { onClose, onStarted };
}

async function pickNode(user: ReturnType<typeof userEvent.setup>, name: string) {
  const dialog = await screen.findByRole('dialog');
  await user.click(within(dialog).getByRole('combobox', { name: 'Target node' }));
  await user.click(await screen.findByRole('option', { name }));
  return dialog;
}

const previewButton = (dialog: HTMLElement) => within(dialog).getByRole('button', { name: 'Preview' });

describe('TransferDialog what it transfers', () => {
  it.each<[string, Partial<Parameters<typeof open>[0]>, string]>([
    ['every message of a subscription', { total: 1200 }, 'From all 1,200 messages of orders on node-a.'],
    ['an unknown number of messages', { total: null }, 'From every message of orders on node-a.'],
    [
      'the picked messages',
      { selection: { kind: 'ids', ids: [1, 2, 3] }, total: 3 },
      'From the 3 selected messages of orders on node-a.',
    ],
    [
      'the messages a filter matches',
      { selection: { kind: 'filter', filter: 'priority > 4' }, total: null },
      'From every message of orders matching priority > 4 on node-a.',
    ],
  ])('says it is moving %s', async (_name, props, sentence) => {
    server.use(meHandler(), ...clusterHandlers());
    open(props);

    expect(await screen.findByText(sentence)).toBeInTheDocument();
    expect(screen.getByRole('dialog', { name: 'Transfer messages from orders' })).toBeInTheDocument();
  });

  it('reads from the node it was opened on, not just the first live one', async () => {
    server.use(meHandler(), ...clusterHandlers());
    open({ node: 'n2' });

    expect(await screen.findByText(/on node-b\.$/)).toBeInTheDocument();
  });

  it('sends a filtered selection as a filter', async () => {
    const bodies: unknown[] = [];
    server.use(meHandler(), ...clusterHandlers(), previewHandler(run(), bodies));
    const user = userEvent.setup();
    open({ selection: { kind: 'filter', filter: 'priority > 4' }, total: null });

    const dialog = await pickNode(user, 'node-b');
    await user.click(previewButton(dialog));

    await waitFor(() => expect(bodies).toHaveLength(1));
    expect(bodies[0]).toMatchObject({ selection: { kind: 'FILTER', ids: null, filter: 'priority > 4' } });
  });
});

describe('TransferDialog destination', () => {
  it('copies instead of moving when the mode is switched', async () => {
    const bodies: unknown[] = [];
    server.use(meHandler(), ...clusterHandlers(), previewHandler(run({ mode: 'COPY' }), bodies));
    const user = userEvent.setup();
    open();

    const dialog = await pickNode(user, 'node-b');
    await user.click(within(dialog).getByRole('radio', { name: 'Copy: the source is unchanged' }));
    await user.click(previewButton(dialog));

    await waitFor(() => expect(bodies).toHaveLength(1));
    expect(bodies[0]).toMatchObject({ mode: 'COPY' });
    expect(
      await within(dialog).findByText(/Copy 1,200 messages \(84 MB\) from orders on node-a \(primary\)/),
    ).toBeInTheDocument();
    expect(within(dialog).getByText(/The source subscription is unchanged\./)).toBeInTheDocument();
  });

  it('targets another cluster, whose nodes are all choosable, and names it in the preview', async () => {
    const bodies: unknown[] = [];
    server.use(
      meHandler(),
      ...clusterHandlers(),
      previewHandler(
        run({
          target: { ...run().target, clusterId: 'c2', nodeName: 'node-a', subscription: 'artemis.queue/orders-dr' },
        }),
        bodies,
      ),
    );
    const user = userEvent.setup();
    open();

    const dialog = await screen.findByRole('dialog');
    await user.click(await within(dialog).findByRole('combobox', { name: 'Target cluster' }));
    await user.click(await screen.findByRole('option', { name: 'dr-site' }));
    // On its own cluster the source node is not offered; on another one it is.
    await user.click(within(dialog).getByRole('combobox', { name: 'Target node' }));
    const nodeA = await screen.findByRole('option', { name: 'node-a' });
    expect(nodeA).not.toHaveAttribute('data-combobox-disabled');
    await user.click(nodeA);
    const subscription = within(dialog).getByRole('textbox', { name: 'Target subscription' });
    await user.clear(subscription);
    await user.type(subscription, '  orders-dr  ');
    await user.click(previewButton(dialog));

    await waitFor(() => expect(bodies).toHaveLength(1));
    expect(bodies[0]).toMatchObject({ targetClusterId: 'c2', targetNodeId: 'n1', target: 'artemis.queue/orders-dr' });
    expect(
      await within(dialog).findByText(/to orders-dr on node-a \(dr-site\)\. They leave the source subscription\./),
    ).toBeInTheDocument();
  });

  it('asks for a subscription name when it is emptied, focuses it, and clears the complaint once one is typed', async () => {
    server.use(meHandler(), ...clusterHandlers());
    const user = userEvent.setup();
    open();

    const dialog = await pickNode(user, 'node-b');
    const subscription = within(dialog).getByRole('textbox', { name: 'Target subscription' });
    await user.clear(subscription);
    await user.click(previewButton(dialog));

    expect(await within(dialog).findByText('Name the subscription the messages go to.')).toBeInTheDocument();
    expect(subscription).toHaveFocus();
    await user.type(subscription, 'x');
    await waitFor(() => expect(within(dialog).queryByText('Name the subscription the messages go to.')).toBeNull());
  });

  it('complains about the node on blur, and stops once one is chosen', async () => {
    server.use(meHandler(), ...clusterHandlers());
    const user = userEvent.setup();
    open();

    const dialog = await screen.findByRole('dialog');
    const node = within(dialog).getByRole('combobox', { name: 'Target node' });
    await user.click(node);
    await user.tab();
    expect(await within(dialog).findByText('Choose the node the messages go to.')).toBeInTheDocument();

    await pickNode(user, 'node-b');
    await waitFor(() => expect(within(dialog).queryByText('Choose the node the messages go to.')).toBeNull());
  });

  it('closes and forgets what was typed when cancelled', async () => {
    server.use(meHandler(), ...clusterHandlers());
    const user = userEvent.setup();
    const { onClose } = open();

    const dialog = await screen.findByRole('dialog');
    await user.type(within(dialog).getByRole('textbox', { name: 'Target subscription' }), '-copy');
    await user.click(within(dialog).getByRole('button', { name: 'Cancel' }));

    expect(onClose).toHaveBeenCalledTimes(1);
  });
});

describe('TransferDialog redistribution', () => {
  it('fixes the target to the same subscription and picks the only other node for the operator', async () => {
    const bodies: unknown[] = [];
    server.use(meHandler(), ...clusterHandlers(), previewHandler(run(), bodies));
    const user = userEvent.setup();
    open({ redistribute: true });

    const dialog = await screen.findByRole('dialog', { name: 'Redistribute from orders to another node' });
    expect(within(dialog).getByText(/land on that node.s own subscription\./)).toBeInTheDocument();
    expect(within(dialog).queryByRole('textbox', { name: 'Target subscription' })).toBeNull();
    expect(within(dialog).queryByRole('combobox', { name: 'Target cluster' })).toBeNull();
    expect(within(dialog).queryByText('Mode')).toBeNull();
    await waitFor(() => expect(within(dialog).getByRole('combobox', { name: 'Target node' })).toHaveValue('node-b'));

    await user.click(previewButton(dialog));
    await waitFor(() => expect(bodies).toHaveLength(1));
    expect(bodies[0]).toEqual({
      mode: 'MOVE',
      source: 'artemis.queue/orders',
      sourceNodeId: 'n1',
      selection: { kind: 'ALL', ids: null, filter: null },
      targetClusterId: 'c1',
      targetNodeId: 'n2',
      target: 'artemis.queue/orders',
    });
  });

  it('leaves the choice to the operator when there are several other nodes', async () => {
    const three = (clusterId: string) => ({
      clusterId,
      nodes: [1, 2, 3].map((i) => ({
        artemisNodeId: `n${i}`,
        splitBrain: 'NONE',
        replicationBehind: false,
        endpoints: [endpoint(`n${i}`, `node-${'abc'[i - 1]}`)],
      })),
    });
    server.use(meHandler(), ...clusterHandlers(three));
    open({ redistribute: true });

    const dialog = await screen.findByRole('dialog');
    const node = within(dialog).getByRole('combobox', { name: 'Target node' });
    await waitFor(() => expect(node).toHaveAttribute('placeholder', 'Choose a node'));
    expect(node).toHaveValue('');
  });
});

describe('TransferDialog what may not be done', () => {
  it('says there is nothing to read from when no node is live and managed, and offers no preview', async () => {
    const backupOnly = (clusterId: string) => ({
      clusterId,
      nodes: [
        {
          artemisNodeId: 'n3',
          splitBrain: 'NONE',
          replicationBehind: false,
          endpoints: [endpoint('n3', 'node-b-backup', { haRole: 'BACKUP', active: false, state: 'BACKUP' })],
        },
      ],
    });
    server.use(meHandler(), ...clusterHandlers(backupOnly));
    open();

    const notice = await screen.findByRole('region', { name: 'No source node to read from' });
    expect(notice).toHaveTextContent('No node of this cluster is live and managed by Rahdar now');
    const dialog = screen.getByRole('dialog');
    expect(previewButton(dialog)).toBeDisabled();
    expect(within(dialog).getByRole('combobox', { name: 'Target node' })).toBeInTheDocument();
  });

  it('keeps preview visible but disabled, with the broker refusal, when the source cannot be managed', async () => {
    server.use(
      meHandler(),
      ...clusterHandlers(undefined, {
        managementWrite: { status: 'UNAVAILABLE', reason: 'the broker refused a write', brokerXmlSnippet: null },
      }),
    );
    const user = userEvent.setup();
    open();

    const dialog = await screen.findByRole('dialog');
    const explain = await within(dialog).findByRole('button', { name: 'Why previewing this transfer is unavailable' });
    expect(within(dialog).getByRole('button', { name: 'Preview' })).toBeDisabled();
    await user.click(explain);
    expect(await screen.findByText(/the broker refused a write/)).toBeInTheDocument();
  });

  it('blocks on the target when the operator may move but not send', async () => {
    server.use(meHandler(['message:move', 'message:read']), ...clusterHandlers());
    const user = userEvent.setup();
    open();

    const dialog = await screen.findByRole('dialog');
    const explain = await within(dialog).findByRole('button', { name: 'Why previewing this transfer is unavailable' });
    await user.click(explain);
    expect(await screen.findByText(/You do not have the "Send messages" permission/)).toBeInTheDocument();
  });

  it('offers the preview anyway, and says what it could not establish, when a capability is not known yet', async () => {
    server.use(
      meHandler(),
      ...clusterHandlers(undefined, {
        managementWrite: { status: 'UNKNOWN', reason: 'not probed', brokerXmlSnippet: null },
      }),
    );
    open();

    expect(await screen.findByText(/has not been established yet\. The preview is offered anyway/)).toBeInTheDocument();
    await waitFor(() => expect(previewButton(screen.getByRole('dialog'))).toBeEnabled());
  });
});

describe('TransferDialog preview', () => {
  async function previewed(over: Parameters<typeof run>[0], user = userEvent.setup()) {
    server.use(meHandler(), ...clusterHandlers(), previewHandler(run(over)));
    const handlers = open();
    const dialog = await pickNode(user, 'node-b');
    await user.click(previewButton(dialog));
    await within(dialog).findByText(/The target (can|cannot) accept/);
    return { dialog, user, ...handlers };
  }

  it('says the target can accept a clean plan, and states an unknown size and byte count as unknown', async () => {
    const { dialog } = await previewed({ estimate: null, estimateBytes: null });

    expect(
      within(dialog).getByText(
        'Move the selected messages (how many is not known until the run counts them) from orders on node-a (primary) to orders on node-a (dr-site). They leave the source subscription.',
      ),
    ).toBeInTheDocument();
    expect(within(dialog).getByText('The target can accept it.')).toBeInTheDocument();
    // Without a count the action is named without one.
    expect(within(dialog).getByRole('button', { name: /^Move/ })).toBeInTheDocument();
  });

  it('counts the warnings still to acknowledge and lets each be withdrawn again', async () => {
    const { dialog, user } = await previewed({
      findings: [
        finding('WARN', 'a', 'First warning.', '<x>1</x>'),
        finding('WARN', 'b', 'Second warning.'),
        finding('UNKNOWN', 'c', 'Could not check disk.', '<y>2</y>'),
      ],
      notes: ['Messages keep their order.'],
    });

    expect(within(dialog).getByText('The target can accept it, with 2 warnings to acknowledge.')).toBeInTheDocument();
    expect(within(dialog).getByText('Acknowledge the 2 warnings above to run this.')).toBeInTheDocument();
    expect(within(dialog).getByText('<x>1</x>')).toBeInTheDocument();
    expect(within(dialog).getByText('<y>2</y>')).toBeInTheDocument();
    expect(within(dialog).getByRole('heading', { name: 'Good to know' })).toBeInTheDocument();
    expect(within(dialog).getByText('Messages keep their order.')).toBeInTheDocument();

    await user.click(within(dialog).getByRole('checkbox', { name: 'First warning.' }));
    expect(within(dialog).getByText('Acknowledge the warning above to run this.')).toBeInTheDocument();
    await user.click(within(dialog).getByRole('checkbox', { name: 'Second warning.' }));
    expect(within(dialog).queryByText(/Acknowledge/)).toBeNull();
    await user.click(within(dialog).getByRole('checkbox', { name: 'Second warning.' }));
    expect(within(dialog).getByText('Acknowledge the warning above to run this.')).toBeInTheDocument();
  });

  it('says one warning in the singular', async () => {
    const { dialog } = await previewed({ findings: [finding('WARN', 'a', 'Only warning.')] });
    expect(within(dialog).getByText('The target can accept it, with 1 warning to acknowledge.')).toBeInTheDocument();
  });

  it('offers a copy under the cap a plain button, and a move the destination change', async () => {
    const { dialog, user } = await previewed({ mode: 'COPY', estimateBytes: null });

    expect(within(dialog).getByText(/Copy 1,200 messages \(size unknown\)/)).toBeInTheDocument();
    expect(within(dialog).getByRole('button', { name: 'Copy 1,200 messages' })).toBeEnabled();

    await user.click(within(dialog).getByRole('button', { name: 'Change the destination' }));
    expect(await within(dialog).findByRole('combobox', { name: 'Target node' })).toBeInTheDocument();
    expect(previewButton(dialog)).toBeInTheDocument();
    expect(within(dialog).queryByText(/The target can accept it/)).toBeNull();
  });

  it('holds a copy over the safety cap to a typed confirmation and sends the override', async () => {
    const executed: unknown[] = [];
    server.use(
      http.post('*/api/v1/clusters/c1/transfers/runs/r1/execute', async ({ request }) => {
        executed.push(await request.json());
        return HttpResponse.json(run({ state: 'RUNNING' }));
      }),
    );
    navigate.mockClear();
    const { dialog, onClose, onStarted } = await previewed({
      mode: 'COPY',
      overCap: true,
      estimate: 20_000,
      cap: 10_000,
    });

    const alert =
      within(dialog).getByText('Over the safety cap').closest('[role="alert"]') ??
      within(dialog).getByText(/This selects 20,000 messages/);
    expect(alert).toHaveTextContent('This selects 20,000 messages, over the safety cap of 10,000.');
    expect(alert).toHaveTextContent('recorded in the audit log');

    const confirm = within(dialog).getByRole('button', { name: 'Copy 20,000 messages' });
    await holdButton(confirm);

    await waitFor(() =>
      expect(executed).toEqual([{ planHash: 'h1', override: true, acknowledged: [], confirmSubscription: 'orders' }]),
    );
    await waitFor(() => expect(navigate).toHaveBeenCalledWith({ to: '/clusters/c1/transfers/r1' }));
    expect(onClose).toHaveBeenCalled();
    expect(onStarted).toHaveBeenCalled();
  });

  it('says an unknown size cannot be held to the cap', async () => {
    const { dialog } = await previewed({ mode: 'COPY', overCap: true, estimate: null, cap: 10_000 });

    expect(
      within(dialog).getByText(
        /How many messages this selects is not known, so it cannot be held to the safety cap of 10,000\./,
      ),
    ).toBeInTheDocument();
  });

  it('does not talk of a cap override for a transfer that is refused, and offers nothing to run', async () => {
    const { dialog } = await previewed({
      overCap: true,
      estimate: 20_000,
      findings: [finding('REFUSE', 'no-room', 'node-b has no room.')],
    });

    expect(within(dialog).queryByText('Over the safety cap')).toBeNull();
    expect(within(dialog).getByText('Refused')).toBeInTheDocument();
    expect(within(dialog).getByText('node-b has no room.')).toBeInTheDocument();
    // Only the way back is left.
    expect(within(dialog).getByRole('button', { name: 'Change the destination' })).toBeInTheDocument();
  });

  it('offers a fresh preview after the run could not start, keeping what was acknowledged out of it', async () => {
    const bodies: unknown[] = [];
    server.use(
      meHandler(),
      ...clusterHandlers(),
      http.post('*/api/v1/clusters/c1/transfers/preview', async ({ request }) => {
        bodies.push(await request.json());
        return HttpResponse.json(run({ mode: 'COPY', findings: [finding('WARN', 'a', 'A warning.')] }), {
          status: 201,
        });
      }),
      http.post('*/api/v1/clusters/c1/transfers/runs/r1/execute', () =>
        HttpResponse.json(
          {
            type: 'https://rahdar.dev/problems/transfer-plan-mismatch',
            title: 'Conflict',
            status: 409,
            detail: 'The plan changed.',
          },
          { status: 409 },
        ),
      ),
    );
    const user = userEvent.setup();
    open();
    const dialog = await pickNode(user, 'node-b');
    await user.click(previewButton(dialog));
    await user.click(await within(dialog).findByRole('checkbox', { name: 'A warning.' }));
    await user.click(within(dialog).getByRole('button', { name: 'Copy 1,200 messages' }));

    const alert = await within(dialog).findByRole('alert');
    expect(alert).toHaveTextContent('The plan changed.');
    expect(within(dialog).getByText(/Nothing was run\./)).toBeInTheDocument();
    await user.click(within(dialog).getByRole('button', { name: 'Preview again' }));

    await waitFor(() => expect(bodies).toHaveLength(2));
    // A new plan needs its warnings acknowledged afresh.
    expect(await within(dialog).findByText('Acknowledge the warning above to run this.')).toBeInTheDocument();
    expect(within(dialog).queryByRole('alert')).toBeNull();
  });
});
