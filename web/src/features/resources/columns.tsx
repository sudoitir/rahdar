import { nameOf } from '../../kernel/api/resourceId.ts';
import type { ReactNode } from 'react';

import { OwnerChip } from '../../kernel/auth/OwnerChip.tsx';
import { ResourceLink } from '../../kernel/actions/ResourceLink.tsx';
import { MiddleTruncate, type Column } from '../../ui/table/index.ts';
import type { DestinationView, ConnectionView, ConsumerView, ProducerView, ChannelView } from './api.ts';
import { CloseAddressConsumersAction, CloseConnectionAction } from './CloseConnection.tsx';

/** What a row action needs that the row itself does not carry. */
export interface ColumnDeps {
  clusterId: string;
  /** When the rows on screen were fetched, in epoch ms. A close depends on it. */
  fetchedAt: number | null;
}

const LINK_TARGET = {
  queue: (value: string) => ({ queueName: value }),
  address: (value: string) => ({ address: value }),
  connection: (value: string) => ({ connectionId: value, nodeId: '', nodeName: '' }),
  session: (value: string) => ({ sessionId: value, nodeId: '', nodeName: '' }),
};

/**
 * A value that names another resource, as a link to it (ADR-0107) — or as plain text when the
 * feature presenting it is disabled, or when the broker gave no value. Its text is shortened in the
 * middle like any identifier, so names that differ only at the end stay apart.
 */
function linked(kind: 'queue' | 'address' | 'connection' | 'session', value: string | null | undefined): ReactNode {
  if (!value) return '';
  const target = LINK_TARGET[kind](value) as never;
  return (
    <ResourceLink kind={kind} target={target}>
      <MiddleTruncate text={value} />
    </ResourceLink>
  );
}

/**
 * The trailing action column: the verb a row's finding implies, beside the row rather than behind a
 * selection or a detail pane. The action names itself; the header does not. `label` is the text of
 * the control, which is what the column is sized by.
 */
function actionColumn<T>(label: string, render: (row: T) => ReactNode): Column<T> {
  return {
    id: 'action',
    header: 'Action',
    accessor: () => label,
    cell: render,
    kind: 'status',
    priority: 'high',
    // The control has padding of its own beyond its label.
    min: label.length + 4,
  };
}

/** The node a row came from: never hidden, so a row is always attributable. */
function nodeColumn<T extends { nodeName: string }>(): Column<T> {
  return { id: 'node', header: 'Node', accessor: (r) => r.nodeName, kind: 'identifier', priority: 'essential' };
}

/** How an address delivers, as an operator reads it. */
const DELIVERY: Record<DestinationView['delivery'][number], string> = {
  POINT_TO_POINT: 'Point to point',
  PUBLISH_SUBSCRIBE: 'Publish-subscribe',
};

