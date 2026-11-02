import { describe, expect, it, vi } from 'vitest';
import { http, HttpResponse } from 'msw';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

import { holdButton } from '../../test/hold.ts';
import { mockRabbitMqCluster } from '../../test/rabbitmqCluster.ts';
import { renderAppAt } from '../../test/render.tsx';
import { server } from '../../test/setup.ts';
import { notify } from '../../ui/notify.ts';

const VHOST = {
  name: 'orders',
  description: 'Order flow',
  tags: ['production'],
  defaultQueueType: 'quorum',
  queues: 2,
  messages: 17,
};

const PREVIEW = {
  effect: {
    count: 17,
    unit: 'messages',
    detail: 'Deletes the virtual host and everything in it.',
    changes: [],
    lines: ['queue orders.in: 12 messages', 'queue orders.out: 5 messages'],
  },
  etag: 'e1',
  identityBasis: 'CONFIGURATION',
};

const BASE = '*/api/v1/clusters/r1/rabbitmq/vhosts';

function serve(items: object[], handlers: Parameters<typeof server.use> = [], permissions?: string[]) {
  mockRabbitMqCluster(permissions);
  server.use(
    http.get(BASE, () => HttpResponse.json({ items, countsComplete: true, next: null })),
    ...handlers,
  );
}

