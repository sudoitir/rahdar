import { absoluteLabel, elapsedLabel } from '../../kernel/time/time.ts';
import { urlProblemWords, urlSourceWords } from './connectionWords.ts';
import type { LogicalNodeView, NodeEndpointView, TopologyView } from './api.ts';

/**
 * What the Topology view says about one broker endpoint, in words, worked out once.
 *
 * <p>The box on the canvas, the row in the table and the side panel all read these facts, so the three
 * can never tell different stories about the same node. Pure: it takes the endpoint and the logical node
 * (the pair) it belongs to, and nothing else. Colour is only ever an emphasis on a fact that is already
 * a sentence: `tone` is set for a fault and left out for a node that is as it should be.
 */

/** The high-availability role an endpoint plays in its pair. */
export type Role = 'Primary' | 'Backup' | 'Standalone' | 'Unknown';

/** The shape of the mark drawn beside the liveness word. Each is told apart by shape, not by brightness. */
export type NodeKind = 'live' | 'standby' | 'behind' | 'down' | 'unmanaged' | 'split';

export type LivenessKind =
  'live' | 'split-brain' | 'in-sync' | 'behind' | 'standby' | 'stopped' | 'unreachable' | 'not-polled';

export interface Liveness {
  kind: LivenessKind;
  /** The short form, for the second line of a box: "Unreachable". */
  label: string;
  /** The whole sentence, with the error or the reason: "Unreachable: connection refused". */
  text: string;
  tone?: 'warning' | 'danger';
}

export interface NodeFacts {
  id: string;
  name: string;
  role: Role;
  liveness: Liveness;
  /** Which mark the box draws. */
  mark: NodeKind;
  /** "paired with broker-b, in sync", or "no pair (standalone)". */
  pair: string;
  /** The version, such as "2.40.0"; null when the broker has not reported one. */
  version: string | null;
  /** "Artemis 2.40.0", or "Version unknown". */
  versionLabel: string;
  /** Why this release is outside what Rahdar supports or has tested, in words; null when it is neither. */
  versionNote: string | null;
  /** The note in two words, for a box: "unsupported", "untested". */
  versionFlag: string | null;
  /** The management host and port, falling back to the Core URL; null when neither is known. */
  address: string | null;
  jolokiaUrl: string | null;
  coreUrl: string | null;
  /** The ID the pair shares; null when the broker has not reported one. */
  artemisNodeId: string | null;
  lastError: string | null;
  /** An ISO instant; null when the node has never answered. */
  lastSeenAt: string | null;
  /** How Rahdar came to know this node, in words. */
  origin: string;
  /** Whether Rahdar can poll it: false for a node found but given no management URL. */
  manageable: boolean;
  /** The box's accessible name, and what is announced when it is chosen. */
  sentence: string;
}

/** An endpoint that answers and reports itself active: the one above the axis. */
export function isServing(endpoint: NodeEndpointView): boolean {
  return endpoint.active && !endpoint.lastError;
}

function roleOf(endpoint: NodeEndpointView): Role {
  switch (endpoint.haRole) {
    case 'PRIMARY':
      return 'Primary';
    case 'BACKUP':
      return 'Backup';
    case 'NONE':
      return 'Standalone';
    default:
      return 'Unknown';
  }
}

/** The supported-range verdict as words (ADR-0142); colour only repeats it. */
export function versionNoteOf(endpoint: NodeEndpointView): string | null {
  switch (endpoint.versionSupport) {
    case 'BELOW_MINIMUM':
      return 'unsupported release: older than Rahdar supports';
    case 'NEWER_THAN_TESTED':
      return 'newer release than Rahdar has tested';
    default:
      return null;
  }
}

function versionFlagOf(endpoint: NodeEndpointView): string | null {
  switch (endpoint.versionSupport) {
    case 'BELOW_MINIMUM':
      return 'unsupported';
    case 'NEWER_THAN_TESTED':
      return 'untested';
    default:
      return null;
  }
}

function host(url: string | null | undefined): string | null {
  if (!url) return null;
  try {
    const u = new URL(url);
    return u.port ? `${u.hostname}:${u.port}` : u.hostname;
  } catch {
    return url;
  }
}

