import { ARTEMIS_KINDS, addressId, subscriptionIdentity } from '../../test/wire.ts';
import { beforeEach, describe, expect, it, onTestFinished, vi } from 'vitest';
import { delay, http, HttpResponse } from 'msw';
import { act, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

import { renderWithProviders } from '../../test/render.tsx';
import { server } from '../../test/setup.ts';

let search: Record<string, unknown> = { q: 'orders' };
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

// The drawer and the create form have their own tests; here they only show that the view opened them.
vi.mock('./QueueDetailDrawer.tsx', () => ({
  QueueDetailDrawer: ({
    queue,
    missing,
    onClose,
  }: {
    queue: { name: string } | null;
    missing?: string;
    onClose: () => void;
  }) =>
    queue ? (
      <div role="dialog" aria-label="Queue detail">
        {queue.name}
        <button onClick={onClose}>Close detail</button>
      </div>
    ) : missing ? (
      <div role="dialog" aria-label={`Queue not found: ${missing}`} />
    ) : null,
}));
vi.mock('./CreateQueueForm.tsx', () => ({
  CreateQueueForm: ({ opened }: { opened: boolean }) =>
    opened ? <div role="dialog" aria-label="New queue form" /> : null,
}));

beforeEach(() => {
  search = { q: 'orders' };
  navigate.mockReset();
});

const { QueuesView } = await import('./QueuesView.tsx');

function queue(name: string) {
  return {
    ...subscriptionIdentity(name, name),
    allowedActions: [
      'subscription:read',
      'subscription:pause',
      'subscription:update',
      'subscription:delete',
      'subscription:purge',
      'message:read',
      'message:move',
    ],
    durable: true,
    totalMessageCount: 1,
    totalConsumerCount: 0,
    totalDeliveringCount: 0,
    totalScheduledCount: 0,
    nodesPresent: 1,
    nodesTotal: 1,
    paused: false,
    perNode: [],
  };
}

describe('QueuesView selection', () => {
  it('offers to select every queue matching the filter, and says when it has', async () => {
    server.use(
      http.get('*/api/v1/auth/me', () =>
        HttpResponse.json({
          id: 'u1',
          username: 'admin',
          mustChangePassword: false,
          grants: [{ scopeType: 'GLOBAL', scopeId: null, permissions: ['*'] }],
        }),
      ),
      http.get('*/api/v1/clusters/c1', () =>
        HttpResponse.json({ id: 'c1', name: 'c1', topology: { nodes: [] }, kinds: ARTEMIS_KINDS, capabilities: {} }),
      ),
      // 140 match, one page of two is shown.
      http.get('*/api/v1/clusters/c1/subscriptions', () =>
        HttpResponse.json({ data: [queue('orders.a'), queue('orders.b')], count: 140, page: 1, pageSize: 200 }),
      ),
    );
    const user = userEvent.setup();
    renderWithProviders(<QueuesView />);

    await user.click(await screen.findByRole('checkbox', { name: 'Select all on this page' }));
    expect(screen.getByText('2 queues selected')).toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: 'Select all 140 queues matching "orders"' }));
    expect(screen.getByText('All 140 queues matching "orders" are selected.')).toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: 'Clear selection' }));
    expect(screen.getByText(/No queues selected/)).toBeInTheDocument();
  });
});

const AVAILABLE = { status: 'AVAILABLE', reason: 'ok', brokerXmlSnippet: null };

function serve({
  queues = [],
  count,
  permissions = ['*'],
  nodes = [],
  managementWrite = AVAILABLE,
}: {
  queues?: ReturnType<typeof queue>[];
  count?: number;
  permissions?: string[];
  nodes?: object[];
  managementWrite?: object;
} = {}) {
  server.use(
    http.get('*/api/v1/auth/me', () =>
      HttpResponse.json({
        id: 'u1',
        username: 'admin',
        mustChangePassword: false,
        grants: [{ scopeType: 'GLOBAL', scopeId: null, permissions }],
      }),
    ),
    http.get('*/api/v1/clusters/c1', () =>
      HttpResponse.json({
        id: 'c1',
        name: 'c1',
        topology: { nodes },
        kinds: ARTEMIS_KINDS,
        capabilities: { managementWrite },
      }),
    ),
    http.get('*/api/v1/clusters/c1/subscriptions', () =>
      HttpResponse.json({ data: queues, count: count ?? queues.length, page: 1, pageSize: 200 }),
    ),
  );
}

