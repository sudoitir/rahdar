import { useMutation, useQueryClient } from '@tanstack/react-query';
import {
  ApiError,
  clusterKey,
  lifecycleBase,
  lifecycleQuery,
  type LifecycleVars,
  request,
} from '../../kernel/api/request.ts';
import { type ResourceParams, useResource } from '../../kernel/api/paging.ts';
import { resourcePath } from '../../kernel/api/resourceId.ts';
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

/** The four closes `connection-control` exposes; the first three are node-scoped. */
export type ConnectionCloseKind = 'connection' | 'session' | 'consumer' | 'address-consumers';

export const keys = {
  topic: (id: string, topic: string) => clusterKey(id, topic),
};

export const useAddresses = (id: string, p: ResourceParams = {}) =>
  useResource<DestinationView>(id, 'addresses', p, 'destinations');

export const useConsumers = (id: string, p: ResourceParams = {}) => useResource<ConsumerView>(id, 'consumers', p);

export const useSessions = (id: string, p: ResourceParams = {}) =>
  useResource<ChannelView>(id, 'sessions', p, 'channels');

export const useConnections = (id: string, p: ResourceParams = {}) => useResource<ConnectionView>(id, 'connections', p);

export const useProducers = (id: string, p: ResourceParams = {}) => useResource<ProducerView>(id, 'producers', p);

/**
 * Closing a connection, a session, the connection behind a consumer, or every
 * consumer connection on an address.
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
      for (const topic of ['connections', 'sessions', 'consumers'] as const) {
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
export function useCloseAddressConsumers(clusterId: string, address: string) {
  const ids = useRoleIds(clusterId);
  return useCloseMutation<LifecycleVars>(clusterId, ({ dryRun, override }) =>
    request(
      `${lifecycleBase(clusterId)}${resourcePath(ids.destination(address))}:close${lifecycleQuery(dryRun, override)}`,
      {
        method: 'POST',
      },
    ),
  );
}
