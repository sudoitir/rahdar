import { describe, expect, it } from 'vitest';
import { http, HttpResponse } from 'msw';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

import { holdButton } from '../../test/hold.ts';
import { mockRabbitMqCluster } from '../../test/rabbitmqCluster.ts';
import { renderAppAt } from '../../test/render.tsx';
import { server } from '../../test/setup.ts';

const BINDING = {
  id: 'b1',
  vhost: 'orders',
  source: 'events',
  destination: 'orders.in',
  destinationType: 'queue',
  routingKey: 'order.created',
  arguments: {},
};

const PREVIEW = {
  effect: { count: 1, unit: 'changes', detail: 'Removes the binding.', changes: [], lines: [] },
  etag: 'b-etag',
  identityBasis: 'CONFIGURATION',
};

const BASE = '*/api/v1/clusters/r1/rabbitmq/bindings';

function serve(items: object[], handlers: Parameters<typeof server.use> = [], permissions?: string[]) {
  mockRabbitMqCluster(permissions);
  server.use(
    http.get(BASE, () => HttpResponse.json({ items, next: null })),
    ...handlers,
  );
}

describe('the bindings', () => {
  it('lists them by source, destination and routing key', async () => {
    serve([BINDING]);
    renderAppAt('/clusters/r1/rabbitmq/bindings');
    const grid = await screen.findByRole('grid', { name: 'Bindings' });
    expect(await within(grid).findByText('order.created')).toBeInTheDocument();
    expect(within(grid).getByText('orders.in')).toBeInTheDocument();
  });

  it('says there is none, and says so differently under a filter', async () => {
    serve([]);
    renderAppAt('/clusters/r1/rabbitmq/bindings');
    expect(await screen.findByText('No bindings')).toBeInTheDocument();
    renderAppAt('/clusters/r1/rabbitmq/bindings?source=events');
    expect(await screen.findByText('No binding matches this filter')).toBeInTheDocument();
  });

  it('passes the filter in the address to the read', async () => {
    let query = '';
    mockRabbitMqCluster();
    server.use(
      http.get(BASE, ({ request }) => {
        query = new URL(request.url).search;
        return HttpResponse.json({ items: [], next: null });
      }),
    );
    renderAppAt('/clusters/r1/rabbitmq/bindings?vhost=orders&source=events');
    await waitFor(() => expect(query).toContain('source=events'));
    expect(query).toContain('vhost=orders');
  });

  it('shows the error of a failed read', async () => {
    mockRabbitMqCluster();
    server.use(
      http.get(BASE, () => HttpResponse.json({ title: 'Bad gateway', detail: 'No answer.' }, { status: 502 })),
    );
    renderAppAt('/clusters/r1/rabbitmq/bindings');
    expect(await screen.findByText(/No answer/)).toBeInTheDocument();
  });

  it('previews a delete and deletes it after a hold with the etag', async () => {
    const calls: string[] = [];
    serve(
      [BINDING],
      [
        http.delete(`${BASE}/b1\\:preview`, () => {
          calls.push('preview');
          return HttpResponse.json(PREVIEW);
        }),
        http.delete(`${BASE}/b1`, ({ request }) => {
          calls.push(`delete ${new URL(request.url).searchParams.get('etag')}`);
          return HttpResponse.json({ id: 'b1', identityBasis: 'CONFIGURATION', changes: [] });
        }),
      ],
    );
    const user = userEvent.setup();
    renderAppAt('/clusters/r1/rabbitmq/bindings');
    await user.click(await screen.findByRole('button', { name: /^Delete binding events to queue orders.in/ }));
    const dialog = await screen.findByRole('dialog', { name: 'Delete a binding' });
    expect(await within(dialog).findByText('Removes the binding.')).toBeInTheDocument();
    await holdButton(within(dialog).getByRole('button', { name: 'Delete 1 binding' }));
    await waitFor(() => expect(calls).toEqual(['preview', 'delete b-etag']));
  });

  it('says a delete is stale when the binding changed since the preview', async () => {
    serve(
      [BINDING],
      [
        http.delete(`${BASE}/b1\\:preview`, () => HttpResponse.json(PREVIEW)),
        http.delete(`${BASE}/b1`, () => HttpResponse.json({ title: 'Precondition failed' }, { status: 412 })),
      ],
    );
    const user = userEvent.setup();
    renderAppAt('/clusters/r1/rabbitmq/bindings');
    await user.click(await screen.findByRole('button', { name: /^Delete binding/ }));
    const dialog = await screen.findByRole('dialog', { name: 'Delete a binding' });
    await holdButton(await within(dialog).findByRole('button', { name: 'Delete 1 binding' }));
    expect(await within(dialog).findByText(/changed since the preview/)).toBeInTheDocument();
  });

  it('creates a binding after checking the fields and previewing it', async () => {
    const bodies: Record<string, unknown>[] = [];
    serve(
      [],
      [
        http.post(`${BASE}\\:preview`, async ({ request }) => {
          bodies.push((await request.json()) as never);
          return HttpResponse.json({ ...PREVIEW, effect: { ...PREVIEW.effect, detail: 'Adds the binding.' } });
        }),
        http.post(BASE, async ({ request }) => {
          bodies.push((await request.json()) as never);
          return HttpResponse.json({ id: 'b2', identityBasis: 'CONFIGURATION', changes: [] });
        }),
      ],
    );
    const user = userEvent.setup();
    renderAppAt('/clusters/r1/rabbitmq/bindings');
    await user.click(await screen.findByRole('button', { name: 'Create a binding' }));
    const form = await screen.findByRole('dialog', { name: 'Create a binding' });
    await user.click(within(form).getByRole('button', { name: 'Preview the binding' }));
    expect(await within(form).findByText('Enter the virtual host.')).toBeInTheDocument();
    await user.type(within(form).getByRole('textbox', { name: 'Virtual host' }), 'orders');
    await user.type(within(form).getByRole('textbox', { name: 'Source exchange' }), 'events');
    await user.type(within(form).getByRole('textbox', { name: 'Destination' }), 'orders.in');
    await user.type(within(form).getByRole('textbox', { name: 'Routing key' }), 'order.created');
    await user.click(within(form).getByRole('button', { name: 'Preview the binding' }));

    const dialog = await screen.findByRole('dialog', { name: 'Create a binding' });
    expect(await within(dialog).findByText('Adds the binding.')).toBeInTheDocument();
    await user.click(within(dialog).getByRole('button', { name: 'Create the binding' }));
    await waitFor(() => expect(bodies).toHaveLength(2));
    expect(bodies[0]).toMatchObject({
      vhost: 'orders',
      source: 'events',
      destination: 'orders.in',
      routingKey: 'order.created',
    });
    expect(bodies[1]).toMatchObject({ etag: 'b-etag' });
  });

  it('keeps the buttons, disabled, for someone who may not change routes', async () => {
    serve([BINDING], [], ['cluster:read', 'destination:read']);
    renderAppAt('/clusters/r1/rabbitmq/bindings');
    await screen.findByRole('button', { name: /^Delete binding/ });
    await waitFor(() => expect(screen.getByRole('button', { name: 'Create a binding' })).toBeDisabled());
  });
});
