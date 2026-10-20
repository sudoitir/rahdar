import type { components } from '../kernel/api/schema.d.ts';

type CapabilityView = components['schemas']['CapabilityView'];

export type GateVerdict =
  { kind: 'allowed'; uncertain: boolean } | { kind: 'blocked'; reason: string; snippet?: string | null };

/** What a permission is held on: one cluster, or Rahdar itself. */
export type GateScope = 'cluster' | 'installation';

/** What is known of a refusal on one subscription or destination: the permission, and who owns it, to say who can grant it. */
export interface ResourceDenial {
  permission: string;
  noun: 'subscription' | 'destination';
  /** The name of the team that owns the resource; none when no team does. */
  owner?: string | null;
}

/**
 * Whether a lifecycle control may act, and why not when it may not.
 *
 * <p>Two independent gates, checked in this order (a kind that lacks the verb comes before both, in
 * `useKindVerbRefusal`):
 *
 * <ul>
 *   <li><b>Capability</b> — whether the broker connection can write at all. Only
 *       a known refusal blocks. Since ADR-0049 D5 an unproven capability is
 *       reported as unknown, and blocking on the absence of evidence would stop
 *       an operator using a broker that works perfectly well.
 *   <li><b>Permission</b> — the caller's own grants. The server is the
 *       enforcement point; this only explains, and never hides.
 * </ul>
 *
 * <p>`scope` is what the permission is held on, so the reason names the right place: `cluster` for
 * an action on one cluster (the usual one), `installation` for one on Rahdar itself, such as its
 * settings, users or plugins.
 */
export function gateFor(
  permitted: boolean,
  permissionLabel: string,
  capability: CapabilityView | undefined,
  /** True while the caller's grants are still being fetched. */
  loading = false,
  scope: GateScope = 'cluster',
  /** Given for a control on one subscription or destination the caller can see: the reason then names the permission and whom to ask. */
  denial?: ResourceDenial,
): GateVerdict {
  // Nothing is known yet. Saying "you do not have permission" here would be a
  // claim about the operator that has not been checked, and it would flash on
  // every render before settling. The server is the enforcement point either
  // way, so the honest interim answer is to offer the control.
  if (loading) {
    return { kind: 'allowed', uncertain: false };
  }
  if (capability?.status === 'UNAVAILABLE') {
    return { kind: 'blocked', reason: capability.reason, snippet: capability.brokerXmlSnippet };
  }
  if (!permitted && denial) {
    const ask = denial.owner ? `Ask an admin of team ${denial.owner}.` : 'Ask a platform administrator.';
    return {
      kind: 'blocked',
      reason: `You do not have the "${permissionLabel}" permission (${denial.permission}) on this ${denial.noun}. ${ask}`,
    };
  }
  if (!permitted) {
    return {
      kind: 'blocked',
      reason: `You do not have the "${permissionLabel}" permission on this ${scope}. An administrator can grant it in Settings → Roles.`,
    };
  }
  return { kind: 'allowed', uncertain: capability?.status === 'UNKNOWN' };
}
