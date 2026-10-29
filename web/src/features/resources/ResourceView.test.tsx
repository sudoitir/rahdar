import { destinationIdentity } from '../../test/wire.ts';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { delay, http, HttpResponse } from 'msw';
import { screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

import { renderWithProviders } from '../../test/render.tsx';
import { server } from '../../test/setup.ts';

let search: Record<string, unknown> = {};
const navigate = vi.fn();
vi.mock('@tanstack/react-router', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@tanstack/react-router')>()),
  useParams: () => ({ clusterId: 'c1' }),
  useSearch: () => search,
  useNavigate: () => navigate,
  Link: ({
    children,
    to,
    search,
    'aria-label': label,
  }: {
    children: React.ReactNode;
    to?: string;
    search?: Record<string, string>;
    'aria-label'?: string;
  }) => (
    <a href={`${to ?? ''}?${new URLSearchParams(search ?? {})}`} aria-label={label}>
      {children}
    </a>
  ),
}));

const { ResourceView } = await import('./ResourceView.tsx');

const AVAILABLE = { status: 'AVAILABLE', reason: 'ok', brokerXmlSnippet: null };

beforeEach(() => {
  search = {};
  navigate.mockReset();
});

function consumer(id: string) {
  return {
    nodeId: 'n1',
    nodeName: 'node-a',
    id: `artemis.consumer/s1/${id}`,
    kind: 'artemis.consumer',
    kindLabel: { singular: 'Consumer', plural: 'Consumers' },
    channel: 'artemis.session/s1',
    subscription: 'artemis.queue/orders',
    destination: 'artemis.address/orders',
    protocol: 'CORE',
    messagesDelivered: 7,
    messagesAcknowledged: 5,
    messagesInFlight: 0,
  };
}

function serve({
  consumers = [],
  nodes = [],
  permissions = ['*'],
}: { consumers?: object[]; nodes?: object[]; permissions?: string[] } = {}) {
  server.use(
    http.get('*/api/v1/auth/me', () =>
      HttpResponse.json({
        id: 'u1',
        username: 'op',
        mustChangePassword: false,
        grants: [{ scopeType: 'GLOBAL', scopeId: null, permissions }],
      }),
    ),
    http.get('*/api/v1/clusters/c1', () =>
      HttpResponse.json({
        id: 'c1',
        name: 'c1',
        topology: { nodes },
        capabilities: { managementWrite: AVAILABLE },
      }),
    ),
    http.get('*/api/v1/clusters/c1/consumers', () =>
      HttpResponse.json({ data: consumers, count: consumers.length, page: 1, pageSize: 200, hasNext: false }),
    ),
  );
}

/** What the last navigate() call does to the destination it starts from. */
function nextSearch(prev: Record<string, unknown> = {}) {
  const call = navigate.mock.lastCall![0] as { search: (p: Record<string, unknown>) => Record<string, unknown> };
  return call.search(prev);
}

describe('ResourceView', () => {
  it('lists each row with the node it came from and the action it implies', async () => {
    serve({ consumers: [consumer('c-1')] });
    renderWithProviders(<ResourceView kind="consumers" />);

    const grid = await screen.findByRole('grid', { name: 'Consumers' });
    expect(await within(grid).findByText('node-a')).toBeInTheDocument();
    expect(within(grid).getByRole('columnheader', { name: 'Node' })).toBeInTheDocument();
    expect(
      within(grid).getByRole('button', { name: "Close the consumer's connection for orders" }),
    ).toBeInTheDocument();
    expect(await screen.findByText('1–1 of 1 consumers')).toBeInTheDocument();
  });

  it('puts the sort in the destination and starts again at the first page', async () => {
    serve({ consumers: [consumer('c-1')] });
    const user = userEvent.setup();
    renderWithProviders(<ResourceView kind="consumers" />);

    await screen.findByText('node-a');
    await user.click(within(screen.getByRole('columnheader', { name: /Subscription/ })).getByRole('button'));

    expect(nextSearch({ page: 3, q: 'x' })).toEqual({ page: undefined, q: 'x', sort: 'subscription' });
  });

  it('teaches what a consumer is when there is none', async () => {
    serve();
    renderWithProviders(<ResourceView kind="consumers" />);

    expect(await screen.findByText('No consumers right now')).toBeInTheDocument();
    expect(screen.getByText(/A consumer is a client attached to a subscription/)).toBeInTheDocument();
  });

  it('says the filter emptied it, and clears the filter from the destination', async () => {
    search = { q: 'zzz' };
    serve();
    const user = userEvent.setup();
    renderWithProviders(<ResourceView kind="consumers" />);

    expect(await screen.findByText('No consumer matches "zzz"')).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Clear filters' }));

    expect(nextSearch({ q: 'zzz', page: 2 })).toEqual({ q: undefined, page: undefined });
    expect(screen.getByRole('textbox', { name: 'Filter consumers' })).toHaveValue('');
  });

  it('names the nodes that did not answer instead of presenting an empty view', async () => {
    serve({ nodes: [{ endpoints: [{ name: 'node-a', lastError: 'refused' }, { name: 'node-b' }] }] });
    renderWithProviders(<ResourceView kind="consumers" />);

    expect(await screen.findByText('node-a could not be reached')).toBeInTheDocument();
    expect(screen.queryByText('No consumers right now')).not.toBeInTheDocument();
  });

  it('states why the rows could not be listed, and offers to retry', async () => {
    server.use(
      http.get('*/api/v1/clusters/c1/consumers', () =>
        HttpResponse.json({ title: 'Listing failed', detail: 'No node answered.' }, { status: 502 }),
      ),
    );
    renderWithProviders(<ResourceView kind="consumers" />);

    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent('Rahdar failed to complete the request');
    expect(within(alert).getByRole('button', { name: 'Retry' })).toBeInTheDocument();
  });
});

