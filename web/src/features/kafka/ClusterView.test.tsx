import { describe, expect, it } from 'vitest';
import { http, HttpResponse } from 'msw';
import { screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

import { renderAppAt } from '../../test/render.tsx';
import { server } from '../../test/setup.ts';
import { mockKafkaCluster } from '../../test/kafkaCluster.ts';
import type { ClusterView } from './api.ts';

const BROKERS: ClusterView['brokers'] = [
  {
    id: '1',
    rack: 'a',
    address: 'k1:9092',
    reachable: true,
    reason: null,
    leaders: 10,
    replicasHeld: 12,
    outOfSyncReplicas: 0,
    offlineReplicas: 0,
    quorumLeader: true,
  },
  {
    id: '2',
    rack: 'b',
    address: 'k2:9092',
    reachable: false,
    reason: 'Connection timed out.',
    leaders: 0,
    replicasHeld: 4,
    outOfSyncReplicas: 4,
    offlineReplicas: 1,
    quorumLeader: false,
  },
];

function cluster(over: Partial<ClusterView> = {}): ClusterView {
  return {
    mode: 'KRaft',
    complete: true,
    reason: null,
    brokers: BROKERS,
    controllers: [],
    quorum: {
      available: true,
      leader: '1',
      majority: true,
      voters: [
        { id: '1', lag: 0, caughtUp: true },
        { id: '2', lag: 5, caughtUp: true },
        { id: '3', caughtUp: false },
      ],
    },
    edges: [{ a: '1', b: '2', sharedPartitions: 4, outOfSyncReplicas: 1 }],
    totals: {
      brokers: 2,
      brokersReached: 1,
      controllers: 0,
      topics: 3,
      partitions: 9,
      underReplicated: 1,
      offline: 1,
      preferredLeaderMismatches: 0,
      atLeast: false,
    },
    problems: {
      offline: 1,
      underReplicated: 1,
      preferredLeaderMismatches: 0,
      topics: ['orders'],
      topicsWithheld: null,
      topicsOmitted: 0,
    },
    notes: [],
    ...over,
  };
}

function stub(body: ClusterView | { title: string; detail: string }, status = 200) {
  mockKafkaCluster();
  server.use(http.get('*/api/v1/clusters/k1/kafka/cluster', () => HttpResponse.json(body, { status })));
}

describe('the cluster', () => {
  it('states the same brokers in the graph and the table, and the quorum with its majority', async () => {
    stub(cluster());
    renderAppAt('/clusters/k1/kafka/cluster');
    expect(await screen.findByRole('button', { name: /Broker 1/ })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /Broker 2/ })).toHaveAccessibleName(/Not reached. Connection timed out/);
    expect(screen.getByRole('button', { name: /Broker 2/ })).toHaveAccessibleName(
      /1 out of sync of 4 shared partitions/,
    );
    expect(screen.queryByRole('button', { name: /^Actions for/ })).toBeNull();
    expect(screen.queryByRole('button', { name: /^Insert between/ })).toBeNull();
    expect(screen.getByText('1 of 2')).toBeInTheDocument();
    expect(screen.getByText('The quorum has a majority')).toBeInTheDocument();
    expect(screen.getByText('2 of 3 voters are caught up, a majority.')).toBeInTheDocument();
  });

  it('uses the singular when one voter is caught up', async () => {
    stub(
      cluster({
        quorum: {
          available: true,
          leader: '1',
          majority: true,
          voters: [{ id: '1', lag: 0, caughtUp: true }],
        },
      }),
    );
    renderAppAt('/clusters/k1/kafka/cluster?view=table');
    expect(await screen.findByText('1 of 1 voters is caught up, a majority.')).toBeInTheDocument();
  });

  it('lists the same brokers in the table, with a link to each configuration', async () => {
    stub(cluster());
    const { router } = renderAppAt('/clusters/k1/kafka/cluster?view=table');
    const table = await screen.findByRole('table', { name: 'Kafka brokers' });
    expect(within(table).getByText('Connection timed out.')).toBeInTheDocument();
    expect(within(table).getByText('Not reached')).toBeInTheDocument();
    expect(within(table).getByRole('link', { name: 'Configuration of broker 1' })).toHaveAttribute(
      'href',
      '/clusters/k1/kafka/brokers/1/config',
    );
    expect(router.state.location.search).toMatchObject({ view: 'table' });
  });

  it('opens the chosen broker beside the table, with an unknown figure absent', async () => {
    const brokers = BROKERS.map((b) =>
      b.id === '2' ? { ...b, leaders: null, replicasHeld: null, outOfSyncReplicas: null, offlineReplicas: null } : b,
    );
    stub(cluster({ brokers }));
    renderAppAt('/clusters/k1/kafka/cluster?view=table&broker=2');
    const aside = await screen.findByRole('complementary', { name: 'Chosen broker' });
    expect(within(aside).getByText(/Connection timed out/)).toBeInTheDocument();
    expect(within(aside).getAllByText('Unknown').length).toBeGreaterThanOrEqual(3);
    expect(within(aside).queryByText('0')).toBeNull();
    const table = screen.getByRole('table', { name: 'Kafka brokers' });
    expect(within(table).getAllByText('Unknown').length).toBeGreaterThanOrEqual(3);
  });

  it('uses the singular when one voter is caught up', async () => {
    stub(
      cluster({
        quorum: {
          available: true,
          leader: '1',
          majority: true,
          voters: [{ id: '1', lag: 0, caughtUp: true }],
        },
      }),
    );
    renderAppAt('/clusters/k1/kafka/cluster?view=table');
    expect(await screen.findByText('1 of 1 voters is caught up, a majority.')).toBeInTheDocument();
  });

  it('states that the quorum has no majority as the critical state', async () => {
    stub(
      cluster({
        quorum: {
          available: true,
          leader: '1',
          majority: false,
          voters: [
            { id: '1', lag: 0, caughtUp: true },
            { id: '2', lag: 900, caughtUp: false },
            { id: '3', caughtUp: false },
          ],
        },
      }),
    );
    renderAppAt('/clusters/k1/kafka/cluster?view=table');
    expect(await screen.findByText('The quorum has no majority')).toBeInTheDocument();
    expect(screen.getByRole('alert')).toHaveTextContent('Only 1 of 3 voters is caught up, which is not a majority.');
  });

  it('says the quorum is unknown on a ZooKeeper cluster, and draws no controller', async () => {
    stub(
      cluster({
        mode: 'ZooKeeper',
        quorum: {
          available: false,
          reason: 'The controller is kept in ZooKeeper and cannot be read.',
          voters: [],
          majority: false,
        },
        notes: ['The controller is kept in ZooKeeper and cannot be read.'],
      }),
    );
    renderAppAt('/clusters/k1/kafka/cluster');
    expect(await screen.findByRole('button', { name: /Broker 1/ })).toBeInTheDocument();
    expect(screen.getByText('The controller quorum is not shown')).toBeInTheDocument();
    expect(
      screen.getByText(
        /The controller is kept in ZooKeeper and cannot be read. It is not read as healthy; it is unknown./,
      ),
    ).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /Controller/ })).toBeNull();
  });

  it('says a fenced broker is not listed before Kafka 4.0', async () => {
    stub(
      cluster({
        notes: [
          'Kafka before 4.0 does not list fenced brokers, so a fenced broker is missing from the nodes instead of shown as fenced.',
        ],
      }),
    );
    renderAppAt('/clusters/k1/kafka/cluster?view=table');
    expect(await screen.findByText(/a fenced broker is missing from the nodes/)).toBeInTheDocument();
  });

  it('withholds topic names without destination:read and still states the counts', async () => {
    stub(
      cluster({
        problems: {
          offline: 2,
          underReplicated: 5,
          preferredLeaderMismatches: 1,
          topics: [],
          topicsWithheld: 'Topic names need destination:read.',
          topicsOmitted: 7,
        },
      }),
    );
    renderAppAt('/clusters/k1/kafka/cluster?view=table');
    expect(await screen.findByText('Topic names need destination:read.')).toBeInTheDocument();
    expect(screen.getByText('7 topic names are not listed.')).toBeInTheDocument();
    expect(screen.queryByRole('link', { name: 'orders' })).toBeNull();
    const counts = screen.getByRole('group', { name: 'Partitions needing attention' });
    expect(within(counts).getByText('5')).toBeInTheDocument();
  });

  it('states a capped read as absent, never as zero', async () => {
    stub(
      cluster({
        complete: false,
        reason: 'The cluster describes at most 2000 topics at once, so these figures are a floor, not a total.',
        totals: {
          brokers: 2,
          brokersReached: 1,
          controllers: 0,
          topics: null,
          partitions: null,
          underReplicated: 4,
          offline: 1,
          preferredLeaderMismatches: 0,
          atLeast: true,
        },
        notes: ['The cluster describes at most 2000 topics at once, so these figures are a floor, not a total.'],
      }),
    );
    renderAppAt('/clusters/k1/kafka/cluster?view=table');
    const topics = await screen.findByText('Topics');
    const figure = topics.closest('dl')?.querySelector('dd');
    expect(figure).toHaveTextContent('Unavailable');
    expect(figure).not.toHaveTextContent('0');
    expect(topics.closest('dl')).toHaveTextContent('a floor, not a total');
    const under = screen.getAllByText('Under-replicated')[0]?.closest('dl')?.querySelector('dd');
    expect(under).toHaveTextContent(/at least/);
    expect(under).toHaveTextContent('4');
    expect(under).not.toHaveTextContent('Unavailable');
    expect(screen.getByText('This read is incomplete')).toBeInTheDocument();
    expect(screen.getAllByText(/a floor, not a total/).length).toBeGreaterThan(0);
  });

  it('says how many topic names a capped list left out', async () => {
    stub(
      cluster({
        complete: false,
        reason: 'The cluster describes at most 2000 topics at once, so these figures are a floor, not a total.',
        totals: {
          brokers: 2,
          brokersReached: 1,
          controllers: 0,
          topics: null,
          partitions: null,
          underReplicated: 4,
          offline: 1,
          preferredLeaderMismatches: 0,
          atLeast: true,
        },
        problems: {
          offline: 1,
          underReplicated: 4,
          preferredLeaderMismatches: 0,
          topics: ['orders'],
          topicsWithheld: null,
          topicsOmitted: 14,
        },
      }),
    );
    renderAppAt('/clusters/k1/kafka/cluster?view=table');
    expect(await screen.findByText('14 topic names are not listed.')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'orders' })).toBeInTheDocument();
  });

  it('holds the graph frame while the cluster loads', async () => {
    mockKafkaCluster();
    server.use(http.get('*/api/v1/clusters/k1/kafka/cluster', () => new Promise(() => {})));
    renderAppAt('/clusters/k1/kafka/cluster');
    expect(await screen.findByText('Loading the cluster')).toBeInTheDocument();
    expect(screen.queryByRole('alert')).toBeNull();
  });

  it('says no broker has answered yet, and that Rahdar keeps looking', async () => {
    stub(cluster({ brokers: [], controllers: [], edges: [] }));
    renderAppAt('/clusters/k1/kafka/cluster');
    expect(await screen.findByText('No broker has answered yet')).toBeInTheDocument();
    expect(screen.getByText('Rahdar keeps looking.')).toBeInTheDocument();
  });

  it('names the cause and offers retry when the cluster cannot be read', async () => {
    stub({ title: 'Error', detail: 'No broker answered.' }, 502);
    renderAppAt('/clusters/k1/kafka/cluster');
    expect(await screen.findByRole('alert')).toHaveTextContent('No broker answered');
    expect(screen.getByRole('button', { name: 'Retry' })).toBeInTheDocument();
  });

  it('keeps Cluster listed and disabled without cluster:read, with the reason', async () => {
    mockKafkaCluster(['destination:read']);
    server.use(http.get('*/api/v1/clusters/k1/kafka/cluster', () => HttpResponse.json(cluster())));
    renderAppAt('/clusters/k1/kafka/cluster');
    const row = await screen.findByRole('link', { name: 'Cluster', description: /cluster:read/ });
    expect(row).toHaveAttribute('aria-disabled', 'true');
    expect(row).toHaveAccessibleDescription(/Opening Cluster needs the cluster:read permission/);
  });

  it('says how many co-replication pairs the graph leaves undrawn', async () => {
    const edges = Array.from({ length: 201 }, (_, i) => ({
      a: '1',
      b: String(i + 2),
      sharedPartitions: 201 - i,
      outOfSyncReplicas: 0,
    }));
    const first = BROKERS[0];
    if (!first) throw new Error('fixture');
    const brokers = [
      first,
      ...edges.map((edge) => ({
        id: edge.b,
        rack: null,
        address: null,
        reachable: true,
        reason: null,
        leaders: 0,
        replicasHeld: 1,
        outOfSyncReplicas: 0,
        offlineReplicas: 0,
        quorumLeader: false,
      })),
    ];
    stub(cluster({ brokers, edges }));
    renderAppAt('/clusters/k1/kafka/cluster');
    expect(await screen.findByText(/1 co-replication pair beyond the strongest 200/)).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /^Actions for/ })).toBeNull();
  });

  it('keeps the table in the address', async () => {
    stub(cluster());
    const { router } = renderAppAt('/clusters/k1/kafka/cluster');
    await userEvent.setup().click(await screen.findByRole('radio', { name: 'Table' }));
    expect(router.state.location.search).toMatchObject({ view: 'table' });
    expect(screen.getByRole('table', { name: 'Kafka brokers' })).toBeInTheDocument();
  });
});
