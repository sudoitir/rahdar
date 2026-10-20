import { useCluster } from '../clusters/index.ts';
import { useCan } from '../../kernel/auth/useCan.ts';
import { gateFor, type GateVerdict } from '../../ui/capabilityGate.ts';

/** Whether diverts may be deleted on this cluster, and why not. */
export function useDivertWriteGate(clusterId: string): GateVerdict {
  const { can, loading } = useCan();
  const cluster = useCluster(clusterId);
  return gateFor(
    can('route:write', clusterId),
    'Create and delete diverts',
    cluster.data?.capabilities.managementWrite,
    loading || cluster.isPending,
  );
}
