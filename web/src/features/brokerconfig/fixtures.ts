import { http, HttpResponse } from 'msw';

import type { ConfigApplyOutcomeView, ConfigCatalogueView, ConfigDeclarationView, ConfigNodeStateView } from './api.ts';
import { paged } from '../../kernel/api/paging.ts';

/** Test fixtures for the configuration screens. Placeholder names only. */

export const NODE_A: ConfigNodeStateView = {
  nodeId: 'n-a',
  nodeName: 'broker-1',
  live: true,
  state: 'IN_SYNC',
  detail: null,
  verifiedRevision: 3,
  evaluatedAt: '2026-09-11T10:00:00Z',
  findings: [],
};

export const NODE_B: ConfigNodeStateView = { ...NODE_A, nodeId: 'n-b', nodeName: 'broker-2' };

export function declaration(over: Partial<ConfigDeclarationView> = {}): ConfigDeclarationView {
  return {
    clusterId: 'c1',
    clusterName: 'prod',
    declared: true,
    revision: 3,
    document: {
      version: 1,
      addresses: [
        {
          name: 'orders.request',
          routingTypes: ['ANYCAST'],
          queues: [{ name: 'orders.request', routingType: 'ANYCAST', durable: true }],
        },
      ],
      addressSettings: [{ match: 'orders.#', values: { addressFullMessagePolicy: 'PAGE', maxSizeBytes: 104857600 } }],
      securitySettings: [{ match: 'orders.#', permissions: { send: ['app-role'], consume: ['app-role'] } }],
      diverts: [],
      bridges: [],
    },
    applyMode: 'RAHDAR_MANAGED',
    reportUndeclared: false,
    undeclaredExclusions: [],
    updatedAt: '2026-09-11T09:00:00Z',
    updatedBy: 'admin',
    source: 'EDIT',
    note: null,
    nodes: [NODE_A, NODE_B],
    driftIntervalSeconds: 300,
    ...over,
  };
}

export const CATALOGUE: ConfigCatalogueView = {
  addressSettingKeys: [
    {
      jsonName: 'addressFullMessagePolicy',
      xmlName: 'address-full-policy',
      type: 'ENUM',
      allowedValues: ['PAGE', 'DROP', 'FAIL', 'BLOCK'],
      hazardClass: 'HIGH',
      applicable: true,
    },
    {
      jsonName: 'maxSizeBytes',
      xmlName: 'max-size-bytes',
      type: 'LONG',
      allowedValues: [],
      hazardClass: 'HIGH',
      applicable: true,
    },
    {
      jsonName: 'deadLetterAddress',
      xmlName: 'dead-letter-address',
      type: 'STRING',
      allowedValues: [],
      hazardClass: 'MEDIUM',
      applicable: true,
    },
    {
      jsonName: 'autoDeleteQueues',
      xmlName: 'auto-delete-queues',
      type: 'BOOLEAN',
      allowedValues: [],
      hazardClass: 'MEDIUM',
      applicable: true,
    },
  ],
  permissionTypes: ['send', 'consume', 'manage', 'browse'],
};

