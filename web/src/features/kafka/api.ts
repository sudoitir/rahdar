import { useInfiniteQuery, useMutation, useQuery, useQueryClient, type UseQueryResult } from '@tanstack/react-query';

import { ApiError, clusterKey, request } from '../../kernel/api/request.ts';
import type { components } from '../../kernel/api/schema.d.ts';

type Schemas = components['schemas'];

const base = (clusterId: string) => `/clusters/${clusterId}/kafka`;
const seg = encodeURIComponent;

export const keys = {
  all: (c: string) => clusterKey(c, 'kafka'),
  groups: (c: string, filter: GroupFilter) => clusterKey(c, 'kafka', 'groups', filter),
  group: (c: string, g: string) => clusterKey(c, 'kafka', 'group', g),
  config: (c: string, t: string) => clusterKey(c, 'kafka', 'config', t),
  acls: (c: string, f: AclFilter) => clusterKey(c, 'kafka', 'acls', f),
  transactions: (c: string) => clusterKey(c, 'kafka', 'transactions'),
  brokers: (c: string) => clusterKey(c, 'kafka', 'brokers'),
};

// ── Groups ───────────────────────────────────────────────────────────────────

export interface GroupFilter {
  type?: string;
  state?: string;
  q?: string;
}

export type GroupSummary = Schemas['GroupRow'];
export type GroupPartition = Schemas['PartitionLag'];
export type GroupTopic = Schemas['GroupTopic'];
export type GroupMember = Schemas['GroupMember'];
export type GroupDetail = Schemas['GroupDetail'];

/** The groups of the cluster a page at a time by opaque cursor; `fetchNextPage` reads the next. */
export function useGroups(clusterId: string, filter: GroupFilter) {
  return useInfiniteQuery({
    queryKey: keys.groups(clusterId, filter),
    queryFn: ({ pageParam }) => {
      const sp = new URLSearchParams();
      if (filter.type) sp.set('type', filter.type);
      if (filter.state) sp.set('state', filter.state);
      if (filter.q) sp.set('q', filter.q);
      if (pageParam) sp.set('cursor', pageParam);
      const query = sp.toString();
      return request<Schemas['GroupPage']>(`${base(clusterId)}/groups${query ? `?${query}` : ''}`);
    },
    initialPageParam: undefined as string | undefined,
    getNextPageParam: (last) => last.next ?? undefined,
    refetchInterval: 15_000,
  });
}

export function useGroup(clusterId: string, group: string): UseQueryResult<GroupDetail, ApiError> {
  return useQuery({
    queryKey: keys.group(clusterId, group),
    queryFn: () => request<GroupDetail>(`${base(clusterId)}/groups/${seg(group)}`),
    refetchInterval: 15_000,
  });
}

export type ResetRequest = Omit<Schemas['OffsetRequest'], 'etag'>;
export type ResetTarget = ResetRequest['to'];
export type ResetPartition = Schemas['OffsetRow'];
export type ResetOutcome = Schemas['OffsetResult'];
export type ResetPreview = Schemas['OffsetPreview'];

export function usePreviewOffsetReset(clusterId: string, group: string) {
  return useMutation<ResetPreview, ApiError, ResetRequest>({
    mutationFn: (body) =>
      request(`${base(clusterId)}/groups/${seg(group)}/offsets:preview`, {
        method: 'POST',
        body: JSON.stringify(body),
      }),
  });
}

export function useResetOffsets(clusterId: string, group: string) {
  const qc = useQueryClient();
  return useMutation<Schemas['OffsetResult'], ApiError, ResetRequest & { etag: string }>({
    mutationFn: (body) =>
      request(`${base(clusterId)}/groups/${seg(group)}/offsets:reset`, { method: 'POST', body: JSON.stringify(body) }),
    onSuccess: () => qc.invalidateQueries({ queryKey: keys.all(clusterId) }),
  });
}

// ── Topic configuration ──────────────────────────────────────────────────────

export type ConfigEntry = Schemas['ConfigEntry'];
export type TopicConfig = Schemas['TopicConfig'];
export type ConfigChange = Omit<Schemas['ConfigChangeRequest'], 'etag'>;
export type ConfigPreview = Schemas['ConfigPreview'];

export function useTopicConfig(clusterId: string, topic: string): UseQueryResult<TopicConfig, ApiError> {
  return useQuery({
    queryKey: keys.config(clusterId, topic),
    queryFn: () => request<TopicConfig>(`${base(clusterId)}/topics/${seg(topic)}/config`),
  });
}

export function usePreviewTopicConfig(clusterId: string, topic: string) {
  return useMutation<ConfigPreview, ApiError, ConfigChange>({
    mutationFn: (body) =>
      request(`${base(clusterId)}/topics/${seg(topic)}/config:preview`, { method: 'POST', body: JSON.stringify(body) }),
  });
}

export function useApplyTopicConfig(clusterId: string, topic: string) {
  const qc = useQueryClient();
  return useMutation<ConfigPreview, ApiError, ConfigChange & { etag: string }>({
    mutationFn: (body) =>
      request(`${base(clusterId)}/topics/${seg(topic)}/config:apply`, { method: 'POST', body: JSON.stringify(body) }),
    onSuccess: () => qc.invalidateQueries({ queryKey: keys.config(clusterId, topic) }),
  });
}

