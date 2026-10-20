import { describe, expect, it } from 'vitest';

import type { LogicalNodeView, NodeEndpointView, TopologyView } from './api.ts';
import { allNodeFacts, lastSeenWords, nodeFacts, pairVerdict, withoutTrailingStops } from './nodeFacts.ts';

function endpoint(over: Partial<NodeEndpointView> = {}): NodeEndpointView {
  return {
    id: 'e1',
    name: 'broker-a',
    artemisNodeId: 'NID',
    jolokiaUrl: 'http://broker-a:8161/jolokia',
    coreUrl: 'broker-a:61616',
    haRole: 'PRIMARY',
    state: 'STARTED',
    active: true,
    replicaSync: null,
    version: '2.44.0',
    versionSupport: 'SUPPORTED',
    lastError: null,
    lastSeenAt: '2026-09-30T14:00:00Z',
    urlSource: null,
    urlProblem: null,
    coreUrlManual: false,
    manageable: true,
    ...over,
  };
}

function pair(
  endpoints: NodeEndpointView[],
  over: Partial<LogicalNodeView> = {},
): { logical: LogicalNodeView; of: (id: string) => ReturnType<typeof nodeFacts> } {
  const logical: LogicalNodeView = {
    artemisNodeId: 'NID',
    splitBrain: 'NONE',
    replicationBehind: false,
    endpoints,
    ...over,
  };
  return {
    logical,
    of: (id) =>
      nodeFacts(
        endpoints.find((e) => e.id === id)!,
        logical,
      ),
  };
}

const primary = endpoint({ id: 'p', name: 'broker-a' });
const backup = endpoint({ id: 'b', name: 'broker-b', haRole: 'BACKUP', active: false, replicaSync: true });

describe('nodeFacts: role', () => {
  it.each([
    ['PRIMARY', 'Primary'],
    ['BACKUP', 'Backup'],
    ['NONE', 'Standalone'],
    ['SOMETHING_NEW', 'Unknown'],
  ])('%s reads %s', (haRole, role) => {
    expect(nodeFacts(endpoint({ haRole }), pair([endpoint({ haRole })]).logical).role).toBe(role);
  });
});

describe('nodeFacts: liveness, in words', () => {
  it('a serving primary is live', () => {
    expect(pair([primary, backup]).of('p').liveness).toEqual({
      kind: 'live',
      label: 'Live, serving',
      text: 'Live, serving',
    });
    expect(pair([primary, backup]).of('p').mark).toBe('live');
  });

  it('two serving nodes in one pair are a split brain, and each names the other', () => {
    const other = endpoint({ id: 'x', name: 'broker-x', active: true });
    const facts = pair([primary, other], { splitBrain: 'CRITICAL' }).of('p');
    expect(facts.liveness).toMatchObject({
      kind: 'split-brain',
      label: 'Split brain',
      text: 'Split brain: serving alongside broker-x',
      tone: 'danger',
    });
    expect(facts.mark).toBe('split');
    expect(facts.pair).toBe('paired with broker-x, split brain: both serving');
  });

  it('a suspected split brain is still live, with the suspicion in words', () => {
    const facts = pair([primary, backup], { splitBrain: 'SUSPECTED' }).of('p');
    expect(facts.liveness).toMatchObject({ kind: 'live', tone: 'warning' });
    expect(facts.liveness.text).toContain('split brain suspected');
    expect(facts.pair).toBe('paired with broker-b, split brain suspected');
  });

  it('a backup in step is replicating and in sync', () => {
    const facts = pair([primary, backup]).of('b');
    expect(facts.liveness).toMatchObject({ kind: 'in-sync', text: 'Backup, replicating, in sync' });
    expect(facts.liveness.tone).toBeUndefined();
    expect(facts.mark).toBe('standby');
  });

  it('a backup that is not in sync is not caught up', () => {
    const behind = endpoint({ ...backup, replicaSync: false });
    const facts = pair([primary, behind], { replicationBehind: true }).of('b');
    expect(facts.liveness).toMatchObject({ kind: 'behind', text: 'Backup, not caught up', tone: 'warning' });
    expect(facts.mark).toBe('behind');
    expect(facts.pair).toBe('paired with broker-a, replication behind');
  });

  it('a passive node whose replication is not reported is on standby', () => {
    const facts = pair([primary, endpoint({ ...backup, replicaSync: null })]).of('b');
    expect(facts.liveness).toMatchObject({ kind: 'standby', label: 'Standby', text: 'Standby' });
    expect(facts.pair).toBe('paired with broker-a, replication not reported');
  });

  it('a stopped node is stopped', () => {
    const stopped = endpoint({ id: 's', state: 'STOPPED', active: false });
    expect(pair([stopped]).of('s').liveness).toMatchObject({ kind: 'stopped', text: 'Stopped', tone: 'warning' });
    expect(pair([stopped]).of('s').mark).toBe('down');
  });

  it('an unreachable node says so, with the error', () => {
    const failing = endpoint({ id: 'f', lastError: 'connection refused' });
    const facts = pair([failing]).of('f');
    expect(facts.liveness).toMatchObject({
      kind: 'unreachable',
      label: 'Unreachable',
      text: 'Unreachable: connection refused',
    });
    expect(facts.lastError).toBe('connection refused');
    expect(facts.mark).toBe('down');
  });

  it('a node with no management URL is not polled, and that outranks everything else', () => {
    const found = endpoint({ id: 'u', jolokiaUrl: null, manageable: false, active: false, lastError: 'ignored' });
    const facts = pair([found]).of('u');
    expect(facts.liveness).toMatchObject({
      kind: 'not-polled',
      text: 'Not polled: no management URL',
    });
    expect(facts.mark).toBe('unmanaged');
    expect(facts.manageable).toBe(false);
  });
});

