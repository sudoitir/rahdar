import { beforeEach, describe, expect, it, vi } from 'vitest';
import { http, HttpResponse } from 'msw';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

import { renderWithProviders } from '../../test/render.tsx';
import { server } from '../../test/setup.ts';

const routerState = vi.hoisted(() => ({ search: {} as Record<string, unknown>, navigate: vi.fn() }));

vi.mock('@tanstack/react-router', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@tanstack/react-router')>()),
  useParams: () => ({ clusterId: 'c1' }),
  useSearch: () => routerState.search,
  useNavigate: () => routerState.navigate,
}));

const { FlowView } = await import('./FlowView.tsx');

function graph(over: Record<string, unknown> = {}) {
  return {
    nodes: [
      { id: 'destination:orders', kind: 'DESTINATION', label: 'orders', routingTypes: ['ANYCAST'], faults: [] },
      {
        id: 'subscription:ORDERS.inbound',
        kind: 'SUBSCRIPTION',
        label: 'orders',
        messageCount: 1200,
        consumerCount: 0,
        faults: ['NO_CONSUMER'],
      },
      { id: 'producer:order-svc', kind: 'PRODUCER', label: 'order-svc', members: 2, faults: [] },
    ],
    edges: [
      {
        id: 'route:orders->orders',
        kind: 'ROUTE',
        source: 'destination:orders',
        target: 'subscription:ORDERS.inbound',
        rate: 42,
        rateSource: 'SUBSCRIPTION_METRIC',
        asOf: '2026-09-14T10:00:00Z',
        stale: false,
        delivery: 'SHARED',
        faults: [],
      },
      {
        id: 'produce:order-svc->orders',
        kind: 'PRODUCE',
        source: 'producer:order-svc',
        target: 'destination:orders',
        rateSource: 'SAMPLER',
        stale: false,
        members: 2,
        faults: [],
      },
    ],
    kpis: { inRate: 42, outRate: null, backlog: 1200, clients: 1, faults: 2 },
    totals: { paths: 5, shown: 2, limit: 40, clamped: false },
    focus: null,
    sampledAt: null,
    measuring: true,
    sampleIntervalSeconds: 15,
    brokerNodes: [
      {
        nodeId: 'n2',
        name: 'node-b',
        state: 'UNREACHABLE',
        message: 'This node did not answer the latest sweep, so its clients are not shown.',
        producersSeen: 0,
        producersTotal: 0,
        consumersSeen: 0,
        consumersTotal: 0,
        truncated: false,
      },
    ],
    ...over,
  };
}

function serve(body: Record<string, unknown>, seen: string[] = []) {
  server.use(
    http.get('*/api/v1/clusters/c1/flow', ({ request }) => {
      seen.push(request.url);
      return HttpResponse.json(body);
    }),
  );
}

/** The queue's node shares: a stranded backlog on artemis-b, and artemis-c silent. */
function splitGraph() {
  const base = graph({ measuring: false, sampledAt: '2026-09-14T10:00:00Z' });
  const nodes = (base.nodes as Array<Record<string, unknown>>).map((n) =>
    n.kind === 'SUBSCRIPTION'
      ? {
          ...n,
          byNode: [
            { nodeId: 'a', node: 'artemis-a', messageCount: 10, consumerCount: 3, inRate: 5, outRate: 5, stale: false },
            {
              nodeId: 'b',
              node: 'artemis-b',
              messageCount: 9000,
              consumerCount: 0,
              inRate: 5,
              outRate: 0,
              stale: false,
            },
            {
              nodeId: 'c',
              node: 'artemis-c',
              messageCount: null,
              consumerCount: null,
              inRate: null,
              outRate: null,
              stale: true,
            },
          ],
        }
      : n,
  );
  return { ...base, nodes };
}

