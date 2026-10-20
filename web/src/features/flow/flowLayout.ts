import type { Edge, Node } from '@xyflow/react';
import type { ElkNode } from 'elkjs/lib/elk-api';

import type { FlowEdgeView, FlowGraphView, FlowNodeView } from './api.ts';

/**
 * The flow graph's geometry (ADR-0080): four fixed columns laid out by ELK layered, pure so it is
 * tested with the real engine and run unchanged in a web worker.
 */

export const COLUMNS = ['PRODUCER', 'DESTINATION', 'SUBSCRIPTION', 'CONSUMER', 'REMOTE'] as const;
export type Column = (typeof COLUMNS)[number];

export const COLUMN_TITLES: Record<Column, string> = {
  PRODUCER: 'Producers',
  DESTINATION: 'Destinations',
  SUBSCRIPTION: 'Subscriptions',
  CONSUMER: 'Consumers',
  REMOTE: 'Other nodes and brokers',
};

export const NODE_SIZE: Record<Column, { width: number; height: number }> = {
  PRODUCER: { width: 216, height: 64 },
  DESTINATION: { width: 208, height: 58 },
  SUBSCRIPTION: { width: 228, height: 78 },
  CONSUMER: { width: 216, height: 64 },
  REMOTE: { width: 208, height: 60 },
};

/** Above this many nodes the canvas renders only what is on screen and shows a minimap (ADR-0056). */
export const DENSE_NODES = 80;

const LANE_OFFSET = 52;

/** How far the tag's point and notch, and the hexagon's points, reach in from the side, in px. */
export const POINT = 14;
export const NOTCH = 10;

/** The outline of a tag or a hexagon `w` by `h` px, as SVG polygon points. */
export function outline(shape: 'tag' | 'hex', w: number, h: number): string {
  const points =
    shape === 'tag'
      ? [
          [0, 0],
          [w - POINT, 0],
          [w, h / 2],
          [w - POINT, h],
          [0, h],
          [NOTCH, h / 2],
        ]
      : [
          [POINT, 0],
          [w - POINT, 0],
          [w, h / 2],
          [w - POINT, h],
          [POINT, h],
          [0, h / 2],
        ];
  return points.map(([x, y]) => `${x},${y}`).join(' ');
}

export type Positions = Record<string, { x: number; y: number }>;

export interface FlowNodeData extends Record<string, unknown> {
  view: FlowNodeView;
  /** 0–1: this subscription's backlog against the deepest shown subscription; 0 for other kinds. */
  depth: number;
  dimmed: boolean;
}

export interface FlowEdgeData extends Record<string, unknown> {
  view: FlowEdgeView;
  dots: number;
  dimmed: boolean;
}

export interface LaneData extends Record<string, unknown> {
  title: string;
  count: number;
}

function column(node: FlowNodeView): Column {
  return (COLUMNS as readonly string[]).includes(node.kind ?? '') ? (node.kind as Column) : 'SUBSCRIPTION';
}

/**
 * What positions depend on: which nodes exist and how they connect — never rates. A refresh that
 * changes only rates keeps the signature, so no node moves.
 */
export function layoutSignature(graph: FlowGraphView): string {
  const nodes = (graph.nodes ?? []).map((n) => n.id ?? '').sort((a, b) => a.localeCompare(b));
  const edges = (graph.edges ?? []).map((e) => `${e.source}>${e.target}`).sort((a, b) => a.localeCompare(b));
  return `${nodes.join('|')}#${edges.join('|')}`;
}

export function toElkGraph(graph: FlowGraphView): ElkNode {
  // Model order puts the busiest paths first, so a view that opens at the top opens on traffic, and an
  // idle dead-letter or redistribution subscription sinks to the bottom of its column.
  const busiest = new Map<string, number>();
  for (const e of graph.edges ?? []) {
    for (const id of [e.source!, e.target!]) busiest.set(id, Math.max(busiest.get(id) ?? 0, e.rate ?? 0));
  }
  const nodes = [...(graph.nodes ?? [])].sort(
    (a, b) =>
      COLUMNS.indexOf(column(a)) - COLUMNS.indexOf(column(b)) ||
      (busiest.get(b.id!) ?? 0) - (busiest.get(a.id!) ?? 0) ||
      (a.label ?? '').localeCompare(b.label ?? ''),
  );
  const ids = new Set(nodes.map((n) => n.id));
  return {
    id: 'flow',
    layoutOptions: {
      'elk.algorithm': 'layered',
      'elk.direction': 'RIGHT',
      'elk.partitioning.activate': 'true',
      // Unrelated paths are separate components, and ELK lays components out one by one and packs
      // them side by side — which would put a later column's node left of an earlier one. One layout
      // for the whole graph keeps every column where the partitions say.
      'elk.separateConnectedComponents': 'false',
      'elk.layered.considerModelOrder.strategy': 'NODES_AND_EDGES',
      'elk.layered.spacing.nodeNodeBetweenLayers': '104',
      'elk.spacing.nodeNode': '22',
      'elk.layered.nodePlacement.strategy': 'BRANDES_KOEPF',
    },
    children: nodes.map((n) => ({
      id: n.id!,
      ...NODE_SIZE[column(n)],
      layoutOptions: { 'elk.partitioning.partition': String(COLUMNS.indexOf(column(n))) },
    })),
    edges: (graph.edges ?? [])
      .filter((e) => ids.has(e.source) && ids.has(e.target))
      .map((e) => ({ id: e.id!, sources: [e.source!], targets: [e.target!] })),
  };
}

