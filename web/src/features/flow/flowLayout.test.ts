import { describe, expect, it } from 'vitest';

import type { FlowGraphView } from './api.ts';
import { layoutSignature, pathThrough, reuseUnchanged, toElkGraph, toReactFlow, COLUMNS } from './flowLayout.ts';
import { runLayout } from './useFlowLayout.ts';

const node = (id: string, kind: string, label: string, over: object = {}) => ({ id, kind, label, faults: [], ...over });
const edge = (source: string, target: string, kind: string, rate?: number) => ({
  id: `${kind}:${source}->${target}`,
  kind,
  source,
  target,
  rate,
  rateSource: kind === 'ROUTE' ? 'SUBSCRIPTION_METRIC' : 'SAMPLER',
  stale: false,
  faults: [],
  bypassed: false,
  rahdar: false,
});

function graph(rate = 10): FlowGraphView {
  return {
    nodes: [
      node('consumer:billing', 'CONSUMER', 'billing'),
      node('subscription:ORDERS', 'SUBSCRIPTION', 'ORDERS', { messageCount: 50 }),
      node('subscription:AUDIT', 'SUBSCRIPTION', 'AUDIT', { messageCount: 200 }),
      node('destination:ORDERS', 'DESTINATION', 'ORDERS'),
      node('producer:order-svc', 'PRODUCER', 'order-svc'),
      node('producer:web', 'PRODUCER', 'web'),
      node('consumer:auditor', 'CONSUMER', 'auditor'),
    ],
    edges: [
      edge('producer:order-svc', 'destination:ORDERS', 'PRODUCE', rate),
      edge('producer:web', 'destination:ORDERS', 'PRODUCE', 1),
      edge('destination:ORDERS', 'subscription:ORDERS', 'ROUTE', rate),
      edge('destination:ORDERS', 'subscription:AUDIT', 'ROUTE', rate),
      edge('subscription:ORDERS', 'consumer:billing', 'CONSUME', rate),
      edge('subscription:AUDIT', 'consumer:auditor', 'CONSUME'),
    ],
    measuring: false,
    sampleIntervalSeconds: 15,
  } as FlowGraphView;
}

describe('flow layout', () => {
  it('places producers, addresses, queues and consumers in strictly ordered columns', async () => {
    const g = graph();
    const positions = await runLayout(toElkGraph(g));
    const x = (kind: string) => g.nodes!.filter((n) => n.kind === kind).map((n) => positions[n.id!].x);

    for (let i = 1; i < COLUMNS.length; i++) {
      expect(Math.max(...x(COLUMNS[i - 1]))).toBeLessThan(Math.min(...x(COLUMNS[i])));
    }
  });

  it('puts other nodes and remote brokers in a column after the consumers', async () => {
    const g = graph();
    g.nodes!.push(node('remote:node:abc', 'REMOTE', 'node-b', { role: 'CLUSTER_NODE' }) as never);
    g.nodes!.push(
      node('subscription:$.artemis.internal.sf.demo.abc', 'SUBSCRIPTION', 'sf', { role: 'STORE_AND_FORWARD' }) as never,
    );
    g.edges!.push(edge('subscription:$.artemis.internal.sf.demo.abc', 'remote:node:abc', 'CLUSTER_HOP', 7) as never);
    const positions = await runLayout(toElkGraph(g));

    const consumers = g.nodes!.filter((n) => n.kind === 'CONSUMER').map((n) => positions[n.id!].x);
    expect(positions['remote:node:abc'].x).toBeGreaterThan(Math.max(...consumers));
    const model = toReactFlow(g, positions, new Map(), null);
    expect(model.nodes.find((n) => n.id === 'remote:node:abc')?.type).toBe('remote');
  });

  it('keeps the same layout signature when only rates change', () => {
    expect(layoutSignature(graph(10))).toBe(layoutSignature(graph(9_000)));
    const grown = graph();
    grown.nodes!.push(node('subscription:NEW', 'SUBSCRIPTION', 'NEW') as never);
    expect(layoutSignature(grown)).not.toBe(layoutSignature(graph()));
  });

  it('emphasises the whole path through a node and dims the rest', async () => {
    const g = graph();
    const positions = await runLayout(toElkGraph(g));
    const path = pathThrough(g, 'subscription:ORDERS');

    expect([...path].sort()).toEqual(
      ['destination:ORDERS', 'consumer:billing', 'producer:order-svc', 'producer:web', 'subscription:ORDERS'].sort(),
    );
    const model = toReactFlow(g, positions, new Map(), path);
    expect(model.nodes.find((n) => n.id === 'subscription:AUDIT')?.data.dimmed).toBe(true);
    expect(model.nodes.find((n) => n.id === 'subscription:ORDERS')?.data.dimmed).toBe(false);
    expect(model.edges.find((e) => e.target === 'subscription:AUDIT')?.data?.dimmed).toBe(true);
  });

  it('hands back the elements an emphasis leaves alone, so only the changed ones render again', async () => {
    const g = graph();
    const positions = await runLayout(toElkGraph(g));
    const plain = toReactFlow(g, positions, new Map(), null);
    const emphasised = toReactFlow(g, positions, new Map(), pathThrough(g, 'subscription:ORDERS'));

    const nodes = reuseUnchanged(plain.nodes, emphasised.nodes);
    const edges = reuseUnchanged(plain.edges, emphasised.edges);
    const kept = (id: string) => nodes.find((n) => n.id === id) === plain.nodes.find((n) => n.id === id);
    expect(kept('subscription:ORDERS')).toBe(true);
    expect(kept('lane:SUBSCRIPTION')).toBe(true);
    expect(kept('subscription:AUDIT')).toBe(false);
    expect(nodes.find((n) => n.id === 'subscription:AUDIT')?.data.dimmed).toBe(true);
    expect(edges.find((e) => e.target === 'subscription:AUDIT')).not.toBe(
      plain.edges.find((e) => e.target === 'subscription:AUDIT'),
    );
    expect(edges.find((e) => e.target === 'subscription:ORDERS')).toBe(
      plain.edges.find((e) => e.target === 'subscription:ORDERS'),
    );
  });

  it('labels each column once, and scales queue depth against the deepest shown queue', async () => {
    const g = graph();
    const model = toReactFlow(g, await runLayout(toElkGraph(g)), new Map(), null);

    expect(model.nodes.filter((n) => n.type === 'lane').map((n) => n.data.title)).toEqual([
      'Producers',
      'Addresses',
      'Queues',
      'Consumers',
    ]);
    expect(model.nodes.find((n) => n.id === 'subscription:AUDIT')?.data.depth).toBe(1);
    expect(model.nodes.find((n) => n.id === 'subscription:ORDERS')?.data.depth).toBe(0.25);
  });

  it('leaves out a node that has no position yet, with its edges', () => {
    const g = graph();
    const model = toReactFlow(
      g,
      { 'producer:web': { x: 0, y: 0 }, 'destination:ORDERS': { x: 300, y: 0 } },
      new Map(),
      null,
    );

    expect(
      model.nodes
        .filter((n) => n.type !== 'lane')
        .map((n) => n.id)
        .sort(),
    ).toEqual(['destination:ORDERS', 'producer:web']);
    expect(model.edges.map((e) => e.id)).toEqual(['PRODUCE:producer:web->destination:ORDERS']);
  });
});