function serveHistory() {
  const points = [{ ts: '2026-09-14T10:00:00Z', value: 10 }];
  const series = (metric: string) => ({ metric, kind: 'GAUGE', unit: 'messages', points });
  server.use(
    http.get('*/api/v1/clusters/c1/metrics', () =>
      HttpResponse.json({
        from: '2026-09-14T09:00:00Z',
        to: '2026-09-14T10:00:00Z',
        step: 'PT1M',
        truncated: false,
        series: [],
        splitBy: 'NODE',
        byNode: [
          {
            nodeId: 'a',
            nodeName: 'artemis-a',
            sampled: true,
            series: ['openbroker.backlog', 'openbroker.enqueued', 'openbroker.acknowledged'].map(series),
          },
          { nodeId: 'b', nodeName: 'artemis-b', sampled: false, series: [] },
        ],
      }),
    ),
  );
}

describe('FlowView', () => {
  beforeEach(() => {
    routerState.search = {};
    routerState.navigate.mockReset();
  });

  it('leads with totals, states an unknown total as measuring, and states its bound', async () => {
    serve(graph());
    renderWithProviders(<FlowView />);

    const totals = await screen.findByRole('region', { name: 'Totals across every path' });
    expect(totals).toHaveTextContent('Messages in42msg/s');
    // An unknown total is stated and explained, never rendered as zero.
    expect(totals).toHaveTextContent('Messages outUnavailable');
    expect(totals).toHaveTextContent('Still measuring: a rate needs two samples.');
    expect(totals).toHaveTextContent('2 faults');
    expect(screen.getByText('Showing 2 of 5 paths, ranked by messages in.')).toBeInTheDocument();
    expect(
      screen.getByText('Raise the limit, or focus a client, address or queue to reach the rest.'),
    ).toBeInTheDocument();
    expect(screen.getByText(/Measuring client rates/)).toBeInTheDocument();
  });

  it('is one page with a single h1 named Flow, and sections under it', async () => {
    serve(graph());
    renderWithProviders(<FlowView />);

    expect(await screen.findByRole('heading', { level: 2, name: 'Totals across every path' })).toBeInTheDocument();
    expect(screen.getByRole('heading', { level: 1, name: 'Flow' })).toBeInTheDocument();
    expect(screen.getAllByRole('heading', { level: 1 })).toHaveLength(1);
    expect(screen.getByRole('heading', { level: 2, name: 'Paths' })).toBeInTheDocument();
  });

  it('says the flow is incomplete, not absent, when the nodes that would show it did not answer', async () => {
    serve(
      graph({
        nodes: [],
        edges: [],
        kpis: { inRate: null, outRate: null, backlog: 0, clients: 0, faults: 0 },
        totals: { paths: 0, shown: 0, limit: 40, clamped: false },
      }),
    );
    renderWithProviders(<FlowView />);

    expect(await screen.findByText('No flow to show, and some nodes did not answer')).toBeInTheDocument();
    expect(screen.getByRole('list', { name: 'Nodes that could not be reached' })).toHaveTextContent('node-b');
    expect(screen.queryByText('No flow to show yet')).not.toBeInTheDocument();
  });

  it('names the nodes that did not answer in the table instead of presenting an empty list as a fact', async () => {
    routerState.search = { tab: 'table' };
    serve(graph({ nodes: [], edges: [] }));
    renderWithProviders(<FlowView />);

    expect(await screen.findByText('No paths to list, and some nodes did not answer')).toBeInTheDocument();
    expect(screen.getByRole('list', { name: 'Nodes that could not be reached' })).toHaveTextContent('node-b');
  });

  it('says a narrowed table is narrowed, and clears the focus and the layers on request', async () => {
    routerState.search = { tab: 'table', layers: 'ROUTES' };
    serve(graph({ nodes: [], edges: [], brokerNodes: [] }));
    renderWithProviders(<FlowView />);

    expect(await screen.findByText('No path matches this focus and these layers')).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: 'Clear filters' }));

    const call = routerState.navigate.mock.calls.at(-1)?.[0] as { search: (prev: object) => Record<string, unknown> };
    expect(call.search({ tab: 'table', layers: 'ROUTES' })).toEqual({
      tab: 'table',
      layers: undefined,
      focus: undefined,
      hops: undefined,
    });
  });

  it('teaches what the table lists when there are no paths and nothing narrows it', async () => {
    routerState.search = { tab: 'table' };
    serve(graph({ nodes: [], edges: [], brokerNodes: [] }));
    renderWithProviders(<FlowView />);

    expect(await screen.findByText('No paths to list')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Clear filters' })).not.toBeInTheDocument();
  });

  it('names a node that did not answer instead of showing fewer clients silently', async () => {
    serve(graph());
    renderWithProviders(<FlowView />);

    expect(await screen.findByText('node-b did not answer')).toBeInTheDocument();
    expect(screen.getByText(/its clients are not shown/)).toBeInTheDocument();
  });

  it('shows the management access that grants a refused listing', async () => {
    serve(
      graph({
        brokerNodes: [
          {
            nodeId: 'n1',
            name: 'node-a',
            state: 'PERMISSION_DENIED',
            message: 'The broker refused to list producers or consumers.',
            brokerXmlSnippet: '<role-access><match domain="org.apache.activemq.artemis"/></role-access>',
            truncated: false,
          },
        ],
      }),
    );
    renderWithProviders(<FlowView />);

    expect(await screen.findByText('node-a refused to list clients')).toBeInTheDocument();
    expect(screen.getByText(/role-access/)).toBeInTheDocument();
  });

  it('teaches what flow is when the cluster has nothing to draw', async () => {
    serve(
      graph({
        nodes: [],
        edges: [],
        kpis: { inRate: null, outRate: null, backlog: 0, clients: 0, faults: 0 },
        totals: { paths: 0, shown: 0, limit: 40, clamped: false },
        brokerNodes: [],
      }),
    );
    renderWithProviders(<FlowView />);

    expect(await screen.findByText('No flow to show yet')).toBeInTheDocument();
  });

  it('says when a focus matches nothing and clears it through the URL', async () => {
    routerState.search = { focus: 'subscription:ARCHIVE.gone' };
    serve(
      graph({
        nodes: [],
        edges: [],
        totals: { paths: 5, shown: 0, limit: 40, clamped: false },
        focus: { kind: 'queue', name: 'ARCHIVE.gone', hops: 1, matched: false },
        brokerNodes: [],
      }),
    );
    renderWithProviders(<FlowView />);

    expect(await screen.findByText('Nothing matches the focus queue ARCHIVE.gone')).toBeInTheDocument();
    await userEvent.click(screen.getAllByRole('button', { name: 'Clear focus' })[0]);

    const call = routerState.navigate.mock.calls.at(-1)?.[0] as { search: (prev: object) => Record<string, unknown> };
    expect(call.search({ focus: 'subscription:ARCHIVE.gone', rank: 'OUT' })).toEqual({
      focus: undefined,
      hops: undefined,
      rank: 'OUT',
    });
  });

  it('draws no moving dots when the system asks for reduced motion, and says why', async () => {
    const original = window.matchMedia;
    window.matchMedia = ((query: string) => ({
      ...original(query),
      matches: query.includes('prefers-reduced-motion'),
    })) as typeof window.matchMedia;
    try {
      serve(graph({ measuring: false, sampledAt: '2026-09-14T10:00:00Z' }));
      const { container } = renderWithProviders(<FlowView />);

      expect(await screen.findByText(/Motion is off \(reduced motion\)/)).toBeInTheDocument();
      expect(screen.getByText('Motion off: your system asks for reduced motion.')).toBeInTheDocument();
      expect(screen.queryByRole('button', { name: 'Pause motion' })).not.toBeInTheDocument();
      expect(container.querySelector('animateMotion')).toBeNull();
    } finally {
      window.matchMedia = original;
    }
  });

  it('folds the overview map away and remembers the choice', async () => {
    // The minimap covers the corner of a graph the size of a real estate, and
    // the operator's choice has to survive the next visit to be worth making.
    window.localStorage.removeItem('rahdar.flow.minimap');
    serve(graph());
    const { unmount } = renderWithProviders(<FlowView />);

    expect(await screen.findByRole('img', { name: 'Overview of the whole graph' })).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: 'Hide overview' }));
    expect(screen.queryByRole('img', { name: 'Overview of the whole graph' })).not.toBeInTheDocument();
    unmount();

    renderWithProviders(<FlowView />);
    expect(await screen.findByRole('button', { name: 'Show overview' })).toBeInTheDocument();
    expect(screen.queryByRole('img', { name: 'Overview of the whole graph' })).not.toBeInTheDocument();
  });

  it('turns a routing layer on through the URL', async () => {
    serve(graph());
    renderWithProviders(<FlowView />);

    await userEvent.click(await screen.findByRole('checkbox', { name: 'Dead letter & expiry' }));

    const call = routerState.navigate.mock.calls.at(-1)?.[0] as { search: (prev: object) => Record<string, unknown> };
    expect(call.search({})).toEqual({ layers: 'CLUSTER,DEAD_LETTER,ROUTES' });
  });

  it('offers ranking, grouping and the bound as labelled controls', async () => {
    routerState.search = { tab: 'table' };
    serve(graph());
    renderWithProviders(<FlowView />);

    // Mantine names both the input and its option list by the label; the input is the control.
    const input = (label: string) =>
      screen.getAllByLabelText(label).find((el) => el.tagName === 'INPUT') as HTMLInputElement;
    await screen.findAllByLabelText('Rank paths by');
    expect(input('Rank paths by')).toHaveValue('messages in');
    expect(input('Group clients by')).toHaveValue('Client ID');
    expect(input('Show')).toHaveValue('40 busiest paths');
    // The controls render before the graph arrives; the table only once it has.
    const headers = await screen.findAllByRole('columnheader');
    expect(headers.map((h) => h.textContent?.trim())).toEqual(
      expect.arrayContaining(['From', 'Relation', 'To', 'Rate', 'Rate from', 'Clients', 'Faults']),
    );
  });

  it('opens the Split layout through the URL, and only it asks for the per-node breakdown', async () => {
    const seen: string[] = [];
    serve(graph(), seen);
    renderWithProviders(<FlowView />);

    await userEvent.click(await screen.findByRole('radio', { name: 'Split' }));
    const call = routerState.navigate.mock.calls.at(-1)?.[0] as { search: (prev: object) => Record<string, unknown> };
    expect(call.search({ rank: 'OUT' })).toEqual({ rank: 'OUT', tab: 'split' });
    expect(seen.every((url) => !url.includes('byNode'))).toBe(true);
  });

  it('restores the selection from the address, states imbalance in words, and never reads a silent node as zero', async () => {
    routerState.search = { tab: 'split', node: 'subscription:ORDERS.inbound' };
    const seen: string[] = [];
    serve(splitGraph(), seen);
    serveHistory();
    renderWithProviders(<FlowView />);

    const pane = await screen.findByRole('region', { name: 'Queue orders' });
    expect(seen.at(-1)).toContain('byNode=true');
    expect(pane).toHaveTextContent(
      'artemis-b holds 9,000 messages and has no consumer; the consumers are on artemis-a.',
    );
    expect(pane).toHaveTextContent('artemis-c did not answer, so its share is unknown and left out.');
    const grid = screen.getByRole('grid', { name: 'orders per node' });
    const silent = (await within(grid).findByText('artemis-c')).closest('[role="row"]') as HTMLElement;
    expect(silent).toHaveTextContent('did not answer');
    expect(within(silent).getAllByText('unknown')).toHaveLength(4);

    // The trends are the metrics feature's, per node; a node without samples says so.
    expect(await screen.findByRole('region', { name: 'History on artemis-a' })).toBeInTheDocument();
    expect(screen.getByRole('region', { name: 'History on artemis-b' })).toHaveTextContent(
      'Not sampled in this window',
    );
  });

  it('clears the selection through the address', async () => {
    routerState.search = { tab: 'split', node: 'subscription:ORDERS.inbound' };
    serve(splitGraph());
    serveHistory();
    renderWithProviders(<FlowView />);

    await userEvent.click(await screen.findByRole('button', { name: 'Clear selection' }));
    const call = routerState.navigate.mock.calls.at(-1)?.[0] as { search: (prev: object) => Record<string, unknown> };
    expect(call.search({ tab: 'split', node: 'subscription:ORDERS.inbound' })).toEqual({
      tab: 'split',
      node: undefined,
    });
  });

  it('opens the inspector for a selection in the address in the graph layout', async () => {
    routerState.search = { node: 'subscription:ORDERS.inbound' };
    serve(graph());
    renderWithProviders(<FlowView />);

    expect(await screen.findByRole('complementary', { name: 'Details of Queue orders' })).toBeInTheDocument();
  });

  it('resizes the monitoring pane from the keyboard', async () => {
    routerState.search = { tab: 'split' };
    serve(splitGraph());
    renderWithProviders(<FlowView />);

    const separator = await screen.findByRole('separator', { name: 'Resize the monitoring pane' });
    const before = Number(separator.getAttribute('aria-valuenow'));
    separator.focus();
    await userEvent.keyboard('{ArrowLeft}');
    expect(Number(separator.getAttribute('aria-valuenow'))).toBeLessThan(before);
    // With nothing selected, the pane shows each broker node's totals.
    expect(screen.getByRole('grid', { name: 'Totals per broker node' })).toBeInTheDocument();
  });

  it("offers a path's resource to open, and nothing that changes the broker", async () => {
    routerState.search = { tab: 'table' };
    serve(graph());
    renderWithProviders(<FlowView />);

    await userEvent.click(await screen.findByRole('button', { name: 'Actions for order-svc' }));
    const menu = await screen.findByRole('menu');
    expect(within(menu).getByRole('menuitem', { name: /Focus the view on this/ })).toBeInTheDocument();
    expect(within(menu).getByRole('menuitem', { name: /Open its connections/ })).toBeInTheDocument();
    expect(within(menu).queryByRole('menuitem', { name: /Delete|Purge|Close/ })).not.toBeInTheDocument();
    expect(within(menu).getByText(/This view never changes the broker/)).toBeInTheDocument();
  });
});