export function positionsFrom(laidOut: ElkNode): Positions {
  const out: Positions = {};
  for (const child of laidOut.children ?? []) {
    out[child.id] = { x: child.x ?? 0, y: child.y ?? 0 };
  }
  return out;
}

/** Every node reachable upstream and downstream of `id`: the whole path an emphasis should keep. */
export function pathThrough(graph: FlowGraphView, id: string): Set<string> {
  const out = new Map<string, string[]>();
  const into = new Map<string, string[]>();
  for (const e of graph.edges ?? []) {
    out.set(e.source!, [...(out.get(e.source!) ?? []), e.target!]);
    into.set(e.target!, [...(into.get(e.target!) ?? []), e.source!]);
  }
  const seen = new Set<string>([id]);
  for (const links of [out, into]) {
    const stack = [id];
    while (stack.length) {
      const next = stack.pop()!;
      for (const n of links.get(next) ?? []) {
        if (!seen.has(n)) {
          seen.add(n);
          stack.push(n);
        }
      }
    }
  }
  return seen;
}

/**
 * The React Flow model for a graph at known positions. A node whose position is not known yet — it
 * appeared since the last layout — is left out, with its edges, until the next layout lands.
 */
export function toReactFlow(
  graph: FlowGraphView,
  positions: Positions,
  dots: Map<string, number>,
  emphasis: Set<string> | null,
): { nodes: Node[]; edges: Edge[] } {
  const placed = (graph.nodes ?? []).filter((n) => positions[n.id!]);
  const deepest = Math.max(1, ...placed.map((n) => n.messageCount ?? 0));
  const nodes: Node[] = placed.map((n) => ({
    id: n.id!,
    type: {
      SUBSCRIPTION: 'subscription',
      DESTINATION: 'destination',
      REMOTE: 'remote',
      PRODUCER: 'client',
      CONSUMER: 'client',
    }[column(n)],
    position: positions[n.id!],
    ...NODE_SIZE[column(n)],
    draggable: false,
    connectable: false,
    focusable: false,
    data: {
      view: n,
      depth: column(n) === 'SUBSCRIPTION' ? Math.min(1, (n.messageCount ?? 0) / deepest) : 0,
      dimmed: emphasis !== null && !emphasis.has(n.id!),
    } satisfies FlowNodeData,
  }));

  for (const col of COLUMNS) {
    const inColumn = placed.filter((n) => column(n) === col);
    if (inColumn.length === 0) continue;
    const xs = inColumn.map((n) => positions[n.id!].x);
    const top = Math.min(...placed.map((n) => positions[n.id!].y));
    nodes.push({
      id: `lane:${col}`,
      type: 'lane',
      position: { x: Math.min(...xs), y: top - LANE_OFFSET },
      width: NODE_SIZE[col].width,
      height: 32,
      draggable: false,
      connectable: false,
      selectable: false,
      focusable: false,
      data: { title: COLUMN_TITLES[col], count: inColumn.length } satisfies LaneData,
    });
  }

  const ids = new Set(placed.map((n) => n.id));
  const edges: Edge[] = (graph.edges ?? [])
    .filter((e) => ids.has(e.source) && ids.has(e.target))
    .map((e) => ({
      id: e.id!,
      source: e.source!,
      target: e.target!,
      type: 'flow',
      focusable: false,
      selectable: false,
      data: {
        view: e,
        dots: dots.get(e.id!) ?? 0,
        dimmed: emphasis !== null && !(emphasis.has(e.source!) && emphasis.has(e.target!)),
      } satisfies FlowEdgeData,
    }));
  return { nodes, edges };
}

function sameRecord(a: Record<string, unknown> | undefined, b: Record<string, unknown> | undefined): boolean {
  if (a === b) return true;
  if (!a || !b) return false;
  const keys = Object.keys(a);
  return keys.length === Object.keys(b).length && keys.every((k) => Object.is(a[k], b[k]));
}

/**
 * `next`, with every element that says the same as its counterpart in `previous` swapped for that
 * counterpart: a hover that dims two nodes hands React Flow two new nodes, not a whole new graph,
 * so the memoised nodes and edges it leaves alone do not render again.
 */
export function reuseUnchanged<T extends Node | Edge>(previous: readonly T[], next: T[]): T[] {
  if (previous.length === 0) return next;
  const before = new Map(previous.map((element) => [element.id, element]));
  return next.map((element) => {
    const old = before.get(element.id);
    if (!old) return element;
    const { data, position, ...rest } = element as T & { position?: { x: number; y: number } };
    const { data: oldData, position: oldPosition, ...oldRest } = old as T & { position?: { x: number; y: number } };
    const samePosition = position?.x === oldPosition?.x && position?.y === oldPosition?.y;
    return samePosition && sameRecord(rest, oldRest) && sameRecord(data, oldData) ? old : element;
  });
}
