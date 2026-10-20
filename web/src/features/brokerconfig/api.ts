import { useMutation, useQuery, useQueryClient, type UseQueryResult } from '@tanstack/react-query';
import { requestAll } from '../../kernel/api/paging.ts';
import { ApiError, BASE, clusterKey, lifecycleQuery, type LifecycleVars, request } from '../../kernel/api/request.ts';
import { type ResourceParams } from '../../kernel/api/paging.ts';
import type { components } from '../../kernel/api/schema.d.ts';

type Schemas = components['schemas'];

export type ConfigAddressSettingKeyView = Schemas['ConfigAddressSettingKeyView'];
export type ConfigAddressSettingView = Schemas['ConfigAddressSettingView'];
export type ConfigAddressView = Schemas['ConfigAddressView'];
export type ConfigAdoptionView = Schemas['ConfigAdoptionView'];
export type ConfigBridgeCredentialView = Schemas['ConfigBridgeCredentialView'];
export type ConfigBridgeView = Schemas['ConfigBridgeView'];
export type BridgeCredentialRequest = Schemas['BridgeCredentialRequest'];
export type ConfigApplyDetailView = Schemas['ConfigApplyDetailView'];
export type ConfigApplyHistoryView = Schemas['ConfigApplyHistoryView'];
export type ConfigApplyOutcomeView = Schemas['ConfigApplyOutcomeView'];
export type ConfigApplyRequest = Schemas['ApplyRequest'];
export type ConfigCatalogueView = Schemas['ConfigCatalogueView'];
export type ConfigDeclarationView = Schemas['ConfigDeclarationView'];
export type ConfigDiffView = Schemas['ConfigDiffView'];
export type ConfigDivertView = Schemas['ConfigDivertView'];
export type ConfigDocumentView = Schemas['ConfigDocumentView'];
export type ConfigDriftFindingView = Schemas['ConfigDriftFindingView'];
export type ConfigDriftReportView = Schemas['ConfigDriftReportView'];
export type ConfigHazardView = Schemas['ConfigHazardView'];
export type ConfigImportResultView = Schemas['ConfigImportResultView'];
export type ConfigKeyView = Schemas['ConfigKeyView'];
export type ConfigNodeApplyView = Schemas['ConfigNodeApplyView'];
export type ConfigNodeConnectorsView = Schemas['ConfigNodeConnectorsView'];
export type ConfigNodeStateView = Schemas['ConfigNodeStateView'];
export type ConfigNodeValueView = Schemas['ConfigNodeValueView'];
export type ConfigNodeView = Schemas['ConfigNodeView'];
export type ConfigQueueView = Schemas['ConfigQueueView'];
export type ConfigRecommendationView = Schemas['ConfigRecommendationView'];
export type ConfigRecommendationsView = Schemas['ConfigRecommendationsView'];
export type ConfigRevisionView = Schemas['ConfigRevisionView'];
export type ConfigSectionView = Schemas['ConfigSectionView'];
export type ConfigSecuritySettingView = Schemas['ConfigSecuritySettingView'];
export type ConfigStepApplyView = Schemas['ConfigStepApplyView'];
export type ConfigTransformerView = Schemas['ConfigTransformerView'];
export type ConfigureRequest = Schemas['ConfigureRequest'];
export type DeclareRecommendedRequest = Schemas['DeclareRecommendedRequest'];
export type NodeConfigView = Schemas['NodeConfigView'];
export type SaveDeclarationRequest = Schemas['SaveDeclarationRequest'];

export const keys = {
  brokerConfig: (id: string) => clusterKey(id, 'config'),
  resource: (id: string, kind: string, params: ResourceParams = {}) => clusterKey(id, kind, params),
  topic: (id: string, topic: string) => clusterKey(id, topic),
};

/** One node's effective broker configuration (ADR-0043 + ADR-0049). */
export function useNodeConfig(clusterId: string, nodeId: string | undefined): UseQueryResult<NodeConfigView, ApiError> {
  return useQuery({
    queryKey: ['clusters', clusterId, 'nodes', nodeId, 'config'] as const,
    queryFn: () => request<NodeConfigView>(`/clusters/${clusterId}/artemis/nodes/${nodeId}/config`),
    enabled: Boolean(nodeId),
  });
}

/**
 * Compare every node's broker configuration against the majority (ADR-0043, ADR-0178). Read-only, so
 * no mutation hook — and no polling: configuration does not change under the operator, and a
 * comparison costs one batched broker call per node. The filters are the view's own, applied to
 * this one answer.
 */
export function useConfigDiff(clusterId: string): UseQueryResult<ConfigDiffView, ApiError> {
  return useQuery({
    queryKey: ['clusters', clusterId, 'config-diff'] as const,
    queryFn: () => request<ConfigDiffView>(`/clusters/${clusterId}/artemis/config-diff`),
    staleTime: 30_000,
  });
}

const configBase = (clusterId: string) => `/clusters/${clusterId}/artemis/config`;

