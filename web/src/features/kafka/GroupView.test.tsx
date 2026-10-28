import { describe, expect, it } from 'vitest';
import { http, HttpResponse } from 'msw';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

import { holdButton } from '../../test/hold.ts';
import { renderAppAt } from '../../test/render.tsx';
import { server } from '../../test/setup.ts';
import { mockKafkaCluster } from '../../test/kafkaCluster.ts';

function detail(over: object = {}) {
  return {
    id: 'billing',
    type: 'classic',
    state: 'EMPTY',
    readOnly: null,
    members: [],
    sampledAt: new Date().toISOString(),
    topics: [
      {
        topic: 'orders',
        deleted: false,
        partitions: [
          { partition: 0, committed: 10, end: 100, lag: 90, reason: null },
          { partition: 1, committed: null, end: 50, lag: null, reason: 'No offset has been committed here.' },
        ],
      },
      {
        topic: 'old-orders',
        deleted: true,
        partitions: [{ partition: 0, committed: 5, end: 5, lag: 0, reason: null }],
      },
    ],
    ...over,
  };
}

const PREVIEW = {
  partitions: [
    { topic: 'orders', partition: 0, current: 10, next: 0, skipped: 0, reread: 10, note: null },
    { topic: 'orders', partition: 1, current: null, next: 0, skipped: 0, reread: 0, note: 'Nothing was committed.' },
  ],
  members: [],
  blocked: null,
  etag: 'etag-1',
};

function serve(group: object, handlers: Parameters<typeof server.use> = []) {
  mockKafkaCluster();
  server.use(
    http.get('*/api/v1/clusters/k1/kafka/groups/billing', () => HttpResponse.json(group)),
    ...handlers,
  );
}

async function openReset(user: ReturnType<typeof userEvent.setup>) {
  const trigger = await screen.findByRole('button', { name: 'Reset offsets…' });
  await waitFor(() => expect(trigger).toBeEnabled());
  await user.click(trigger);
  return screen.findByRole('dialog', { name: 'Reset the offsets of billing' });
}

