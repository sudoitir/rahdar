import { useMemo } from 'react';
import { CloseButton, SegmentedControl, Text } from '@mantine/core';

import { useSlot } from '../../kernel/slots.ts';
import { METRIC_RANGES, type MetricRange } from '../../kernel/time/ranges.ts';
import { EmptyState } from '../../ui/EmptyState.tsx';
import { LoadingState } from '../../ui/LoadingState.tsx';
import { Section } from '../../ui/Section.tsx';
import { DataTable } from '../../ui/table/index.ts';
import type { FlowGraphView, FlowNodeShare, FlowNodeView } from './api.ts';
import { nodeColumns, type NodeRow, type NodeShape } from './columns.ts';
import { FAULT_LABELS } from './flowFormat.ts';
import { imbalance } from './imbalance.ts';
import classes from './FlowView.module.css';

const KIND_WORD: Record<string, string> = {
  PRODUCER: 'Producing client',
  CONSUMER: 'Consuming client',
  DESTINATION: 'Address',
  SUBSCRIPTION: 'Queue',
  REMOTE: 'Remote',
};

const rowKey = (r: NodeRow) => r.nodeId;

function NodeTable({ label, rows, shape }: Readonly<{ label: string; rows: NodeRow[]; shape: NodeShape }>) {
  const columns = useMemo(() => nodeColumns(shape), [shape]);
  return (
    <DataTable
      label={label}
      storageKey={`flow.nodes.${shape}`}
      height={{ maxRows: 8 }}
      columns={columns}
      data={rows}
      rowKey={rowKey}
      empty={
        <EmptyState
          kind="empty"
          title="No broker node has reported"
          description="Each row is a broker node of this cluster, with what it holds and moves now. Rahdar lists a node once it has read it, so there is nothing here until the first scrape of a node completes."
        />
      }
    />
  );
}

/** A client's rates per node, from its edges: what it produces is messages in, what it consumes is out. */
function clientRows(graph: FlowGraphView, node: FlowNodeView): NodeRow[] {
  const rows = new Map<string, NodeRow>();
  for (const edge of graph.edges ?? []) {
    const produced = edge.source === node.id;
    if (!produced && edge.target !== node.id) continue;
    for (const r of edge.byNode ?? []) {
      const row = rows.get(r.nodeId) ?? { nodeId: r.nodeId, node: r.node, stale: false };
      row.stale ||= r.stale;
      const field = produced ? 'inRate' : 'outRate';
      if (r.rate !== null && r.rate !== undefined) row[field] = (row[field] ?? 0) + r.rate;
      rows.set(r.nodeId, row);
    }
  }
  return [...rows.values()].sort((a, b) => a.node.localeCompare(b.node));
}

/** With nothing selected: each broker node's totals now. */
function BrokerNodeTotals({ graph, stale }: Readonly<{ graph: FlowGraphView; stale: boolean }>) {
  const rows: NodeRow[] = (graph.brokerNodes ?? []).map((b) => ({
    nodeId: b.nodeId ?? b.name ?? '',
    node: b.name ?? b.nodeId ?? '',
    messageCount: b.backlog,
    consumerCount: b.consumers,
    inRate: b.inRate,
    outRate: b.outRate,
    stale: b.state === 'UNREACHABLE' || b.state === 'FAILED',
  }));
  return (
    <div className={classes.pane}>
      <Section
        variant="card"
        headingLevel={3}
        title="Broker nodes"
        description={
          <>
            {stale ? 'The selection is not in the shown paths any more. ' : ''}
            Select a client, address or queue to break it down per node. These are each node&rsquo;s totals now.
          </>
        }
      >
        <NodeTable label="Totals per broker node" rows={rows} shape="resource" />
      </Section>
    </div>
  );
}

type Panels = ReturnType<typeof useSlot<'flow.selection.panels'>>;

const SHAPE: Record<string, NodeShape> = { PRODUCER: 'producer', CONSUMER: 'consumer' };

