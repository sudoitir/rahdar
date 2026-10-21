import { useQuery, type UseQueryResult } from '@tanstack/react-query';
import { ApiError, clusterKey, request } from '../../kernel/api/request.ts';
import type { components } from '../../kernel/api/schema.d.ts';

type Schemas = components['schemas'];

export type MetricSeries = Schemas['MetricSeries'];
export type MetricSeriesResponse = Schemas['MetricSeriesResponse'];
export type MetricNodeSeries = Schemas['MetricNodeSeries'];

export const keys = {
  metrics: (id: string, params: MetricsParams) => clusterKey(id, 'metrics', params),
};

export interface MetricsParams {
  metrics: string[];
  /** The subscription's id; the cluster's totals when absent. */
  resource?: string;
  from: string;
  to: string;
  step?: string;
  /** `NODE` adds one entry per serving node beside the total; one subscription only (ADR-0110). */
  splitBy?: 'NODE';
}

/**
 * `refetchMs` is passed explicitly by the caller rather than defaulting to the
 * global 5s poll every other hook uses — a 7-day chart must not refetch every
 * 5 seconds, and an absolute (non-live) range must never poll at all.
 */
export function useMetrics(
  clusterId: string,
  params: MetricsParams,
  refetchMs: number | false = false,
  enabled = true,
): UseQueryResult<MetricSeriesResponse, ApiError> {
  return useQuery({
    queryKey: keys.metrics(clusterId, params),
    enabled,
    queryFn: () => {
      const sp = new URLSearchParams();
      for (const m of params.metrics) sp.append('metric', m);
      if (params.resource) sp.set('resource', params.resource);
      sp.set('from', params.from);
      sp.set('to', params.to);
      if (params.step) sp.set('step', params.step);
      if (params.splitBy) sp.set('splitBy', params.splitBy);
      return request<MetricSeriesResponse>(`/clusters/${clusterId}/metrics?${sp.toString()}`);
    },
    refetchInterval: refetchMs,
    placeholderData: (prev) => prev,
  });
}
