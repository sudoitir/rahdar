import { beforeEach, describe, expect, it, vi } from 'vitest';
import { http, HttpResponse } from 'msw';
import { act, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

import { renderWithProviders } from '../../test/render.tsx';
import { EventSourceStub, server } from '../../test/setup.ts';

const search = { current: {} as { q?: string; live?: boolean } };

const navigate = vi.fn();

vi.mock('@tanstack/react-router', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@tanstack/react-router')>()),
  useParams: () => ({ clusterId: 'c1' }),
  useSearch: () => search.current,
  useNavigate: () => navigate,
}));

const { SqlConsoleView } = await import('./SqlConsoleView.tsx');

const AVAILABLE = { status: 'AVAILABLE', reason: 'ok', brokerXmlSnippet: null };

function target(nodeId: string, nodeName: string) {
  return {
    nodeId,
    nodeName,
    queueName: 'ORDER.IN',
    address: 'ORDER.IN',
    messageCount: 1200,
  };
}

function plan(overrides: Record<string, unknown> = {}) {
  return {
    source: 'BROKER',
    targets: [target('n1', 'primary')],
    selector: 'JMSPriority > 4',
    requiresScan: false,
    pushedDown: ['priority > 4'],
    scanned: [],
    estimatedMessagesExamined: 0,
    effectiveLimit: 100,
    notices: [],
    ...overrides,
  };
}

function answered(overrides: Record<string, unknown> = {}) {
  return {
    nodeId: 'n1',
    nodeName: 'primary',
    queueName: 'ORDER.IN',
    status: 'ANSWERED',
    examined: 500,
    matched: 0,
    detail: null,
    ...overrides,
  };
}

function mockCluster(over: { permissions?: string[]; messageIo?: object; queues?: object[] } = {}) {
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
          notifications: {
            status: 'UNKNOWN',
            reason: 'n/a',
            brokerXmlSnippet: null,
          },
          messageIo: over.messageIo ?? AVAILABLE,
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
    http.get('*/api/v1/clusters/c1/queues', () =>
      HttpResponse.json({ data: over.queues ?? [], count: (over.queues ?? []).length, page: 1, pageSize: 500 }),
    ),
    http.get('*/api/v1/auth/me', () =>
      HttpResponse.json({
        username: 'op',
        displayName: 'Op',
        provider: 'LOCAL',
        mustChangePassword: false,
        grants: [
          {
            scopeType: 'GLOBAL',
            scopeId: null,
            roleName: 'admin',
            permissions: over.permissions ?? ['*'],
          },
        ],
      }),
    ),
  );
}

function mockPlan(body: Record<string, unknown> = plan()) {
  server.use(
    http.post('*/api/v1/clusters/c1/sql/plan', () => HttpResponse.json(body)),
    // Execution is POST-then-stream (ADR-0064): the text is posted and the stream is
    // opened by reference, so the query never appears in a URL.
    http.post('*/api/v1/clusters/c1/sql/query', () =>
      HttpResponse.json({ queryId: 'q-1', expiresAt: new Date().toISOString() }),
    ),
  );
}

/** Click Run and wait for the query stream to be opened. */
async function run(user: ReturnType<typeof userEvent.setup>, name = 'Run') {
  await user.click(await screen.findByRole('button', { name }));
  await vi.waitFor(() => expect(EventSourceStub.instances).toHaveLength(1));
}

/** Deliver one frame from the server, inside act so React sees the state change. */
function emit(type: string, data: unknown) {
  act(() => EventSourceStub.emit(type, data));
}

