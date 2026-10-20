import { afterAll, afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';
import { onlineManager, QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { act, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { page as browserPage } from 'vitest/browser';
import {
  createMemoryHistory,
  createRootRoute,
  createRoute,
  createRouter,
  RouterProvider,
} from '@tanstack/react-router';

import { clusterKey } from '../../kernel/api/request.ts';
import { accessKeys, keys as authKeys } from '../../kernel/auth/api.ts';
import { FeatureProvider } from '../../kernel/FeatureProvider.tsx';
import { manifestKey } from '../../kernel/manifest.ts';
import { accessFor } from '../../test/accessSummary.ts';
import { renderApp } from '../../test/appShell.tsx';
import { manifestView } from '../../test/manifest.ts';
import { axeViolations, contentWidth, renderThemed, SCHEMES, settle, type Scheme } from '../../test/browser.tsx';
import { keys } from './api.ts';
import { SqlConsoleView } from './SqlConsoleView.tsx';

/**
 * The SQL Console in a real browser at the narrowest window the console is designed for, in both colour
 * schemes: in every state of the editor and of a run, no accessibility violations (the editor's and the
 * results' scroll areas included), one h1, nothing scrolling sideways, and the split the operator
 * resizes. There is no network: the plan is seeded in the cache, and the stream is a stub that a test
 * feeds frame by frame.
 */
beforeAll(() => onlineManager.setOnline(false));
afterAll(() => onlineManager.setOnline(true));

const AVAILABLE = { status: 'AVAILABLE', reason: 'ok', brokerXmlSnippet: null };
const QUERY = 'SELECT *\nFROM "ORDER.IN"\nORDER BY timestamp DESC\nLIMIT 100';
const SCAN = 'SELECT * FROM "ORDER.IN" WHERE body LIKE \'%4471%\'';
const BAD = 'SELECT * FROM "ORDER.IN" JOIN "ORDER.OUT"';

const target = (nodeId: string, nodeName: string) => ({
  nodeId,
  nodeName,
  subscription: 'artemis.queue/ORDER.IN',
  destination: 'artemis.address/ORDER.IN',
  messageCount: 1200,
});

const plan = (over: Record<string, unknown> = {}) => ({
  source: 'BROKER',
  targets: [target('n1', 'broker-1'), target('n2', 'broker-2')],
  selector: null,
  requiresScan: false,
  captured: false,
  pushedDown: ['priority > 4'],
  scanned: [],
  estimatedMessagesExamined: 0,
  effectiveLimit: 100,
  notices: [{ kind: 'CLOCK_OFFSET_UNKNOWN' }],
  ...over,
});

const row = (messageId: number, source = 'BROKER') => ({
  nodeId: `n${(messageId % 2) + 1}`,
  nodeName: `broker-${(messageId % 2) + 1}`,
  subscription: 'artemis.queue/ORDER.IN',
  destination: 'artemis.address/ORDER.IN',
  messageId,
  messageType: 3,
  durable: true,
  priority: 4,
  timestamp: 1757000000000 + messageId * 1000,
  expiration: 0,
  size: 64,
  body: `{"order":${messageId},"status":"created"}`,
  bodyTruncated: false,
  properties: {},
  source,
  observedAt: null,
  lastSeenAt: null,
});

const node = (nodeId: string, nodeName: string, over: Record<string, unknown> = {}) => ({
  nodeId,
  nodeName,
  subscription: 'artemis.queue/ORDER.IN',
  status: 'ANSWERED',
  examined: 500,
  matched: 1,
  detail: null,
  ...over,
});

const done = (over: Record<string, unknown> = {}) => ({
  nodes: [node('n1', 'broker-1'), node('n2', 'broker-2')],
  boundsReached: [{ kind: 'ROW_LIMIT', value: 100 }],
  notices: [{ kind: 'BODY_TRUNCATED' }],
  partial: false,
  plan: plan(),
  ...over,
});

/** A stream the test feeds: `emit` delivers a named frame to every stream the console has opened. */
class StreamStub {
  static instances: StreamStub[] = [];
  readyState = 1;
  onerror: (() => void) | null = null;
  private readonly listeners = new Map<string, ((e: MessageEvent) => void)[]>();
  constructor() {
    StreamStub.instances.push(this);
  }
  addEventListener(type: string, listener: (e: MessageEvent) => void) {
    this.listeners.set(type, [...(this.listeners.get(type) ?? []), listener]);
  }
  close() {
    this.readyState = 2;
  }
  static emit(type: string, data: unknown) {
    for (const stream of StreamStub.instances) {
      const event = { data: JSON.stringify(data) } as MessageEvent;
      stream.listeners.get(type)?.forEach((listener) => listener(event));
    }
  }
}

beforeEach(() => {
  StreamStub.instances = [];
  vi.stubGlobal('EventSource', StreamStub);
  vi.stubGlobal('fetch', async (input: RequestInfo | URL) => {
    const url = String(input instanceof Request ? input.url : input);
    // The one plan the server rejects: a syntax error, with the token it is about.
    if (url.endsWith('/sql/plan')) {
      return Response.json(
        {
          title: 'That is not the console’s dialect',
          detail: 'JOIN is not part of this dialect.',
          offending: 'JOIN',
          suggestion: null,
        },
        { status: 400 },
      );
    }
    if (url.endsWith('/sql/query')) return Response.json({ queryId: 'q-1', expiresAt: '2026-09-14T10:00:00Z' });
    throw new TypeError('Failed to fetch');
  });
});
afterEach(() => vi.unstubAllGlobals());

const emit = (type: string, data: unknown) => act(() => StreamStub.emit(type, data));

function seeded(permissions: string[]): QueryClient {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false, staleTime: Infinity, gcTime: Infinity } },
  });
  const grants = [{ scopeType: 'GLOBAL', scopeId: null, permissions }];
  client.setQueryData(authKeys.me, { id: 'u1', username: 'admin', mustChangePassword: false, grants });
  client.setQueryData(accessKeys.of(), accessFor(grants));
  client.setQueryData(accessKeys.of('c1'), accessFor(grants, 'c1'));
  client.setQueryData(clusterKey('c1'), {
    id: 'c1',
    name: 'prod',
    description: null,
    topology: { clusterId: 'c1', nodes: [] },
    capabilities: {
      managementRead: AVAILABLE,
      managementWrite: AVAILABLE,
      notifications: { status: 'UNKNOWN', reason: 'n/a', brokerXmlSnippet: null },
      messageIo: AVAILABLE,
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
  });
  client.setQueryData(keys.sqlPlan('c1', QUERY), plan());
  client.setQueryData(
    keys.sqlPlan('c1', SCAN),
    plan({ requiresScan: true, pushedDown: [], scanned: ["body LIKE '%4471%'"], estimatedMessagesExamined: 2_400_000 }),
  );
  return client;
}

