import { createElement } from 'react';

import { resourceName } from '../../kernel/api/resourceId.ts';
import { absoluteLabel } from '../../kernel/time/time.ts';
import { AUTO, localZone } from '../../kernel/time/timezone.ts';
import type { Column } from '../../ui/table/index.ts';
import type { TransferRunView } from './api.ts';
import { RunLink, RunOutcome } from './cells.tsx';
import { MODE, stateWords } from './words.ts';

export interface TransferColumnDeps {
  /** The cluster whose transfers are listed: an end on another cluster names it. */
  clusterId: string;
  clusterName: (id: string) => string;
  /** The display zone the times are written in, which the header states. */
  zone: string;
}

/**
 * The transfers grid's columns. The start time, which opens the run, and the two ends, which name the
 * queues and nodes, identify a row and are never hidden; the outcome and the count go next, and who
 * ran it and the mode are the first to be hidden when the table is narrow. A view builds the columns
 * again when the zone or the cluster names change.
 */
export function transferColumns({ clusterId, clusterName, zone }: TransferColumnDeps): Column<TransferRunView>[] {
  const end = (run: TransferRunView, which: 'source' | 'target') => {
    const { subscription, nodeName, clusterId: endCluster } = run[which];
    const where = endCluster === clusterId ? '' : ` (${clusterName(endCluster)})`;
    return `${resourceName(subscription)} on ${nodeName}${where}`;
  };

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
    { id: 'mode', header: 'Mode', accessor: (r) => MODE[r.mode].verb, kind: 'status', priority: 'low' },
    { id: 'from', header: 'From', accessor: (r) => end(r, 'source'), kind: 'identifier', priority: 'essential' },
    { id: 'to', header: 'To', accessor: (r) => end(r, 'target'), kind: 'identifier', priority: 'essential' },
    { id: 'delivered', header: 'Delivered', accessor: (r) => r.delivered, kind: 'number', priority: 'high' },
    {
      id: 'state',
      header: 'Outcome',
      accessor: (r) => stateWords(r.state).text,
      cell: (r) => createElement(RunOutcome, { run: r }),
      kind: 'text',
      priority: 'high',
    },
    { id: 'user', header: 'Run by', accessor: (r) => r.username, kind: 'text', priority: 'low' },
  ];
}