describe('SqlConsoleView', () => {
  it('states that a query is pushed down and costs no scan', async () => {
    mockCluster();
    mockPlan();
    renderWithProviders(<SqlConsoleView />);

    expect(
      await screen.findByText('The brokers filter; Rahdar examines at most 100 messages from 1 queue.'),
    ).toBeInTheDocument();
    expect(screen.getByText('Broker-filtered')).toBeInTheDocument();
    expect(screen.getByText(/pushed down:/i)).toBeInTheDocument();
  });

  it('states the estimate when a predicate forces a scan, rather than omitting it', async () => {
    mockCluster();
    mockPlan(
      plan({
        requiresScan: true,
        pushedDown: [],
        scanned: ["body LIKE '%4471%'"],
        estimatedMessagesExamined: 12400,
      }),
    );
    renderWithProviders(<SqlConsoleView />);

    expect(
      await screen.findByText('Rahdar reads and examines about 12,400 messages on 1 queue across 1 node.'),
    ).toBeInTheDocument();
    expect(screen.getByText('Scan')).toBeInTheDocument();
    expect(screen.getByText(/scanned by rahdar:/i)).toBeInTheDocument();
  });

  it('reports a rejected query on its offending token, and in words, instead of a generic failure', async () => {
    search.current = { q: 'SELECT * FROM "A" JOIN "B"' };
    mockCluster();
    server.use(
      http.post('*/api/v1/clusters/c1/sql/plan', () =>
        HttpResponse.json(
          {
            title: 'That is not the console’s dialect',
            detail: 'JOIN is not part of this dialect.',
            offending: 'JOIN',
            suggestion: null,
          },
          { status: 400 },
        ),
      ),
    );
    renderWithProviders(<SqlConsoleView />);

    expect(await screen.findByText('Not estimated: JOIN is not part of this dialect.')).toBeInTheDocument();
    expect(screen.getByText('Unavailable')).toBeInTheDocument();
    // The token is marked in the editor as a lint diagnostic, at its position.
    await vi.waitFor(() => expect(document.querySelector('.cm-lintRange-error')?.textContent).toBe('JOIN'));
    search.current = {};
  });

  it('renders a partial result as a per-node outcome, not as an empty table', async () => {
    mockCluster();
    mockPlan();
    const user = userEvent.setup();
    renderWithProviders(<SqlConsoleView />);
    await run(user);

    emit('done', {
      nodes: [
        answered(),
        answered({
          nodeId: 'n2',
          nodeName: 'backup',
          status: 'FAILED',
          examined: 0,
          detail: 'connection refused',
        }),
      ],
      boundsReached: [{ kind: 'SCAN_CAP', value: 50000 }],
      notices: [],
      partial: true,
      plan: plan(),
    });

    // Three on purpose: the aria-live region, the meta bar's headline, and the
    // per-node summary's verdict. All three say the same thing rather than one of
    // them softening it.
    expect(await screen.findAllByText(/incomplete, this is a prefix of the answer/i)).toHaveLength(3);
    expect(screen.getByText('connection refused')).toBeInTheDocument();

    // Every bound is still stated in full — behind a disclosure rather than in a
    // full-width alert that pushes the rows off screen (7.4).
    await user.click(await screen.findByRole('button', { name: /what this means/i }));
    expect(screen.getByText(/the scan cap/i)).toBeInTheDocument();
    // An unanswered node is not an empty result, and must not be presented as one.
    expect(screen.getByText(/not because there was nothing/i)).toBeInTheDocument();
  });

  it('renders every row a multi-node broker result streams, attributed to its node', async () => {
    const twoNodes = plan({
      targets: [target('n1', 'primary'), target('n2', 'secondary')],
    });
    mockCluster();
    mockPlan(twoNodes);
    const user = userEvent.setup();
    renderWithProviders(<SqlConsoleView />);
    await run(user);

    const row = (nodeId: string, nodeName: string, messageId: number, body: string) => ({
      nodeId,
      nodeName,
      queueName: 'ORDER.IN',
      address: 'ORDER.IN',
      messageId,
      messageType: 3,
      durable: true,
      priority: 4,
      timestamp: 1757000000000 + messageId,
      expiration: 0,
      size: body.length,
      body,
      bodyTruncated: false,
      properties: {},
      source: 'BROKER',
      observedAt: null,
      lastSeenAt: null,
    });
    emit('row', row('n1', 'primary', 11, 'order 4471'));
    emit('row', row('n1', 'primary', 12, 'order 4472'));
    emit('row', row('n2', 'secondary', 21, 'order 5501'));
    emit('done', {
      nodes: [
        answered({ examined: 2, matched: 2 }),
        answered({ nodeId: 'n2', nodeName: 'secondary', examined: 1, matched: 1 }),
      ],
      boundsReached: [],
      notices: [],
      partial: false,
      plan: twoNodes,
    });

    await vi.waitFor(() => expect(screen.getAllByText(/order 4471|order 4472|order 5501/)).toHaveLength(3));
    const dataRows = screen.getAllByRole('row').filter((r) => /order \d{4}/.test(r.textContent ?? ''));
    expect(dataRows).toHaveLength(3);
    expect(
      within(dataRows.find((r) => r.textContent?.includes('order 5501'))!).getByText('secondary'),
    ).toBeInTheDocument();
    expect(dataRows.filter((r) => r.textContent?.includes('primary'))).toHaveLength(2);
  });

  it('distinguishes no-queue-matched from an empty queue', async () => {
    mockCluster();
    mockPlan(plan({ targets: [] }));
    const user = userEvent.setup();
    renderWithProviders(<SqlConsoleView />);
    await run(user);

    emit('done', {
      nodes: [],
      boundsReached: [],
      notices: [{ kind: 'NO_QUEUE_MATCHED', detail: null }],
      partial: false,
      plan: plan({ targets: [] }),
    });

    expect(await screen.findByText('No queue matched')).toBeInTheDocument();
  });

  it('refuses an over-budget query with its estimate and how to narrow it', async () => {
    mockCluster();
    mockPlan();
    const user = userEvent.setup();
    renderWithProviders(<SqlConsoleView />);
    await run(user);

    // The refusal is a frame, not an HTTP status: an EventSource cannot read the
    // body of a non-200, and a refusal without its estimate is not actionable.
    emit('failed', {
      status: 422,
      title: 'Query refused before it was started',
      detail: 'This query is too expensive to run.',
      estimate: 900000,
      ceiling: 250000,
      hint: 'Add a header predicate so the broker filters first.',
    });

    expect(await screen.findByText(/900,000 messages, against a ceiling of 250,000/i)).toBeInTheDocument();
    expect(screen.getByText(/add a header predicate/i)).toBeInTheDocument();
  });

  it('states the sampled-tail limitation while tailing, with no way to dismiss it', async () => {
    mockCluster();
    mockPlan();
    const user = userEvent.setup();
    renderWithProviders(<SqlConsoleView />);

    await user.click(await screen.findByRole('switch', { name: /live tail/i }));
    await run(user, 'Run and tail');
    emit('done', {
      nodes: [answered()],
      boundsReached: [],
      notices: [],
      partial: false,
      plan: plan(),
    });
    emit('tail', {
      enqueued: 40,
      shown: 12,
      polls: 3,
      lastPollAt: '2026-09-07T10:00:00Z',
      everyMessageMatches: true,
    });

    const notice = await screen.findByText(/is never seen/i);
    expect(notice).toBeInTheDocument();
    // Non-dismissable: the whole notice offers no close control. Only "stop
    // tailing" removes it, and that ends the tail rather than hiding its caveat.
    const banner = notice.closest('output') as HTMLElement;
    expect(within(banner).getByText('Live tail: a sample, not a capture')).toBeInTheDocument();
    expect(within(banner).queryByRole('button', { name: /close/i })).not.toBeInTheDocument();
    // The observed gap is reported as a figure, not implied.
    expect(screen.getByText(/28 passed through between reads/i)).toBeInTheDocument();
  });

  it('stops the tail when the operator stops it, closing the stream', async () => {
    mockCluster();
    mockPlan();
    const user = userEvent.setup();
    renderWithProviders(<SqlConsoleView />);

    await user.click(await screen.findByRole('switch', { name: /live tail/i }));
    await run(user, 'Run and tail');
    emit('done', {
      nodes: [answered()],
      boundsReached: [],
      notices: [],
      partial: false,
      plan: plan(),
    });

    await user.click(await screen.findByRole('button', { name: /stop tailing/i }));

    // Closing the stream is what stops the poller: the server's sink reports
    // itself cancelled and issues no further broker read.
    await vi.waitFor(() => expect(EventSourceStub.instances[0].readyState).toBe(2));
    expect(screen.queryByText(/is never seen/i)).not.toBeInTheDocument();
  });

  it('does not silently re-run a query whose stream dropped', async () => {
    mockCluster();
    mockPlan();
    const user = userEvent.setup();
    renderWithProviders(<SqlConsoleView />);
    await run(user);

    act(() => EventSourceStub.instances[0].onerror?.());

    // Twice on purpose: the aria-live announcement and the alert's own title.
    expect(await screen.findAllByText(/connection to this query was lost/i)).toHaveLength(2);
    expect(screen.getByRole('button', { name: /run it again/i })).toBeInTheDocument();
    // One stream, not two: reconnecting would fan out across brokers a second
    // time and write a second audit record for one intent.
    expect(EventSourceStub.instances).toHaveLength(1);
  });

  it('runs the query again on a new stream when the server asks to reconnect', async () => {
    mockCluster();
    mockPlan();
    const user = userEvent.setup();
    renderWithProviders(<SqlConsoleView />);
    await user.click(await screen.findByRole('switch', { name: /live tail/i }));
    await run(user, 'Run and tail');
    emit('done', { nodes: [answered()], boundsReached: [], notices: [], partial: false, plan: plan() });

    act(() => EventSourceStub.emit('reconnect', 0, 0));

    // A new ticket and a new stream, not a "connection lost" the operator has to act on.
    await vi.waitFor(() => expect(EventSourceStub.instances).toHaveLength(2));
    expect(EventSourceStub.instances[0].readyState).toBe(2);
    expect(screen.queryByText(/connection to this query was lost/i)).not.toBeInTheDocument();
  });

  it('offers Verify on an indexed row, and never reports an unsettled read as gone', async () => {
    mockCluster();
    mockPlan(plan({ source: 'INDEX' }));
    server.use(
      http.post('*/api/v1/clusters/c1/sql/verify', () =>
        HttpResponse.json({
          presence: 'UNKNOWN',
          detail: 'More messages share this message’s enqueue time than one read returns.',
        }),
      ),
    );
    const user = userEvent.setup();
    renderWithProviders(<SqlConsoleView />);
    await run(user);

    emit('row', {
      nodeId: 'n1',
      nodeName: 'primary',
      queueName: 'ORDER.IN',
      address: 'ORDER.IN',
      messageId: 42,
      messageType: 3,
      durable: true,
      priority: 4,
      timestamp: 1757000000000,
      expiration: 0,
      size: 120,
      body: '{"orderId":"4471"}',
      bodyTruncated: false,
      properties: {},
      source: 'INDEX',
      observedAt: '2026-09-07T09:00:00Z',
      lastSeenAt: '2026-09-07T09:00:05Z',
    });
    emit('done', {
      nodes: [answered({ matched: 1 })],
      boundsReached: [],
      notices: [],
      partial: false,
      plan: plan({ source: 'INDEX' }),
    });

    // The result says where it came from once, in the meta bar, not only in a
    // per-row badge — and a sampled index row is not called a captured one.
    expect(await screen.findByText(/from the index — sampled/i)).toBeInTheDocument();

    await user.click(await screen.findByRole('button', { name: 'Verify' }));

    // UNKNOWN is rendered as unknown. Folding it into "gone" would tell an
    // operator a message was consumed on the strength of a read that could not
    // settle the question.
    expect(await screen.findByText('unknown')).toBeInTheDocument();
    expect(screen.queryByText('gone')).not.toBeInTheDocument();
  });

  it('opens the syntax help from the keyboard and returns focus to its trigger', async () => {
    mockCluster();
    mockPlan();
    const user = userEvent.setup();
    renderWithProviders(<SqlConsoleView />);

    const trigger = await screen.findByRole('button', {
      name: 'Syntax and examples',
    });
    trigger.focus();
    await user.keyboard('{Enter}');

    const dialog = await screen.findByRole('dialog');
    expect(within(dialog).getByRole('heading', { name: 'Where the query reads' })).toBeInTheDocument();
    expect(within(dialog).getByRole('heading', { name: 'What a predicate costs' })).toBeInTheDocument();

    await user.keyboard('{Escape}');
    await vi.waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
    await vi.waitFor(() => expect(trigger).toHaveFocus());
  });

  it('restores the query and the tail from the URL so a console link opens as it was left', async () => {
    search.current = { q: 'SELECT * FROM "SHARED.Q" LIMIT 7', live: true };
    mockCluster();
    mockPlan();
    renderWithProviders(<SqlConsoleView />);

    const editor = await screen.findByRole('textbox', { name: 'Query' });
    await vi.waitFor(() => expect(editor.textContent).toContain('SHARED.Q'));
    expect(await screen.findByRole('switch', { name: /live tail/i })).toBeChecked();
    expect(screen.getByRole('button', { name: 'Run and tail' })).toBeInTheDocument();
    search.current = {};
  });
});

