import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import { onlineManager, QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import {
  createMemoryHistory,
  createRootRoute,
  createRoute,
  createRouter,
  RouterProvider,
} from '@tanstack/react-router';

import { keys as authKeys } from '../../kernel/auth/api.ts';
import { axeViolations, contentWidth, Frame, renderThemed, SCHEMES, settle } from '../../test/browser.tsx';
import { FlowsView } from './FlowsView.tsx';

/**
 * The request-reply tracing page in a real browser at the narrowest window the console is designed for
 * (1280 px beside the expanded navigation), in both colour schemes: one h1, no accessibility violations
 * on any tab, and nothing scrolling sideways. No network: every query is answered from the seeded cache.
 */
beforeAll(() => onlineManager.setOnline(false));
afterAll(() => onlineManager.setOnline(true));

const flow = (id: string, state: string, over: Record<string, unknown> = {}) => ({
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
  ...over,
});

const FLOWS = [
  flow('f1', 'AWAITING_REPLY'),
  flow('f2', 'COMPLETED', { latencyMs: 42 }),
  flow('f3', 'TIMED_OUT', { correlationId: 'c'.repeat(80) }),
];

const EXPECTATION = {
  id: 'e1',
  requestDestination: 'orders.request',
  replyDestinations: ['orders.reply.*'],
  resolvedReplyDestinations: [],
  replyDestinationsCapped: false,
  correlationProperty: null,
  deadlineMs: 30_000,
  samplePerMin: 10,
  capturePayload: false,
  enabled: true,
};

const DIAGNOSTICS = {
  asOf: '2026-09-06T12:00:00Z',
  sampleIntervalMs: 5_000,
  nodesWithCoreEndpoint: 2,
  nodesTotal: 2,
  notificationsCapability: 'CONNECTED',
  clock: {
    verdict: 'IN_AGREEMENT',
    worstOffsetMs: null,
    uncertaintyMs: null,
    skewedNodes: [],
    measuredAt: '2026-09-06T12:00:00Z',
    toleranceMs: 2_000,
  },
  expectations: [
    {
      expectationId: 'e1',
      requestDestination: 'orders.request',
      enabled: true,
      lastAttemptAt: '2026-09-06T12:00:00Z',
      lastSuccessAt: '2026-09-06T12:00:00Z',
      nodesSampled: 1,
      nodesTotal: 2,
      skipped: ["broker-2: no subscription for destination 'orders.request' in the last scrape"],
      messagesBrowsed: 4,
      observations: 1,
      lastError: null,
      lastErrorAt: null,
      samplePerMin: 10,
      rateExceedsInterval: false,
    },
  ],
  reasons: [
    {
      code: 'CONSUMED_FASTER_THAN_SAMPLED',
      summary: 'Tracing browses every 5000ms, so a request consumed faster than that is never seen.',
      remedy: 'Raise samples per minute and lower rr.sample-interval.',
    },
  ],
};

function seeded(flows: readonly unknown[]): QueryClient {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false, staleTime: Infinity, gcTime: Infinity } },
  });
  client.setQueryData(authKeys.me, {
    id: 'u1',
    username: 'admin',
    mustChangePassword: false,
    grants: [{ scopeType: 'GLOBAL', scopeId: null, permissions: ['*'] }],
  });
  client.setQueryData(['clusters', 'c1'], {
    id: 'c1',
    name: 'prod',
    capabilities: { notifications: { status: 'AVAILABLE', reason: 'ok', brokerXmlSnippet: null } },
  });
  const page = { data: flows, count: flows.length, page: 1, pageSize: 100 };
  client.setQueryData(['clusters', 'c1', 'rr', 'flows', { page: 1, size: 100 }], page);
  client.setQueryData(['clusters', 'c1', 'rr', 'flows', { size: 500 }], page);
  client.setQueryData(['clusters', 'c1', 'rr', 'flows', 'f2'], {
    ...flow('f2', 'COMPLETED', { latencyMs: 42, latencySource: 'OBSERVED', latencyBoundMs: 100, requestSkewMs: 1500 }),
    events: [
      { seq: 1, ts: '2026-09-04T10:00:00.000Z', kind: 'REQUEST_SEEN', nodeId: null, detail: null },
      { seq: 2, ts: '2026-09-04T10:00:01.000Z', kind: 'REPLY_SEEN', nodeId: null, detail: { subscription: 'reply.q' } },
    ],
  });
  client.setQueryData(['clusters', 'c1', 'rr', 'expectations'], [EXPECTATION]);
  client.setQueryData(['clusters', 'c1', 'rr', 'diagnostics'], DIAGNOSTICS);
  client.setQueryData(['clusters', 'c1', 'rr', 'stats', 'PT15M'], {
    destinations: [{ destination: 'orders.request', p50Ms: 20, p95Ms: 80, p99Ms: 120, coverageRatio: 0.5 }],
  });
  return client;
}

