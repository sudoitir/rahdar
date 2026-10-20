import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { clusterKey, pluginApi, request } from '@rahdar/plugin-sdk';

export const ID = 'acme-notes';

export interface Note {
  id: string;
  queue: string;
  author: string;
  body: string;
  createdAt: string;
}

/** Every query of this plugin sits under the cluster's key, so the live topic invalidates them all at once. */
export const notesKey = (clusterId: string, ...parts: unknown[]) => clusterKey(clusterId, ID, ...parts);

export function useRecentNotes(clusterId: string) {
  return useQuery({
    queryKey: notesKey(clusterId, 'recent'),
    queryFn: () => request<Note[]>(pluginApi(ID, 'notes', clusterId)),
  });
}

export function useSubscriptionNotes(clusterId: string, subscription: string) {
  return useQuery({
    queryKey: notesKey(clusterId, 'subscription', subscription),
    queryFn: () => request<Note[]>(pluginApi(ID, `queues/${encodeURIComponent(subscription)}/notes`, clusterId)),
  });
}

export function useAddNote(clusterId: string, subscription: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (body: string) =>
      request<Note>(pluginApi(ID, `queues/${encodeURIComponent(subscription)}/notes`, clusterId), {
        method: 'POST',
        body: JSON.stringify({ body }),
      }),
    onSuccess: () => qc.invalidateQueries({ queryKey: notesKey(clusterId) }),
  });
}

export function useDeleteNote(clusterId: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (id: string) => request<void>(pluginApi(ID, `notes/${id}`, clusterId), { method: 'DELETE' }),
    onSuccess: () => qc.invalidateQueries({ queryKey: notesKey(clusterId) }),
  });
}