const messageRow = (messageId: number, body = `order ${messageId}`) => ({
  nodeId: 'n1',
  nodeName: 'primary',
  queueName: 'ORDER.IN',
  address: 'ORDER.IN',
  messageId,
  messageType: 3,
  durable: true,
  priority: 4,
  timestamp: 1757000000000 + messageId,
  expiration: 0,
  size: body.length,
  body,
  bodyTruncated: false,
  properties: {},
  source: 'BROKER',
  observedAt: null,
  lastSeenAt: null,
});

const doneFrame = (over: Record<string, unknown> = {}) => ({
  nodes: [answered({ matched: 1 })],
  boundsReached: [],
  notices: [],
  partial: false,
  plan: plan(),
  ...over,
});

/** What the last navigate() call writes to the address. */
function writtenSearch() {
  const call = navigate.mock.lastCall![0] as { search: () => unknown };
  return call.search();
}

describe('SqlConsoleView result outcomes', () => {
  beforeEach(() => {
    window.localStorage.clear();
    navigate.mockReset();
  });

  it('says no node answered rather than presenting the result as an answer', async () => {
    mockCluster();
    mockPlan();
    const user = userEvent.setup();
    renderWithProviders(<SqlConsoleView />);
    await run(user);

    emit('done', doneFrame({ nodes: [answered({ status: 'FAILED', detail: 'timed out' })] }));

    expect(await screen.findAllByText('No node answered — this result is not an answer')).toHaveLength(3);
  });

  it('counts the rows and the targets that answered, in the singular for one row', async () => {
    mockCluster();
    mockPlan();
    const user = userEvent.setup();
    renderWithProviders(<SqlConsoleView />);
    await run(user);

    emit('row', messageRow(11));
    emit('done', doneFrame());

    expect((await screen.findAllByText('1 row from 1 of 1 targets')).length).toBeGreaterThan(0);
  });

  it('shows the rows so far while the query runs, and announces that it is running', async () => {
    mockCluster();
    mockPlan();
    const user = userEvent.setup();
    renderWithProviders(<SqlConsoleView />);
    await run(user);

    emit('row', messageRow(11));
    emit('row', messageRow(12));

    expect(await screen.findByText('Running — 2 rows so far')).toBeInTheDocument();
    expect(await screen.findAllByText('Running query')).not.toHaveLength(0);
  });

  it('says nothing matched, and not that a queue is empty, when every target answered', async () => {
    mockCluster();
    mockPlan();
    const user = userEvent.setup();
    renderWithProviders(<SqlConsoleView />);
    await run(user);

    emit('done', doneFrame({ nodes: [answered({ matched: 0 })] }));

    expect(await screen.findByText('No message matched')).toBeInTheDocument();
  });

  it('says nothing has matched yet, and why, when a tail starts on an empty match', async () => {
    mockCluster();
    mockPlan();
    const user = userEvent.setup();
    renderWithProviders(<SqlConsoleView />);
    await user.click(await screen.findByRole('switch', { name: /live tail/i }));
    await run(user, 'Run and tail');

    emit('done', doneFrame({ nodes: [answered({ matched: 0 })] }));

    expect(await screen.findByText('Nothing matched yet')).toBeInTheDocument();
    expect(await screen.findAllByText(/Live tail running\. 0 rows so far\./)).not.toHaveLength(0);
  });

  it('states a refusal that carries no estimate with the advice for its status', async () => {
    mockCluster();
    mockPlan();
    const user = userEvent.setup();
    renderWithProviders(<SqlConsoleView />);
    await run(user);

    emit('failed', { status: 429, title: 'Too many queries', detail: 'You have 3 queries running.' });

    expect(await screen.findByText('Too many requests')).toBeInTheDocument();
    expect(screen.getAllByText('You have 3 queries running.')).not.toHaveLength(0);
    expect(
      screen.getByText('Wait for one of your running queries to finish, then run this one again.'),
    ).toBeInTheDocument();
    expect(screen.queryByText(/against a ceiling of/)).not.toBeInTheDocument();
    expect(await screen.findAllByText('Query failed: You have 3 queries running.')).not.toHaveLength(0);
  });

  it('gives the narrowing advice for any other failure without a hint of its own', async () => {
    mockCluster();
    mockPlan();
    const user = userEvent.setup();
    renderWithProviders(<SqlConsoleView />);
    await run(user);

    emit('failed', { status: 500, title: 'Query failed', detail: 'The broker reset the connection.' });

    expect(await screen.findByText(/Narrow the query — a header or property predicate/)).toBeInTheDocument();
  });
});

