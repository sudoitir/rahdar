import { useInfiniteQuery, useMutation, useQuery, useQueryClient, type UseQueryResult } from '@tanstack/react-query';

import { ApiError, clusterKey, request } from '../../kernel/api/request.ts';
import type { BacklogValue } from '../../ui/backlog.ts';

/*
 * The Kafka API of design D7, under `/clusters/{c}/kafka/`. These types are hand-written until the backend
 * generates them; replace each with its `Schemas[...]` then.
 */

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

export interface GroupSummary {
  id: string;
  type: string;
  state: string;
  members: number;
  lag: BacklogValue;
  /** Why the group cannot be changed, such as a share group's offsets. */
  readOnly?: string | null;
}

export interface GroupPartition {
  partition: number;
  committed?: number | null;
  end: number;
  lag?: number | null;
  reason?: string | null;
}

export interface GroupTopic {
  topic: string;
  deleted: boolean;
  partitions: GroupPartition[];
}

export interface GroupMember {
  id: string;
  clientId: string;
  host: string;
  assignments: string[];
}

export interface GroupDetail {
  id: string;
  type: string;
  state: string;
  readOnly?: string | null;
  topics: GroupTopic[];
  members: GroupMember[];
  sampledAt: string;
}

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
      return request<{ items: GroupSummary[]; next: string | null }>(
        `${base(clusterId)}/groups${query ? `?${query}` : ''}`,
      );
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

export type ResetTarget = 'earliest' | 'latest' | 'time' | 'offset';

export interface ResetRequest {
  topics?: string[];
  to: ResetTarget;
  value?: string;
}

export interface ResetPartition {
  topic: string;
  partition: number;
  current?: number | null;
  next: number;
  /** Records the group would skip (moving forward). */
  skipped: number;
  /** Records the group would read again (moving back). */
  reread: number;
  note?: string | null;
}

export interface ResetPreview {
  partitions: ResetPartition[];
  /** The members that still hold the group, named, when it is not empty. */
  members: string[];
  /** Why the reset cannot run right now: members, or a rebalance in progress. */
  blocked?: string | null;
  etag: string;
}

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
  return useMutation<unknown, ApiError, ResetRequest & { etag: string }>({
    mutationFn: (body) =>
      request(`${base(clusterId)}/groups/${seg(group)}/offsets:reset`, { method: 'POST', body: JSON.stringify(body) }),
    onSuccess: () => qc.invalidateQueries({ queryKey: keys.all(clusterId) }),
  });
}

// ── Topic configuration ──────────────────────────────────────────────────────

export interface ConfigEntry {
  name: string;
  value?: string | null;
  source: 'TOPIC' | 'BROKER_DEFAULT' | 'STATIC' | 'DEFAULT';
  default?: string | null;
  readOnly: boolean;
  sensitive: boolean;
  unit?: string | null;
}

export interface TopicConfig {
  internal: boolean;
  entries: ConfigEntry[];
}

export interface ConfigChange {
  set: Record<string, string>;
  remove: string[];
}

export interface ConfigPreview {
  entries: {
    name: string;
    before?: string | null;
    after?: string | null;
    /** What the entry falls back to when it is removed. */
    fallback?: string | null;
    /** The driver's validation error for this entry. */
    error?: string | null;
  }[];
  etag: string;
}

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
  return useMutation<unknown, ApiError, ConfigChange & { etag: string }>({
    mutationFn: (body) =>
      request(`${base(clusterId)}/topics/${seg(topic)}/config:apply`, { method: 'POST', body: JSON.stringify(body) }),
    onSuccess: () => qc.invalidateQueries({ queryKey: keys.config(clusterId, topic) }),
  });
}

// ── ACLs ─────────────────────────────────────────────────────────────────────

export interface Acl {
  principal: string;
  host: string;
  operation: string;
  permission: 'ALLOW' | 'DENY';
  resourceType: string;
  pattern: string;
  name: string;
}

export interface AclFilter {
  principal?: string;
  resource?: string;
}

export interface AclPreview {
  /** Effective access per principal and resource, before and after. */
  effects: { principal: string; resource: string; before: string[]; after: string[] }[];
  /** What a principal would lose, in words: "User:app loses READ on topic orders". */
  losses: string[];
  /** Notes on DENY entries, which override ALLOW. */
  notes: string[];
  etag: string;
}

export interface AclChange {
  add: Acl[];
  remove: Acl[];
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
      return request<{ available: boolean; reason?: string | null; items: Acl[]; next: string | null }>(
        `${base(clusterId)}/acls${query ? `?${query}` : ''}`,
      );
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
  return useMutation<unknown, ApiError, AclChange & { etag: string }>({
    mutationFn: (body) => request(`${base(clusterId)}/acls:apply`, { method: 'POST', body: JSON.stringify(body) }),
    onSuccess: () => qc.invalidateQueries({ queryKey: [...keys.all(clusterId), 'acls'] }),
  });
}

// ── Transactions ─────────────────────────────────────────────────────────────

export interface Transaction {
  id: string;
  state: string;
  startedAt?: string | null;
  /** The transaction timeout, in milliseconds. */
  timeout: number;
  hanging: boolean;
  blocked: { topic: string; partition: number }[];
}

export interface AbortPreview {
  /** What aborting does, in words, naming the partitions it unblocks and that the producer is fenced. */
  effect: string;
  blocked: { topic: string; partition: number }[];
  etag: string;
}

export function useTransactions(clusterId: string): UseQueryResult<{ items: Transaction[] }, ApiError> {
  return useQuery({
    queryKey: keys.transactions(clusterId),
    queryFn: () => request<{ items: Transaction[] }>(`${base(clusterId)}/transactions`),
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
  return useMutation<unknown, ApiError, { etag: string }>({
    mutationFn: (body) =>
      request(`${base(clusterId)}/transactions/${seg(id)}:abort`, { method: 'POST', body: JSON.stringify(body) }),
    onSuccess: () => qc.invalidateQueries({ queryKey: keys.transactions(clusterId) }),
  });
}

// ── Brokers ──────────────────────────────────────────────────────────────────

export interface KafkaBroker {
  id: string;
  rack?: string | null;
  address: string;
  reachable: boolean;
  reason?: string | null;
  leaders: number;
  underReplicated: number;
  offline: number;
}

export interface Quorum {
  available: boolean;
  reason?: string | null;
  leader?: string | null;
  voters?: { id: string; lag: number; caughtUp: boolean }[];
  majority?: number;
}

export function useKafkaBrokers(
  clusterId: string,
): UseQueryResult<{ brokers: KafkaBroker[]; quorum: Quorum }, ApiError> {
  return useQuery({
    queryKey: keys.brokers(clusterId),
    queryFn: () => request<{ brokers: KafkaBroker[]; quorum: Quorum }>(`${base(clusterId)}/brokers`),
    refetchInterval: 15_000,
  });
}
