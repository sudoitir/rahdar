import { useSlot, type SlotEntry, type SlotName } from '../slots.ts';
import { useClusterSystem } from './useClusterSystem.ts';

/**
 * The contributions to a slot that apply to this cluster: those with no `system`, and those for the system the
 * cluster runs. While the cluster has not said which system it runs, a system-only contribution is not offered:
 * a claim about what applies is made only once it is known.
 */
export function useClusterSlot<K extends SlotName>(name: K, clusterId: string | undefined): SlotEntry<K>[] {
  const system = useClusterSystem(clusterId);
  return useSlot(name).filter((c) => !c.system || c.system === system);
}