function page(search: string) {
  const root = createRootRoute();
  const route = createRoute({ getParentRoute: () => root, path: '/clusters/$clusterId/rr', component: FlowsView });
  return createRouter({
    routeTree: root.addChildren([route]),
    history: createMemoryHistory({ initialEntries: [`/clusters/c1/rr${search}`] }),
  });
}

const TABS = [
  { name: 'Flows', search: '', ready: 'Flows', flows: FLOWS },
  { name: 'Flows, with none yet', search: '', ready: 'No flows to show — here is what Rahdar did', flows: [] },
  { name: 'Stuck', search: '?tab=stuck', ready: 'Stuck flows', flows: FLOWS },
  { name: 'Latency', search: '?tab=latency', ready: 'Latency', flows: FLOWS },
  { name: 'Expectations', search: '?tab=expectations', ready: 'Traced destinations', flows: FLOWS },
] as const;

describe.each(TABS)('Request-reply tracing, $name', ({ search, ready, flows }) => {
  describe.each(SCHEMES)('in the %s scheme at 1280 px', (scheme) => {
    it('has one h1, no accessibility violations and nothing scrolling sideways', async () => {
      const width = contentWidth(1280);
      const { container } = renderThemed(
        <QueryClientProvider client={seeded(flows)}>
          <Frame width={width} height={2400}>
            <RouterProvider router={page(search)} />
          </Frame>
        </QueryClientProvider>,
        scheme,
      );
      await screen.findByRole('heading', { name: ready });
      await settle(() => `${container.querySelectorAll('table, [role="grid"]').length}:${container.scrollHeight}`);

      expect(screen.getAllByRole('heading', { level: 1 })).toHaveLength(1);
      expect(await axeViolations(container)).toEqual([]);
      expect(container.scrollWidth).toBeLessThanOrEqual(container.clientWidth + 1);
    });
  });
});

describe.each(SCHEMES)('Request-reply tracing dialogs in the %s scheme', (scheme) => {
  /** The overlay and the panel fade in; a colour read mid-fade is a blend with the page behind it. */
  async function opened(name: string) {
    const dialog = await screen.findByRole('dialog', { name });
    await settle(() => `${getComputedStyle(dialog).opacity}:${dialog.getBoundingClientRect().left}`);
    return dialog;
  }

  it('shows a flow in a drawer with no accessibility violations and a named close button', async () => {
    renderThemed(
      <QueryClientProvider client={seeded(FLOWS)}>
        <Frame width={contentWidth(1280)} height={1200}>
          <RouterProvider router={page('')} />
        </Frame>
      </QueryClientProvider>,
      scheme,
    );
    await userEvent.click(await screen.findByRole('gridcell', { name: 'corr-f2' }));
    const drawer = await opened('Flow on orders.request');

    expect(within(drawer).getByRole('button', { name: 'Close the flow' })).toBeInTheDocument();
    expect(within(drawer).getByRole('heading', { level: 3, name: 'Timeline' })).toBeInTheDocument();
    expect(await axeViolations(drawer)).toEqual([]);
  });

  it('asks for the destination to be typed before stopping tracing, with no accessibility violations', async () => {
    renderThemed(
      <QueryClientProvider client={seeded(FLOWS)}>
        <Frame width={contentWidth(1280)} height={1200}>
          <RouterProvider router={page('?tab=expectations')} />
        </Frame>
      </QueryClientProvider>,
      scheme,
    );
    await userEvent.click(await screen.findByRole('button', { name: 'Remove orders.request' }));
    const dialog = await opened('Stop tracing this destination');

    expect(await axeViolations(dialog)).toEqual([]);
  });
});
