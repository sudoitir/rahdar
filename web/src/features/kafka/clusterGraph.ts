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
 * One line. DiagramView names a box by joining kind, label and detail with commas, and draws a
 * problem word before the label while naming it after. Either split fails the check that the visible
 * words are a contiguous run of the accessible name, so the role, the place and the reach are one label.
 */
function brokerNode(broker: ClusterGraphBroker): DiagramNode {
  const where = place(broker.rack, broker.address);
  const reach = broker.reachable ? null : `Not reached. ${broker.reason ?? 'The cluster gave no reason.'}`;
  return {
    id: brokerNodeId(broker.id),
    label: [`Broker ${broker.id}`, broker.quorumLeader ? 'quorum leader' : null, where, reach]
      .filter(Boolean)
      .join(', '),
  };
}

function controllerNode(controller: ClusterGraphController): DiagramNode {
  const role = controller.quorumLeader ? 'Quorum leader, controller' : 'Controller';
  return { id: `controller:${controller.id}`, label: `${role} ${controller.id}` };
}

function edgeLabel(shared: number, outOfSync: number): string {
  const noun = shared === 1 ? 'partition' : 'partitions';
  const base = `${shared} shared ${noun}`;
  if (outOfSync === 0) return base;
  const sync = outOfSync === 1 ? 'replica is' : 'replicas are';
  return `${base}, ${outOfSync} ${sync} out of sync`;
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
      label: edgeLabel(e.sharedPartitions, e.outOfSyncReplicas),
      dashed: e.outOfSyncReplicas > 0,
      weight: e.sharedPartitions,
    })),
    summary: { omittedEdges: ranked.length - drawn.length },
  };
}
