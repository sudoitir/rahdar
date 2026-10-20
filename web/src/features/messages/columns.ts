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
export function messageColumns(zone: string): Column<MessageSummaryView>[] {
  return [
    {
      id: 'position',
      header: 'Message ID',
      accessor: (m) => m.position,
      kind: 'identifier',
      priority: 'essential',
    },
    {
      id: 'timestamp',
      header: 'Enqueued',
      accessor: (m) => absoluteLabel(m.timestamp),
      description: `When the broker enqueued the message, in ${zone === AUTO ? localZone() : zone}`,
      kind: 'time',
      priority: 'high',
    },
    { id: 'priority', header: 'Prio', accessor: (m) => m.priority, kind: 'number', priority: 'low' },
    {
      id: 'durable',
      header: 'Durable',
      accessor: (m) => (m.durable ? 'yes' : 'no'),
      kind: 'status',
      priority: 'low',
    },
    { id: 'size', header: 'Size', accessor: (m) => m.size, kind: 'number', priority: 'high' },
    { id: 'props', header: 'Props', accessor: (m) => m.propertyCount, kind: 'number', priority: 'low' },
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
      accessor: (r) => r.subscription.totalDepth,
      kind: 'number',
      priority: 'essential',
    },
    { id: 'nodes', header: 'Nodes', accessor: (r) => r.subscription.perNode.length, kind: 'number', priority: 'high' },
  ];
}
