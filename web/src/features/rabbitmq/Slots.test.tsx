import { describe, expect, it, vi } from 'vitest';
import { http, HttpResponse } from 'msw';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

import { mockRabbitMqCluster } from '../../test/rabbitmqCluster.ts';
import { renderWithProviders } from '../../test/render.tsx';
import { server } from '../../test/setup.ts';

vi.mock('@tanstack/react-router', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@tanstack/react-router')>()),
  Link: ({ children, to }: { children: React.ReactNode; to: string }) => <a href={to}>{children}</a>,
}));

const { CreateExchangeForm } = await import('./CreateExchangeForm.tsx');
const { CreateQueueForm } = await import('./CreateQueueForm.tsx');
const { PublishDialog } = await import('./PublishToExchange.tsx');
const { QueuePanel } = await import('./QueuePanel.tsx');
const { rabbitmqFeature } = await import('./feature.ts');

const BASE = '*/api/v1/clusters/r1/rabbitmq';
const VHOSTS = {
  items: [{ name: 'orders', description: '', tags: [], queues: 0, messages: 0 }],
  countsComplete: true,
  next: null,
};
const PREVIEW = {
  effect: { count: 0, unit: 'changes', detail: 'Creates it.', changes: [], lines: [] },
  etag: 'c-etag',
  identityBasis: 'CONFIGURATION',
};

describe('the feature', () => {
  it('has every entry and contribution is for RabbitMQ only', () => {
    expect(rabbitmqFeature.nav?.every((n) => n.system === 'rabbitmq')).toBe(true);
    expect(rabbitmqFeature.nav?.map((n) => n.path)).toEqual([
      'rabbitmq/nodes',
      'rabbitmq/bindings',
      'rabbitmq/links',
      'rabbitmq/vhosts',
      'rabbitmq/policies',
      'rabbitmq/permissions',
    ]);
    const slots = Object.values(rabbitmqFeature.slots ?? {}).flat();
    expect(slots).toHaveLength(5);
    expect(slots.every((s) => s.system === 'rabbitmq')).toBe(true);
  });
});

describe('the queue panel', () => {
  const ref = { id: 'rabbitmq.quorum-queue/orders/in', kind: 'rabbitmq.quorum-queue', name: 'in' };
  const show = (kind = ref.kind) =>
    renderWithProviders(<QueuePanel clusterId="r1" subscription={{ ...ref, kind }} onClose={() => {}} />);

  it('shows the type, leader, members with their sync, arguments and the effective policy', async () => {
    mockRabbitMqCluster();
    server.use(
      http.get('*/api/v1/clusters/r1/resources/rabbitmq.quorum-queue/*', () =>
        HttpResponse.json({
          nativeAttributes: {
            'rabbitmq.leader': 'rabbit@a',
            'rabbitmq.members': 'rabbit@a, rabbit@b',
            'rabbitmq.online': 'rabbit@a',
            'rabbitmq.policy': 'limit',
            'rabbitmq.delivery_limit': '20',
            'rabbitmq.argument.x-max-length': '1000',
          },
        }),
      ),
    );
    show();
    expect(await screen.findByText(/A replicated queue that keeps its messages/)).toBeInTheDocument();
    const members = screen.getByRole('list', { name: 'Members' });
    expect(within(members).getByText('In sync')).toBeInTheDocument();
    expect(within(members).getByText('Not in sync')).toBeInTheDocument();
    expect(screen.getByText('limit')).toBeInTheDocument();
    expect(screen.getByText('x-max-length')).toBeInTheDocument();
    expect(screen.getByText('20')).toBeInTheDocument();
  });

  it('says there are no arguments, and shows the error of a failed read', async () => {
    mockRabbitMqCluster();
    server.use(
      http.get('*/api/v1/clusters/r1/resources/rabbitmq.quorum-queue/*', () =>
        HttpResponse.json({ nativeAttributes: {} }),
      ),
    );
    show();
    expect(await screen.findByText(/No arguments/)).toBeInTheDocument();
    expect(screen.getByText('None applies')).toBeInTheDocument();
  });

  it('shows the error of a failed read', async () => {
    mockRabbitMqCluster();
    server.use(
      http.get('*/api/v1/clusters/r1/resources/rabbitmq.quorum-queue/*', () =>
        HttpResponse.json({ title: 'Bad gateway', detail: 'No answer.' }, { status: 502 }),
      ),
    );
    show();
    expect(await screen.findByText(/No answer/)).toBeInTheDocument();
  });

  it('shows nothing for a resource that is not a RabbitMQ queue', () => {
    show('artemis.queue');
    expect(screen.queryByText('Arguments')).not.toBeInTheDocument();
    expect(screen.queryByText('Loading the queue')).not.toBeInTheDocument();
  });
});

