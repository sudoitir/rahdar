import { describe, expect, it } from 'vitest';

import type { PermissionView } from './api.ts';
import { dependentsOf, withRequired } from './permissionRequires.ts';

const perm = (action: string, requires: string[] = []): PermissionView => ({
  action,
  label: action,
  featureId: 'subscriptions',
  featureTitle: 'Subscriptions',
  scope: 'RESOURCE',
  resourceTypes: ['SUBSCRIPTION'],
  requires,
});

const CATALOGUE = [
  perm('subscription:read'),
  perm('subscription:purge', ['subscription:read']),
  perm('subscription:delete', ['subscription:purge']),
];

describe('withRequired', () => {
  it('adds the requirements of a permission, transitively, and names who asked', () => {
    const { next, added } = withRequired(CATALOGUE, [], ['subscription:delete']);

    expect(next).toEqual(['subscription:delete', 'subscription:purge', 'subscription:read']);
    expect(added).toEqual([
      { permission: 'subscription:purge', requiredBy: 'subscription:delete' },
      { permission: 'subscription:read', requiredBy: 'subscription:purge' },
    ]);
  });

  it('adds nothing that is already held, directly or through a wildcard', () => {
    expect(withRequired(CATALOGUE, ['subscription:read'], ['subscription:purge']).added).toEqual([]);
    expect(withRequired(CATALOGUE, ['*'], ['subscription:purge']).added).toEqual([]);
    expect(withRequired(CATALOGUE, ['subscription:*'], ['subscription:purge']).added).toEqual([]);
  });

  it('does not report a requirement that was chosen in the same step', () => {
    expect(withRequired(CATALOGUE, [], ['subscription:purge', 'subscription:read']).added).toEqual([]);
  });
});

describe('dependentsOf', () => {
  it('finds what needs a removed permission, following the chain', () => {
    const held = ['subscription:read', 'subscription:purge', 'subscription:delete'];

    expect(dependentsOf(CATALOGUE, held, ['subscription:read'])).toEqual([
      { permission: 'subscription:purge', needs: 'subscription:read' },
      { permission: 'subscription:delete', needs: 'subscription:purge' },
    ]);
  });

  it('finds nothing when no held permission needs it, or a wildcard still gives it', () => {
    expect(dependentsOf(CATALOGUE, ['subscription:read', 'subscription:purge'], ['subscription:purge'])).toEqual([]);
    expect(
      dependentsOf(CATALOGUE, ['subscription:*', 'subscription:read', 'subscription:purge'], ['subscription:read']),
    ).toEqual([]);
  });
});