function page(q: string) {
  const root = createRootRoute();
  const route = createRoute({
    getParentRoute: () => root,
    path: '/clusters/$clusterId/sql',
    component: SqlConsoleView,
  });
  return createRouter({
    routeTree: root.addChildren([route]),
    history: createMemoryHistory({ initialEntries: [`/clusters/c1/sql?q=${encodeURIComponent(q)}`] }),
  });
}

/** The page in the window's content box, a window tall, as the shell lays it out. */
function mount(scheme: Scheme, client: QueryClient, q = QUERY, height = 900) {
  return renderThemed(
    <QueryClientProvider client={client}>
      <FeatureProvider features={[]}>
        <div style={{ inlineSize: contentWidth(1280), blockSize: height, display: 'flex', flexDirection: 'column' }}>
          <RouterProvider router={page(q)} />
        </div>
      </FeatureProvider>
    </QueryClientProvider>,
    scheme,
  );
}

async function settled(container: HTMLElement) {
  await screen.findByRole('heading', { level: 1, name: 'SQL Console' });
  await settle(
    () => `${container.querySelectorAll('[role="row"], [role="gridcell"]').length}:${container.scrollHeight}`,
  );
}

async function check(container: HTMLElement) {
  expect(screen.getAllByRole('heading', { level: 1 })).toHaveLength(1);
  expect(await axeViolations(container)).toEqual([]);
  expect(container.scrollWidth).toBeLessThanOrEqual(container.clientWidth + 1);
}

/** Run the query and wait for its stream to be opened. */
async function run(name = 'Run') {
  await userEvent.click(await screen.findByRole('button', { name }));
  await vi.waitFor(() => expect(StreamStub.instances).toHaveLength(1));
}

const ADMIN = ['*'];

