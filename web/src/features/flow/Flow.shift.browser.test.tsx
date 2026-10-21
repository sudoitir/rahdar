import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { screen } from '@testing-library/react';
import {
  createMemoryHistory,
  createRootRoute,
  createRoute,
  createRouter,
  RouterProvider,
} from '@tanstack/react-router';

import { FeatureProvider } from '../../kernel/FeatureProvider.tsx';
import { contentWidth, Frame, renderThemed, settle } from '../../test/browser.tsx';
import { FlowView } from './FlowView.tsx';

/**
 * The Flow page swaps its loading frame for the totals and the graph (or the table), and the sweep holds
 * every route to 0.01 of layout shift from navigation start. Here the flow arrives after a delay, as it
 * does from the server, and the shift of the page's own parts is measured around it.
 */
const GRAPH = {
  nodes: [
    { id: 'destination:orders', kind: 'DESTINATION', label: 'orders', routingTypes: ['ANYCAST'], faults: [] },
    {
      id: 'subscription:ORDERS.inbound',
      kind: 'SUBSCRIPTION',
      label: 'ORDERS.inbound',
      messageCount: 1200,
      consumerCount: 1,
      faults: [],
    },
    { id: 'producer:order-svc', kind: 'PRODUCER', label: 'order-svc', members: 2, faults: [] },
  ],
  edges: [
    {
      id: 'route',
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
      id: 'produce',
      kind: 'PRODUCE',
      source: 'producer:order-svc',
      target: 'destination:orders',
      rateSource: 'SAMPLER',
      stale: false,
      members: 2,
      faults: [],
    },
  ],
  kpis: { inRate: 42, outRate: 40, backlog: 1200, clients: 1, faults: 0 },
  totals: { paths: 2, shown: 2, limit: 40, clamped: false },
  focus: null,
  sampledAt: '2026-09-14T10:00:00Z',
  measuring: false,
  sampleIntervalSeconds: 15,
  brokerNodes: [],
  assumptions: [],
};

const ME = {
  id: 'u1',
  username: 'admin',
  mustChangePassword: false,
  grants: [{ scopeType: 'GLOBAL', scopeId: null, permissions: ['*'] }],
};

beforeEach(() => {
  vi.stubGlobal('fetch', async (input: RequestInfo | URL) => {
    const path = new URL(String(input), location.origin).pathname;
    await new Promise((resolve) => setTimeout(resolve, 150));
    return new Response(JSON.stringify(path.endsWith('/auth/me') ? ME : GRAPH), {
      headers: { 'content-type': 'application/json' },
    });
  });
});
afterEach(() => vi.unstubAllGlobals());

function page(search: string) {
  const root = createRootRoute();
  const route = createRoute({ getParentRoute: () => root, path: '/clusters/$clusterId/flow', component: FlowView });
  return createRouter({
    routeTree: root.addChildren([route]),
    history: createMemoryHistory({ initialEntries: [`/clusters/c1/flow${search}`] }),
  });
}

/** The layout shift the page adds between mounting and settling, as the browser reports it. */
function watchShift(): () => number {
  let total = 0;
  const observer = new PerformanceObserver((list) => {
    for (const entry of list.getEntries() as unknown as { value: number; hadRecentInput: boolean }[]) {
      if (!entry.hadRecentInput) total += entry.value;
    }
  });
  observer.observe({ type: 'layout-shift', buffered: false });
  return () => {
    observer.takeRecords();
    observer.disconnect();
    return total;
  };
}

const LAYOUTS = [
  { name: 'graph', search: '' },
  { name: 'table', search: '?tab=table' },
] as const;

/** The window the console is laid out for, and the same window at 200% zoom, where the page wraps. */
const WIDTHS = [
  { name: '1280 px', width: () => contentWidth(1280) },
  { name: '200% zoom', width: () => 560 },
] as const;

describe.each(LAYOUTS.flatMap((l) => WIDTHS.map((w) => ({ ...l, ...w, title: `Flow, ${l.name}, at ${w.name}` }))))(
  '$title',
  ({ search, width }) => {
    it('swaps loading for content without moving what is already on the page', async () => {
      const done = watchShift();
      const { container } = renderThemed(
        <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
          <FeatureProvider features={[]}>
            <Frame width={width()} height={3200}>
              <RouterProvider router={page(search)} />
            </Frame>
          </FeatureProvider>
        </QueryClientProvider>,
        'light',
      );
      await screen.findByRole('heading', { level: 1, name: 'Flow' });
      await screen.findByRole('heading', { level: 2, name: 'Totals across every path' }, { timeout: 5000 });
      await settle(() => `${container.scrollHeight}:${container.querySelectorAll('[role="button"], table').length}`);
      await new Promise((resolve) => setTimeout(resolve, 500));

      expect(done()).toBeLessThanOrEqual(0.01);
    });
  },
);
