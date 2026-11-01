import { describe, expect, it } from 'vitest';

import type { components } from '../kernel/api/schema.d.ts';
import { appliedEverywhere } from './nodeOutcome.ts';

type LifecycleOutcomeView = components['schemas']['LifecycleOutcomeView'];
type NodeOutcomeView = components['schemas']['NodeOutcomeView'];

const node = (status: NodeOutcomeView['status']): NodeOutcomeView => ({
  nodeId: status,
  nodeName: status,
  status,
  affected: null,
  error: null,
});

const outcome = (over: Partial<LifecycleOutcomeView>): LifecycleOutcomeView => ({
  dryRun: false,
  cap: 1000,
  identityBasis: 'INCARNATION',
  overCap: false,
  partial: false,
  totalAffected: 0,
  nodes: [node('APPLIED')],
  ...over,
});

describe('appliedEverywhere', () => {
  it('is true when every node applied or already had it', () => {
    expect(appliedEverywhere(outcome({ nodes: [node('APPLIED'), node('ALREADY')] }))).toBe(true);
  });

  it('is false for a cluster with no node, which settled nowhere', () => {
    expect(appliedEverywhere(outcome({ nodes: [] }))).toBe(false);
  });

  it('is false for a preview and when any node did not apply', () => {
    expect(appliedEverywhere(outcome({ dryRun: true }))).toBe(false);
    expect(appliedEverywhere(outcome({ nodes: [node('APPLIED'), node('FAILED')] }))).toBe(false);
  });
});