describe('nodeFacts: pair', () => {
  it('names its partner and the pair in step', () => {
    expect(pair([primary, backup]).of('p').pair).toBe('paired with broker-b, in sync');
    expect(pair([primary, backup]).of('b').pair).toBe('paired with broker-a, in sync');
  });

  it('a lone node has no pair', () => {
    expect(pair([endpoint({ haRole: 'NONE' })]).of('e1').pair).toBe('no pair (standalone)');
  });

  it('a backup alone says its primary is not seen', () => {
    expect(pair([backup]).of('b').pair).toBe('no pair: its primary is not seen');
  });
});

describe('nodeFacts: version, address, origin and the sentence', () => {
  it('states the version, and the note when it is outside what Rahdar supports', () => {
    const old = pair([endpoint({ version: '2.31.2', versionSupport: 'BELOW_MINIMUM' })]).of('e1');
    expect(old).toMatchObject({
      version: '2.31.2',
      versionLabel: 'Artemis 2.31.2',
      versionNote: 'unsupported release: older than Rahdar supports',
      versionFlag: 'unsupported',
    });
    const newer = pair([endpoint({ version: '2.60.0', versionSupport: 'NEWER_THAN_TESTED' })]).of('e1');
    expect(newer).toMatchObject({ versionNote: 'newer release than Rahdar has tested', versionFlag: 'untested' });
    expect(pair([primary]).of('p')).toMatchObject({ versionNote: null, versionFlag: null });
  });

  it('a node that reports no version says so', () => {
    expect(pair([endpoint({ version: null })]).of('e1')).toMatchObject({
      version: null,
      versionLabel: 'Version unknown',
    });
  });

  it('shows the management host and port, then the Core URL', () => {
    expect(pair([primary]).of('p').address).toBe('broker-a:8161');
    expect(pair([endpoint({ jolokiaUrl: 'https://broker.example/jolokia' })]).of('e1').address).toBe('broker.example');
    expect(pair([endpoint({ jolokiaUrl: 'not a url' })]).of('e1').address).toBe('not a url');
    expect(pair([endpoint({ jolokiaUrl: null })]).of('e1').address).toBe('broker-a:61616');
    expect(pair([endpoint({ jolokiaUrl: null, coreUrl: null })]).of('e1').address).toBeNull();
  });

  it('says how Rahdar found the node', () => {
    expect(pair([endpoint({ urlSource: 'MANUAL' })]).of('e1').origin).toBe('Management URL set by hand');
    expect(pair([endpoint({ urlSource: 'DERIVED' })]).of('e1').origin).toBe('Derived from the management URL pattern');
    expect(pair([endpoint({ urlSource: 'SEED' })]).of('e1').origin).toBe('From the registered seed address');
    expect(pair([endpoint({ urlSource: null, urlProblem: 'OTHER_BROKER' })]).of('e1').origin).toBe(
      'No management URL: a different broker answered at the address the pattern gives',
    );
  });

  it('falls back to the pair id when the endpoint has no NodeID of its own', () => {
    expect(pair([endpoint({ artemisNodeId: null })]).of('e1').artemisNodeId).toBe('NID');
  });

  it('writes one sentence a screen reader can read as the box name', () => {
    expect(pair([primary, backup]).of('p').sentence).toBe(
      'broker-a: Primary. Live, serving. Paired with broker-b, in sync. Artemis 2.44.0.',
    );
    expect(pair([endpoint({ lastError: 'connection refused' })]).of('e1').sentence).toBe(
      'broker-a: Primary. Unreachable: connection refused. No pair (standalone). Artemis 2.44.0.',
    );
    expect(
      pair([endpoint({ version: '2.31.2', versionSupport: 'BELOW_MINIMUM', state: 'STOPPED', lastError: null })]).of(
        'e1',
      ).sentence,
    ).toContain('Stopped. No pair (standalone). Artemis 2.31.2, unsupported release: older than Rahdar supports.');
  });
});

