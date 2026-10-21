/** Every permission the console checks, which a wildcard in a test's grants stands for. */
const UNIVERSE = [
  'destination:create',
  'destination:read',
  'alert:read',
  'alert:write',
  'audit:export',
  'audit:read',
  'capture:write',
  'cluster:manage',
  'cluster:read',
  'cluster:write',
  'config:apply',
  'config:read',
  'config:write',
  'connection:close',
  'connection:read',
  'data:read',
  'data:write',
  'diagnostics:bundle',
  'route:write',
  'environment:read',
  'governance:read',
  'governance:write',
  'message:delete',
  'message:move',
  'message:read',
  'message:send',
  'metrics:read',
  'subscription:create',
  'subscription:delete',
  'subscription:pause',
  'subscription:purge',
  'subscription:read',
  'subscription:update',
  'rr:write',
  'settings:read',
  'settings:write',
  'team:admin',
  'token:admin',
  'user:admin',
];

export type Grant = { scopeType: string; scopeId?: string | null; permissions: string[] };

const expand = (held: string[]) =>
  UNIVERSE.filter((p) => held.some((h) => h === '*' || h === p || (h.endsWith(':*') && p.startsWith(h.slice(0, -1)))));

/**
 * The answer `/me/access` gives for `grants`: what the server's resolver decides for role grants that name no
 * environment, globally or, when `clusterId` is given, on that cluster.
 */
export function accessFor(grants: Grant[], clusterId: string | null = null) {
  const reaching = grants.filter(
    (g) => g.scopeType === 'GLOBAL' || (clusterId !== null && g.scopeType === 'CLUSTER' && g.scopeId === clusterId),
  );
  return {
    permissions: expand(reaching.flatMap((g) => g.permissions)),
    anywhere: [],
    canSeeCluster: clusterId === null ? null : reaching.length > 0,
    teams: [],
    createPatterns: { subscription: [], destination: [] },
  };
}
