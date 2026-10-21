import { useMutation, useQuery, useQueryClient, type UseQueryResult } from '@tanstack/react-query';
import {
  ApiError,
  clusterKey,
  lifecycleBase,
  lifecycleQuery,
  type LifecycleVars,
  request,
} from '../../kernel/api/request.ts';
import { type PagedView, type ResourceParams, resourceSearch, useResource } from '../../kernel/api/paging.ts';
import { nameOf, resourcePath } from '../../kernel/api/resourceId.ts';
import { useRoleIds } from '../clusters/index.ts';
import type { components } from '../../kernel/api/schema.d.ts';

type Schemas = components['schemas'];

export type DestinationView = Schemas['DestinationView'];
export type CapabilityView = Schemas['CapabilityView'];
export type ConnectionCloseView = Schemas['ConnectionCloseView'];
export type ConnectionView = Schemas['ConnectionView'];
export type ConsumerView = Schemas['ConsumerView'];
export type ProducerView = Schemas['ProducerView'];
export type ChannelView = Schemas['ChannelView'];
export type CreateDestinationRequest = Schemas['CreateDestinationRequest'];
export type CreateSubscriptionRequest = Schemas['CreateSubscriptionRequest'];
export type LifecycleOutcomeView = Schemas['LifecycleOutcomeView'];
export type SubscriptionConfiguration = Schemas['SubscriptionConfiguration'];
export type SubscriptionView = Schemas['SubscriptionView'];
export type UpdateResourceRequest = Schemas['UpdateResourceRequest'];

/** The name of the destination a subscription is bound to. */
export const destinationOf = (q: SubscriptionView): string => nameOf(q.destination);

/** The routing type the system reports for a subscription, in the case it reports it. */
export const routingTypeOf = (q: SubscriptionView): string => q.nativeAttributes.routingType ?? '';

export const keys = {
  resource: (id: string, kind: string, params: ResourceParams = {}) => clusterKey(id, kind, params),
  topic: (id: string, topic: string) => clusterKey(id, topic),
  configuration: (id: string, subscriptionName: string) =>
    clusterKey(id, 'subscriptions', subscriptionName, 'configuration'),
};

