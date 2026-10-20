import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import { onlineManager, QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { screen } from '@testing-library/react';
import {
  createMemoryHistory,
  createRootRoute,
  createRoute,
  createRouter,
  RouterProvider,
} from '@tanstack/react-router';

import { keys as authKeys } from '../../kernel/auth/api.ts';
import { FeatureProvider } from '../../kernel/FeatureProvider.tsx';
import { axeViolations, contentWidth, Frame, renderThemed, SCHEMES, settle } from '../../test/browser.tsx';
import { flowQueryString } from './api.ts';
import { FlowView } from './FlowView.tsx';

/**
 * The Flow page in a real browser at the narrowest window the console is designed for (1280 px beside the
 * expanded navigation), in both colour schemes: one h1, no accessibility violations in any layout (the
 * graph, its inspector, the table and the split with its monitoring pane), and nothing scrolling sideways.
 * No network: every query is answered from the seeded cache.
 */
beforeAll(() => onlineManager.setOnline(false));
afterAll(() => onlineManager.setOnline(true));

const share = (nodeId: string, node: string, over: Record<string, unknown> = {}) => ({
  nodeId,
  node,
  messageCount: 10,
  consumerCount: 1,
  inRate: 5,
  outRate: 5,
  stale: false,
  ...over,
});

function graph(byNode: boolean) {
  return {
    nodes: [
      { id: 'destination:orders', kind: 'DESTINATION', label: 'orders', routingTypes: ['ANYCAST'], faults: [] },
      {
        id: 'subscription:ORDERS.inbound',
        kind: 'SUBSCRIPTION',
        label: 'ORDERS.inbound',
        messageCount: 1200,
        consumerCount: 0,
        faults: ['NO_CONSUMER'],
        ...(byNode
          ? { byNode: [share('a', 'artemis-a'), share('b', 'artemis-b', { messageCount: 9000, consumerCount: 0 })] }
          : {}),
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
    kpis: { inRate: 42, outRate: null, backlog: 1200, clients: 1, faults: 1 },
    totals: { paths: 5, shown: 2, limit: 40, clamped: false },
    focus: null,
    sampledAt: '2026-09-14T10:00:00Z',
    measuring: false,
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
    assumptions: [],
  };
}

function seeded(tab: 'table' | 'split' | undefined): QueryClient {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false, staleTime: Infinity, gcTime: Infinity } },
  });
  client.setQueryData(authKeys.me, {
    id: 'u1',
    username: 'admin',
    mustChangePassword: false,
    grants: [{ scopeType: 'GLOBAL', scopeId: null, permissions: ['*'] }],
  });
  client.setQueryData(['clusters', 'c1', 'flow', flowQueryString({ tab })], graph(tab === 'split'));
  return client;
}

function page(search: string) {
  const root = createRootRoute();
  const route = createRoute({ getParentRoute: () => root, path: '/clusters/$clusterId/flow', component: FlowView });
  return createRouter({
    routeTree: root.addChildren([route]),
    history: createMemoryHistory({ initialEntries: [`/clusters/c1/flow${search}`] }),
  });
}

const LAYOUTS = [
  { name: 'graph', tab: undefined, search: '' },
  { name: 'graph with its inspector', tab: undefined, search: '?node=subscription%3AORDERS.inbound' },
  { name: 'table', tab: 'table', search: '?tab=table' },
  { name: 'split, nothing selected', tab: 'split', search: '?tab=split' },
  { name: 'split, a subscription selected', tab: 'split', search: '?tab=split&node=subscription%3AORDERS.inbound' },
] as const;

describe.each(LAYOUTS)('Flow, $name', ({ tab, search }) => {
  describe.each(SCHEMES)('in the %s scheme at 1280 px', (scheme) => {
    it('has one h1, no accessibility violations and nothing scrolling sideways', async () => {
      const width = contentWidth(1280);
      const { container } = renderThemed(
        <QueryClientProvider client={seeded(tab)}>
          <FeatureProvider features={[]}>
            <Frame width={width} height={2400}>
              <RouterProvider router={page(search)} />
            </Frame>
          </FeatureProvider>
        </QueryClientProvider>,
        scheme,
      );
      await screen.findByRole('heading', { level: 2, name: 'Totals across every path' });
      // The graph is laid out in a worker; check it once the nodes are there, not while it is still busy.
      // The worker's first layout can take seconds on a loaded runner, as the routing builder's tests allow.
      if (tab !== 'table') await screen.findByRole('button', { name: /^ORDERS\.inbound/ }, { timeout: 15_000 });
      await settle(
        () => `${container.querySelectorAll('[role="button"], [role="grid"]').length}:${container.scrollHeight}`,
      );

      expect(screen.getAllByRole('heading', { level: 1 })).toHaveLength(1);
      expect(await axeViolations(container)).toEqual([]);
      expect(container.scrollWidth).toBeLessThanOrEqual(container.clientWidth + 1);
      if (tab !== 'table') {
        // WCAG 2.2 target size: the overview's own button is at least 24 by 24 CSS px. It starts open only
        // where the graph does not fit the canvas.
        const button = screen.getByRole('button', { name: /^(Show|Hide) overview$/ }).getBoundingClientRect();
        expect(button.width).toBeGreaterThanOrEqual(24);
        expect(button.height).toBeGreaterThanOrEqual(24);
      }
    });
  });
});
