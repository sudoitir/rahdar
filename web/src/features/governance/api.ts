import { useMutation, useQuery, useQueryClient, type UseQueryResult } from '@tanstack/react-query';

import { requestAll } from '../../kernel/api/paging.ts';
import { ApiError, request } from '../../kernel/api/request.ts';
import type { components } from '../../kernel/api/schema.d.ts';

type Schemas = components['schemas'];

export type RuleView = Schemas['RuleView'];
export type RuleRequest = Schemas['RuleRequest'];
export type PolicyView = Schemas['PolicyView'];
export type FindingView = Schemas['FindingView'];

export const keys = {
  all: ['governance'] as const,
  rules: ['governance', 'rules'] as const,
  remask: ['governance', 'remask'] as const,
  findings: (status: string) => ['governance', 'findings', status] as const,
};

/** The classification inbox, filtered by status (OPEN, CONFIRMED, DISMISSED or ALL). */
export function useFindings(status: string): UseQueryResult<FindingView[], ApiError> {
  return useQuery({
    queryKey: keys.findings(status),
    queryFn: () => requestAll<FindingView>(`/governance/findings?status=${encodeURIComponent(status)}`),
  });
}

/** Confirm a finding into a rule, or dismiss it as a false positive. Both change the policy. */
export function useDecideFinding() {
  const qc = useQueryClient();
  return useMutation<FindingView, ApiError, { findingId: string; decision: 'confirm' | 'dismiss' }>({
    mutationFn: ({ findingId, decision }) =>
      request<FindingView>(`/governance/findings/${findingId}/${decision}`, { method: 'POST' }),
    onSuccess: () => qc.invalidateQueries({ queryKey: keys.all }),
  });
}

/** How many stored messages are still masked under an earlier policy. Polled: re-masking runs in the background. */
export function useRemaskProgress(): UseQueryResult<PolicyView, ApiError> {
  return useQuery({
    queryKey: keys.remask,
    queryFn: () => request<PolicyView>('/governance/remask'),
    refetchInterval: 30_000,
  });
}

export function useRules(): UseQueryResult<RuleView[], ApiError> {
  return useQuery({
    queryKey: keys.rules,
    queryFn: () => requestAll<RuleView>('/governance/rules'),
  });
}

export function useCreateRule() {
  const qc = useQueryClient();
  return useMutation<RuleView, ApiError, RuleRequest>({
    mutationFn: (body) => request<RuleView>('/governance/rules', { method: 'POST', body: JSON.stringify(body) }),
    onSuccess: () => qc.invalidateQueries({ queryKey: keys.all }),
  });
}

export function useUpdateRule() {
  const qc = useQueryClient();
  return useMutation<RuleView, ApiError, { ruleId: string; body: RuleRequest }>({
    mutationFn: ({ ruleId, body }) =>
      request<RuleView>(`/governance/rules/${ruleId}`, { method: 'PUT', body: JSON.stringify(body) }),
    onSuccess: () => qc.invalidateQueries({ queryKey: keys.all }),
  });
}

export function useDeleteRule() {
  const qc = useQueryClient();
  return useMutation<void, ApiError, string>({
    mutationFn: (ruleId) => request<void>(`/governance/rules/${ruleId}`, { method: 'DELETE' }),
    onSuccess: () => qc.invalidateQueries({ queryKey: keys.all }),
  });
}

/** The request that keeps a rule as it is except for `enabled` — all a built-in rule accepts. */
export function withEnabled(rule: RuleView, enabled: boolean): RuleRequest {
  return {
    destinationPattern: rule.destinationPattern ?? null,
    target: rule.target,
    selector: rule.selector,
    dataClass: rule.dataClass,
    action: rule.action ?? null,
    enabled,
  };
}
