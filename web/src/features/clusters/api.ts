import { useMemo } from 'react';
import { useMutation, useQuery, useQueryClient, type UseQueryResult } from '@tanstack/react-query';
import { requestAll, type PagedView } from '../../kernel/api/paging.ts';
import { useCan } from '../../kernel/auth/useCan.ts';
import { ApiError, clusterKey, request } from '../../kernel/api/request.ts';
import { destinationId, subscriptionId } from '../../kernel/api/resourceId.ts';
import type { components } from '../../kernel/api/schema.d.ts';

type Schemas = components['schemas'];

export type CapabilitiesView = Schemas['CapabilitiesView'];
export type CapabilityView = Schemas['CapabilityView'];
export type ClusterDetail = Schemas['ClusterDetail'];
export type ClusterSummary = Schemas['ClusterSummary'];
export type EnvironmentRequest = Schemas['EnvironmentRequest'];
export type EnvironmentView = Schemas['EnvironmentView'];
export type HealthView = Schemas['HealthView'];
export type LogicalNodeView = Schemas['LogicalNodeView'];
export type NodeEndpointView = Schemas['NodeEndpointView'];
export type ProblemDetail = Schemas['ProblemDetail'];
export type VersionGateView = Schemas['VersionGateView'];
export type RegisterPreview = Schemas['RegisterPreview'];
export type NodeProbeView = Schemas['NodeProbeView'];
export type AdoptionPreviewView = Schemas['AdoptionPreviewView'];
export type ConnectionView = Schemas['ConnectionView'];
export type ClusterConnectionView = Schemas['ClusterConnectionView'];
/** The edit: `core: null` clears the Core account, which the generated type cannot say. */
export type TopologyView = Schemas['TopologyView'];
export type EstateSubscription = Schemas['EstateSubscriptionView'];

// The registration contract of design D2. The types up to `UpdateClusterRequest` are hand-written until the
// generated schema carries them; replace them with `Schemas[...]` then.

/** A broker system Rahdar loads, from `GET /systems`. `connection` says which form the system's connection takes. */
export interface LoadedSystem {
  id: string;
  name: string;
  supportedVersions: string[];
  connection: 'jolokia-seeds' | 'bootstrap';
}

export type SecurityProtocol = 'PLAINTEXT' | 'SSL' | 'SASL_PLAINTEXT' | 'SASL_SSL';
export type SaslMechanism = 'PLAIN' | 'SCRAM-SHA-256' | 'SCRAM-SHA-512' | 'OAUTHBEARER';

export interface ArtemisConnection {
  system: 'artemis';
  seedUrls: string[];
  credentials?: Schemas['Credentials'];
  coreCredentials?: Schemas['Credentials'];
  tlsBundle?: string;
  managementUrlPattern?: string;
}

export interface KafkaConnection {
  system: 'kafka';
  bootstrapServers: string[];
  securityProtocol: SecurityProtocol;
  saslMechanism?: SaslMechanism | null;
  /** Read back, a connection holds the username only; a secret is never returned. */
  credentials?: { username: string; password?: string } | { token: string } | null;
  tlsBundle?: string | null;
  clientId?: string | null;
}

export type ConnectionInput = ArtemisConnection | KafkaConnection;

export interface RegisterClusterRequest {
  system: string;
  name?: string;
  description?: string;
  environmentId?: string;
  adopt?: boolean;
  connection: ConnectionInput;
}

export type CauseKind = 'DNS' | 'REFUSED' | 'TIMEOUT' | 'TLS' | 'AUTHENTICATION';

export interface KafkaNodeCheck {
  id: string;
  address: string;
  functions: string[];
  outcome: 'OK' | 'UNREACHABLE';
  cause: CauseKind | null;
  message: string | null;
}

/** The dry run of a Kafka registration or edit. */
export interface KafkaConnectionCheck {
  system: 'kafka';
  version: { text: string; mode: 'KRaft' | 'ZooKeeper'; exact: boolean };
  supported: boolean;
  clusterId: string | null;
  alreadyRegistered: { id: string; name: string } | null;
  nodes: KafkaNodeCheck[];
  quorum: { available: true; leader: string; voters: number } | { available: false; reason: string };
  access: { capability: string; status: CapabilityStatus; reason: string | null }[];
  failure: {
    cause: CauseKind | 'AUTHORIZATION' | 'UNSUPPORTED_VERSION';
    message: string;
    next: string;
  } | null;
}

/** The dry run of an Artemis registration: the probe, as before. */
export type ArtemisConnectionCheck = RegisterPreview & { system: 'artemis' };

export type RegistrationCheck = ArtemisConnectionCheck | KafkaConnectionCheck;

