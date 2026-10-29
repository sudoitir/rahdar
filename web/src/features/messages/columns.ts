import { createElement } from 'react';

import { absoluteLabel } from '../../kernel/time/time.ts';
import { AUTO, localZone } from '../../kernel/time/timezone.ts';
import { kindColumn, type Column } from '../../ui/table/index.ts';
import type { DeadLetterSubscription, MessageSummaryView } from './api.ts';
import { BodyPreview, SubscriptionLink } from './cells.tsx';

/**
 * The columns of the message browser. The enqueue time is written in the display zone `zone`, which
 * the header states, so a view builds its columns again when the zone changes.
 */
export function messageColumns(zone: string, partitioned = false): Column<MessageSummaryView>[] {
  // A record of a log has none of an Artemis message's priority, durability or properties; the server sends zeros.
  const own: Column<MessageSummaryView>[] = partitioned
    ? [
        {
          id: 'key',
          header: 'Key',
          description: 'The record key; base64 where it is not text',
          accessor: (m) => m.key ?? '',
          kind: 'code',
          priority: 'high',
        },
      ]
    : [
        { id: 'priority', header: 'Prio', accessor: (m) => m.priority, kind: 'number', priority: 'low' },
        {
          id: 'durable',
          header: 'Durable',
          accessor: (m) => (m.durable ? 'yes' : 'no'),
          kind: 'status',
          priority: 'low',
        },
      ];
  const PROPS: Column<MessageSummaryView> = {
    id: 'props',
    header: 'Props',
    accessor: (m) => m.propertyCount,
    kind: 'number',
    priority: 'low',
  };
  return [
    {
      id: 'position',
      header: partitioned ? 'Offset' : 'Message ID',
      accessor: (m) => m.position,
      kind: 'identifier',
      priority: 'essential',
    },
    {
      id: 'timestamp',
      header: partitioned ? 'Written' : 'Enqueued',
      accessor: (m) => absoluteLabel(m.timestamp),
      description: `When the broker enqueued the message, in ${zone === AUTO ? localZone() : zone}`,
      kind: 'time',
      priority: 'high',
    },
    ...own,
    { id: 'size', header: 'Size', accessor: (m) => m.size, kind: 'number', priority: 'high' },
    ...(partitioned ? [] : [PROPS]),
    {
      id: 'body',
      header: 'Body',
      accessor: (m) => m.bodyPreview ?? '',
      cell: (m) => createElement(BodyPreview, { message: m }),
      kind: 'code',
      priority: 'high',
    },
  ];
}

/** One dead-letter subscription as a row: the subscription, the destination it is dead-lettered from, and its depth. */
export interface DeadLetterRow {
  destination: string;
  kind: string;
  subscription: DeadLetterSubscription;
}

/**
 * The depth the nodes reported. Null is unknown, never zero. A total that leaves some nodes out says so,
 * because those nodes are not counted as empty.
 */
function deadLetterDepth(subscription: DeadLetterSubscription): string | number {
  if (subscription.totalDepth == null) return 'Unknown';
  if (subscription.unknownDepthNodes > 0) {
    const nodes = subscription.unknownDepthNodes === 1 ? '1 node' : `${subscription.unknownDepthNodes} nodes`;
    return `${subscription.totalDepth.toLocaleString()} (${nodes} unknown)`;
  }
  return subscription.totalDepth;
}

/** The dead-letter grid's columns. The subscription identifies a row and is never hidden. */
export function deadLetterColumns(clusterId: string): Column<DeadLetterRow>[] {
  return [
    {
      id: 'subscription',
      header: 'Subscription',
      accessor: (r) => r.subscription.name,
      cell: (r) => createElement(SubscriptionLink, { clusterId, subscriptionName: r.subscription.name }),
      kind: 'identifier',
      priority: 'essential',
    },
    { id: 'destination', header: 'Destination', accessor: (r) => r.destination, kind: 'identifier', priority: 'high' },
    kindColumn<DeadLetterRow>((r) => r.subscription.kindLabel),
    { id: 'purpose', header: 'Purpose', accessor: (r) => r.kind, kind: 'status', priority: 'low' },
    {
      id: 'depth',
      header: 'Messages',
      accessor: (r) => deadLetterDepth(r.subscription),
      kind: 'number',
      priority: 'essential',
    },
    { id: 'nodes', header: 'Nodes', accessor: (r) => r.subscription.perNode.length, kind: 'number', priority: 'high' },
  ];
}
