import { useMutation, useQuery, useQueryClient, type UseQueryResult } from '@tanstack/react-query';
import { type PagedView, requestAll } from '../../kernel/api/paging.ts';
import { ApiError, request } from '../../kernel/api/request.ts';
import type { components } from '../../kernel/api/schema.d.ts';

type Schemas = components['schemas'];

export type CreateExpectationRequest = Schemas['CreateExpectationRequest'];
export type ExpectationDiagnosticsView = Schemas['ExpectationDiagnosticsView'];
export type ExpectationView = Schemas['ExpectationView'];
export type FlowView = Schemas['FlowView'];
export type FlowPageView = PagedView<FlowView>;
export type RrDiagnosticsView = Schemas['RrDiagnosticsView'];
export type StatsResponse = Schemas['StatsResponse'];
export type UpdateExpectationRequest = Schemas['UpdateExpectationRequest'];

export function useRrExpectations(clusterId: string): UseQueryResult<ExpectationView[], ApiError> {
  return useQuery({
    queryKey: ['clusters', clusterId, 'rr', 'expectations'],
    queryFn: () => requestAll<ExpectationView>(`/clusters/${clusterId}/rr/expectations`),
  });
}

export function useCreateRrExpectation(clusterId: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (body: CreateExpectationRequest) =>
      request<ExpectationView>(`/clusters/${clusterId}/rr/expectations`, {
        method: 'POST',
        body: JSON.stringify(body),
      }),
    onSuccess: () =>
      qc.invalidateQueries({
        queryKey: ['clusters', clusterId, 'rr', 'expectations'],
      }),
  });
}

export function useUpdateRrExpectation(clusterId: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({ id, body }: { id: string; body: UpdateExpectationRequest }) =>
      request<ExpectationView>(`/clusters/${clusterId}/rr/expectations/${id}`, {
        method: 'PUT',
        body: JSON.stringify(body),
      }),
    onSuccess: () =>
      qc.invalidateQueries({
        queryKey: ['clusters', clusterId, 'rr', 'expectations'],
      }),
  });
}

export function useDeleteRrExpectation(clusterId: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (id: string) =>
      request<void>(`/clusters/${clusterId}/rr/expectations/${id}`, {
        method: 'DELETE',
      }),
    onSuccess: () =>
      qc.invalidateQueries({
        queryKey: ['clusters', clusterId, 'rr', 'expectations'],
      }),
  });
}

export interface RrFlowFilter {
  state?: string;
  destination?: string;
  correlationId?: string;
  from?: string;
  to?: string;
  page?: number;
  size?: number;
}

export function useRrFlows(clusterId: string, filter: RrFlowFilter = {}): UseQueryResult<FlowPageView, ApiError> {
  return useQuery({
    queryKey: ['clusters', clusterId, 'rr', 'flows', filter],
    queryFn: () => {
      const sp = new URLSearchParams();
      for (const [k, v] of Object.entries(filter)) {
        if (v !== undefined && v !== '' && !(k === 'page' && v === 1)) sp.set(k, String(v));
      }
      const qs = sp.toString();
      const query = qs ? `?${qs}` : '';
      return request<FlowPageView>(`/clusters/${clusterId}/rr/flows${query}`);
    },
    refetchInterval: 5_000,
    placeholderData: (prev) => prev,
  });
}

export function useRrFlow(clusterId: string, flowId: string | undefined): UseQueryResult<FlowView, ApiError> {
  return useQuery({
    queryKey: ['clusters', clusterId, 'rr', 'flows', flowId],
    queryFn: () => request<FlowView>(`/clusters/${clusterId}/rr/flows/${flowId}`),
    enabled: !!flowId,
  });
}

/**
 * Why tracing is or is not producing flows.
 *
 * <p>Polls slowly on purpose: it is a diagnosis, not a live view, and it is read
 * on a screen an operator only opens when something already looks wrong.
 */
export function useRrDiagnostics(clusterId: string): UseQueryResult<RrDiagnosticsView, ApiError> {
  return useQuery({
    queryKey: ['clusters', clusterId, 'rr', 'diagnostics'],
    queryFn: () => request<RrDiagnosticsView>(`/clusters/${clusterId}/rr/diagnostics`),
    refetchInterval: 15_000,
  });
}

export function useRrStats(clusterId: string, window = 'PT15M'): UseQueryResult<StatsResponse, ApiError> {
  return useQuery({
    queryKey: ['clusters', clusterId, 'rr', 'stats', window],
    queryFn: () => request<StatsResponse>(`/clusters/${clusterId}/rr/stats?window=${window}`),
    refetchInterval: 10_000,
  });
}