describe('the virtual hosts', () => {
  it('lists them with their queues and messages', async () => {
    serve([VHOST]);
    renderAppAt('/clusters/r1/rabbitmq/vhosts');
    const grid = await screen.findByRole('grid', { name: 'Virtual hosts' });
    expect(await within(grid).findByText('orders')).toBeInTheDocument();
    expect(within(grid).getByText('17')).toBeInTheDocument();
  });

  it('says so when there is none, and when the list cannot be read', async () => {
    serve([]);
    renderAppAt('/clusters/r1/rabbitmq/vhosts');
    expect(await screen.findByText('No virtual hosts')).toBeInTheDocument();
  });

  it('shows the error of a failed read', async () => {
    mockRabbitMqCluster();
    server.use(
      http.get(BASE, () => HttpResponse.json({ title: 'Bad gateway', detail: 'No answer.' }, { status: 502 })),
    );
    renderAppAt('/clusters/r1/rabbitmq/vhosts');
    expect(await screen.findByText(/No answer/)).toBeInTheDocument();
  });

  it('warns that the counts are lower bounds when the read was cut short', async () => {
    mockRabbitMqCluster();
    server.use(http.get(BASE, () => HttpResponse.json({ items: [VHOST], countsComplete: false, next: null })));
    renderAppAt('/clusters/r1/rabbitmq/vhosts');
    expect(await screen.findByText('The counts are not complete')).toBeInTheDocument();
  });

  it('previews a delete naming every queue, and deletes after a hold with the etag', async () => {
    const calls: string[] = [];
    serve(
      [VHOST],
      [
        http.delete(`${BASE}/orders\\:preview`, () => {
          calls.push('preview');
          return HttpResponse.json(PREVIEW);
        }),
        http.delete(`${BASE}/orders`, ({ request }) => {
          calls.push(`delete ${new URL(request.url).searchParams.get('etag')}`);
          return HttpResponse.json({ id: 'orders', identityBasis: 'CONFIGURATION', changes: [] });
        }),
      ],
    );
    const user = userEvent.setup();
    renderAppAt('/clusters/r1/rabbitmq/vhosts');
    await user.click(await screen.findByRole('button', { name: 'Delete virtual host orders' }));

    const dialog = await screen.findByRole('dialog', { name: 'Delete virtual host orders' });
    expect(await within(dialog).findByText('queue orders.in: 12 messages')).toBeInTheDocument();
    expect(within(dialog).getByText('queue orders.out: 5 messages')).toBeInTheDocument();
    expect(within(dialog).getByText('A recreated resource is not told apart')).toBeInTheDocument();
    const confirm = within(dialog).getByRole('button', { name: 'Delete virtual host with 2 queues' });
    await user.click(confirm);
    expect(calls).toEqual(['preview']);
    await holdButton(confirm);
    await waitFor(() => expect(calls).toEqual(['preview', 'delete e1']));
  });

  it('says the preview is stale when the host changed since', async () => {
    serve(
      [VHOST],
      [
        http.delete(`${BASE}/orders\\:preview`, () => HttpResponse.json(PREVIEW)),
        http.delete(`${BASE}/orders`, () =>
          HttpResponse.json({ title: 'Precondition failed', detail: 'It changed.' }, { status: 412 }),
        ),
      ],
    );
    const user = userEvent.setup();
    renderAppAt('/clusters/r1/rabbitmq/vhosts');
    await user.click(await screen.findByRole('button', { name: 'Delete virtual host orders' }));
    const dialog = await screen.findByRole('dialog', { name: 'Delete virtual host orders' });
    await holdButton(await within(dialog).findByRole('button', { name: 'Delete virtual host with 2 queues' }));
    expect(await within(dialog).findByText(/changed since the preview/)).toBeInTheDocument();
    expect(within(dialog).getByRole('button', { name: 'Delete virtual host with 2 queues' })).toBeDisabled();
  });

  it('closes the dialog and says the delete waits for approval when the gate holds it', async () => {
    const held = vi.spyOn(notify, 'held');
    serve(
      [VHOST],
      [
        http.delete(`${BASE}/orders\\:preview`, () => HttpResponse.json(PREVIEW)),
        http.delete(`${BASE}/orders`, () =>
          HttpResponse.json(
            { heldOperation: { summary: 'Delete virtual host orders', expiresAt: '', link: '' } },
            { status: 202, headers: { 'X-Rahdar-Held-Operation': 'h-1' } },
          ),
        ),
      ],
    );
    const user = userEvent.setup();
    renderAppAt('/clusters/r1/rabbitmq/vhosts');
    await user.click(await screen.findByRole('button', { name: 'Delete virtual host orders' }));
    const dialog = await screen.findByRole('dialog', { name: 'Delete virtual host orders' });
    await holdButton(await within(dialog).findByRole('button', { name: 'Delete virtual host with 2 queues' }));
    await waitFor(() => expect(held).toHaveBeenCalledWith(expect.objectContaining({ id: 'h-1' })));
    await waitFor(() =>
      expect(screen.queryByRole('dialog', { name: 'Delete virtual host orders' })).not.toBeInTheDocument(),
    );
  });

  it('shows a refused preview where the control sits', async () => {
    serve(
      [VHOST],
      [
        http.delete(`${BASE}/orders\\:preview`, () =>
          HttpResponse.json(
            { title: 'Conflict', detail: 'The default virtual host cannot be deleted.' },
            { status: 409 },
          ),
        ),
      ],
    );
    const user = userEvent.setup();
    renderAppAt('/clusters/r1/rabbitmq/vhosts');
    await user.click(await screen.findByRole('button', { name: 'Delete virtual host orders' }));
    expect(await screen.findByText(/The default virtual host cannot be deleted/)).toBeInTheDocument();
  });

  it('creates a virtual host after a preview, without a hold', async () => {
    const bodies: Record<string, unknown>[] = [];
    serve(
      [],
      [
        http.post(`${BASE}\\:preview`, async ({ request }) => {
          bodies.push((await request.json()) as never);
          return HttpResponse.json({ ...PREVIEW, effect: { ...PREVIEW.effect, count: 0, lines: [] } });
        }),
        http.post(BASE, async ({ request }) => {
          bodies.push((await request.json()) as never);
          return HttpResponse.json({ id: 'billing', identityBasis: 'CONFIGURATION', changes: [] });
        }),
      ],
    );
    const user = userEvent.setup();
    renderAppAt('/clusters/r1/rabbitmq/vhosts');
    await user.click(await screen.findByRole('button', { name: 'Create a virtual host' }));
    const form = await screen.findByRole('dialog', { name: 'Create a virtual host' });
    await user.click(within(form).getByRole('button', { name: 'Preview the change' }));
    expect(await within(form).findByText('Enter the name of the virtual host.')).toBeInTheDocument();
    await user.type(within(form).getByRole('textbox', { name: 'Name' }), 'billing');
    await user.type(within(form).getByRole('textbox', { name: 'Tags' }), 'eu, prod');
    await user.click(within(form).getByRole('button', { name: 'Preview the change' }));

    const dialog = await screen.findByRole('dialog', { name: 'Create virtual host billing' });
    await user.click(within(dialog).getByRole('button', { name: 'Create the virtual host' }));
    await waitFor(() => expect(bodies).toHaveLength(2));
    expect(bodies[0]).toMatchObject({ name: 'billing', tags: ['eu', 'prod'] });
    expect(bodies[1]).toMatchObject({ name: 'billing', etag: 'e1' });
  });

  it('keeps the buttons, disabled with the reason, for someone without the permission', async () => {
    serve([VHOST], [], ['cluster:read', 'namespace:read']);
    const user = userEvent.setup();
    renderAppAt('/clusters/r1/rabbitmq/vhosts');
    await screen.findByRole('button', { name: 'Delete virtual host orders' });
    await waitFor(() => expect(screen.getByRole('button', { name: 'Create a virtual host' })).toBeDisabled());
    await user.click(await screen.findByRole('button', { name: 'Why creating a virtual host is unavailable' }));
    expect(await screen.findByText(/You do not have the "Change virtual hosts" permission/)).toBeInTheDocument();
  });
});
