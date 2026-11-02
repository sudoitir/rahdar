import { useInfiniteQuery, useMutation, useQuery, useQueryClient, type UseQueryResult } from '@tanstack/react-query';
import { useCallback, useState } from 'react';

import { ApiError, clusterKey, request } from '../../kernel/api/request.ts';
import { resourcePath } from '../../kernel/api/resourceId.ts';
import type { components } from '../../kernel/api/schema.d.ts';
import { notify, type ActionVerb } from '../../ui/notify.ts';

type Schemas = components['schemas'];

const base = (clusterId: string) => `/clusters/${clusterId}/rabbitmq`;
export const seg = encodeURIComponent;

const withQuery = (path: string, params: Record<string, string | undefined>) => {
  const sp = new URLSearchParams();
  for (const [name, value] of Object.entries(params)) if (value) sp.set(name, value);
  const query = sp.toString();
  return query ? `${path}?${query}` : path;
};

export const keys = {
  all: (c: string) => clusterKey(c, 'rabbitmq'),
  list: (c: string, what: string, filter: object) => clusterKey(c, 'rabbitmq', what, filter),
  queue: (c: string, id: string) => clusterKey(c, 'rabbitmq', 'queue', id),
};

// ── Reads ────────────────────────────────────────────────────────────────────

export type Nodes = Schemas['NodesView'];
export type NodeView = Schemas['NodeView'];
export type Vhost = Schemas['Vhost'];
export type Binding = Schemas['Binding'];
export type Shovel = Schemas['Shovel'];
export type FederationLink = Schemas['FederationLink'];
export type Policy = Schemas['Policy'];
export type Permission = Schemas['Permission'];
export type User = Schemas['User'];
export type RabbitMqPreview = Schemas['RabbitMqChangePreview'];
export type PolicyPreview = Schemas['PolicyPreview'];
export type RabbitMqDiff = Schemas['RabbitMqDiff'];

export function useNodes(clusterId: string): UseQueryResult<Nodes, ApiError> {
  return useQuery({
    queryKey: keys.list(clusterId, 'nodes', {}),
    queryFn: () => request<Nodes>(`${base(clusterId)}/nodes`),
    refetchInterval: 15_000,
  });
}

/** One of the paged lists, read a page at a time by opaque cursor; `fetchNextPage` reads the next. */
function usePaged<P extends { next?: string | null }>(
  clusterId: string,
  what: string,
  path: string,
  filter: Record<string, string | undefined>,
) {
  return useInfiniteQuery({
    queryKey: keys.list(clusterId, what, filter),
    queryFn: ({ pageParam }) =>
      request<P>(withQuery(`${base(clusterId)}/${path}`, { ...filter, cursor: pageParam })).catch((error: unknown) => {
        // A virtual host that does not exist has nothing in it: a filter typed to a name the broker does not know
        // is an empty list, not a failure.
        if (filter.vhost && error instanceof ApiError && error.status === 404) {
          return { available: true, items: [], next: null } as unknown as P;
        }
        throw error;
      }),
    initialPageParam: undefined as string | undefined,
    getNextPageParam: (last) => last.next ?? undefined,
  });
}

export const useVhosts = (c: string) => usePaged<Schemas['VhostPage']>(c, 'vhosts', 'vhosts', {});
export const useBindings = (c: string, filter: { vhost?: string; source?: string; destination?: string }) =>
  usePaged<Schemas['BindingPage']>(c, 'bindings', 'bindings', filter);
export const usePolicies = (c: string, filter: { vhost?: string }) =>
  usePaged<Schemas['PolicyPage']>(c, 'policies', 'policies', filter);
export const usePermissions = (c: string, filter: { vhost?: string; user?: string }) =>
  usePaged<Schemas['PermissionPage']>(c, 'permissions', 'permissions', filter);
export const useUsers = (c: string) => usePaged<Schemas['UserPage']>(c, 'users', 'users', {});
export const useShovels = (c: string, filter: { vhost?: string }) =>
  usePaged<Schemas['ShovelPage']>(c, 'shovels', 'shovels', filter);
export const useFederationLinks = (c: string, filter: { vhost?: string }) =>
  usePaged<Schemas['FederationPage']>(c, 'federation', 'federation-links', filter);

// ── Changes ──────────────────────────────────────────────────────────────────

/** One change to the broker: the verb and path of the real call; `:preview` and `?etag=` are added here. */
export interface Op {
  method: 'POST' | 'PUT' | 'PATCH' | 'DELETE';
  path: string;
  body?: object;
}

