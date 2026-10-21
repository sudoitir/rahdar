import { beforeEach, describe, expect, it, vi } from 'vitest';
import { delay, http, HttpResponse } from 'msw';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import type { ComponentType } from 'react';

import { renderWithProviders } from '../../test/render.tsx';
import { server } from '../../test/setup.ts';
import { ARTEMIS_KINDS, ARTEMIS_SYSTEM } from '../../test/wire.ts';
import type { CapabilityView, RouteView } from './api.ts';

let search: Record<string, unknown> = {};
const navigate = vi.fn();
vi.mock('@tanstack/react-router', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@tanstack/react-router')>()),
  useParams: () => ({ clusterId: 'c1' }),
  useSearch: () => search,
  useNavigate: () => navigate,
}));

/** The tabs other features contribute; the real routing builder is not what is under test here. */
let contributed: {
  id: string;
  order: number;
  title: string;
  system?: string;
  Component: ComponentType<{ clusterId: string }>;
}[] = [];
vi.mock('../../kernel/slots.ts', async (importOriginal) => ({
  ...(await importOriginal<typeof import('../../kernel/slots.ts')>()),
  useSlot: (name: string) => (name === 'routes.tabs' ? contributed : []),
}));

const { RoutesView } = await import('./RoutesView.tsx');

const AVAILABLE: CapabilityView = { status: 'AVAILABLE', reason: 'ok', brokerXmlSnippet: null };

function divert(
  over: Partial<{
    name: string;
    owner: string | null;
    exclusive: boolean;
    brokerXml: string | null;
    filter: string;
    nodesPresent: number;
  }> = {},
): RouteView {
  const name = over.name ?? 'copy-orders';
  return {
    id: `artemis.divert/${name}`,
    kind: 'artemis.divert',
    kindLabel: 'Divert',
    name,
    routeClass: 'FORWARD',
    source: 'artemis.address/orders',
    target: 'artemis.address/orders.audit',
    nativeAttributes: {
      ...(over.filter ? { filter: over.filter } : {}),
      exclusive: String(over.exclusive ?? false),
    },
    nodesPresent: over.nodesPresent ?? 2,
    nodesTotal: 2,
    perNode: [],
    owner: over.owner ?? null,
    drift: over.brokerXml ?? null,
  };
}

function bridge(
  over: Partial<{
    name: string;
    started: boolean;
    connected: boolean;
    queueName: string | null;
    forwardingAddress: string | null;
  }> = {},
): RouteView {
  const name = over.name ?? 'to-dr';
  const queueName = over.queueName === undefined ? 'orders.out' : over.queueName;
  const forwardingAddress = over.forwardingAddress === undefined ? 'orders.in' : over.forwardingAddress;
  return {
    id: `artemis.bridge/${name}`,
    kind: 'artemis.bridge',
    kindLabel: 'Bridge',
    name,
    routeClass: 'LINK',
    source: queueName ? `artemis.queue/${queueName}` : null,
    target: null,
    nativeAttributes: {
      ...(forwardingAddress ? { forwardingAddress } : {}),
      started: String(over.started ?? true),
      connected: String(over.connected ?? true),
    },
    nodesPresent: 1,
    nodesTotal: 2,
    perNode: [{ nodeId: 'n1', nodeName: 'broker-1', messagesAcknowledged: 12, messagesPendingAcknowledgement: 3 }],
    owner: null,
    drift: null,
  };
}

const CLUSTER = {
  id: 'c1',
  name: 'c1',
  description: null,
  topology: { nodes: [] },
  capabilities: {
    managementRead: AVAILABLE,
    managementWrite: AVAILABLE,
    notifications: AVAILABLE,
    messageIo: AVAILABLE,
    slowConsumerDetection: AVAILABLE,
    versionGates: [],
  },
  health: { level: 'OK', reasons: [] },
  environmentId: null,
  system: ARTEMIS_SYSTEM,
  kinds: ARTEMIS_KINDS,
};

function serve({ routes = [], count }: { routes?: RouteView[]; count?: number } = {}) {
  server.use(
    http.get('*/api/v1/auth/me', () =>
      HttpResponse.json({
        id: 'u1',
        username: 'admin',
        mustChangePassword: false,
        grants: [{ scopeType: 'GLOBAL', scopeId: null, permissions: ['*'] }],
      }),
    ),
    http.get('*/api/v1/clusters/c1', () => HttpResponse.json(CLUSTER)),
    http.get('*/api/v1/clusters/c1/routes', () =>
      HttpResponse.json({ data: routes, count: count ?? routes.length, page: 1, pageSize: 200 }),
    ),
  );
}

