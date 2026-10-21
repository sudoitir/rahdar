import { useMutation, useQuery, useQueryClient, type UseQueryResult } from '@tanstack/react-query';

import { ApiError, clusterKey, request } from '../../kernel/api/request.ts';
import type { components } from '../../kernel/api/schema.d.ts';

type Schemas = components['schemas'];

export type SetupReviewView = Schemas['SetupReviewView'];
export type SetupFindingView = Schemas['SetupFindingView'];
export type AcceptRiskRequest = Schemas['AcceptRiskRequest'];

export const keys = {
  review: (id: string) => clusterKey(id, 'setup-review'),
};

/** The latest review. The SSE topic refreshes it when a review lands; the poll is a fallback. */
export function useSetupReview(clusterId: string): UseQueryResult<SetupReviewView, ApiError> {
  return useQuery({
    queryKey: keys.review(clusterId),
    queryFn: () => request<SetupReviewView>(`/clusters/${clusterId}/artemis/setup-review`),
    refetchInterval: 60_000,
  });
}

/** Runs a review now. Too soon after the last, the answer is the last review with a `notice`. */
export function useRunSetupReview(clusterId: string) {
  const qc = useQueryClient();
  return useMutation<SetupReviewView, ApiError, void>({
    mutationFn: () => request<SetupReviewView>(`/clusters/${clusterId}/artemis/setup-review/run`, { method: 'POST' }),
    onSuccess: (view) => qc.setQueryData(keys.review(clusterId), view),
  });
}

export function useAcceptRisk(clusterId: string) {
  const qc = useQueryClient();
  return useMutation<SetupReviewView, ApiError, AcceptRiskRequest>({
    mutationFn: (body) =>
      request<SetupReviewView>(`/clusters/${clusterId}/artemis/setup-review/acceptances`, {
        method: 'POST',
        body: JSON.stringify(body),
      }),
    onSuccess: (view) => qc.setQueryData(keys.review(clusterId), view),
  });
}

export function useRevokeRisk(clusterId: string) {
  const qc = useQueryClient();
  return useMutation<SetupReviewView, ApiError, { code: string; subject: string }>({
    mutationFn: ({ code, subject }) =>
      request<SetupReviewView>(
        `/clusters/${clusterId}/artemis/setup-review/acceptances?code=${encodeURIComponent(code)}&subject=${encodeURIComponent(subject)}`,
        { method: 'DELETE' },
      ),
    onSuccess: (view) => qc.setQueryData(keys.review(clusterId), view),
  });
}
