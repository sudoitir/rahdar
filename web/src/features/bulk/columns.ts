import { createElement } from 'react';

import { nameOf } from '../../kernel/api/resourceId.ts';
import { absoluteLabel } from '../../kernel/time/time.ts';
import { AUTO, localZone } from '../../kernel/time/timezone.ts';
import type { Column } from '../../ui/table/index.ts';
import type { BulkItemView, BulkRunView } from './api.ts';
import { ItemOutcome, QueueButton, RunLink, RunOutcome } from './cells.tsx';
import { itemStatus, OPERATIONS, runStatus } from './words.ts';

/** An unknown figure is stated, never shown as zero. */
const messagesOf = (i: BulkItemView) => (i.affected == null ? 'unknown' : i.affected.toLocaleString());

/** How many messages a destroy would take from each queue; only a destroy has the column. */
const messagesColumn = (id: string): Column<BulkItemView> => ({
  id,
  header: 'Messages',
  accessor: messagesOf,
  kind: 'number',
  priority: 'high',
});

export interface RunColumnDeps {
  /** The cluster whose runs are listed: each run's time links into it. */
  clusterId: string;
  /** The display zone the times are written in, which the header states. */
  zone: string;
}

/**
 * The runs grid's columns. The time, which opens the run, identifies a row and is never hidden; the
 * outcome, the operation and the size go next, and who ran it is the first to be hidden when the
 * table is narrow. A view builds the columns again when the zone changes.
 */
export function runColumns({ clusterId, zone }: RunColumnDeps): Column<BulkRunView>[] {
  return [
    {
      id: 'when',
      header: 'When',
      accessor: (r) => absoluteLabel(r.startedAt ?? r.createdAt),
      description: `When the run started, in ${zone === AUTO ? localZone() : zone}`,
      cell: (r) => createElement(RunLink, { run: r, clusterId }),
      kind: 'time',
      priority: 'essential',
    },
    {
      id: 'operation',
      header: 'Operation',
      accessor: (r) => OPERATIONS[r.operation].verb,
      kind: 'status',
      priority: 'high',
    },
    { id: 'queues', header: 'Queues', accessor: (r) => r.total, kind: 'number', priority: 'high' },
    { id: 'user', header: 'Run by', accessor: (r) => r.username, kind: 'text', priority: 'low' },
    {
      id: 'outcome',
      header: 'Outcome',
      accessor: (r) => runStatus(r.status).text,
      cell: (r) => createElement(RunOutcome, { run: r }),
      kind: 'text',
      priority: 'high',
    },
  ];
}

/**
 * The columns of one run's queues. The queue identifies a row and is never hidden; its outcome and,
 * for a destroy, how many messages it held go next, and the reason is the first to be hidden when the
 * table is narrow. Building them again is needed only when the operation or the handler changes.
 */
export function itemColumns(destructive: boolean, onOpen: (subscription: string) => void): Column<BulkItemView>[] {
  return [
    {
      id: 'queue',
      header: 'Queue',
      accessor: (i) => nameOf(i.subscription),
      cell: (i) => createElement(QueueButton, { item: i, onOpen }),
      kind: 'identifier',
      priority: 'essential',
    },
    {
      id: 'status',
      header: 'Outcome',
      accessor: (i) => itemStatus(i.status).text,
      cell: (i) => createElement(ItemOutcome, { item: i }),
      kind: 'status',
      badge: true,
      priority: 'high',
    },
    ...(destructive ? [messagesColumn('affected')] : []),
    { id: 'note', header: 'Reason', accessor: (i) => i.error ?? i.warning ?? '', kind: 'text', priority: 'low' },
  ];
}

/**
 * The columns of the preview's frozen queue set. The queue identifies a row and is never hidden; the
 * plan and, for a destroy, how many messages it holds go next, then the reason or warning, and the
 * node count is the first to be hidden when the table is narrow.
 */
export function previewColumns(destructive: boolean): Column<BulkItemView>[] {
  return [
    {
      id: 'queue',
      header: 'Queue',
      accessor: (i) => nameOf(i.subscription),
      kind: 'identifier',
      priority: 'essential',
    },
    ...(destructive ? [messagesColumn('messages')] : []),
    { id: 'nodes', header: 'Nodes', accessor: (i) => i.nodes.length, kind: 'number', priority: 'low' },
    {
      id: 'plan',
      header: 'Plan',
      accessor: (i) => (i.status === 'REFUSED' ? 'refused' : 'will act'),
      kind: 'status',
      priority: 'high',
    },
    {
      id: 'note',
      header: 'Reason or warning',
      accessor: (i) => i.error ?? i.warning ?? '',
      kind: 'text',
      priority: 'high',
    },
  ];
}