/** What a navigate() call would do to the address it starts from. */
function lastSearch(prev: Record<string, unknown> = {}) {
  const update = navigate.mock.lastCall![0].search as (prev: Record<string, unknown>) => Record<string, unknown>;
  return update(prev);
}

beforeEach(() => {
  search = {};
  contributed = [
    { id: 'builder', order: 10, title: 'Builder', system: 'artemis', Component: () => <p>builder body</p> },
  ];
  navigate.mockReset();
});

describe('RoutesView', () => {
  it('reads each divert as a direction, a state and an owner, in words', async () => {
    serve({
      routes: [
        divert({ name: 'capture-tap', owner: 'MESSAGE_CAPTURE', exclusive: true }),
        divert({ name: 'by-operator', owner: 'OPERATOR', brokerXml: '<divert name="by-operator"/>' }),
        divert({ name: 'legacy', owner: null, filter: "color='red'", nodesPresent: 1 }),
      ],
    });
    renderWithProviders(<RoutesView />);

    const grid = await screen.findByRole('grid', { name: 'Routes' });
    expect(await within(grid).findAllByLabelText('from orders to orders.audit')).toHaveLength(3);
    expect(within(grid).getByText('takes the message')).toBeInTheDocument();
    expect(within(grid).getAllByText('copies the message')).toHaveLength(2);
    expect(within(grid).getByText('message capture')).toBeInTheDocument();
    expect(within(grid).getByText('not recorded')).toBeInTheDocument();
    expect(within(grid).getByText("color='red'")).toBeInTheDocument();
    expect(within(grid).getByText('1/2')).toBeInTheDocument();
    expect(screen.getByText('1–3 of 3 routes')).toBeInTheDocument();
  });

  it('names each route by its kind', async () => {
    serve({ routes: [divert(), bridge()] });
    renderWithProviders(<RoutesView />);

    const grid = await screen.findByRole('grid', { name: 'Routes' });
    expect(await within(grid).findByText('Divert')).toBeInTheDocument();
    expect(within(grid).getByText('Bridge')).toBeInTheDocument();
  });

  it('opens the broker.xml that would make the configuration match for a divert Rahdar created', async () => {
    serve({ routes: [divert({ name: 'by-operator', owner: 'OPERATOR', brokerXml: '<divert name="by-operator"/>' })] });
    const user = userEvent.setup();
    renderWithProviders(<RoutesView />);

    await user.click(await screen.findByRole('button', { name: 'Rahdar — not in broker.xml' }));

    const dialog = await screen.findByRole('dialog', { name: '"by-operator" is not in broker.xml' });
    expect(within(dialog).getByText(/will not appear in the broker\.xml/)).toBeInTheDocument();
    expect(within(dialog).getByText('<divert name="by-operator"/>')).toBeInTheDocument();
  });

  it('still explains the drift when Rahdar holds no broker.xml for the divert', async () => {
    serve({ routes: [divert({ owner: 'OPERATOR', brokerXml: null })] });
    const user = userEvent.setup();
    renderWithProviders(<RoutesView />);

    await user.click(await screen.findByRole('button', { name: 'Rahdar — not in broker.xml' }));

    const dialog = await screen.findByRole('dialog');
    expect(within(dialog).getByText(/will not appear in the broker\.xml/)).toBeInTheDocument();
    expect(within(dialog).queryByText(/Add this to broker.xml/)).not.toBeInTheDocument();
  });

  it('says whether each bridge is running, half-up or stopped, in words', async () => {
    serve({
      routes: [
        bridge({ name: 'up', started: true, connected: true }),
        bridge({ name: 'half', started: true, connected: false, queueName: null, forwardingAddress: null }),
        bridge({ name: 'down', started: false, connected: false }),
      ],
    });
    renderWithProviders(<RoutesView />);

    const grid = await screen.findByRole('grid', { name: 'Routes' });
    expect(await within(grid).findByText('running and connected')).toBeInTheDocument();
    expect(within(grid).getByText('started, not connected to its target')).toBeInTheDocument();
    expect(within(grid).getByText('not started')).toBeInTheDocument();
    // A link with nothing recorded is named as such rather than left blank.
    expect(within(grid).getByLabelText('from (unnamed source) to (the target broker)')).toBeInTheDocument();
    expect(within(grid).getAllByLabelText('from orders.out to orders.in')).toHaveLength(2);
    expect(within(grid).getAllByText('3').length).toBeGreaterThan(0);
  });

  it('teaches what a route is when there are none, pointing to the Builder tab for a bridge', async () => {
    serve();
    renderWithProviders(<RoutesView />);

    expect(await screen.findByText('No routes yet')).toBeInTheDocument();
    expect(screen.getByText(/A route copies, takes or carries the messages/)).toBeInTheDocument();
    expect(screen.getByText(/declare a bridge on the Builder tab and apply it/)).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Clear filters' })).not.toBeInTheDocument();
  });

  it('says the cluster configuration is not enabled when there is no builder to point to', async () => {
    contributed = [];
    serve();
    renderWithProviders(<RoutesView />);

    expect(await screen.findByText(/which is not enabled on this Rahdar/)).toBeInTheDocument();
  });

  it('says a filter emptied the list and clears it from the empty state', async () => {
    search = { q: 'zzz' };
    serve();
    const user = userEvent.setup();
    renderWithProviders(<RoutesView />);

    expect(await screen.findByText('No route matches this filter')).toBeInTheDocument();
    expect(screen.getByText('Clear it to see every route on the cluster.')).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Clear filters' }));

    expect(screen.getByRole('textbox', { name: 'Filter by destination or name' })).toHaveValue('');
    await waitFor(() => expect(navigate).toHaveBeenCalled());
    expect(lastSearch({ q: 'zzz', page: 2 })).toEqual({ q: undefined, page: undefined });
  });

  it('names a node that did not answer instead of presenting no routes as a fact', async () => {
    serve();
    server.use(
      http.get('*/api/v1/clusters/c1', () =>
        HttpResponse.json({
          ...CLUSTER,
          topology: { nodes: [{ endpoints: [{ id: 'e1', name: 'backup', lastError: 'connection refused' }] }] },
        }),
      ),
    );
    renderWithProviders(<RoutesView />);

    expect(await screen.findByText('No routes could be listed')).toBeInTheDocument();
    expect(
      within(screen.getByRole('list', { name: 'Nodes that could not be reached' })).getByText('backup'),
    ).toBeVisible();
    expect(screen.queryByText('No routes yet')).not.toBeInTheDocument();
  });

  it('states why the routes could not be read instead of listing none, and retries', async () => {
    let calls = 0;
    server.use(
      http.get('*/api/v1/clusters/c1/routes', () => {
        calls += 1;
        return HttpResponse.json({ title: 'Cluster unreachable', detail: 'No node answered.' }, { status: 502 });
      }),
    );
    const user = userEvent.setup();
    renderWithProviders(<RoutesView />);

    const alert = await screen.findByRole('alert');
    expect(within(alert).getByText('Rahdar failed to complete the request')).toBeInTheDocument();
    expect(screen.queryByText('No routes yet')).not.toBeInTheDocument();

    const before = calls;
    await user.click(within(alert).getByRole('button', { name: 'Retry' }));
    await waitFor(() => expect(calls).toBeGreaterThan(before));
  });

  it('puts the sort in the address and starts again at the first page', async () => {
    serve({ routes: [divert()] });
    const user = userEvent.setup();
    renderWithProviders(<RoutesView />);

    await screen.findByRole('grid', { name: 'Routes' });
    await user.click(within(screen.getByRole('columnheader', { name: /Name/ })).getByRole('button'));

    expect(lastSearch({ page: 3 })).toEqual({ page: undefined, sort: 'name' });
  });

  it('pages through a long list, saying where it is, and drops the page parameter on the first page', async () => {
    search = { page: 2 };
    serve({ routes: [divert()], count: 450 });
    const user = userEvent.setup();
    renderWithProviders(<RoutesView />);

    expect(await screen.findByText('201–400 of 450 routes')).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Next' }));
    expect(lastSearch()).toEqual({ page: 3 });
    await user.click(screen.getByRole('button', { name: 'Previous' }));
    expect(lastSearch()).toEqual({ page: undefined });
  });

  it('writes the typed filter into the address once typing pauses, and resets the page', async () => {
    serve({ routes: [divert()] });
    const user = userEvent.setup();
    renderWithProviders(<RoutesView />);

    await user.type(await screen.findByRole('textbox', { name: 'Filter by destination or name' }), 'orders');

    await waitFor(() => expect(navigate).toHaveBeenCalled());
    expect(lastSearch({ page: 4 })).toEqual({ page: undefined, q: 'orders' });
  });

  it('offers to create a divert on an Artemis cluster, and not on a cluster of another system', async () => {
    serve({ routes: [divert()] });
    renderWithProviders(<RoutesView />);
    expect(await screen.findByRole('button', { name: /Create divert/ })).toBeEnabled();
  });

  it('does not offer a divert on a cluster that runs another system', async () => {
    serve({ routes: [bridge()] });
    server.use(
      http.get('*/api/v1/clusters/c1', () =>
        HttpResponse.json({ ...CLUSTER, system: { id: 'kafka', name: 'Kafka' }, kinds: [] }),
      ),
    );
    renderWithProviders(<RoutesView />);

    await screen.findByRole('grid', { name: 'Routes' });
    expect(screen.queryByRole('button', { name: /Create divert/ })).not.toBeInTheDocument();
    // The Builder is Artemis's own, so its tab is not listed here either.
    expect(screen.queryByRole('tab', { name: 'Builder' })).not.toBeInTheDocument();
  });
});