export const op = (clusterId: string, method: Op['method'], path: string, body?: object): Op => ({
  method,
  path: `${base(clusterId)}/${path}`,
  body,
});

function send<T>(call: Op, etag?: string): Promise<T> {
  if (etag === undefined) {
    return request<T>(`${call.path}:preview`, {
      method: call.method,
      body: call.body ? JSON.stringify(call.body) : undefined,
    });
  }
  if (call.method === 'DELETE') return request<T>(`${call.path}?etag=${seg(etag)}`, { method: 'DELETE' });
  return request<T>(call.path, { method: call.method, body: JSON.stringify({ ...call.body, etag }) });
}

/** A preview the operator is reading, with what it was taken of. */
export interface Reviewed<V, P> {
  vars: V;
  preview: P;
}

/**
 * The shape of every RabbitMQ change: preview first, show the effect, then the real call with the preview's etag.
 * A stale etag answers 412 and the dialog says so; a held answer closes it, as the Kafka views do.
 */
export function useChange<V, P extends RabbitMqPreview | PolicyPreview = RabbitMqPreview>(
  clusterId: string,
  call: (vars: V) => Op,
  /** Runs when the change is closed, however it ended, so a form behind it can close too. */
  onDone?: () => void,
) {
  const qc = useQueryClient();
  const [reviewed, setReviewed] = useState<Reviewed<V, P> | null>(null);
  const previewing = useMutation<P, ApiError, V>({ mutationFn: (vars) => send<P>(call(vars)) });
  const applying = useMutation<unknown, ApiError, Reviewed<V, P>>({
    mutationFn: ({ vars, preview }) => send(call(vars), preview.etag),
    onSuccess: () => qc.invalidateQueries({ queryKey: clusterKey(clusterId) }),
  });
  const { mutate: takePreview } = previewing;
  const start = useCallback(
    (vars: V, onReady?: () => void) =>
      takePreview(vars, {
        onSuccess: (preview) => {
          setReviewed({ vars, preview });
          onReady?.();
        },
      }),
    [takePreview],
  );
  const close = () => {
    setReviewed(null);
    previewing.reset();
    applying.reset();
    onDone?.();
  };
  return {
    reviewed,
    previewing: previewing.isPending,
    previewError: previewing.error,
    applying: applying.isPending,
    applyError: applying.error,
    /** Takes the preview of `vars`; `onReady` runs when it is shown, so a form can close behind it. */
    start,
    close,
    /** Runs the change the preview described. */
    apply: (action: ActionVerb, subject: string) => {
      if (!reviewed) return;
      applying.mutate(reviewed, {
        onSuccess: () => {
          notify.succeeded({ action, subject });
          close();
        },
        onError: (error) => {
          if (error.status === 412) return;
          notify.settle(error, {
            action,
            subject,
            cause: error.message,
            next: 'Check the audit log for what ran, then try again.',
            onHeld: close,
          });
        },
      });
    },
  };
}

export type Change<V, P extends RabbitMqPreview | PolicyPreview = RabbitMqPreview> = ReturnType<typeof useChange<V, P>>;

// ── Publish ──────────────────────────────────────────────────────────────────

export type PublishRequest = Schemas['SendMessageRequest'];

export function usePublish(clusterId: string, exchangeId: string) {
  const qc = useQueryClient();
  return useMutation<Schemas['AffectedView'] | Schemas['InDoubtView'], ApiError, PublishRequest>({
    mutationFn: (body) =>
      request(`/clusters/${clusterId}${resourcePath(exchangeId)}/messages`, {
        method: 'POST',
        body: JSON.stringify(body),
      }),
    onSuccess: () => qc.invalidateQueries({ queryKey: clusterKey(clusterId, 'subscriptions') }),
  });
}

/** A queue's own figures, read from the resource's native attributes. */
export function useQueueAttributes(clusterId: string, id: string) {
  return useQuery({
    queryKey: keys.queue(clusterId, id),
    queryFn: () => request<Schemas['SubscriptionConfiguration']>(`/clusters/${clusterId}${resourcePath(id)}`),
  });
}

/** The broker refused the message because no queue was bound for its routing key (a 409 `not-routed`). */
export const isNotRouted = (error: Error | null): boolean =>
  error instanceof ApiError && error.status === 409 && error.type.endsWith('/not-routed');
