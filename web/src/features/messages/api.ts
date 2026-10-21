import { useMutation, useQuery, useQueryClient, type UseQueryResult } from '@tanstack/react-query';
import { ApiError, clusterKey, request } from '../../kernel/api/request.ts';
import { resourcePath } from '../../kernel/api/resourceId.ts';
import { useRoleIds } from '../clusters/index.ts';
import type { components } from '../../kernel/api/schema.d.ts';

type Schemas = components['schemas'];

export type AffectedView = Schemas['AffectedView'];
export type DeadLetterSubscription = Schemas['DeadLetterSubscription'];
export type DeadLetterView = Schemas['DeadLetterView'];
export type DryRunView = Schemas['DryRunView'];
export type MessageActionRequest = Schemas['MessageActionRequest'];
export type MessageDetailView = Schemas['MessageDetailView'];
export type MessagePageView = Schemas['MessagePageView'];
export type MessageSummaryView = Schemas['MessageSummaryView'];
export type PartialView = Schemas['PartialView'];
export type SendMessageRequest = Schemas['SendMessageRequest'];

export type MessageActionKind = 'move' | 'retry' | 'delete' | 'expire';

export const keys = {
  message: (id: string, subscriptionName: string, messageId: string, node?: string, filter?: string) =>
    clusterKey(id, 'subscriptions', subscriptionName, 'messages', messageId, { node, filter }),
  messages: (id: string, subscriptionName: string, params: MessageBrowseParams = {}) =>
    clusterKey(id, 'subscriptions', subscriptionName, 'messages', params),
  topic: (id: string, topic: string) => clusterKey(id, topic),
};

export interface MessageBrowseParams {
  node?: string;
  filter?: string;
  page?: number;
  size?: number;
}

function messageSearch(params: MessageBrowseParams): string {
  const sp = new URLSearchParams();
  if (params.node) sp.set('node', params.node);
  if (params.filter) sp.set('filter', params.filter);
  if (params.page && params.page > 1) sp.set('page', String(params.page));
  if (params.size) sp.set('size', String(params.size));
  const s = sp.toString();
  return s ? `?${s}` : '';
}

export function useMessages(
  clusterId: string,
  subscriptionName: string,
  params: MessageBrowseParams = {},
): UseQueryResult<MessagePageView, ApiError> {
  const ids = useRoleIds(clusterId);
  return useQuery({
    queryKey: keys.messages(clusterId, subscriptionName, params),
    queryFn: () =>
      request<MessagePageView>(
        `${messagesBase(clusterId, ids.subscription(subscriptionName))}${messageSearch(params)}`,
      ),
    // A subscription is not a document: messages arrive and are consumed while the
    // screen is open, and a browse that never refreshes shows an arrangement of
    // the subscription that stopped being true the moment it was drawn. One bounded
    // page read per interval, pausable with every other poll (ADR-0052).
    enabled: ids.loaded,
    refetchInterval: 10_000,
    placeholderData: (prev) => prev,
  });
}

export function useMessageDetail(
  clusterId: string,
  subscriptionName: string,
  messageId: string | null,
  node?: string,
  filter?: string,
): UseQueryResult<MessageDetailView, ApiError> {
  const ids = useRoleIds(clusterId);
  return useQuery({
    queryKey: keys.message(clusterId, subscriptionName, messageId ?? 'none', node, filter),
    queryFn: () => {
      const sp = new URLSearchParams();
      if (node) sp.set('node', node);
      if (filter) sp.set('filter', filter);
      const qs = sp.toString();
      const query = qs ? `?${qs}` : '';
      return request<MessageDetailView>(
        `${messagesBase(clusterId, ids.subscription(subscriptionName))}/${encodeURIComponent(messageId ?? '')}${query}`,
      );
    },
    enabled: messageId !== null && ids.loaded,
  });
}

/** Extra `type` on `ApiError` for the 422 bulk-cap problem so the UI can show the count/cap. */
export interface BulkCapProblem {
  affectedCount: number;
  cap: number;
}