describe('SqlConsoleView running a query', () => {
  beforeEach(() => {
    window.localStorage.clear();
    navigate.mockReset();
  });

  it('writes the query into the address when it is run, and never on a keystroke', async () => {
    search.current = { q: 'SELECT * FROM "A.B"' };
    mockCluster();
    mockPlan();
    const user = userEvent.setup();
    renderWithProviders(<SqlConsoleView />);
    await screen.findByRole('textbox', { name: 'Query' });
    expect(navigate).not.toHaveBeenCalled();

    await run(user);

    expect(writtenSearch()).toEqual({ q: 'SELECT * FROM "A.B"', live: undefined });
    search.current = {};
  });

  it('reopens a running query in the other mode when the tail is switched, and writes it to the address', async () => {
    mockCluster();
    mockPlan();
    const user = userEvent.setup();
    renderWithProviders(<SqlConsoleView />);
    await run(user);

    await user.click(screen.getByRole('switch', { name: /live tail/i }));

    await vi.waitFor(() => expect(EventSourceStub.instances.length).toBeGreaterThan(1));
    expect(writtenSearch()).toEqual({ q: expect.stringContaining('SELECT'), live: true });
  });

  it('drops the tail from the address when it is stopped', async () => {
    mockCluster();
    mockPlan();
    const user = userEvent.setup();
    renderWithProviders(<SqlConsoleView />);
    await user.click(await screen.findByRole('switch', { name: /live tail/i }));
    await run(user, 'Run and tail');
    emit('done', doneFrame());

    await user.click(await screen.findByRole('button', { name: /stop tailing/i }));

    expect(writtenSearch()).toEqual({ q: expect.stringContaining('SELECT') });
    expect(screen.getByRole('switch', { name: /live tail/i })).not.toBeChecked();
  });

  it('offers a run again after a dropped stream, and that opens a second stream on request only', async () => {
    mockCluster();
    mockPlan();
    const user = userEvent.setup();
    renderWithProviders(<SqlConsoleView />);
    await run(user);
    act(() => EventSourceStub.instances[0].onerror?.());

    await user.click(await screen.findByRole('button', { name: /run it again/i }));

    await vi.waitFor(() => expect(EventSourceStub.instances).toHaveLength(2));
  });

  it('cannot run an empty query', async () => {
    mockCluster();
    mockPlan();
    const user = userEvent.setup();
    renderWithProviders(<SqlConsoleView />);

    const editor = await screen.findByRole('textbox', { name: 'Query' });
    await user.click(editor);
    await user.keyboard('{Control>}a{/Control}{Backspace}');

    await vi.waitFor(() => expect(screen.getByRole('button', { name: 'Run' })).toBeDisabled());
  });
});

