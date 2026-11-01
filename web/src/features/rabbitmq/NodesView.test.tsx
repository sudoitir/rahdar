import { describe, expect, it } from 'vitest';
import { http, HttpResponse } from 'msw';
import { screen, within } from '@testing-library/react';

import { renderAppAt } from '../../test/render.tsx';
import { server } from '../../test/setup.ts';
import { mockRabbitMqCluster } from '../../test/rabbitmqCluster.ts';

const NODE = {
  name: 'rabbit@a',
  version: '4.4.0',
  running: true,
  alarms: [],
  partitions: [],
  listeners: [{ protocol: 'amqp', port: 5672 }],
};

const serve = (body: object | (() => Response)) => {
  mockRabbitMqCluster();
  server.use(
    http.get('*/api/v1/clusters/r1/rabbitmq/nodes', () =>
      typeof body === 'function' ? body() : HttpResponse.json(body),
    ),
  );
};

describe('the RabbitMQ nodes', () => {
  it('lists each node with its state, version and listeners under the cluster name', async () => {
    serve({ clusterName: 'orders-mq', nodes: [NODE] });
    renderAppAt('/clusters/r1/rabbitmq/nodes');
    const table = await screen.findByRole('table', { name: 'RabbitMQ nodes' });
    expect(within(table).getByText('rabbit@a')).toBeInTheDocument();
    expect(within(table).getByText('Running')).toBeInTheDocument();
    expect(within(table).getByText('amqp 5672')).toBeInTheDocument();
    expect(screen.getByText('Cluster name')).toBeInTheDocument();
    expect(screen.getByText('1 of 1')).toBeInTheDocument();
  });

  it('says publishers are blocked by an alarm and that a partition leaves the sides apart', async () => {
    serve({
      clusterName: 'orders-mq',
      nodes: [
        { ...NODE, alarms: ['memory'] },
        { ...NODE, name: 'rabbit@b', running: false, partitions: ['rabbit@a'] },
      ],
    });
    renderAppAt('/clusters/r1/rabbitmq/nodes');
    expect(await screen.findByText('Publishers are blocked')).toBeInTheDocument();
    expect(screen.getAllByText('Memory alarm').length).toBeGreaterThan(0);
    expect(screen.getByText('The cluster is partitioned')).toBeInTheDocument();
    expect(screen.getByText('Not running')).toBeInTheDocument();
  });

  it('says there is no node when the cluster reports none', async () => {
    serve({ clusterName: null, nodes: [] });
    renderAppAt('/clusters/r1/rabbitmq/nodes');
    expect(await screen.findByText('No node was found')).toBeInTheDocument();
    expect(screen.getByText('Not reported')).toBeInTheDocument();
  });

  it('shows the loading state, then the error with a retry', async () => {
    serve(() => HttpResponse.json({ title: 'Bad gateway', detail: 'The node is unreachable.' }, { status: 502 }));
    renderAppAt('/clusters/r1/rabbitmq/nodes');
    expect(await screen.findByText('Loading the nodes')).toBeInTheDocument();
    expect(await screen.findByText(/The node is unreachable/)).toBeInTheDocument();
  });

  it('is listed under Observe for a RabbitMQ cluster and the cluster lands on it', async () => {
    serve({ clusterName: 'orders-mq', nodes: [NODE] });
    renderAppAt('/clusters/r1');
    expect(await screen.findByRole('table', { name: 'RabbitMQ nodes' })).toBeInTheDocument();
  });
});
