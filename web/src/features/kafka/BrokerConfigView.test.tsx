import { describe, expect, it } from 'vitest';
import { http, HttpResponse } from 'msw';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

import { renderAppAt } from '../../test/render.tsx';
import { server } from '../../test/setup.ts';
import { mockKafkaCluster } from '../../test/kafkaCluster.ts';

const CONFIG = {
  broker: '1',
  clusterDefault: false,
  entries: [
    { name: 'message.max.bytes', value: '1048588', source: 'CLUSTER', readOnly: false, unit: 'bytes' },
    { name: 'log.cleaner.threads', value: '2', source: 'NODE', readOnly: false },
    { name: 'broker.id', value: '1', source: 'STATIC', readOnly: true },
  ],
};

function serve(handlers: Parameters<typeof server.use> = []) {
  mockKafkaCluster();
  server.use(
    http.get('*/api/v1/clusters/k1/kafka/brokers/1/config', () => HttpResponse.json(CONFIG)),
    ...handlers,
  );
}

describe('a broker configuration', () => {
  it('shows where each value comes from and presents a restart-only setting as a fact', async () => {
    serve();
    renderAppAt('/clusters/k1/kafka/brokers/1/config');

    const grid = await screen.findByRole('grid', { name: 'Broker configuration' });
    expect(await within(grid).findByText('Cluster default')).toBeInTheDocument();
    expect(within(grid).getByText('Set on this broker')).toBeInTheDocument();
    expect(within(grid).getByText(/Needs a restart/)).toBeInTheDocument();
    expect(within(grid).queryByRole('button', { name: 'Change broker.id' })).not.toBeInTheDocument();
    expect(within(grid).getByRole('button', { name: 'Change message.max.bytes' })).toBeEnabled();
  });

  it("previews a change, then applies it with the preview's etag", async () => {
    const calls: { path: string; body: Record<string, unknown> }[] = [];
    serve([
      http.post('*/api/v1/clusters/k1/kafka/brokers/1/config\\:preview', async ({ request }) => {
        calls.push({ path: 'preview', body: (await request.json()) as never });
        return HttpResponse.json({
          changes: [{ name: 'log.cleaner.threads', before: '2', after: '4', error: null }],
          valid: true,
          etag: 'e1',
        });
      }),
      http.post('*/api/v1/clusters/k1/kafka/brokers/1/config\\:apply', async ({ request }) => {
        calls.push({ path: 'apply', body: (await request.json()) as never });
        return HttpResponse.json({});
      }),
    ]);
    const user = userEvent.setup();
    renderAppAt('/clusters/k1/kafka/brokers/1/config');
    expect(await screen.findByRole('button', { name: 'Review changes' })).toBeDisabled();

    await user.click(await screen.findByRole('button', { name: 'Change log.cleaner.threads' }));
    const edit = await screen.findByRole('dialog', { name: 'Change log.cleaner.threads' });
    await user.clear(within(edit).getByRole('textbox', { name: 'New value' }));
    await user.type(within(edit).getByRole('textbox', { name: 'New value' }), '4');
    await user.click(within(edit).getByRole('button', { name: 'Keep this change' }));

    await user.click(screen.getByRole('button', { name: 'Review changes' }));
    const review = await screen.findByRole('dialog', { name: 'Apply 1 change to broker 1' });
    expect(within(review).getByText('4')).toBeInTheDocument();
    await user.click(within(review).getByRole('button', { name: 'Apply 1 change' }));

    await waitFor(() => expect(calls.map((c) => c.path)).toEqual(['preview', 'apply']));
    expect(calls[0].body).toEqual({ set: { 'log.cleaner.threads': '4' }, remove: [] });
    expect(calls[1].body).toMatchObject({ etag: 'e1' });
  });

  it('names the cluster default and can add a setting that is not listed yet', async () => {
    mockKafkaCluster();
    server.use(
      http.get('*/api/v1/clusters/k1/kafka/brokers/default/config', () =>
        HttpResponse.json({
          broker: 'default',
          clusterDefault: true,
          entries: [],
          note: 'The cluster-wide default lists only the settings set on it.',
        }),
      ),
    );
    const user = userEvent.setup();
    renderAppAt('/clusters/k1/kafka/brokers/default/config');

    expect(await screen.findByRole('heading', { name: 'Cluster default configuration' })).toBeInTheDocument();
    expect(await screen.findByText(/lists only the settings set on it/)).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Add a setting' }));
    const add = await screen.findByRole('dialog', { name: 'Add a setting' });
    await user.type(within(add).getByRole('textbox', { name: 'Setting' }), 'message.max.bytes');
    await user.type(within(add).getByRole('textbox', { name: 'Value' }), '2097152');
    await user.click(within(add).getByRole('button', { name: 'Keep this change' }));
    expect(await screen.findByText('2097152 (pending)')).toBeInTheDocument();
    expect(screen.getByText('1 change to review')).toBeInTheDocument();
  });

  it('says why a refused change was refused', async () => {
    serve([
      http.post('*/api/v1/clusters/k1/kafka/brokers/1/config\\:preview', () =>
        HttpResponse.json(
          { status: 409, title: 'Conflict', detail: 'broker.id is read-only while a broker runs.' },
          { status: 409, headers: { 'Content-Type': 'application/problem+json' } },
        ),
      ),
    ]);
    const user = userEvent.setup();
    renderAppAt('/clusters/k1/kafka/brokers/1/config');
    await user.click(await screen.findByRole('button', { name: 'Change message.max.bytes' }));
    const edit = await screen.findByRole('dialog', { name: 'Change message.max.bytes' });
    await user.clear(within(edit).getByRole('textbox', { name: 'New value' }));
    await user.type(within(edit).getByRole('textbox', { name: 'New value' }), '5');
    await user.click(within(edit).getByRole('button', { name: 'Keep this change' }));
    await user.click(screen.getByRole('button', { name: 'Review changes' }));
    expect(await screen.findByText(/broker\.id is read-only while a broker runs\./)).toBeInTheDocument();
    expect(screen.queryByRole('dialog', { name: /Apply 1 change/ })).not.toBeInTheDocument();
  });
});
