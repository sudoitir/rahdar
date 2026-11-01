import { useCan } from '../../kernel/auth/useCan.ts';
import { gateFor, type GateVerdict } from '../../ui/capabilityGate.ts';

/** Whether the caller holds a permission on the cluster, as the control that needs it states it. */
export function useWriteGate(clusterId: string, permission: string, label: string): GateVerdict {
  const { can, loading } = useCan();
  return gateFor(can(permission, clusterId), label, undefined, loading);
}

/** How many of a thing, with its noun in the right number. */
export function countOf(count: number, one: string, many = `${one}s`): string {
  return `${count.toLocaleString()} ${count === 1 ? one : many}`;
}
