import { describe, expect, it } from 'vitest';
import { http, HttpResponse } from 'msw';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

import { holdButton } from '../../test/hold.ts';
import { mockRabbitMqCluster } from '../../test/rabbitmqCluster.ts';
import { renderAppAt } from '../../test/render.tsx';
import { server } from '../../test/setup.ts';

const PERMISSION = {
  vhost: 'orders',
  user: 'app',
  configure: '',
  write: '^orders\\.',
  read: '.*',
  topic: [{ exchange: 'events', write: '.*', read: '.*' }],
};

const PREVIEW = {
  effect: {
    count: 1,
    unit: 'changes',
    detail: 'Changes what app may do on orders.',
    changes: [{ key: 'write', before: '^orders\\.', after: '.*' }],
    lines: [],
  },
  etag: 'x-etag',
  identityBasis: 'CONFIGURATION',
};

const BASE = '*/api/v1/clusters/r1/rabbitmq';

function serve(items: object[], handlers: Parameters<typeof server.use> = [], permissions?: string[]) {
  mockRabbitMqCluster(permissions);
  server.use(
    http.get(`${BASE}/permissions`, () => HttpResponse.json({ items, next: null })),
    http.get(`${BASE}/users`, () => HttpResponse.json({ items: [{ name: 'app', tags: [] }], next: null })),
    ...handlers,
  );
}

describe('the permissions', () => {
  it('lists each user on each virtual host with the topic permissions in words', async () => {
    serve([PERMISSION]);
    renderAppAt('/clusters/r1/rabbitmq/permissions');
    const grid = await screen.findByRole('grid', { name: 'Permissions' });
    expect(await within(grid).findByText('app')).toBeInTheDocument();
    expect(within(grid).getByText('events: write .*, read .*')).toBeInTheDocument();
  });

  it('says there is none', async () => {
    serve([]);
    renderAppAt('/clusters/r1/rabbitmq/permissions');
    expect(await screen.findByText('No permissions')).toBeInTheDocument();
  });

  it('shows the error of a failed read', async () => {
    mockRabbitMqCluster();
    server.use(
      http.get(`${BASE}/permissions`, () =>
        HttpResponse.json({ title: 'Bad gateway', detail: 'No answer.' }, { status: 502 }),
      ),
    );
    renderAppAt('/clusters/r1/rabbitmq/permissions');
    expect(await screen.findByText(/No answer/)).toBeInTheDocument();
  });

  it('previews a change with the rights before and after and applies it', async () => {
    const bodies: Record<string, unknown>[] = [];
    serve(
      [PERMISSION],
      [
        http.put(`${BASE}/permissions/orders/app\\:preview`, async ({ request }) => {
          bodies.push((await request.json()) as never);
          return HttpResponse.json(PREVIEW);
        }),
        http.put(`${BASE}/permissions/orders/app`, async ({ request }) => {
          bodies.push((await request.json()) as never);
          return HttpResponse.json({ id: 'orders/app', identityBasis: 'CONFIGURATION', changes: [] });
        }),
      ],
    );
    const user = userEvent.setup();
    renderAppAt('/clusters/r1/rabbitmq/permissions');
    await user.click(await screen.findByRole('button', { name: 'Edit permissions of app on orders' }));
    const form = await screen.findByRole('dialog', { name: 'Edit permissions of app' });
    await user.clear(within(form).getByRole('textbox', { name: 'Write' }));
    await user.type(within(form).getByRole('textbox', { name: 'Write' }), '.*');
    await user.click(within(form).getByRole('button', { name: 'Preview the permissions' }));

    const dialog = await screen.findByRole('dialog', { name: 'Set permissions of app' });
    expect(await within(dialog).findByText('Changes what app may do on orders.')).toBeInTheDocument();
    expect(within(dialog).getByRole('table', { name: 'Settings before and after' })).toBeInTheDocument();
    await user.click(within(dialog).getByRole('button', { name: 'Set the permissions' }));
    await waitFor(() => expect(bodies).toHaveLength(2));
    expect(bodies[0]).toMatchObject({ write: '.*', read: '.*' });
    expect(bodies[1]).toMatchObject({ etag: 'x-etag' });
  });

  it('clears after a hold, and says it is stale when the rights changed since', async () => {
    const calls: string[] = [];
    serve(
      [PERMISSION],
      [
        http.delete(`${BASE}/permissions/orders/app\\:preview`, () => HttpResponse.json(PREVIEW)),
        http.delete(`${BASE}/permissions/orders/app`, ({ request }) => {
          calls.push(new URL(request.url).searchParams.get('etag') ?? '');
          return HttpResponse.json({ title: 'Precondition failed' }, { status: 412 });
        }),
      ],
    );
    const user = userEvent.setup();
    renderAppAt('/clusters/r1/rabbitmq/permissions');
    await user.click(await screen.findByRole('button', { name: 'Clear permissions of app on orders' }));
    const dialog = await screen.findByRole('dialog', { name: 'Clear permissions of app' });
    await holdButton(await within(dialog).findByRole('button', { name: 'Clear 1 permission' }));
    expect(await within(dialog).findByText(/changed since the preview/)).toBeInTheDocument();
    expect(calls).toEqual(['x-etag']);
  });

  it('keeps the buttons, disabled, for someone who may only read access rules', async () => {
    serve([PERMISSION], [], ['cluster:read', 'access-rule:read']);
    renderAppAt('/clusters/r1/rabbitmq/permissions');
    await screen.findByRole('button', { name: 'Edit permissions of app on orders' });
    await waitFor(() => expect(screen.getByRole('button', { name: 'Set permissions' })).toBeDisabled());
  });
});