describe('SqlConsoleView permission and capability', () => {
  beforeEach(() => {
    window.localStorage.clear();
    navigate.mockReset();
  });

  it('keeps Run and Live tail visible but disabled, and says what is missing', async () => {
    mockCluster({ permissions: ['queue:read'] });
    mockPlan();
    renderWithProviders(<SqlConsoleView />);

    await vi.waitFor(() => expect(screen.getByRole('button', { name: 'Run' })).toBeDisabled());
    expect(screen.getByRole('switch', { name: /live tail/i })).toBeDisabled();
    expect(await screen.findByText('This console cannot read messages here')).toBeInTheDocument();
    // Said in the notice, and again where the cost would be: an estimate that cannot be made is stated.
    expect(screen.getAllByText(/You do not have the "Browse messages" permission/)).toHaveLength(2);
    expect(screen.getByText('Unavailable')).toBeInTheDocument();
  });

  it('says when message access is not yet established, and offers the console anyway', async () => {
    mockCluster({ messageIo: { status: 'UNKNOWN', reason: 'not tried', brokerXmlSnippet: null } });
    mockPlan();
    renderWithProviders(<SqlConsoleView />);

    expect(await screen.findByText('Not yet established for this connection')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Run' })).toBeEnabled();
  });

  it('says completion is capped when the cluster has more queues than it lists', async () => {
    const queues = Array.from({ length: 500 }, (_, i) => ({ queueName: `Q.${i}` }));
    mockCluster({ queues });
    mockPlan();
    renderWithProviders(<SqlConsoleView />);

    expect(
      await screen.findByText(/completion lists the first 500 queues on this cluster, not all of them/),
    ).toBeInTheDocument();
  });
});

describe('SqlConsoleView history', () => {
  beforeEach(() => {
    window.localStorage.clear();
    navigate.mockReset();
  });

  it('says it is empty at first, and that what it holds stays in this browser', async () => {
    mockCluster();
    mockPlan();
    const user = userEvent.setup();
    renderWithProviders(<SqlConsoleView />);

    await user.click(await screen.findByRole('button', { name: 'History' }));

    expect(
      await screen.findByText(/Nothing yet\. Queries you run on this browser are remembered here/),
    ).toBeInTheDocument();
  });

  it('remembers a finished run with its row count and source, loads it back without running it, and clears', async () => {
    search.current = { q: 'SELECT * FROM "REMEMBERED"' };
    mockCluster();
    mockPlan();
    const user = userEvent.setup();
    renderWithProviders(<SqlConsoleView />);
    await run(user);
    emit('row', messageRow(11));
    emit('row', messageRow(12));
    emit('done', doneFrame({ nodes: [answered({ matched: 2 })] }));
    await screen.findAllByText('2 rows from 1 of 1 targets');

    await user.click(await screen.findByRole('button', { name: 'History' }));
    // The entry, then Clear history, are the whole menu.
    const [entry] = await screen.findAllByRole('menuitem');
    expect(entry).toHaveTextContent('SELECT * FROM "REMEMBERED"');
    expect(entry).toHaveTextContent('2 rows · brokers');

    search.current = {};
    await user.click(entry);
    const streams = EventSourceStub.instances.length;
    expect(screen.getByRole('textbox', { name: 'Query' }).textContent).toContain('REMEMBERED');
    expect(EventSourceStub.instances).toHaveLength(streams);

    await vi.waitFor(() => expect(screen.queryAllByRole('menuitem')).toHaveLength(0));
    await user.click(screen.getByRole('button', { name: 'History' }));
    const items = await screen.findAllByRole('menuitem');
    expect(items.at(-1)).toHaveTextContent('Clear history');
    await user.click(items.at(-1)!);
    await vi.waitFor(() => expect(screen.queryAllByRole('menuitem')).toHaveLength(0));
    await user.click(screen.getByRole('button', { name: 'History' }));
    expect(await screen.findByText(/Nothing yet/)).toBeInTheDocument();
    expect(window.localStorage.getItem('rahdar.sql.history')).toBeNull();
  });

  it('names an index run as such, and a single row in the singular', async () => {
    window.localStorage.setItem(
      'rahdar.sql.history',
      JSON.stringify([{ sql: 'SELECT 1', at: '2026-09-01T10:00:00Z', rowCount: 1, source: 'INDEX' }]),
    );
    mockCluster();
    mockPlan();
    const user = userEvent.setup();
    renderWithProviders(<SqlConsoleView />);

    await user.click(await screen.findByRole('button', { name: 'History' }));

    expect(await screen.findByRole('menuitem', { name: /SELECT 1/ })).toHaveTextContent('1 row · index');
  });
});

describe('SqlConsoleView result columns and export', () => {
  beforeEach(() => {
    window.localStorage.clear();
    navigate.mockReset();
  });

  async function withRows() {
    mockCluster();
    mockPlan();
    const user = userEvent.setup();
    renderWithProviders(<SqlConsoleView />);
    await run(user);
    emit('row', messageRow(11, 'order, "4471"'));
    emit('done', doneFrame());
    await screen.findByRole('button', { name: 'Columns' });
    return user;
  }

  const headers = () => screen.getAllByRole('columnheader').map((header) => header.textContent);

  it('leads with the message, then the queue and the node, as the table’s own columns', async () => {
    await withRows();
    expect(headers().map((h) => h?.replace(/\s+/g, ' ').trim())).toEqual([
      'Message ID',
      'Queue',
      'Node',
      'Source',
      'Enqueued',
      'Body',
      'Prio',
      'Size',
      'On broker',
    ]);
    // One Columns control, the table's: the console no longer has a picker of its own.
    expect(screen.getAllByRole('button', { name: 'Columns' })).toHaveLength(1);
  });

  it('hides, shows and moves a column from the table’s menu, remembering it under the table’s key', async () => {
    const user = await withRows();
    await user.click(screen.getByRole('button', { name: 'Columns' }));

    await user.click(await screen.findByLabelText('Prio'));
    await vi.waitFor(() => expect(screen.queryByRole('columnheader', { name: /Prio/ })).not.toBeInTheDocument());
    expect(JSON.parse(window.localStorage.getItem('as.table.sql.results')!)).toMatchObject({ hidden: ['priority'] });

    await user.click(screen.getByRole('button', { name: 'Move Node earlier' }));
    expect(headers().slice(0, 3)).toEqual(['Message ID', 'Node', 'Queue']);
    expect(JSON.parse(window.localStorage.getItem('as.table.sql.results')!).order.slice(0, 3)).toEqual([
      'messageId',
      'node',
      'queue',
    ]);
    // The message identifies the row and stays first.
    expect(screen.getByRole('button', { name: 'Move Message ID later' })).toBeDisabled();
  });

  it('neither reads nor writes the console’s old storage keys', async () => {
    window.localStorage.setItem('rahdar.sql.columns', JSON.stringify(['body']));
    window.localStorage.setItem('rahdar.sql.editorFraction', '0.7');
    await withRows();

    expect(screen.getByRole('columnheader', { name: /Prio/ })).toBeInTheDocument();
    // The old fraction is ignored: with rows and no split of its own, the results get the larger share.
    expect(screen.getByRole('separator', { name: 'Resize the editor and the results' })).toHaveAttribute(
      'aria-valuenow',
      '28',
    );
    expect(window.localStorage.getItem('rahdar.sql.columns')).toBe('["body"]');
    expect(window.localStorage.getItem('rahdar.sql.editorFraction')).toBe('0.7');
  });

  it('exports exactly the rows in view as CSV and JSON, saying they may be a prefix', async () => {
    const files: { name: string; blob: Blob }[] = [];
    let last: Blob | null = null;
    URL.createObjectURL = vi.fn((blob: Blob | MediaSource) => {
      last = blob as Blob;
      return 'blob:sql';
    });
    URL.revokeObjectURL = vi.fn();
    vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(function (this: HTMLAnchorElement) {
      files.push({ name: this.download, blob: last! });
    });
    const user = await withRows();

    expect(
      screen.getByText(/Export writes the 1 row in view, which may be a prefix of the answer/),
    ).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'CSV' }));
    await user.click(screen.getByRole('button', { name: 'JSON' }));

    expect(files.map((f) => f.name)).toEqual(['sql-console.csv', 'sql-console.json']);
    const csv = await files[0].blob.text();
    expect(csv.split('\r\n')[0]).toContain('queue,address,messageId');
    expect(csv).toContain('"order, ""4471"""');
    const json = JSON.parse(await files[1].blob.text());
    expect(json).toHaveLength(1);
    expect(json[0]).toMatchObject({ queue: 'ORDER.IN', messageId: 11, body: 'order, "4471"' });
    vi.restoreAllMocks();
  });
});