describe('a consumer group', () => {
  it('shows every partition, a lag that could not be read with its reason, and a deleted topic as gone', async () => {
    serve(detail());
    renderAppAt('/clusters/k1/kafka/groups/billing');

    const table = await screen.findByRole('table', { name: 'orders partitions' });
    expect(within(table).getByText('Unavailable')).toBeInTheDocument();
    expect(within(table).getByText('No offset has been committed here.')).toBeInTheDocument();
    expect(screen.getByText('This topic was deleted')).toBeInTheDocument();
    expect(screen.getByText('No member is connected')).toBeInTheDocument();
  });

  it('previews the reset, then resets against the preview after a hold', async () => {
    const calls: { path: string; body: Record<string, unknown> }[] = [];
    serve(detail(), [
      http.post('*/api/v1/clusters/k1/kafka/groups/billing/offsets\\:preview', async ({ request }) => {
        calls.push({ path: 'preview', body: (await request.json()) as never });
        return HttpResponse.json(PREVIEW);
      }),
      http.post('*/api/v1/clusters/k1/kafka/groups/billing/offsets\\:reset', async ({ request }) => {
        calls.push({ path: 'reset', body: (await request.json()) as never });
        return HttpResponse.json({});
      }),
    ]);
    const user = userEvent.setup();
    renderAppAt('/clusters/k1/kafka/groups/billing');
    const dialog = await openReset(user);

    expect(within(dialog).getByRole('button', { name: 'Reset offsets' })).toBeDisabled();
    expect(within(dialog).getByText(/Preview the reset first/)).toBeInTheDocument();

    await user.click(within(dialog).getByRole('button', { name: 'Preview the reset' }));
    const table = await within(dialog).findByRole('table', { name: 'Partitions the reset would move' });
    expect(within(table).getByText('Nothing was committed.')).toBeInTheDocument();
    expect(
      within(dialog).getByText(/Moves 2 partitions: skips 0 records and reads 10 records again/),
    ).toBeInTheDocument();

    await holdButton(within(dialog).getByRole('button', { name: 'Reset 2 partitions' }));
    await waitFor(() => expect(calls.map((c) => c.path)).toEqual(['preview', 'reset']));
    expect(calls[1].body).toMatchObject({ to: 'earliest', etag: 'etag-1' });
  });

  it('names the members that still hold the group and leaves the reset disabled with the reason', async () => {
    serve(detail({ state: 'STABLE' }), [
      http.post('*/api/v1/clusters/k1/kafka/groups/billing/offsets\\:preview', () =>
        HttpResponse.json({
          ...PREVIEW,
          members: ['consumer-1', 'consumer-2'],
          blocked: 'The group has 2 members. Stop them before resetting.',
        }),
      ),
    ]);
    const user = userEvent.setup();
    renderAppAt('/clusters/k1/kafka/groups/billing');
    const dialog = await openReset(user);
    await user.click(within(dialog).getByRole('button', { name: 'Preview the reset' }));

    expect(await within(dialog).findByText('This group cannot be reset right now')).toBeInTheDocument();
    expect(within(dialog).getByText('Members: consumer-1, consumer-2.')).toBeInTheDocument();
    expect(within(dialog).getByRole('button', { name: /Reset 2 partitions/ })).toBeDisabled();
    expect(within(dialog).getAllByText(/Stop them before resetting/).length).toBeGreaterThan(0);
  });

  it('asks for a new preview when the group changed since, and states an offset out of range', async () => {
    let status = 412;
    serve(detail(), [
      http.post('*/api/v1/clusters/k1/kafka/groups/billing/offsets\\:preview', () => HttpResponse.json(PREVIEW)),
      http.post('*/api/v1/clusters/k1/kafka/groups/billing/offsets\\:reset', () =>
        status === 412
          ? HttpResponse.json({ title: 'Precondition failed', detail: 'The group changed.' }, { status: 412 })
          : HttpResponse.json(
              {
                title: 'Bad request',
                detail: 'Some offsets are out of range.',
                errors: [{ field: 'orders-0', message: 'beyond the end (100)' }],
              },
              { status: 400 },
            ),
      ),
    ]);
    const user = userEvent.setup();
    renderAppAt('/clusters/k1/kafka/groups/billing');
    const dialog = await openReset(user);
    await user.click(within(dialog).getByRole('button', { name: 'Preview the reset' }));
    await holdButton(await within(dialog).findByRole('button', { name: 'Reset 2 partitions' }));
    expect(await within(dialog).findByText('The group changed since the preview')).toBeInTheDocument();
    expect(within(dialog).getAllByText(/Preview it again/).length).toBeGreaterThan(0);

    status = 400;
    await user.click(within(dialog).getByRole('button', { name: 'Preview the reset' }));
    await holdButton(await within(dialog).findByRole('button', { name: 'Reset 2 partitions' }));
    expect(await within(dialog).findByText('The offsets are out of range')).toBeInTheDocument();
    expect(within(dialog).getByText('orders-0: beyond the end (100)')).toBeInTheDocument();
  });

  it('checks the target value beside its field, and Escape returns focus to the button', async () => {
    serve(detail());
    const user = userEvent.setup();
    renderAppAt('/clusters/k1/kafka/groups/billing');
    const trigger = await screen.findByRole('button', { name: 'Reset offsets…' });
    const dialog = await openReset(user);

    await user.click(within(dialog).getByRole('combobox', { name: 'Move to' }));
    await user.click(await screen.findByRole('option', { name: 'An offset' }));
    await user.click(within(dialog).getByRole('button', { name: 'Preview the reset' }));
    expect(await within(dialog).findByText('Enter the offset as a whole number, such as 120.')).toBeInTheDocument();

    await user.keyboard('{Escape}');
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
    expect(trigger).toHaveFocus();
  });

  it('keeps the button, disabled with the reason, for a group whose offsets cannot be changed', async () => {
    serve(detail({ type: 'share', readOnly: 'A share group has no offsets to reset.' }));
    const user = userEvent.setup();
    renderAppAt('/clusters/k1/kafka/groups/billing');

    await screen.findByRole('button', { name: 'Reset offsets…' });
    await user.click(await screen.findByRole('button', { name: "Why resetting this group's offsets is unavailable" }));
    expect(await screen.findAllByText('A share group has no offsets to reset.')).not.toHaveLength(0);
  });

  it('keeps the button, disabled with the reason, for someone without the permission', async () => {
    mockKafkaCluster(['cluster:read', 'subscription:read']);
    server.use(http.get('*/api/v1/clusters/k1/kafka/groups/billing', () => HttpResponse.json(detail())));
    const user = userEvent.setup();
    renderAppAt('/clusters/k1/kafka/groups/billing');

    await screen.findByRole('button', { name: 'Reset offsets…' });
    await user.click(await screen.findByRole('button', { name: "Why resetting this group's offsets is unavailable" }));
    expect(await screen.findByText(/You do not have the "Reset offsets" permission/)).toBeInTheDocument();
  });
});