describe('RoutesView tabs', () => {
  it('lists Routes and each contributed tab, with the address deciding which is open', async () => {
    serve();
    renderWithProviders(<RoutesView />);

    expect(await screen.findByRole('tab', { name: 'Routes', selected: true })).toBeInTheDocument();
    expect(await screen.findByRole('tab', { name: 'Builder', selected: false })).toBeInTheDocument();
  });

  it('shows a contributed tab in place of the listing, handing it the cluster', async () => {
    search = { tab: 'builder' };
    contributed = [
      {
        id: 'builder',
        order: 10,
        title: 'Builder',
        system: 'artemis',
        Component: ({ clusterId }) => <p>builder of {clusterId}</p>,
      },
    ];
    serve();
    renderWithProviders(<RoutesView />);

    expect(await screen.findByText('builder of c1')).toBeInTheDocument();
    expect(screen.getByRole('tab', { name: 'Builder', selected: true })).toBeInTheDocument();
    expect(screen.queryByRole('textbox', { name: 'Filter by destination or name' })).not.toBeInTheDocument();
  });

  it('falls back to Routes for a tab nothing provides', async () => {
    search = { tab: 'gone' };
    serve();
    renderWithProviders(<RoutesView />);

    expect(await screen.findByRole('tab', { name: 'Routes', selected: true })).toBeInTheDocument();
  });

  it('drops what the last tab kept in the address when the tab changes, and Routes is the bare address', async () => {
    search = { q: 'x' };
    serve();
    const user = userEvent.setup();
    renderWithProviders(<RoutesView />);

    await user.click(await screen.findByRole('tab', { name: 'Builder' }));
    expect(lastSearch({ q: 'x' })).toEqual({ tab: 'builder' });

    await user.click(screen.getByRole('tab', { name: 'Routes' }));
    expect(lastSearch({ q: 'x' })).toEqual({ tab: undefined });
  });
});

