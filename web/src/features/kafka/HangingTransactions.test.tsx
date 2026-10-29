import { describe, expect, it } from 'vitest';
import { http, HttpResponse } from 'msw';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

import { holdButton } from '../../test/hold.ts';
import { renderAppAt } from '../../test/render.tsx';
import { server } from '../../test/setup.ts';
import { mockKafkaCluster } from '../../test/kafkaCluster.ts';

const FIND = '*/api/v1/clusters/k1/kafka/hanging-transactions\\:find';
const PREVIEW = '*/api/v1/clusters/k1/kafka/hanging-transactions\\:abort-preview';
const ABORT = '*/api/v1/clusters/k1/kafka/hanging-transactions\\:abort';

const ITEM = {
  id: 'kafka.hanging-transaction/orders/0/4711',
  topic: 'orders',
  partition: 0,
  producerId: 4711,
  producerEpoch: 3,
  startOffset: 120,
  recordsHeldBack: 842,
  ageMs: 540_000,
};

function serve(handlers: Parameters<typeof server.use> = []) {
  mockKafkaCluster();
  server.use(
    http.get('*/api/v1/clusters/k1/kafka/transactions', () => HttpResponse.json({ items: [] })),
    ...handlers,
  );
}

async function find(user: ReturnType<typeof userEvent.setup>) {
  renderAppAt('/clusters/k1/kafka/transactions');
  await user.click(await screen.findByRole('button', { name: 'Find hanging transactions' }));
}

