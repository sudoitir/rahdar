import { Stack, Text } from '@mantine/core';
import { useParams } from '@tanstack/react-router';

import { DescriptionList } from '../../ui/DescriptionList.tsx';
import { EmptyState } from '../../ui/EmptyState.tsx';
import { ErrorState } from '../../ui/ErrorState.tsx';
import { LoadingState } from '../../ui/LoadingState.tsx';
import { Notice } from '../../ui/Notice.tsx';
import { Page } from '../../ui/Page.tsx';
import { PageHeader } from '../../ui/PageHeader.tsx';
import { Section } from '../../ui/Section.tsx';
import { StatusBadge } from '../../ui/StatusBadge.tsx';
import { DataTable, type Column } from '../../ui/table/index.ts';
import { useNodes, type NodeView } from './api.ts';
import { alarmWords } from './words.ts';

const NODE_COLUMNS: Column<NodeView>[] = [
  { id: 'name', header: 'Node', accessor: (n) => n.name, kind: 'identifier', priority: 'essential' },
  {
    id: 'running',
    header: 'State',
    accessor: (n) => (n.running ? 'Running' : 'Not running'),
    cell: (n) => (
      <StatusBadge tone={n.running ? 'neutral' : 'danger'}>{n.running ? 'Running' : 'Not running'}</StatusBadge>
    ),
    kind: 'status',
    badge: true,
    priority: 'essential',
  },
  {
    id: 'alarms',
    header: 'Alarms',
    description: 'A node with a memory or disk alarm blocks publishers until it clears',
    accessor: (n) => (n.alarms.length === 0 ? 'None' : n.alarms.map(alarmWords).join(', ')),
    cell: (n) =>
      n.alarms.length === 0 ? (
        'None'
      ) : (
        <Stack gap={2} align="flex-start">
          {n.alarms.map((a) => (
            <StatusBadge key={a} tone="danger">
              {alarmWords(a)}
            </StatusBadge>
          ))}
        </Stack>
      ),
    kind: 'text',
    badge: true,
    wrap: true,
    priority: 'essential',
  },
  {
    id: 'partitions',
    header: 'Partitioned from',
    accessor: (n) => (n.partitions.length === 0 ? 'None' : n.partitions.join(', ')),
    kind: 'text',
    wrap: true,
    priority: 'high',
  },
  {
    id: 'listeners',
    header: 'Listeners',
    accessor: (n) => n.listeners.map((l) => `${l.protocol} ${l.port}`).join(', '),
    kind: 'code',
    wrap: true,
    priority: 'high',
  },
  { id: 'version', header: 'Version', accessor: (n) => n.version ?? 'Unknown', kind: 'code', priority: 'low' },
];

/**
 * The nodes of a RabbitMQ cluster: whether each runs, the alarms that block publishers, the nodes it cannot reach
 * (a network partition) and where it listens. The landing view of a RabbitMQ cluster.
 */
export function NodesView() {
  const { clusterId } = useParams({ strict: false }) as { clusterId: string };
  const nodes = useNodes(clusterId);
  if (nodes.isError) {
    return (
      <Page>
        <PageHeader title="Nodes" />
        <ErrorState error={nodes.error} onRetry={() => void nodes.refetch()} />
      </Page>
    );
  }
  if (!nodes.data) {
    return (
      <Page>
        <PageHeader title="Nodes" />
        <LoadingState label="Loading the nodes" blockSize="12rem" />
      </Page>
    );
  }
  const { clusterName, nodes: rows } = nodes.data;
  const alarmed = rows.filter((n) => n.alarms.length > 0);
  const partitioned = rows.filter((n) => n.partitions.length > 0);
  return (
    <Page>
      <PageHeader title="Nodes" description="The nodes of the cluster, their alarms and the nodes they cannot reach." />
      <DescriptionList
        label="Cluster"
        columns={2}
        items={[
          { term: 'Cluster name', value: clusterName ?? 'Not reported' },
          { term: 'Nodes running', value: `${rows.filter((n) => n.running).length} of ${rows.length}` },
        ]}
      />
      {alarmed.length > 0 ? (
        <Notice tone="danger" title="Publishers are blocked">
          <Text size="sm">
            {alarmed.map((n) => `${n.name}: ${n.alarms.map(alarmWords).join(', ')}`).join('; ')}. A broker with an alarm
            stops accepting messages until the memory or disk is freed.
          </Text>
        </Notice>
      ) : null}
      {partitioned.length > 0 ? (
        <Notice tone="warning" title="The cluster is partitioned">
          {partitioned.map((n) => `${n.name} cannot reach ${n.partitions.join(', ')}`).join('; ')}. Each side may have
          accepted messages the other has not seen.
        </Notice>
      ) : null}
      <Section title="Nodes">
        <DataTable
          variant="static"
          label="RabbitMQ nodes"
          storageKey="rabbitmq.nodes"
          columns={NODE_COLUMNS}
          data={rows}
          rowKey={(n) => n.name}
          height={{ maxRows: 16 }}
          empty={<EmptyState kind="empty" title="No node was found" description="The cluster reported no node." />}
        />
      </Section>
    </Page>
  );
}