describe('RoutesView page structure', () => {
  it('is one page whose single h1 names the view, with the tabs under it', async () => {
    serve({ routes: [divert()] });
    renderWithProviders(<RoutesView />);

    expect(await screen.findByRole('heading', { level: 1, name: 'Routes' })).toBeInTheDocument();
    expect(screen.getAllByRole('heading', { level: 1 })).toHaveLength(1);
    expect(screen.getByRole('tab', { name: 'Routes' })).toBeInTheDocument();
  });

  it('keeps the page header above a contributed tab', async () => {
    search = { tab: 'builder' };
    renderWithProviders(<RoutesView />);

    expect(await screen.findByText('builder body')).toBeInTheDocument();
    expect(screen.getByRole('heading', { level: 1, name: 'Routes' })).toBeInTheDocument();
  });

  it('puts the page position in the table toolbar, not below the table', async () => {
    serve({ routes: [divert()] });
    renderWithProviders(<RoutesView />);

    const controls = await screen.findByRole('group', { name: 'Routes controls' });
    expect(await within(controls).findByText('1–1 of 1 routes')).toBeInTheDocument();
  });

  it('does not say there are no routes while the first page is still loading', async () => {
    serve();
    server.use(http.get('*/api/v1/clusters/c1/routes', async () => delay('infinite')));
    renderWithProviders(<RoutesView />);

    await screen.findByRole('grid', { name: 'Routes' });
    expect(screen.queryByText(/^No routes$/)).not.toBeInTheDocument();
    expect(screen.queryByText('No routes yet')).not.toBeInTheDocument();
  });
});
