import { beforeEach, describe, expect, it, vi } from 'vitest';
import { http, HttpResponse } from 'msw';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

import { paged } from '../../kernel/api/paging.ts';
import { ARTEMIS_KINDS, ARTEMIS_SYSTEM, subscriptionIdentity } from '../../test/wire.ts';
import { renderWithProviders } from '../../test/render.tsx';
import { server } from '../../test/setup.ts';
import type { ClusterSummary } from './api.ts';

let search: Record<string, unknown> = {};
const navigate = vi.fn();
vi.mock('@tanstack/react-router', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@tanstack/react-router')>()),
  useSearch: () => search,
  useNavigate: () => navigate,
  Link: ({ children, to, search }: { children: React.ReactNode; to?: string; search?: Record<string, string> }) => (
    <a href={`${to ?? ''}?${new URLSearchParams(search ?? {})}`}>{children}</a>
  ),
}));

const { EstateView } = await import('./EstateView.tsx');

const AVAILABLE = { status: 'AVAILABLE', reason: 'ok', brokerXmlSnippet: null };
const OTHER_SYSTEM = { id: 'other', name: 'Other Broker' };
/** What a second system offers: its subscriptions can be paused but not purged. */
const OTHER_KINDS = [
  { id: 'other.stream', resourceClass: 'SUBSCRIPTION', label: 'Stream', verbs: ['pause', 'resume'] },
];

function cluster(id: string, name: string, over: Partial<ClusterSummary> = {}): ClusterSummary {
  return {
    id,
    name,
    health: 'OK',
    nodeCount: 2,
    updatedAt: '2026-01-01T00:00:00Z',
    environmentId: null,
    system: ARTEMIS_SYSTEM,
    kinds: ARTEMIS_KINDS,
    ...over,
  };
}

const ARTEMIS = cluster('c1', 'prod-emea');
const OTHER = cluster('c2', 'edge-eu', { system: OTHER_SYSTEM, kinds: OTHER_KINDS, health: 'DEGRADED' });

const ALLOWED = ['subscription:read', 'subscription:pause', 'subscription:purge', 'message:read'];

function row(c: ClusterSummary, name: string, kind: { id: string; label: string }, stale = false) {
  return {
    cluster: { id: c.id, name: c.name, system: c.system.id },
    subscription: {
      ...subscriptionIdentity(name),
      kind: kind.id,
      kindLabel: kind.label,
      allowedActions: ALLOWED,
      durable: true,
      totalMessageCount: 7,
      totalConsumerCount: 1,
      totalDeliveringCount: 0,
      totalScheduledCount: 0,
      nodesPresent: 1,
      nodesTotal: 1,
      paused: false,
      perNode: [
        {
          nodeId: 'n1',
          nodeName: 'node-a',
          stale,
          lastSeenAt: null,
          messageCount: 7,
          consumerCount: 1,
          deliveringCount: 0,
          scheduledCount: 0,
          paused: false,
        },
      ],
    },
  };
}

const ROWS = [
  row(ARTEMIS, 'orders', { id: 'artemis.queue', label: 'Queue' }),
  row(OTHER, 'telemetry', { id: 'other.stream', label: 'Stream' }),
];

function detail(c: ClusterSummary) {
  return {
    ...c,
    topology: { nodes: [] },
    capabilities: {
      managementRead: AVAILABLE,
      managementWrite: AVAILABLE,
      notifications: AVAILABLE,
      messageIo: AVAILABLE,
      slowConsumerDetection: AVAILABLE,
      versionGates: [],
    },
  };
}

function mockApi(clusters: ClusterSummary[], rows: unknown[] = ROWS) {
  server.use(
    http.get('*/api/v1/auth/me', () =>
      HttpResponse.json({
        id: 'u1',
        username: 'op',
        mustChangePassword: false,
        grants: [{ scopeType: 'GLOBAL', scopeId: null, permissions: ['*'] }],
      }),
    ),
    http.get('*/api/v1/clusters', () => HttpResponse.json(paged(clusters))),
    http.get('*/api/v1/environments', () => HttpResponse.json(paged([]))),
    ...clusters.map((c) => http.get(`*/api/v1/clusters/${c.id}`, () => HttpResponse.json(detail(c)))),
    http.get('*/api/v1/subscriptions', ({ request }) => {
      const q = new URL(request.url).searchParams.get('q');
      const data = q ? rows.filter((r) => JSON.stringify(r).includes(q)) : rows;
      return HttpResponse.json({ data, count: data.length, page: 1, pageSize: 50, hasNext: false });
    }),
  );
}

beforeEach(() => {
  search = {};
  navigate.mockReset();
});

