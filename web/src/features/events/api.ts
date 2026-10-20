import { useQuery, type UseQueryResult } from '@tanstack/react-query';
import { ApiError, request } from '../../kernel/api/request.ts';
import type { components } from '../../kernel/api/schema.d.ts';

type Schemas = components['schemas'];

export type BrokerEventPageView = Schemas['BrokerEventPageView'];
export type BrokerEventView = Schemas['BrokerEventView'];

export interface EventFilter {
  type?: string;
  nodeId?: string;
  destination?: string;
  from?: string;
  to?: string;
  page?: number;
  size?: number;
}

/**
 * One event by its id, for a link to an event that is not on the loaded page. A 404 is the
 * answer (retention reaped it), not a failure to retry.
 */
export function useEvent(clusterId: string, seq: number | undefined): UseQueryResult<BrokerEventView, ApiError> {
  return useQuery({
    queryKey: ['clusters', clusterId, 'events', 'one', seq],
    queryFn: () => request<BrokerEventView>(`/clusters/${clusterId}/events/${seq}`),
    enabled: clusterId !== '' && seq !== undefined,
    retry: false,
  });
}

export function useEvents(clusterId: string, filter: EventFilter = {}): UseQueryResult<BrokerEventPageView, ApiError> {
  return useQuery({
    queryKey: ['clusters', clusterId, 'events', filter],
    queryFn: () => {
      const sp = new URLSearchParams();
      for (const [k, v] of Object.entries(filter)) {
        if (v !== undefined && v !== '' && !(k === 'page' && v === 1)) sp.set(k, String(v));
      }
      const qs = sp.toString();
      const query = qs ? `?${qs}` : '';
      return request<BrokerEventPageView>(`/clusters/${clusterId}/events${query}`);
    },
    refetchInterval: 5_000,
    placeholderData: (prev) => prev,
  });
}
