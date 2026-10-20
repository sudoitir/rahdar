import { describe, expect, it } from 'vitest';

import { gateFor } from './capabilityGate.ts';

describe('gateFor', () => {
  it('names the cluster as the place a missing permission is held, by default', () => {
    const verdict = gateFor(false, 'Purge queues', undefined);
    expect(verdict).toMatchObject({ kind: 'blocked' });
    expect(verdict.kind === 'blocked' && verdict.reason).toContain('"Purge queues" permission on this cluster.');
  });

  it('names the installation when the caller says the permission is held on Rahdar itself', () => {
    const verdict = gateFor(false, 'Manage plugins', undefined, false, 'installation');
    expect(verdict.kind === 'blocked' && verdict.reason).toContain('"Manage plugins" permission on this installation.');
    expect(verdict.kind === 'blocked' && verdict.reason).not.toContain('cluster');
  });

  it('offers the control while grants load, whatever the scope', () => {
    expect(gateFor(false, 'Manage plugins', undefined, true, 'installation')).toEqual({
      kind: 'allowed',
      uncertain: false,
    });
  });

  it('names the permission and the owning team when a queue the caller can see does not allow it', () => {
    const verdict = gateFor(false, 'Purge queues', undefined, false, 'cluster', {
      permission: 'subscription:purge',
      noun: 'queue',
      owner: 'Orders',
    });
    expect(verdict.kind === 'blocked' && verdict.reason).toBe(
      'You do not have the "Purge queues" permission (subscription:purge) on this queue. Ask an admin of team Orders.',
    );
  });

  it('points at a platform administrator when no team owns the resource', () => {
    const verdict = gateFor(false, 'Purge queues', undefined, false, 'cluster', {
      permission: 'subscription:purge',
      noun: 'queue',
      owner: null,
    });
    expect(verdict.kind === 'blocked' && verdict.reason).toContain('Ask a platform administrator.');
  });
});
