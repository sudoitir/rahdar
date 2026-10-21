import { useQuery, type UseQueryResult } from '@tanstack/react-query';

import { clusterKey, request, type ApiError } from './request.ts';

/** Generic paged envelope (`PagedView<T>` on the backend). */
export interface PagedView<T> {
  data: T[];
  page: number;
  pageSize: number;
  /** The total across all pages; null where the server cannot know it. */
  count: number | null;
  hasNext: boolean;
}

/**
 * A page of a history that can be very long (`HistoryPage<T>` on the backend): by page number for the first
 * rows, then by the `next` cursor passed as `after`, which has no total.
 */
export interface HistoryPage<T> extends PagedView<T> {
  /** The cursor of the page after this one; null on the last page. */
  next: string | null;
  /** Read from a replica that may be a little behind the primary. */
  mayLag: boolean;
}

/** The largest page the server allows (`ResourceQuery.MAX_SIZE`). */
export const MAX_PAGE_SIZE = 500;

/**
 * Every row of a list the UI shows in full (roles, environments, channels, …): pages of the maximum size,
 * followed while the server says another page exists. Stopping early would read as "no such row".
 */
export async function requestAll<T>(path: string): Promise<T[]> {
  const joiner = path.includes('?') ? '&' : '?';
  const rows: T[] = [];
  for (let page = 1; ; page++) {
    const result = await request<PagedView<T>>(`${path}${joiner}size=${MAX_PAGE_SIZE}&page=${page}`);
    rows.push(...result.data);
    // An empty page that claims a successor would loop forever; it is the end.
    if (!result.hasNext || result.data.length === 0) return rows;
  }
}

export interface ResourceParams {
  q?: string;
  sort?: string;
  page?: number;
  size?: number;
}

export function resourceSearch(params: ResourceParams): string {
  const sp = new URLSearchParams();
  if (params.q) sp.set('q', params.q);
  if (params.sort) sp.set('sort', params.sort);
  if (params.page && params.page > 1) sp.set('page', String(params.page));
  if (params.size) sp.set('size', String(params.size));
  const s = sp.toString();
  return s ? `?${s}` : '';
}

/**
 * One page of a cluster-wide listing (subscriptions, destinations, consumers, …), refetched on the usual interval.
 * `topic` names the stream topic that invalidates it; `path` is the route when that is another word.
 */
export function useResource<T>(
  id: string,
  topic: string,
  params: ResourceParams,
  path: string = topic,
): UseQueryResult<PagedView<T>, ApiError> {
  return useQuery({
    queryKey: clusterKey(id, topic, params),
    queryFn: () => request<PagedView<T>>(`/clusters/${id}/${path}${resourceSearch(params)}`),
    refetchInterval: 5_000,
    placeholderData: (prev) => prev,
  });
}

/** Rows in the envelope every list endpoint answers with, for the fixtures that stand in for one. */
export function paged<T>(data: T[]): PagedView<T> {
  return { data, page: 1, pageSize: 50, count: data.length, hasNext: false };
}
