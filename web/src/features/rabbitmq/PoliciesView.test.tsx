import { describe, expect, it } from 'vitest';
import { http, HttpResponse } from 'msw';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

import { holdButton } from '../../test/hold.ts';
import { mockRabbitMqCluster } from '../../test/rabbitmqCluster.ts';
import { renderAppAt } from '../../test/render.tsx';
import { server } from '../../test/setup.ts';

const POLICY = {
  id: 'orders/limit',
  vhost: 'orders',
  name: 'limit',
  pattern: '^orders\\.',
  applyTo: 'quorum_queues',
  priority: 5,
  definition: { 'max-length': '1000' },
  operator: false,
  matches: 2,
};

const PREVIEW = {
  effect: { count: 2, unit: 'resources', detail: 'The policy reaches 2 resources.', changes: [], lines: [] },
  etag: 'p-etag',
  identityBasis: 'CONFIGURATION',
  partial: false,
  matches: [
    { kind: 'quorum_queue', name: 'orders.in' },
    { kind: 'quorum_queue', name: 'orders.out', shadowedBy: 'stricter' },
  ],
};

const BASE = '*/api/v1/clusters/r1/rabbitmq/policies';

function serve(items: object[], handlers: Parameters<typeof server.use> = [], permissions?: string[]) {
  mockRabbitMqCluster(permissions);
  server.use(
    http.get(BASE, () => HttpResponse.json({ items, next: null })),
    ...handlers,
  );
}

