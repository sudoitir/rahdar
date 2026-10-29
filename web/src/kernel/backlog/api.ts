import { useQuery, type UseQueryResult } from '@tanstack/react-query';

import { clusterKey, request, type ApiError } from '../api/request.ts';
import type { components } from '../api/schema.d.ts';

/** One partition of a backlog breakdown. */
export type PartitionBacklog = components['schemas']['PartitionLag'];

/** `GET …/subscriptions/{kind}/{path}/backlog`: the partitions, slowest first, and when they were sampled. */
export type BacklogBreakdown = components['schemas']['Breakdown'];

/** The breakdown of one subscription's backlog, read only while `id` is set. */
export function useBacklogBreakdown(
  clusterId: string,
  subscriptionId: string | null,
): UseQueryResult<BacklogBreakdown, ApiError> {
  return useQuery({
    queryKey: clusterKey(clusterId, 'backlog', subscriptionId),
    enabled: subscriptionId !== null,
    queryFn: () => {
      const id = subscriptionId ?? '';
      const slash = id.indexOf('/');
      return request<BacklogBreakdown>(
        `/clusters/${clusterId}/subscriptions/${id.slice(0, slash)}/${encodeURIComponent(id.slice(slash + 1))}/backlog`,
      );
    },
  });
}
