import { useInfiniteQuery, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';

import { ApiError, clusterKey, request } from '../../kernel/api/request.ts';

/**
 * Responses from the Kafka replication API. These match that API's JSON. They are local because the
 * generated OpenAPI types do not carry them yet. A missing figure is null, never an invented zero.
 */

export interface ReplicaOnBroker {
  broker: number;
  /** Bytes of this replica on this broker. Null when not known. */
  bytes: number | null;
  /** This replica's offset lag. Null when not known. */
  offsetLag: number | null;
  /** A future log that has not replaced the current one. */
  future: boolean;
}

export interface PartitionRow {
  topic: string;
  partition: number;
  /** Absent when the partition has no leader. */
  leader: number | null;
  /** Replicas in the order the cluster keeps them. */
  replicas: number[];
  inSyncReplicas: number[];
  offlineReplicas: number[];
  logStartOffset: number | null;
  logEndOffset: number | null;
  /** The leader replica's bytes on its broker, not remote storage. */
  sizeBytes: number | null;
  replicasOnBroker: ReplicaOnBroker[];
  /** True when the leader is the first replica. */
  preferredLeader: boolean;
  /** Eligible leader replicas, only when the cluster keeps them. */
  eligibleLeaderReplicas?: number[] | null;
  localLogStartOffset: number | null;
  tieredOffset: number | null;
  /** Why the tiered offset is absent, such as "not tiered". */
  tieredOffsetReason: string | null;
  /** Brokers that did not answer describeLogDirs. Absent when all did. */
  logDirsUnavailable: string | null;
}

export interface PartitionPage {
  items: PartitionRow[];
  /** The cursor of the next page; absent on the last. */
  next: string | null;
}

/** A rack suggestion. `label` is "suggestion": this list is not a move. */
export interface ReplicaSuggestion {
  label: string;
  topic: string;
  partition: number;
  currentReplicas: number[];
  replicas: number[];
  note: string;
}

export interface SelectedPartition {
  topic: string;
  partition: number;
}

export interface ElectionPreview {
  wouldChange: number;
  alreadyPreferred: number;
  etag: string;
  note: string;
}

export interface ElectionOutcome {
  scope: string;
  status: string;
  elected: number;
  alreadyPreferred: number;
}

export interface ReplicaMove {
  replicas: number[];
  throttleBytesPerSecond: number;
}

export interface ReassignPreview {
  currentReplicas: number[];
  replicas: number[];
  currentReplicationFactor: number;
  replicationFactor: number;
  minInSyncReplicas: number;
  /** Bytes to copy. Null when not known, never a stand-in zero. */
  bytes: number | null;
  bytesUnavailable: string | null;
  /** How long the copy should take. Null when it cannot be estimated. */
  duration: string | null;
  durationUnavailable: string | null;
  throttleOutcome: string;
  throttleBytesPerSecond: number;
  etag: string;
}

export interface ReassignOutcome {
  scope: string;
  status: string;
  throttleOutcome: string;
}

export interface CancelPartition {
  topic: string;
  partition: number;
  currentReplicas: number[];
  /** The replicas cancel leaves: the set before the move. */
  replicas: number[];
}

export interface CancelPreview {
  partitions: CancelPartition[];
  etag: string;
  note: string;
}

export interface CancelOutcome {
  scope: string;
  status: string;
  partitions: CancelPartition[];
}

export interface ReassignmentRow {
  topic: string;
  partition: number;
  /** The in-progress reassignment, when the cluster still lists one. */
  reassignment: string | null;
  /** This product started the move and stored its throttle baseline. */
  startedHere: boolean;
}

export interface ReassignmentList {
  items: ReassignmentRow[];
}

export interface PartitionSelection {
  partitions: SelectedPartition[];
  etag?: string;
}

/** The refusal a move gets when the throttle is missing or not a positive whole number of bytes per second. */
export const THROTTLE_PROBLEM =
  'A reassignment needs a positive number of bytes per second, so it cannot be confirmed.';

/** Names both numbers when the new factor cannot satisfy min.insync.replicas. Undefined when it can. */
export function factorProblem(factor: number, minInSync: number): string | undefined {
  if (factor >= minInSync) return undefined;
  return `Replication factor ${factor} is below min.insync.replicas ${minInSync}.`;
}

/** Broker numbers in the order typed. Empty, a non-integer or a repeat is an error, not a list. */
export function parseReplicas(text: string): { replicas: number[] } | { error: string } {
  const parts = text
    .split(',')
    .map((part) => part.trim())
    .filter((part) => part !== '');
  if (parts.length === 0) return { error: 'Name the replicas.' };
  const replicas: number[] = [];
  const seen = new Set<number>();
  for (const part of parts) {
    if (!/^(0|[1-9]\d*)$/.test(part)) return { error: 'A replica is a whole broker number.' };
    const broker = Number(part);
    if (seen.has(broker)) return { error: `Broker ${broker} is named twice.` };
    seen.add(broker);
    replicas.push(broker);
  }
  return { replicas };
}

/** A positive whole number of bytes per second, or nothing when the field is blank, fractional or not positive. */
export function wholeThrottle(value: number | string): number | undefined {
  if (typeof value === 'number') return Number.isSafeInteger(value) && value > 0 ? value : undefined;
  const text = value.trim();
  if (!/^[1-9]\d*$/.test(text)) return undefined;
  const parsed = Number(text);
  return Number.isSafeInteger(parsed) ? parsed : undefined;
}

const base = (clusterId: string) => `/clusters/${clusterId}/kafka`;
const seg = encodeURIComponent;

const withQuery = (path: string, query: string) => (query ? `${path}?${query}` : path);

const partitionsKey = (clusterId: string, topic: string) => clusterKey(clusterId, 'kafka', 'partitions', topic);
const replicationKey = (clusterId: string, topic: string) => clusterKey(clusterId, 'kafka', 'replication', topic);
const reassignmentsKey = (clusterId: string) => clusterKey(clusterId, 'kafka', 'reassignments');

const refreshKafka = (clusterId: string) => clusterKey(clusterId, 'kafka');

/** One topic's partitions, a page at a time. Replica order is the cluster's order. */
export function usePartitions(clusterId: string, topic: string, enabled = true) {
  return useInfiniteQuery({
    queryKey: partitionsKey(clusterId, topic),
    enabled,
    queryFn: ({ pageParam }) => {
      const sp = new URLSearchParams();
      if (pageParam) sp.set('cursor', pageParam);
      return request<PartitionPage>(withQuery(`${base(clusterId)}/topics/${seg(topic)}/partitions`, sp.toString()));
    },
    initialPageParam: undefined as string | undefined,
    getNextPageParam: (last) => last.next ?? undefined,
    refetchInterval: 15_000,
  });
}

/** Partitions the API already kept: under-replicated, offline, or not on the preferred leader. */
export function useReplication(clusterId: string, topic: string | undefined, enabled = true) {
  return useInfiniteQuery({
    queryKey: replicationKey(clusterId, topic ?? ''),
    enabled,
    queryFn: ({ pageParam }) => {
      const sp = new URLSearchParams();
      if (topic) sp.set('topic', topic);
      if (pageParam) sp.set('cursor', pageParam);
      return request<PartitionPage>(withQuery(`${base(clusterId)}/replication`, sp.toString()));
    },
    initialPageParam: undefined as string | undefined,
    getNextPageParam: (last) => last.next ?? undefined,
    refetchInterval: 15_000,
  });
}

export function useReassignments(clusterId: string, enabled = true) {
  return useQuery({
    queryKey: reassignmentsKey(clusterId),
    enabled,
    queryFn: () => request<ReassignmentList>(`${base(clusterId)}/reassignments`),
    refetchInterval: 15_000,
  });
}

export function useReplicaSuggestion(clusterId: string, topic: string, partition: number) {
  return useMutation<ReplicaSuggestion, ApiError, void>({
    mutationFn: () => request(`${base(clusterId)}/topics/${seg(topic)}/partitions/${partition}:suggestion`),
  });
}

export function usePreviewElection(clusterId: string) {
  return useMutation<ElectionPreview, ApiError, PartitionSelection>({
    mutationFn: (body) =>
      request(`${base(clusterId)}/replication:elect-preview`, { method: 'POST', body: JSON.stringify(body) }),
  });
}

export function useElectLeaders(clusterId: string) {
  const qc = useQueryClient();
  return useMutation<ElectionOutcome, ApiError, PartitionSelection>({
    mutationFn: (body) =>
      request(`${base(clusterId)}/replication:elect`, { method: 'POST', body: JSON.stringify(body) }),
    onSuccess: () => void qc.invalidateQueries({ queryKey: refreshKafka(clusterId) }),
  });
}

export function usePreviewReassign(clusterId: string, topic: string, partition: number) {
  return useMutation<ReassignPreview, ApiError, ReplicaMove>({
    mutationFn: (body) =>
      request(`${base(clusterId)}/topics/${seg(topic)}/partitions/${partition}:reassign-preview`, {
        method: 'POST',
        body: JSON.stringify(body),
      }),
  });
}

export function useReassign(clusterId: string, topic: string, partition: number) {
  const qc = useQueryClient();
  return useMutation<ReassignOutcome, ApiError, ReplicaMove & { etag: string }>({
    mutationFn: (body) =>
      request(`${base(clusterId)}/topics/${seg(topic)}/partitions/${partition}:reassign`, {
        method: 'POST',
        body: JSON.stringify(body),
      }),
    onSuccess: () => void qc.invalidateQueries({ queryKey: refreshKafka(clusterId) }),
  });
}

export function usePreviewCancel(clusterId: string) {
  return useMutation<CancelPreview, ApiError, PartitionSelection>({
    mutationFn: (body) =>
      request(`${base(clusterId)}/reassignments:cancel-preview`, { method: 'POST', body: JSON.stringify(body) }),
  });
}

export function useCancelReassignment(clusterId: string) {
  const qc = useQueryClient();
  return useMutation<CancelOutcome, ApiError, PartitionSelection>({
    mutationFn: (body) =>
      request(`${base(clusterId)}/reassignments:cancel`, { method: 'POST', body: JSON.stringify(body) }),
    onSuccess: () => void qc.invalidateQueries({ queryKey: refreshKafka(clusterId) }),
  });
}
