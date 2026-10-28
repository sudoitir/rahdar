import { Link } from '@tanstack/react-router';

import { nameOf } from '../../kernel/api/resourceId.ts';
import { absoluteLabel } from '../../kernel/time/time.ts';
import { AUTO, localZone } from '../../kernel/time/timezone.ts';
import { BacklogCell } from '../../ui/BacklogCell.tsx';
import { backlogWords } from '../../ui/backlog.ts';
import linkClasses from '../../ui/InlineLink.module.css';
import { StatusBadge } from '../../ui/StatusBadge.tsx';
import { kindColumn, type Column } from '../../ui/table/index.ts';
import type { ClusterSummary, EnvironmentView, EstateSubscription } from './api.ts';
import { HEALTH_TONE, HEALTH_WORD } from './clusterHealth.ts';

const zoneName = (zone: string) => (zone === AUTO ? localZone() : zone);

/** A cluster's name as a link to the cluster. */
const clusterLink = (id: string, name: string) => {
  return (
    <Link to={`/clusters/${id}`} className={linkClasses.link}>
      {name}
    </Link>
  );
};

/** Health in words; a cluster with no known health is one whose brokers are not answering. */
const healthWord = (c: ClusterSummary) => (c.health === 'UNKNOWN' ? 'Not answering' : HEALTH_WORD[c.health]);

/** The clusters of every system side by side: health from the cluster's conditions, so systems compare. */
export function clusterColumns(
  environments: ReadonlyMap<string, EnvironmentView>,
  zone: string,
): Column<ClusterSummary>[] {
  return [
    {
      id: 'name',
      header: 'Name',
      accessor: (c) => c.name,
      cell: (c) => clusterLink(c.id, c.name),
      kind: 'identifier',
      priority: 'essential',
    },
    { id: 'system', header: 'System', accessor: (c) => c.system.name, kind: 'text', priority: 'high', max: 20 },
    {
      id: 'environment',
      header: 'Environment',
      accessor: (c) => environments.get(c.environmentId ?? '')?.name ?? 'None',
      kind: 'text',
      priority: 'high',
      max: 20,
    },
    {
      id: 'health',
      header: 'Health',
      description: 'The cluster’s health from its conditions, the same measure for every system',
      accessor: healthWord,
      cell: (c) => <StatusBadge tone={HEALTH_TONE[c.health]}>{healthWord(c)}</StatusBadge>,
      kind: 'status',
      badge: true,
      priority: 'essential',
    },
    { id: 'nodes', header: 'Nodes', accessor: (c) => c.nodeCount, kind: 'number', priority: 'high' },
    {
      id: 'updated',
      header: 'Updated',
      description: `When the cluster was last changed, in ${zoneName(zone)}`,
      accessor: (c) => absoluteLabel(c.updatedAt),
      kind: 'time',
      priority: 'low',
    },
  ];
}

/** Figures older than the last scrape, or from fewer nodes than the cluster has: the cluster's brokers did not all answer. */
export function freshnessOf(row: EstateSubscription): 'current' | 'stale' | 'partial' {
  const { perNode, nodesPresent, nodesTotal } = row.subscription;
  if (perNode.some((n) => n.stale)) return 'stale';
  return nodesPresent < nodesTotal ? 'partial' : 'current';
}

/** The estate-wide subscriptions: each row says which cluster it is on, in that cluster's own kind label. */
export function estateSubscriptionColumns(
  onBreakdown?: (row: EstateSubscription) => void,
): Column<EstateSubscription>[] {
  return [
    {
      id: 'cluster',
      header: 'Cluster',
      accessor: (r) => r.cluster.name,
      cell: (r) => clusterLink(r.cluster.id, r.cluster.name),
      kind: 'identifier',
      priority: 'essential',
      sortKey: 'cluster',
    },
    {
      id: 'name',
      header: 'Name',
      accessor: (r) => r.subscription.name,
      cell: (r) => (
        <Link
          to={`/clusters/${r.cluster.id}/subscriptions`}
          search={{ subscription: r.subscription.id }}
          className={linkClasses.link}
        >
          {r.subscription.name}
        </Link>
      ),
      kind: 'identifier',
      priority: 'essential',
      sortKey: 'name',
    },
    kindColumn<EstateSubscription>((r) => r.subscription.kindLabel),
    {
      id: 'destination',
      header: 'Destination',
      accessor: (r) => nameOf(r.subscription.destination),
      kind: 'identifier',
      priority: 'high',
      sortKey: 'destination',
    },
    {
      id: 'backlog',
      header: 'Backlog',
      description:
        'Messages waiting in the subscription. ≈ marks an estimate, and Unavailable says why the figure could not be read',
      accessor: (r) => backlogWords(r.subscription.backlog),
      cell: (r) => (
        <BacklogCell
          backlog={r.subscription.backlog}
          subject={r.subscription.name}
          onBreakdown={onBreakdown && (() => onBreakdown(r))}
        />
      ),
      kind: 'number',
      priority: 'high',
      sortKey: 'depth',
    },
    {
      id: 'consumers',
      header: 'Consumers',
      short: 'Cons.',
      description: 'Consumers attached, on every node',
      accessor: (r) => r.subscription.totalConsumerCount,
      kind: 'number',
      priority: 'high',
      sortKey: 'consumers',
    },
    {
      id: 'state',
      header: 'State',
      accessor: (r) => (r.subscription.paused ? 'paused' : ''),
      kind: 'status',
      priority: 'high',
    },
    {
      // The figures of a cluster whose brokers did not answer are the last ones heard, and say so in words.
      id: 'figures',
      header: 'Figures',
      accessor: freshnessOf,
      cell: (r) => {
        const freshness = freshnessOf(r);
        return freshness === 'current' ? 'current' : <StatusBadge tone="warning">{freshness}</StatusBadge>;
      },
      kind: 'status',
      badge: true,
      priority: 'high',
    },
  ];
}
