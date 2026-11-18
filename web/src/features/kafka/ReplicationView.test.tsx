import { describe, expect, it } from 'vitest';
import { http, HttpResponse } from 'msw';
import { screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

import { renderAppAt } from '../../test/render.tsx';
import { server } from '../../test/setup.ts';
import { mockKafkaCluster } from '../../test/kafkaCluster.ts';
import type { PartitionRow } from './placement.ts';

const IN_SYNC = 'Every partition is in sync and on its preferred leader.';

function partition(over: Partial<PartitionRow> = {}): PartitionRow {
  return {
    topic: 'orders',
    partition: 0,
    leader: 1,
    replicas: [2, 1],
    inSyncReplicas: [1],
    offlineReplicas: [2],
    logStartOffset: 10,
    logEndOffset: 40,
    sizeBytes: 100,
    replicasOnBroker: [
      { broker: 2, bytes: 100, offsetLag: 4, future: false },
      { broker: 1, bytes: 80, offsetLag: 0, future: false },
    ],
    preferredLeader: false,
    eligibleLeaderReplicas: null,
    localLogStartOffset: null,
    tieredOffset: 12,
    tieredOffsetReason: null,
    logDirsUnavailable: null,
    ...over,
  };
}

function serve(items: PartitionRow[], permissions?: string[]) {
  mockKafkaCluster(permissions);
  server.use(
    http.get('*/api/v1/clusters/k1/kafka/replication', () => HttpResponse.json({ items, next: null })),
    http.get('*/api/v1/clusters/k1/kafka/reassignments', () => HttpResponse.json({ items: [] })),
  );
}

describe('replication', () => {
  it('lists the under-replicated partition the API returned and not a healthy one it did not', async () => {
    serve([partition({ topic: 'orders', partition: 4 })]);
    renderAppAt('/clusters/k1/kafka/replication');

    const grid = await screen.findByRole('grid', { name: 'Partitions out of sync' });
    expect(within(grid).getByRole('link', { name: 'orders' })).toBeInTheDocument();
    expect(within(grid).getByText('4')).toBeInTheDocument();
    expect(screen.queryByText('payments')).not.toBeInTheDocument();
    expect(screen.queryByText(IN_SYNC)).not.toBeInTheDocument();
    expect(screen.queryByRole('checkbox', { name: /unclean/i })).not.toBeInTheDocument();
    expect(screen.queryByText(/unclean/i)).not.toBeInTheDocument();
  });

  it('says every partition is in sync, and shows no table, when the API returns nothing', async () => {
    serve([]);
    renderAppAt('/clusters/k1/kafka/replication');

    expect(await screen.findByText(IN_SYNC)).toBeInTheDocument();
    expect(screen.queryByRole('grid', { name: 'Partitions out of sync' })).not.toBeInTheDocument();
    expect(screen.queryByRole('table')).not.toBeInTheDocument();
  });

  it('says when a topic filter matches nothing, which is not the in-sync sentence', async () => {
    serve([]);
    renderAppAt('/clusters/k1/kafka/replication?topic=missing');

    expect(
      await screen.findByText('No partition of this topic is under-replicated, offline, or off its preferred leader.'),
    ).toBeInTheDocument();
    expect(screen.queryByText(IN_SYNC)).not.toBeInTheDocument();
    expect(screen.queryByRole('grid', { name: 'Partitions out of sync' })).not.toBeInTheDocument();
  });

  it('keeps elect and reassign visible but disabled, and names destination:update, without that permission', async () => {
    serve([partition()], ['cluster:read', 'destination:read']);
    renderAppAt('/clusters/k1/kafka/replication');

    expect(await screen.findAllByText(/destination:update/)).toHaveLength(2);
    expect(screen.getByRole('button', { name: 'Elect preferred leaders' })).toBeDisabled();
    expect(screen.getByRole('button', { name: 'Reassign partition' })).toBeDisabled();
  });

  it('does not send a reassignment when the suggestion is dismissed', async () => {
    const posts: string[] = [];
    serve([partition()]);
    server.use(
      http.get('*/api/v1/clusters/k1/kafka/topics/orders/partitions/0\\:suggestion', () =>
        HttpResponse.json({
          label: 'suggestion',
          topic: 'orders',
          partition: 0,
          currentReplicas: [2, 1],
          replicas: [2, 1, 3],
          note: 'Keeps the current replicas and adds a broker for a rack not yet represented.',
        }),
      ),
      http.post('*/api/v1/clusters/k1/kafka/topics/orders/partitions/0\\:reassign', () => {
        posts.push('reassign');
        return HttpResponse.json({});
      }),
      http.post('*/api/v1/clusters/k1/kafka/topics/orders/partitions/0\\:reassign-preview', () => {
        posts.push('preview');
        return HttpResponse.json({});
      }),
    );
    const user = userEvent.setup();
    renderAppAt('/clusters/k1/kafka/replication');

    await user.click(await screen.findByRole('checkbox', { name: 'Select row orders-0' }));
    await user.click(screen.getByRole('button', { name: 'Reassign partition' }));
    const dialog = await screen.findByRole('dialog', { name: 'Reassign partition 0 of orders' });
    expect(within(dialog).getByRole('textbox', { name: 'Replicas' })).toHaveValue('2, 1');

    await user.click(within(dialog).getByRole('button', { name: 'Suggest placement' }));
    expect(await within(dialog).findByText('suggestion')).toBeInTheDocument();
    expect(within(dialog).getByRole('textbox', { name: 'Replicas' })).toHaveValue('2, 1, 3');

    await user.click(within(dialog).getByRole('button', { name: 'Dismiss suggestion' }));
    expect(within(dialog).queryByText('suggestion')).not.toBeInTheDocument();
    expect(within(dialog).getByRole('textbox', { name: 'Replicas' })).toHaveValue('2, 1');
    await user.click(within(dialog).getByRole('button', { name: 'Cancel' }));
    expect(posts).toEqual([]);
  });

  it('disables confirm and names both numbers when the preview factor is below min.insync.replicas', async () => {
    serve([partition()]);
    server.use(
      http.post('*/api/v1/clusters/k1/kafka/topics/orders/partitions/0\\:reassign-preview', () =>
        HttpResponse.json({
          currentReplicas: [2, 1],
          replicas: [1],
          currentReplicationFactor: 2,
          replicationFactor: 1,
          minInSyncReplicas: 2,
          bytes: null,
          bytesUnavailable: 'The byte count is not known.',
          duration: null,
          durationUnavailable: 'The duration is not known.',
          throttleOutcome: 'this throttle is removed when the move finishes',
          throttleBytesPerSecond: 1048576,
          etag: 'e1',
        }),
      ),
    );
    const user = userEvent.setup();
    renderAppAt('/clusters/k1/kafka/replication');

    await user.click(await screen.findByRole('checkbox', { name: 'Select row orders-0' }));
    await user.click(screen.getByRole('button', { name: 'Reassign partition' }));
    const dialog = await screen.findByRole('dialog', { name: 'Reassign partition 0 of orders' });
    await user.clear(within(dialog).getByRole('textbox', { name: 'Replicas' }));
    await user.type(within(dialog).getByRole('textbox', { name: 'Replicas' }), '1');
    await user.type(within(dialog).getByRole('textbox', { name: 'Throttle' }), '1048576');
    await user.click(within(dialog).getByRole('button', { name: 'Preview reassignment' }));

    expect(await within(dialog).findByText('Replication factor 1 is below min.insync.replicas 2.')).toBeInTheDocument();
    expect(within(dialog).getByText('The byte count is not known.')).toBeInTheDocument();
    expect(within(dialog).getByText('The duration is not known.')).toBeInTheDocument();
    expect(within(dialog).getByText('this throttle is removed when the move finishes')).toBeInTheDocument();
    expect(within(dialog).getByRole('button', { name: 'Reassign partition 0 of orders' })).toBeDisabled();
  });
});
