import { METRIC_RANGES, type MetricRange } from '../../kernel/time/ranges.ts';
import type { FlowNodeView } from './api.ts';

/**
 * What the flow view is showing, as URL search params (non-negotiable #9): a shared or reloaded
 * destination restores the same focus, ranking, grouping, bound, layers, sort, layout and selection.
 */

export const FLOW_RANKS = ['IN', 'OUT', 'BACKLOG'] as const;
export const FLOW_GROUPINGS = ['CLIENT_ID', 'USER', 'HOST'] as const;
export const FLOW_LIMITS = [20, 40, 100, 200] as const;
export const DEFAULT_LIMIT = 40;

/** Routing drawn around the paths; the server's default is forwards, links and cluster hops. */
export const FLOW_LAYERS = ['ROUTES', 'CLUSTER', 'DEAD_LETTER', 'TEMPORARY', 'CAPTURE'] as const;

export type FlowRank = (typeof FLOW_RANKS)[number];
export type FlowGroupBy = (typeof FLOW_GROUPINGS)[number];
export type FlowLayer = (typeof FLOW_LAYERS)[number];

export const DEFAULT_LAYERS: readonly FlowLayer[] = ['CLUSTER', 'ROUTES'];

export interface FlowSearch {
  /** The graph is the default view; the table is its accessible twin; split adds the monitoring pane. */
  tab?: 'table' | 'split';
  /** The selected node's id (`subscription:<name>`, `client:<name>`…): what the inspector or the pane shows. */
  node?: string;
  /** The window of the split pane's trends; absent for an hour. */
  range?: MetricRange;
  /** `client:<name>`, `destination:<name>` or `subscription:<name>`. */
  focus?: string;
  hops?: number;
  rank?: FlowRank;
  limit?: number;
  groupBy?: FlowGroupBy;
  /** Comma-separated layers, sorted; `NONE` for no routing layers; absent for the defaults. */
  layers?: string;
  /** Table sort: a column id, `-` prefixed for descending. */
  sort?: string;
}

const FOCUS = /^(client|destination|subscription):.+$/;

export function validateFlowSearch(raw: Record<string, unknown>): FlowSearch {
  return { ...validateView(raw), ...validateBounds(raw) };
}

/** Which view is open and what it is looking at: the tab, the selection, the focus, the sort, the layers. */
function validateView(raw: Record<string, unknown>): FlowSearch {
  const out: FlowSearch = {};
  if (raw.tab === 'table' || raw.tab === 'split') out.tab = raw.tab;
  if (typeof raw.node === 'string' && raw.node) out.node = raw.node;
  if (typeof raw.focus === 'string' && FOCUS.test(raw.focus)) out.focus = raw.focus;
  if (typeof raw.layers === 'string') {
    const layers = layersParam(parseLayers(raw.layers));
    if (layers !== undefined) out.layers = layers;
  }
  if (typeof raw.sort === 'string' && /^-?[a-z]+$/.test(raw.sort)) out.sort = raw.sort;
  return out;
}

/** How much the view asks for: range, reach, ranking, limit and grouping — the defaults left out. */
function validateBounds(raw: Record<string, unknown>): FlowSearch {
  const out: FlowSearch = {};
  const range = nonDefault(raw.range, METRIC_RANGES, '1h');
  if (range) out.range = range;
  const hops = Number(raw.hops);
  if (Number.isInteger(hops) && hops >= 2 && hops <= 3) out.hops = hops;
  const rank = nonDefault(raw.rank, FLOW_RANKS, 'IN');
  if (rank) out.rank = rank;
  const limit = Number(raw.limit);
  if ((FLOW_LIMITS as readonly number[]).includes(limit) && limit !== DEFAULT_LIMIT) out.limit = limit;
  const groupBy = nonDefault(raw.groupBy, FLOW_GROUPINGS, 'CLIENT_ID');
  if (groupBy) out.groupBy = groupBy;
  return out;
}

/** A search value that names one of the choices and is not the default the URL leaves out. */
function nonDefault<T extends string>(value: unknown, choices: readonly T[], fallback: T): T | undefined {
  if (typeof value !== 'string' || value === fallback) return undefined;
  return (choices as readonly string[]).includes(value) ? (value as T) : undefined;
}

/** The layers a search asks for: the defaults when absent, none for `NONE`, unknown names ignored. */
export function parseLayers(layers: string | undefined): FlowLayer[] {
  if (layers === undefined) return [...DEFAULT_LAYERS];
  return layers
    .split(',')
    .map((l) => l.trim().toUpperCase())
    .filter((l): l is FlowLayer => (FLOW_LAYERS as readonly string[]).includes(l))
    .sort((a, b) => a.localeCompare(b));
}

/** The URL value for a set of layers: undefined for the defaults, `NONE` for an empty set. */
export function layersParam(layers: readonly FlowLayer[]): string | undefined {
  const sorted = [...new Set(layers)].sort((a, b) => a.localeCompare(b));
  if (sorted.join(',') === [...DEFAULT_LAYERS].sort((a, b) => a.localeCompare(b)).join(',')) return undefined;
  return sorted.length === 0 ? 'NONE' : sorted.join(',');
}

/** `subscription:orders` → `{ kind: 'subscription', name: 'orders' }`; anything else → null. */
export function parseFocus(
  focus: string | undefined,
): { kind: 'client' | 'destination' | 'subscription'; name: string } | null {
  if (!focus || !FOCUS.test(focus)) return null;
  const colon = focus.indexOf(':');
  return { kind: focus.slice(0, colon) as 'client' | 'destination' | 'subscription', name: focus.slice(colon + 1) };
}

/** The URL focus for a node, or null for one the server cannot focus on (a remote node). */
export function focusOf(node: FlowNodeView): string | null {
  switch (node.kind) {
    case 'SUBSCRIPTION':
      return `subscription:${node.label}`;
    case 'DESTINATION':
      return `destination:${node.label}`;
    case 'PRODUCER':
    case 'CONSUMER':
      return `client:${node.label}`;
    default:
      return null;
  }
}

/** Synthetic nodes that stand for many resources, or none: nothing to open or copy. */
const NO_MENU_ROLES = new Set(['TEMPORARY', 'ANONYMOUS']);

/** Whether a flow node is one resource a menu can be about. */
export function hasActions(node: FlowNodeView): boolean {
  return (
    ['SUBSCRIPTION', 'DESTINATION', 'PRODUCER', 'CONSUMER'].includes(node.kind ?? '') &&
    !NO_MENU_ROLES.has(node.role ?? '') &&
    Boolean(node.label)
  );
}
