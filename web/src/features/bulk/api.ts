import { useMutation, useQuery, useQueryClient, type UseQueryResult } from '@tanstack/react-query';
import { requestAll } from '../../kernel/api/paging.ts';
import { ApiError, clusterKey, request } from '../../kernel/api/request.ts';
import type { components } from '../../kernel/api/schema.d.ts';

type Schemas = components['schemas'];

export type BulkPreviewRequest = Schemas['BulkPreviewRequest'];
export type BulkExecuteRequest = Schemas['BulkExecuteRequest'];
export type BulkRunView = Schemas['BulkRunView'];
export type BulkRunDetailView = Schemas['BulkRunDetailView'];
export type BulkItemView = Schemas['BulkItemView'];
export type BulkOperation = BulkRunView['operation'];
export type LifecycleOutcomeView = Schemas['LifecycleOutcomeView'];

/** Everything bulk sits under one key, so a `bulk` stream frame refreshes the run in view and the history. */
export const keys = {
  all: (clusterId: string) => clusterKey(clusterId, 'bulk'),
  runs: (clusterId: string) => clusterKey(clusterId, 'bulk', 'runs'),
  run: (clusterId: string, runId: string) => clusterKey(clusterId, 'bulk', 'runs', runId),
};

const base = (clusterId: string) => `/clusters/${clusterId}/bulk`;

/** Freezes the subscription set and states its blast radius. Persists a PREVIEWED run; acts on nothing. */
export function useBulkPreview(clusterId: string) {
  return useMutation<BulkRunDetailView, ApiError, BulkPreviewRequest>({
    mutationFn: (body) =>
      request<BulkRunDetailView>(`${base(clusterId)}/preview`, { method: 'POST', body: JSON.stringify(body) }),
  });
}

export function useBulkExecute(clusterId: string) {
  const qc = useQueryClient();
  return useMutation<BulkRunView, ApiError, { runId: string; body: BulkExecuteRequest }>({
    mutationFn: ({ runId, body }) =>
      request<BulkRunView>(`${base(clusterId)}/runs/${runId}/execute`, { method: 'POST', body: JSON.stringify(body) }),
    onSuccess: () => qc.invalidateQueries({ queryKey: keys.all(clusterId) }),
  });
}

export function useBulkStop(clusterId: string, runId: string) {
  const qc = useQueryClient();
  return useMutation<BulkRunView, ApiError, void>({
    mutationFn: () => request<BulkRunView>(`${base(clusterId)}/runs/${runId}/stop`, { method: 'POST' }),
    onSuccess: () => qc.invalidateQueries({ queryKey: keys.all(clusterId) }),
  });
}

/** Kept current by the `bulk` stream topic, which invalidates it per subscription acted on. */
export function useBulkRun(clusterId: string, runId: string): UseQueryResult<BulkRunDetailView, ApiError> {
  return useQuery({
    queryKey: keys.run(clusterId, runId),
    queryFn: () => request<BulkRunDetailView>(`${base(clusterId)}/runs/${runId}`),
  });
}

export function useBulkRuns(clusterId: string): UseQueryResult<BulkRunView[], ApiError> {
  return useQuery({
    queryKey: keys.runs(clusterId),
    queryFn: () => requestAll<BulkRunView>(`${base(clusterId)}/runs`),
  });
}
