import type { HeldOperationDetail, HeldOperationSummary } from './api.ts';

export const HELD_ID = '0b1f6a3e-6f4e-4d4a-9a8e-1c2d3e4f5a6b';

/** A request as the list shows it: a purge waiting for approval. */
export function summary(over: Partial<HeldOperationSummary> = {}): HeldOperationSummary {
  return {
    id: HELD_ID,
    type: 'subscription.purge',
    state: 'HELD',
    summary: 'Purge queue orders.dlq on prod-eu',
    requesterId: 'u-alice',
    requesterUsername: 'alice',
    authKind: 'SESSION',
    clusterId: null,
    requestedAt: '2026-10-07T10:00:00Z',
    expiresAt: '2099-01-01T00:00:00Z',
    approverUsername: null,
    decidedAt: null,
    finishedAt: null,
    ...over,
  };
}

/** A request as its page shows it, to someone who may decide it unless `over` says otherwise. */
export function detail(
  over: Partial<HeldOperationDetail> = {},
  operation: Partial<HeldOperationSummary> = {},
): HeldOperationDetail {
  return {
    operation: summary(operation),
    typeVersion: 1,
    mode: 'ON_APPROVAL',
    traits: ['DESTRUCTIVE'],
    environmentId: null,
    display: [
      { label: 'Queue', from: null, to: 'orders.dlq' },
      { label: 'Cluster', from: null, to: 'prod-eu' },
    ],
    params: '{"queue":"orders.dlq"}',
    paramsHash: 'hash-1',
    version: 3,
    tokenName: null,
    effect: { count: 1204, unit: 'messages', detail: null },
    policy: { id: 'default', version: '2', name: 'Two people for destructive changes' },
    approverHint: 'Anyone with the operator role on prod-eu',
    approverId: null,
    decisionReason: null,
    outcomeDetail: null,
    link: `/approvals/${HELD_ID}`,
    events: [
      {
        seq: 1,
        kind: 'REQUESTED',
        actorId: 'u-alice',
        actorUsername: 'alice',
        detail: 'Poison messages block the consumer',
        at: '2026-10-07T10:00:00Z',
      },
    ],
    mine: false,
    canDecide: true,
    decideRefusal: null,
    canCancel: false,
    mfaVerified: false,
    requesterLacksPermission: false,
    ...over,
  };
}