describe('SqlConsoleView workspace split', () => {
  beforeEach(() => {
    window.localStorage.clear();
    navigate.mockReset();
  });

  const handle = () => screen.getByRole('separator', { name: 'Resize the editor and the results' });

  it('divides the editor from the results with a separator that starts at 36 percent', async () => {
    mockCluster();
    mockPlan();
    renderWithProviders(<SqlConsoleView />);

    expect(await screen.findByRole('separator', { name: 'Resize the editor and the results' })).toHaveAttribute(
      'aria-valuenow',
      '36',
    );
  });

  it('starts where this browser remembered it, and ignores a split it cannot trust', async () => {
    window.localStorage.setItem('as:sql:split', JSON.stringify([50, 50]));
    mockCluster();
    mockPlan();
    const { unmount } = renderWithProviders(<SqlConsoleView />);
    expect(await screen.findByRole('separator', { name: 'Resize the editor and the results' })).toHaveAttribute(
      'aria-valuenow',
      '50',
    );
    unmount();

    window.localStorage.setItem('as:sql:split', JSON.stringify([2, 98]));
    renderWithProviders(<SqlConsoleView />);
    expect(await screen.findByRole('separator', { name: 'Resize the editor and the results' })).toHaveAttribute(
      'aria-valuenow',
      '36',
    );
  });

  it('resizes from the keyboard by 5, and by 10 with Shift, within its limits', async () => {
    mockCluster();
    mockPlan();
    const user = userEvent.setup();
    renderWithProviders(<SqlConsoleView />);
    await screen.findByRole('separator', { name: 'Resize the editor and the results' });

    handle().focus();
    await user.keyboard('{ArrowDown}');
    expect(handle()).toHaveAttribute('aria-valuenow', '41');
    expect(JSON.parse(window.localStorage.getItem('as:sql:split')!)).toEqual([41, 59]);
    await user.keyboard('{Shift>}{ArrowDown}{/Shift}');
    expect(handle()).toHaveAttribute('aria-valuenow', '51');

    // The smallest editor is the share its floor needs of the measured workspace, never under 20.
    await user.keyboard('{Home}');
    const smallest = Number(handle().getAttribute('aria-valuenow'));
    expect(smallest).toBeGreaterThanOrEqual(20);
    await user.keyboard('{ArrowUp}');
    expect(Number(handle().getAttribute('aria-valuenow'))).toBe(smallest);
    await user.keyboard('{End}');
    expect(Number(handle().getAttribute('aria-valuenow'))).toBeLessThanOrEqual(75);
  });
});

