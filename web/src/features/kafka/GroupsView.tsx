import { useMemo, useState } from 'react';
import { Button, Select, Text, TextInput } from '@mantine/core';
import { useDebouncedCallback } from '@mantine/hooks';
import { useNavigate, useParams, useSearch } from '@tanstack/react-router';

import { EmptyState } from '../../ui/EmptyState.tsx';
import { ErrorState } from '../../ui/ErrorState.tsx';
import { Page } from '../../ui/Page.tsx';
import { PageHeader } from '../../ui/PageHeader.tsx';
import { Toolbar } from '../../ui/Toolbar.tsx';
import { DataTable } from '../../ui/table/index.ts';
import { useGroups } from './api.ts';
import { groupColumns } from './groupColumns.tsx';
import type { GroupsSearch } from './search.ts';
import { GROUP_STATES, GROUP_TYPES, typeWords } from './words.ts';

/**
 * The consumer groups of a Kafka cluster: type, state, members and lag, read a page at a time by cursor.
 * The filter lives in the address. A lag that is an estimate or could not be read says so beside the figure.
 */
export function GroupsView() {
  const { clusterId } = useParams({ strict: false }) as { clusterId: string };
  const search = useSearch({ strict: false }) as GroupsSearch;
  const navigate = useNavigate();
  const [q, setQ] = useState(search.q ?? '');
  const groups = useGroups(clusterId, search);
  const columns = useMemo(() => groupColumns(clusterId), [clusterId]);

  const write = (patch: GroupsSearch) =>
    void navigate({ to: '.', search: (prev: Record<string, unknown>) => ({ ...prev, ...patch }) });
  const writeQ = useDebouncedCallback((value: string) => write({ q: value || undefined }), 250);
  const filtered = Boolean(search.q || search.type || search.state);
  const clear = () => {
    writeQ.cancel();
    setQ('');
    write({ q: undefined, type: undefined, state: undefined });
  };

  const rows = groups.data?.pages.flatMap((p) => p.items) ?? [];
  return (
    <Page fill>
      <PageHeader
        title="Consumer groups"
        description="Every group of the cluster with its state, members and lag. Open one to see its partitions and members, or to reset its offsets."
      />
      <Toolbar
        label="Group filters"
        start={
          <>
            <TextInput
              label="Filter groups"
              placeholder="Group name"
              size="xs"
              w="17.5rem"
              value={q}
              onChange={(e) => {
                setQ(e.currentTarget.value);
                writeQ(e.currentTarget.value);
              }}
            />
            <Select
              label="Type"
              size="xs"
              w="9rem"
              clearable
              data={GROUP_TYPES.map((t) => ({ value: t, label: typeWords(t) }))}
              value={search.type ?? null}
              onChange={(v) => write({ type: v ?? undefined })}
            />
            <Select
              label="State"
              size="xs"
              w="9rem"
              clearable
              data={GROUP_STATES.map((s) => ({ value: s, label: typeWords(s) }))}
              value={search.state ?? null}
              onChange={(v) => write({ state: v ?? undefined })}
            />
          </>
        }
        end={
          groups.hasNextPage ? (
            <Button
              size="xs"
              variant="default"
              loading={groups.isFetchingNextPage}
              onClick={() => void groups.fetchNextPage()}
            >
              Load more groups
            </Button>
          ) : (
            <Text size="xs" c="dimmed">
              {rows.length === 1 ? '1 group' : `${rows.length} groups`}
            </Text>
          )
        }
      />
      <DataTable
        label="Consumer groups"
        storageKey="kafka.groups"
        height="fill"
        columns={columns}
        data={rows}
        rowKey={(g) => g.id}
        loading={groups.isPending}
        error={groups.isError ? <ErrorState error={groups.error} onRetry={() => void groups.refetch()} /> : undefined}
        empty={
          filtered ? (
            <EmptyState
              kind="filtered"
              title="No group matches this filter"
              description="Groups may still exist; none of them match the filter."
              onClearFilters={clear}
            />
          ) : (
            <EmptyState
              kind="empty"
              title="No consumer groups yet"
              description="A group is a set of consumers that share the partitions of the topics they read, and remember how far they got. One appears here once a consumer with a group id has committed an offset or joined."
            />
          )
        }
      />
    </Page>
  );
}
