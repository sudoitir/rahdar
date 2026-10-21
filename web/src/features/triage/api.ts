import { useQuery, type UseQueryResult } from '@tanstack/react-query';

import { type PagedView, type ResourceParams, resourceSearch } from '../../kernel/api/paging.ts';
import { ApiError, clusterKey, request } from '../../kernel/api/request.ts';
import type { ResourceRef } from '../../kernel/api/resourceId.ts';
import type { components } from '../../kernel/api/schema.d.ts';

type Schemas = components['schemas'];

export type ConsumerHealthView = Schemas['ConsumerHealthView'];

export const keys = {
  health: (id: string, params: ResourceParams = {}) => clusterKey(id, 'consumer-health', params),
  subscription: (id: string, subscriptionName: string) =>
    clusterKey(id, 'consumer-health', 'subscription', subscriptionName),
  /** The stream topic the subscription sweep publishes; a verdict changes when a sweep lands. */
  topic: (id: string) => clusterKey(id, 'consumer-health'),
};

/** The cluster's subscriptions with their verdicts, worst first unless `sort` says otherwise. */
export function useConsumerHealth(
  clusterId: string,
  params: ResourceParams = {},
): UseQueryResult<PagedView<ConsumerHealthView>, ApiError> {
  return useQuery({
    queryKey: keys.health(clusterId, params),
    // The palette mounts outside a cluster, where there is no id to ask about.
    enabled: clusterId !== '',
    queryFn: () =>
      request<PagedView<ConsumerHealthView>>(`/clusters/${clusterId}/consumer-health${resourceSearch(params)}`),
    refetchInterval: 5_000,
    placeholderData: (prev) => prev,
  });
}

/**
 * One subscription's verdict, for the detail drawer. A separate key from the listing so
 * opening a drawer does not evict the ranked page behind it.
 */
export function useSubscriptionHealth(
  clusterId: string,
  subscription: ResourceRef,
  enabled = true,
): UseQueryResult<ConsumerHealthView | null, ApiError> {
  return useQuery({
    queryKey: keys.subscription(clusterId, subscription.name),
    enabled: enabled && subscription.id !== '',
    queryFn: async () => {
      const page = await request<PagedView<ConsumerHealthView>>(
        `/clusters/${clusterId}/consumer-health?subscription=${encodeURIComponent(subscription.id)}`,
      );
      return page.data[0] ?? null;
    },
    refetchInterval: 5_000,
    placeholderData: (prev) => prev,
  });
}
