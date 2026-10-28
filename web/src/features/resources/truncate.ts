import { useMutation, useQueryClient } from '@tanstack/react-query';

import { ApiError, clusterKey, request } from '../../kernel/api/request.ts';

/** Where a truncate cuts: before an offset in each named partition, or before a time. */
export type TruncateBefore = { offsets: Record<string, number> } | { time: string };

/**
 * What a truncate does, or would do (design D6): how many records go, and every group that loses records it
 * has not read, with the count. Hand-written until the backend generates it.
 */
export interface TruncateEffect {
  records: number | null;
  groups: { group: string; records: number }[];
}

/** What is wrong with the cut as typed, by field; the cut itself when it is whole. */
export function parseCut(mode: 'offsets' | 'time', text: string): { before: TruncateBefore } | { problem: string } {
  const value = text.trim();
  if (mode === 'time') {
    return value && !Number.isNaN(Date.parse(value))
      ? { before: { time: value } }
      : { problem: 'Enter the time as ISO-8601, such as 2026-10-28T09:30:00Z.' };
  }
  const offsets: Record<string, number> = {};
  for (const token of value.split(/[\s,;]+/).filter(Boolean)) {
    const match = /^(\d+)[:=](\d+)$/.exec(token);
    if (!match) return { problem: `${token} is not partition:offset. Write each cut as 0:120, separated by commas.` };
    offsets[match[1]] = Number(match[2]);
  }
  return Object.keys(offsets).length > 0
    ? { before: { offsets } }
    : { problem: 'Enter at least one cut as partition:offset, such as 0:120.' };
}

/** The truncate of one destination: a preview (`dryRun`) of the effect, then the run. */
export function useTruncateDestination(clusterId: string, destinationId: string) {
  const qc = useQueryClient();
  const slash = destinationId.indexOf('/');
  const path = `/clusters/${clusterId}/destinations/${destinationId.slice(0, slash)}/${encodeURIComponent(destinationId.slice(slash + 1))}:truncate`;
  return useMutation<TruncateEffect, ApiError, { before: TruncateBefore; dryRun?: boolean }>({
    mutationFn: ({ before, dryRun }) =>
      request(`${path}${dryRun ? '?dryRun=true' : ''}`, { method: 'POST', body: JSON.stringify({ before }) }),
    onSuccess: (_, { dryRun }) => {
      if (!dryRun) void qc.invalidateQueries({ queryKey: clusterKey(clusterId) });
    },
  });
}
