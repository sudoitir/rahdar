import { afterEach, describe, expect, it, vi } from 'vitest';
import { http, HttpResponse } from 'msw';
import { act, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { Notifications, notifications } from '@mantine/notifications';

import { renderWithProviders } from '../../test/render.tsx';
import { server } from '../../test/setup.ts';
import { holdButton } from '../../test/hold.ts';

// The address is state here, as it is in the app: the open message lives in it (`?message=`).
let searchState: Record<string, unknown> = {};
const searchListeners = new Set<() => void>();
afterEach(() => {
  searchState = {};
  act(() => notifications.clean());
});

vi.mock('@tanstack/react-router', async (importOriginal) => {
  const { useSyncExternalStore } = await import('react');
  return {
    ...(await importOriginal<typeof import('@tanstack/react-router')>()),
    useParams: () => ({ clusterId: 'c1', queueName: 'PHASE3.SRC' }),
    useSearch: () =>
      useSyncExternalStore(
        (listener) => {
          searchListeners.add(listener);
          return () => searchListeners.delete(listener);
        },
        () => searchState,
      ),
    useNavigate: () => (options: { search?: unknown }) => {
      if (typeof options.search === 'function') searchState = options.search(searchState);
      searchListeners.forEach((listener) => listener());
    },
    Link: ({ children }: { children: React.ReactNode }) => <a>{children}</a>,
  };
});

const { MessagesView } = await import('./MessagesView.tsx');

const AVAILABLE = { status: 'AVAILABLE', reason: 'ok', brokerXmlSnippet: null };

function endpoint(id: string, name: string, lastError: string | null = null) {
  return {
    id,
    name,
    artemisNodeId: 'NID',
    jolokiaUrl: `http://${name}:8161/jolokia`,
    coreUrl: null,
    haRole: 'PRIMARY',
    state: 'STARTED',
    active: name === 'primary',
    replicaSync: null,
    version: '2.44.0',
    versionSupport: 'SUPPORTED',
    lastError,
    lastSeenAt: null,
    urlSource: 'SEED',
    urlProblem: null,
    coreUrlManual: false,
    manageable: true,
  };
}

/** Who the caller is: everything, unless a test narrows it. */
function signedInWith(permissions: string[]) {
  server.use(
    http.get('*/api/v1/auth/me', () =>
      HttpResponse.json({
        id: 'u1',
        username: 'op',
        mustChangePassword: false,
        grants: [{ scopeType: 'GLOBAL', scopeId: null, permissions }],
      }),
    ),
  );
}

function mockCluster(endpoints: ReturnType<typeof endpoint>[]) {
  signedInWith(['*']);
  server.use(
    http.get('*/api/v1/clusters/c1', () =>
      HttpResponse.json({
        id: 'c1',
        name: 'prod',
        description: null,
        topology: {
          clusterId: 'c1',
          nodes: [{ artemisNodeId: 'NID', splitBrain: 'NONE', replicationBehind: false, endpoints }],
        },
        capabilities: {
          managementRead: AVAILABLE,
          managementWrite: AVAILABLE,
          notifications: { status: 'UNKNOWN', reason: 'phase 4', brokerXmlSnippet: null },
          messageIo: AVAILABLE,
        },
        health: {
          clusterId: 'c1',
          level: 'OK',
          liveEndpointNames: [],
          splitBrain: 'NONE',
          replicationBehind: false,
          notes: [],
        },
      }),
    ),
    http.get('*/api/v1/clusters/c1/queues/PHASE3.SRC/messages', () =>
      HttpResponse.json({ data: [], count: 0, page: 1, pageSize: 200, node: endpoints[0].id }),
    ),
  );
}

describe('MessagesView', () => {
  it('keeps the page current on its own, without an operator pressing refresh', async () => {
    // A queue changes under the operator: a browse that only ever loads once
    // shows an arrangement of the queue that stopped being true when it drew.
    vi.useFakeTimers({ shouldAdvanceTime: true });
    let browses = 0;
    try {
      mockCluster([endpoint('n1', 'primary')]);
      server.use(
        http.get('*/api/v1/clusters/c1/queues/PHASE3.SRC/messages', () => {
          browses += 1;
          return HttpResponse.json({ data: [], count: 0, page: 1, pageSize: 200, node: 'n1' });
        }),
      );
      const { unmount } = renderWithProviders(<MessagesView />);
      await vi.waitFor(() => expect(browses).toBe(1));

      await vi.advanceTimersByTimeAsync(11_000);
      await vi.waitFor(() => expect(browses).toBeGreaterThan(1));
      unmount();
    } finally {
      vi.useRealTimers();
    }
  });

  it('is one page named Messages, with the queue beside its title and the way back to all queues', async () => {
    mockCluster([endpoint('n1', 'primary')]);
    renderWithProviders(<MessagesView />);

    expect(await screen.findByRole('heading', { level: 1, name: 'Messages' })).toBeInTheDocument();
    expect(screen.getAllByRole('heading', { level: 1 })).toHaveLength(1);
    expect(screen.getByText('PHASE3.SRC')).toBeInTheDocument();
    expect(screen.getByText('All queues')).toBeInTheDocument();
  });

  it('says message operations are not available, and keeps Send and Purge visible, disabled, with the reason', async () => {
    mockCluster([endpoint('n1', 'primary')]);
    server.use(
      http.get('*/api/v1/clusters/c1', () =>
        HttpResponse.json({
          id: 'c1',
          name: 'prod',
          description: null,
          topology: { clusterId: 'c1', nodes: [] },
          capabilities: {
            managementRead: AVAILABLE,
            managementWrite: AVAILABLE,
            notifications: AVAILABLE,
            messageIo: { status: 'UNAVAILABLE', reason: 'The broker refused message I/O.', brokerXmlSnippet: null },
            slowConsumerDetection: AVAILABLE,
            versionGates: [],
          },
          health: {
            clusterId: 'c1',
            level: 'OK',
            liveEndpointNames: [],
            splitBrain: 'NONE',
            replicationBehind: false,
            notes: [],
          },
        }),
      ),
    );
    renderWithProviders(<MessagesView />);

    expect(await screen.findByText('Message operations are not available here')).toBeInTheDocument();
    await waitFor(() => expect(screen.getByRole('button', { name: 'Purge queue' })).toBeDisabled());
    expect(screen.getByRole('button', { name: 'Send' })).toBeDisabled();
    expect(screen.getByRole('button', { name: 'Why sending a message is unavailable' })).toBeInTheDocument();
  });

  it('hides the node selector when the queue is served by a single endpoint', async () => {
    mockCluster([endpoint('n1', 'primary')]);
    renderWithProviders(<MessagesView />);

    expect(await screen.findByText('PHASE3.SRC')).toBeInTheDocument();
    expect(screen.queryAllByLabelText('Node to browse')).toHaveLength(0);
  });

  it('shows the node selector when there is more than one manageable endpoint', async () => {
    mockCluster([endpoint('n1', 'primary'), endpoint('n2', 'backup')]);
    renderWithProviders(<MessagesView />);

    expect(await screen.findByText('PHASE3.SRC')).toBeInTheDocument();
    await vi.waitFor(() => expect(screen.getAllByLabelText('Node to browse').length).toBeGreaterThan(0));
  });

  it('states an unavailable total instead of showing zero', async () => {
    mockCluster([endpoint('n1', 'primary')]);
    server.use(
      http.get('*/api/v1/clusters/c1/queues/PHASE3.SRC/messages', () =>
        HttpResponse.json({
          data: [],
          count: null,
          countUnavailable: 'the broker did not answer the count in time',
          page: 1,
          pageSize: 200,
          node: 'n1',
        }),
      ),
    );
    renderWithProviders(<MessagesView />);

    expect(
      await screen.findByText(/total unavailable — the broker did not answer the count in time/),
    ).toBeInTheDocument();
    expect(screen.getByText(/page 1 · total unavailable/)).toBeInTheDocument();
    expect(screen.queryByText(/^0 messages/)).not.toBeInTheDocument();
  });

  it('teaches what a queue is when it has no messages, and clears the selector when one emptied it', async () => {
    mockCluster([endpoint('n1', 'primary')]);
    const { unmount } = renderWithProviders(<MessagesView />);
    expect(await screen.findByText('This queue has no messages')).toBeInTheDocument();
    expect(screen.getByText(/Messages wait in a queue until a consumer takes them/)).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Clear filters' })).not.toBeInTheDocument();
    unmount();

    searchState = { filter: "region = 'eu'" };
    const user = userEvent.setup();
    renderWithProviders(<MessagesView />);
    expect(await screen.findByText('No message matches this selector')).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Clear filters' }));
    await vi.waitFor(() => expect(searchState).toMatchObject({ filter: undefined, page: undefined }));
  });

  it('names a node that did not answer instead of presenting an empty queue', async () => {
    mockCluster([endpoint('n1', 'primary', 'connection refused')]);
    renderWithProviders(<MessagesView />);

    expect(await screen.findByText('No messages could be listed')).toBeInTheDocument();
    expect(
      within(screen.getByRole('list', { name: 'Nodes that could not be reached' })).getByText('primary'),
    ).toBeVisible();
  });

  it('states why the page could not be read, with a retry, in place of the rows', async () => {
    mockCluster([endpoint('n1', 'primary')]);
    server.use(
      http.get('*/api/v1/clusters/c1/queues/PHASE3.SRC/messages', () =>
        HttpResponse.json({ title: 'Cluster unreachable', detail: 'No node answered.' }, { status: 502 }),
      ),
    );
    renderWithProviders(<MessagesView />);

    const alert = await screen.findByRole('alert');
    expect(within(alert).getByText('Rahdar failed to complete the request')).toBeInTheDocument();
    expect(within(alert).getByRole('button', { name: 'Retry' })).toBeInTheDocument();
    expect(screen.queryByText('This queue has no messages')).not.toBeInTheDocument();
  });

  it('keeps Send and Purge visible but disabled, with the reason, for an operator who may only read', async () => {
    mockCluster([endpoint('n1', 'primary')]);
    signedInWith(['message:read']);
    const user = userEvent.setup();
    renderWithProviders(<MessagesView />);

    // The gate wraps the button once the grants say no, so it is looked up again rather than held.
    await vi.waitFor(() => expect(screen.getByRole('button', { name: 'Purge queue' })).toBeDisabled());
    expect(screen.getByRole('button', { name: 'Send' })).toBeDisabled();

    await user.click(screen.getByRole('button', { name: 'Why purging this queue is unavailable' }));
    expect(await screen.findByText(/You do not have the "Purge queues" permission/)).toBeInTheDocument();
    await user.keyboard('{Escape}');
    await user.click(screen.getByRole('button', { name: 'Why sending a message is unavailable' }));
    expect(await screen.findByText(/You do not have the "Send messages" permission/)).toBeInTheDocument();
  });

  it('renders every message of a populated page and opens the one clicked', async () => {
    const summary = (messageId: number, body: string, truncated = false) => ({
      messageId,
      type: 3,
      durable: true,
      priority: 4,
      timestamp: 1789847475826 + messageId,
      expiration: 0,
      size: body.length,
      groupId: null,
      correlationId: null,
      bodyPreview: body,
      bodyTruncated: truncated,
      propertyCount: 1,
      redactions: [],
    });
    mockCluster([endpoint('n1', 'primary'), endpoint('n2', 'secondary')]);
    server.use(
      http.get('*/api/v1/clusters/c1/queues/PHASE3.SRC/messages', () =>
        HttpResponse.json({
          data: [summary(101, 'order A-1'), summary(102, 'order A-2', true), summary(103, 'order A-3')],
          count: 3,
          countUnavailable: null,
          page: 1,
          pageSize: 200,
          node: 'n2',
        }),
      ),
      http.get('*/api/v1/clusters/c1/queues/PHASE3.SRC/messages/102', () =>
        HttpResponse.json({
          ...summary(102, 'order A-2', true),
          userId: null,
          body: 'order A-2',
          bodyEncoding: 'TEXT',
          contentType: null,
          observedLimitBytes: null,
          node: 'n2',
          stringProperties: { orderId: 'A-2' },
          intProperties: {},
          longProperties: {},
          doubleProperties: {},
          booleanProperties: {},
          withheld: [],
        }),
      ),
    );
    const user = userEvent.setup();
    renderWithProviders(<MessagesView />);

    // The count and the node it was read from — the second endpoint, not the first listed.
    expect(await screen.findByText(/3 messages · read from secondary/)).toBeInTheDocument();
    const rows = await screen.findAllByRole('row');
    // Row 0 is the header.
    expect(rows).toHaveLength(4);
    expect(within(rows[1]).getByText('order A-1')).toBeInTheDocument();
    expect(within(rows[2]).getByText('order A-2')).toBeInTheDocument();
    expect(within(rows[2]).getByText('truncated')).toBeInTheDocument();
    expect(within(rows[3]).getByText('order A-3')).toBeInTheDocument();
    expect(screen.queryByText('This queue has no messages')).not.toBeInTheDocument();

    await user.click(within(rows[2]).getByText('order A-2'));

    const drawer = await screen.findByRole('dialog', { name: 'Message 102' });
    expect(await within(drawer).findByText('A-2')).toBeInTheDocument();
  });
});

describe('the purge estimate', () => {
  it('states an estimate that could not be taken, and still lets the purge be armed', async () => {
    mockCluster([endpoint('n1', 'primary')]);
    server.use(
      http.delete('*/api/v1/clusters/c1/queues/PHASE3.SRC/messages', () =>
        HttpResponse.json(
          { title: 'The broker did not answer', detail: 'The node timed out after 5s.' },
          { status: 504 },
        ),
      ),
    );
    const user = userEvent.setup();
    renderWithProviders(<MessagesView />);

    await user.click(await screen.findByRole('button', { name: 'Purge queue' }));
    const dialog = await screen.findByRole('dialog');

    // An absent number reads as zero — the failure is stated instead.
    expect(await within(dialog).findByText(/The node timed out after 5s\./)).toBeInTheDocument();
    expect(within(dialog).queryByText(/Estimating current depth/)).not.toBeInTheDocument();

    // And the confirmation is not left disabled with no reason given.
    await vi.waitFor(() => expect(within(dialog).getByRole('button', { name: 'Purge queue' })).toBeEnabled());
  });
});

describe('the purge and the bulk safety cap', () => {
  it('does not override the cap when the depth is unknown', async () => {
    mockCluster([endpoint('n1', 'primary')]);
    const urls: string[] = [];
    server.use(
      http.delete('*/api/v1/clusters/c1/queues/PHASE3.SRC/messages', ({ request }) => {
        const url = new URL(request.url);
        urls.push(url.search);
        if (url.searchParams.get('dryRun') === 'true') {
          return HttpResponse.json(
            { title: 'The broker did not answer', detail: 'The node timed out after 5s.' },
            { status: 504 },
          );
        }
        return HttpResponse.json({ affectedCount: 4, dryRun: false, node: 'n1' });
      }),
    );
    const user = userEvent.setup();
    renderWithProviders(<MessagesView />);

    await user.click(await screen.findByRole('button', { name: 'Purge queue' }));
    const dialog = await screen.findByRole('dialog');
    expect(await within(dialog).findByText(/The node timed out after 5s\./)).toBeInTheDocument();

    await holdButton(within(dialog).getByRole('button', { name: 'Purge queue' }));

    await vi.waitFor(() => expect(urls).toHaveLength(2));
    // The server's cap is the only guard left when Rahdar cannot state a blast radius.
    expect(urls.at(-1)).not.toContain('override=true');
  });

  it('states the cap it is about to override, and overrides it only then', async () => {
    mockCluster([endpoint('n1', 'primary')]);
    const urls: string[] = [];
    server.use(
      http.delete('*/api/v1/clusters/c1/queues/PHASE3.SRC/messages', ({ request }) => {
        const url = new URL(request.url);
        urls.push(url.search);
        if (url.searchParams.get('dryRun') === 'true') {
          return HttpResponse.json({ affectedCount: 5000, cap: 1000, overCap: true, node: 'n1' });
        }
        return HttpResponse.json({ affectedCount: 5000, dryRun: false, node: 'n1' });
      }),
    );
    const user = userEvent.setup();
    renderWithProviders(<MessagesView />);

    await user.click(await screen.findByRole('button', { name: 'Purge queue' }));
    const dialog = await screen.findByRole('dialog');
    expect(await within(dialog).findByText(/over the cap of 1,000/)).toBeInTheDocument();

    await holdButton(within(dialog).getByRole('button', { name: 'Purge anyway, over the cap' }));

    await vi.waitFor(() => expect(urls).toHaveLength(2));
    expect(urls.at(-1)).toContain('override=true');
  });
});

describe('one message, from its row (ADR-0107)', () => {
  it('deletes a single message only once its id is typed, and states the outcome', async () => {
    const summary = {
      messageId: 205,
      type: 3,
      durable: true,
      priority: 4,
      timestamp: 1789847475826,
      expiration: 0,
      size: 9,
      groupId: null,
      correlationId: null,
      bodyPreview: 'order B-5',
      bodyTruncated: false,
      propertyCount: 0,
      redactions: [],
    };
    const bodies: unknown[] = [];
    mockCluster([endpoint('n1', 'primary')]);
    server.use(
      http.get('*/api/v1/auth/me', () =>
        HttpResponse.json({
          id: 'u1',
          username: 'op',
          mustChangePassword: false,
          grants: [{ scopeType: 'GLOBAL', scopeId: null, permissions: ['*'] }],
        }),
      ),
      http.get('*/api/v1/clusters/c1/queues/PHASE3.SRC/messages', () =>
        HttpResponse.json({
          data: [summary],
          count: 1,
          countUnavailable: null,
          page: 1,
          pageSize: 200,
          node: 'n1',
        }),
      ),
      http.post('*/api/v1/clusters/c1/queues/PHASE3.SRC/messages/actions/delete', async ({ request }) => {
        bodies.push(await request.json());
        return HttpResponse.json({ affectedCount: 1 });
      }),
    );
    const user = userEvent.setup();
    renderWithProviders(
      <>
        <Notifications />
        <MessagesView />
      </>,
    );

    await user.click(await screen.findByRole('button', { name: 'Actions for message 205' }));
    const menu = await screen.findByRole('menu', { name: 'Actions for message 205' });
    await user.click(within(menu).getByRole('menuitem', { name: /^Delete…/ }));

    const dialog = await screen.findByRole('dialog', { name: 'Delete message 205' });
    const confirm = within(dialog).getByRole('button', { name: 'Delete this message' });
    await holdButton(confirm);

    // The outcome is announced, politely, and the dialog is out of the way.
    expect(await screen.findByText('Deleted message 205 in queue "PHASE3.SRC"')).toBeInTheDocument();
    await waitFor(() => expect(screen.queryByRole('dialog', { name: 'Delete message 205' })).not.toBeInTheDocument());
    expect(bodies).toEqual([{ messageIds: [205] }]);
  });

  it('asks for the target queue on the field when a single message is moved without one', async () => {
    const summary = {
      messageId: 206,
      type: 3,
      durable: true,
      priority: 4,
      timestamp: 1789847475826,
      expiration: 0,
      size: 9,
      groupId: null,
      correlationId: null,
      bodyPreview: 'order B-6',
      bodyTruncated: false,
      propertyCount: 0,
      redactions: [],
    };
    const posts: unknown[] = [];
    mockCluster([endpoint('n1', 'primary')]);
    server.use(
      http.get('*/api/v1/clusters/c1/queues/PHASE3.SRC/messages', () =>
        HttpResponse.json({ data: [summary], count: 1, countUnavailable: null, page: 1, pageSize: 200, node: 'n1' }),
      ),
      http.post('*/api/v1/clusters/c1/queues/PHASE3.SRC/messages/actions/move', async ({ request }) => {
        posts.push(await request.json());
        return HttpResponse.json({ affectedCount: 1 });
      }),
    );
    const user = userEvent.setup();
    renderWithProviders(
      <>
        <Notifications />
        <MessagesView />
      </>,
    );

    await user.click(await screen.findByRole('button', { name: 'Actions for message 206' }));
    const menu = await screen.findByRole('menu', { name: 'Actions for message 206' });
    await user.click(within(menu).getByRole('menuitem', { name: /^Move…/ }));

    const dialog = await screen.findByRole('dialog', { name: 'Move message 206' });
    await user.click(within(dialog).getByRole('button', { name: 'Move message' }));
    const target = within(dialog).getByLabelText('Target queue');
    expect(target).toBeInvalid();
    expect(within(dialog).getByText('Name the target queue to move the message.')).toBeInTheDocument();
    expect(target).toHaveFocus();
    expect(posts).toHaveLength(0);

    await user.type(target, 'ARCHIVE');
    await user.click(within(dialog).getByRole('button', { name: 'Move message' }));
    expect(await screen.findByText('Moved message 206 in queue "PHASE3.SRC"')).toBeInTheDocument();
    expect(posts).toEqual([{ messageIds: [206], targetQueue: 'ARCHIVE' }]);
  });
});
