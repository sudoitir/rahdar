import { useCluster } from '../clusters/index.ts';
import { kindOf } from '../../kernel/api/resourceId.ts';
import { useKindVerbRefusal } from '../../kernel/auth/kindVerbs.ts';
import { useCan } from '../../kernel/auth/useCan.ts';
import { gateFor, type GateVerdict } from '../../ui/capabilityGate.ts';
import type { ConnectionCloseKind } from './api.ts';

const PERMISSION_LABEL = "Close client connections, channels and an destination's consumers";

type NodeKind = Exclude<ConnectionCloseKind, 'destination-consumers'>;

const NOUN: Record<NodeKind, string> = {
  connection: 'connection',
  channel: 'channel',
  consumer: "consumer's connection",
};

/**
 * Whether a close may be attempted on this target, and why not. A row the broker gave no
 * identifier for has nothing to aim at; the control still shows, with the reason — a missing
 * button would read as "Rahdar cannot close connections" rather than "this row cannot be
 * destinationed".
 */
export function useCloseGate(clusterId: string, kind: NodeKind, targetId: string): GateVerdict {
  const refusal = useKindVerbRefusal(clusterId, targetId ? kindOf(targetId) : undefined, 'close');
  const { can, loading } = useCan();
  const cluster = useCluster(clusterId);
  const permitted = gateFor(
    can('connection:close', clusterId),
    PERMISSION_LABEL,
    cluster.data?.capabilities.managementWrite,
    loading || cluster.isPending,
  );
  if (refusal) return refusal;
  return targetId
    ? permitted
    : {
        kind: 'blocked',
        reason: `This broker reported no identifier for this ${NOUN[kind]}, so there is nothing to aim a close at.`,
      };
}

/** Whether closing an destination's consumers may be attempted, and why not. */
export function useCloseDestinationGate(clusterId: string, kindId?: string): GateVerdict {
  const refusal = useKindVerbRefusal(clusterId, kindId, 'close');
  const { can, loading } = useCan();
  const cluster = useCluster(clusterId);
  return (
    refusal ??
    gateFor(
      can('connection:close', clusterId),
      PERMISSION_LABEL,
      cluster.data?.capabilities.managementWrite,
      loading || cluster.isPending,
    )
  );
}
