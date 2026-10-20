import { useMutation, useQuery, useQueryClient, type UseQueryResult } from '@tanstack/react-query';
import {
  ApiError,
  clusterKey,
  lifecycleBase,
  lifecycleQuery,
  type LifecycleVars,
  request,
} from '../../kernel/api/request.ts';
import { type PagedView, type ResourceParams, resourceSearch } from '../../kernel/api/paging.ts';
import { nameOf, resourcePath } from '../../kernel/api/resourceId.ts';
import { useRoleIds } from '../clusters/index.ts';
import type { components } from '../../kernel/api/schema.d.ts';

type Schemas = components['schemas'];

export type DestinationView = Schemas['DestinationView'];
export type CapabilityView = Schemas['CapabilityView'];
export type CreateDestinationRequest = Schemas['CreateDestinationRequest'];
export type CreateSubscriptionRequest = Schemas['CreateSubscriptionRequest'];
export type LifecycleOutcomeView = Schemas['LifecycleOutcomeView'];
export type SubscriptionConfiguration = Schemas['SubscriptionConfiguration'];
export type SubscriptionView = Schemas['SubscriptionView'];
export type UpdateResourceRequest = Schemas['UpdateResourceRequest'];

/** The name of the destination a subscription is bound to. */
export const addressOf = (q: SubscriptionView): string => nameOf(q.destination);

/** The routing type the system reports for a subscription, in the case it reports it. */
export const routingTypeOf = (q: SubscriptionView): string => q.nativeAttributes.routingType ?? '';

export const keys = {
  resource: (id: string, kind: string, params: ResourceParams = {}) => clusterKey(id, kind, params),
  topic: (id: string, topic: string) => clusterKey(id, topic),
  configuration: (id: string, queueName: string) => clusterKey(id, 'queues', queueName, 'configuration'),
};

export function useQueues(
  id: string,
  params: ResourceParams = {},
  /** `enabled: false` holds the read; `live: false` reads once instead of polling. */
  options: { enabled?: boolean; live?: boolean } = {},
): UseQueryResult<PagedView<SubscriptionView>, ApiError> {
  return useQuery({
    queryKey: keys.resource(id, 'queues', params),
    queryFn: () => request<PagedView<SubscriptionView>>(`/clusters/${id}/subscriptions${resourceSearch(params)}`),
    // The command palette mounts on every route, including the ones outside a
    // cluster, where it has no id to give. Without this it requested
    // `/clusters//subscriptions`, which is a 400 — and an errored observed query puts
    // the whole shell into its offline state, so every cluster-less screen
    // claimed Rahdar had lost the brokers.
    enabled: id !== '' && (options.enabled ?? true),
    refetchInterval: options.live === false ? false : 5_000,
    placeholderData: (prev) => prev,
  });
}

/**
 * The addresses matching `q`, each with the actions the caller holds on it, for a picker that offers only those
 * the caller may use. Held while `enabled` is false.
 */
export function useAddresses(
  id: string,
  params: ResourceParams,
  enabled: boolean,
): UseQueryResult<PagedView<DestinationView>, ApiError> {
  return useQuery({
    queryKey: keys.resource(id, 'addresses', params),
    queryFn: () => request<PagedView<DestinationView>>(`/clusters/${id}/destinations${resourceSearch(params)}`),
    enabled: id !== '' && enabled,
    placeholderData: (prev) => prev,
  });
}

/**
 * One queue by name, exactly. The listing is the only read of a queue, so this asks it for the name
 * and keeps the exact match: a filter is a substring match on queue or address, and `orders` also
 * matches `orders.dlq`. `snapshot`, when the caller already has the row, answers without a request.
 */
export function useQueue(
  clusterId: string,
  queueName: string | undefined,
  snapshot?: SubscriptionView,
): { queue: SubscriptionView | undefined; isPending: boolean; isError: boolean; error: Error | null } {
  const lookup = useQuery({
    queryKey: keys.resource(clusterId, 'queues', { q: queueName, size: 50 }),
    queryFn: () =>
      request<PagedView<SubscriptionView>>(
        `/clusters/${clusterId}/subscriptions${resourceSearch({ q: queueName, size: 50 })}`,
      ),
    enabled: !snapshot && clusterId !== '' && Boolean(queueName),
    refetchInterval: 5_000,
  });
  if (snapshot) return { queue: snapshot, isPending: false, isError: false, error: null };
  return {
    queue: lookup.data?.data.find((q) => q.name === queueName),
    isPending: Boolean(queueName) && lookup.isPending,
    isError: lookup.isError,
    error: lookup.error,
  };
}

/**
 * What the queue is configured as on each node that has it. The broker's update
 * replaces the whole configuration, so the edit form reads this first: a form of
 * blank inputs cannot say what the queue runs, nor whether an update took.
 */
