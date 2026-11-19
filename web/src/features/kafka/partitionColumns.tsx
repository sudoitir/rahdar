import { Link } from '@tanstack/react-router';

import linkClasses from '../../ui/InlineLink.module.css';
import type { Column } from '../../ui/table/index.ts';
import type { PartitionRow, ReplicaOnBroker } from './placement.ts';

const reported = (value: number | null): string => (value == null ? 'Not reported' : value.toLocaleString());

/** One replica, in place: broker, bytes on this broker, offset lag, and future when it is one. */
function replicaLine(replica: ReplicaOnBroker): string {
  const bytes =
    replica.bytes == null
      ? 'bytes on this broker not reported'
      : `${replica.bytes.toLocaleString()} bytes on this broker`;
  const lag =
    replica.offsetLag == null ? 'offset lag not reported' : `offset lag ${replica.offsetLag.toLocaleString()}`;
  const future = replica.future ? ', future' : '';
  return `Broker ${replica.broker}, ${bytes}, ${lag}${future}`;
}

/** The same facts, short enough that two replicas fit on the grid's one line. The long form is the reveal. */
function replicaCompact(replica: ReplicaOnBroker): string {
  const bytes = replica.bytes == null ? 'size not reported' : `${replica.bytes.toLocaleString()} bytes`;
  const lag = replica.offsetLag == null ? 'lag not reported' : `lag ${replica.offsetLag.toLocaleString()}`;
  return `${replica.broker}: ${bytes}, ${lag}${replica.future ? ', future' : ''}`;
}

function replicasInOrder(row: PartitionRow): ReplicaOnBroker[] {
  const byBroker = new Map(row.replicasOnBroker.map((replica) => [replica.broker, replica]));
  const listed = row.replicas.length > 0 ? row.replicas : row.replicasOnBroker.map((replica) => replica.broker);
  return listed.map((broker) => byBroker.get(broker) ?? { broker, bytes: null, offsetLag: null, future: false });
}

/** Replica lines in the cluster's order. The leader is not moved to the front. */
export function replicaLines(row: PartitionRow): string[] {
  return replicasInOrder(row).map(replicaLine);
}

/** The tiered offset, or the reason it is absent. Never a blank and never a stand-in zero. */
export function tieredWords(row: PartitionRow): string {
  if (row.tieredOffset != null) return row.tieredOffset.toLocaleString();
  if (row.tieredOffsetReason) return row.tieredOffsetReason;
  return 'Not reported';
}

/** The same log-directory miss is named once, however many rows carry it. */
export function logDirMisses(rows: readonly PartitionRow[]): string[] {
  const seen = new Set<string>();
  const misses: string[] = [];
  for (const row of rows) {
    const miss = row.logDirsUnavailable;
    if (!miss || seen.has(miss)) continue;
    seen.add(miss);
    misses.push(miss);
  }
  return misses;
}

const brokers = (ids: readonly number[]): string => (ids.length === 0 ? 'None' : ids.join(', '));

/** One line. The grid row is a fixed height, so a second line is clipped and the operator never sees it. */
function leaderWords(row: PartitionRow): string {
  const who = row.leader == null ? 'No leader' : `Broker ${row.leader}`;
  return row.preferredLeader ? who : `${who}. The preferred leader is not leading.`;
}

/** Placement columns. Eligible leaders are included only when the page's rows keep that field. */
export function partitionColumns(options: {
  clusterId?: string;
  showTopic: boolean;
  showEligible: boolean;
}): Column<PartitionRow>[] {
  const topic: Column<PartitionRow>[] = options.showTopic
    ? [
        {
          id: 'topic',
          header: 'Topic',
          accessor: (row) => row.topic,
          cell: (row) =>
            options.clusterId ? (
              <Link
                to={`/clusters/${options.clusterId}/kafka/topics/${encodeURIComponent(row.topic)}`}
                className={linkClasses.link}
              >
                {row.topic}
              </Link>
            ) : (
              row.topic
            ),
          kind: 'identifier',
          priority: 'essential',
        },
      ]
    : [];
  const eligible: Column<PartitionRow>[] = options.showEligible
    ? [
        {
          id: 'eligible',
          header: 'Eligible leaders',
          accessor: (row) => {
            const leaders = row.eligibleLeaderReplicas;
            if (leaders == null) return 'Not kept';
            return leaders.length === 0 ? 'None' : leaders.join(', ');
          },
          kind: 'text',
          priority: 'essential',
        },
      ]
    : [];
  return [
    ...topic,
    {
      id: 'partition',
      header: 'Partition',
      accessor: (row) => row.partition,
      kind: 'number',
      priority: 'essential',
    },
    {
      id: 'leader',
      header: 'Leader',
      accessor: leaderWords,
      cell: (row) =>
        row.preferredLeader || row.leader == null
          ? leaderWords(row)
          : `Broker ${row.leader}, not preferred`,
      kind: 'text',
      priority: 'essential',
    },
    {
      id: 'replicas',
      header: 'Replicas',
      description: 'Broker order, bytes on this broker, offset lag, and future when the replica is one',
      accessor: (row) => replicaLines(row).join(' · '),
      cell: (row) => replicasInOrder(row).map(replicaCompact).join(' · '),
      kind: 'text',
      max: 72,
      grow: true,
      priority: 'essential',
    },
    {
      id: 'isr',
      header: 'In sync',
      accessor: (row) => brokers(row.inSyncReplicas),
      kind: 'text',
      priority: 'high',
    },
    {
      id: 'offline',
      header: 'Offline',
      accessor: (row) => brokers(row.offlineReplicas),
      kind: 'text',
      priority: 'high',
    },
    {
      id: 'start',
      header: 'Log start',
      accessor: (row) => reported(row.logStartOffset),
      kind: 'number',
      priority: 'low',
    },
    {
      id: 'end',
      header: 'Log end',
      accessor: (row) => reported(row.logEndOffset),
      kind: 'number',
      priority: 'high',
    },
    {
      id: 'bytes',
      header: 'Leader bytes',
      description: 'Bytes of the leader replica on its broker, not a record count',
      accessor: (row) => (row.sizeBytes == null ? 'Not reported' : `${row.sizeBytes.toLocaleString()} bytes`),
      kind: 'number',
      priority: 'high',
    },
    {
      id: 'tiered',
      header: 'Tiered offset',
      description: 'The tiered offset, or why there is none',
      accessor: tieredWords,
      kind: 'text',
      priority: 'essential',
    },
    {
      id: 'local',
      header: 'Local log start',
      accessor: (row) => reported(row.localLogStartOffset),
      kind: 'number',
      priority: 'low',
    },
    ...eligible,
  ];
}
