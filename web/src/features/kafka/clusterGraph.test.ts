import { describe, expect, it } from 'vitest';

import { layoutSignature } from '../../ui/diagram.ts';
import { GRAPH_EDGE_CAP, clusterGraph, type ClusterGraphInput } from './clusterGraph.ts';

function view(over: Partial<ClusterGraphInput> = {}): ClusterGraphInput {
  return { brokers: [], controllers: [], edges: [], ...over };
}

const three = view({
  brokers: [
    { id: '1', rack: 'a', address: 'k1:9092', reachable: true, quorumLeader: true },
    { id: '2', rack: 'b', address: 'k2:9092', reachable: true, quorumLeader: false },
    { id: '3', address: 'k3:9092', reachable: true, quorumLeader: false },
  ],
  edges: [
    { a: '1', b: '2', sharedPartitions: 4, outOfSyncReplicas: 0 },
    { a: '2', b: '3', sharedPartitions: 1, outOfSyncReplicas: 0 },
    { a: '1', b: '3', sharedPartitions: 9, outOfSyncReplicas: 0 },
  ],
});

describe('clusterGraph', () => {
  it('draws a healthy cluster as boxes joined by the pairs that share partitions', () => {
    const graph = clusterGraph(three);
    expect(graph.nodes.map((n) => n.id)).toEqual(['broker:1', 'broker:2', 'broker:3']);
    expect(graph.nodes[0]?.label).toBe('Broker 1, quorum leader, a · k1:9092');
    expect(graph.nodes[2]?.label).toBe('Broker 3, k3:9092');
    expect(graph.edges.map((e) => e.id)).toEqual(['1-3', '1-2', '2-3']);
    expect(graph.edges[0]).toMatchObject({ label: '9 shared partitions', weight: 9, dashed: false });
    expect(graph.edges[2]?.label).toBe('1 shared partition');
    expect(graph.summary.omittedEdges).toBe(0);
    expect(layoutSignature(graph.nodes, graph.edges, 'RIGHT')).toBe(
      layoutSignature(
        graph.nodes,
        graph.edges.map((e) => ({ ...e, weight: 1 })),
        'RIGHT',
      ),
    );
  });

  it('says an unreachable broker is not reached, and dashes an edge whose replica is out of sync', () => {
    const graph = clusterGraph(
      view({
        brokers: [
          { id: '1', reachable: true, quorumLeader: false },
          { id: '2', reachable: false, reason: 'Connection refused.', quorumLeader: false },
        ],
        edges: [{ a: '1', b: '2', sharedPartitions: 4, outOfSyncReplicas: 2 }],
      }),
    );
    expect(graph.nodes[1]?.label).toBe('Broker 2, Not reached. Connection refused.');
    expect(graph.nodes[1]?.state).toBeUndefined();
    expect(graph.edges[0]).toMatchObject({
      dashed: true,
      label: '4 shared partitions, 2 replicas are out of sync',
    });
  });

  it('draws no quorum node for a ZooKeeper cluster, and no edge for a single broker', () => {
    const zk = clusterGraph(view({ brokers: [{ id: '1', reachable: true, quorumLeader: false }] }));
    expect(zk.nodes).toHaveLength(1);
    expect(zk.nodes.some((n) => n.label.includes('Controller') || n.label.includes('quorum'))).toBe(false);
    expect(zk.edges).toEqual([]);
  });

  it('draws a controller-only node outside the brokers, with no co-replication edge', () => {
    const graph = clusterGraph(
      view({
        brokers: [{ id: '1', reachable: true, quorumLeader: false }],
        controllers: [{ id: '100', quorumLeader: true }],
        edges: [{ a: '1', b: '100', sharedPartitions: 3, outOfSyncReplicas: 0 }],
      }),
    );
    expect(graph.nodes.map((n) => n.id)).toEqual(['broker:1', 'controller:100']);
    expect(graph.nodes[1]?.label).toBe('Quorum leader, controller 100');
    expect(graph.edges).toEqual([]);
    expect(graph.summary.omittedEdges).toBe(0);
  });

  it('draws the strongest 200 pairs and counts the rest', () => {
    const edges = Array.from({ length: GRAPH_EDGE_CAP + 1 }, (_, i) => ({
      a: '1',
      b: String(i + 2),
      sharedPartitions: i + 1,
      outOfSyncReplicas: 0,
    }));
    const brokers = [{ id: '1', reachable: true, quorumLeader: false }].concat(
      edges.map((e) => ({ id: e.b, reachable: true, quorumLeader: false })),
    );
    const graph = clusterGraph(view({ brokers, edges }));
    expect(graph.edges).toHaveLength(GRAPH_EDGE_CAP);
    expect(graph.summary.omittedEdges).toBe(1);
    expect(graph.edges[0]?.weight).toBe(GRAPH_EDGE_CAP + 1);
    expect(graph.edges.some((e) => e.weight === 1)).toBe(false);
  });
});