function servingAlongside(endpoint: NodeEndpointView, logical: LogicalNodeView): string[] {
  return logical.endpoints.filter((e) => e.id !== endpoint.id && isServing(e)).map((e) => e.name);
}

function activeLiveness(endpoint: NodeEndpointView, logical: LogicalNodeView): Liveness {
  if (logical.splitBrain === 'CRITICAL') {
    const others = servingAlongside(endpoint, logical);
    return {
      kind: 'split-brain',
      label: 'Split brain',
      text: others.length
        ? `Split brain: serving alongside ${others.join(', ')}`
        : 'Split brain: two nodes are serving',
      tone: 'danger',
    };
  }
  if (logical.splitBrain === 'SUSPECTED') {
    return {
      kind: 'live',
      label: 'Live, split brain suspected',
      text: 'Live, serving; split brain suspected: two nodes report active',
      tone: 'warning',
    };
  }
  return { kind: 'live', label: 'Live, serving', text: 'Live, serving' };
}

function passiveLiveness(endpoint: NodeEndpointView, role: Role): Liveness {
  if (endpoint.replicaSync === false) {
    return { kind: 'behind', label: 'Backup, not caught up', text: 'Backup, not caught up', tone: 'warning' };
  }
  if (role === 'Backup' && endpoint.replicaSync === true) {
    return { kind: 'in-sync', label: 'Backup, replicating, in sync', text: 'Backup, replicating, in sync' };
  }
  return { kind: 'standby', label: 'Standby', text: 'Standby' };
}

function livenessOf(endpoint: NodeEndpointView, logical: LogicalNodeView, role: Role): Liveness {
  if (!endpoint.manageable) {
    return { kind: 'not-polled', label: 'Not polled', text: `Not polled: ${urlProblemWords(endpoint.urlProblem)}` };
  }
  if (endpoint.lastErrorKind === 'CREDENTIALS_REJECTED') {
    return {
      kind: 'unreachable',
      label: 'Credentials rejected',
      text: 'The broker rejected the management account',
      tone: 'warning',
    };
  }
  if (endpoint.lastError) {
    return {
      kind: 'unreachable',
      label: 'Unreachable',
      text: `Unreachable: ${endpoint.lastError}`,
      tone: 'warning',
    };
  }
  if (endpoint.state === 'STOPPED') return { kind: 'stopped', label: 'Stopped', text: 'Stopped', tone: 'warning' };
  return endpoint.active ? activeLiveness(endpoint, logical) : passiveLiveness(endpoint, role);
}

function markOf(kind: LivenessKind): NodeKind {
  switch (kind) {
    case 'live':
      return 'live';
    case 'split-brain':
      return 'split';
    case 'behind':
      return 'behind';
    case 'stopped':
    case 'unreachable':
      return 'down';
    case 'not-polled':
      return 'unmanaged';
    default:
      return 'standby';
  }
}

/** The state of the pair as a whole, in words, for the end of "paired with …". */
function pairStateOf(logical: LogicalNodeView): string {
  if (logical.splitBrain === 'CRITICAL') return 'split brain: both serving';
  if (logical.splitBrain === 'SUSPECTED') return 'split brain suspected';
  if (logical.replicationBehind) return 'replication behind';
  return logical.endpoints.some((e) => e.replicaSync === true) ? 'in sync' : 'replication not reported';
}

function pairOf(endpoint: NodeEndpointView, logical: LogicalNodeView, role: Role): string {
  const others = logical.endpoints.filter((e) => e.id !== endpoint.id);
  if (others.length === 0) return role === 'Backup' ? 'no pair: its primary is not seen' : 'no pair (standalone)';
  return `paired with ${others.map((e) => e.name).join(', ')}, ${pairStateOf(logical)}`;
}

function originOf(endpoint: NodeEndpointView): string {
  const source = urlSourceWords(endpoint.urlSource);
  if (source) return source;
  return endpoint.urlProblem ? `No management URL: ${urlProblemWords(endpoint.urlProblem)}` : 'No management URL yet';
}