export function useSubscriptions(
  id: string,
  params: ResourceParams = {},
  /** `enabled: false` holds the read; `live: false` reads once instead of polling. */
  options: { enabled?: boolean; live?: boolean } = {},
): UseQueryResult<PagedView<SubscriptionView>, ApiError> {
  return useQuery({
    queryKey: keys.resource(id, 'subscriptions', params),
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
 * The destinations matching `q`, each with the actions the caller holds on it, for a picker that offers only those
 * the caller may use. Held while `enabled` is false.
 */
export function useDestinationOptions(
  id: string,
  params: ResourceParams,
  enabled: boolean,
): UseQueryResult<PagedView<DestinationView>, ApiError> {
  return useQuery({
    queryKey: keys.resource(id, 'destinations', params),
    queryFn: () => request<PagedView<DestinationView>>(`/clusters/${id}/destinations${resourceSearch(params)}`),
    enabled: id !== '' && enabled,
    placeholderData: (prev) => prev,
  });
}

/**
 * One subscription by name, exactly. The listing is the only read of a subscription, so this asks it for the name
 * and keeps the exact match: a filter is a substring match on subscription or destination, and `orders` also
 * matches `orders.dlq`. `snapshot`, when the caller already has the row, answers without a request.
 */
export function useSubscription(
  clusterId: string,
  subscriptionName: string | undefined,
  snapshot?: SubscriptionView,
): { subscription: SubscriptionView | undefined; isPending: boolean; isError: boolean; error: Error | null } {
  const lookup = useQuery({
    queryKey: keys.resource(clusterId, 'subscriptions', { q: subscriptionName, size: 50 }),
    queryFn: () =>
      request<PagedView<SubscriptionView>>(
        `/clusters/${clusterId}/subscriptions${resourceSearch({ q: subscriptionName, size: 50 })}`,
      ),
    enabled: !snapshot && clusterId !== '' && Boolean(subscriptionName),
    refetchInterval: 5_000,
  });
  if (snapshot) return { subscription: snapshot, isPending: false, isError: false, error: null };
  return {
    subscription: lookup.data?.data.find((q) => q.name === subscriptionName),
    isPending: Boolean(subscriptionName) && lookup.isPending,
    isError: lookup.isError,
    error: lookup.error,
  };
}

/**
 * What the subscription is configured as on each node that has it. The broker's update
 * replaces the whole configuration, so the edit form reads this first: a form of
 * blank inputs cannot say what the subscription runs, nor whether an update took.
 */
export function useSubscriptionConfiguration(
  clusterId: string,
  subscriptionName: string,
  enabled = true,
): UseQueryResult<SubscriptionConfiguration, ApiError> {
  const ids = useRoleIds(clusterId);
  return useQuery({
    queryKey: keys.configuration(clusterId, subscriptionName),
    queryFn: () =>
      request<SubscriptionConfiguration>(`/clusters/${clusterId}${resourcePath(ids.subscription(subscriptionName))}`),
    enabled: enabled && ids.loaded && subscriptionName !== '',
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
      void qc.invalidateQueries({ queryKey: keys.topic(clusterId, 'subscriptions') });
      void qc.invalidateQueries({ queryKey: keys.resource(clusterId, 'destinations') });
      // The subscription's own configuration is what the edit form shows; leaving it
      // cached is how an applied change reads as though it never happened. The
      // topic key above already covers it — `clusters/<id>/subscriptions` is its prefix.
    },
  });
}

export function useCreateSubscription(clusterId: string) {
  return useLifecycleMutation<LifecycleVars & { body: CreateSubscriptionRequest }>(clusterId, ({ body, dryRun }) =>
    request(`${lifecycleBase(clusterId)}/subscriptions${lifecycleQuery(dryRun)}`, {
      method: 'POST',
      body: JSON.stringify(body),
    }),
  );
}

export function useUpdateSubscription(clusterId: string, subscriptionName: string) {
  const ids = useRoleIds(clusterId);
  return useLifecycleMutation<LifecycleVars & { body: UpdateResourceRequest }>(clusterId, ({ body, dryRun }) =>
    request(`${lifecycleBase(clusterId)}${resourcePath(ids.subscription(subscriptionName))}${lifecycleQuery(dryRun)}`, {
      method: 'PATCH',
      body: JSON.stringify(body),
    }),
  );
}

/**
 * Delete a subscription. `disconnectConsumers` closes its consumers so a node with any can delete it;
 * without it such a node refuses (ADR-0084).
 */
export function useDeleteSubscription(clusterId: string, subscriptionName: string) {
  const ids = useRoleIds(clusterId);
  return useLifecycleMutation<LifecycleVars & { disconnectConsumers?: boolean }>(
    clusterId,
    ({ dryRun, override, disconnectConsumers }) => {
      const query = lifecycleQuery(dryRun, override);
      const separator = query ? '&' : '?';
      const flag = disconnectConsumers ? `${separator}disconnectConsumers=true` : '';
      return request(`${lifecycleBase(clusterId)}${resourcePath(ids.subscription(subscriptionName))}${query}${flag}`, {
        method: 'DELETE',
      });
    },
  );
}

/** Pause and resume are one hook: the same permission, and the UI toggles between them. */
export function useSetSubscriptionPaused(clusterId: string, subscriptionName: string) {
  const ids = useRoleIds(clusterId);
  return useLifecycleMutation<LifecycleVars & { paused: boolean }>(clusterId, ({ paused, dryRun }) =>
    request(
      `${lifecycleBase(clusterId)}${resourcePath(ids.subscription(subscriptionName))}:${paused ? 'pause' : 'resume'}${lifecycleQuery(dryRun)}`,
      { method: 'POST' },
    ),
  );
}

export function useResetSubscriptionCounter(clusterId: string, subscriptionName: string) {
  const ids = useRoleIds(clusterId);
  return useLifecycleMutation<LifecycleVars>(clusterId, ({ dryRun }) =>
    request(
      `${lifecycleBase(clusterId)}${resourcePath(ids.subscription(subscriptionName))}:reset-counters${lifecycleQuery(dryRun)}`,
      {
        method: 'POST',
      },
    ),
  );
}

export function useCreateDestination(clusterId: string) {
  return useLifecycleMutation<LifecycleVars & { body: CreateDestinationRequest }>(clusterId, ({ body, dryRun }) =>
    request(`${lifecycleBase(clusterId)}/destinations${lifecycleQuery(dryRun)}`, {
      method: 'POST',
      body: JSON.stringify(body),
    }),
  );
}

export function useDeleteDestination(clusterId: string, destination: string) {
  const ids = useRoleIds(clusterId);
  return useLifecycleMutation<LifecycleVars>(clusterId, ({ dryRun }) =>
    request(`${lifecycleBase(clusterId)}${resourcePath(ids.destination(destination))}${lifecycleQuery(dryRun)}`, {
      method: 'DELETE',
    }),
  );
}

/** The four closes `connection-control` exposes; the first three are node-scoped. */
export type ConnectionCloseKind = 'connection' | 'channel' | 'consumer' | 'destination-consumers';

export const useDestinations = (id: string, p: ResourceParams = {}) =>
  useResource<DestinationView>(id, 'destinations', p);

export const useConsumers = (id: string, p: ResourceParams = {}) => useResource<ConsumerView>(id, 'consumers', p);

export const useChannels = (id: string, p: ResourceParams = {}) => useResource<ChannelView>(id, 'channels', p);

export const useConnections = (id: string, p: ResourceParams = {}) => useResource<ConnectionView>(id, 'connections', p);

export const useProducers = (id: string, p: ResourceParams = {}) => useResource<ProducerView>(id, 'producers', p);

/**
 * Closing a connection, a channel, the connection behind a consumer, or every
 * consumer connection on a destination.
 *
 * <p>A by-id close names the **node** the identifier was issued by — the one
 * mutating call in Rahdar that is not cluster-wide, because a connection id
 * means nothing on another node. Only the address-scoped close names the
 * cluster.
 *
 * <p>A real run invalidates the three views a close changes at once. A preview
 * invalidates nothing: it mutated nothing, and refetching would cost a broker
 * round trip to learn what we already know.
 */
function useCloseMutation<V extends LifecycleVars>(clusterId: string, send: (vars: V) => Promise<ConnectionCloseView>) {
  const qc = useQueryClient();
  return useMutation<ConnectionCloseView, ApiError, V>({
    mutationFn: send,
    onSuccess: (result) => {
      if (result.outcome.dryRun) return;
      for (const topic of ['connections', 'channels', 'consumers'] as const) {
        void qc.invalidateQueries({ queryKey: keys.topic(clusterId, topic) });
      }
    },
  });
}

/**
 * A connection, a channel or a consumer, named by its id and closed on the node that issued it
 * (`:close?node=`).
 */
export function useCloseNodeTarget(clusterId: string, nodeId: string, targetId: string) {
  return useCloseMutation<LifecycleVars>(clusterId, ({ dryRun }) =>
    request(`${lifecycleBase(clusterId)}${resourcePath(targetId)}:close${lifecycleQuery(dryRun, undefined, nodeId)}`, {
      method: 'POST',
    }),
  );
}

/** Every consumer connection bound to a destination, on every live node. Capped. */
export function useCloseDestinationConsumers(clusterId: string, destination: string) {
  const ids = useRoleIds(clusterId);
  return useCloseMutation<LifecycleVars>(clusterId, ({ dryRun, override }) =>
    request(
      `${lifecycleBase(clusterId)}${resourcePath(ids.destination(destination))}:close${lifecycleQuery(dryRun, override)}`,
      {
        method: 'POST',
      },
    ),
  );
}