describe('ResourceView destinations of teams', () => {
  function destination(name: string, ownerTeam?: { id: string; name: string }) {
    return {
      nodeId: 'n1',
      nodeName: 'node-a',
      ...destinationIdentity(name),
      delivery: ['POINT_TO_POINT'],
      subscriptionCount: 1,
      messageCount: 3,
      allowedActions: ['destination:read'],
      ...(ownerTeam ? { ownerTeam } : {}),
    };
  }

  function serveDestinations(rows: object[], access: { permissions: string[]; anywhere: string[] }) {
    serve();
    server.use(
      http.get('*/api/v1/clusters/c1/destinations', () =>
        HttpResponse.json({ data: rows, count: rows.length, page: 1, pageSize: 200, hasNext: false }),
      ),
      http.get('*/api/v1/me/access', ({ request }) => {
        const clusterId = new URL(request.url).searchParams.get('clusterId');
        return HttpResponse.json({
          ...access,
          canSeeCluster: clusterId ? true : null,
          teams: [],
          createPatterns: { subscription: [], destination: [] },
        });
      }),
    );
  }

  it('shows the team that owns each destination, and says when none does', async () => {
    serveDestinations([destination('orders.in', { id: 't1', name: 'Orders' }), destination('legacy.in')], {
      permissions: ['destination:read', 'user:admin'],
      anywhere: [],
    });
    renderWithProviders(<ResourceView kind="destinations" />);

    const grid = await screen.findByRole('grid', { name: 'Destinations' });
    expect(await within(grid).findByRole('link', { name: 'Owner: team Orders' })).toBeInTheDocument();
    expect(within(grid).getByText('No owner')).toBeInTheDocument();
  });

  it("says there are no destinations in the caller's teams when they reach destinations only through a team", async () => {
    serveDestinations([], { permissions: [], anywhere: ['destination:read'] });
    renderWithProviders(<ResourceView kind="destinations" />);

    expect(await screen.findByText('No destinations in your teams on this cluster')).toBeInTheDocument();
  });

  it('says the caller is in no team on the cluster, and whom to ask, when they reach nothing', async () => {
    serveDestinations([], { permissions: [], anywhere: [] });
    renderWithProviders(<ResourceView kind="destinations" />);

    expect(await screen.findByText("You're not in any team on this cluster")).toBeInTheDocument();
    expect(screen.getByText(/Ask a platform administrator to add you to a team/)).toBeInTheDocument();
  });
});

describe('ResourceView page structure', () => {
  it.each([
    ['destinations', 'Destinations'],
    ['consumers', 'Consumers'],
    ['channels', 'Channels'],
    ['connections', 'Connections'],
    ['producers', 'Producers'],
  ] as const)('is one page whose single h1 names the %s', async (kind, title) => {
    serve();
    renderWithProviders(<ResourceView kind={kind} />);

    expect(await screen.findByRole('heading', { level: 1, name: title })).toBeInTheDocument();
    expect(screen.getAllByRole('heading', { level: 1 })).toHaveLength(1);
  });

  it('does not say there are no consumers while the first page is still loading', async () => {
    serve();
    server.use(http.get('*/api/v1/clusters/c1/consumers', async () => delay('infinite')));
    renderWithProviders(<ResourceView kind="consumers" />);

    await screen.findByRole('grid', { name: 'Consumers' });
    expect(screen.queryByText(/^No consumers$/)).not.toBeInTheDocument();
    expect(screen.queryByText('No consumers right now')).not.toBeInTheDocument();
  });
});