/** What `/me/access` says for someone who holds nothing on the cluster itself, and some rights through teams. */
function teamAccess({
  anywhere = [],
  createPatterns = { subscription: [], destination: [] },
}: {
  anywhere?: string[];
  createPatterns?: { subscription: string[]; destination: string[] };
} = {}) {
  return http.get('*/api/v1/me/access', ({ request }) => {
    const clusterId = new URL(request.url).searchParams.get('clusterId');
    return HttpResponse.json({
      permissions: [],
      anywhere,
      canSeeCluster: clusterId ? true : null,
      teams: [],
      createPatterns,
    });
  });
}

/** What the last navigate() call does to the address it starts from. */
function nextSearch(prev: Record<string, unknown> = {}) {
  const call = navigate.mock.lastCall![0] as { search: (p: Record<string, unknown>) => Record<string, unknown> };
  return call.search(prev);
}

describe('QueuesView rows', () => {
  it('says in words when a queue is paused everywhere or only on some nodes, and stays quiet otherwise', async () => {
    search = {};
    serve({
      queues: [
        { ...queue('running'), durable: false },
        { ...queue('all'), paused: true, perNode: [{ paused: true }, { paused: true }] },
        { ...queue('some'), paused: true, perNode: [{ paused: true }, { paused: false }] },
      ] as never,
    });
    renderWithProviders(<QueuesView />);

    const grid = await screen.findByRole('grid', { name: 'Queues' });
    expect(await within(grid).findByText('paused')).toBeInTheDocument();
    expect(within(grid).getByText('paused on some nodes')).toBeInTheDocument();
    expect(within(grid).getByText('no')).toBeInTheDocument();
    expect(within(grid).getAllByText('yes')).toHaveLength(2);
    expect(screen.getByText('1–3 of 3 queues')).toBeInTheDocument();
  });

  it('puts the sort in the address and starts again at the first page', async () => {
    search = {};
    serve({ queues: [queue('a')] });
    const user = userEvent.setup();
    renderWithProviders(<QueuesView />);

    await screen.findByRole('grid', { name: 'Queues' });
    await user.click(within(screen.getByRole('columnheader', { name: /Depth/ })).getByRole('button'));

    expect(nextSearch({ page: 3, q: 'x' })).toEqual({ page: undefined, q: 'x', sort: 'depth' });
  });

  it('pages through a long list and leaves the page out of the address on the first', async () => {
    search = { page: 2 };
    serve({ queues: [queue('a')], count: 450 });
    const user = userEvent.setup();
    renderWithProviders(<QueuesView />);

    expect(await screen.findByText('201–400 of 450 queues')).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Next' }));
    expect(nextSearch({ q: 'x' })).toEqual({ q: 'x', page: 3 });
    await user.click(screen.getByRole('button', { name: 'Previous' }));
    expect(nextSearch({ q: 'x' })).toEqual({ q: 'x', page: undefined });
  });

  it('writes the typed filter into the address once typing pauses, and resets the page', async () => {
    search = {};
    serve({ queues: [queue('a')] });
    const user = userEvent.setup();
    renderWithProviders(<QueuesView />);

    await user.type(await screen.findByRole('textbox', { name: 'Filter queues' }), 'ord');

    await waitFor(() => expect(navigate).toHaveBeenCalled());
    expect(nextSearch({ page: 5 })).toEqual({ page: undefined, q: 'ord' });
  });

  it('states why the queues could not be listed instead of showing an empty grid', async () => {
    search = {};
    server.use(
      http.get('*/api/v1/clusters/c1/subscriptions', () =>
        HttpResponse.json({ title: 'Listing failed', detail: 'No node answered.' }, { status: 502 }),
      ),
    );
    renderWithProviders(<QueuesView />);

    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent('Rahdar failed to complete the request');
    expect(within(alert).getByRole('button', { name: 'Retry' })).toBeInTheDocument();
    // The failure stands in place of the rows, not of the view: the filter is still there.
    expect(screen.getByRole('textbox', { name: 'Filter queues' })).toBeInTheDocument();
    expect(within(screen.getByRole('grid', { name: 'Queues' })).queryAllByRole('row')).toHaveLength(1);
  });
});

