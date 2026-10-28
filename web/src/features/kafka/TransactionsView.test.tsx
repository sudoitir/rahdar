import { describe, expect, it } from 'vitest';
import { http, HttpResponse } from 'msw';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

import { holdButton } from '../../test/hold.ts';
import { renderAppAt } from '../../test/render.tsx';
import { server } from '../../test/setup.ts';
import { mockKafkaCluster } from '../../test/kafkaCluster.ts';

const TXN = {
  id: 'payments-1',
  state: 'Ongoing',
  startedAt: new Date(Date.now() - 3_600_000).toISOString(),
  timeoutMs: 60_000,
  hanging: true,
  blocked: [{ topic: 'payments', partition: 2 }],
};

function serve(items: object[], handlers: Parameters<typeof server.use> = []) {
  mockKafkaCluster();
  server.use(
    http.get('*/api/v1/clusters/k1/kafka/transactions', () => HttpResponse.json({ items })),
    ...handlers,
  );
}

describe('the transactions', () => {
  it('flags a hanging transaction and the partitions it holds back', async () => {
    serve([TXN, { ...TXN, id: 'ok-1', hanging: false, blocked: [] }]);
    renderAppAt('/clusters/k1/kafka/transactions');
    expect(await screen.findByText('1 hanging transaction')).toBeInTheDocument();
    expect(screen.getByText('payments-2')).toBeInTheDocument();
    expect(screen.getByText('Ongoing, hanging')).toBeInTheDocument();
  });

  it('teaches what a transactional producer is when there are none', async () => {
    serve([]);
    renderAppAt('/clusters/k1/kafka/transactions');
    expect(await screen.findByText('No transactional producers')).toBeInTheDocument();
  });

  it('names what the abort does before it can be armed, then aborts after a hold with the etag', async () => {
    const calls: { path: string; body?: unknown }[] = [];
    serve(
      [TXN],
      [
        http.post('*/api/v1/clusters/k1/kafka/transactions/payments-1\\:abort-preview', () => {
          calls.push({ path: 'preview' });
          return HttpResponse.json({
            id: 'payments-1',
            state: 'Ongoing',
            effect: 'Aborts payments-1 and releases partition payments-2 for readers.',
            released: [{ topic: 'payments', partition: 2 }],
            abortable: true,
            etag: 't1',
          });
        }),
        http.post('*/api/v1/clusters/k1/kafka/transactions/payments-1\\:abort', async ({ request }) => {
          calls.push({ path: 'abort', body: await request.json() });
          return HttpResponse.json({});
        }),
      ],
    );
    const user = userEvent.setup();
    renderAppAt('/clusters/k1/kafka/transactions');
    await user.click(await screen.findByRole('button', { name: 'Abort payments-1' }));

    const dialog = await screen.findByRole('dialog', { name: 'Abort transaction payments-1' });
    expect(await within(dialog).findByText(/releases partition payments-2/)).toBeInTheDocument();
    expect(within(dialog).getByText(/The producer is fenced/)).toBeInTheDocument();
    await holdButton(within(dialog).getByRole('button', { name: 'Abort transaction payments-1' }));
    await waitFor(() => expect(calls.map((c) => c.path)).toEqual(['preview', 'abort']));
    expect(calls[1].body).toEqual({ etag: 't1' });
  });

  it('disables the abort with the reason when the transaction is not past its timeout', async () => {
    serve(
      [TXN],
      [
        http.post('*/api/v1/clusters/k1/kafka/transactions/payments-1\\:abort-preview', () =>
          HttpResponse.json({
            id: 'payments-1',
            state: 'Ongoing',
            effect: 'Aborts payments-1 and fences its producer.',
            released: [],
            abortable: false,
            etag: 't1',
          }),
        ),
      ],
    );
    const user = userEvent.setup();
    renderAppAt('/clusters/k1/kafka/transactions');
    await user.click(await screen.findByRole('button', { name: 'Abort payments-1' }));
    const dialog = await screen.findByRole('dialog', { name: 'Abort transaction payments-1' });
    expect(
      await within(dialog).findByText(/Only an open transaction past its timeout can be aborted/),
    ).toBeInTheDocument();
    expect(within(dialog).getByRole('button', { name: 'Abort transaction payments-1' })).toBeDisabled();
  });

  it('shows the refusal when the abort is refused as not hanging after the preview', async () => {
    serve(
      [TXN],
      [
        http.post('*/api/v1/clusters/k1/kafka/transactions/payments-1\\:abort-preview', () =>
          HttpResponse.json({
            id: 'payments-1',
            state: 'Ongoing',
            effect: 'Aborts payments-1.',
            released: [],
            abortable: true,
            etag: 't1',
          }),
        ),
        http.post('*/api/v1/clusters/k1/kafka/transactions/payments-1\\:abort', () =>
          HttpResponse.json(
            { type: 'transaction-not-hanging', title: 'Conflict', detail: 'payments-1 is no longer past its timeout.' },
            { status: 409 },
          ),
        ),
      ],
    );
    const user = userEvent.setup();
    renderAppAt('/clusters/k1/kafka/transactions');
    await user.click(await screen.findByRole('button', { name: 'Abort payments-1' }));
    const dialog = await screen.findByRole('dialog', { name: 'Abort transaction payments-1' });
    await holdButton(await within(dialog).findByRole('button', { name: 'Abort transaction payments-1' }));
    expect((await within(dialog).findAllByText('payments-1 is no longer past its timeout.')).length).toBeGreaterThan(0);
  });

  it('keeps Abort visible but disabled, with the reason, for someone without the permission', async () => {
    mockKafkaCluster(['cluster:read', 'transaction:read']);
    server.use(http.get('*/api/v1/clusters/k1/kafka/transactions', () => HttpResponse.json({ items: [TXN] })));
    const user = userEvent.setup();
    renderAppAt('/clusters/k1/kafka/transactions');
    await user.click(await screen.findByRole('button', { name: 'Why aborting payments-1 is unavailable' }));
    expect(await screen.findByText(/You do not have the "Abort transactions" permission/)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Abort payments-1' })).toBeDisabled();
  });
});