describe('the estate view', () => {
  it('lists clusters of two systems with comparable health, and their subscriptions with native kinds', async () => {
    mockApi([ARTEMIS, OTHER]);
    renderWithProviders(<EstateView />);

    const clusters = await screen.findByRole('table', { name: 'Clusters' });
    expect(within(clusters).getByRole('link', { name: 'prod-emea' })).toBeInTheDocument();
    expect(within(clusters).getByRole('link', { name: 'edge-eu' })).toBeInTheDocument();
    expect(within(clusters).getByText(ARTEMIS_SYSTEM.name)).toBeInTheDocument();
    expect(within(clusters).getByText('Other Broker')).toBeInTheDocument();
    expect(within(clusters).getByText('Healthy')).toBeInTheDocument();
    expect(within(clusters).getByText('Degraded')).toBeInTheDocument();

    const grid = await screen.findByRole('grid', { name: 'Subscriptions' });
    await within(grid).findByRole('link', { name: 'orders' });
    expect(within(grid).getByRole('link', { name: 'telemetry' })).toBeInTheDocument();
    expect(within(grid).getByText('Queue')).toBeInTheDocument();
    expect(within(grid).getByText('Stream')).toBeInTheDocument();
  });

  it("disables a verb the row's system lacks, with the reason, while the other system's row can purge", async () => {
    mockApi([ARTEMIS, OTHER]);
    const user = userEvent.setup();
    renderWithProviders(<EstateView />);

    await user.click(await screen.findByRole('button', { name: 'Actions for telemetry' }));
    const menu = await screen.findByRole('menu', { name: 'Actions for telemetry' });
    const purge = await within(menu).findByRole('menuitem', { name: /Purge messages/ });
    await waitFor(() => expect(purge).toHaveAttribute('aria-disabled', 'true'));
    expect(purge).toHaveAccessibleDescription(/Other Broker streams cannot be purged/);
    expect(within(menu).getByRole('menuitem', { name: /Pause/ })).not.toHaveAttribute('aria-disabled', 'true');
    expect(within(menu).getByRole('menuitem', { name: /Browse messages/ })).toBeInTheDocument();
  });

  it('leaves the verbs a system has enabled', async () => {
    mockApi([ARTEMIS, OTHER]);
    const user = userEvent.setup();
    renderWithProviders(<EstateView />);

    await user.click(await screen.findByRole('button', { name: 'Actions for orders' }));
    const menu = await screen.findByRole('menu', { name: 'Actions for orders' });
    const purge = await within(menu).findByRole('menuitem', { name: /Purge messages/ });
    await waitFor(() => expect(purge).not.toHaveAttribute('aria-disabled', 'true'));
  });

  it('teaches what a cluster is and offers to register one when the estate is empty', async () => {
    mockApi([]);
    renderWithProviders(<EstateView />);

    expect(await screen.findByText('No clusters yet')).toBeInTheDocument();
    expect(screen.getByText(/A cluster is a set of brokers of one system/)).toBeInTheDocument();
    expect(screen.getByRole('heading', { name: 'Register a cluster', level: 2 })).toBeInTheDocument();
  });

  it('says why the clusters could not be read, and retries', async () => {
    let failing = true;
    mockApi([ARTEMIS]);
    server.use(
      http.get('*/api/v1/clusters', () =>
        failing
          ? HttpResponse.json({ title: 'Error', detail: 'The cluster store is down.' }, { status: 500 })
          : HttpResponse.json(paged([ARTEMIS])),
      ),
    );
    const user = userEvent.setup();
    renderWithProviders(<EstateView />);

    expect(await screen.findByText('The cluster store is down.')).toBeInTheDocument();
    failing = false;
    await user.click(screen.getByRole('button', { name: 'Retry' }));
    expect(await screen.findByRole('link', { name: 'prod-emea' })).toBeInTheDocument();
  });

  it('states a filter that matches nothing, distinct from an estate with none, and offers to clear it', async () => {
    search = { q: 'zzz' };
    mockApi([ARTEMIS], []);
    const user = userEvent.setup();
    renderWithProviders(<EstateView />);

    expect(await screen.findByText('No subscriptions match this filter')).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Clear filters' }));
    expect(navigate).toHaveBeenCalled();
    const { search: update } = navigate.mock.calls.at(-1)![0] as { search: (p: object) => Record<string, unknown> };
    expect(update({ q: 'zzz' })).toMatchObject({ q: undefined, cluster: undefined });
  });

  it('says so, and shows the rest, when a cluster is not answering', async () => {
    const silent = cluster('c3', 'dark-site', { health: 'UNKNOWN' });
    mockApi([ARTEMIS, silent], [row(ARTEMIS, 'orders', { id: 'artemis.queue', label: 'Queue' }, true)]);
    renderWithProviders(<EstateView />);

    const clusters = await screen.findByRole('table', { name: 'Clusters' });
    expect(within(clusters).getByText('Not answering')).toBeInTheDocument();
    expect(await screen.findByText(/Subscriptions of dark-site are unavailable/)).toBeInTheDocument();
    const grid = await screen.findByRole('grid', { name: 'Subscriptions' });
    expect(await within(grid).findByText('stale')).toBeInTheDocument();
    expect(within(grid).getByRole('link', { name: 'orders' })).toBeInTheDocument();
  });
});
