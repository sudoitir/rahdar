import { describe, expect, it } from 'vitest';

import { diffRoles } from './diffRoles.ts';

describe('diffRoles', () => {
  it('lists what only one role holds and leaves shared permissions out', () => {
    expect(
      diffRoles(['subscription:purge', 'cluster:read', 'subscription:*'], ['cluster:read', 'message:read']),
    ).toEqual({
      onlyA: ['subscription:*', 'subscription:purge'],
      onlyB: ['message:read'],
    });
  });

  it('is empty for identical roles', () => {
    expect(diffRoles(['a:b'], ['a:b'])).toEqual({ onlyA: [], onlyB: [] });
  });
});
