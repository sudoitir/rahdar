import { useQuery, type UseQueryResult } from '@tanstack/react-query';

import { clusterKey, request, type ApiError } from '../api/request.ts';

/** One partition of a backlog breakdown. */
export interface PartitionBacklog {
  partition: number;
  committed: number | null;
  end: number;
  lag: number | null;
  /** Why this partition has no lag, such as a group that never committed there. */
  reason: string | null;
}

/** `GET …/subscriptions/{kind}/{path}/backlog`: the partitions, slowest first, and when they were sampled. */
export interface BacklogBreakdown {
  sampledAt: string;
  partitions: PartitionBacklog[];
}

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