describe.each(SCHEMES)('SQL Console in the %s scheme at 1280 px', (scheme) => {
  beforeEach(() => localStorage.clear());

  describe('before a run', () => {
    it('states the cost of a query the brokers filter, with nothing wrong', async () => {
      const { container } = mount(scheme, seeded(ADMIN));
      await settled(container);
      expect(await screen.findByText(/The brokers filter; Rahdar examines at most 100 messages/)).toBeVisible();
      await check(container);
    });

    it('warns of a scan, with the figure', async () => {
      const { container } = mount(scheme, seeded(ADMIN), SCAN);
      await settled(container);
      expect(
        await screen.findByText('Rahdar reads and examines about 2,400,000 messages on 1 subscription across 2 nodes.'),
      ).toBeVisible();
      await check(container);
    });

    it('marks a syntax error on its token and says so in words', async () => {
      onlineManager.setOnline(true);
      try {
        const { container } = mount(scheme, seeded(ADMIN), BAD);
        await settled(container);
        expect(await screen.findByText('Not estimated: JOIN is not part of this dialect.')).toBeVisible();
        await vi.waitFor(() => expect(document.querySelector('.cm-lintRange-error')?.textContent).toBe('JOIN'));
        await check(container);
      } finally {
        onlineManager.setOnline(false);
      }
    });

    it('says why it cannot estimate or run when messages cannot be read', async () => {
      const { container } = mount(scheme, seeded(['subscription:read']));
      await settled(container);
      expect(await screen.findByText('This console cannot read messages here')).toBeVisible();
      expect(screen.getByRole('button', { name: 'Run' })).toBeDisabled();
      await check(container);
    });
  });

  describe('during and after a run', () => {
    const states: [string, () => Promise<void> | void][] = [
      [
        'running, with rows so far',
        () => {
          emit('node', node('n1', 'broker-1'));
          [1, 2, 3].forEach((id) => emit('row', row(id)));
        },
      ],
      [
        'done, with rows, bounds and notices',
        () => {
          [1, 2, 3, 4].forEach((id) => emit('row', row(id, id % 2 ? 'INDEX' : 'BROKER')));
          emit('done', done());
        },
      ],
      ['done, with no subscription matched', () => emit('done', done({ nodes: [], plan: plan({ targets: [] }) }))],
      [
        'done, with a node that did not answer',
        () => emit('done', done({ nodes: [node('n1', 'broker-1', { status: 'FAILED', detail: 'timed out' })] })),
      ],
      [
        'tailing',
        () => {
          emit('row', row(1));
          emit('done', done());
          emit('tail', {
            enqueued: 40,
            shown: 12,
            polls: 3,
            lastPollAt: '2026-09-14T10:00:00Z',
            everyMessageMatches: true,
          });
        },
      ],
      [
        'refused, with its estimate',
        () =>
          emit('failed', {
            status: 422,
            title: 'Query refused',
            detail: 'The query would examine too many messages.',
            estimate: 2_400_000,
            ceiling: 100_000,
            hint: 'Add a predicate on a header or a property.',
          }),
      ],
      ['disconnected', () => act(() => StreamStub.instances[0].onerror?.())],
    ];

    it.each(states)('has no violations when %s', async (_name, feed) => {
      const { container } = mount(scheme, seeded(ADMIN));
      await settled(container);
      const tailing = _name === 'tailing';
      if (tailing) await userEvent.click(await screen.findByRole('switch', { name: 'Live tail' }));
      await run(tailing ? 'Run and tail' : 'Run');
      await feed();
      await settled(container);
      await check(container);
    });

    it('says a cancelled query is cancelled, keeps its rows and releases the stream', async () => {
      const { container } = mount(scheme, seeded(ADMIN));
      await settled(container);
      await run();
      [1, 2].forEach((id) => emit('row', row(id)));

      await userEvent.click(screen.getByRole('button', { name: 'Cancel' }));

      expect(await screen.findByText('Query cancelled')).toBeVisible();
      expect(StreamStub.instances[0].readyState).toBe(2);
      await settled(container);
      await check(container);
    });
  });

  describe('the table’s Columns menu', () => {
    it('moves a column from the keyboard and keeps focus on its button', async () => {
      const { container } = mount(scheme, seeded(ADMIN));
      await settled(container);
      await run();
      emit('row', row(1));
      emit('done', done());
      await userEvent.click(await screen.findByRole('button', { name: /^Columns/ }));

      const earlier = await screen.findByRole('button', { name: 'Move Source earlier' });
      earlier.focus();
      await userEvent.keyboard('{Enter}');

      expect(screen.getByRole('button', { name: 'Move Source earlier' })).toHaveFocus();
      expect(await screen.findByText('Source moved to position 3 of 9')).toBeInTheDocument();
      // The menu fades in; a contrast reading mid-fade is not one.
      const menu = screen.getByRole('button', { name: 'Reset widths' }).closest('[role="dialog"]')!;
      await settle(() => getComputedStyle(menu).opacity);
      expect(await axeViolations(document.body)).toEqual([]);
    });
  });

  describe('a pane too short for everything in it', () => {
    // 200% zoom of a 1280 x 800 window leaves 400 px: the editor pane cannot hold the toolbar, the editor, the
    // hint and the cost line at once.
    const SHORT = 400;
    const edges = () => {
      const editor = document.querySelector('.cm-editor')!.getBoundingClientRect();
      const hint = screen.getByText(/Ctrl-Enter runs/).getBoundingClientRect();
      return { editor, hint };
    };

    it('keeps the editor its height instead of squeezing it under the lines that follow', async () => {
      const { container } = mount(scheme, seeded(ADMIN), QUERY, SHORT);
      await settled(container);

      const { editor, hint } = edges();
      expect(editor.height).toBeGreaterThanOrEqual(4.5 * 16);
      expect(editor.bottom).toBeLessThanOrEqual(hint.top);
    });

    it('moves nothing above the cost line when the estimate arrives with notices', async () => {
      const client = seeded(ADMIN);
      const { container } = mount(scheme, client, QUERY, SHORT);
      await settled(container);
      const before = edges();

      act(() =>
        client.setQueryData(
          keys.sqlPlan('c1', QUERY),
          plan({ notices: [{ kind: 'CLOCK_OFFSET_UNKNOWN' }, { kind: 'BODY_TRUNCATED' }, { kind: 'TARGET_CAPPED' }] }),
        ),
      );
      await settle(() => String(container.scrollHeight));

      const after = edges();
      expect(after.editor.top).toBe(before.editor.top);
      expect(after.editor.height).toBe(before.editor.height);
      expect(after.hint.top).toBe(before.hint.top);
    });
  });

  describe('the split', () => {
    it('is a separator the keyboard resizes, and the page does not scroll sideways', async () => {
      const { container } = mount(scheme, seeded(ADMIN));
      await settled(container);
      const separator = await screen.findByRole('separator', { name: 'Resize the editor and the results' });
      const before = Number(separator.getAttribute('aria-valuenow'));

      separator.focus();
      await userEvent.keyboard('{ArrowDown}');

      expect(Number(separator.getAttribute('aria-valuenow'))).toBe(before + 5);
      expect(JSON.parse(localStorage.getItem('as:sql:split')!)).toEqual([before + 5, 100 - before - 5]);
      await check(container);
    });

    it('keeps Run, Cancel and the cost line in view at the smallest editor', async () => {
      const { container } = mount(scheme, seeded(ADMIN), QUERY, 700);
      await settled(container);
      const separator = await screen.findByRole('separator', { name: 'Resize the editor and the results' });
      separator.focus();
      await userEvent.keyboard('{Home}');

      const pane = screen.getByRole('region', { name: 'Query and cost' }).getBoundingClientRect();
      const inPane = (el: Element) => {
        const box = el.getBoundingClientRect();
        return box.top >= pane.top - 1 && box.bottom <= pane.bottom + 1;
      };
      const inWindow = (el: Element) => {
        const box = el.getBoundingClientRect();
        return box.top >= 0 && box.bottom <= window.innerHeight;
      };
      expect(inWindow(screen.getByRole('button', { name: 'Run' }))).toBe(true);
      expect(inWindow(screen.getByRole('button', { name: 'Cancel' }))).toBe(true);
      const cost = document.getElementById(
        screen.getByRole('button', { name: 'Run' }).getAttribute('aria-describedby')!,
      )!;
      expect(inPane(cost)).toBe(true);
    });

    it('lets both panes scroll by keyboard, so neither is a trap and neither is out of reach', async () => {
      const { container } = mount(scheme, seeded(ADMIN));
      await settled(container);
      for (const name of ['Query and cost', 'Results']) {
        const region = screen.getByRole('region', { name });
        expect(region.tabIndex).toBe(0);
        expect(getComputedStyle(region).overflowY).toBe('auto');
      }
    });
  });
});

