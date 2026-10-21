import { useMemo } from 'react';
import { Stack, Text } from '@mantine/core';

import { EmptyState } from '../../ui/EmptyState.tsx';
import { ErrorState } from '../../ui/ErrorState.tsx';
import { useRrFlows } from './api.ts';
import { FlowsTable } from './FlowsTable.tsx';
import { STUCK_STATES } from './rrState.ts';

/** How many are stuck, in words; a failed read says the count is unknown, never zero. */
function stuckWords(pending: boolean, failed: boolean, count: number): string {
  if (pending) return 'Reading the flows…';
  if (failed) return 'The flows could not be read, so how many are stuck is not known.';
  return `${count} flow${count === 1 ? '' : 's'} timed out, orphaned, or dropped — oldest first.`;
}

/**
 * The panel an operator opens at 3am: awaiting-reply flows past half their
 * deadline, plus every terminal non-completed state, oldest first. One query
 * over every flow, filtered client-side — simpler than one hook per state and
 * the flow volume this screen deals with is small by construction (Phase 5
 * traces a handful of declared destinations, not a whole broker's traffic).
 */
export function StuckPanel({
  clusterId,
  onSelect,
}: Readonly<{ clusterId: string; onSelect: (flowId: string) => void }>) {
  const query = useRrFlows(clusterId, { size: 500 });

  const stuck = useMemo(
    () =>
      (query.data?.data ?? [])
        .filter((f) => (STUCK_STATES as readonly string[]).includes(f.state))
        .sort((a, b) => new Date(a.requestedAt).getTime() - new Date(b.requestedAt).getTime()),
    [query.data],
  );

  return (
    <Stack gap="sm">
      {/* Always one sentence, so the table below does not move when the count arrives. */}
      <Text size="sm" c="dimmed" role="status">
        {stuckWords(query.isPending, query.isError, stuck.length)}
      </Text>
      <FlowsTable
        label="Stuck flows"
        storageKey="rr.stuck"
        flows={stuck}
        loading={query.isPending}
        error={query.isError ? <ErrorState error={query.error} onRetry={() => void query.refetch()} /> : undefined}
        empty={
          <EmptyState
            kind="empty"
            title="No flow is stuck"
            description="A flow is stuck when its request timed out, was never answered, or was answered by a responder Rahdar could not match to it. None is in that state right now, which is the healthy answer."
          />
        }
        onSelect={onSelect}
      />
    </Stack>
  );
}