/** `text` without the full stops it ends in; a scan from the end, so it is linear however many there are. */
export function withoutTrailingStops(text: string): string {
  let end = text.length;
  while (end > 0 && text[end - 1] === '.') end--;
  return text.slice(0, end);
}

function sentenceOf(facts: Omit<NodeFacts, 'sentence'>): string {
  const note = facts.versionNote ? `, ${facts.versionNote}` : '';
  const pair = `${facts.pair.charAt(0).toUpperCase()}${facts.pair.slice(1)}`;
  const parts = [
    `${facts.name}: ${facts.role}`,
    facts.liveness.text,
    pair,
    `${facts.versionLabel}${note}`,
    // An unreachable node's error is already in its liveness.
    facts.lastError && facts.liveness.kind !== 'unreachable' ? `Last error: ${facts.lastError}` : null,
  ];
  return `${parts
    .filter((p) => p !== null)
    .map((p) => withoutTrailingStops(p))
    .join('. ')}.`;
}

/** Every fact the view states about `endpoint`, which belongs to `logical`. */
export function nodeFacts(endpoint: NodeEndpointView, logical: LogicalNodeView): NodeFacts {
  const role = roleOf(endpoint);
  const liveness = livenessOf(endpoint, logical, role);
  const facts = {
    id: endpoint.id,
    name: endpoint.name,
    role,
    liveness,
    mark: markOf(liveness.kind),
    pair: pairOf(endpoint, logical, role),
    version: endpoint.version ?? null,
    versionLabel: endpoint.version ? `Artemis ${endpoint.version}` : 'Version unknown',
    versionNote: versionNoteOf(endpoint),
    versionFlag: versionFlagOf(endpoint),
    address: host(endpoint.jolokiaUrl) ?? endpoint.coreUrl ?? null,
    jolokiaUrl: endpoint.jolokiaUrl ?? null,
    coreUrl: endpoint.coreUrl ?? null,
    artemisNodeId: endpoint.artemisNodeId ?? logical.artemisNodeId ?? null,
    lastError: endpoint.lastError ?? null,
    lastSeenAt: endpoint.lastSeenAt ?? null,
    origin: originOf(endpoint),
    manageable: endpoint.manageable,
  };
  return { ...facts, sentence: sentenceOf(facts) };
}

/** When a node last answered, as how long ago and as the exact time. `now` is the caller's clock, so a table's rows share one. */
export function lastSeenWords(at: string | null, now: number): { relative: string; absolute: string } {
  if (!at || !Number.isFinite(Date.parse(at))) return { relative: 'Not seen yet', absolute: 'Never answered' };
  const ms = now - Date.parse(at);
  return { relative: ms < 1_000 ? 'just now' : `${elapsedLabel(ms)} ago`, absolute: absoluteLabel(at) };
}

/** Every endpoint of the topology, pair by pair in the order the graph draws them: the serving one first. */
export function allNodeFacts(topology: TopologyView): NodeFacts[] {
  const pairs = [...topology.nodes].sort((a, b) => (a.artemisNodeId ?? '').localeCompare(b.artemisNodeId ?? ''));
  return pairs.flatMap((logical) =>
    [...logical.endpoints]
      .sort((a, b) => Number(isServing(b)) - Number(isServing(a)))
      .map((e) => nodeFacts(e, logical)),
  );
}

/** How the pair stands as a whole, in one sentence, with the tone that repeats it where something is wrong. */
export function pairVerdict(logical: LogicalNodeView): { verdict: string; tone?: 'warning' | 'danger' } {
  if (logical.splitBrain === 'CRITICAL') return { verdict: 'Split brain: two nodes are serving', tone: 'danger' };
  if (logical.splitBrain === 'SUSPECTED')
    return { verdict: 'Split brain suspected: two nodes report active', tone: 'warning' };
  if (logical.replicationBehind) return { verdict: 'Replication is behind', tone: 'warning' };
  const serving = logical.endpoints.filter(isServing).length;
  const count = logical.endpoints.length;
  return { verdict: `${serving} of ${count} ${count === 1 ? 'node' : 'nodes'} serving` };
}
