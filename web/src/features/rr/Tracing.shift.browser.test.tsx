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

import { accessFor } from '../../test/accessSummary.ts';
import { contentWidth, Frame, renderThemed, settle } from '../../test/browser.tsx';
import { FlowsView } from './FlowsView.tsx';

/**
 * The tracing page swaps its loading states for its tables, and the sweep holds every route to 0.01 of
 * layout shift from navigation start. Here the data arrives after a delay, as it does from the server,
 * and the shift of the page's own parts is measured around it.
 */
const flow = (id: string, state: string) => ({
  id,
  clusterId: 'c1',
  nodeId: null,
  requestDestination: 'orders.request',
  replyDestination: null,
  replyKind: 'SHARED',
  state,
  correlationId: `corr-${id}`,
  requestedAt: '2026-09-04T10:00:00.000Z',
  deadlineAt: null,
  repliedAt: null,
  latencyMs: null,
});

const FLOWS = [flow('f1', 'AWAITING_REPLY'), flow('f2', 'COMPLETED'), flow('f3', 'TIMED_OUT')];

const EXPECTATION = {
  id: 'e1',
  requestDestination: 'orders.request',
  replyDestinations: ['orders.reply.*'],
  resolvedReplyDestinations: ['orders.reply.a'],
  replyDestinationsCapped: false,
  correlationProperty: null,
  deadlineMs: 30_000,
  samplePerMin: 10,
  capturePayload: false,
  enabled: true,
};

const ME = {
  id: 'u1',
  username: 'admin',
  mustChangePassword: false,
  grants: [{ scopeType: 'GLOBAL', scopeId: null, permissions: ['*'] }],
};

const CLUSTER = {
  id: 'c1',
  name: 'prod',
  capabilities: { notifications: { status: 'AVAILABLE', reason: 'ok', brokerXmlSnippet: null } },
};

const paged = (data: unknown[]) => ({ data, count: data.length, page: 1, pageSize: 100 });

function bodyFor(path: string): unknown {
  if (path.endsWith('/auth/me')) return ME;
  if (path.endsWith('/me/access')) return accessFor(ME.grants);
  if (path.endsWith('/rr/flows')) return paged(FLOWS);
  if (path.endsWith('/rr/expectations')) return paged([EXPECTATION]);
  if (path.endsWith('/rr/stats')) {
    return { destinations: [{ destination: 'orders.request', p50Ms: 20, p95Ms: 80, p99Ms: 120, coverageRatio: 0.5 }] };
  }
  if (path.endsWith('/rr/diagnostics')) return { expectations: [], reasons: [] };
  if (/\/clusters\/c1$/.test(path)) return CLUSTER;
  return paged([]);
}

beforeEach(() => {
  vi.stubGlobal('fetch', async (input: RequestInfo | URL) => {
    const path = new URL(String(input), location.origin).pathname;
    await new Promise((resolve) => setTimeout(resolve, 150));
    return new Response(JSON.stringify(bodyFor(path)), { headers: { 'content-type': 'application/json' } });
  });
});
afterEach(() => vi.unstubAllGlobals());

function page(search: string) {
  const root = createRootRoute();
  const route = createRoute({ getParentRoute: () => root, path: '/clusters/$clusterId/rr', component: FlowsView });
  return createRouter({
    routeTree: root.addChildren([route]),
    history: createMemoryHistory({ initialEntries: [`/clusters/c1/rr${search}`] }),
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

const TABS = [
  { name: 'Stuck', search: '?tab=stuck', ready: 'Stuck flows', row: /^timed out$/ },
  { name: 'Latency', search: '?tab=latency', ready: 'Latency', row: /Sampled, not exhaustive/ },
  { name: 'Flows', search: '', ready: 'Flows', row: /^timed out$/ },
  { name: 'Expectations', search: '?tab=expectations', ready: 'Traced destinations', row: /^or\s*ders\.request$/ },
] as const;

const WIDTHS = [
  { name: '1280 px', width: () => contentWidth(1280) },
  { name: '200% zoom', width: () => 560 },
] as const;

describe.each(TABS.flatMap((t) => WIDTHS.map((w) => ({ ...t, ...w, title: `${t.name} at ${w.name}` }))))(
  'Request-reply tracing, $title',
  ({ search, ready, row, width }) => {
    it('swaps loading for content without moving what is already on the page', async () => {
      const done = watchShift();
      const { container } = renderThemed(
        <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
          <Frame width={width()} height={2400}>
            <RouterProvider router={page(search)} />
          </Frame>
        </QueryClientProvider>,
        'light',
      );
      await screen.findByRole('heading', { level: 1, name: 'Request-reply tracing' });
      await screen.findByRole('heading', { level: 2, name: ready }, { timeout: 5000 });
      await screen.findByRole(search === '?tab=latency' ? 'heading' : 'rowheader', { name: row }, { timeout: 5000 });
      await settle(() => `${container.scrollHeight}:${container.querySelectorAll('[role="row"], tr').length}`);
      await new Promise((resolve) => setTimeout(resolve, 500));

      expect(done()).toBeLessThanOrEqual(0.01);
    });
  },
);
