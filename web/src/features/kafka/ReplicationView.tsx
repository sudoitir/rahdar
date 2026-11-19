import { useMemo, useState } from 'react';
import { Button, TextInput } from '@mantine/core';
import { useDebouncedCallback } from '@mantine/hooks';
import { useNavigate, useParams, useSearch } from '@tanstack/react-router';

import { useCan } from '../../kernel/auth/useCan.ts';
import { ApiError } from '../../kernel/api/request.ts';
import { EmptyState } from '../../ui/EmptyState.tsx';
import { ErrorState } from '../../ui/ErrorState.tsx';
import { LoadingState } from '../../ui/LoadingState.tsx';
import { Notice } from '../../ui/Notice.tsx';
import { Page } from '../../ui/Page.tsx';
import { PageHeader } from '../../ui/PageHeader.tsx';
import { Toolbar } from '../../ui/Toolbar.tsx';
import { DataTable } from '../../ui/table/index.ts';
import { logDirMisses, partitionColumns } from './partitionColumns.tsx';
import { useReplication, type PartitionRow } from './placement.ts';
import { Reassignments, ReplicationActions } from './ReplicaMoves.tsx';
import type { ReplicationSearch } from './search.ts';

const IN_SYNC = 'Every partition is in sync and on its preferred leader.';
const FILTERED = 'No partition of this topic is under-replicated, offline, or off its preferred leader.';

const rowKey = (row: PartitionRow) => `${row.topic}-${row.partition}`;

/**
 * Partitions that are under-replicated, offline, or not on their preferred leader, and the moves in
 * progress. A cluster with none of those says so and shows no table. Opened during an incident: no
 * entrance animation, and both writes stay visible when they cannot be used.
 */
export function ReplicationView() {
  const { clusterId } = useParams({ strict: false }) as { clusterId: string };
  const search = useSearch({ strict: false }) as ReplicationSearch;
  const navigate = useNavigate();
  const { reach, loading: grantsLoading } = useCan();
  const read = reach('destination:read', clusterId);
  const list = useReplication(clusterId, search.topic, read !== 'none');
  const [topic, setTopic] = useState(search.topic ?? '');
  const [selected, setSelected] = useState<ReadonlySet<string>>(() => new Set());
  const [selectedFor, setSelectedFor] = useState(search.topic ?? '');
  if ((search.topic ?? '') !== selectedFor) {
    setSelectedFor(search.topic ?? '');
    setSelected(new Set());
  }

  const write = (next: string) =>
    void navigate({ to: '.', search: (prev: Record<string, unknown>) => ({ ...prev, topic: next || undefined }) });
  const writeTopic = useDebouncedCallback(write, 250);
  const clear = () => {
    writeTopic.cancel();
    setTopic('');
    write('');
  };

  const rows = list.data?.pages.flatMap((page) => page.items) ?? [];
  const showEligible = rows.some((row) => row.eligibleLeaderReplicas != null);
  const columns = useMemo(
    () => partitionColumns({ clusterId, showTopic: true, showEligible }),
    [clusterId, showEligible],
  );
  const chosen = rows.filter((row) => selected.has(rowKey(row)));
  const filtered = Boolean(search.topic);

  if (!grantsLoading && read === 'none') {
    return (
      <Page>
        <PageHeader title="Replication" />
        <ErrorState error={new ApiError(403, { title: 'Forbidden', permission: 'destination:read' })} />
      </Page>
    );
  }
  if (list.isError) {
    return (
      <Page>
        <PageHeader title="Replication" />
        <ErrorState error={list.error} onRetry={() => void list.refetch()} />
      </Page>
    );
  }
  if (!list.data) {
    return (
      <Page>
        <PageHeader title="Replication" />
        <LoadingState label="Loading replication" blockSize="12rem" />
      </Page>
    );
  }

  const misses = logDirMisses(rows);
  let body;
  if (!filtered && rows.length === 0) {
    body = <EmptyState kind="empty" title="In sync" description={IN_SYNC} />;
  } else if (rows.length === 0) {
    body = (
      <EmptyState
        kind="filtered"
        title="No partition matches this topic"
        description={FILTERED}
        onClearFilters={clear}
      />
    );
  } else {
    body = (
      <DataTable
        label="Partitions out of sync"
        storageKey="kafka.replication"
        height={{ maxRows: 12 }}
        columns={columns}
        data={rows}
        rowKey={rowKey}
        selectable
        selected={selected}
        onToggleRow={(key) =>
          setSelected((current) => {
            const next = new Set(current);
            if (next.has(key)) next.delete(key);
            else next.add(key);
            return next;
          })
        }
        onToggleAll={(keys, allSelected) => setSelected(allSelected ? new Set() : new Set(keys))}
        loading={list.isFetching && !list.isFetchingNextPage}
        toolbar={{
          end: list.hasNextPage ? (
            <Button
              size="xs"
              variant="default"
              loading={list.isFetchingNextPage}
              onClick={() => void list.fetchNextPage()}
            >
              Load more partitions
            </Button>
          ) : undefined,
        }}
        empty={null}
      />
    );
  }

  return (
    <Page>
      <PageHeader
        title="Replication"
        description="Partitions that are under-replicated, have an offline replica, or are not led by their first replica."
      />
      <Toolbar
        label="Replication filter"
        start={
          <TextInput
            label="Topic"
            size="xs"
            w="17.5rem"
            value={topic}
            onChange={(event) => {
              setTopic(event.currentTarget.value);
              writeTopic(event.currentTarget.value);
            }}
          />
        }
      />
      <ReplicationActions clusterId={clusterId} chosen={chosen} />
      {misses.map((miss) => (
        <Notice key={miss} tone="warning" title={miss} />
      ))}
      {body}
      <Reassignments clusterId={clusterId} />
    </Page>
  );
}