/** The edit of an Artemis cluster: `core: null` clears the Core account, which the generated type cannot say. */
export type ArtemisUpdateRequest = Omit<Schemas['UpdateClusterRequest'], 'core'> & {
  core?: Schemas['AccountUpdate'] | null;
};

/** The edit of a Kafka cluster: its whole connection, a secret left out keeping the stored one. */
export interface KafkaUpdateRequest {
  name?: string;
  description?: string;
  connection: KafkaConnection;
}

export type UpdateClusterRequest = ArtemisUpdateRequest | KafkaUpdateRequest;

/** The dry run of an Artemis edit, node by node. */
export type ConnectionCheck = Schemas['ConnectionCheck'];

/** String enums the backend serialises as bare strings; narrowed here for the UI. */
export type CapabilityStatus = 'AVAILABLE' | 'UNAVAILABLE' | 'UNKNOWN';
export type SplitBrain = 'NONE' | 'SUSPECTED' | 'CRITICAL';
export type HealthLevel = 'OK' | 'DEGRADED' | 'CRITICAL' | 'UNKNOWN';

export const keys = {
  all: ['clusters'] as const,
  detail: (id: string) => clusterKey(id),
  environments: ['environments'] as const,
  health: (id: string) => clusterKey(id, 'health'),
  topology: (id: string) => clusterKey(id, 'topology'),
};

export function useClusters(): UseQueryResult<ClusterSummary[], ApiError> {
  return useQuery({
    queryKey: keys.all,
    queryFn: () => requestAll<ClusterSummary>('/clusters'),
    refetchInterval: 5_000,
  });
}

export interface EstateParams {
  q?: string;
  cluster?: string;
  sort?: string;
  page?: number;
  size?: number;
}

/** The subscriptions of every cluster the caller may read, one page of the estate-wide list. */
export function useEstateSubscriptions(params: EstateParams): UseQueryResult<PagedView<EstateSubscription>, ApiError> {
  const search = new URLSearchParams();
  if (params.q) search.set('q', params.q);
  if (params.cluster) search.set('cluster', params.cluster);
  if (params.sort) search.set('sort', params.sort);
  if (params.page && params.page > 1) search.set('page', String(params.page));
  if (params.size) search.set('size', String(params.size));
  const query = search.size > 0 ? `?${search}` : '';
  return useQuery({
    queryKey: ['estate', 'subscriptions', params],
    queryFn: () => request<PagedView<EstateSubscription>>(`/subscriptions${query}`),
    refetchInterval: 5_000,
    placeholderData: (prev) => prev,
  });
}

export function useCluster(id: string | null): UseQueryResult<ClusterDetail, ApiError> {
  return useQuery({
    queryKey: id ? keys.detail(id) : ['clusters', 'none'],
    queryFn: () => request<ClusterDetail>(`/clusters/${id}`),
    enabled: id !== null,
    refetchInterval: 5_000,
  });
}

export function useTopology(id: string): UseQueryResult<TopologyView, ApiError> {
  return useQuery({
    queryKey: keys.topology(id),
    queryFn: () => request<TopologyView>(`/clusters/${id}/topology`),
    refetchInterval: 5_000,
  });
}

export function useHealth(id: string): UseQueryResult<HealthView, ApiError> {
  return useQuery({
    queryKey: keys.health(id),
    queryFn: () => request<HealthView>(`/clusters/${id}/health`),
    refetchInterval: 5_000,
  });
}

/** The broker systems Rahdar loads, for the registration page's choice. */
export function useSystems(): UseQueryResult<LoadedSystem[], ApiError> {
  return useQuery({
    queryKey: ['systems'],
    queryFn: () => request<PagedView<LoadedSystem>>('/systems').then((page) => page.data),
    staleTime: Infinity,
  });
}

export function useCheckConnection() {
  return useMutation<RegistrationCheck, ApiError, RegisterClusterRequest>({
    mutationFn: (body) =>
      request('/clusters?dryRun=true', {
        method: 'POST',
        body: JSON.stringify(body),
      }),
  });
}

/**
 * The problem a connection check or a registration answers when its brokers already belong to a
 * registered cluster (ADR-0167), or null for any other error. It names that cluster when the operator
 * may see it.
 */
export function alreadyRegistered(error: unknown): ProblemDetail | null {
  return error instanceof ApiError && error.type.endsWith('/cluster-already-registered')
    ? (error.problem as ProblemDetail)
    : null;
}

export function useRegisterCluster() {
  const qc = useQueryClient();
  return useMutation<ClusterDetail, ApiError, RegisterClusterRequest>({
    mutationFn: (body) => request('/clusters', { method: 'POST', body: JSON.stringify(body) }),
    onSuccess: () => qc.invalidateQueries({ queryKey: keys.all }),
  });
}

