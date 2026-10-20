import { gateFor, type GateVerdict } from '../../ui/capabilityGate.ts';
import type { components } from '../api/schema.d.ts';
import { useKindVerbRefusal } from './kindVerbs.ts';
import { useCan } from './useCan.ts';

type CapabilityView = components['schemas']['CapabilityView'];

/** What a row the server sent says about the caller: the actions allowed on it, and the team that owns it. */
export interface ResourceAccess {
  allowedActions: string[];
  ownerTeam?: { name: string } | null;
}

/**
 * The state of one control on one subscription or destination.
 *
 * <p>`hidden` only when the caller cannot take the action anywhere on the cluster: it is not theirs to be offered.
 * A kind that lacks the verb is never hidden, and its reason comes first; then a capability, then a permission.
 * Otherwise the control is shown, and `verdict` is blocked, with the permission and whom to ask, when this resource
 * is one the action is not allowed on (the row's `allowedActions`). Without a row the cluster-level answer decides.
 */
export function useResourceGate(options: {
  clusterId: string;
  noun: 'subscription' | 'destination';
  permission: string;
  label: string;
  resource?: ResourceAccess;
  /** The resource's kind and the verb the control performs: a kind that lacks the verb blocks the control first. */
  kind?: { id: string | undefined; verb: string };
  capability?: CapabilityView;
  /** Something other than grants is still loading. */
  pending?: boolean;
}): { hidden: boolean; verdict: GateVerdict } {
  const { clusterId, noun, permission, label, resource, kind, capability, pending = false } = options;
  const refusal = useKindVerbRefusal(clusterId, kind?.id, kind?.verb ?? '');
  const { can, canAnywhere, canOn, loading } = useCan();
  const permitted = resource ? canOn(permission, resource) : can(permission, clusterId);
  // A kind that cannot take the verb is a fact about the system, not about the caller: the control stays,
  // disabled, and says so before any reason about capability or permission.
  if (kind && refusal) return { hidden: false, verdict: refusal };
  return {
    hidden: !loading && !canAnywhere(permission, clusterId),
    verdict: gateFor(
      permitted,
      label,
      capability,
      loading || pending,
      'cluster',
      resource ? { permission, noun, owner: resource.ownerTeam?.name } : undefined,
    ),
  };
}
