import { describe, expect, it } from 'vitest';
import { http, HttpResponse } from 'msw';
import { screen, within } from '@testing-library/react';

import { renderAppAt } from '../../test/render.tsx';
import { server } from '../../test/setup.ts';
import { mockKafkaCluster } from '../../test/kafkaCluster.ts';
import type { PartitionRow } from './placement.ts';

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
      { broker: 2, bytes: 100, offsetLag: 0, future: false },
      { broker: 1, bytes: 80, offsetLag: 3, future: true },
    ],
    preferredLeader: false,
    eligibleLeaderReplicas: null,
    localLogStartOffset: 10,
    tieredOffset: null,
    tieredOffsetReason: 'not tiered',
    logDirsUnavailable: null,
    ...over,
  };
}

function serve(items: PartitionRow[]) {
  mockKafkaCluster();
  server.use(
    http.get('*/api/v1/clusters/k1/kafka/topics/orders/partitions', () => HttpResponse.json({ items, next: null })),
  );
}

describe("a topic's partitions", () => {
  it('keeps replica order when the leader is the second replica, and says the preferred leader is not leading', async () => {
    serve([partition()]);
    renderAppAt('/clusters/k1/kafka/topics/orders');

    const grid = await screen.findByRole('grid', { name: 'Partitions of orders' });
    const first = within(grid).getByText('Broker 2, 100 bytes on this broker, offset lag 0', { selector: 'span' });
    const second = within(grid).getByText('Broker 1, 80 bytes on this broker, offset lag 3, future', {
      selector: 'span',
    });
    expect(first.compareDocumentPosition(second) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
    expect(within(grid).getByText('The preferred leader is not leading.')).toBeInTheDocument();
    expect(within(grid).queryByRole('columnheader', { name: 'Eligible leaders' })).not.toBeInTheDocument();
  });

  it('names a log directory miss once, above the rows', async () => {
    const miss = 'Broker 3 did not answer describeLogDirs.';
    serve([
      partition({ partition: 0, logDirsUnavailable: miss }),
      partition({ partition: 1, logDirsUnavailable: miss }),
    ]);
    renderAppAt('/clusters/k1/kafka/topics/orders');

    const grid = await screen.findByRole('grid', { name: 'Partitions of orders' });
    expect(screen.getAllByText(miss)).toHaveLength(1);
    expect(within(grid).queryByText(miss)).not.toBeInTheDocument();
  });

  it('shows the tiered-offset reason, not a made-up zero, when the partition is not tiered', async () => {
    serve([
      partition({
        partition: 4,
        leader: 1,
        replicas: [2, 1],
        inSyncReplicas: [1],
        offlineReplicas: [],
        logStartOffset: null,
        logEndOffset: null,
        sizeBytes: null,
        localLogStartOffset: null,
        replicasOnBroker: [
          { broker: 2, bytes: null, offsetLag: null, future: false },
          { broker: 1, bytes: 80, offsetLag: null, future: false },
        ],
        tieredOffset: null,
        tieredOffsetReason: 'not tiered',
      }),
    ]);
    renderAppAt('/clusters/k1/kafka/topics/orders');

    const grid = await screen.findByRole('grid', { name: 'Partitions of orders' });
    expect(within(grid).getByText('not tiered')).toBeInTheDocument();
    expect(within(grid).getByText(/bytes on this broker not reported/)).toBeInTheDocument();
    expect(within(grid).queryByText('0')).not.toBeInTheDocument();
  });

  it('shows eligible leaders, in the order given, only when the field is present', async () => {
    serve([partition({ eligibleLeaderReplicas: [2, 1] })]);
    renderAppAt('/clusters/k1/kafka/topics/orders');

    const grid = await screen.findByRole('grid', { name: 'Partitions of orders' });
    expect(await within(grid).findByRole('columnheader', { name: 'Eligible leaders' })).toBeInTheDocument();
    expect(within(grid).getByText('2, 1')).toBeInTheDocument();
  });
});