describe('QueuesView the open queue', () => {
  it('puts the clicked queue in the address, and opens the one the address names', async () => {
    search = {};
    serve({ queues: [{ ...queue('orders.a'), destination: addressId('orders') }] });
    const user = userEvent.setup();
    const { unmount } = renderWithProviders(<QueuesView />);

    await user.click(await screen.findByText('orders.a', { selector: '[role="gridcell"]' }));
    expect(nextSearch({ q: 'x' })).toEqual({ q: 'x', queue: 'orders.a' });
    unmount();

    search = { queue: 'orders.a' };
    renderWithProviders(<QueuesView />);
    const dialog = await screen.findByRole('dialog', { name: 'Queue detail' });
    expect(dialog).toHaveTextContent('orders.a');
    await user.click(within(dialog).getByRole('button', { name: 'Close detail' }));
    expect(nextSearch({ queue: 'orders.a' })).toEqual({ queue: undefined });
  });

  it('looks up a queue named by the address that is not on the loaded page', async () => {
    search = { queue: 'elsewhere' };
    server.use(
      http.get('*/api/v1/auth/me', () =>
        HttpResponse.json({ id: 'u1', username: 'admin', mustChangePassword: false, grants: [] }),
      ),
      http.get('*/api/v1/clusters/c1', () =>
        HttpResponse.json({ id: 'c1', name: 'c1', topology: { nodes: [] }, kinds: ARTEMIS_KINDS, capabilities: {} }),
      ),
      http.get('*/api/v1/clusters/c1/subscriptions', ({ request }) => {
        const asked = new URL(request.url).searchParams.get('q');
        return HttpResponse.json({
          data: asked === 'elsewhere' ? [queue('elsewhere')] : [queue('orders.a')],
          count: 1,
          page: 1,
          pageSize: 200,
        });
      }),
    );
    renderWithProviders(<QueuesView />);

    expect(await screen.findByRole('dialog', { name: 'Queue detail' })).toHaveTextContent('elsewhere');
  });
});