function resourceBase(clusterId: string, subscription: string) {
  return `/clusters/${clusterId}${resourcePath(subscription)}`;
}

function messagesBase(clusterId: string, subscription: string) {
  return `${resourceBase(clusterId, subscription)}/messages`;
}

function mutationQuery(node?: string, dryRun?: boolean, override?: boolean): string {
  const sp = new URLSearchParams();
  if (node) sp.set('node', node);
  if (dryRun) sp.set('dryRun', 'true');
  if (override) sp.set('override', 'true');
  const s = sp.toString();
  return s ? `?${s}` : '';
}

export interface SendVars {
  body: SendMessageRequest;
  node?: string;
  dryRun?: boolean;
}

export function useSendMessage(clusterId: string, subscriptionName: string) {
  const ids = useRoleIds(clusterId);
  const qc = useQueryClient();
  return useMutation<AffectedView | DryRunView, ApiError, SendVars>({
    mutationFn: ({ body, node, dryRun }) =>
      request(`${messagesBase(clusterId, ids.subscription(subscriptionName))}${mutationQuery(node, dryRun)}`, {
        method: 'POST',
        body: JSON.stringify(body),
      }),
    onSuccess: (result) => {
      // DryRunView carries `cap`; AffectedView does not. Only a real run changes state.
      if ('cap' in result) return;
      void qc.invalidateQueries({ queryKey: keys.messages(clusterId, subscriptionName) });
      void qc.invalidateQueries({ queryKey: keys.topic(clusterId, 'subscriptions') });
    },
  });
}

export interface ActionVars {
  action: MessageActionKind;
  body: MessageActionRequest;
  node?: string;
  dryRun?: boolean;
  override?: boolean;
}

export function useMessageAction(clusterId: string, subscriptionName: string) {
  const ids = useRoleIds(clusterId);
  const qc = useQueryClient();
  return useMutation<AffectedView | DryRunView | PartialView, ApiError, ActionVars>({
    mutationFn: ({ action, body, node, dryRun, override }) =>
      request(
        `${resourceBase(clusterId, ids.subscription(subscriptionName))}/messages:${action}${mutationQuery(node, dryRun, override)}`,
        {
          method: 'POST',
          body: JSON.stringify(body),
        },
      ),
    onSuccess: (result) => {
      // DryRunView carries `cap`; AffectedView does not. Only a real run changes state.
      if ('cap' in result) return;
      void qc.invalidateQueries({ queryKey: keys.messages(clusterId, subscriptionName) });
      void qc.invalidateQueries({ queryKey: keys.topic(clusterId, 'subscriptions') });
    },
  });
}

export interface PurgeVars {
  node?: string;
  dryRun?: boolean;
  override?: boolean;
}

export function usePurgeSubscription(clusterId: string, subscriptionName: string) {
  const ids = useRoleIds(clusterId);
  const qc = useQueryClient();
  return useMutation<AffectedView | DryRunView, ApiError, PurgeVars>({
    mutationFn: ({ node, dryRun, override }) =>
      request(
        `${resourceBase(clusterId, ids.subscription(subscriptionName))}:purge${mutationQuery(node, dryRun, override)}`,
        {
          method: 'POST',
        },
      ),
    onSuccess: (result) => {
      // DryRunView carries `cap`; AffectedView does not. Only a real run changes state.
      if ('cap' in result) return;
      void qc.invalidateQueries({ queryKey: keys.messages(clusterId, subscriptionName) });
      void qc.invalidateQueries({ queryKey: keys.topic(clusterId, 'subscriptions') });
    },
  });
}

export function useDeadLetters(clusterId: string): UseQueryResult<DeadLetterView, ApiError> {
  return useQuery({
    queryKey: ['clusters', clusterId, 'dead-letters'],
    queryFn: () => request<DeadLetterView>(`/clusters/${clusterId}/dead-letters`),
    refetchInterval: 10_000,
  });
}