// ── ACLs ─────────────────────────────────────────────────────────────────────

export type Acl = Schemas['Acl'];
export type AclPreview = Schemas['AclPreview'];
export type AclChange = Omit<Schemas['AclChangeRequest'], 'etag'>;

export interface AclFilter {
  principal?: string;
  resource?: string;
}

export function useAcls(clusterId: string, filter: AclFilter) {
  return useInfiniteQuery({
    queryKey: keys.acls(clusterId, filter),
    queryFn: ({ pageParam }) => {
      const sp = new URLSearchParams();
      if (filter.principal) sp.set('principal', filter.principal);
      if (filter.resource) sp.set('resource', filter.resource);
      if (pageParam) sp.set('cursor', pageParam);
      const query = sp.toString();
      return request<Schemas['AclPage']>(`${base(clusterId)}/acls${query ? `?${query}` : ''}`);
    },
    initialPageParam: undefined as string | undefined,
    getNextPageParam: (last) => last.next ?? undefined,
  });
}

export function usePreviewAcls(clusterId: string) {
  return useMutation<AclPreview, ApiError, AclChange>({
    mutationFn: (body) => request(`${base(clusterId)}/acls:preview`, { method: 'POST', body: JSON.stringify(body) }),
  });
}

export function useApplyAcls(clusterId: string) {
  const qc = useQueryClient();
  return useMutation<Schemas['AclResult'], ApiError, AclChange & { etag: string }>({
    mutationFn: (body) => request(`${base(clusterId)}/acls:apply`, { method: 'POST', body: JSON.stringify(body) }),
    onSuccess: () => qc.invalidateQueries({ queryKey: [...keys.all(clusterId), 'acls'] }),
  });
}

// ── Transactions ─────────────────────────────────────────────────────────────

export type Transaction = Schemas['TransactionRow'];
export type AbortPreview = Schemas['AbortPreview'];

export function useTransactions(clusterId: string): UseQueryResult<Schemas['TransactionPage'], ApiError> {
  return useQuery({
    queryKey: keys.transactions(clusterId),
    queryFn: () => request<Schemas['TransactionPage']>(`${base(clusterId)}/transactions`),
    refetchInterval: 15_000,
  });
}

export function usePreviewAbort(clusterId: string, id: string) {
  return useMutation<AbortPreview, ApiError, void>({
    mutationFn: () => request(`${base(clusterId)}/transactions/${seg(id)}:abort-preview`, { method: 'POST' }),
  });
}

export function useAbortTransaction(clusterId: string, id: string) {
  const qc = useQueryClient();
  return useMutation<Schemas['AbortResult'], ApiError, { etag: string }>({
    mutationFn: (body) =>
      request(`${base(clusterId)}/transactions/${seg(id)}:abort`, { method: 'POST', body: JSON.stringify(body) }),
    onSuccess: () => qc.invalidateQueries({ queryKey: keys.transactions(clusterId) }),
  });
}

// ── Hanging transactions ─────────────────────────────────────────────────────

export type HangingTransaction = Schemas['HangingTransaction'];
export type HangingPage = Schemas['HangingPage'];
export type HangingAbortPreview = Schemas['HangingAbortPreview'];
export type HangingTarget = Pick<HangingTransaction, 'topic' | 'partition' | 'producerId'>;

/**
 * Looks for the transactions the coordinator does not know: it reads every partition in scope, so it is a button
 * and never a query that runs on mount. Whole cluster, or one topic; `cursor` reads the page after one.
 */
export function useFindHanging(clusterId: string) {
  return useMutation<HangingPage, ApiError, { topic?: string; cursor?: string }>({
    mutationFn: ({ topic, cursor }) => {
      const sp = new URLSearchParams({ limit: '100' });
      if (topic) sp.set('topic', topic);
      if (cursor) sp.set('cursor', cursor);
      return request(`${base(clusterId)}/hanging-transactions:find?${sp}`, { method: 'POST' });
    },
  });
}

export function usePreviewHangingAbort(clusterId: string) {
  return useMutation<HangingAbortPreview, ApiError, HangingTarget>({
    mutationFn: (target) =>
      request(`${base(clusterId)}/hanging-transactions:abort-preview`, {
        method: 'POST',
        body: JSON.stringify(target),
      }),
  });
}

export function useAbortHanging(clusterId: string) {
  return useMutation<Schemas['HangingAbortResult'], ApiError, HangingTarget & { etag: string }>({
    mutationFn: (body) =>
      request(`${base(clusterId)}/hanging-transactions:abort`, { method: 'POST', body: JSON.stringify(body) }),
  });
}

// ── Brokers ──────────────────────────────────────────────────────────────────

export type KafkaBroker = Schemas['BrokerRow'];
export type QuorumVoter = Schemas['QuorumVoter'];
export type Brokers = Schemas['Brokers'];

export function useKafkaBrokers(clusterId: string): UseQueryResult<Brokers, ApiError> {
  return useQuery({
    queryKey: keys.brokers(clusterId),
    queryFn: () => request<Brokers>(`${base(clusterId)}/brokers`),
    refetchInterval: 15_000,
  });
}