describe('QueuesView selection', () => {
  it('picks and unpicks rows, and the page checkbox picks and unpicks them all', async () => {
    search = {};
    serve({ queues: [queue('orders.a'), queue('orders.b')] });
    const user = userEvent.setup();
    renderWithProviders(<QueuesView />);

    await user.click(await screen.findByRole('checkbox', { name: 'Select row orders.a::orders.a::ANYCAST' }));
    expect(screen.getByText('1 queue selected')).toBeInTheDocument();
    await user.click(screen.getByRole('checkbox', { name: 'Select row orders.a::orders.a::ANYCAST' }));
    expect(screen.getByText(/No queues selected/)).toBeInTheDocument();

    await user.click(screen.getByRole('checkbox', { name: 'Select all on this page' }));
    expect(screen.getByText('2 queues selected')).toBeInTheDocument();
    // Every queue is on this page, so there is nothing more to offer to select.
    expect(screen.queryByRole('button', { name: /Select all 2 queues/ })).not.toBeInTheDocument();
    await user.click(screen.getByRole('checkbox', { name: 'Deselect all on this page' }));
    expect(screen.getByText(/No queues selected/)).toBeInTheDocument();
  });

  it('turns "all matching" back into the names on this page when one row is changed', async () => {
    search = {};
    serve({ queues: [queue('orders.a'), queue('orders.b')], count: 140 });
    const user = userEvent.setup();
    renderWithProviders(<QueuesView />);

    await user.click(await screen.findByRole('checkbox', { name: 'Select all on this page' }));
    await user.click(screen.getByRole('button', { name: 'Select all 140 queues on this cluster' }));
    expect(screen.getByText('All 140 queues on this cluster are selected.')).toBeInTheDocument();

    await user.click(screen.getByRole('checkbox', { name: 'Select row orders.a::orders.a::ANYCAST' }));
    expect(screen.getByText('1 queue selected')).toBeInTheDocument();

    // One of the two was dropped, so the whole page is no longer picked and there is nothing to widen.
    expect(screen.queryByRole('button', { name: /Select all 140 queues/ })).not.toBeInTheDocument();
  });

  it('forgets the selection when the filter changes, since it was made under another set of queues', async () => {
    search = {};
    serve({ queues: [queue('orders.a')] });
    const user = userEvent.setup();
    const { rerender } = renderWithProviders(<QueuesView />);

    await user.click(await screen.findByRole('checkbox', { name: 'Select row orders.a::orders.a::ANYCAST' }));
    expect(screen.getByText('1 queue selected')).toBeInTheDocument();

    search = { q: 'orders' };
    rerender(<QueuesView />);
    await waitFor(() => expect(screen.getByText(/No queues selected/)).toBeInTheDocument());
  });
});

describe('QueuesView empty grid', () => {
  it('says the filter emptied it, and clears the filter from the address', async () => {
    search = { q: 'zzz' };
    serve();
    const user = userEvent.setup();
    renderWithProviders(<QueuesView />);

    expect(await screen.findByText('No queue matches "zzz"')).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Clear filters' }));

    expect(nextSearch({ q: 'zzz', page: 2 })).toEqual({ q: undefined, page: undefined });
    expect(screen.getByRole('textbox', { name: 'Filter queues' })).toHaveValue('');
  });

  it('does not write a half-typed filter back after the filter was cleared', async () => {
    search = { q: 'zzz' };
    serve();
    // Time is driven, not waited for: the filter's debounce has to have expired, however loaded the machine.
    vi.useFakeTimers({ shouldAdvanceTime: true });
    onTestFinished(() => {
      vi.useRealTimers();
    });
    const user = userEvent.setup({ advanceTimers: vi.advanceTimersByTime });
    renderWithProviders(<QueuesView />);

    await screen.findByText('No queue matches "zzz"');
    await user.type(screen.getByRole('textbox', { name: 'Filter queues' }), 'a');
    await user.click(screen.getByRole('button', { name: 'Clear filters' }));
    await act(() => vi.advanceTimersByTimeAsync(1_000));

    expect(navigate).toHaveBeenCalledOnce();
    expect(nextSearch({ q: 'zzz', page: 2 })).toEqual({ q: undefined, page: undefined });
  });

  it('says one unreachable node makes this an incomplete view rather than an empty cluster', async () => {
    search = {};
    serve({ nodes: [{ endpoints: [{ name: 'node-a', lastError: 'connection refused' }, { name: 'node-b' }] }] });
    renderWithProviders(<QueuesView />);

    expect(await screen.findByText('node-a could not be reached')).toBeInTheDocument();
    expect(screen.getByText(/This node did not answer the last scrape/)).toBeInTheDocument();
    expect(screen.queryByText('No queues yet')).not.toBeInTheDocument();
  });

  it('names how many nodes are unreachable, and which', async () => {
    search = {};
    serve({
      nodes: [
        { endpoints: [{ name: 'node-a', lastError: 'refused' }] },
        { endpoints: [{ name: 'node-b', lastError: 'timeout' }] },
      ],
    });
    renderWithProviders(<QueuesView />);

    expect(await screen.findByText('2 nodes could not be reached')).toBeInTheDocument();
    expect(screen.getByText(/These nodes did not answer/)).toBeInTheDocument();
    const nodes = screen.getByRole('list', { name: 'Nodes that could not be reached' });
    expect(
      within(nodes)
        .getAllByRole('listitem')
        .map((li) => li.textContent),
    ).toEqual(['node-a', 'node-b']);
  });

  it('teaches what a queue is when there is none, and offers to create the first where allowed', async () => {
    search = {};
    serve();
    const user = userEvent.setup();
    renderWithProviders(<QueuesView />);

    expect(await screen.findByText('No queues yet')).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Create the first queue' }));
    expect(await screen.findByRole('dialog', { name: 'New queue form' })).toBeInTheDocument();
  });

  it('does not offer to create the first queue to someone who may not', async () => {
    search = {};
    serve({ permissions: ['subscription:read'] });
    renderWithProviders(<QueuesView />);

    expect(await screen.findByText('No queues yet')).toBeInTheDocument();
    // The cluster's rights arrive after the page; until they do, creating is offered.
    await waitFor(() =>
      expect(screen.queryByRole('button', { name: 'Create the first queue' })).not.toBeInTheDocument(),
    );
  });
});

