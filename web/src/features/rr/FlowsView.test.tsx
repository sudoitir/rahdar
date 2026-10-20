import { describe, expect, it, vi } from 'vitest';
import { http, HttpResponse } from 'msw';
import { screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

import { renderWithProviders } from '../../test/render.tsx';
import { server } from '../../test/setup.ts';

let routerSearch: Record<string, unknown> = {};

vi.mock('@tanstack/react-router', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@tanstack/react-router')>()),
  useParams: () => ({ clusterId: 'c1' }),
  useSearch: () => routerSearch,
  useNavigate: () => () => {},
}));

const { FlowsView } = await import('./FlowsView.tsx');

function cluster(notifStatus: string) {
  return {
    id: 'c1',
    name: 'prod',
    description: null,
    topology: { clusterId: 'c1', nodes: [], unmanaged: [] },
    capabilities: {
      managementRead: { status: 'AVAILABLE', reason: 'ok', brokerXmlSnippet: null },
      managementWrite: { status: 'AVAILABLE', reason: 'ok', brokerXmlSnippet: null },
      messageIo: { status: 'AVAILABLE', reason: 'ok', brokerXmlSnippet: null },
      notifications: {
        status: notifStatus,
        reason: 'the broker refused the subscription',
        brokerXmlSnippet: '<security-setting match="activemq.notifications"/>',
      },
    },
    health: {
      clusterId: 'c1',
      level: 'OK',
      liveEndpointNames: [],
      splitBrain: 'NONE',
      replicationBehind: false,
      notes: [],
    },
  };
}

function flow(over: Record<string, unknown> = {}) {
  return {
    id: 'f1',
    clusterId: 'c1',
    nodeId: null,
    requestAddress: 'orders.request',
    replyDestination: null,
    replyKind: 'SHARED',
    state: 'AWAITING_REPLY',
    correlationId: 'corr-1',
    requestedAt: '2026-09-04T10:00:00.000Z',
    deadlineAt: '2026-09-04T10:00:30.000Z',
    repliedAt: null,
    latencyMs: null,
    ...over,
  };
}

function diagnostics() {
  return {
    asOf: '2026-09-06T12:00:00Z',
    sampleIntervalMs: 5_000,
    nodesWithCoreEndpoint: 1,
    nodesTotal: 1,
    notificationsCapability: 'CONNECTED',
    clock: {
      verdict: 'IN_AGREEMENT',
      worstOffsetMs: null,
      uncertaintyMs: null,
      skewedNodes: [],
      measuredAt: '2026-09-06T12:00:00Z',
      toleranceMs: 2_000,
    },
    expectations: [],
    reasons: [{ code: 'NOTHING_SENT', summary: 'Nothing was sent.', remedy: 'Send a request.' }],
  };
}