describe('withoutTrailingStops', () => {
  it('drops every full stop at the end and only those', () => {
    expect(withoutTrailingStops('Live, serving.')).toBe('Live, serving');
    expect(withoutTrailingStops('Wait...')).toBe('Wait');
    expect(withoutTrailingStops('v2.44.0')).toBe('v2.44.0');
    expect(withoutTrailingStops('a. b')).toBe('a. b');
    expect(withoutTrailingStops('')).toBe('');
    expect(withoutTrailingStops('...')).toBe('');
  });

  it('stays fast on a long run of full stops', () => {
    expect(withoutTrailingStops(`x${'.'.repeat(200_000)}y${'.'.repeat(200_000)}`)).toBe(`x${'.'.repeat(200_000)}y`);
  });
});

describe('lastSeenWords', () => {
  const now = Date.parse('2026-09-30T14:00:42Z');

  it('says how long ago, and the exact time', () => {
    const seen = lastSeenWords('2026-09-30T14:00:00Z', now);
    expect(seen.relative).toBe('42s ago');
    expect(seen.absolute).toMatch(/2026-09-30 \d\d:00:00/);
  });

  it('says just now inside a second', () => {
    expect(lastSeenWords('2026-09-30T14:00:42Z', now).relative).toBe('just now');
  });

  it('says so when a node has never answered', () => {
    expect(lastSeenWords(null, now)).toEqual({ relative: 'Not seen yet', absolute: 'Never answered' });
  });
});

describe('allNodeFacts and pairVerdict', () => {
  it('lists every endpoint pair by pair in NodeID order, the serving one first', () => {
    const topology: TopologyView = {
      clusterId: 'c',
      nodes: [
        { artemisNodeId: 'B', splitBrain: 'NONE', replicationBehind: false, endpoints: [endpoint({ id: 'b1' })] },
        {
          artemisNodeId: 'A',
          splitBrain: 'NONE',
          replicationBehind: false,
          endpoints: [backup, primary],
        },
      ],
    };
    expect(allNodeFacts(topology).map((f) => f.id)).toEqual(['p', 'b', 'b1']);
  });

  it('says how the pair stands as a whole', () => {
    expect(pairVerdict(pair([primary, backup]).logical)).toEqual({ verdict: '1 of 2 nodes serving' });
    expect(pairVerdict(pair([primary]).logical)).toEqual({ verdict: '1 of 1 node serving' });
    expect(pairVerdict(pair([primary, backup], { replicationBehind: true }).logical)).toEqual({
      verdict: 'Replication is behind',
      tone: 'warning',
    });
    expect(pairVerdict(pair([primary, backup], { splitBrain: 'SUSPECTED' }).logical).tone).toBe('warning');
    expect(pairVerdict(pair([primary, backup], { splitBrain: 'CRITICAL' }).logical)).toEqual({
      verdict: 'Split brain: two nodes are serving',
      tone: 'danger',
    });
  });
});