describe('QueuesView ownership and teams', () => {
  it('shows each queue with the team that owns it, as a link to the team, and says when none does', async () => {
    search = {};
    serve({
      queues: [{ ...queue('orders.in'), ownerTeam: { id: 't1', name: 'Orders' } }, queue('legacy.in')] as never,
    });
    renderWithProviders(<QueuesView />);

    const grid = await screen.findByRole('grid', { name: 'Queues' });
    const owner = await within(grid).findByRole('link', { name: 'Owner: team Orders' });
    expect(owner).toHaveAttribute('href', expect.stringContaining('/admin'));
    expect(owner).toHaveAttribute('href', expect.stringContaining('team=t1'));
    expect(within(grid).getByText('No owner')).toBeInTheDocument();
  });

  it("says there are no queues in the caller's teams when they reach queues only through a team", async () => {
    search = {};
    serve({ permissions: [] });
    server.use(teamAccess({ anywhere: ['subscription:read'] }));
    renderWithProviders(<QueuesView />);

    expect(await screen.findByText('No queues in your teams on this cluster')).toBeInTheDocument();
  });

  it('says the caller is in no team on the cluster, and whom to ask, when they reach nothing', async () => {
    search = {};
    serve({ permissions: [] });
    server.use(teamAccess());
    renderWithProviders(<QueuesView />);

    expect(await screen.findByText("You're not in any team on this cluster")).toBeInTheDocument();
    expect(screen.getByText(/Ask a platform administrator to add you to a team/)).toBeInTheDocument();
  });

  it('turns the open queue into the not-found state when it is no longer in what the caller can list', async () => {
    search = { queue: 'orders.in' };
    serve({ queues: [] });
    renderWithProviders(<QueuesView />);

    expect(await screen.findByRole('dialog', { name: 'Queue not found: orders.in' })).toBeInTheDocument();
  });
});