describe('FlowsView', () => {
  it('shows the reason and broker.xml snippet when tracing is not available', async () => {
    server.use(http.get('*/api/v1/clusters/c1', () => HttpResponse.json(cluster('UNAVAILABLE'))));
    renderWithProviders(<FlowsView />);

    expect(await screen.findByText(/refused the subscription/)).toBeInTheDocument();
    expect(screen.getByText(/activemq\.notifications/)).toBeInTheDocument();
  });

  it('lists flows and opens the detail drawer on selection', async () => {
    server.use(
      http.get('*/api/v1/clusters/c1', () => HttpResponse.json(cluster('AVAILABLE'))),
      http.get('*/api/v1/clusters/c1/rr/flows', () =>
        HttpResponse.json({ data: [flow()], count: 1, page: 1, pageSize: 100 }),
      ),
      http.get('*/api/v1/clusters/c1/rr/flows/f1', () =>
        HttpResponse.json({
          ...flow(),
          events: [{ seq: 1, ts: '2026-09-04T10:00:00.000Z', kind: 'REQUEST_SEEN', nodeId: null, detail: null }],
        }),
      ),
    );
    const user = userEvent.setup();
    renderWithProviders(<FlowsView />);

    expect(await screen.findByText('corr-1')).toBeInTheDocument();
    await user.click(screen.getByText('corr-1'));
    expect(await screen.findByText('REQUEST_SEEN')).toBeInTheDocument();
  });

  it('shows an empty state with no flows', async () => {
    server.use(
      http.get('*/api/v1/clusters/c1', () => HttpResponse.json(cluster('AVAILABLE'))),
      http.get('*/api/v1/clusters/c1/rr/flows', () =>
        HttpResponse.json({ data: [], count: 0, page: 1, pageSize: 100 }),
      ),
      http.get('*/api/v1/clusters/c1/rr/diagnostics', () => HttpResponse.json(diagnostics())),
    );
    renderWithProviders(<FlowsView />);

    expect(await screen.findByText(/here is what Rahdar did/)).toBeInTheDocument();
  });

  it('is one page with a single h1, and sections under it', async () => {
    server.use(
      http.get('*/api/v1/clusters/c1', () => HttpResponse.json(cluster('AVAILABLE'))),
      http.get('*/api/v1/clusters/c1/rr/flows', () =>
        HttpResponse.json({ data: [flow()], count: 1, page: 1, pageSize: 100 }),
      ),
    );
    renderWithProviders(<FlowsView />);

    expect(await screen.findByRole('heading', { level: 1, name: 'Request-reply tracing' })).toBeInTheDocument();
    expect(screen.getAllByRole('heading', { level: 1 })).toHaveLength(1);
    expect(screen.getByRole('heading', { level: 2, name: 'Flows' })).toBeInTheDocument();
  });

  it('keeps the page heading when tracing is not available', async () => {
    server.use(http.get('*/api/v1/clusters/c1', () => HttpResponse.json(cluster('UNKNOWN'))));
    renderWithProviders(<FlowsView />);

    expect(await screen.findByRole('heading', { level: 1, name: 'Request-reply tracing' })).toBeInTheDocument();
    expect(await screen.findByRole('heading', { name: 'Tracing not available' })).toBeInTheDocument();
  });

  it('says a flow is in trouble in words, and a healthy one in words too', async () => {
    server.use(
      http.get('*/api/v1/clusters/c1', () => HttpResponse.json(cluster('AVAILABLE'))),
      http.get('*/api/v1/clusters/c1/rr/flows', () =>
        HttpResponse.json({
          data: [flow(), flow({ id: 'f2', state: 'TIMED_OUT', correlationId: 'corr-2' })],
          count: 2,
          page: 1,
          pageSize: 100,
        }),
      ),
    );
    renderWithProviders(<FlowsView />);

    expect(await screen.findByText('awaiting reply')).toBeInTheDocument();
    expect(screen.getByText('timed out')).toBeInTheDocument();
  });

  it('states the cause and offers a retry when the flows cannot be read, not an empty list', async () => {
    server.use(
      http.get('*/api/v1/clusters/c1', () => HttpResponse.json(cluster('AVAILABLE'))),
      http.get('*/api/v1/clusters/c1/rr/flows', () => HttpResponse.json({ title: 'Down' }, { status: 503 })),
    );
    renderWithProviders(<FlowsView />);

    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent('Rahdar failed to complete the request');
    expect(within(alert).getByRole('button', { name: 'Retry' })).toBeInTheDocument();
    expect(screen.queryByText(/here is what Rahdar did/)).not.toBeInTheDocument();
  });

  it('labels both filters, and says a filter excluded every flow, offering to clear it', async () => {
    server.use(
      http.get('*/api/v1/clusters/c1', () => HttpResponse.json(cluster('AVAILABLE'))),
      http.get('*/api/v1/clusters/c1/rr/flows', () =>
        HttpResponse.json({ data: [], count: 0, page: 1, pageSize: 100 }),
      ),
    );
    routerSearch = { state: 'TIMED_OUT' };
    try {
      renderWithProviders(<FlowsView />);

      expect(await screen.findByText('No flow matches these filters')).toBeInTheDocument();
      expect(screen.getByRole('button', { name: 'Clear filters' })).toBeInTheDocument();
      expect(screen.getByRole('textbox', { name: 'Filter by address' })).toBeInTheDocument();
      expect(screen.getByRole('combobox', { name: 'State' })).toBeInTheDocument();
    } finally {
      routerSearch = {};
    }
  });

  it('says on the Stuck tab when nothing is stuck, and what stuck means', async () => {
    server.use(
      http.get('*/api/v1/clusters/c1', () => HttpResponse.json(cluster('AVAILABLE'))),
      http.get('*/api/v1/clusters/c1/rr/flows', () =>
        HttpResponse.json({ data: [flow({ state: 'COMPLETED' })], count: 1, page: 1, pageSize: 500 }),
      ),
    );
    routerSearch = { tab: 'stuck' };
    try {
      renderWithProviders(<FlowsView />);

      expect(await screen.findByText('No flow is stuck')).toBeInTheDocument();
      expect(screen.getByText(/0 flows timed out, orphaned, or dropped/)).toBeInTheDocument();
    } finally {
      routerSearch = {};
    }
  });

  it('does not report a failed read as zero stuck flows', async () => {
    server.use(
      http.get('*/api/v1/clusters/c1', () => HttpResponse.json(cluster('AVAILABLE'))),
      http.get('*/api/v1/clusters/c1/rr/flows', () => HttpResponse.json({ title: 'Down' }, { status: 503 })),
    );
    routerSearch = { tab: 'stuck' };
    try {
      renderWithProviders(<FlowsView />);

      expect(await screen.findByText(/how many are stuck is not known/)).toBeInTheDocument();
      expect(screen.queryByText(/0 flows timed out/)).not.toBeInTheDocument();
    } finally {
      routerSearch = {};
    }
  });
});