describe('SqlConsoleView other panels', () => {
  beforeEach(() => {
    window.localStorage.clear();
    navigate.mockReset();
  });

  it('loads an example from the syntax help into the editor, and closes the help', async () => {
    mockCluster();
    mockPlan();
    const user = userEvent.setup();
    renderWithProviders(<SqlConsoleView />);

    await user.click(await screen.findByRole('button', { name: 'Syntax and examples' }));
    const dialog = await screen.findByRole('dialog');
    await user.click(within(dialog).getAllByRole('button', { name: 'Load into editor' })[0]);

    await vi.waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
    expect(screen.getByRole('textbox', { name: 'Query' }).textContent).not.toContain(
      'ORDER BY timestamp DESC\nLIMIT 100',
    );
  });

  it('opens a result row in the message detail, and closes it again', async () => {
    mockCluster();
    mockPlan();
    server.use(
      http.get('*/api/v1/clusters/c1/queues/ORDER.IN/messages/11', () =>
        HttpResponse.json({ title: 'Message gone', detail: 'It was consumed.' }, { status: 404 }),
      ),
    );
    const user = userEvent.setup();
    renderWithProviders(<SqlConsoleView />);
    await run(user);
    emit('row', messageRow(11, 'order 4471'));
    emit('done', doneFrame());

    await user.click(await screen.findByText('order 4471'));

    const dialog = await screen.findByRole('dialog');
    await user.keyboard('{Escape}');
    await vi.waitFor(() => expect(dialog).not.toBeInTheDocument());
  });
});

