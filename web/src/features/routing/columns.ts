import { createElement } from 'react';

import type { Column } from '../../ui/table/index.ts';
import type { BridgeRow, DivertRow } from './api.ts';
import { BridgeRoute, BridgeStateBadge, DivertEffect, DivertRoute, NodesPresent, OwnerCell } from './cells.tsx';
import { DeleteDivertAction } from './DivertActions.tsx';

/** A bridge's state in words, which is also what its column's cells copy. */
function bridgeWords(r: BridgeRow): string {
  if (r.connected) return 'running and connected';
  if (r.started) return 'started, not connected to its target';
  return 'not started';
}

/**
 * The columns of the divert view.
 *
 * <p>There is no origin column. Artemis records nothing saying whether a divert
 * came from broker.xml or from a management call, and exposes no way to read
 * configured-but-undeployed diverts, so the product does not guess (ADR-0065).
 * What it can say honestly is which diverts are its own, and it says that.
 */
export function divertColumns(clusterId: string): Column<DivertRow>[] {
  return [
    { id: 'name', header: 'Name', accessor: (r) => r.name, kind: 'identifier', priority: 'essential', sortKey: 'name' },
    {
      id: 'direction',
      header: 'Routes',
      accessor: (r) => `${r.address} → ${r.forwardingAddress}`,
      cell: (r) => createElement(DivertRoute, { divert: r }),
      kind: 'identifier',
      priority: 'high',
      min: 24,
      max: 80,
    },
    {
      id: 'exclusive',
      header: 'Effect',
      accessor: (r) => (r.exclusive ? 'takes the message' : 'copies the message'),
      cell: (r) => createElement(DivertEffect, { divert: r }),
      kind: 'status',
      priority: 'high',
    },
    { id: 'filter', header: 'Filter', accessor: (r) => r.filter ?? '', kind: 'code', priority: 'low' },
    {
      id: 'owner',
      header: 'Created by',
      accessor: (r) => r.owner ?? '',
      cell: (r) => createElement(OwnerCell, { divert: r }),
      kind: 'status',
      priority: 'low',
    },
    {
      id: 'nodes',
      header: 'Nodes',
      accessor: (r) => `${r.nodesPresent}/${r.nodesTotal}`,
      cell: (r) => createElement(NodesPresent, { row: r }),
      kind: 'number',
      priority: 'essential',
    },
    {
      id: 'action',
      header: 'Action',
      accessor: () => '',
      cell: (r) => createElement(DeleteDivertAction, { clusterId, divert: r }),
      kind: 'status',
      priority: 'low',
    },
  ];
}

/**
 * What the live view of a bridge reports. Declaring, changing and removing one is
 * the declaration's job (ADR-0091), reached on the Builder tab — this
 * table reads every serving node and has no write of its own.
 */
export function bridgeColumns(): Column<BridgeRow>[] {
  return [
    { id: 'name', header: 'Name', accessor: (r) => r.name, kind: 'identifier', priority: 'essential', sortKey: 'name' },
    {
      id: 'direction',
      header: 'Routes',
      accessor: (r) => `${r.queueName ?? '(unnamed queue)'} → ${r.forwardingAddress ?? '(the target broker)'}`,
      cell: (r) => createElement(BridgeRoute, { bridge: r }),
      kind: 'identifier',
      priority: 'high',
      min: 24,
      max: 80,
    },
    {
      id: 'state',
      header: 'State',
      accessor: (r) => bridgeWords(r),
      cell: (r) => createElement(BridgeStateBadge, { words: bridgeWords(r), halfUp: r.started && !r.connected }),
      kind: 'status',
      priority: 'high',
    },
    {
      id: 'pending',
      header: 'Pending',
      accessor: (r) => r.messagesPendingAcknowledgement,
      kind: 'number',
      priority: 'high',
    },
    { id: 'acked', header: 'Acked', accessor: (r) => r.messagesAcknowledged, kind: 'number', priority: 'low' },
    {
      id: 'nodes',
      header: 'Nodes',
      accessor: (r) => `${r.nodesPresent}/${r.nodesTotal}`,
      cell: (r) => createElement(NodesPresent, { row: r }),
      kind: 'number',
      priority: 'essential',
    },
  ];
}
