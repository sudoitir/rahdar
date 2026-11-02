import { createElement } from 'react';

import { nameOf, virtualHostOf } from '../../kernel/api/resourceId.ts';
import { kindColumn, type Column } from '../../ui/table/index.ts';
import type { ConsumerHealthView } from './api.ts';
import { SubscriptionLink } from './cells.tsx';
import { HealthVerdict } from './HealthVerdict.tsx';
import { formatRate, trendPhrase, verdictText } from './verdict.ts';

/**
 * The consumer-health grid's columns. The verdict and the subscription identify a row and are never
 * hidden; the figures that explain the verdict go next, and the destination and the in-flight count are
 * the first to be hidden when the table is narrow.
 *
 * An unmeasured rate or trend reads "not measured", never 0: on this screen the two mean opposite
 * things and lead to opposite actions.
 */
export function consumerHealthColumns(system?: string): Column<ConsumerHealthView>[] {
  // A RabbitMQ queue lives in a virtual host, not under a destination, and the same name can exist in two of them.
  const rabbit = system === 'rabbitmq';
  return [
    {
      id: 'verdict',
      header: 'Health',
      accessor: verdictText,
      cell: (r) => createElement(HealthVerdict, { row: r }),
      kind: 'status',
      badge: true,
      priority: 'essential',
      sortKey: 'severity',
    },
    {
      id: 'subscription',
      header: 'Subscription',
      accessor: (r) => r.name,
      cell: (r) => createElement(SubscriptionLink, { row: r }),
      kind: 'identifier',
      priority: 'essential',
      sortKey: 'subscription',
    },
    kindColumn<ConsumerHealthView>(),
    rabbit
      ? {
          id: 'vhost',
          header: 'Virtual host',
          accessor: (r) => virtualHostOf(r.subscription),
          kind: 'identifier',
          priority: 'essential',
          min: 12,
          max: 24,
          grow: false,
        }
      : {
          id: 'destination',
          header: 'Destination',
          accessor: (r) => nameOf(r.destination),
          kind: 'identifier',
          priority: 'low',
          sortKey: 'destination',
        },
    { id: 'depth', header: 'Depth', accessor: (r) => r.depth, kind: 'number', priority: 'high', sortKey: 'depth' },
    {
      id: 'consumers',
      header: 'Consumers',
      accessor: (r) => r.consumers,
      kind: 'number',
      priority: 'high',
      sortKey: 'consumers',
    },
    {
      id: 'delivering',
      header: 'In flight',
      accessor: (r) => r.delivering,
      kind: 'number',
      priority: 'low',
      sortKey: 'delivering',
    },
    {
      id: 'ackRate',
      header: 'Acknowledged',
      accessor: (r) => formatRate(r.ackRate),
      kind: 'number',
      priority: 'high',
    },
    { id: 'trend', header: 'Trend', accessor: trendPhrase, kind: 'text', priority: 'high' },
  ];
}
