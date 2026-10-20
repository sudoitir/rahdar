import { useMutation, useQueryClient } from '@tanstack/react-query';
import {
  ApiError,
  clusterKey,
  lifecycleBase,
  lifecycleQuery,
  type LifecycleVars,
  request,
} from '../../kernel/api/request.ts';
import { type ResourceParams, useResource } from '../../kernel/api/paging.ts';
import type { components } from '../../kernel/api/schema.d.ts';

type Schemas = components['schemas'];

export type BridgeView = Schemas['BridgeView'];
export type CapabilityView = Schemas['CapabilityView'];
export type CreateDivertRequest = Schemas['CreateDivertRequest'];
export type DivertMutationView = Schemas['DivertMutationView'];
export type DivertView = Schemas['DivertView'];
export type LifecycleOutcomeView = Schemas['LifecycleOutcomeView'];

export const keys = {
  resource: (id: string, kind: string, params: ResourceParams = {}) => clusterKey(id, kind, params),
};

export const useDiverts = (id: string, p: ResourceParams = {}) => useResource<DivertView>(id, 'diverts', p);

export const useBridges = (id: string, p: ResourceParams = {}) => useResource<BridgeView>(id, 'bridges', p);

/**
 * Create a divert across the cluster. The response carries the `broker.xml` that
 * would make the broker's own configuration match, built from the submitted
 * values — with `dryRun` it is the preview, so the blast radius and the
 * configuration arrive together and neither can be skipped past.
 */
export function useCreateDivert(clusterId: string) {
  const qc = useQueryClient();
  return useMutation<DivertMutationView, ApiError, LifecycleVars & { body: CreateDivertRequest }>({
    mutationFn: ({ body, dryRun }) =>
      request(`${lifecycleBase(clusterId)}/artemis/diverts${lifecycleQuery(dryRun)}`, {
        method: 'POST',
        body: JSON.stringify(body),
      }),
    onSuccess: (result) => {
      if (result.outcome.dryRun) return;
      void qc.invalidateQueries({ queryKey: keys.resource(clusterId, 'diverts') });
    },
  });
}

/**
 * Delete a divert across the cluster. Nothing else removes one — a divert created
 * over management outlives the broker process — so this is the only path.
 */
export function useDeleteDivert(clusterId: string, name: string) {
  const qc = useQueryClient();
  return useMutation<LifecycleOutcomeView, ApiError, LifecycleVars>({
    mutationFn: ({ dryRun }) =>
      request(`${lifecycleBase(clusterId)}/artemis/diverts/${encodeURIComponent(name)}${lifecycleQuery(dryRun)}`, {
        method: 'DELETE',
      }),
    onSuccess: (result) => {
      if (result.dryRun) return;
      void qc.invalidateQueries({ queryKey: keys.resource(clusterId, 'diverts') });
    },
  });
}
