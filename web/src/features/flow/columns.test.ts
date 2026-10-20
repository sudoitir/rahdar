import { describe, expect, it } from 'vitest';

import type { FlowEdgeView } from './api.ts';
import { nodeColumns, pathColumns, type NodeRow, type PathRow } from './columns.ts';

const row = (edge: Partial<FlowEdgeView> = {}): PathRow => ({
  id: 'e1',
  edge: { id: 'e1', kind: 'PRODUCE', stale: false, ...edge } as FlowEdgeView,
  from: { id: 'p1', kind: 'PRODUCER', label: 'app-1', members: 3 },
  to: { id: 'q1', kind: 'SUBSCRIPTION', label: 'orders' },
  faults: ['no consumer'],
});

describe('flow columns', () => {
  it('never hides either end of a path, and sorts by what the URL names', () => {
    const columns = pathColumns(0);
    const essential = columns.filter((c) => c.priority === 'essential').map((c) => c.id);
    expect(essential).toEqual(['from', 'to']);
    expect(columns.filter((c) => c.sortKey).map((c) => c.sortKey)).toEqual([
      'from',
      'relation',
      'to',
      'rate',
      'clients',
      'faults',
    ]);
  });

  it('measures a path end by what its cell shows: its kind, its name and its client count', () => {
    const [from, , to] = pathColumns(0);
    expect(from.accessor(row())).toBe('client app-1 ×3');
    expect(to.accessor(row())).toBe('subscription orders');
  });

  it('puts the node first and attributes every figure to it, whatever the shape', () => {
    for (const shape of ['resource', 'producer', 'consumer'] as const) {
      const [node, ...figures] = nodeColumns(shape);
      expect(node.priority).toBe('essential');
      expect(figures.every((c) => c.kind === 'number')).toBe(true);
    }
    expect(nodeColumns('producer').map((c) => c.header)).toEqual(['Node', 'Sends/s']);
    expect(nodeColumns('consumer').map((c) => c.header)).toEqual(['Node', 'Receives/s']);
  });

  it('states a node that did not answer as unknown, never as a figure', () => {
    const stale: NodeRow = { nodeId: 'n1', node: 'node-a', messageCount: 0, inRate: 0, stale: true };
    const backlog = nodeColumns('resource').find((c) => c.id === 'backlog');
    expect(backlog?.accessor(stale)).toBe('unknown');
    expect(backlog?.accessor({ ...stale, stale: false, messageCount: 1234 })).toBe('1,234');
  });
});
