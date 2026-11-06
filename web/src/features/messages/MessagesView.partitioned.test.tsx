import { afterEach, describe, expect, it, vi } from 'vitest';
import { http, HttpResponse } from 'msw';
import { act, cleanup, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { holdButton } from '../../test/hold.ts';
import { notifications } from '@mantine/notifications';

import { renderWithProviders } from '../../test/render.tsx';
import { server } from '../../test/setup.ts';
import { KAFKA_KINDS, KAFKA_SYSTEM } from '../../test/wire.ts';
import { browseAddress, formatResume, messageQuery, orderLogRecords } from './partitionBrowse.ts';

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
    useParams: () => ({ clusterId: 'k1', subscriptionName: 'orders' }),
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

const { MessagesView, isPartitionedLog } = await import('./MessagesView.tsx');

const AVAILABLE = { status: 'AVAILABLE', reason: 'ok', brokerXmlSnippet: null };

function record(offset: number, extra: Record<string, unknown> = {}) {
  return {
    position: String(offset),
    partition: 0,
    type: 0,
    durable: true,
    priority: 0,
    timestamp: 1_790_000_000_000,
    expiration: 0,
    size: 12,
    bodyPreview: `value-${offset}`,
    bodyTruncated: false,
    propertyCount: 0,
    redactions: [],
    tombstone: false,
    ...extra,
  };
}

/** A merged page has no single partition and no single next offset. */
function mergedLog(extra: Record<string, unknown> = {}) {
  return {
    partition: null,
    start: 0,
    end: 0,
    next: null,
    retentionMoved: false,
    uncommittedHidden: true,
    subscriptionPosition: null,
    resume: null,
    ...extra,
  };
}

/** One partition, with its own start and end. */
function focusedLog(partition: number, extra: Record<string, unknown> = {}) {
  return {
    partition,
    start: 0,
    end: 900,
    next: null,
    retentionMoved: false,
    uncommittedHidden: true,
    resume: null,
    ...extra,
  };
}

/** The data columns, in the order the grid announces them. */
function dataHeaders(): string[] {
  const wanted = ['Partition', 'Offset', 'Written', 'Key', 'Size', 'Body'];
  return screen
    .getAllByRole('columnheader')
    .map((header) => (header.textContent ?? '').replace(/\s+/g, ' ').trim())
    .filter((name) => wanted.includes(name));
}

/** A Kafka cluster whose browse answers with `answer(url)`; every request is recorded. */
function mockKafka(answer: (url: URL) => object) {
  const seen: URL[] = [];
  server.use(
    http.get('*/api/v1/auth/me', () =>
      HttpResponse.json({
        id: 'u1',
        username: 'op',
        mustChangePassword: false,
        grants: [{ scopeType: 'GLOBAL', scopeId: null, permissions: ['*'] }],
      }),
    ),
    http.get('*/api/v1/clusters/k1', () =>
      HttpResponse.json({
        id: 'k1',
        name: 'events',
        description: null,
        system: KAFKA_SYSTEM,
        topology: { clusterId: 'k1', nodes: [] },
        kinds: KAFKA_KINDS,
        capabilities: {
          managementRead: AVAILABLE,
          managementWrite: AVAILABLE,
          notifications: AVAILABLE,
          messageIo: AVAILABLE,
        },
        health: {
          clusterId: 'k1',
          level: 'OK',
          liveEndpointNames: [],
          splitBrain: 'NONE',
          replicationBehind: false,
          notes: [],
        },
      }),
    ),
    http.get('*/api/v1/clusters/k1/resources/kafka.group-topic/orders/messages', ({ request }) => {
      const url = new URL(request.url);
      seen.push(url);
      return HttpResponse.json(answer(url));
    }),
  );
  return seen;
}

const page = (data: object[], log: object | null = null) => ({
  data,
  count: data.length,
  page: 1,
  pageSize: 200,
  hasNext: false,
  node: 'n1',
  log,
});

describe('isPartitionedLog', () => {
  it('knows a Kafka group on a topic is a log before the cluster kinds have loaded', () => {
    expect(isPartitionedLog('kafka.group-topic', undefined)).toBe(true);
    expect(isPartitionedLog('artemis.queue', undefined)).toBe(false);
    expect(isPartitionedLog('kafka.group-topic', [{ id: 'kafka.group-topic', partitioned: false }])).toBe(false);
  });
});

describe('MessagesView on a partitioned log', () => {
  it('default browse that is not locked to a single partition; rows from at least two partitions in one result; a Partition column next to Offset plus Written, Key, Size, and Body; requested page size still 200; tombstone text, the disabled selector reason, and the committed-only notice still present', async () => {
    const seen = mockKafka(() =>
      page(
        [
          record(10, { partition: 2, bodyPreview: 'value-0' }),
          record(11, { partition: 7, tombstone: true, bodyPreview: null }),
        ],
        mergedLog(),
      ),
    );
    renderWithProviders(<MessagesView />);

    expect(await screen.findByText('value-0')).toBeInTheDocument();
    expect(screen.getByText('Tombstone (no value)')).toBeInTheDocument();
    expect(seen[0].searchParams.has('partition')).toBe(false);
    expect(seen[0].searchParams.get('from')).toBe('earliest');
    expect(seen[0].searchParams.get('size')).toBe('200');
    expect(seen[0].searchParams.has('filter')).toBe(false);
    expect(seen[0].searchParams.has('at')).toBe(false);
    expect(dataHeaders()).toEqual(['Partition', 'Offset', 'Written', 'Key', 'Size', 'Body']);
    const zero = screen.getByText('value-0').closest('[role="row"]') as HTMLElement;
    const one = screen.getByText('Tombstone (no value)').closest('[role="row"]') as HTMLElement;
    expect(within(zero).getByRole('rowheader', { name: '2' })).toBeInTheDocument();
    expect(within(zero).getByRole('gridcell', { name: '10' })).toBeInTheDocument();
    expect(within(one).getByRole('rowheader', { name: '7' })).toBeInTheDocument();
    expect(within(one).getByRole('gridcell', { name: '11' })).toBeInTheDocument();
    expect(screen.getByRole('textbox', { name: 'Message selector' })).toBeDisabled();
    expect(screen.getByRole('textbox', { name: 'Message selector' })).toHaveAccessibleDescription(
      /cannot select records on the broker/,
    );
    expect(screen.queryByText(/^page 1/)).not.toBeInTheDocument();
    expect(screen.getByText(/Only committed records are shown/)).toBeInTheDocument();
    expect(screen.getByText(/Ordered by timestamp, then partition, then offset/)).toBeInTheDocument();
    expect(screen.getByText(/at most 200 records/)).toBeInTheDocument();
  });

  it('order timestamp, then partition, then offset', async () => {
    const rows = [
      record(5, { partition: 1, timestamp: 200, bodyPreview: 'rec-mid' }),
      record(9, { partition: 0, timestamp: 150, bodyPreview: 'rec-second' }),
      record(1, { partition: 1, timestamp: 150, bodyPreview: 'rec-other' }),
      record(3, { partition: 0, timestamp: 150, bodyPreview: 'rec-first' }),
      record(10, { partition: 0, timestamp: 100, bodyPreview: 'rec-early' }),
      record(6, { partition: 1, timestamp: null, bodyPreview: 'rec-none' }),
    ];
    mockKafka(() => page(rows, mergedLog()));
    renderWithProviders(<MessagesView />);
    expect(await screen.findByText('rec-early')).toBeInTheDocument();

    const expected = ['rec-early', 'rec-first', 'rec-second', 'rec-other', 'rec-mid', 'rec-none'];
    expect(orderLogRecords(rows).map((row) => row.bodyPreview)).toEqual(expected);
    const rendered = expected.map((body) => screen.getByText(body).closest('[role="row"]'));
    for (let i = 1; i < rendered.length; i += 1) {
      expect(rendered[i - 1]!.compareDocumentPosition(rendered[i]!) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
    }
  });

  it('a no-timestamp record identified as having none', async () => {
    mockKafka(() =>
      page(
        [
          record(1, { partition: 0, timestamp: null, bodyPreview: 'missing-time' }),
          record(2, { partition: 1, timestamp: 0, bodyPreview: 'zero-time' }),
        ],
        mergedLog(),
      ),
    );
    renderWithProviders(<MessagesView />);

    expect(await screen.findByText('missing-time')).toBeInTheDocument();
    expect(screen.getByText('zero-time')).toBeInTheDocument();
    expect(screen.getAllByText('No timestamp')).toHaveLength(2);
    expect(screen.queryByText(/1970/)).not.toBeInTheDocument();
    expect(
      orderLogRecords([
        { position: '2', partition: 1, timestamp: 100 },
        { position: '1', partition: 0, timestamp: null },
      ]).map((row) => row.position),
    ).toEqual(['2', '1']);
  });

  it('a focused partition that excludes the other partition', async () => {
    const seen = mockKafka((url) =>
      url.searchParams.get('partition') === '1'
        ? page([record(4, { partition: 1, bodyPreview: 'only-partition-1' })], focusedLog(1))
        : page(
            [
              record(1, { partition: 0, bodyPreview: 'from-partition-0' }),
              record(2, { partition: 1, bodyPreview: 'from-partition-1' }),
            ],
            mergedLog(),
          ),
    );
    const user = userEvent.setup();
    renderWithProviders(<MessagesView />);
    expect(await screen.findByText('from-partition-0')).toBeInTheDocument();
    expect(screen.getByText('from-partition-1')).toBeInTheDocument();

    await user.click(screen.getByRole('combobox', { name: 'Partitions' }));
    await user.click(await screen.findByRole('option', { name: 'One partition' }));
    const field = await screen.findByRole('textbox', { name: 'Partition' });
    await user.clear(field);
    await user.type(field, '1');
    await user.click(screen.getByRole('button', { name: 'Browse' }));

    expect(await screen.findByText('only-partition-1')).toBeInTheDocument();
    expect(screen.queryByText('from-partition-0')).not.toBeInTheDocument();
    expect(seen.at(-1)!.searchParams.get('partition')).toBe('1');
    expect(screen.getByRole('columnheader', { name: 'Partition' })).toBeInTheDocument();
    expect(screen.getByText(/^Partition 1/)).toBeInTheDocument();
  });

  it('starts earliest, latest, offset, and time accepted', async () => {
    const seen = mockKafka(() => page([record(1)], mergedLog()));
    const user = userEvent.setup();
    renderWithProviders(<MessagesView />);
    await screen.findByText('value-1');
    expect(seen[0].searchParams.get('from')).toBe('earliest');
    expect(seen[0].searchParams.has('partition')).toBe(false);

    await user.click(screen.getByRole('combobox', { name: 'Start from' }));
    await user.click(await screen.findByRole('option', { name: 'The latest records' }));
    await user.click(screen.getByRole('button', { name: 'Browse' }));
    expect(seen.at(-1)!.searchParams.get('from')).toBe('latest');
    expect(seen.at(-1)!.searchParams.has('partition')).toBe(false);

    await user.click(screen.getByRole('combobox', { name: 'Start from' }));
    await user.click(await screen.findByRole('option', { name: 'An offset' }));
    const offset = await screen.findByRole('textbox', { name: 'Offset' });
    expect(offset).toHaveAccessibleDescription(/each partition/);
    await user.type(offset, '40');
    await user.click(screen.getByRole('button', { name: 'Browse' }));
    expect(seen.at(-1)!.searchParams.get('from')).toBe('offset');
    expect(seen.at(-1)!.searchParams.get('value')).toBe('40');
    expect(seen.at(-1)!.searchParams.has('partition')).toBe(false);

    await user.click(screen.getByRole('combobox', { name: 'Start from' }));
    await user.click(await screen.findByRole('option', { name: 'A time' }));
    const time = await screen.findByRole('textbox', { name: 'Time (ISO-8601)' });
    await user.clear(time);
    await user.type(time, '2026-10-28T09:30:00Z');
    await user.click(screen.getByRole('button', { name: 'Browse' }));
    expect(seen.at(-1)!.searchParams.get('from')).toBe('time');
    expect(seen.at(-1)!.searchParams.get('value')).toBe('2026-10-28T09:30:00Z');
    expect(seen.at(-1)!.searchParams.has('partition')).toBe(false);
  });

  it('the URL search restoring all-partitions versus one partition and the start', async () => {
    expect(browseAddress({ from: 'latest' })).toEqual({ from: 'latest' });
    expect(browseAddress({ partition: 'all', from: 'earliest' }).partition).toBeUndefined();
    expect(browseAddress({ partition: null }).partition).toBeUndefined();
    expect(browseAddress({ partition: 3, from: 'time', value: '2026-10-28T09:30:00Z' })).toEqual({
      partition: 3,
      from: 'time',
      value: '2026-10-28T09:30:00Z',
    });
    expect(browseAddress({ at: '0:120,1:40' }).at).toBe('0:120,1:40');
    expect(browseAddress({ at: '0:10:11,1:40' }).at).toBe('0:10:11,1:40');
    expect(browseAddress({ at: '0:12::10' }).at).toBe('0:12::10');
    expect(browseAddress({ at: '0:13:13:10.12,1:6' }).at).toBe('0:13:13:10.12,1:6');
    expect(browseAddress({ at: '120' }).at).toBeUndefined();
    expect(
      formatResume([
        { partition: 1, offset: 6 },
        { partition: 0, offset: 10, end: 11 },
      ]),
    ).toBe('0:10:11,1:6');
    expect(
      formatResume([
        { partition: 0, offset: 12, pending: '10' },
        { partition: 1, offset: 6 },
      ]),
    ).toBe('0:12::10,1:6');
    expect(formatResume([{ partition: 0, offset: 13, end: 13, pending: '10.12' }])).toBe('0:13:13:10.12');

    searchState = { from: 'latest' };
    const seen = mockKafka(() => page([record(1)], mergedLog()));
    renderWithProviders(<MessagesView />);
    await screen.findByText('value-1');
    expect(seen[0].searchParams.get('from')).toBe('latest');
    expect(seen[0].searchParams.has('partition')).toBe(false);

    cleanup();
    searchState = { partition: 2, from: 'offset', value: '9' };
    const focused = mockKafka(() => page([record(9, { partition: 2 })], focusedLog(2, { end: 20 })));
    renderWithProviders(<MessagesView />);
    await screen.findByText('value-9');
    expect(focused[0].searchParams.get('partition')).toBe('2');
    expect(focused[0].searchParams.get('from')).toBe('offset');
    expect(focused[0].searchParams.get('value')).toBe('9');
  });

  it('opens a merged row from its menu with that row’s partition, including partition 0', async () => {
    const detailUrls: URL[] = [];
    const seen = mockKafka(() =>
      page(
        [
          record(40, { partition: 0, bodyPreview: 'on-partition-0' }),
          record(41, { partition: 3, bodyPreview: 'on-partition-3' }),
        ],
        mergedLog(),
      ),
    );
    server.use(
      http.get('*/api/v1/clusters/k1/resources/:kind/:name/messages/:position', ({ request, params }) => {
        detailUrls.push(new URL(request.url));
        return HttpResponse.json({
          position: String(params.position),
          type: 0,
          durable: true,
          priority: 0,
          timestamp: 1,
          expiration: 0,
          size: 1,
          body: 'opened',
          bodyEncoding: 'TEXT',
          bodyTruncated: false,
          stringProperties: {},
          intProperties: {},
          longProperties: {},
          doubleProperties: {},
          booleanProperties: {},
          redactions: [],
          withheld: [],
          tombstone: false,
        });
      }),
    );
    const user = userEvent.setup();
    renderWithProviders(<MessagesView />);
    await screen.findByText('on-partition-0');
    expect(seen[0].searchParams.has('partition')).toBe(false);

    await user.click(await screen.findByRole('button', { name: 'Actions for message 40' }));
    const menu = await screen.findByRole('menu', { name: 'Actions for message 40' });
    await user.click(within(menu).getByRole('menuitem', { name: 'Copy link to this message' }));
    expect(await navigator.clipboard.readText()).toContain('messagePartition=0');

    await user.click(await screen.findByRole('button', { name: 'Actions for message 40' }));
    const again = await screen.findByRole('menu', { name: 'Actions for message 40' });
    const open = within(again).getByRole('menuitem', { name: 'Open details' });
    expect(open).toHaveAttribute('href', expect.stringContaining('messagePartition=0'));
    await user.click(open);

    await waitFor(() => expect(detailUrls.length).toBeGreaterThan(0));
    expect(detailUrls.at(-1)!.pathname.endsWith('/40')).toBe(true);
    expect(detailUrls.at(-1)!.searchParams.get('partition')).toBe('0');
  });

  it('a stream browse still on its single log', () => {
    const stream = messageQuery(true, true, { partition: null, from: 'earliest' }, {}, 1, 200);
    expect(stream).toEqual({ partition: 0, from: 'earliest', value: undefined, at: undefined, size: 200 });
    const merged = messageQuery(true, false, { partition: null, from: 'earliest', at: '0:120,1:40' }, {}, 1, 200);
    expect(merged.partition).toBeUndefined();
    expect(merged.at).toBe('0:120,1:40');
    expect(merged.size).toBe(200);
    const one = messageQuery(true, false, { partition: 4, from: 'latest' }, {}, 1, 200);
    expect(one.partition).toBe(4);
    expect(one.at).toBeUndefined();
  });

  it('browses from an offset, checking the value beside its field first', async () => {
    const seen = mockKafka(() => page([record(500, { partition: 3 })], focusedLog(3)));
    const user = userEvent.setup();
    renderWithProviders(<MessagesView />);
    await screen.findByText('value-500');

    await user.click(screen.getByRole('combobox', { name: 'Partitions' }));
    await user.click(await screen.findByRole('option', { name: 'One partition' }));
    const field = await screen.findByRole('textbox', { name: 'Partition' });
    await user.clear(field);
    await user.type(field, '3');
    await user.click(screen.getByRole('combobox', { name: 'Start from' }));
    await user.click(await screen.findByRole('option', { name: 'An offset' }));
    await user.click(screen.getByRole('button', { name: 'Browse' }));
    expect(await screen.findByText('Enter the offset as a whole number, such as 120.')).toBeInTheDocument();

    await user.type(screen.getByRole('textbox', { name: 'Offset' }), '500');
    await user.click(screen.getByRole('button', { name: 'Browse' }));
    await screen.findByText('value-500');
    const last = seen.at(-1)!;
    expect(last.searchParams.get('partition')).toBe('3');
    expect(last.searchParams.get('from')).toBe('offset');
    expect(last.searchParams.get('value')).toBe('500');
  });

  it('states the end of the log when the offset is past it, and that retention moved the start', async () => {
    searchState = { partition: 0, from: 'offset', value: '5000' };
    mockKafka(() =>
      page([], { partition: 0, start: 200, end: 900, next: null, retentionMoved: true, uncommittedHidden: true }),
    );
    renderWithProviders(<MessagesView />);

    expect(await screen.findByText('That offset is past the end of the partition')).toBeInTheDocument();
    expect(screen.getByText(/The log ends at offset 900/)).toBeInTheDocument();
    expect(screen.getByText('Retention removed the start of the partition')).toBeInTheDocument();
    expect(await screen.findByText('No records from here')).toBeInTheDocument();
  });

  it('says an offset past every partition is not one shared end', async () => {
    searchState = { from: 'offset', value: '50' };
    mockKafka(() => ({
      ...page([], mergedLog()),
      notice: 'Offset 50 is past the end of every partition. Each partition ends at its own offset.',
    }));
    renderWithProviders(<MessagesView />);

    expect(await screen.findByText(/past the end of every partition/)).toBeInTheDocument();
    expect(screen.queryByText('That offset is past the end of the partition')).not.toBeInTheDocument();
    expect(await screen.findByText(/No partition has a committed record/)).toBeInTheDocument();
  });

  it("says where the group stands on the partition when it is read through the group's subscription", async () => {
    searchState = { partition: 0 };
    mockKafka(() =>
      page([record(0)], {
        partition: 0,
        start: 0,
        end: 900,
        next: null,
        retentionMoved: false,
        uncommittedHidden: true,
        subscriptionPosition: 640,
      }),
    );
    renderWithProviders(<MessagesView />);

    expect(await screen.findByText(/The group stands at offset 640 on partition 0/)).toBeInTheDocument();
  });

  it('says the group position is unavailable when the read did not report one', async () => {
    searchState = { partition: 0 };
    mockKafka(() =>
      page([record(0)], {
        partition: 0,
        start: 0,
        end: 900,
        next: null,
        retentionMoved: false,
        uncommittedHidden: true,
        subscriptionPosition: null,
      }),
    );
    renderWithProviders(<MessagesView />);

    expect(await screen.findByText(/The group's position on this partition is unavailable/)).toBeInTheDocument();
    expect(screen.queryByText(/undefined/)).toBeNull();
    expect(screen.queryByText(/stands at offset 0/)).toBeNull();
  });

  it("keeps move, retry, delete and expire visible but disabled, with the system's own words", async () => {
    mockKafka(() =>
      page([record(0)], { partition: 0, start: 0, end: 1, next: null, retentionMoved: false, uncommittedHidden: true }),
    );
    const user = userEvent.setup();
    renderWithProviders(<MessagesView />);
    await screen.findByText('value-0');

    await user.click(screen.getByRole('button', { name: 'By selector…' }));
    const menu = await screen.findByRole('menu');
    for (const [label, words] of [
      ['Move by selector', /Messages cannot be moved out of Apache Kafka groups on topics/],
      ['Delete by selector', /Messages cannot be deleted from Apache Kafka groups on topics/],
    ] as const) {
      const item = await within(menu).findByRole('menuitem', { name: new RegExp(label) });
      await vi.waitFor(() => expect(item).toHaveAttribute('aria-disabled', 'true'));
      expect(item).toHaveAccessibleDescription(words);
    }
  });

  it('offers the next records from the offset the focused partition ends at, and shows the record key', async () => {
    searchState = { partition: 0 };
    const seen = mockKafka((url) =>
      url.searchParams.get('value') === '2'
        ? page([record(2)], focusedLog(0, { end: 3 }))
        : page([{ ...record(0), key: 'order-17' }, record(1)], focusedLog(0, { end: 3, next: 2 })),
    );
    const user = userEvent.setup();
    renderWithProviders(<MessagesView />);

    expect(await screen.findByText('order-17')).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Next records in partition 0, from offset 2' }));
    expect(await screen.findByText('value-2')).toBeInTheDocument();
    const last = seen.at(-1)!;
    expect(last.searchParams.get('partition')).toBe('0');
    expect(last.searchParams.get('from')).toBe('offset');
    expect(last.searchParams.get('value')).toBe('2');
    expect(last.searchParams.has('at')).toBe(false);
    expect(screen.queryByRole('button', { name: /Next records/ })).not.toBeInTheDocument();
  });

  it('continues a merged page from each partition, not from one offset', async () => {
    const seen = mockKafka((url) =>
      url.searchParams.has('at')
        ? page([record(11, { partition: 0, bodyPreview: 'continued' })], mergedLog())
        : page(
            [record(10, { partition: 0, bodyPreview: 'kept-0' }), record(5, { partition: 1, bodyPreview: 'kept-1' })],
            mergedLog({
              resume: [
                { partition: 1, offset: 6 },
                { partition: 0, offset: 11 },
              ],
            }),
          ),
    );
    const user = userEvent.setup();
    renderWithProviders(<MessagesView />);

    expect(await screen.findByText('kept-0')).toBeInTheDocument();
    await user.click(
      screen.getByRole('button', { name: 'Next records, continuing each partition from its own offset' }),
    );
    expect(await screen.findByText('continued')).toBeInTheDocument();
    const last = seen.at(-1)!;
    expect(last.searchParams.get('at')).toBe('0:11,1:6');
    expect(last.searchParams.has('partition')).toBe(false);
    expect(last.searchParams.has('value')).toBe(false);
    expect(last.searchParams.get('size')).toBe('200');
  });

  it('purges a log by moving its position, and keeps the reason in the dialog when the run is refused', async () => {
    mockKafka(() =>
      page([record(0)], { partition: 0, start: 0, end: 1, next: null, retentionMoved: false, uncommittedHidden: true }),
    );
    const calls: boolean[] = [];
    server.use(
      http.post('*/api/v1/clusters/k1/resources/kafka.group-topic/orders\\:purge', ({ request }) => {
        const dryRun = new URL(request.url).searchParams.get('dryRun') === 'true';
        calls.push(dryRun);
        return dryRun
          ? HttpResponse.json({ affectedCount: 0, skippedCount: 1200, cap: 100000, overCap: false, dryRun: true })
          : HttpResponse.json(
              { title: 'Failed precondition', detail: 'The group began rebalancing. Try again when it is stable.' },
              { status: 409 },
            );
      }),
    );
    const user = userEvent.setup();
    renderWithProviders(<MessagesView />);
    await user.click(await screen.findByRole('button', { name: 'Purge subscription' }));

    const dialog = await screen.findByRole('dialog');
    expect(await within(dialog).findByText(/moves the position of orders to the end of its log/)).toBeInTheDocument();
    expect(within(dialog).getByText(/No record is deleted/)).toBeInTheDocument();
    await holdButton(within(dialog).getByRole('button', { name: 'Skip 1,200 records' }));

    expect(await within(dialog).findByText('The system refuses this purge right now')).toBeInTheDocument();
    expect(within(dialog).getAllByText(/began rebalancing/).length).toBeGreaterThan(0);
    expect(screen.getByRole('dialog')).toBeInTheDocument();
    expect(within(dialog).getByRole('button', { name: 'Skip 1,200 records' })).toBeDisabled();
    expect(calls).toEqual([true, false]);
  });
});