/** What the last navigate() call does to the address it starts from. */
function nextSearch(prev: Record<string, unknown> = {}) {
  const call = routerState.navigate.mock.lastCall![0] as { search: (p: Record<string, unknown>) => unknown };
  return call.search(prev);
}

/** Mantine names both the input and its option list by the label; the input is the control. */
function selectInput(label: string) {
  return screen.getAllByLabelText(label).find((el) => el.tagName === 'INPUT') as HTMLInputElement;
}

describe('FlowView controls', () => {
  beforeEach(() => {
    routerState.search = {};
    routerState.navigate.mockReset();
  });

  it('puts a chosen ranking, grouping and bound in the address, and leaves a default out of it', async () => {
    serve(graph());
    const user = userEvent.setup();
    renderWithProviders(<FlowView />);

    await screen.findByRole('region', { name: 'Totals across every path' });
    await user.click(selectInput('Rank paths by'));
    await user.click(await screen.findByRole('option', { name: 'messages out' }));
    expect(nextSearch({ tab: 'table' })).toEqual({ tab: 'table', rank: 'OUT' });

    await user.click(selectInput('Group clients by'));
    await user.click(await screen.findByRole('option', { name: 'User' }));
    expect(nextSearch()).toEqual({ groupBy: 'USER' });

    await user.click(selectInput('Show'));
    await user.click(await screen.findByRole('option', { name: '100 busiest paths' }));
    expect(nextSearch()).toEqual({ limit: 100 });
  });

  it('takes a choice back to its default out of the address rather than writing the default', async () => {
    routerState.search = { rank: 'OUT', groupBy: 'USER', limit: 100 };
    serve(graph());
    const user = userEvent.setup();
    renderWithProviders(<FlowView />);

    await screen.findByRole('region', { name: 'Totals across every path' });
    await user.click(selectInput('Rank paths by'));
    await user.click(await screen.findByRole('option', { name: 'messages in' }));
    expect(nextSearch()).toEqual({ rank: undefined });

    await user.click(selectInput('Group clients by'));
    await user.click(await screen.findByRole('option', { name: 'Client ID' }));
    expect(nextSearch()).toEqual({ groupBy: undefined });

    await user.click(selectInput('Show'));
    await user.click(await screen.findByRole('option', { name: '40 busiest paths' }));
    expect(nextSearch()).toEqual({ limit: undefined });
  });

  it('states the focus, widens its reach by hops, and steps back to the neighbours', async () => {
    routerState.search = { focus: 'subscription:orders', hops: 2 };
    serve(graph({ focus: { kind: 'queue', name: 'orders', hops: 2, matched: true } }));
    const user = userEvent.setup();
    renderWithProviders(<FlowView />);

    expect(await screen.findByText('Focused on subscription orders')).toBeInTheDocument();
    // A focused view has reached everything it can, so it does not suggest raising the limit.
    expect(screen.queryByText(/Raise the limit, or focus/)).not.toBeInTheDocument();
    expect(screen.getByRole('radio', { name: '+1 hop' })).toBeChecked();

    await user.click(screen.getByRole('radio', { name: '+2 hops' }));
    expect(nextSearch({ focus: 'subscription:orders' })).toEqual({ focus: 'subscription:orders', hops: 3 });
    await user.click(screen.getByRole('radio', { name: 'Neighbours' }));
    expect(nextSearch({ focus: 'subscription:orders' })).toEqual({ focus: 'subscription:orders', hops: undefined });
  });

  it('states why the flow could not be read and asks again on request', async () => {
    let asked = 0;
    server.use(
      http.get('*/api/v1/clusters/c1/flow', () => {
        asked++;
        return HttpResponse.json({ title: 'Flow unavailable', detail: 'No node answered.' }, { status: 502 });
      }),
    );
    const user = userEvent.setup();
    renderWithProviders(<FlowView />);

    expect(await screen.findByText('Rahdar failed to complete the request')).toBeInTheDocument();
    expect(screen.getByText('No node answered.')).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Retry' }));
    await waitFor(() => expect(asked).toBe(2));
  });

  it('marks the view busy while the flow loads', async () => {
    server.use(
      http.get('*/api/v1/clusters/c1/flow', async () => {
        await new Promise(() => {});
      }),
    );
    renderWithProviders(<FlowView />);

    const loading = await screen.findByRole('status');
    expect(loading).toHaveTextContent('Loading flow');
    expect(loading).toHaveAttribute('aria-busy', 'true');
  });

  it('states the bound, the server cap and when clients were last sampled', async () => {
    serve(
      graph({
        measuring: false,
        sampledAt: new Date(Date.now() - 30_000).toISOString(),
        totals: { paths: 1, shown: 1, limit: 200, clamped: true },
        assumptions: ['Rates are averaged over the last minute.'],
      }),
    );
    renderWithProviders(<FlowView />);

    expect(await screen.findByText('Showing 1 of 1 path, ranked by messages in.')).toBeInTheDocument();
    expect(screen.getByText('The server draws at most 200 paths.')).toBeInTheDocument();
    expect(screen.getByText(/Clients sampled .* ago\./)).toBeInTheDocument();
    expect(screen.getByText('Rates are averaged over the last minute.')).toBeInTheDocument();
    expect(screen.queryByText(/Raise the limit, or focus/)).not.toBeInTheDocument();
    expect(screen.queryByText(/Measuring client rates/)).not.toBeInTheDocument();
  });

  it('pauses and resumes the motion on the graph', async () => {
    serve(graph());
    const user = userEvent.setup();
    renderWithProviders(<FlowView />);

    const pause = await screen.findByRole('button', { name: 'Pause motion' });
    expect(pause).toHaveAttribute('aria-pressed', 'false');
    await user.click(pause);
    expect(screen.getByRole('button', { name: 'Resume motion' })).toHaveAttribute('aria-pressed', 'true');
    await user.click(screen.getByRole('button', { name: 'Resume motion' }));
    expect(screen.getByRole('button', { name: 'Pause motion' })).toBeInTheDocument();
  });

  it('offers no motion control on the table, which has nothing that moves', async () => {
    routerState.search = { tab: 'table' };
    serve(graph());
    renderWithProviders(<FlowView />);

    await screen.findAllByRole('columnheader');
    expect(screen.queryByRole('button', { name: /motion/ })).not.toBeInTheDocument();
  });

  it('focuses the view on a client, address or queue chosen from the find box', async () => {
    serve(graph());
    const user = userEvent.setup();
    renderWithProviders(<FlowView />);

    await screen.findByRole('region', { name: 'Totals across every path' });
    await user.click(selectInput('Find in this view'));
    await user.click(await screen.findByRole('option', { name: 'order-svc' }));

    expect(nextSearch({ node: 'subscription:ORDERS.inbound', hops: 3 })).toEqual({
      node: undefined,
      hops: undefined,
      focus: 'client:order-svc',
    });
  });

  it('sorts the table from a column and focuses on a path endpoint from its menu', async () => {
    routerState.search = { tab: 'table' };
    serve(graph());
    const user = userEvent.setup();
    renderWithProviders(<FlowView />);

    await user.click(within(await screen.findByRole('columnheader', { name: /From/ })).getByRole('button'));
    expect(nextSearch({ focus: 'x' })).toEqual({ focus: 'x', sort: 'from' });

    await user.click(await screen.findByRole('button', { name: 'Actions for order-svc' }));
    await user.click(await screen.findByRole('menuitem', { name: /Focus the view on this/ }));
    // The menu is the resource's own action: it navigates to the flow of the cluster, focused.
    expect(routerState.navigate.mock.lastCall![0]).toEqual({
      to: '/clusters/c1/flow',
      search: { focus: 'client:order-svc' },
    });
  });

  it('focuses from the inspector, and closes it back to no selection', async () => {
    routerState.search = { node: 'subscription:ORDERS.inbound' };
    serve(graph());
    const user = userEvent.setup();
    renderWithProviders(<FlowView />);

    const inspector = await screen.findByRole('complementary', { name: 'Details of Queue orders' });
    await user.click(within(inspector).getByRole('button', { name: /^Focus/ }));
    expect(nextSearch({ node: 'subscription:ORDERS.inbound' })).toEqual({
      focus: 'subscription:orders',
      hops: undefined,
      node: undefined,
    });

    await user.click(within(inspector).getByRole('button', { name: 'Close details' }));
    expect(nextSearch({ node: 'subscription:ORDERS.inbound' })).toEqual({ node: undefined });
  });

  it('changes the window of the monitoring pane, leaving the default out of the address', async () => {
    routerState.search = { tab: 'split', node: 'subscription:ORDERS.inbound' };
    serve(splitGraph());
    serveHistory();
    const user = userEvent.setup();
    renderWithProviders(<FlowView />);

    const pane = await screen.findByRole('region', { name: 'Queue orders' });
    await user.click(await within(pane).findByRole('radio', { name: '6h' }));
    expect(nextSearch({ tab: 'split' })).toEqual({ tab: 'split', range: '6h' });
  });

  it('drops the range from the address when the pane goes back to an hour', async () => {
    routerState.search = { tab: 'split', node: 'subscription:ORDERS.inbound', range: '6h' };
    serve(splitGraph());
    serveHistory();
    const user = userEvent.setup();
    renderWithProviders(<FlowView />);

    const pane = await screen.findByRole('region', { name: 'Queue orders' });
    await user.click(await within(pane).findByRole('radio', { name: '1h' }));
    expect(nextSearch({ range: '6h' })).toEqual({ range: undefined });
  });
});
