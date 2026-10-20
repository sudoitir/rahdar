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

import { ActionHostProvider } from '../../kernel/actions/ActionHost.tsx';
import { FeatureProvider } from '../../kernel/FeatureProvider.tsx';
import { axeViolations, contentWidth, Frame, renderThemed, SCHEMES, settle } from '../../test/browser.tsx';
import { ConsumerHealthView } from './ConsumerHealthView.tsx';
import { QueueHealthPanel } from './QueueHealthPanel.tsx';

/**
 * Consumer health in a real browser: one h1, no accessibility violations in either colour scheme, and no
 * layout shift when the rows replace the loading state (the sweep holds every route to 0.01). The data
 * arrives after a delay, as it does from the server.
 */
const row = (queueName: string, verdict: string, severity: number, over: Record<string, unknown> = {}) => ({
  destination: `artemis.address/${queueName}`,
  subscription: `artemis.queue/${queueName}`,
  verdict,
  severity,
  cause: 'The consumer is blocked, or a message it cannot process is being redelivered.',
  source: 'DERIVED',
  brokerConsumerName: null,
  depth: 5000,
  consumers: 3,
  delivering: 30,
  scheduled: 0,
  paused: false,
  depthSlopePerSecond: 1.5,
  addRate: 10,
  ackRate: 0,
  netRate: 10,
  ackRatePerConsumer: 0,
  drainEtaSeconds: null,
  asOf: '2026-09-20T10:00:00Z',
  sampleSpanSeconds: 30,
  stale: false,
  nodesPresent: 1,
  nodesTotal: 1,
  ...over,
});

const ROWS = [
  row('orders.inbound', 'STALLED', 4),
  row('orders.retry.with.a.long.name.that.is.shortened', 'FALLING_BEHIND', 2, { stale: true }),
  row('billing', 'HEALTHY', 0, { ackRate: 8 }),
  row('new-queue', 'INSUFFICIENT_DATA', 0, { ackRate: null, addRate: null }),
];

const ME = {
  id: 'u1',
  username: 'admin',
  mustChangePassword: false,
  grants: [{ scopeType: 'GLOBAL', scopeId: null, permissions: ['*'] }],
};

const CLUSTER = { id: 'c1', name: 'prod', topology: { nodes: [] }, capabilities: {} };

function bodyFor(path: string, query: string): unknown {
  if (path.endsWith('/auth/me')) return ME;
  if (path.endsWith('/consumer-health')) {
    let rows = query.includes('subscription=') ? ROWS.slice(0, 1) : ROWS;
    if (query.includes('q=nope')) rows = [];
    return { data: rows, count: rows.length, page: 1, pageSize: 200 };
  }
  return CLUSTER;
}

beforeEach(() => {
  vi.stubGlobal('fetch', async (input: RequestInfo | URL) => {
    const url = new URL(String(input), location.origin);
    await new Promise((resolve) => setTimeout(resolve, 150));
    return new Response(JSON.stringify(bodyFor(url.pathname, url.search)), {
      headers: { 'content-type': 'application/json' },
    });
  });
});
afterEach(() => vi.unstubAllGlobals());

function page(search = '') {
  const root = createRootRoute();
  const route = createRoute({
    getParentRoute: () => root,
    path: '/clusters/$clusterId/consumer-health',
    component: ConsumerHealthView,
  });
  return createRouter({
    routeTree: root.addChildren([route]),
    history: createMemoryHistory({ initialEntries: [`/clusters/c1/consumer-health${search}`] }),
  });
}

function Providers({ children }: Readonly<{ children: React.ReactNode }>) {
  return (
    <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
      <FeatureProvider features={[]}>
        <ActionHostProvider>{children}</ActionHostProvider>
      </FeatureProvider>
    </QueryClientProvider>
  );
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

const WIDTHS = [
  { name: '1280 px', width: () => contentWidth(1280) },
  { name: '200% zoom', width: () => 560 },
] as const;

describe('Consumer health', () => {
  describe.each(SCHEMES)('in the %s scheme at 1280 px', (scheme) => {
    it('has one h1, no accessibility violations and nothing scrolling sideways', async () => {
      const { container } = renderThemed(
        <Providers>
          <Frame width={contentWidth(1280)} height={900}>
            <RouterProvider router={page()} />
          </Frame>
        </Providers>,
        scheme,
      );
      await screen.findByText('Stalled', undefined, { timeout: 5000 });
      await settle(() => `${container.scrollHeight}:${container.querySelectorAll('[role="row"]').length}`);

      expect(screen.getAllByRole('heading', { level: 1 })).toHaveLength(1);
      expect(await axeViolations(container)).toEqual([]);
      expect(container.scrollWidth).toBeLessThanOrEqual(container.clientWidth + 1);
    });
  });

  describe.each(WIDTHS)('at $name', ({ width }) => {
    it('swaps loading for rows without moving what is already on the page', async () => {
      const done = watchShift();
      const { container } = renderThemed(
        <Providers>
          <Frame width={width()} height={900}>
            <RouterProvider router={page()} />
          </Frame>
        </Providers>,
        'light',
      );
      await screen.findByRole('heading', { level: 1, name: 'Consumer health' });
      await screen.findByText('Stalled', undefined, { timeout: 5000 });
      await settle(() => `${container.scrollHeight}:${container.querySelectorAll('[role="row"]').length}`);
      await new Promise((resolve) => setTimeout(resolve, 500));

      expect(done()).toBeLessThanOrEqual(0.01);
    });
  });
});

describe('Consumer health, filtered to nothing', () => {
  describe.each(WIDTHS)('at $name', ({ width }) => {
    it('swaps loading for the filtered-empty state without moving what is already on the page', async () => {
      const done = watchShift();
      const { container } = renderThemed(
        <Providers>
          <Frame width={width()} height={900}>
            <RouterProvider router={page('?q=nope')} />
          </Frame>
        </Providers>,
        'dark',
      );
      await screen.findByRole('heading', { level: 1, name: 'Consumer health' });
      await screen.findByText(/No queue matches "nope"/, undefined, { timeout: 5000 });
      await settle(() => `${container.scrollHeight}`);
      await new Promise((resolve) => setTimeout(resolve, 500));

      expect(done()).toBeLessThanOrEqual(0.01);
    });
  });
});

describe('Consumer health in a queue drawer', () => {
  describe.each(SCHEMES)('in the %s scheme', (scheme) => {
    it('has no accessibility violations once the verdict is read', async () => {
      const { container } = renderThemed(
        <Providers>
          <Frame width={420} height={420}>
            <QueueHealthPanel clusterId="c1" queueName="orders.inbound" />
          </Frame>
        </Providers>,
        scheme,
      );
      await screen.findByRole('heading', { level: 3, name: 'Consumer health' });
      await screen.findByText('Stalled', undefined, { timeout: 5000 });
      await settle(() => `${container.scrollHeight}`);

      expect(await axeViolations(container)).toEqual([]);
    });
  });
});