/** The declaration with each node's last evaluation; `declared: false` is the empty state, not an error. */
export function useBrokerConfig(clusterId: string): UseQueryResult<ConfigDeclarationView, ApiError> {
  return useQuery({
    queryKey: keys.brokerConfig(clusterId),
    queryFn: () => request<ConfigDeclarationView>(configBase(clusterId)),
    // Paced by the cluster's own `config.drift-interval`, because nothing on this
    // view changes between scheduled passes except an edit made in another tab.
    // Never slower than 30s, so an edit is not invisible for five minutes on a
    // default-configured cluster; never faster, so a tightened interval is
    // followed. An apply or an evaluation finishing arrives over SSE either way.
    refetchInterval: (query) => {
      const seconds = query.state.data?.driftIntervalSeconds;
      return Math.min(30_000, Math.max(5_000, (seconds ?? 30) * 1_000));
    },
  });
}

export function useBrokerConfigCatalogue(clusterId: string): UseQueryResult<ConfigCatalogueView, ApiError> {
  return useQuery({
    queryKey: [...keys.brokerConfig(clusterId), 'catalogue'],
    queryFn: () => request<ConfigCatalogueView>(`${configBase(clusterId)}/catalogue`),
    staleTime: Infinity,
  });
}

export function useBrokerConfigRevisions(clusterId: string): UseQueryResult<ConfigRevisionView[], ApiError> {
  return useQuery({
    queryKey: [...keys.brokerConfig(clusterId), 'revisions'],
    queryFn: () => requestAll<ConfigRevisionView>(`${configBase(clusterId)}/revisions`),
  });
}

export function useBrokerConfigApplies(clusterId: string): UseQueryResult<ConfigApplyHistoryView[], ApiError> {
  return useQuery({
    queryKey: [...keys.brokerConfig(clusterId), 'applies'],
    queryFn: () => requestAll<ConfigApplyHistoryView>(`${configBase(clusterId)}/applies`),
  });
}

export function useBrokerConfigApply(
  clusterId: string,
  id: number | null,
): UseQueryResult<ConfigApplyDetailView, ApiError> {
  return useQuery({
    queryKey: [...keys.brokerConfig(clusterId), 'applies', id],
    queryFn: () => request<ConfigApplyDetailView>(`${configBase(clusterId)}/applies/${id}`),
    enabled: id !== null,
  });
}

/** The exported `<core>` fragment, as text. Fetched on demand: it is a copy target, not a view. */
export async function fetchBrokerConfigXml(clusterId: string, revision?: number): Promise<string> {
  const query = revision ? `?revision=${revision}` : '';
  const res = await fetch(`${BASE}${configBase(clusterId)}/export-xml${query}`, {
    credentials: 'same-origin',
    headers: { accept: 'application/xml' },
  });
  if (!res.ok) {
    const text = await res.text();
    let body: Record<string, unknown> = {};
    try {
      body = text ? JSON.parse(text) : {};
    } catch {
      /* not a problem body */
    }
    throw new ApiError(res.status, body);
  }
  return res.text();
}

/**
 * Save a revision. `expectedRevision` is the one that was edited; a stale one is
 * refused with 409 `stale-revision` so two operators cannot silently overwrite
 * each other. A real save invalidates the whole config key: declared, drift
 * (now measured against a revision that no longer exists) and history.
 */
export function useSaveBrokerConfig(clusterId: string) {
  const qc = useQueryClient();
  return useMutation<ConfigDeclarationView, ApiError, SaveDeclarationRequest>({
    mutationFn: (body) =>
      request(configBase(clusterId), {
        method: 'PUT',
        body: JSON.stringify(body),
      }),
    // The saved declaration is the response: it is in the cache at once, so an editor that stays
    // open after a save — the divert editor after an inline "Create queue" — saves its next
    // revision against this one rather than racing the refetch into a stale-revision refusal.
    onSuccess: (saved) => {
      qc.setQueryData(keys.brokerConfig(clusterId), saved);
      return qc.invalidateQueries({ queryKey: keys.brokerConfig(clusterId) });
    },
  });
}

export function useConfigureBrokerConfig(clusterId: string) {
  const qc = useQueryClient();
  return useMutation<ConfigDeclarationView, ApiError, ConfigureRequest>({
    mutationFn: (body) =>
      request(`${configBase(clusterId)}/mode`, {
        method: 'PATCH',
        body: JSON.stringify(body),
      }),
    onSuccess: () => qc.invalidateQueries({ queryKey: keys.brokerConfig(clusterId) }),
  });
}

/** Parse a pasted `broker.xml` (or fragment). Nothing is saved: the result is previewed first. */
export function useImportBrokerConfigXml(clusterId: string) {
  return useMutation<ConfigImportResultView, ApiError, string>({
    mutationFn: (xml) =>
      request(`${configBase(clusterId)}/import-xml`, {
        method: 'POST',
        headers: { 'content-type': 'application/xml' },
        body: xml,
      }),
  });
}

/**
 * What the capability probe suggests declaring. A read of the brokers, so it is
 * fetched on demand rather than kept warm: the answer changes only when an apply
 * or a broker.xml edit changes it, and both invalidate the config key.
 */