describe('SqlConsoleView cancelling, Escape and the cost line', () => {
  beforeEach(() => {
    window.localStorage.clear();
    navigate.mockReset();
  });

  const STARTER = 'SELECT *\nFROM "ORDER.IN"\nORDER BY timestamp DESC\nLIMIT 100';

  it('cancels a running query from its button, closes the stream and says so', async () => {
    mockCluster();
    mockPlan();
    const user = userEvent.setup();
    renderWithProviders(<SqlConsoleView />);
    // Run and Cancel sit side by side, and there is nothing to cancel until a query runs.
    expect(await screen.findByRole('button', { name: 'Cancel' })).toBeDisabled();
    await run(user);
    emit('row', messageRow(11));

    await user.click(screen.getByRole('button', { name: 'Cancel' }));

    await vi.waitFor(() => expect(EventSourceStub.instances[0].readyState).toBe(2));
    expect(await screen.findByText('Query cancelled')).toBeInTheDocument();
    expect(await screen.findAllByText(/Query cancelled\. 1 row had arrived and are kept\./)).not.toHaveLength(0);
    expect(screen.getByRole('button', { name: 'Run' })).toBeEnabled();
    expect(screen.getByRole('button', { name: 'Cancel' })).toBeDisabled();
  });

  it('cancels from the editor with Mod+. and from the page with Mod+.', async () => {
    mockCluster();
    mockPlan();
    const user = userEvent.setup();
    renderWithProviders(<SqlConsoleView />);
    await run(user);
    await user.click(screen.getByRole('textbox', { name: 'Query' }));

    await user.keyboard('{Control>}.{/Control}');
    await vi.waitFor(() => expect(EventSourceStub.instances[0].readyState).toBe(2));
    expect(await screen.findByText('Query cancelled')).toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: 'Run' }));
    await vi.waitFor(() => expect(EventSourceStub.instances).toHaveLength(2));
    await user.click(document.body);
    await user.keyboard('{Control>}.{/Control}');
    await vi.waitFor(() => expect(EventSourceStub.instances[1].readyState).toBe(2));
  });

  it('never cancels on Escape: it leaves the editor for the Query toolbar, and the query keeps reading', async () => {
    mockCluster();
    mockPlan();
    const user = userEvent.setup();
    renderWithProviders(<SqlConsoleView />);
    await run(user);
    await user.click(screen.getByRole('textbox', { name: 'Query' }));

    await user.keyboard('{Escape}');

    // Run is busy while the query runs, so the first control that can take focus is Cancel.
    expect(screen.getByRole('button', { name: 'Cancel' })).toHaveFocus();
    expect(EventSourceStub.instances[0].readyState).toBe(1);
    expect(screen.queryByText('Query cancelled')).not.toBeInTheDocument();
  });

  it('collapses a selection on the first Escape and only the next one leaves the editor', async () => {
    mockCluster();
    mockPlan();
    const user = userEvent.setup();
    renderWithProviders(<SqlConsoleView />);
    const editor = await screen.findByRole('textbox', { name: 'Query' });
    await user.click(editor);
    await user.keyboard('{Control>}a{/Control}');

    await user.keyboard('{Escape}');
    expect(editor).toHaveFocus();
    expect(window.getSelection()?.isCollapsed).toBe(true);

    await user.keyboard('{Escape}');
    expect(screen.getByRole('button', { name: 'Run' })).toHaveFocus();
  });

  it('stops a tail from its Cancel button and takes it out of the address', async () => {
    mockCluster();
    mockPlan();
    const user = userEvent.setup();
    renderWithProviders(<SqlConsoleView />);
    await user.click(await screen.findByRole('switch', { name: /live tail/i }));
    await run(user, 'Run and tail');
    emit('done', doneFrame());
    await screen.findByRole('button', { name: /stop tailing/i });

    await user.click(screen.getByRole('button', { name: 'Cancel' }));

    await vi.waitFor(() => expect(EventSourceStub.instances[0].readyState).toBe(2));
    expect(writtenSearch()).toEqual({ q: expect.stringContaining('SELECT') });
    expect(screen.queryByText('Query cancelled')).not.toBeInTheDocument();
  });

  it('describes the editor and Run by the cost line, which is not a live region', async () => {
    mockCluster();
    mockPlan();
    renderWithProviders(<SqlConsoleView />);

    const cost = await screen.findByText('The brokers filter; Rahdar examines at most 100 messages from 1 queue.');
    const line = cost.closest('[id]') as HTMLElement;
    expect(screen.getByRole('textbox', { name: 'Query' })).toHaveAttribute('aria-describedby', line.id);
    expect(screen.getByRole('button', { name: 'Run' })).toHaveAttribute('aria-describedby', line.id);
    expect(line.closest('[aria-live], [role="status"], [role="alert"], output')).toBeNull();
  });

  it('says an unavailable estimate is unavailable, while the text is edited and when it is empty', async () => {
    mockCluster();
    mockPlan();
    const user = userEvent.setup();
    renderWithProviders(<SqlConsoleView />);
    await screen.findByText('The brokers filter; Rahdar examines at most 100 messages from 1 queue.');

    await user.click(screen.getByRole('textbox', { name: 'Query' }));
    await user.keyboard('{Control>}a{/Control}{Backspace}');

    expect(await screen.findByText('Not estimated: the editor is empty.')).toBeInTheDocument();
  });

  it('records the SQL that ran in the history, once, even when the editor changed while it read', async () => {
    mockCluster();
    mockPlan();
    const user = userEvent.setup();
    renderWithProviders(<SqlConsoleView />);
    await run(user);

    // Change the text while the query reads.
    await user.click(screen.getByRole('button', { name: 'Syntax and examples' }));
    const dialog = await screen.findByRole('dialog');
    await user.click(within(dialog).getAllByRole('button', { name: 'Load into editor' })[1]);
    await vi.waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());

    emit('row', messageRow(11));
    emit('done', doneFrame());

    await vi.waitFor(() => {
      const stored = JSON.parse(window.localStorage.getItem('rahdar.sql.history') ?? '[]');
      expect(stored.map((entry: { sql: string }) => entry.sql)).toEqual([STARTER]);
    });
  });

  it('names its scrolling regions, and puts them in the tab order', async () => {
    mockCluster();
    mockPlan();
    renderWithProviders(<SqlConsoleView />);

    expect(await screen.findByRole('region', { name: 'Query and cost' })).toHaveAttribute('tabindex', '0');
    expect(screen.getByRole('region', { name: 'Results' })).toHaveAttribute('tabindex', '0');
  });
});
