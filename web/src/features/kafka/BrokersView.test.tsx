import { describe, expect, it } from 'vitest';
import { http, HttpResponse } from 'msw';
import { screen, within } from '@testing-library/react';

import { renderAppAt } from '../../test/render.tsx';
import { server } from '../../test/setup.ts';
import { mockKafkaCluster } from '../../test/kafkaCluster.ts';

const BROKERS = [
  {
    id: '1',
    rack: 'a',
    address: 'k1:9092',
    reachable: true,
    reason: null,
    leaders: 10,
    underReplicated: 0,
    offline: 0,
  },
  {
    id: '2',
    rack: 'b',
    address: 'k2:9092',
    reachable: false,
    reason: 'Connection timed out.',
    leaders: 0,
    underReplicated: 4,
    offline: 1,
  },
];

describe('the brokers', () => {
  it('lists each broker with what it leads and the quorum with its voters', async () => {
    mockKafkaCluster();
    server.use(
      http.get('*/api/v1/clusters/k1/kafka/brokers', () =>
        HttpResponse.json({
          brokers: BROKERS,
          quorum: {
            available: true,
            leader: '1',
            majority: 2,
            voters: [
              { id: '1', lag: 0, caughtUp: true },
              { id: '2', lag: 900, caughtUp: false },
            ],
          },
        }),
      ),
    );
    renderAppAt('/clusters/k1/kafka/brokers');
    const table = await screen.findByRole('table', { name: 'Kafka brokers' });
    expect(within(table).getByText('Connection timed out.')).toBeInTheDocument();
    expect(within(table).getByText('Not reached')).toBeInTheDocument();
    expect(await screen.findByRole('table', { name: 'Quorum voters' })).toBeInTheDocument();
    expect(screen.getByText('Broker 1', { selector: 'dd, dd *' })).toBeInTheDocument();
    expect(screen.getByText('Lagging')).toBeInTheDocument();
  });

  it('says the quorum is unknown on a ZooKeeper cluster, never healthy', async () => {
    mockKafkaCluster();
    server.use(
      http.get('*/api/v1/clusters/k1/kafka/brokers', () =>
        HttpResponse.json({
          brokers: BROKERS.slice(0, 1),
          quorum: { available: false, reason: 'The cluster runs ZooKeeper.' },
        }),
      ),
    );
    renderAppAt('/clusters/k1/kafka/brokers');
    expect(await screen.findByText('The controller quorum is not shown')).toBeInTheDocument();
    expect(
      screen.getByText(/The cluster runs ZooKeeper\. It is not read as healthy; it is unknown\./),
    ).toBeInTheDocument();
  });

  it('says why the brokers could not be read', async () => {
    mockKafkaCluster();
    server.use(
      http.get('*/api/v1/clusters/k1/kafka/brokers', () =>
        HttpResponse.json({ title: 'Error', detail: 'No broker answered.' }, { status: 502 }),
      ),
    );
    renderAppAt('/clusters/k1/kafka/brokers');
    expect(await screen.findByText(/No broker answered/)).toBeInTheDocument();
  });
});
