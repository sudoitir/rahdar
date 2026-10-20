import { describe, expect, it, vi } from 'vitest';
import { screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

import { renderWithProviders } from '../../test/render.tsx';
import type { FlowGraphView } from './api.ts';

const navigate = vi.hoisted(() => vi.fn());
vi.mock('@tanstack/react-router', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@tanstack/react-router')>()),
  useNavigate: () => navigate,
}));

const { FlowInspector } = await import('./FlowInspector.tsx');

const graph = {
  nodes: [
    {
      id: 'destination:ORDERS.inbound',
      kind: 'DESTINATION',
      label: 'ORDERS.inbound',
      routingTypes: ['ANYCAST'],
      faults: [],
    },
    {
      id: 'subscription:ORDERS.inbound',
      kind: 'SUBSCRIPTION',
      label: 'ORDERS.inbound',
      messageCount: 1200,
      consumerCount: 0,
      brokerNodes: ['node-a'],
      faults: ['NO_CONSUMER'],
    },
  ],
  edges: [
    {
      id: 'route',
      kind: 'ROUTE',
      source: 'destination:ORDERS.inbound',
      target: 'subscription:ORDERS.inbound',
      rate: 42,
      rateSource: 'SUBSCRIPTION_METRIC',
      stale: false,
      delivery: 'SHARED',
      faults: [],
      bypassed: false,
      rahdar: false,
    },
  ],
  measuring: false,
  sampleIntervalSeconds: 15,
} as FlowGraphView;

describe('FlowInspector', () => {
  it('explains a subscription: figures, faults in words, and the flow into it', () => {
    renderWithProviders(
      <FlowInspector
        graph={graph}
        nodeId="subscription:ORDERS.inbound"
        clusterId="c1"
        onClose={() => {}}
        onFocus={() => {}}
      />,
    );

    const details = screen.getByRole('complementary', { name: 'Details of Subscription ORDERS.inbound' });
    expect(details).toHaveTextContent('1,200 waiting');
    expect(details).toHaveTextContent('no consumer');
    expect(details).toHaveTextContent('shared · 42 msg/s');
    expect(details).toHaveTextContent('Seen onnode-a');
  });

  it('takes focus on open and closes on Escape', async () => {
    const onClose = vi.fn();
    renderWithProviders(
      <FlowInspector
        graph={graph}
        nodeId="subscription:ORDERS.inbound"
        clusterId="c1"
        onClose={onClose}
        onFocus={() => {}}
      />,
    );

    expect(screen.getByRole('button', { name: 'Close details' })).toHaveFocus();
    await userEvent.keyboard('{Escape}');
    expect(onClose).toHaveBeenCalled();
  });

  it('focuses the view and opens the exact subscription without mutating anything', async () => {
    const onFocus = vi.fn();
    renderWithProviders(
      <FlowInspector
        graph={graph}
        nodeId="subscription:ORDERS.inbound"
        clusterId="c1"
        onClose={() => {}}
        onFocus={onFocus}
      />,
    );

    await userEvent.click(screen.getByRole('button', { name: 'Focus the view on this' }));
    expect(onFocus).toHaveBeenCalledWith('subscription:ORDERS.inbound');

    await userEvent.click(screen.getByRole('button', { name: 'Open in Subscriptions' }));
    expect(navigate).toHaveBeenCalledWith({
      to: '/clusters/$clusterId/subscriptions',
      params: { clusterId: 'c1' },
      search: { subscription: 'ORDERS.inbound' },
    });
  });
});

const edge = (over: Record<string, unknown>) => ({
  id: 'e',
  kind: 'ROUTE',
  source: 'a',
  target: 'b',
  rateSource: 'UNKNOWN',
  stale: false,
  faults: [],
  bypassed: false,
  rahdar: false,
  ...over,
});

function inspect(node: Record<string, unknown>, edges: unknown[] = [], extra: unknown[] = []) {
  const g = { nodes: [node, ...extra], edges, measuring: false, sampleIntervalSeconds: 15 } as unknown as FlowGraphView;
  const onFocus = vi.fn();
  const view = renderWithProviders(
    <FlowInspector graph={g} nodeId={String(node.id)} clusterId="c1" onClose={() => {}} onFocus={onFocus} />,
  );
  return { ...view, onFocus };
}