describe('the hanging transactions', () => {
  it('teaches what they are and looks for none until asked', async () => {
    let asked = 0;
    serve([http.post(FIND, () => (asked++, HttpResponse.json({ items: [] })))]);
    renderAppAt('/clusters/k1/kafka/transactions');
    expect(await screen.findByText('Nothing has been looked for yet')).toBeInTheDocument();
    expect(asked).toBe(0);
  });

  it('lists what it finds, and says a figure the broker did not give is not reported', async () => {
    serve([
      http.post(FIND, () =>
        HttpResponse.json({
          items: [
            ITEM,
            {
              ...ITEM,
              id: 'x',
              topic: 'payments',
              partition: 2,
              producerId: 9,
              producerEpoch: null,
              recordsHeldBack: null,
              ageMs: null,
            },
          ],
        }),
      ),
    ]);
    const user = userEvent.setup();
    await find(user);
    const table = await screen.findByRole('table', { name: 'Hanging transactions' });
    expect(within(table).getByText('842')).toBeInTheDocument();
    expect(within(table).getByText('9 min ago')).toBeInTheDocument();
    expect(within(table).getAllByText('Not reported')).toHaveLength(2);
  });

  it('says there are none, in words, when it looked and found none', async () => {
    serve([http.post(FIND, () => HttpResponse.json({ items: [] }))]);
    const user = userEvent.setup();
    await find(user);
    expect(await screen.findByText('No hanging transactions')).toBeInTheDocument();
    expect(screen.getByText(/Every open transaction is known to its coordinator/)).toBeInTheDocument();
  });

  it('never reads a search that could not run as an empty answer', async () => {
    serve([
      http.post(FIND, () =>
        HttpResponse.json({ title: 'Unsupported', detail: 'The driver cannot discover them.' }, { status: 501 }),
      ),
    ]);
    const user = userEvent.setup();
    await find(user);
    expect(await screen.findByText('Hanging transactions cannot be looked for here')).toBeInTheDocument();
    expect(screen.getByText(/The driver cannot discover them/)).toBeInTheDocument();
    expect(screen.queryByText('No hanging transactions')).not.toBeInTheDocument();
  });

  it('scopes the search to one topic', async () => {
    const urls: string[] = [];
    serve([http.post(FIND, ({ request }) => (urls.push(request.url), HttpResponse.json({ items: [] })))]);
    const user = userEvent.setup();
    renderAppAt('/clusters/k1/kafka/transactions');
    await user.type(await screen.findByRole('textbox', { name: /Topic/ }), 'orders');
    await user.click(screen.getByRole('button', { name: 'Find hanging transactions' }));
    expect(await screen.findByText(/No open transaction on orders/)).toBeInTheDocument();
    expect(new URL(urls[0]).searchParams.get('topic')).toBe('orders');
  });

  it('previews the abort, names the fenced producer, and aborts against the preview after a hold', async () => {
    const calls: { path: string; body: unknown }[] = [];
    let aborted = false;
    serve([
      http.post(FIND, () => HttpResponse.json({ items: aborted ? [] : [ITEM] })),
      http.post(PREVIEW, async ({ request }) => {
        calls.push({ path: 'preview', body: await request.json() });
        return HttpResponse.json({
          topic: 'orders',
          partition: 0,
          producerId: 4711,
          effect: 'Aborts the transaction of producer 4711 on orders-0 and fences it.',
          recordsHeldBack: 842,
          etag: 'h1',
        });
      }),
      http.post(ABORT, async ({ request }) => {
        calls.push({ path: 'abort', body: await request.json() });
        aborted = true;
        return HttpResponse.json({ topic: 'orders', partition: 0, producerId: 4711, aborted: true });
      }),
    ]);
    const user = userEvent.setup();
    await find(user);
    await user.click(await screen.findByRole('button', { name: 'Abort on orders-0' }));
    const dialog = await screen.findByRole('dialog', { name: /Abort the hanging transaction on orders-0/ });
    expect(await within(dialog).findByText(/fences it/)).toBeInTheDocument();
    expect(within(dialog).getByText(/holds back 842 records/)).toBeInTheDocument();
    await holdButton(within(dialog).getByRole('button', { name: 'Abort on orders-0' }));
    await screen.findByText('No hanging transactions');
    expect(calls.map((c) => c.path)).toEqual(['preview', 'abort']);
    expect(calls[1].body).toEqual({ topic: 'orders', partition: 0, producerId: 4711, etag: 'h1' });
  });

  it('asks for a fresh preview and keeps the dialog open when the transaction changed', async () => {
    let previews = 0;
    serve([
      http.post(FIND, () => HttpResponse.json({ items: [ITEM] })),
      http.post(PREVIEW, () => {
        previews += 1;
        return HttpResponse.json({
          topic: 'orders',
          partition: 0,
          producerId: 4711,
          effect: 'Fences producer 4711.',
          recordsHeldBack: 842,
          etag: `h${previews}`,
        });
      }),
      http.post(ABORT, () =>
        HttpResponse.json({ title: 'Precondition failed', detail: 'It changed.' }, { status: 412 }),
      ),
    ]);
    const user = userEvent.setup();
    await find(user);
    await user.click(await screen.findByRole('button', { name: 'Abort on orders-0' }));
    const dialog = await screen.findByRole('dialog', { name: /Abort the hanging transaction/ });
    await within(dialog).findByText(/Fences producer 4711/);
    await holdButton(within(dialog).getByRole('button', { name: 'Abort on orders-0' }));
    expect(await within(dialog).findByText('The transaction changed since the preview')).toBeInTheDocument();
    expect(within(dialog).getByRole('button', { name: 'Abort on orders-0' })).toBeDisabled();
    await user.click(within(dialog).getByRole('button', { name: 'Preview again' }));
    await within(dialog).findByText(/Fences producer 4711/);
    expect(previews).toBe(2);
    expect(within(dialog).queryByText('The transaction changed since the preview')).not.toBeInTheDocument();
  });

  it('keeps Find and Abort visible but disabled, with the reason, without the permissions', async () => {
    mockKafkaCluster(['cluster:read']);
    server.use(
      http.get('*/api/v1/clusters/k1/kafka/transactions', () => HttpResponse.json({ items: [] })),
      http.post(FIND, () => HttpResponse.json({ items: [ITEM] })),
    );
    const user = userEvent.setup();
    renderAppAt('/clusters/k1/kafka/transactions');
    await waitFor(() => expect(screen.getByRole('button', { name: 'Find hanging transactions' })).toBeDisabled());
    await user.click(
      await screen.findByRole('button', { name: 'Why looking for hanging transactions is unavailable' }),
    );
    expect(await screen.findByText(/You do not have the "Find hanging transactions" permission/)).toBeInTheDocument();
  });
});
