import { beforeEach, describe, expect, it, vi } from 'vitest';
import { http, HttpResponse } from 'msw';
import { fireEvent, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

import { renderWithProviders } from '../../test/render.tsx';
import { server, EventSourceStub } from '../../test/setup.ts';
import { waitFor } from '@testing-library/react';

let search: Record<string, unknown> = {};
const navigate = vi.fn();
vi.mock('@tanstack/react-router', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@tanstack/react-router')>()),
  useParams: () => ({ clusterId: 'c1' }),
  useSearch: () => search,
  useNavigate: () => navigate,
}));

const { EventsView } = await import('./EventsView.tsx');

function cluster(notifStatus: string, extra: Record<string, unknown> = {}) {
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
        ...extra,
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

function event(over: Record<string, unknown> = {}) {
  return {
    seq: 1,
    occurredAt: '2026-09-04T10:00:00.000Z',
    receivedAt: '2026-09-04T10:00:00.000Z',
    type: 'openbroker.consumer.attached',
    destination: 'orders',
    routingName: 'orders',
    consumerName: 'c-42',
    sessionName: 's-1',
    connectionName: 'conn-1',
    remoteAddress: '10.0.0.9:5445',
    username: 'alice',
    nodeId: null,
    props: { 'artemis._AMQ_NotifType': 'CONSUMER_CREATED', 'artemis._AMQ_Address': 'orders' },
    ...over,
  };
}

function page(data: unknown[]) {
  return { data, count: data.length, page: 1, pageSize: 100, dropped: 0, oldestRetained: null };
}

describe('EventsView', () => {
  beforeEach(() => {
    search = {};
    navigate.mockClear();
  });

  it('is one page named Events, with the live switch in its header', async () => {
    server.use(
      http.get('*/api/v1/clusters/c1', () => HttpResponse.json(cluster('AVAILABLE'))),
      http.get('*/api/v1/clusters/c1/events', () => HttpResponse.json(page([event()]))),
    );
    renderWithProviders(<EventsView />);

    expect(await screen.findByRole('heading', { level: 1, name: 'Events' })).toBeInTheDocument();
    expect(screen.getAllByRole('heading', { level: 1 })).toHaveLength(1);
    expect(screen.getByRole('switch', { name: 'Live' })).toBeChecked();
  });

  it('lists events and a click on a row puts it in the address', async () => {
    server.use(
      http.get('*/api/v1/clusters/c1', () => HttpResponse.json(cluster('AVAILABLE'))),
      http.get('*/api/v1/clusters/c1/events', () =>
        HttpResponse.json({
          data: [event()],
          count: 1,
          page: 1,
          pageSize: 100,
          dropped: 0,
          oldestRetained: '2026-09-04T09:00:00.000Z',
        }),
      ),
    );
    const user = userEvent.setup();
    renderWithProviders(<EventsView />);

    // The grid is virtualised, so the payload lives in a drawer rather than in a
    // row expanded underneath.
    await user.click(await screen.findByRole('gridcell', { name: 'openbroker.consumer.attached' }));
    const update = navigate.mock.calls[0][0].search as (prev: object) => object;
    expect(update({})).toEqual({ event: 1 });
  });

  it('shows the raw props of the event the address names', async () => {
    search = { event: 1 };
    server.use(
      http.get('*/api/v1/clusters/c1', () => HttpResponse.json(cluster('AVAILABLE'))),
      http.get('*/api/v1/clusters/c1/events', () => HttpResponse.json(page([event()]))),
    );
    renderWithProviders(<EventsView />);

    const dialog = await screen.findByRole('dialog', { name: 'openbroker.consumer.attached' });
    // What the event is about is stated as terms and values; its raw properties follow, in a region the keyboard can scroll.
    expect(within(dialog).getByText('Subject')).toBeInTheDocument();
    expect(within(dialog).getByText('c-42')).toBeInTheDocument();
    expect(within(dialog).getByRole('heading', { level: 3, name: 'Properties' })).toBeInTheDocument();
    expect(await within(dialog).findByRole('region', { name: 'Event properties' })).toHaveTextContent(/_AMQ_Address/);
  });

  it('opens an event that is not on the loaded page by asking for it', async () => {
    search = { event: 7 };
    server.use(
      http.get('*/api/v1/clusters/c1', () => HttpResponse.json(cluster('AVAILABLE'))),
      http.get('*/api/v1/clusters/c1/events', () => HttpResponse.json(page([event()]))),
      http.get('*/api/v1/clusters/c1/events/7', () =>
        HttpResponse.json(
          event({ seq: 7, type: 'openbroker.channel.closed', props: { 'artemis._AMQ_Reason': 'aged out' } }),
        ),
      ),
    );
    renderWithProviders(<EventsView />);

    expect(await screen.findByRole('dialog', { name: 'openbroker.channel.closed' })).toBeInTheDocument();
    expect(await screen.findByText(/aged out/)).toBeInTheDocument();
  });

  it('says an unknown event no longer exists', async () => {
    search = { event: 404 };
    server.use(
      http.get('*/api/v1/clusters/c1', () => HttpResponse.json(cluster('AVAILABLE'))),
      http.get('*/api/v1/clusters/c1/events', () => HttpResponse.json(page([event()]))),
      http.get('*/api/v1/clusters/c1/events/404', () =>
        HttpResponse.json(
          { type: 'about:blank', title: 'Resource not found', status: 404, detail: 'event 404 does not exist.' },
          { status: 404 },
        ),
      ),
    );
    renderWithProviders(<EventsView />);

    expect(await screen.findByText('This event no longer exists')).toBeInTheDocument();
    expect(screen.getByText(/retention may have removed it/)).toBeInTheDocument();
  });

  it('copies a link to the event from its row menu', async () => {
    server.use(
      http.get('*/api/v1/clusters/c1', () => HttpResponse.json(cluster('AVAILABLE'))),
      http.get('*/api/v1/clusters/c1/events', () => HttpResponse.json(page([event()]))),
    );
    const user = userEvent.setup();
    renderWithProviders(<EventsView />);

    await user.click(await screen.findByRole('button', { name: /^Actions for openbroker\.consumer\.attached/ }));
    await user.click(await screen.findByRole('menuitem', { name: 'Copy link' }));

    expect(await navigator.clipboard.readText()).toBe(`${window.location.origin}/clusters/c1/events?event=1`);
  });

  it('shows the reason and broker.xml snippet when notifications are unavailable', async () => {
    server.use(
      http.get('*/api/v1/clusters/c1', () => HttpResponse.json(cluster('UNAVAILABLE'))),
      http.get('*/api/v1/clusters/c1/events', () =>
        HttpResponse.json({ data: [], count: 0, page: 1, pageSize: 100, dropped: 0, oldestRetained: null }),
      ),
    );
    renderWithProviders(<EventsView />);

    expect(await screen.findByText(/refused the subscription/)).toBeInTheDocument();
    expect(screen.getByText(/activemq\.notifications/)).toBeInTheDocument();
  });

  it('appends a live event pushed over the stream and does not duplicate on a repeat seq', async () => {
    server.use(
      http.get('*/api/v1/clusters/c1', () => HttpResponse.json(cluster('AVAILABLE'))),
      http.get('*/api/v1/clusters/c1/events', () =>
        HttpResponse.json({ data: [], count: 0, page: 1, pageSize: 100, dropped: 0, oldestRetained: null }),
      ),
    );
    renderWithProviders(<EventsView />);

    await screen.findByText('No broker events yet');
    await waitFor(() => expect(EventSourceStub.instances.length).toBeGreaterThan(0));

    EventSourceStub.emit('events', event({ seq: 99, type: 'openbroker.channel.opened', remoteAddress: '10.9.9.9:1' }));
    expect(await screen.findByText('10.9.9.9:1')).toBeInTheDocument();

    // Re-delivering the same seq must not add a second row (remoteAddress is row-only).
    EventSourceStub.emit('events', event({ seq: 99, type: 'openbroker.channel.opened', remoteAddress: '10.9.9.9:1' }));
    await waitFor(() => expect(screen.getAllByText('10.9.9.9:1')).toHaveLength(1));
  });

  it('holds a live event back while the reader is scrolled away from the top, and shows it on return', async () => {
    server.use(
      http.get('*/api/v1/clusters/c1', () => HttpResponse.json(cluster('AVAILABLE'))),
      http.get('*/api/v1/clusters/c1/events', () => HttpResponse.json(page([event()]))),
    );
    renderWithProviders(<EventsView />);

    const grid = await screen.findByRole('grid', { name: 'Broker events' });
    await within(grid).findByText('openbroker.consumer.attached');
    await waitFor(() => expect(EventSourceStub.instances.length).toBeGreaterThan(0));
    const scroller = grid.parentElement!;

    fireEvent.scroll(scroller, { target: { scrollTop: 200 } });
    EventSourceStub.emit('events', event({ seq: 99, type: 'openbroker.channel.opened', remoteAddress: '10.9.9.9:1' }));
    await new Promise((r) => setTimeout(r, 50));
    expect(within(grid).queryByText('10.9.9.9:1')).not.toBeInTheDocument();

    fireEvent.scroll(scroller, { target: { scrollTop: 0 } });
    expect(await within(grid).findByText('10.9.9.9:1')).toBeInTheDocument();
  });

  it('says a filter excludes every event, and clearing it drops the filters from the address', async () => {
    search = { type: 'openbroker.channel.closed' };
    server.use(
      http.get('*/api/v1/clusters/c1', () => HttpResponse.json(cluster('AVAILABLE'))),
      http.get('*/api/v1/clusters/c1/events', () => HttpResponse.json(page([]))),
    );
    const user = userEvent.setup();
    renderWithProviders(<EventsView />);

    expect(await screen.findByText('No event matches these filters')).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Clear filters' }));
    const update = navigate.mock.calls.at(-1)![0].search as (prev: object) => object;
    expect(update({ type: 'openbroker.channel.closed' })).toEqual({
      type: undefined,
      destination: undefined,
      page: undefined,
    });
  });

  it('names the nodes that did not answer instead of presenting no events as a fact', async () => {
    const down = cluster('AVAILABLE');
    down.topology.nodes = [
      { id: 'n1', endpoints: [{ id: 'e1', name: 'broker-b', lastError: 'connection refused' }] },
    ] as never;
    server.use(
      http.get('*/api/v1/clusters/c1', () => HttpResponse.json(down)),
      http.get('*/api/v1/clusters/c1/events', () => HttpResponse.json(page([]))),
    );
    renderWithProviders(<EventsView />);

    expect(await screen.findByText('broker-b could not be reached')).toBeInTheDocument();
    expect(screen.getByRole('list', { name: 'Nodes that could not be reached' })).toHaveTextContent('broker-b');
  });

  it('warns when events have been dropped', async () => {
    server.use(
      http.get('*/api/v1/clusters/c1', () => HttpResponse.json(cluster('AVAILABLE'))),
      http.get('*/api/v1/clusters/c1/events', () =>
        HttpResponse.json({ data: [], count: 0, page: 1, pageSize: 100, dropped: 7, oldestRetained: null }),
      ),
    );
    renderWithProviders(<EventsView />);

    expect(await screen.findByText('Some events were dropped')).toBeInTheDocument();
    expect(
      screen.getByText(/7 notifications arrived faster than the write buffer could be flushed/),
    ).toBeInTheDocument();
  });
});
