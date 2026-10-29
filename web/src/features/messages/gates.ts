import { useKindVerbRefusal } from '../../kernel/auth/kindVerbs.ts';
import { useCan } from '../../kernel/auth/useCan.ts';
import { gateFor, type GateVerdict } from '../../ui/capabilityGate.ts';
import { useCluster } from '../clusters/index.ts';
import type { MessageActionKind } from './api.ts';

/** Which of the connection's capabilities an operation depends on. */
type Capability = 'messageIo' | 'managementWrite';

/**
 * Whether the caller may take a message operation here, and why not when they may not. Offered while
 * grants and the cluster load, and blocked only on a known refusal (non-negotiable #5). The permission is held on
 * the subscription or destination the screen is about, which the server checks; here it counts if it is held anywhere on the
 * cluster, so a team member is not locked out of their own subscription.
 */
export function useMessageGate(
  clusterId: string,
  permission: string,
  label: string,
  capability: Capability,
): GateVerdict {
  const { canAnywhere, loading } = useCan();
  const cluster = useCluster(clusterId);
  return gateFor(
    canAnywhere(permission, clusterId),
    label,
    cluster.data?.capabilities[capability],
    loading || cluster.isPending,
  );
}

/** The permission each by-id or by-selector action needs, and how the permission is named to the operator. */
const ACTION_PERMISSION: Record<MessageActionKind, { permission: string; label: string }> = {
  move: { permission: 'message:move', label: 'Move or retry messages' },
  retry: { permission: 'message:move', label: 'Move or retry messages' },
  delete: { permission: 'message:delete', label: 'Delete or expire messages' },
  expire: { permission: 'message:delete', label: 'Delete or expire messages' },
};

/** The verb of a subscription's kind that each action needs from its system. */
const ACTION_VERB: Record<MessageActionKind, string> = {
  move: 'move-messages',
  retry: 'retry-messages',
  delete: 'delete-messages',
  expire: 'expire',
};

/**
 * The gate for moving, retrying, deleting or expiring messages. A kind whose system cannot take the verb
 * blocks the control first, with that system's own words, whatever the caller may do.
 */
export function useActionGate(clusterId: string, action: MessageActionKind, kindId?: string): GateVerdict {
  const { permission, label } = ACTION_PERMISSION[action];
  const refusal = useKindVerbRefusal(clusterId, kindId, ACTION_VERB[action]);
  const gate = useMessageGate(clusterId, permission, label, 'managementWrite');
  return refusal ?? gate;
}