describe('QueuesView creating a queue', () => {
  it('opens the create form from New queue', async () => {
    search = {};
    serve({ queues: [queue('a')] });
    const user = userEvent.setup();
    renderWithProviders(<QueuesView />);

    await user.click(await screen.findByRole('button', { name: 'New queue' }));

    expect(await screen.findByRole('dialog', { name: 'New queue form' })).toBeInTheDocument();
  });

  it('does not offer New queue to a read-only operator, who has nothing to create with', async () => {
    search = {};
    serve({ queues: [queue('a')], permissions: ['subscription:read'] });
    renderWithProviders(<QueuesView />);

    // While grants load the control is offered; it goes once they say the caller can create nowhere.
    await waitFor(() => expect(screen.queryByRole('button', { name: 'New queue' })).not.toBeInTheDocument());
    expect(screen.queryByRole('button', { name: 'Why creating a queue is unavailable' })).not.toBeInTheDocument();
  });

  it('offers New queue to a team member whose patterns let them create, though no grant does', async () => {
    search = {};
    serve({ queues: [queue('a')], permissions: [] });
    server.use(
      teamAccess({
        anywhere: ['subscription:read'],
        createPatterns: { subscription: ['orders.#'], destination: ['orders.#'] },
      }),
    );
    renderWithProviders(<QueuesView />);

    await screen.findByRole('grid', { name: 'Queues' });
    await waitFor(() => expect(screen.getByRole('button', { name: 'New queue' })).toBeEnabled());
    // Settled: the answer is the team's patterns, not the loading state's benefit of the doubt.
    await new Promise((resolve) => setTimeout(resolve, 50));
    expect(screen.getByRole('button', { name: 'New queue' })).toBeEnabled();
  });

  it('says why when the broker connection cannot write', async () => {
    search = {};
    serve({
      queues: [queue('a')],
      managementWrite: {
        status: 'UNAVAILABLE',
        reason: 'Management is read-only on this broker.',
        brokerXmlSnippet: null,
      },
    });
    const user = userEvent.setup();
    renderWithProviders(<QueuesView />);

    await user.click(await screen.findByRole('button', { name: 'Why creating a queue is unavailable' }));
    expect(await screen.findByText('Management is read-only on this broker.')).toBeInTheDocument();
  });
});

describe('QueuesView page structure', () => {
  it('is one page with a single h1 naming the view', async () => {
    search = {};
    serve({ queues: [queue('a')] });
    renderWithProviders(<QueuesView />);

    expect(await screen.findByRole('heading', { level: 1, name: 'Queues' })).toBeInTheDocument();
    expect(screen.getAllByRole('heading', { level: 1 })).toHaveLength(1);
  });

  it('does not say there are no queues while the first page is still loading', async () => {
    search = {};
    serve();
    server.use(http.get('*/api/v1/clusters/c1/subscriptions', async () => delay('infinite')));
    renderWithProviders(<QueuesView />);

    await screen.findByRole('grid', { name: 'Queues' });
    expect(screen.queryByText(/^No queues$/)).not.toBeInTheDocument();
    expect(screen.queryByText('No queues yet')).not.toBeInTheDocument();
  });

  it('never nests a button inside another, when creating is not allowed and the selection bar is shown', async () => {
    search = {};
    // Creating is refused by the broker connection, not by a grant: without the grant New queue is hidden once the
    // caller's access arrives, so its explanation would only be there until then.
    serve({
      queues: [queue('a')],
      managementWrite: { status: 'UNAVAILABLE', reason: 'Read-only.', brokerXmlSnippet: null },
    });
    const { container } = renderWithProviders(<QueuesView />);

    await screen.findByRole('button', { name: 'Why creating a queue is unavailable' });
    expect(container.querySelector('button button')).toBeNull();
    const bar = screen.getByRole('region', { name: 'Selected queues' });
    // The bulk actions the selection bar hosts are gated too, and each explains itself from beside its button.
    expect(
      await within(bar).findByRole('button', { name: /why pausing these queues is unavailable/i }),
    ).toBeInTheDocument();
    expect(bar.closest('button')).toBeNull();
    expect(bar.querySelector('button button')).toBeNull();
  });
});

describe('QueuesView with a queue named like markup', () => {
  it('shows the name as text and builds no element from it', async () => {
    const name = '<img src=x onerror=alert(1)><script>alert(2)</script>';
    search = {};
    serve({ queues: [queue(name)] });
    renderWithProviders(<QueuesView />);

    const cells = await screen.findAllByRole('gridcell');
    expect(cells.some((cell) => cell.textContent?.includes(name))).toBe(true);
    expect(document.body.querySelector('script, img, [onerror]')).toBeNull();
  });
});