export function useOverrideNodeUrl(clusterId: string) {
  const qc = useQueryClient();
  return useMutation<NodeEndpointView, ApiError, { nodeId: string; jolokiaUrl?: string; coreUrl?: string }>({
    mutationFn: ({ nodeId, jolokiaUrl, coreUrl }) =>
      request(`/clusters/${clusterId}/nodes/${nodeId}`, {
        method: 'PATCH',
        body: JSON.stringify({ jolokiaUrl, coreUrl }),
      }),
    onSuccess: () => qc.invalidateQueries({ queryKey: keys.detail(clusterId) }),
  });
}

export function useDeleteCluster() {
  const qc = useQueryClient();
  return useMutation<void, ApiError, string>({
    mutationFn: (id) => request(`/clusters/${id}`, { method: 'DELETE' }),
    onSuccess: () => qc.invalidateQueries({ queryKey: keys.all }),
  });
}

/** Checks an edited connection node by node; nothing is saved. */
export function useCheckConnectionEdit(clusterId: string) {
  return useMutation<ConnectionCheck, ApiError, ArtemisUpdateRequest>({
    mutationFn: (body) =>
      request(`/clusters/${clusterId}?dryRun=true`, { method: 'PATCH', body: JSON.stringify(body) }),
  });
}

/** Checks an edited Kafka connection broker by broker; nothing is saved. */
export function useCheckKafkaConnectionEdit(clusterId: string) {
  return useMutation<KafkaConnectionCheck, ApiError, KafkaUpdateRequest>({
    mutationFn: (body) =>
      request(`/clusters/${clusterId}?dryRun=true`, { method: 'PATCH', body: JSON.stringify(body) }),
  });
}

/** Saves an edited connection; Rahdar rediscovers the nodes at once. */
export function useUpdateConnection(clusterId: string) {
  const qc = useQueryClient();
  return useMutation<ClusterConnectionView, ApiError, UpdateClusterRequest>({
    mutationFn: (body) => request(`/clusters/${clusterId}`, { method: 'PATCH', body: JSON.stringify(body) }),
    onSuccess: () => qc.invalidateQueries({ queryKey: keys.all }),
  });
}

/** Environments name and group clusters; someone without `environment:read` (a team member) gets none, and no refused request. */
export function useEnvironments(): UseQueryResult<EnvironmentView[], ApiError> {
  const { can } = useCan();
  return useQuery({
    queryKey: keys.environments,
    queryFn: () => requestAll<EnvironmentView>('/environments'),
    enabled: can('environment:read'),
  });
}

export function useCreateEnvironment() {
  const qc = useQueryClient();
  return useMutation<EnvironmentView, ApiError, EnvironmentRequest>({
    mutationFn: (body) =>
      request<EnvironmentView>('/environments', {
        method: 'POST',
        body: JSON.stringify(body),
      }),
    onSuccess: () => qc.invalidateQueries({ queryKey: keys.environments }),
  });
}

export function useUpdateEnvironment() {
  const qc = useQueryClient();
  return useMutation<EnvironmentView, ApiError, { environmentId: string; body: EnvironmentRequest }>({
    mutationFn: ({ environmentId, body }) =>
      request<EnvironmentView>(`/environments/${environmentId}`, {
        method: 'PUT',
        body: JSON.stringify(body),
      }),
    onSuccess: () => qc.invalidateQueries({ queryKey: keys.environments }),
  });
}

export function useDeleteEnvironment() {
  const qc = useQueryClient();
  return useMutation<void, ApiError, string>({
    mutationFn: (environmentId) => request<void>(`/environments/${environmentId}`, { method: 'DELETE' }),
    onSuccess: () => qc.invalidateQueries({ queryKey: keys.environments }),
  });
}

export function useAssignClusterEnvironment(clusterId: string) {
  const qc = useQueryClient();
  return useMutation<void, ApiError, string | null>({
    mutationFn: (environmentId) =>
      request<void>(`/clusters/${clusterId}/environment`, {
        method: 'PUT',
        body: JSON.stringify({ environmentId }),
      }),
    onSuccess: () => qc.invalidateQueries({ queryKey: keys.all }),
  });
}

/**
 * Builders for the ids of a subscription or a destination named only by its name (a URL param, a picker),
 * with the kind the cluster's system gives each role. They throw until the cluster has loaded.
 */
export function useRoleIds(clusterId: string) {
  const kinds = useCluster(clusterId === '' ? null : clusterId).data?.kinds;
  return useMemo(
    () => ({
      subscription: (name: string) => subscriptionId(kinds, name),
      destination: (name: string) => destinationId(kinds, name),
      loaded: kinds !== undefined,
    }),
    [kinds],
  );
}
