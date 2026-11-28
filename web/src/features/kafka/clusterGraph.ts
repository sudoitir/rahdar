import type { DiagramEdge, DiagramNode } from '../../ui/diagram.ts';

/** Co-replication pairs drawn. The rest are counted, not dropped from the table. */
export const GRAPH_EDGE_CAP = 200;

export interface ClusterGraphBroker {
  id: string;
  rack?: string | null;
  address?: string | null;
  reachable: boolean;
  reason?: string | null;
  quorumLeader: boolean;
}

export interface ClusterGraphController {
  id: string;
  quorumLeader: boolean;
}

export interface ClusterGraphPair {
  a: string;
  b: string;
  sharedPartitions: number;
  outOfSyncReplicas: number;
}

/** The fields the graph reads. A `ClusterView` satisfies it. */
export interface ClusterGraphInput {
  brokers: ClusterGraphBroker[];
  controllers: ClusterGraphController[];
  edges: ClusterGraphPair[];
}

export interface ClusterGraph {
  nodes: DiagramNode[];
  edges: DiagramEdge[];
  summary: { omittedEdges: number };
}

export function brokerNodeId(id: string): string {
  return `broker:${id}`;
}

function place(rack?: string | null, address?: string | null): string | undefined {
  const parts = [rack, address].filter((p): p is string => Boolean(p));
  return parts.length ? parts.join(' · ') : undefined;
}

/**
 * The reach word is its own line. One detail line shares the box with the address, and ellipsis then
 * cuts "Not reached" before the address. The accessible name is kind, label, detail, in that order
 * (nodeName), and DiagramView draws them in that order, so the visible words stay a contiguous run.
 */
function brokerNode(broker: ClusterGraphBroker): DiagramNode {
  const where = place(broker.rack, broker.address);
  const reach = broker.reachable ? null : `Not reached. ${broker.reason ?? 'The cluster gave no reason.'}`;
  return {
    id: brokerNodeId(broker.id),
    kind: [`Broker ${broker.id}`, broker.quorumLeader ? 'quorum leader' : null].filter(Boolean).join(', '),
    label: reach ?? where ?? `Broker ${broker.id}`,
    detail: reach && where ? where : undefined,
  };
}

function controllerNode(controller: ClusterGraphController): DiagramNode {
  const role = controller.quorumLeader ? 'Quorum leader, controller' : 'Controller';
  return { id: `controller:${controller.id}`, label: `${role} ${controller.id}` };
}

/**
 * The chip sits in the gap between the 320px boxes, so it is the shared count only. The sentence is what
 * DiagramView folds into the target's accessible name; the chip's words are a contiguous run of it.
 * A dashed stroke is the out-of-sync mark, and its count is spoken.
 */
function edgeCopy(shared: number, outOfSync: number): { label: string; spoken: string } {
  const count = shared.toLocaleString();
  const noun = shared === 1 ? 'partition' : 'partitions';
  const spoken =
    outOfSync > 0 ? `${count} shared ${noun}, ${outOfSync.toLocaleString()} out of sync` : `${count} shared ${noun}`;
  return { label: count, spoken };
}

/**
 * Boxes and co-replication arrows for one cluster snapshot. Edges are the strongest pairs, capped, in a
 * stable order. A weight does not move a box. Nothing here writes.
 */
export function clusterGraph(view: ClusterGraphInput): ClusterGraph {
  const brokers = new Set(view.brokers.map((b) => b.id));
  const ranked = view.edges
    .filter((e) => brokers.has(e.a) && brokers.has(e.b))
    .map((e) => ({ ...e, id: `${e.a}-${e.b}` }))
    .sort((x, y) => y.sharedPartitions - x.sharedPartitions || x.id.localeCompare(y.id));
  const drawn = ranked.slice(0, GRAPH_EDGE_CAP);
  return {
    nodes: [...view.brokers.map(brokerNode), ...view.controllers.map(controllerNode)],
    edges: drawn.map((e) => ({
      id: e.id,
      source: brokerNodeId(e.a),
      target: brokerNodeId(e.b),
      ...edgeCopy(e.sharedPartitions, e.outOfSyncReplicas),
      dashed: e.outOfSyncReplicas > 0,
      weight: e.sharedPartitions,
    })),
    summary: { omittedEdges: ranked.length - drawn.length },
  };
}