export function useQueueConfiguration(
  clusterId: string,
  queueName: string,
  enabled = true,
): UseQueryResult<SubscriptionConfiguration, ApiError> {
  const ids = useRoleIds(clusterId);
  return useQuery({
    queryKey: keys.configuration(clusterId, queueName),
    queryFn: () =>
      request<SubscriptionConfiguration>(`/clusters/${clusterId}${resourcePath(ids.subscription(queueName))}`),
    enabled: enabled && ids.loaded && queueName !== '',
  });
}

/**
 * Invalidate on a real run only. A preview mutated nothing, so refetching after
 * one would cost a broker round trip to learn what we already know.
 */
function useLifecycleMutation<V extends LifecycleVars>(
  clusterId: string,
  send: (vars: V) => Promise<LifecycleOutcomeView>,
) {
  const qc = useQueryClient();
  return useMutation<LifecycleOutcomeView, ApiError, V>({
    mutationFn: send,
    onSuccess: (result) => {
      if (result.dryRun) return;
      void qc.invalidateQueries({ queryKey: keys.topic(clusterId, 'queues') });
      void qc.invalidateQueries({ queryKey: keys.resource(clusterId, 'addresses') });
      // The queue's own configuration is what the edit form shows; leaving it
      // cached is how an applied change reads as though it never happened. The
      // topic key above already covers it — `clusters/<id>/queues` is its prefix.
    },
  });
}

export function useCreateQueue(clusterId: string) {
  return useLifecycleMutation<LifecycleVars & { body: CreateSubscriptionRequest }>(clusterId, ({ body, dryRun }) =>
    request(`${lifecycleBase(clusterId)}/subscriptions${lifecycleQuery(dryRun)}`, {
      method: 'POST',
      body: JSON.stringify(body),
    }),
  );
}

export function useUpdateQueue(clusterId: string, queueName: string) {
  const ids = useRoleIds(clusterId);
  return useLifecycleMutation<LifecycleVars & { body: UpdateResourceRequest }>(clusterId, ({ body, dryRun }) =>
    request(`${lifecycleBase(clusterId)}${resourcePath(ids.subscription(queueName))}${lifecycleQuery(dryRun)}`, {
      method: 'PATCH',
      body: JSON.stringify(body),
    }),
  );
}

/**
 * Delete a queue. `disconnectConsumers` closes its consumers so a node with any can delete it;
 * without it such a node refuses (ADR-0084).
 */
export function useDeleteQueue(clusterId: string, queueName: string) {
  const ids = useRoleIds(clusterId);
  return useLifecycleMutation<LifecycleVars & { disconnectConsumers?: boolean }>(
    clusterId,
    ({ dryRun, override, disconnectConsumers }) => {
      const query = lifecycleQuery(dryRun, override);
      const separator = query ? '&' : '?';
      const flag = disconnectConsumers ? `${separator}disconnectConsumers=true` : '';
      return request(`${lifecycleBase(clusterId)}${resourcePath(ids.subscription(queueName))}${query}${flag}`, {
        method: 'DELETE',
      });
    },
  );
}

/** Pause and resume are one hook: the same permission, and the UI toggles between them. */
export function useSetQueuePaused(clusterId: string, queueName: string) {
  const ids = useRoleIds(clusterId);
  return useLifecycleMutation<LifecycleVars & { paused: boolean }>(clusterId, ({ paused, dryRun }) =>
    request(
      `${lifecycleBase(clusterId)}${resourcePath(ids.subscription(queueName))}:${paused ? 'pause' : 'resume'}${lifecycleQuery(dryRun)}`,
      { method: 'POST' },
    ),
  );
}

export function useResetQueueCounter(clusterId: string, queueName: string) {
  const ids = useRoleIds(clusterId);
  return useLifecycleMutation<LifecycleVars>(clusterId, ({ dryRun }) =>
    request(
      `${lifecycleBase(clusterId)}${resourcePath(ids.subscription(queueName))}:reset-counters${lifecycleQuery(dryRun)}`,
      {
        method: 'POST',
      },
    ),
  );
}

export function useCreateAddress(clusterId: string) {
  return useLifecycleMutation<LifecycleVars & { body: CreateDestinationRequest }>(clusterId, ({ body, dryRun }) =>
    request(`${lifecycleBase(clusterId)}/destinations${lifecycleQuery(dryRun)}`, {
      method: 'POST',
      body: JSON.stringify(body),
    }),
  );
}

export function useDeleteAddress(clusterId: string, address: string) {
  const ids = useRoleIds(clusterId);
  return useLifecycleMutation<LifecycleVars>(clusterId, ({ dryRun }) =>
    request(`${lifecycleBase(clusterId)}${resourcePath(ids.destination(address))}${lifecycleQuery(dryRun)}`, {
      method: 'DELETE',
    }),
  );
}
