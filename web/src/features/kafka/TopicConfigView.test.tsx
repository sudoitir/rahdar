import { describe, expect, it } from 'vitest';
import { http, HttpResponse } from 'msw';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

import { renderAppAt } from '../../test/render.tsx';
import { server } from '../../test/setup.ts';
import { mockKafkaCluster } from '../../test/kafkaCluster.ts';

const CONFIG = {
  internal: false,
  entries: [
    {
      name: 'retention.ms',
      value: '604800000',
      source: 'TOPIC',
      defaultValue: '604800000',
      readOnly: false,
      sensitive: false,
      unit: 'ms',
    },
    {
      name: 'cleanup.policy',
      value: 'delete',
      source: 'BROKER_DEFAULT',
      defaultValue: 'delete',
      readOnly: false,
      sensitive: false,
    },
    { name: 'ssl.key.password', value: null, source: 'STATIC', defaultValue: null, readOnly: false, sensitive: true },
    {
      name: 'message.format.version',
      value: '3.0',
      source: 'STATIC',
      defaultValue: '3.0',
      readOnly: true,
      sensitive: false,
    },
  ],
};

function serve(handlers: Parameters<typeof server.use> = []) {
  mockKafkaCluster();
  server.use(
    http.get('*/api/v1/clusters/k1/kafka/topics/orders/config', () => HttpResponse.json(CONFIG)),
    ...handlers,
  );
}

describe('a topic configuration', () => {
  it('shows where each value comes from, hides a sensitive one and marks a read-only one', async () => {
    serve();
    renderAppAt('/clusters/k1/kafka/topics/orders/config');

    const grid = await screen.findByRole('grid', { name: 'Topic configuration' });
    expect(await within(grid).findByText('Set on this topic')).toBeInTheDocument();
    expect(within(grid).getByText('Broker default')).toBeInTheDocument();
    expect(within(grid).getByText('Hidden (sensitive)')).toBeInTheDocument();
    expect(within(grid).getByText('Read-only')).toBeInTheDocument();
    expect(within(grid).queryByRole('button', { name: 'Change message.format.version' })).not.toBeInTheDocument();
  });

  it("collects a change, reviews it against what it replaces, and applies it with the review's etag", async () => {
    const calls: { path: string; body: Record<string, unknown> }[] = [];
    serve([
      http.post('*/api/v1/clusters/k1/kafka/topics/orders/config\\:preview', async ({ request }) => {
        calls.push({ path: 'preview', body: (await request.json()) as never });
        return HttpResponse.json({
          changes: [{ name: 'retention.ms', before: '604800000', after: '86400000', error: null }],
          valid: true,
          etag: 'e1',
        });
      }),
      http.post('*/api/v1/clusters/k1/kafka/topics/orders/config\\:apply', async ({ request }) => {
        calls.push({ path: 'apply', body: (await request.json()) as never });
        return HttpResponse.json({});
      }),
    ]);
    const user = userEvent.setup();
    renderAppAt('/clusters/k1/kafka/topics/orders/config');
    expect(await screen.findByRole('button', { name: 'Review changes' })).toBeDisabled();

    await user.click(await screen.findByRole('button', { name: 'Change retention.ms' }));
    const edit = await screen.findByRole('dialog', { name: 'Change retention.ms' });
    await user.clear(within(edit).getByRole('textbox', { name: 'New value' }));
    await user.type(within(edit).getByRole('textbox', { name: 'New value' }), '86400000');
    await user.click(within(edit).getByRole('button', { name: 'Keep this change' }));

    await user.click(screen.getByRole('button', { name: 'Review changes' }));
    const review = await screen.findByRole('dialog', { name: 'Apply 1 change to orders' });
    expect(within(review).getByText('86400000')).toBeInTheDocument();
    await user.click(within(review).getByRole('button', { name: 'Apply 1 change' }));

    await waitFor(() => expect(calls.map((c) => c.path)).toEqual(['preview', 'apply']));
    expect(calls[0].body).toEqual({ set: { 'retention.ms': '86400000' }, remove: [] });
    expect(calls[1].body).toMatchObject({ etag: 'e1' });
  });

  it('blocks the apply, with the reason, when the system rejects a value', async () => {
    serve([
      http.post('*/api/v1/clusters/k1/kafka/topics/orders/config\\:preview', () =>
        HttpResponse.json({
          changes: [{ name: 'retention.ms', before: '604800000', after: 'soon', error: 'Not a number.' }],
          valid: false,
          etag: 'e1',
        }),
      ),
    ]);
    const user = userEvent.setup();
    renderAppAt('/clusters/k1/kafka/topics/orders/config');
    await user.click(await screen.findByRole('button', { name: 'Change retention.ms' }));
    const edit = await screen.findByRole('dialog', { name: 'Change retention.ms' });
    await user.clear(within(edit).getByRole('textbox', { name: 'New value' }));
    await user.type(within(edit).getByRole('textbox', { name: 'New value' }), 'soon');
    await user.click(within(edit).getByRole('button', { name: 'Keep this change' }));
    await user.click(screen.getByRole('button', { name: 'Review changes' }));

    const review = await screen.findByRole('dialog', { name: 'Apply 1 change to orders' });
    expect(within(review).getAllByText('Not a number.').length).toBeGreaterThan(0);
    expect(within(review).getByRole('button', { name: 'Apply 1 change' })).toBeDisabled();
    expect(within(review).getByText(/rejects at least one change/)).toBeInTheDocument();
  });

  it('keeps Change visible but disabled, with the reason, for someone who may only read', async () => {
    mockKafkaCluster(['cluster:read', 'destination:read']);
    server.use(http.get('*/api/v1/clusters/k1/kafka/topics/orders/config', () => HttpResponse.json(CONFIG)));
    const user = userEvent.setup();
    renderAppAt('/clusters/k1/kafka/topics/orders/config');
    await user.click(await screen.findByRole('button', { name: 'Why changing retention.ms is unavailable' }));
    expect(screen.getByRole('button', { name: 'Change retention.ms' })).toBeDisabled();
    expect(await screen.findByText(/You do not have the "Change topic configuration" permission/)).toBeInTheDocument();
  });

  it('states why an internal topic cannot be changed and disables every change control', async () => {
    const reason = 'Kafka manages __consumer_offsets itself.';
    serve();
    server.use(
      http.get('*/api/v1/clusters/k1/kafka/topics/orders/config', () =>
        HttpResponse.json({ ...CONFIG, internal: true, readOnlyReason: reason }),
      ),
    );
    renderAppAt('/clusters/k1/kafka/topics/orders/config');

    const grid = await screen.findByRole('grid', { name: 'Topic configuration' });
    expect(await screen.findByText(reason)).toBeInTheDocument();
    expect(await within(grid).findByRole('button', { name: 'Change retention.ms' })).toBeDisabled();
    expect(within(grid).getByRole('button', { name: 'Change cleanup.policy' })).toBeDisabled();
    await userEvent.click(within(grid).getByRole('button', { name: /Why changing retention\.ms/ }));
    await waitFor(() => expect(screen.getAllByText(reason).length).toBeGreaterThan(1));
  });
});
