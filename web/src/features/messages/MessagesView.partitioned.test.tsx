import { afterEach, describe, expect, it, vi } from 'vitest';
import { http, HttpResponse } from 'msw';
import { act, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { holdButton } from '../../test/hold.ts';
import { notifications } from '@mantine/notifications';

import { renderWithProviders } from '../../test/render.tsx';
import { server } from '../../test/setup.ts';
import { KAFKA_KINDS, KAFKA_SYSTEM } from '../../test/wire.ts';

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

const { MessagesView } = await import('./MessagesView.tsx');

const AVAILABLE = { status: 'AVAILABLE', reason: 'ok', brokerXmlSnippet: null };

function record(offset: number, tombstone = false) {
  return {
    position: String(offset),
    type: 0,
    durable: true,
    priority: 0,
    timestamp: 1_790_000_000_000,
    expiration: 0,
    size: 12,
    bodyPreview: tombstone ? null : `value-${offset}`,
    bodyTruncated: false,
    propertyCount: 0,
    redactions: [],
    tombstone,
  };
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

describe('MessagesView on a partitioned log', () => {
  it('reads partition 0 from the earliest record, without paging, and disables the selector with its reason', async () => {
    const seen = mockKafka(() =>
      page([record(0), record(1, true)], {
        partition: 0,
        start: 0,
        end: 2,
        next: null,
        retentionMoved: false,
        uncommittedHidden: true,
      }),
    );
    renderWithProviders(<MessagesView />);

    expect(await screen.findByText('value-0')).toBeInTheDocument();
    expect(screen.getByText('Tombstone (no value)')).toBeInTheDocument();
    expect(seen[0].searchParams.get('partition')).toBe('0');
    expect(seen[0].searchParams.get('from')).toBe('earliest');
    expect(seen[0].searchParams.has('filter')).toBe(false);
    expect(screen.getByRole('textbox', { name: 'Message selector' })).toBeDisabled();
    expect(screen.getByRole('textbox', { name: 'Message selector' })).toHaveAccessibleDescription(
      /cannot select records on the broker/,
    );
    expect(screen.queryByText(/^page 1/)).not.toBeInTheDocument();
    expect(screen.getByText('Only committed records are shown')).toBeInTheDocument();
  });

  it('browses from an offset, checking the value beside its field first', async () => {
    const seen = mockKafka(() =>
      page([record(500)], {
        partition: 3,
        start: 0,
        end: 900,
        next: null,
        retentionMoved: false,
        uncommittedHidden: true,
      }),
    );
    const user = userEvent.setup();
    renderWithProviders(<MessagesView />);
    await screen.findByText('value-500');

    await user.clear(screen.getByRole('textbox', { name: 'Partition' }));
    await user.type(screen.getByRole('textbox', { name: 'Partition' }), '3');
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

  it("says where the group stands on the partition when it is read through the group's subscription", async () => {
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

    expect(await screen.findByText('Where the group stands')).toBeInTheDocument();
    expect(screen.getByText(/The group stands at offset 640 on partition 0/)).toBeInTheDocument();
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

  it('offers the next records from the offset the page ends at, and shows the record key', async () => {
    const seen = mockKafka((url) =>
      url.searchParams.get('value') === '2'
        ? page([record(2)], {
            partition: 0,
            start: 0,
            end: 3,
            next: null,
            retentionMoved: false,
            uncommittedHidden: true,
          })
        : page([{ ...record(0), key: 'order-17' }, record(1)], {
            partition: 0,
            start: 0,
            end: 3,
            next: 2,
            retentionMoved: false,
            uncommittedHidden: true,
          }),
    );
    const user = userEvent.setup();
    renderWithProviders(<MessagesView />);

    expect(await screen.findByText('order-17')).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Next records, from offset 2' }));
    expect(await screen.findByText('value-2')).toBeInTheDocument();
    const last = seen.at(-1)!;
    expect(last.searchParams.get('from')).toBe('offset');
    expect(last.searchParams.get('value')).toBe('2');
    expect(screen.queryByRole('button', { name: /Next records/ })).not.toBeInTheDocument();
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
