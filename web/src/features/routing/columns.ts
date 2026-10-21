import { createElement } from 'react';

import { kindColumn, type Column } from '../../ui/table/index.ts';
import type { RouteView } from './api.ts';
import { NodesPresent, OwnerCell, RouteDirection, RouteState } from './cells.tsx';
import { routeEnds, routeState } from './routeWords.ts';
import { DeleteDivertAction } from './DivertActions.tsx';

/** The id of the kind whose routes Rahdar can delete: Artemis diverts. */
export const DIVERT_KIND = 'artemis.divert';

/** A figure only a link reports, summed over the nodes that carry it; blank for a route that has none. */
function linkTotal(
  r: RouteView,
  figure: (node: RouteView['perNode'][number]) => number | null | undefined,
): string | number {
  if (r.routeClass !== 'LINK') return '';
  return r.perNode.reduce((sum, node) => sum + (figure(node) ?? 0), 0);
}

/**
 * The columns of the routes view.
 *
 * <p>There is no origin column. Artemis records nothing saying whether a divert
 * came from broker.xml or from a management call, and exposes no way to read
 * configured-but-undeployed diverts, so the product does not guess (ADR-0065).
 * What it can say honestly is which routes are its own, and it says that.
 */
export function routeColumns(clusterId: string): Column<RouteView>[] {
  return [
    { id: 'name', header: 'Name', accessor: (r) => r.name, kind: 'identifier', priority: 'essential', sortKey: 'name' },
    kindColumn<RouteView>(),
    {
      id: 'direction',
      header: 'Route',
      accessor: (r) => {
        const { from, to } = routeEnds(r);
        return `${from} → ${to}`;
      },
      cell: (r) => createElement(RouteDirection, { route: r }),
      kind: 'identifier',
      priority: 'high',
      min: 24,
      max: 80,
    },
    {
      id: 'state',
      header: 'State',
      accessor: (r) => routeState(r),
      cell: (r) => createElement(RouteState, { route: r }),
      kind: 'status',
      priority: 'high',
    },
    {
      id: 'pending',
      header: 'Pending',
      description: 'Messages a link has sent and the other broker has not yet acknowledged, on every node',
      accessor: (r) => linkTotal(r, (n) => n.messagesPendingAcknowledgement),
      kind: 'number',
      priority: 'high',
    },
    {
      id: 'acked',
      header: 'Acked',
      accessor: (r) => linkTotal(r, (n) => n.messagesAcknowledged),
      kind: 'number',
      priority: 'low',
    },
    { id: 'filter', header: 'Filter', accessor: (r) => r.nativeAttributes.filter ?? '', kind: 'code', priority: 'low' },
    {
      id: 'owner',
      header: 'Created by',
      accessor: (r) => r.owner ?? '',
      cell: (r) => createElement(OwnerCell, { route: r }),
      kind: 'status',
      priority: 'low',
    },
    {
      id: 'nodes',
      header: 'Nodes',
      accessor: (r) => `${r.nodesPresent}/${r.nodesTotal}`,
      cell: (r) => createElement(NodesPresent, { route: r }),
      kind: 'number',
      priority: 'essential',
    },
    {
      id: 'action',
      header: 'Action',
      accessor: () => '',
      cell: (r) => (r.kind === DIVERT_KIND ? createElement(DeleteDivertAction, { clusterId, divert: r }) : null),
      kind: 'status',
      priority: 'low',
    },
  ];
}
