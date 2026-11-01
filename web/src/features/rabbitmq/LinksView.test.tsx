import { describe, expect, it } from 'vitest';
import { http, HttpResponse } from 'msw';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

import { holdButton } from '../../test/hold.ts';
import { mockRabbitMqCluster } from '../../test/rabbitmqCluster.ts';
import { renderAppAt } from '../../test/render.tsx';
import { server } from '../../test/setup.ts';

const SHOVEL = {
  id: 's1',
  vhost: 'orders',
  name: 'copy-orders',
  state: 'running',
  source: 'queue orders.in',
  destination: 'queue backup',
  managedBy: null,
};
const STATIC = { ...SHOVEL, id: 's2', name: 'from-config', managedBy: 'configuration' };
const LINK = {
  vhost: 'orders',
  upstream: 'eu',
  type: 'exchange',
  name: 'events',
  status: 'running',
  source: 'amqp://eu',
};

const BASE = '*/api/v1/clusters/r1/rabbitmq';

function serve(shovels: object, links: object, handlers: Parameters<typeof server.use> = []) {
  mockRabbitMqCluster();
  server.use(
    http.get(`${BASE}/shovels`, () => HttpResponse.json(shovels)),
    http.get(`${BASE}/federation-links`, () => HttpResponse.json(links)),
    ...handlers,
  );
}

const page = (items: object[]) => ({ available: true, items, next: null });

describe('the shovels and federation links', () => {
  it('lists both, and refuses to delete a shovel the configuration defines, with the reason', async () => {
    serve(page([SHOVEL, STATIC]), page([LINK]));
    renderAppAt('/clusters/r1/rabbitmq/links');
    const shovels = await screen.findByRole('table', { name: 'Shovels' });
    expect(await within(shovels).findByText('copy-orders')).toBeInTheDocument();
    expect(within(shovels).getByRole('button', { name: 'Delete shovel copy-orders' })).toBeInTheDocument();
    expect(within(shovels).queryByRole('button', { name: 'Delete shovel from-config' })).not.toBeInTheDocument();
    expect(within(shovels).getByText(/defined in the node configuration/)).toBeInTheDocument();
    const links = await screen.findByRole('table', { name: 'Federation links' });
    expect(await within(links).findByText('eu')).toBeInTheDocument();
  });

  it('says what to enable when a plugin is missing, instead of an empty list', async () => {
    serve(
      { available: false, remedy: 'Enable rabbitmq_shovel_management on every node.', items: [], next: null },
      { available: false, remedy: null, items: [], next: null },
    );
    renderAppAt('/clusters/r1/rabbitmq/links');
    expect(await screen.findByText('Shovels are not available on this cluster')).toBeInTheDocument();
    expect(screen.getByText('Enable rabbitmq_shovel_management on every node.')).toBeInTheDocument();
    expect(await screen.findByText('Federation links are not available on this cluster')).toBeInTheDocument();
  });

  it('says there is none when the plugins are on and nothing is defined', async () => {
    serve(page([]), page([]));
    renderAppAt('/clusters/r1/rabbitmq/links');
    expect(await screen.findByText('No shovels')).toBeInTheDocument();
    expect(await screen.findByText('No federation links')).toBeInTheDocument();
  });

  it('shows the error of a failed read', async () => {
    mockRabbitMqCluster();
    server.use(
      http.get(`${BASE}/shovels`, () =>
        HttpResponse.json({ title: 'Bad gateway', detail: 'No answer.' }, { status: 502 }),
      ),
      http.get(`${BASE}/federation-links`, () => HttpResponse.json(page([]))),
    );
    renderAppAt('/clusters/r1/rabbitmq/links');
    expect(await screen.findByText(/No answer/)).toBeInTheDocument();
  });

  it('previews a shovel delete and deletes it after a hold', async () => {
    const calls: string[] = [];
    serve(page([SHOVEL]), page([]), [
      http.delete(`${BASE}/shovels/s1\\:preview`, () => {
        calls.push('preview');
        return HttpResponse.json({
          effect: { count: 1, unit: 'changes', detail: 'Stops and removes the shovel.', changes: [], lines: [] },
          etag: 's-etag',
          identityBasis: 'CONFIGURATION',
        });
      }),
      http.delete(`${BASE}/shovels/s1`, ({ request }) => {
        calls.push(`delete ${new URL(request.url).searchParams.get('etag')}`);
        return HttpResponse.json({ id: 's1', identityBasis: 'CONFIGURATION', changes: [] });
      }),
    ]);
    const user = userEvent.setup();
    renderAppAt('/clusters/r1/rabbitmq/links');
    await user.click(await screen.findByRole('button', { name: 'Delete shovel copy-orders' }));
    const dialog = await screen.findByRole('dialog', { name: 'Delete a shovel' });
    expect(await within(dialog).findByText('Stops and removes the shovel.')).toBeInTheDocument();
    await holdButton(within(dialog).getByRole('button', { name: 'Delete 1 shovel' }));
    await waitFor(() => expect(calls).toEqual(['preview', 'delete s-etag']));
  });

  it("shows the driver's refusal of a delete where the control sits", async () => {
    serve(page([SHOVEL]), page([]), [
      http.delete(`${BASE}/shovels/s1\\:preview`, () =>
        HttpResponse.json({ title: 'Conflict', detail: 'A static shovel cannot be deleted.' }, { status: 409 }),
      ),
    ]);
    const user = userEvent.setup();
    renderAppAt('/clusters/r1/rabbitmq/links');
    await user.click(await screen.findByRole('button', { name: 'Delete shovel copy-orders' }));
    expect(await screen.findByText(/A static shovel cannot be deleted/)).toBeInTheDocument();
  });
});
