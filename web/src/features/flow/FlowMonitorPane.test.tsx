import { beforeEach, describe, expect, it, vi } from 'vitest';
import { screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import type { ComponentType } from 'react';

import { renderWithProviders } from '../../test/render.tsx';
import type { ResourceRef } from '../../kernel/api/resourceId.ts';
import type { MetricRange } from '../../kernel/time/ranges.ts';
import type { FlowGraphView, FlowNodeShare, FlowNodeView } from './api.ts';

type Panel = ComponentType<{ clusterId: string; subscription: ResourceRef; range: MetricRange }>;
let panels: { id: string; order: number; Component: Panel }[] = [];
vi.mock('../../kernel/slots.ts', async (importOriginal) => ({
  ...(await importOriginal<typeof import('../../kernel/slots.ts')>()),
  useSlot: (name: string) => (name === 'flow.selection.panels' ? panels : []),
}));

const { FlowMonitorPane } = await import('./FlowMonitorPane.tsx');

const share = (over: Partial<FlowNodeShare>): FlowNodeShare => ({
  nodeId: 'n1',
  node: 'node-a',
  messageCount: 10,
  consumerCount: 1,
  inRate: 2,
  outRate: 2,
  stale: false,
  ...over,
});

const node = (over: Partial<FlowNodeView>): FlowNodeView => ({
  id: 'q1',
  kind: 'SUBSCRIPTION',
  label: 'orders',
  ...over,
});

function graph(over: Partial<FlowGraphView> = {}): FlowGraphView {
  return { measuring: false, sampleIntervalSeconds: 5, ...over };
}

function show(g: FlowGraphView, nodeId: string | null, props: { pending?: boolean; range?: MetricRange } = {}) {
  const onClear = vi.fn();
  const onRangeChange = vi.fn();
  const view = renderWithProviders(
    <FlowMonitorPane
      clusterId="c1"
      graph={g}
      nodeId={nodeId}
      range={props.range ?? '1h'}
      breakdownPending={props.pending ?? false}
      onRangeChange={onRangeChange}
      onClear={onClear}
    />,
  );
  return { onClear, onRangeChange, unmount: view.unmount, user: userEvent.setup() };
}

beforeEach(() => {
  panels = [];
});

describe('FlowMonitorPane with nothing selected', () => {
  it("shows each broker node's totals, stating a node that did not answer as unknown rather than zero", async () => {
    show(
      graph({
        brokerNodes: [
          {
            nodeId: 'n1',
            name: 'node-a',
            state: 'OK',
            backlog: 1234,
            consumers: 3,
            inRate: 12.5,
            outRate: null,
          } as never,
          {
            nodeId: 'n2',
            name: 'node-b',
            state: 'UNREACHABLE',
            backlog: 0,
            consumers: 0,
            inRate: 0,
            outRate: 0,
          } as never,
          { nodeId: 'n3', name: 'node-c', state: 'FAILED' } as never,
          { name: 'node-d', state: 'OK', backlog: null, consumers: null } as never,
        ],
      }),
      null,
    );

    const region = screen.getByRole('region', { name: 'Broker nodes' });
    expect(
      within(region).getByText(/Select a client, destination or subscription to break it down per node/),
    ).toBeInTheDocument();
    expect(within(region).queryByText(/not in the shown paths/)).not.toBeInTheDocument();
    const grid = await within(region).findByRole('grid', { name: 'Totals per broker node' });
    expect(within(grid).getByText('1,234')).toBeInTheDocument();
    expect(within(grid).getByText('12.5')).toBeInTheDocument();
    // A rate not measured yet is said to be, and an unreachable node's figures are unknown, never 0.
    expect(within(grid).getAllByText('measuring…')).toHaveLength(3);
    expect(within(grid).getAllByText('did not answer')).toHaveLength(2);
    expect(within(grid).getAllByText('unknown')).toHaveLength(10);
    // A node with an id and no name, or a name and no id, still gets a row.
    expect(within(grid).getByText('node-d')).toBeInTheDocument();
  });

  it('says so when the selected node is no longer among the shown paths', async () => {
    show(graph({ brokerNodes: [] }), 'gone');

    expect(screen.getByText(/The selection is not in the shown paths any more\./)).toBeInTheDocument();
    expect(await screen.findByText('No broker node has reported')).toBeInTheDocument();
    expect(screen.getByText(/until the first scrape of a node completes/)).toBeInTheDocument();
  });
});

describe('FlowMonitorPane with a subscription selected', () => {
  it('names the subscription and its faults, breaks it down per node and states the imbalance in words', async () => {
    const q = node({
      faults: ['NO_CONSUMER', 'PARTIAL_PRESENCE', 'SOMETHING_NEW' as never],
      byNode: [
        share({ nodeId: 'n1', node: 'node-a', messageCount: 900, consumerCount: 0, inRate: 30, outRate: 0 }),
        share({ nodeId: 'n2', node: 'node-b', messageCount: 100, consumerCount: 2, inRate: 2, outRate: 8 }),
        share({ nodeId: 'n3', node: 'node-c', stale: true, messageCount: null, consumerCount: null }),
      ],
    });
    const { onClear, user } = show(graph({ nodes: [q] }), 'q1');

    const region = screen.getByRole('region', { name: 'Subscription orders' });
    expect(within(region).getByRole('heading', { level: 3, name: 'Subscription orders' })).toBeInTheDocument();
    expect(within(region).getByText('no consumer, partly deployed, something_new')).toBeInTheDocument();

    const statements = within(region).getByRole('list', { name: 'Balance across nodes' });
    expect(
      within(statements).getByText('node-c did not answer, so its share is unknown and left out.'),
    ).toBeInTheDocument();
    expect(within(statements).getByText('90% of the backlog is on node-a.')).toBeInTheDocument();
    expect(
      within(statements).getByText('node-a holds 900 messages and has no consumer; the consumers are on node-b.'),
    ).toBeInTheDocument();
    expect(
      within(statements).getByText('node-a receives 94% of messages in but delivers 0% of messages out.'),
    ).toBeInTheDocument();

    const grid = await within(region).findByRole('grid', { name: 'orders per node' });
    expect(
      within(grid)
        .getAllByRole('columnheader')
        .map((h) => h.textContent),
    ).toEqual(expect.arrayContaining(['Node', 'Backlog', 'Consumers', 'In/s', 'Out/s']));
    expect(within(grid).getByText('did not answer')).toBeInTheDocument();

    await user.click(within(region).getByRole('button', { name: 'Clear selection' }));
    expect(onClear).toHaveBeenCalledTimes(1);
  });

  it('says a resource served evenly is balanced, and one served by a single node says which', () => {
    const both = node({ byNode: [share({ nodeId: 'n1', node: 'node-a' }), share({ nodeId: 'n2', node: 'node-b' })] });
    const { unmount } = show(graph({ nodes: [both] }), 'q1');
    expect(screen.getByText('Balanced across 2 nodes.')).toBeInTheDocument();
    unmount();

    show(graph({ nodes: [node({ byNode: [share({ node: 'node-a' })] })] }), 'q1');
    expect(screen.getByText('Served by one node, node-a.')).toBeInTheDocument();
  });

  it('says it is still breaking the subscription down while the graph is the one from before it was asked for', () => {
    show(graph({ nodes: [node({ byNode: [] })] }), 'q1', { pending: true });

    expect(screen.getByRole('status')).toHaveTextContent('Breaking this down per node');
    expect(screen.getByRole('status')).toHaveAttribute('aria-busy', 'true');
    expect(screen.queryByRole('grid')).not.toBeInTheDocument();
  });

  it('says no node reports it on its own rather than showing an empty table', () => {
    show(graph({ nodes: [node({ byNode: null })] }), 'q1');

    expect(
      screen.getByText('No broker node reports this on its own, so there is no per-node breakdown.'),
    ).toBeInTheDocument();
  });

  it('offers a history range and hands each contributed panel the subscription and the range', async () => {
    const seen = vi.fn();
    panels = [
      {
        id: 'metrics.flow',
        order: 1,
        Component: ({ clusterId, subscription, range }) => {
          seen(clusterId, subscription.name, range);
          return <p>history of {subscription.name}</p>;
        },
      },
    ];
    const { user, onRangeChange } = show(graph({ nodes: [node({ byNode: [share({})] })] }), 'q1', { range: '6h' });

    // The history waits for the cluster's kinds, which say what a subscription's id is.
    expect(await screen.findByRole('heading', { name: 'Over time' })).toBeInTheDocument();
    expect(screen.getByText('history of orders')).toBeInTheDocument();
    expect(seen).toHaveBeenCalledWith('c1', 'orders', '6h');
    expect(screen.getByRole('radio', { name: '6h' })).toBeChecked();

    await user.click(screen.getByRole('radio', { name: '24h' }));
    expect(onRangeChange).toHaveBeenCalledWith('24h');
  });

  it('shows no history for a subscription when nothing contributes one, or for a destination', () => {
    const { unmount } = show(graph({ nodes: [node({ byNode: [share({})] })] }), 'q1');
    expect(screen.queryByRole('heading', { name: 'Over time' })).not.toBeInTheDocument();
    unmount();

    panels = [{ id: 'p', order: 1, Component: () => <p>panel</p> }];
    show(graph({ nodes: [node({ kind: 'DESTINATION', label: 'orders', byNode: [share({})] })] }), 'q1');
    expect(screen.getByRole('region', { name: 'Destination orders' })).toBeInTheDocument();
    expect(screen.queryByText('panel')).not.toBeInTheDocument();
  });

  it('names a node of an unlisted kind plainly', () => {
    show(graph({ nodes: [node({ kind: undefined, label: 'x', byNode: [] })] }), 'q1');

    expect(screen.getByRole('region', { name: 'Node x' })).toBeInTheDocument();
  });
});

describe('FlowMonitorPane with a client selected', () => {
  const edges = [
    {
      id: 'e1',
      source: 'p1',
      target: 'q1',
      stale: false,
      byNode: [
        { nodeId: 'n2', node: 'node-b', rate: 3, stale: false },
        { nodeId: 'n1', node: 'node-a', rate: 4, stale: false },
      ],
    },
    // A second edge from the same client adds to the node's rate; a stale reading marks the node.
    {
      id: 'e2',
      source: 'p1',
      target: 'q2',
      stale: false,
      byNode: [{ nodeId: 'n1', node: 'node-a', rate: 1.5, stale: true }],
    },
    // An edge that does not touch this client is not counted.
    {
      id: 'e3',
      source: 'p2',
      target: 'q1',
      stale: false,
      byNode: [{ nodeId: 'n1', node: 'node-a', rate: 99, stale: false }],
    },
  ];

  it("sums a producer's rates per node from its edges, sorted by node, and keeps no history for it", async () => {
    const g = graph({ nodes: [node({ id: 'p1', kind: 'PRODUCER', label: 'app-1' })], edges: edges as never });
    show(g, 'p1');

    const region = screen.getByRole('region', { name: 'Producing client app-1' });
    const grid = await within(region).findByRole('grid', { name: 'app-1 per node' });
    expect(within(grid).getByRole('columnheader', { name: /Sends\/s/ })).toBeInTheDocument();
    expect(within(grid).getAllByRole('row').length).toBeGreaterThanOrEqual(3);
    // node-a: 4 + 1.5, but one reading is stale, so its figure is unknown; node-b: 3.
    expect(within(grid).getByText('unknown')).toBeInTheDocument();
    expect(within(grid).getByText('3')).toBeInTheDocument();
    expect(within(grid).queryByText('99')).not.toBeInTheDocument();
    expect(
      within(region).getByText('Client history is not kept, so there are no trends for a client.'),
    ).toBeInTheDocument();
    // A client has no imbalance to state.
    expect(within(region).queryByRole('list', { name: 'Balance across nodes' })).not.toBeInTheDocument();
  });

  it("reads a consumer's rates from the edges that end at it", async () => {
    const g = graph({
      nodes: [node({ id: 'c1', kind: 'CONSUMER', label: 'worker' })],
      edges: [
        {
          id: 'e',
          source: 'q1',
          target: 'c1',
          stale: false,
          byNode: [{ nodeId: 'n1', node: 'node-a', rate: 7, stale: false }],
        },
      ] as never,
    });
    show(g, 'c1');

    const grid = await screen.findByRole('grid', { name: 'worker per node' });
    expect(within(grid).getByRole('columnheader', { name: /Receives\/s/ })).toBeInTheDocument();
    expect(within(grid).getByText('7')).toBeInTheDocument();
  });

  it('says no rate is measured for a client yet, and states a node with no rate as measuring', async () => {
    const empty = graph({ nodes: [node({ id: 'p1', kind: 'PRODUCER', label: 'app-1' })], edges: [] });
    const { unmount } = show(empty, 'p1');
    expect(screen.getByText('No per-node rate is measured for this client yet.')).toBeInTheDocument();
    unmount();

    const unrated = graph({
      nodes: [node({ id: 'p1', kind: 'PRODUCER', label: 'app-1' })],
      edges: [
        { id: 'e', source: 'p1', target: 'q1', stale: false, byNode: [{ nodeId: 'n1', node: 'node-a', stale: false }] },
      ] as never,
    });
    show(unrated, 'p1');
    expect(await screen.findByText('measuring…')).toBeInTheDocument();
  });
});