export function useBrokerConfigRecommendations(
  clusterId: string,
  enabled = true,
): UseQueryResult<ConfigRecommendationsView, ApiError> {
  return useQuery({
    queryKey: [...keys.brokerConfig(clusterId), 'recommendations'],
    queryFn: () => request<ConfigRecommendationsView>(`${configBase(clusterId)}/recommendations`),
    enabled,
  });
}

/**
 * Declare the appliable recommendations as a new revision. Nothing reaches a
 * broker: the caller opens the plan next and applies it through the ordinary
 * gates.
 */
export function useDeclareRecommended(clusterId: string) {
  const qc = useQueryClient();
  return useMutation<ConfigDeclarationView, ApiError, DeclareRecommendedRequest>({
    mutationFn: (body) =>
      request(`${configBase(clusterId)}/recommendations/declare`, {
        method: 'POST',
        body: JSON.stringify(body),
      }),
    onSuccess: () => qc.invalidateQueries({ queryKey: keys.brokerConfig(clusterId) }),
  });
}

/** Build a declaration from what the live nodes run. Nothing is saved. */
export function useAdoptBrokerConfig(clusterId: string) {
  return useMutation<ConfigAdoptionView, ApiError, void>({
    mutationFn: () => request(`${configBase(clusterId)}/adopt`, { method: 'POST' }),
  });
}

/** Evaluate every live node now; the stored state is what `useBrokerConfig` shows afterwards. */
export function useEvaluateBrokerConfigDrift(clusterId: string) {
  const qc = useQueryClient();
  return useMutation<ConfigDriftReportView, ApiError, void>({
    mutationFn: () => request(`${configBase(clusterId)}/drift/evaluate`, { method: 'POST' }),
    onSuccess: () => qc.invalidateQueries({ queryKey: keys.brokerConfig(clusterId) }),
  });
}

/**
 * Plan (`dryRun`) or apply the declaration. Mirrors the lifecycle mutations: a
 * dry run changes nothing and invalidates nothing; a real run invalidates the
 * config key (drift and history moved) and the resources the steps may have
 * created. The result shape is the same either way, so the preview and the
 * result are comparable.
 */
export function useApplyBrokerConfig(clusterId: string) {
  const qc = useQueryClient();
  return useMutation<ConfigApplyOutcomeView, ApiError, LifecycleVars & { body: ConfigApplyRequest }>({
    mutationFn: ({ body, dryRun, override }) =>
      request(`${configBase(clusterId)}/apply${lifecycleQuery(dryRun, override)}`, {
        method: 'POST',
        body: JSON.stringify(body),
      }),
    onSuccess: (result) => {
      if (result.dryRun) return;
      void qc.invalidateQueries({ queryKey: keys.brokerConfig(clusterId) });
      void qc.invalidateQueries({ queryKey: keys.topic(clusterId, 'queues') });
      void qc.invalidateQueries({ queryKey: keys.resource(clusterId, 'addresses') });
      void qc.invalidateQueries({ queryKey: keys.resource(clusterId, 'diverts') });
    },
  });
}

/**
 * Each node's connector names, for a bridge to reference (ADR-0091). `known:
 * false` is "Rahdar could not read them", which is not the same as "there are
 * none" — the editor says so and keeps the field usable (ADR-0049 D5).
 *
 * <p>One batched read per serving node, on demand: it is asked for only when a
 * bridge editor is open, so the configuration screen costs nothing extra until
 * then (non-negotiable #1).
 */
export function useConfigConnectors(
  clusterId: string,
  enabled = true,
): UseQueryResult<ConfigNodeConnectorsView[], ApiError> {
  return useQuery({
    queryKey: [...keys.brokerConfig(clusterId), 'connectors'],
    queryFn: () => requestAll<ConfigNodeConnectorsView>(`${configBase(clusterId)}/connectors`),
    staleTime: 60_000,
    enabled,
  });
}

/** The bridge credentials this cluster holds: a reference and the user, never a password (ADR-0092). */
export function useBridgeCredentials(
  clusterId: string,
  enabled = true,
): UseQueryResult<ConfigBridgeCredentialView[], ApiError> {
  return useQuery({
    queryKey: [...keys.brokerConfig(clusterId), 'bridge-credentials'],
    queryFn: () => requestAll<ConfigBridgeCredentialView>(`${configBase(clusterId)}/bridge-credentials`),
    enabled,
  });
}

/** Seal a bridge credential in the vault under `ref`. The declaration only ever carries the ref. */
export function useSetBridgeCredential(clusterId: string) {
  const qc = useQueryClient();
  return useMutation<void, ApiError, { ref: string; body: BridgeCredentialRequest }>({
    mutationFn: ({ ref, body }) =>
      request(`${configBase(clusterId)}/bridge-credentials/${encodeURIComponent(ref)}`, {
        method: 'PUT',
        body: JSON.stringify(body),
      }),
    onSuccess: () => qc.invalidateQueries({ queryKey: [...keys.brokerConfig(clusterId), 'bridge-credentials'] }),
  });
}
