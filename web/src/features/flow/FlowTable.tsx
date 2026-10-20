import { useMemo } from 'react';
import { Text } from '@mantine/core';

import { useServerNow } from '../../kernel/time/time.ts';
import { EmptyState } from '../../ui/EmptyState.tsx';
import { DataTable } from '../../ui/table/index.ts';
import type { FlowGraphView, FlowNodeView } from './api.ts';
import { pathColumns, type PathRow } from './columns.ts';
import { FAULT_LABELS, RELATION, rateSortValue, unreachableNodes } from './flowFormat.ts';
import { focusOf, hasActions } from './flowSearch.ts';
import { FlowNodeActions } from './rowActions.tsx';

const rowKey = (r: PathRow) => r.id;

/**
 * The same paths the graph draws, as rows (flow-visualization spec: the table presents the same
 * paths as the graph). One row per edge; activating a row focuses its client or queue, and its
 * menu opens that resource elsewhere, never changing the broker.
 */
export function FlowTable({
  clusterId,
  graph,
  sort,
  loading,
  filtered,
  onSortChange,
  onFocus,
  onClearFilters,
}: Readonly<{
  clusterId: string;
  graph: FlowGraphView;
  sort: string | undefined;
  /** The paths on screen are the ones from before a new choice (a focus, a layer, a limit) was read. */
  loading: boolean;
  /** A focus or a layer choice is narrowing the paths. */
  filtered: boolean;
  onSortChange: (sort: string | undefined) => void;
  onFocus: (focus: string) => void;
  onClearFilters: () => void;
}>) {
  const now = useServerNow(5_000);
  const rows = useMemo(() => sortRows(toRows(graph), sort), [graph, sort]);
  const columns = useMemo(() => pathColumns(now), [now]);
  const unreachable = unreachableNodes(graph);

  return (
    <DataTable
      label="Flow paths"
      storageKey="flow.paths"
      height="fill"
      columns={columns}
      data={rows}
      loading={loading}
      sort={sort}
      onSortChange={onSortChange}
      rowKey={rowKey}
      onRowClick={(r) => {
        const subject = subjectOf(r);
        const focus = subject ? focusOf(subject) : null;
        if (focus) onFocus(focus);
      }}
      rowMenu={{
        label: (r) => subjectOf(r)?.label ?? r.id,
        render: (r, menu) => {
          const subject = subjectOf(r);
          return subject && hasActions(subject) ? (
            <FlowNodeActions clusterId={clusterId} node={subject} restoreFocus={menu.restoreFocus} />
          ) : (
            <Text size="sm" c="dimmed" px="sm" py="xs">
              Nothing to open for this path.
            </Text>
          );
        },
      }}
      empty={<NoPaths filtered={filtered} unreachable={unreachable} onClearFilters={onClearFilters} />}
    />
  );
}

function toRows(graph: FlowGraphView): PathRow[] {
  const byId = new Map((graph.nodes ?? []).map((n) => [n.id, n]));
  return (graph.edges ?? []).map((edge) => {
    const from = byId.get(edge.source ?? '');
    const to = byId.get(edge.target ?? '');
    const faults = [...(edge.faults ?? []), ...(edge.kind === 'ROUTE' ? (to?.faults ?? []) : [])].map(
      (f) => FAULT_LABELS[f] ?? f.toLowerCase(),
    );
    return { id: edge.id ?? `${edge.source}->${edge.target}`, edge, from, to, faults };
  });
}

/** The resource a row is about: its client, its queue, or the address it diverts to. */
function subjectOf(row: PathRow): FlowNodeView | undefined {
  switch (row.edge.kind) {
    case 'PRODUCE':
    case 'LINK':
    case 'CLUSTER_HOP':
      return row.from;
    case 'CONSUME':
    case 'ROUTE':
      return row.to;
    case 'FORWARD':
    case 'WILDCARD':
    case 'DEAD_LETTER':
    case 'EXPIRY':
      return row.to?.kind === 'DESTINATION' ? row.to : undefined;
    default:
      return undefined;
  }
}

/** Default: busiest first, unknown rates last. A column sort keeps unknown rates last in both directions. */
function sortRows(rows: PathRow[], sort: string | undefined): PathRow[] {
  const desc = sort ? sort.startsWith('-') : true;
  const field = sort ? sort.replace(/^-/, '') : 'rate';
  const dir = desc ? -1 : 1;
  const text = (r: PathRow): string => {
    switch (field) {
      case 'from':
        return r.from?.label ?? '';
      case 'to':
        return r.to?.label ?? '';
      case 'relation':
        return RELATION[r.edge.kind ?? ''] ?? '';
      default:
        return r.faults.join(',');
    }
  };
  return [...rows].sort((a, b) => {
    if (field === 'rate') {
      return dir * (rateSortValue(a.edge.rate, desc) - rateSortValue(b.edge.rate, desc)) || a.id.localeCompare(b.id);
    }
    if (field === 'clients') {
      return dir * ((a.edge.members ?? -1) - (b.edge.members ?? -1)) || a.id.localeCompare(b.id);
    }
    return dir * text(a).localeCompare(text(b)) || a.id.localeCompare(b.id);
  });
}

/** What an empty path table says: narrowed away, hidden by unreachable nodes, or genuinely none. */
function NoPaths({
  filtered,
  unreachable,
  onClearFilters,
}: Readonly<{
  filtered: boolean;
  unreachable: ReturnType<typeof unreachableNodes>;
  onClearFilters: () => void;
}>) {
  if (filtered) {
    return (
      <EmptyState
        kind="filtered"
        title="No path matches this focus and these layers"
        description="Paths exist on this cluster, but none is left once the view is narrowed to the focus and the layers you chose. Clear them to see every shown path again."
        onClearFilters={onClearFilters}
      />
    );
  }
  if (unreachable.length > 0) {
    return (
      <EmptyState
        kind="unreachable"
        title="No paths to list, and some nodes did not answer"
        description="There may be paths here that Rahdar cannot currently see. These nodes did not answer the last sweep, so this is an incomplete view rather than a cluster with no flow."
        nodes={unreachable}
      />
    );
  }
  return (
    <EmptyState
      kind="empty"
      title="No paths to list"
      description="A path is one hop of a message's journey: a client producing to an address, an address routing to a queue, a queue consumed by a client. Rahdar draws a path once it sees a producer, a consumer or a binding on this cluster, so an empty table means none has been seen yet."
    />
  );
}