/** The per-node table with its imbalance statements, or why there is none (still asking, or nothing reports it). */
function Breakdown({
  node,
  rows,
  statements,
  client,
  pending,
}: Readonly<{
  node: FlowNodeView;
  rows: NodeRow[];
  statements: ReturnType<typeof imbalance>;
  client: boolean;
  pending: boolean;
}>) {
  if (pending) {
    return <LoadingState label="Breaking this down per node" blockSize="8rem" />;
  }
  if (rows.length === 0) {
    return (
      <Text size="sm" c="dimmed">
        {client
          ? 'No per-node rate is measured for this client yet.'
          : 'No broker node reports this on its own, so there is no per-node breakdown.'}
      </Text>
    );
  }
  return (
    <>
      {statements.length ? (
        <ul className={classes.statements} aria-label="Balance across nodes">
          {statements.map((s) => (
            <li key={s.text} data-kind={s.kind}>
              <Text size="sm" fw={s.kind === 'balanced' || s.kind === 'unknown' ? undefined : 600}>
                {s.text}
              </Text>
            </li>
          ))}
        </ul>
      ) : null}
      <NodeTable label={`${node.label} per node`} rows={rows} shape={SHAPE[node.kind ?? ''] ?? 'resource'} />
    </>
  );
}

/** A queue's history per node from the contributed panels; a client keeps none. */
function History({
  clusterId,
  node,
  client,
  panels,
  range,
  onRangeChange,
}: Readonly<{
  clusterId: string;
  node: FlowNodeView;
  client: boolean;
  panels: Panels;
  range: MetricRange;
  onRangeChange: (range: MetricRange) => void;
}>) {
  if (client) {
    return (
      <Text size="sm" c="dimmed">
        Client history is not kept, so there are no trends for a client.
      </Text>
    );
  }
  if (node.kind !== 'SUBSCRIPTION' || panels.length === 0) return null;
  return (
    <Section
      headingLevel={3}
      title="Over time"
      actions={
        <SegmentedControl
          size="xs"
          aria-label="History range"
          data={METRIC_RANGES.map((r) => ({ value: r, label: r }))}
          value={range}
          onChange={(value) => onRangeChange(value as MetricRange)}
        />
      }
    >
      {panels.map(({ id, Component }) => (
        <Component key={id} clusterId={clusterId} queueName={node.label ?? ''} range={range} />
      ))}
    </Section>
  );
}

/**
 * The Split layout's monitoring pane (flow-visualization spec, ADR-0110): what the selection is
 * now, per broker node, with any imbalance stated in words; for a queue, its history per node from
 * whichever feature contributes `flow.selection.panels`. With nothing selected, each broker node's
 * totals. It reads only what the flow graph already carries, and never changes the broker.
 */
export function FlowMonitorPane({
  clusterId,
  graph,
  nodeId,
  range,
  breakdownPending,
  onRangeChange,
  onClear,
}: Readonly<{
  clusterId: string;
  graph: FlowGraphView;
  nodeId: string | null;
  range: MetricRange;
  /** The graph on screen is the one from before the breakdown was asked for. */
  breakdownPending: boolean;
  onRangeChange: (range: MetricRange) => void;
  onClear: () => void;
}>) {
  const panels = useSlot('flow.selection.panels');
  const node = nodeId ? (graph.nodes ?? []).find((n) => n.id === nodeId) : undefined;

  if (!node) return <BrokerNodeTotals graph={graph} stale={Boolean(nodeId)} />;

  const client = node.kind === 'PRODUCER' || node.kind === 'CONSUMER';
  const shares: FlowNodeShare[] = node.byNode ?? [];
  const rows: NodeRow[] = client ? clientRows(graph, node) : shares;
  const statements = client ? [] : imbalance(shares);
  const faults = (node.faults ?? []).map((f) => FAULT_LABELS[f] ?? f.toLowerCase());
  const kind = KIND_WORD[node.kind ?? ''] ?? 'Node';

  return (
    <div className={classes.pane}>
      <Section
        variant="card"
        headingLevel={3}
        title={`${kind} ${node.label}`}
        description="Broken down per broker node."
        actions={<CloseButton aria-label="Clear selection" onClick={onClear} />}
      >
        {faults.length ? (
          <Text size="sm" fw={600} className={classes.alarm}>
            {faults.join(', ')}
          </Text>
        ) : null}

        <Breakdown node={node} rows={rows} statements={statements} client={client} pending={breakdownPending} />

        <History
          clusterId={clusterId}
          node={node}
          client={client}
          panels={panels}
          range={range}
          onRangeChange={onRangeChange}
        />
      </Section>
    </div>
  );
}