describe('FlowInspector other resources', () => {
  it('renders nothing for a node the graph no longer holds', () => {
    renderWithProviders(
      <FlowInspector graph={graph} nodeId="subscription:gone" clusterId="c1" onClose={() => {}} onFocus={() => {}} />,
    );
    expect(screen.queryByRole('complementary')).toBeNull();
    expect(screen.queryByRole('button', { name: 'Close details' })).toBeNull();
  });

  it('says a subscription that has not been swept yet has no figures yet, rather than zero', () => {
    inspect({ id: 'subscription:q', kind: 'SUBSCRIPTION', label: 'q' });

    const details = screen.getByRole('complementary', { name: 'Details of Subscription q' });
    expect(details).toHaveTextContent('Backlognot swept yet');
    expect(details).toHaveTextContent('Consumersnot swept yet');
    expect(details).not.toHaveTextContent('0 waiting');
  });

  it('states a subscription with consumers and a backlog of nothing', () => {
    inspect({ id: 'subscription:q', kind: 'SUBSCRIPTION', label: 'q', messageCount: 0, consumerCount: 3 });

    const details = screen.getByRole('complementary', { name: 'Details of Subscription q' });
    expect(details).toHaveTextContent('Backlog0 waiting');
    expect(details).toHaveTextContent('Consumers3');
  });

  it('explains a destination by its routing and opens the destinations screen filtered to it', async () => {
    navigate.mockClear();
    const { onFocus } = inspect({
      id: 'destination:a',
      kind: 'DESTINATION',
      label: 'a',
      routingTypes: ['ANYCAST', 'MULTICAST'],
    });

    expect(screen.getByRole('complementary', { name: 'Details of Destination a' })).toHaveTextContent(
      'Routinganycast, multicast',
    );
    await userEvent.click(screen.getByRole('button', { name: 'Open in Destinations' }));
    expect(navigate).toHaveBeenCalledWith({
      to: '/clusters/$clusterId/destinations',
      params: { clusterId: 'c1' },
      search: { q: 'a' },
    });
    await userEvent.click(screen.getByRole('button', { name: 'Focus the view on this' }));
    expect(onFocus).toHaveBeenCalledWith('destination:a');
    expect(screen.queryByRole('button', { name: 'Open in Subscriptions' })).toBeNull();
  });

  it('explains a producing client by its connections, and offers its producers and connections', async () => {
    navigate.mockClear();
    const { onFocus } = inspect({
      id: 'producer:p',
      kind: 'PRODUCER',
      label: 'billing',
      members: 4,
      protocols: ['AMQP', 'CORE'],
      hosts: ['10.0.0.1'],
      users: ['svc-billing'],
    });

    const details = screen.getByRole('complementary', { name: 'Details of Producing client billing' });
    expect(details).toHaveTextContent('Connections4');
    expect(details).toHaveTextContent('ProtocolsAMQP, CORE');
    expect(details).toHaveTextContent('Hosts10.0.0.1');
    expect(details).toHaveTextContent('Userssvc-billing');
    expect(screen.queryByRole('button', { name: 'Open its consumers' })).toBeNull();

    await userEvent.click(screen.getByRole('button', { name: 'Open its producers' }));
    expect(navigate).toHaveBeenLastCalledWith({
      to: '/clusters/$clusterId/producers',
      params: { clusterId: 'c1' },
      search: { q: 'billing' },
    });
    await userEvent.click(screen.getByRole('button', { name: 'Open its connections' }));
    expect(navigate).toHaveBeenLastCalledWith({
      to: '/clusters/$clusterId/connections',
      params: { clusterId: 'c1' },
      search: { q: 'billing' },
    });
    await userEvent.click(screen.getByRole('button', { name: 'Focus the view on this' }));
    expect(onFocus).toHaveBeenCalledWith('client:billing');
  });

  it('offers a consuming client its consumers and connections', async () => {
    navigate.mockClear();
    inspect({ id: 'consumer:c', kind: 'CONSUMER', label: 'shipping' });

    expect(screen.getByRole('complementary', { name: 'Details of Consuming client shipping' })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Open its producers' })).toBeNull();
    await userEvent.click(screen.getByRole('button', { name: 'Open its consumers' }));
    expect(navigate).toHaveBeenLastCalledWith({
      to: '/clusters/$clusterId/consumers',
      params: { clusterId: 'c1' },
      search: { q: 'shipping' },
    });
    expect(screen.getByRole('button', { name: 'Open its connections' })).toBeInTheDocument();
  });

  it('says what a synthetic node stands for and offers no screen for it', () => {
    inspect({ id: 'remote:r', kind: 'REMOTE', label: 'dr-site', role: 'LINK_TARGET' });

    expect(screen.getByRole('complementary', { name: 'Details of Remote dr-site' })).toHaveTextContent(
      'What it isA link target outside this cluster',
    );
    // A remote broker is not something this cluster can focus on or open.
    expect(screen.queryByRole('button', { name: 'Focus the view on this' })).toBeNull();
    expect(screen.getByText(/Flow never changes the broker/)).toBeInTheDocument();
  });

  it('keeps a subscription with a role from opening the Subscriptions screen, since it is not one subscription', () => {
    inspect({
      id: 'subscription:t',
      kind: 'SUBSCRIPTION',
      label: 'temporary subscriptions',
      role: 'TEMPORARY',
      members: 12,
    });

    expect(screen.getByRole('complementary')).toHaveTextContent('What it isTemporary subscriptions, collapsed');
    expect(screen.queryByRole('button', { name: 'Open in Subscriptions' })).toBeNull();
    expect(screen.getByRole('button', { name: 'Focus the view on this' })).toBeInTheDocument();
  });

  it('names a role and a fault it has no wording for as the server sent them, in lower case', () => {
    inspect({
      id: 'destination:x',
      kind: 'DESTINATION',
      label: 'x',
      role: 'NEW_ROLE',
      faults: ['STALLED', 'BRAND_NEW'],
    });

    const details = screen.getByRole('complementary');
    expect(details).toHaveTextContent('What it isnew_role');
    expect(details).toHaveTextContent('stalled, brand_new');
    expect(screen.queryByRole('button', { name: 'Open in Destinations' })).toBeNull();
  });

  it('describes a node of no known kind without a kind word', () => {
    inspect({ id: 'n', label: 'mystery' });

    expect(screen.getByRole('complementary', { name: 'Details of node mystery' })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Focus the view on this' })).toBeNull();
    // No facts, no flows: the definition list is not rendered empty.
    expect(document.querySelector('dl')).toBeNull();
  });

  it('lists the flows in and out, each with the other end, how it was measured and how old that is', () => {
    const asOf = new Date(Date.now() - 3 * 60_000).toISOString();
    inspect(
      { id: 'subscription:mid', kind: 'SUBSCRIPTION', label: 'mid', messageCount: 5, consumerCount: 1 },
      [
        edge({
          id: 'in',
          source: 'producer:p',
          target: 'subscription:mid',
          kind: 'PRODUCE',
          rate: 3,
          rateSource: 'SAMPLER',
          asOf,
        }),
        edge({
          id: 'out',
          source: 'subscription:mid',
          target: 'subscription:gone',
          kind: 'ROUTE',
          rateSource: 'SUBSCRIPTION_METRIC',
          averagedOverSeconds: 300,
          stale: true,
          faults: ['STALLED'],
        }),
      ],
      [{ id: 'producer:p', kind: 'PRODUCER', label: 'billing' }],
    );

    const details = screen.getByRole('complementary');
    expect(screen.getByText('Flow in')).toBeInTheDocument();
    expect(screen.getByText('Flow out')).toBeInTheDocument();
    expect(within(details).getByText('billing')).toBeInTheDocument();
    expect(within(details).getByText(/sampled clients · .+ ago/)).toBeInTheDocument();
    // An edge whose far end is not in the graph shows a dash, not a blank; stale and faults are worded.
    expect(within(details).getByText('—')).toBeInTheDocument();
    expect(within(details).getByText(/subscription metrics, ~5 min average/)).toBeInTheDocument();
    expect(within(details).getByText(/stalled/)).toBeInTheDocument();
    expect(within(details).getByText(/stale/)).toBeInTheDocument();
  });

  it('omits the flow lists when nothing flows through the node', () => {
    inspect({ id: 'subscription:q', kind: 'SUBSCRIPTION', label: 'q', messageCount: 1, consumerCount: 1 });

    expect(screen.queryByText('Flow in')).toBeNull();
    expect(screen.queryByText('Flow out')).toBeNull();
  });

  it('moves focus to the close button when it is pointed at another node, and closes from it', async () => {
    const onClose = vi.fn();
    const g = {
      nodes: [
        { id: 'subscription:a', kind: 'SUBSCRIPTION', label: 'a' },
        { id: 'subscription:b', kind: 'SUBSCRIPTION', label: 'b' },
      ],
      edges: [],
    } as unknown as FlowGraphView;
    const view = renderWithProviders(
      <FlowInspector graph={g} nodeId="subscription:a" clusterId="c1" onClose={onClose} onFocus={() => {}} />,
    );
    screen.getByRole('button', { name: 'Focus the view on this' }).focus();

    view.rerender(
      <FlowInspector graph={g} nodeId="subscription:b" clusterId="c1" onClose={onClose} onFocus={() => {}} />,
    );
    expect(screen.getByRole('complementary', { name: 'Details of Subscription b' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Close details' })).toHaveFocus();

    await userEvent.click(screen.getByRole('button', { name: 'Close details' }));
    expect(onClose).toHaveBeenCalledTimes(1);
  });

  it('is a card with the node as its heading, its facts as terms and values, and a close control', () => {
    renderWithProviders(
      <FlowInspector
        graph={graph}
        nodeId="subscription:ORDERS.inbound"
        clusterId="c1"
        onClose={() => {}}
        onFocus={() => {}}
      />,
    );

    const details = screen.getByRole('complementary', { name: 'Details of Subscription ORDERS.inbound' });
    expect(within(details).getByRole('heading', { level: 3, name: 'ORDERS.inbound' })).toBeInTheDocument();
    const facts = within(details).getByRole('group', { name: 'Facts' });
    expect(within(facts).getByText('Backlog')).toBeInTheDocument();
    expect(within(facts).getByText('1,200 waiting')).toBeInTheDocument();
    expect(within(details).getByRole('list', { name: 'Flow in' })).toBeInTheDocument();
    expect(within(details).queryByRole('table')).not.toBeInTheDocument();
    expect(within(details).getByRole('button', { name: 'Close details' })).toBeInTheDocument();
  });
});
