import { useQuery, type UseQueryResult } from '@tanstack/react-query';
import { type HistoryPage } from '../../kernel/api/paging.ts';
import { ApiError, BASE, request } from '../../kernel/api/request.ts';
import type { components } from '../../kernel/api/schema.d.ts';

type Schemas = components['schemas'];

export type AuditEventView = Schemas['AuditEventView'];
export type AuditPageView = HistoryPage<AuditEventView>;

export interface AuditFilter {
  user?: string;
  action?: string;
  outcome?: string;
  /** Only the events that belong to this one, such as the subscriptions of a bulk run. */
  parentId?: number;
  from?: string;
  to?: string;
  page?: number;
  size?: number;
}

/**
 * One audit event by its id, for a link to an event that is not on the loaded page. A 404 is the
 * answer, not a failure to retry.
 */
export function useAuditEvent(clusterId: string, id: number | undefined): UseQueryResult<AuditEventView, ApiError> {
  return useQuery({
    queryKey: ['clusters', clusterId, 'audit', 'one', id],
    queryFn: () => request<AuditEventView>(`/clusters/${clusterId}/audit/${id}`),
    enabled: clusterId !== '' && id !== undefined,
    retry: false,
  });
}

export type AuditExportFormat = 'csv' | 'json' | 'ndjson';

/**
 * The URL that streams the filtered trail as a file, for a link the browser follows itself so the download goes
 * straight to disk and can be cancelled. The page and size of the list do not apply to it.
 */
export function auditExportHref(clusterId: string, filter: AuditFilter, format: AuditExportFormat): string {
  const sp = new URLSearchParams({ format });
  for (const key of ['user', 'action', 'outcome', 'parentId', 'from', 'to'] as const) {
    const value = filter[key];
    if (value !== undefined && value !== '') sp.set(key, String(value));
  }
  return `${BASE}/clusters/${clusterId}/audit/export?${sp.toString()}`;
}

export function useAudit(clusterId: string, filter: AuditFilter = {}): UseQueryResult<AuditPageView, ApiError> {
  return useQuery({
    queryKey: ['clusters', clusterId, 'audit', filter],
    queryFn: () => {
      const sp = new URLSearchParams();
      for (const [k, v] of Object.entries(filter)) {
        if (v !== undefined && v !== '' && !(k === 'page' && v === 1)) sp.set(k, String(v));
      }
      const qs = sp.toString();
      const query = qs ? `?${qs}` : '';
      return request<AuditPageView>(`/clusters/${clusterId}/audit${query}`);
    },
    refetchInterval: 5_000,
    placeholderData: (prev) => prev,
  });
}
