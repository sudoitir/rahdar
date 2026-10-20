import { http, HttpResponse } from 'msw';

import type { Finding, TransferRunView } from './api.ts';
import { paged } from '../../kernel/api/paging.ts';

/** Test fixtures for the transfer screens. Placeholder names only. */

const KINDS = [
  { id: 'artemis.queue', resourceClass: 'SUBSCRIPTION', label: 'Queue', verbs: [] },
  { id: 'artemis.address', resourceClass: 'DESTINATION', label: 'Address', verbs: [] },
];

export const AVAILABLE = { status: 'AVAILABLE', reason: 'ok', brokerXmlSnippet: null };

export function endpoint(id: string, name: string, over: Record<string, unknown> = {}) {
  return {
    id,
    name,
    artemisNodeId: id,
    jolokiaUrl: `http://${name}:8161/console/jolokia`,
    coreUrl: `tcp://${name}:61616`,
    haRole: 'PRIMARY',
    state: 'LIVE',
    active: true,
    replicaSync: null,
    version: '2.57.0',
    versionSupport: 'SUPPORTED',
    lastError: null,
    lastSeenAt: null,
    urlSource: null,
    urlProblem: null,
    coreUrlManual: false,
    manageable: true,
    ...over,
  };
}

/** Two live primaries, plus a backup that must be listed and unchoosable. */
export function topology(clusterId: string) {
  return {
    clusterId,
    nodes: [
      { artemisNodeId: 'n1', splitBrain: 'NONE', replicationBehind: false, endpoints: [endpoint('n1', 'node-a')] },
      { artemisNodeId: 'n2', splitBrain: 'NONE', replicationBehind: false, endpoints: [endpoint('n2', 'node-b')] },
      {
        artemisNodeId: 'n3',
        splitBrain: 'NONE',
        replicationBehind: false,
        endpoints: [endpoint('n3', 'node-b-backup', { haRole: 'BACKUP', active: false, state: 'BACKUP' })],
      },
    ],
  };
}

export function clusterHandlers(
  topologyOf: (clusterId: string) => ReturnType<typeof topology> = topology,
  capabilities = {},
) {
  const detail = (id: string, name: string) => ({
    id,
    name,
    description: null,
    topology: topologyOf(id),
    kinds: KINDS,
    capabilities: {
      managementRead: AVAILABLE,
      managementWrite: AVAILABLE,
      notifications: AVAILABLE,
      messageIo: AVAILABLE,
      slowConsumerDetection: AVAILABLE,
      versionGates: [],
      ...capabilities,
    },
    health: { level: 'OK', reasons: [] },
    environmentId: null,
  });
  return [
    http.get('*/api/v1/clusters/c1', () => HttpResponse.json(detail('c1', 'primary'))),
    http.get('*/api/v1/clusters/c2', () => HttpResponse.json(detail('c2', 'dr-site'))),
    http.get('*/api/v1/clusters', () =>
      HttpResponse.json(
        paged([
          { id: 'c1', name: 'primary', nodeCount: 2, updatedAt: '2026-09-21T10:00:00Z', environmentId: null },
          { id: 'c2', name: 'dr-site', nodeCount: 2, updatedAt: '2026-09-21T10:00:00Z', environmentId: null },
        ]),
      ),
    ),
    http.get('*/api/v1/clusters/:id/subscriptions', () => HttpResponse.json({ items: [], total: 0 })),
  ];
}

export function meHandler(permissions: string[] = ['*']) {
  return http.get('*/api/v1/auth/me', () =>
    HttpResponse.json({
      id: 'u1',
      username: 'admin',
      mustChangePassword: false,
      grants: [{ scopeType: 'GLOBAL', scopeId: null, permissions }],
    }),
  );
}

export const END = (clusterId: string, nodeId: string, nodeName: string, queue: string) => ({
  clusterId,
  nodeId,
  nodeName,
  subscription: `artemis.queue/${queue}`,
  destination: `artemis.address/${queue}`,
});

export function run(over: Partial<TransferRunView> = {}): TransferRunView {
  return {
    id: 'r1',
    mode: 'MOVE',
    state: 'PREVIEWED',
    source: END('c1', 'n1', 'node-a', 'orders'),
    target: END('c2', 'n1', 'node-a', 'orders'),
    sameNode: false,
    selection: { kind: 'ALL', ids: null, filter: null },
    t0: '2026-09-21T10:00:00Z',
    planHash: 'h1',
    username: 'admin',
    createdAt: '2026-09-21T10:00:00Z',
    expiresAt: '2026-09-21T10:10:00Z',
    startedAt: null,
    updatedAt: null,
    finishedAt: null,
    estimate: 1200,
    estimateBytes: 84_000_000,
    staged: 0,
    held: 0,
    delivered: 0,
    notTransferred: 0,
    expired: 0,
    returned: 0,
    bytes: 0,
    messagesPerSecond: null,
    stagingQueue: null,
    findings: [],
    notes: [],
    cap: 10_000,
    overCap: false,
    overrideCap: false,
    resumable: false,
    returnable: false,
    auditEventId: 7,
    targetAuditEventId: 8,
    lastError: null,
    errorSnippet: null,
    ...over,
  };
}

export const finding = (kind: Finding['kind'], code: string, words: string, snippet?: string): Finding => ({
  kind,
  code,
  words,
  snippet: snippet ?? null,
});

export const problem = (status: number, type: string, title: string, detail: string) =>
  HttpResponse.json({ type: `https://rahdar.dev/problems/${type}`, title, status, detail }, { status });

export function previewHandler(body: TransferRunView, bodies: unknown[] = []) {
  return http.post('*/api/v1/clusters/c1/transfers/preview', async ({ request }) => {
    bodies.push(await request.json());
    return HttpResponse.json(body, { status: 201 });
  });
}