describe('the policies', () => {
  it('lists them with how many names each matches, and leaves operator policies unchanged', async () => {
    serve([POLICY, { ...POLICY, id: 'orders/op', name: 'op', operator: true }]);
    renderAppAt('/clusters/r1/rabbitmq/policies');
    const grid = await screen.findByRole('grid', { name: 'Policies' });
    expect(await within(grid).findByText('limit')).toBeInTheDocument();
    expect(within(grid).getByText('Operator policy')).toBeInTheDocument();
    expect(within(grid).getByRole('button', { name: 'Clear policy limit' })).toBeInTheDocument();
    expect(within(grid).queryByRole('button', { name: 'Clear policy op' })).not.toBeInTheDocument();
  });

  it('says there is none', async () => {
    serve([]);
    renderAppAt('/clusters/r1/rabbitmq/policies');
    expect(await screen.findByText('No policies')).toBeInTheDocument();
  });

  it('shows the error of a failed read', async () => {
    mockRabbitMqCluster();
    server.use(
      http.get(BASE, () => HttpResponse.json({ title: 'Bad gateway', detail: 'No answer.' }, { status: 502 })),
    );
    renderAppAt('/clusters/r1/rabbitmq/policies');
    expect(await screen.findByText(/No answer/)).toBeInTheDocument();
  });

  it('previews a clear with the names it reaches and those a higher priority wins, then clears after a hold', async () => {
    const calls: string[] = [];
    serve(
      [POLICY],
      [
        http.delete(`${BASE}/orders/limit\\:preview`, () => {
          calls.push('preview');
          return HttpResponse.json(PREVIEW);
        }),
        http.delete(`${BASE}/orders/limit`, ({ request }) => {
          calls.push(`clear ${new URL(request.url).searchParams.get('etag')}`);
          return HttpResponse.json({ id: 'orders/limit', identityBasis: 'CONFIGURATION', changes: [] });
        }),
      ],
    );
    const user = userEvent.setup();
    renderAppAt('/clusters/r1/rabbitmq/policies');
    await user.click(await screen.findByRole('button', { name: 'Clear policy limit' }));
    const dialog = await screen.findByRole('dialog', { name: 'Clear policy limit' });
    expect(await within(dialog).findByText('orders.in')).toBeInTheDocument();
    expect(within(dialog).getByText('Won by stricter')).toBeInTheDocument();
    expect(within(dialog).getByText(/as Rahdar matches names/)).toBeInTheDocument();
    expect(within(dialog).getByText('1 won by a policy of higher priority')).toBeInTheDocument();
    await holdButton(within(dialog).getByRole('button', { name: 'Clear 1 policy' }));
    await waitFor(() => expect(calls).toEqual(['preview', 'clear p-etag']));
  });

  it('says a clear is stale when the policy changed since the preview', async () => {
    serve(
      [POLICY],
      [
        http.delete(`${BASE}/orders/limit\\:preview`, () => HttpResponse.json(PREVIEW)),
        http.delete(`${BASE}/orders/limit`, () => HttpResponse.json({ title: 'Precondition failed' }, { status: 412 })),
      ],
    );
    const user = userEvent.setup();
    renderAppAt('/clusters/r1/rabbitmq/policies');
    await user.click(await screen.findByRole('button', { name: 'Clear policy limit' }));
    const dialog = await screen.findByRole('dialog', { name: 'Clear policy limit' });
    await holdButton(await within(dialog).findByRole('button', { name: 'Clear 1 policy' }));
    expect(await within(dialog).findByText(/changed since the preview/)).toBeInTheDocument();
  });

  it('sets a policy after checking the JSON of each setting and previewing the matches', async () => {
    const bodies: Record<string, unknown>[] = [];
    serve(
      [],
      [
        http.put(`${BASE}/orders/limit\\:preview`, async ({ request }) => {
          bodies.push((await request.json()) as never);
          return HttpResponse.json({ ...PREVIEW, partial: true });
        }),
        http.put(`${BASE}/orders/limit`, async ({ request }) => {
          bodies.push((await request.json()) as never);
          return HttpResponse.json({ id: 'orders/limit', identityBasis: 'CONFIGURATION', changes: [] });
        }),
      ],
    );
    const user = userEvent.setup();
    renderAppAt('/clusters/r1/rabbitmq/policies');
    await user.click(await screen.findByRole('button', { name: 'Set a policy' }));
    const form = await screen.findByRole('dialog', { name: 'Set a policy' });
    await user.type(within(form).getByRole('textbox', { name: 'Virtual host' }), 'orders');
    await user.type(within(form).getByRole('textbox', { name: 'Name' }), 'limit');
    await user.type(within(form).getByRole('textbox', { name: 'Pattern' }), '^orders');
    await user.type(within(form).getByRole('textbox', { name: 'Setting' }), 'max-length');
    await user.type(within(form).getByRole('textbox', { name: 'Value (JSON)' }), 'lots');
    await user.click(within(form).getByRole('button', { name: 'Preview the policy' }));
    expect(await within(form).findByText(/Write the value as JSON/)).toBeInTheDocument();
    await user.clear(within(form).getByRole('textbox', { name: 'Value (JSON)' }));
    await user.type(within(form).getByRole('textbox', { name: 'Value (JSON)' }), '1000');
    await user.click(within(form).getByRole('button', { name: 'Preview the policy' }));

    const dialog = await screen.findByRole('dialog', { name: 'Set policy limit' });
    expect(await within(dialog).findByText('The matches are not complete')).toBeInTheDocument();
    await user.click(within(dialog).getByRole('button', { name: 'Set the policy' }));
    await waitFor(() => expect(bodies).toHaveLength(2));
    expect(bodies[0]).toMatchObject({ pattern: '^orders', definition: { 'max-length': '1000' } });
    expect(bodies[1]).toMatchObject({ etag: 'p-etag' });
  });

  it('keeps the buttons, disabled, for someone without the policy permission', async () => {
    serve([POLICY], [], ['cluster:read', 'policy:read']);
    renderAppAt('/clusters/r1/rabbitmq/policies');
    await screen.findByRole('button', { name: 'Clear policy limit' });
    await waitFor(() => expect(screen.getByRole('button', { name: 'Set a policy' })).toBeDisabled());
  });
});