export function cluster(writeStatus = 'AVAILABLE') {
  return {
    id: 'c1',
    name: 'prod',
    description: null,
    topology: { clusterId: 'c1', nodes: [], unmanaged: [] },
    capabilities: {
      managementRead: { status: 'AVAILABLE', reason: 'ok', brokerXmlSnippet: null },
      managementWrite: { status: writeStatus, reason: 'the broker refused a write', brokerXmlSnippet: null },
      messageIo: { status: 'AVAILABLE', reason: 'ok', brokerXmlSnippet: null },
      notifications: { status: 'AVAILABLE', reason: 'ok', brokerXmlSnippet: null },
    },
    health: {
      clusterId: 'c1',
      level: 'OK',
      liveEndpointNames: [],
      splitBrain: 'NONE',
      replicationBehind: false,
      notes: [],
    },
  };
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

export function baseHandlers(d: ConfigDeclarationView = declaration(), permissions: string[] = ['*']) {
  return [
    meHandler(permissions),
    http.get('*/api/v1/clusters/c1', () => HttpResponse.json(cluster())),
    http.get('*/api/v1/clusters/c1/artemis/config', () => HttpResponse.json(d)),
    http.get('*/api/v1/clusters/c1/artemis/config/catalogue', () => HttpResponse.json(CATALOGUE)),
    http.get('*/api/v1/clusters/c1/artemis/config/revisions', () => HttpResponse.json(paged([]))),
    http.get('*/api/v1/clusters/c1/artemis/config/applies', () => HttpResponse.json(paged([]))),
  ];
}

/** A plan with one High hazard on a two-node cluster. */
export function plan(over: Partial<ConfigApplyOutcomeView> = {}): ConfigApplyOutcomeView {
  const step = {
    id: 'ADDRESS_SETTING:orders.#:REPLACE',
    op: 'REPLACE' as const,
    section: 'ADDRESS_SETTING' as const,
    key: 'orders.#',
    before: { addressFullMessagePolicy: 'PAGE' },
    after: { addressFullMessagePolicy: 'DROP' },
    already: false,
    description: 'Replace address setting orders.#',
  };
  const stepApply = (status: 'WOULD_APPLY' | 'APPLIED' | 'FAILED' | 'NOT_ATTEMPTED', error: string | null = null) => ({
    stepId: step.id,
    section: 'ADDRESS_SETTING',
    key: 'orders.#',
    op: 'REPLACE',
    description: step.description,
    status,
    verified: status === 'APPLIED' ? ('VERIFIED' as const) : ('NOT_VERIFIED' as const),
    error,
  });
  return {
    applyId: 1,
    dryRun: true,
    outcome: 'DRY_RUN',
    revision: 3,
    plan: {
      nodes: [
        { nodeId: 'n-a', nodeName: 'broker-1', live: true, unavailableReason: null, steps: [step] },
        { nodeId: 'n-b', nodeName: 'broker-2', live: true, unavailableReason: null, steps: [step] },
      ],
      hazards: [
        {
          id: 'MESSAGE_LOSS_POLICY:n-a:ADDRESS_SETTING:orders.#',
          kind: 'MESSAGE_LOSS_POLICY',
          hazardClass: 'HIGH',
          nodeId: 'n-a',
          nodeName: 'broker-1',
          section: 'ADDRESS_SETTING',
          key: 'orders.#',
          message:
            'address-full-policy DROP discards messages once the limit is hit; orders.request is under this match.',
        },
      ],
      findings: [],
      planHash: 'abc123',
      stepCount: 2,
      canaryNodeId: 'n-a',
    },
    nodes: [
      {
        nodeId: 'n-a',
        nodeName: 'broker-1',
        live: true,
        canary: true,
        unavailableReason: null,
        steps: [stepApply('WOULD_APPLY')],
        note: null,
      },
      {
        nodeId: 'n-b',
        nodeName: 'broker-2',
        live: true,
        canary: false,
        unavailableReason: null,
        steps: [stepApply('WOULD_APPLY')],
        note: null,
      },
    ],
    stepCap: 100,
    overCap: false,
    summary: 'Would apply 2 steps to 2 live nodes.',
    auditEventId: 10,
    ...over,
  };
}

export function halted(): ConfigApplyOutcomeView {
  const p = plan();
  return {
    ...p,
    dryRun: false,
    outcome: 'HALTED',
    nodes: [
      { ...p.nodes[0], steps: [{ ...p.nodes[0].steps[0], status: 'FAILED', error: 'AMQ229001: invalid JSON' }] },
      { ...p.nodes[1], steps: [{ ...p.nodes[1].steps[0], status: 'NOT_ATTEMPTED' }] },
    ],
    summary:
      'Halted at broker-1 step 1: AMQ229001: invalid JSON. broker-2 not attempted. Nothing was rolled back. Re-running converges.',
  };
}