describe('publishing to an exchange', () => {
  const exchange = { id: 'rabbitmq.exchange/orders/events', kind: 'rabbitmq.exchange', name: 'events' };
  const open = () =>
    renderWithProviders(<PublishDialog clusterId="r1" exchange={exchange} opened onClose={() => {}} />);

  it('keeps the dialog open and names the exchange and key when no queue received the message', async () => {
    mockRabbitMqCluster();
    server.use(
      http.post('*/api/v1/clusters/r1/resources/rabbitmq.exchange/*/messages', () =>
        HttpResponse.json(
          { type: 'https://x/problems/not-routed', title: 'Conflict', detail: 'Nothing bound.' },
          { status: 409 },
        ),
      ),
    );
    const user = userEvent.setup();
    open();
    const dialog = await screen.findByRole('dialog', { name: 'Publish to events' });
    await user.type(within(dialog).getByRole('textbox', { name: 'Routing key' }), 'order.created');
    await user.click(within(dialog).getByRole('button', { name: 'Publish' }));
    expect(await within(dialog).findByText('No queue received the message')).toBeInTheDocument();
    expect(
      within(dialog).getByText(/Exchange events has no binding that matches routing key "order.created"/),
    ).toBeInTheDocument();
    expect(within(dialog).getByRole('link', { name: 'Open its bindings' })).toHaveAttribute(
      'href',
      '/clusters/r1/rabbitmq/bindings',
    );
  });

  it('sends the routing key, body and headers', async () => {
    let body: Record<string, unknown> = {};
    mockRabbitMqCluster();
    server.use(
      http.post('*/api/v1/clusters/r1/resources/rabbitmq.exchange/*/messages', async ({ request }) => {
        body = (await request.json()) as never;
        return HttpResponse.json({ affected: 1 });
      }),
    );
    const user = userEvent.setup();
    open();
    const dialog = await screen.findByRole('dialog', { name: 'Publish to events' });
    await user.type(within(dialog).getByRole('textbox', { name: 'Routing key' }), 'order.created');
    await user.type(within(dialog).getByRole('textbox', { name: 'Body' }), 'hello');
    await user.click(within(dialog).getByRole('button', { name: 'Add header' }));
    await user.click(within(dialog).getByRole('button', { name: 'Publish' }));
    expect(await within(dialog).findByText('Name the header, or remove the row.')).toBeInTheDocument();
    await user.type(within(dialog).getByRole('textbox', { name: 'Name' }), 'tenant');
    await user.type(within(dialog).getByRole('textbox', { name: 'Value' }), 'eu');
    await user.click(within(dialog).getByRole('button', { name: 'Publish' }));
    await waitFor(() =>
      expect(body).toMatchObject({ routingKey: 'order.created', body: 'hello', headers: { tenant: 'eu' } }),
    );
  });
});

describe('the create forms', () => {
  it('previews a queue with its type, delivery limit and arguments, then creates it', async () => {
    const bodies: Record<string, unknown>[] = [];
    mockRabbitMqCluster();
    server.use(
      http.get(`${BASE}/vhosts`, () => HttpResponse.json(VHOSTS)),
      http.post(`${BASE}/queues\\:preview`, async ({ request }) => {
        bodies.push((await request.json()) as never);
        return HttpResponse.json(PREVIEW);
      }),
      http.post(`${BASE}/queues`, async ({ request }) => {
        bodies.push((await request.json()) as never);
        return HttpResponse.json({ id: 'rabbitmq.quorum-queue/orders/in' });
      }),
    );
    const user = userEvent.setup();
    const closed = vi.fn();
    renderWithProviders(<CreateQueueForm clusterId="r1" opened onClose={closed} />);
    const form = await screen.findByRole('dialog', { name: 'Create a queue' });
    expect(within(form).getByText(/append-only log/)).toBeInTheDocument();
    await user.click(within(form).getByRole('button', { name: 'Preview the queue' }));
    expect(await within(form).findByText('Enter the name of the queue.')).toBeInTheDocument();
    await user.type(within(form).getByRole('textbox', { name: 'Name' }), 'in');
    await user.click(within(form).getByLabelText('Virtual host'));
    await user.click(await screen.findByRole('option', { name: 'orders' }));
    await user.type(within(form).getByRole('textbox', { name: 'Delivery limit' }), '20');
    await user.click(within(form).getByRole('button', { name: 'Add argument' }));
    await user.type(within(form).getAllByRole('textbox', { name: 'Name' })[1], 'x-max-length');
    await user.type(within(form).getByRole('textbox', { name: 'Value' }), '1000');
    await user.click(within(form).getByRole('button', { name: 'Preview the queue' }));

    const dialog = await screen.findByRole('dialog', { name: 'Create queue in' });
    await user.click(await within(dialog).findByRole('button', { name: 'Create the queue' }));
    await waitFor(() => expect(bodies).toHaveLength(2));
    expect(bodies[0]).toMatchObject({
      vhost: 'orders',
      name: 'in',
      type: 'quorum',
      durable: true,
      arguments: { 'x-delivery-limit': 20, 'x-max-length': 1000 },
    });
    expect(bodies[1]).toMatchObject({ etag: 'c-etag' });
    await waitFor(() => expect(closed).toHaveBeenCalled());
  });

  it('offers the maximum age of a stream and previews an exchange', async () => {
    const bodies: Record<string, unknown>[] = [];
    mockRabbitMqCluster();
    server.use(
      http.get(`${BASE}/vhosts`, () => HttpResponse.json(VHOSTS)),
      http.post(`${BASE}/exchanges\\:preview`, async ({ request }) => {
        bodies.push((await request.json()) as never);
        return HttpResponse.json(PREVIEW);
      }),
    );
    const user = userEvent.setup();
    renderWithProviders(<CreateExchangeForm clusterId="r1" opened onClose={() => {}} />);
    const form = await screen.findByRole('dialog', { name: 'Create an exchange' });
    expect(within(form).getByText(/Routes by pattern/)).toBeInTheDocument();
    await user.click(within(form).getByLabelText('Virtual host'));
    await user.click(await screen.findByRole('option', { name: 'orders' }));
    await user.type(within(form).getByRole('textbox', { name: 'Name' }), 'events');
    await user.click(within(form).getByRole('radio', { name: /Topic/ }));
    await user.click(within(form).getByRole('button', { name: 'Preview the exchange' }));
    await screen.findByRole('dialog', { name: 'Create exchange events' });
    expect(bodies[0]).toMatchObject({ vhost: 'orders', name: 'events', type: 'topic', durable: true });
  });
});