describe.each(SCHEMES)('SQL Console in the application shell at 1280 x 800 in the %s scheme', (scheme) => {
  beforeAll(() => browserPage.viewport(1280, 800));
  afterAll(() => browserPage.viewport(1920, 1080));
  beforeEach(() => localStorage.clear());

  /** The console in the real shell, run, with 500 rows delivered. */
  async function openWithRows() {
    const client = seeded(ADMIN);
    client.setQueryData(manifestKey, manifestView());
    const { container } = renderApp(`/clusters/c1/sql?q=${encodeURIComponent(QUERY)}`, client, scheme);
    await screen.findByRole('heading', { level: 1, name: 'SQL Console' });
    await userEvent.click(await screen.findByRole('button', { name: 'Run' }));
    await vi.waitFor(() => expect(StreamStub.instances.length).toBeGreaterThan(0));
    act(() => {
      for (let id = 1; id <= 500; id++) StreamStub.emit('row', row(id));
      StreamStub.emit('done', done());
    });
    const grid = await screen.findByRole('grid', { name: 'Query results' });
    await settle(() => `${grid.querySelectorAll('[role="row"]').length}:${document.documentElement.scrollHeight}`);
    return { container, grid, scroller: grid.parentElement! };
  }

  it('fills the window below the header: the page does not scroll and the grid scrolls inside its pane', async () => {
    const { grid, scroller } = await openWithRows();

    const page = document.documentElement;
    expect(page.scrollHeight).toBeLessThanOrEqual(window.innerHeight);
    expect(scroller.scrollHeight).toBeGreaterThan(scroller.clientHeight);
    // The header stays at the top of the grid's pane while the rows scroll under it.
    const header = within(grid).getAllByRole('columnheader')[0];
    scroller.scrollTop = scroller.scrollHeight;
    await settle(() => String(scroller.scrollTop));
    expect(header.getBoundingClientRect().top).toBeGreaterThanOrEqual(scroller.getBoundingClientRect().top - 1);
    expect(header.getBoundingClientRect().bottom).toBeLessThanOrEqual(scroller.getBoundingClientRect().bottom);
    // The last row is reachable inside the pane, and the page has not moved.
    const rows = within(grid).getAllByRole('row');
    const last = rows[rows.length - 1];
    expect(last.textContent).toContain('500');
    expect(last.getBoundingClientRect().bottom).toBeLessThanOrEqual(scroller.getBoundingClientRect().bottom + 1);
    expect(page.scrollHeight).toBeLessThanOrEqual(window.innerHeight);
    expect(window.scrollY).toBe(0);
  });

  it('maximises the results from the button and the keyboard, and Escape restores the editor and the split', async () => {
    await openWithRows();
    const separator = screen.getByRole('separator', { name: 'Resize the editor and the results' });
    // The editor's pane, which the split sizes: what is inside it keeps its own height and is clipped.
    const editor = () =>
      screen.getByRole('region', { name: 'Query and cost', hidden: true }).parentElement!.getBoundingClientRect()
        .height;
    const results = () => screen.getByRole('region', { name: 'Results' }).getBoundingClientRect().height;
    const before = { split: separator.getAttribute('aria-valuenow'), editor: editor(), results: results() };

    await userEvent.click(screen.getByRole('button', { name: 'Maximise the results' }));
    await settle(() => String(results()));
    expect(editor()).toBe(0);
    expect(results()).toBeGreaterThan(before.results);
    expect(screen.queryByRole('button', { name: 'Maximise the results' })).toBeNull();

    await userEvent.keyboard('{Escape}');
    await settle(() => String(results()));
    expect(separator.getAttribute('aria-valuenow')).toBe(before.split);
    expect(editor()).toBeCloseTo(before.editor, 0);
    expect(results()).toBeCloseTo(before.results, 0);

    // The same key from the page, then Escape again.
    await userEvent.keyboard('{Control>}{Shift>}m{/Shift}{/Control}');
    await settle(() => String(results()));
    expect(editor()).toBe(0);
    await userEvent.click(screen.getByRole('button', { name: 'Restore the editor' }));
    await settle(() => String(results()));
    expect(editor()).toBeCloseTo(before.editor, 0);
  });

  it('gives the results the larger share once rows exist, unless the operator chose a split', async () => {
    await openWithRows();
    const separator = screen.getByRole('separator', { name: 'Resize the editor and the results' });

    expect(Number(separator.getAttribute('aria-valuenow'))).toBeLessThanOrEqual(50);
    // The console's own move is not the operator's choice, so it is not remembered.
    expect(localStorage.getItem('as:sql:split')).toBeNull();
  });
});
