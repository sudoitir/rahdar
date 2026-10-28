import { useMutation, useQuery, useQueryClient, type UseQueryResult } from '@tanstack/react-query';
import { requestAll } from '../../kernel/api/paging.ts';
import { ApiError, clusterKey, request } from '../../kernel/api/request.ts';
import type { components } from '../../kernel/api/schema.d.ts';

type Schemas = components['schemas'];

export type SqlBoundView = Schemas['BoundView'];
export type SqlCapturePreviewView = Schemas['CapturePreviewView'];
export type SqlIndexSubscriptionRequest = Schemas['IndexSubscriptionRequest'];
export type SqlIndexSubscriptionView = Schemas['IndexSubscriptionView'];
export type SqlNodeOutcomeView = Schemas['SqlNodeOutcomeView'];
export type SqlNoticeView = Schemas['NoticeView'];
export type SqlPlanView = Schemas['QueryPlanView'];
export type SqlResultView = Schemas['ResultView'];
export type SqlRowView = Schemas['RowView'];
export type SqlTailStatusView = Schemas['TailStatusView'];
export type SqlVerifyView = Schemas['VerifyView'];

export const keys = {
  sqlIndex: (id: string) => clusterKey(id, 'sql', 'index'),
  sqlPlan: (id: string, sql: string) => clusterKey(id, 'sql', 'plan', sql),
};

/**
 * Plan a query without running it. Contacts no broker, so it is safe to call
 * while the operator types — the caller debounces the text it passes in.
 *
 * <p>A rejected query is a 400 carrying the offending token, which is the whole
 * point of calling this: it is how the editor reports a syntax error inline. It
 * is never retried, because the same text will be rejected the same way.
 */
export function useSqlPlan(clusterId: string, sql: string): UseQueryResult<SqlPlanView, ApiError> {
  return useQuery({
    queryKey: keys.sqlPlan(clusterId, sql),
    queryFn: () =>
      request<SqlPlanView>(`/clusters/${clusterId}/sql/plan`, {
        method: 'POST',
        body: JSON.stringify({ sql }),
      }),
    enabled: sql.trim().length > 0,
    retry: false,
    staleTime: 30_000,
    placeholderData: (prev) => prev,
  });
}

/**
 * Ask the broker whether one indexed row is still on its subscription.
 *
 * <p>A mutation rather than a query, because it costs a broker read and answers
 * about a moment rather than about a resource: caching the verdict would be
 * caching a claim that expires the instant it is made.
 */
export function useVerifyOnBroker(clusterId: string) {
  return useMutation<SqlVerifyView, ApiError, SqlRowView>({
    mutationFn: (row) =>
      request<SqlVerifyView>(`/clusters/${clusterId}/sql/verify`, {
        method: 'POST',
        body: JSON.stringify({
          nodeId: row.nodeId,
          subscription: row.subscription,
          // A captured row's own messageId belongs to the diverted copy, which never
          // existed on the source subscription. The id to ask the broker about is the source
          // one the divert copied across (ADR-0062 D2).
          messageId: row.origin === 'CAPTURED' ? row.sourceMessageId : row.messageId,
          timestamp: row.timestamp,
        }),
      }),
  });
}

export function useIndexSubscriptions(clusterId: string): UseQueryResult<SqlIndexSubscriptionView[], ApiError> {
  return useQuery({
    queryKey: keys.sqlIndex(clusterId),
    queryFn: () => requestAll<SqlIndexSubscriptionView>(`/clusters/${clusterId}/sql/index`),
  });
}

export function useCreateIndexSubscription(clusterId: string) {
  const qc = useQueryClient();
  return useMutation<SqlIndexSubscriptionView, ApiError, SqlIndexSubscriptionRequest>({
    mutationFn: (body) =>
      request(`/clusters/${clusterId}/sql/index`, {
        method: 'POST',
        body: JSON.stringify(body),
      }),
    onSuccess: () => qc.invalidateQueries({ queryKey: keys.sqlIndex(clusterId) }),
  });
}

/** The dry run of creating a subscription: what it would cover and create. Saves nothing, contacts no broker. */
export function usePreviewIndexSubscription(clusterId: string) {
  return useMutation<SqlCapturePreviewView, ApiError, SqlIndexSubscriptionRequest>({
    mutationFn: (body) =>
      request(`/clusters/${clusterId}/sql/index?dryRun=true`, {
        method: 'POST',
        body: JSON.stringify(body),
      }),
  });
}

export function useUpdateIndexSubscription(clusterId: string) {
  const qc = useQueryClient();
  return useMutation<SqlIndexSubscriptionView, ApiError, { id: string; body: SqlIndexSubscriptionRequest }>({
    mutationFn: ({ id, body }) =>
      request(`/clusters/${clusterId}/sql/index/${id}`, {
        method: 'PATCH',
        body: JSON.stringify(body),
      }),
    onSuccess: () => qc.invalidateQueries({ queryKey: keys.sqlIndex(clusterId) }),
  });
}

/** Deletes the subscription and everything it captured; resolves with how much was destroyed. */
export function useDeleteIndexSubscription(clusterId: string) {
  const qc = useQueryClient();
  return useMutation<{ messagesDestroyed: number }, ApiError, string>({
    mutationFn: (id) => request(`/clusters/${clusterId}/sql/index/${id}`, { method: 'DELETE' }),
    onSuccess: () => qc.invalidateQueries({ queryKey: keys.sqlIndex(clusterId) }),
  });
}

/*
 * Running a query is not here: it is a stream, in `sql/useSqlTail.ts`. Execution
 * and the live tail share one path so that progress, per-node outcomes and
 * cancellation have one implementation, and abandoning a query actually stops the
 * broker reads.
 */
