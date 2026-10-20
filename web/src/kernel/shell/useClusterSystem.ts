import { useQuery } from '@tanstack/react-query';

import { clusterKey, request } from '../api/request.ts';
import type { components } from '../api/schema.d.ts';

type ClusterDetail = components['schemas']['ClusterDetail'];

/**
 * What the cluster says about its broker system: the system itself and the kinds it offers, each with the verbs
 * it supports. It reads the cluster the clusters feature already keeps fresh, under the same key, so it adds
 * no request of its own. `undefined` until the cluster has loaded, and for no cluster.
 */
export function useClusterKinds(clusterId: string | undefined): Pick<ClusterDetail, 'system' | 'kinds'> | undefined {
  const { data } = useQuery({
    queryKey: clusterKey(clusterId ?? ''),
    queryFn: () => request<ClusterDetail>(`/clusters/${clusterId}`),
    enabled: Boolean(clusterId),
    select: ({ system, kinds }) => ({ system, kinds }),
  });
  return data;
}

/** The id of the system the cluster runs (`artemis`), which decides whether a system-only view is offered. */
export function useClusterSystem(clusterId: string | undefined): string | undefined {
  return useClusterKinds(clusterId)?.system?.id;
}