/** Each resource's columns. The first column identifies a row; the node column is never hidden. */
export const resourceColumns = {
  addresses: ({ clusterId }: ColumnDeps): Column<DestinationView>[] => [
    {
      id: 'name',
      header: 'Address',
      accessor: (r) => r.name,
      kind: 'identifier',
      priority: 'essential',
      sortKey: 'name',
    },
    {
      id: 'owner',
      header: 'Owner',
      description: 'The team whose patterns own this address',
      accessor: (r) => r.ownerTeam?.name ?? '',
      cell: (r) => <OwnerChip team={r.ownerTeam} />,
      kind: 'status',
      badge: true,
      // A team name fits in nine characters; any wider and the row would push a column out at 1280 px.
      max: 9,
      priority: 'high',
    },
    {
      id: 'delivery',
      header: 'Delivery',
      description: 'To one consumer, to every subscription, or both',
      accessor: (r) => r.delivery.map((mode) => DELIVERY[mode]).join(', '),
      kind: 'status',
      priority: 'low',
    },
    { id: 'queues', header: 'Queues', accessor: (r) => r.subscriptionCount, kind: 'number', priority: 'high' },
    { id: 'depth', header: 'Messages', accessor: (r) => r.messageCount, kind: 'number', priority: 'high' },
    nodeColumn<DestinationView>(),
    actionColumn<DestinationView>('Close consumers', (row) => (
      <CloseAddressConsumersAction clusterId={clusterId} address={row.name} />
    )),
  ],
  consumers: ({ clusterId, fetchedAt }: ColumnDeps): Column<ConsumerView>[] => [
    {
      id: 'queue',
      header: 'Queue',
      accessor: (r) => nameOf(r.subscription),
      cell: (r) => linked('queue', nameOf(r.subscription)),
      kind: 'identifier',
      priority: 'essential',
      sortKey: 'queue',
    },
    {
      id: 'address',
      header: 'Address',
      accessor: (r) => nameOf(r.destination),
      cell: (r) => linked('address', nameOf(r.destination)),
      kind: 'identifier',
      priority: 'high',
    },
    { id: 'protocol', header: 'Protocol', accessor: (r) => r.protocol ?? '', kind: 'status', priority: 'low' },
    { id: 'delivered', header: 'Delivered', accessor: (r) => r.messagesDelivered, kind: 'number', priority: 'high' },
    { id: 'acked', header: 'Acked', accessor: (r) => r.messagesAcknowledged, kind: 'number', priority: 'low' },
    {
      id: 'inFlight',
      header: 'In flight',
      description: 'Delivered to this consumer and not yet acknowledged',
      accessor: (r) => r.messagesInFlight,
      kind: 'number',
      priority: 'high',
    },
    nodeColumn<ConsumerView>(),
    actionColumn<ConsumerView>('Close', (row) => (
      <CloseConnectionAction
        clusterId={clusterId}
        kind="consumer"
        nodeId={row.nodeId}
        nodeName={row.nodeName}
        targetId={row.id}
        rowLabel={nameOf(row.subscription) || nameOf(row.id)}
        fetchedAt={fetchedAt}
      />
    )),
  ],
  sessions: ({ clusterId, fetchedAt }: ColumnDeps): Column<ChannelView>[] => [
    {
      id: 'session',
      header: 'Session',
      accessor: (r) => nameOf(r.id),
      kind: 'identifier',
      priority: 'essential',
      sortKey: 'session',
    },
    { id: 'user', header: 'User', accessor: (r) => r.user ?? '', kind: 'text', priority: 'high' },
    {
      id: 'conn',
      header: 'Connection',
      accessor: (r) => nameOf(r.connection),
      cell: (r) => linked('connection', nameOf(r.connection)),
      kind: 'identifier',
      priority: 'low',
    },
    { id: 'consumers', header: 'Consumers', accessor: (r) => r.consumerCount, kind: 'number', priority: 'high' },
    { id: 'producers', header: 'Producers', accessor: (r) => r.producerCount, kind: 'number', priority: 'high' },
    nodeColumn<ChannelView>(),
    actionColumn<ChannelView>('Close', (row) => (
      <CloseConnectionAction
        clusterId={clusterId}
        kind="session"
        nodeId={row.nodeId}
        nodeName={row.nodeName}
        targetId={row.id}
        rowLabel={row.user ?? nameOf(row.id)}
        fetchedAt={fetchedAt}
      />
    )),
  ],
  connections: ({ clusterId, fetchedAt }: ColumnDeps): Column<ConnectionView>[] => [
    {
      id: 'remote',
      header: 'Remote address',
      accessor: (r) => r.remoteAddress ?? '',
      kind: 'identifier',
      priority: 'essential',
      sortKey: 'remote',
    },
    { id: 'protocol', header: 'Protocol', accessor: (r) => r.protocol ?? '', kind: 'status', priority: 'low' },
    { id: 'client', header: 'Client id', accessor: (r) => r.clientId ?? '', kind: 'identifier', priority: 'high' },
    { id: 'sessions', header: 'Sessions', accessor: (r) => r.channelCount, kind: 'number', priority: 'high' },
    nodeColumn<ConnectionView>(),
    actionColumn<ConnectionView>('Close', (row) => (
      <CloseConnectionAction
        clusterId={clusterId}
        kind="connection"
        nodeId={row.nodeId}
        nodeName={row.nodeName}
        targetId={row.id}
        rowLabel={row.clientId || row.remoteAddress || nameOf(row.id)}
        fetchedAt={fetchedAt}
      />
    )),
  ],
  producers: (): Column<ProducerView>[] => [
    {
      id: 'address',
      header: 'Address',
      accessor: (r) => nameOf(r.destination),
      cell: (r) => linked('address', nameOf(r.destination)),
      kind: 'identifier',
      priority: 'essential',
      sortKey: 'address',
    },
    { id: 'client', header: 'Client', accessor: (r) => r.clientId ?? '', kind: 'text', priority: 'high' },
    { id: 'protocol', header: 'Protocol', accessor: (r) => r.protocol ?? '', kind: 'status', priority: 'low' },
    { id: 'sent', header: 'Sent', accessor: (r) => r.messagesSent, kind: 'number', priority: 'high' },
    nodeColumn<ProducerView>(),
  ],
};
