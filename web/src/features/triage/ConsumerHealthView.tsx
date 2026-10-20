import { useEffect, useMemo, useState, useRef } from 'react';
import { Text, TextInput } from '@mantine/core';
import { useDebouncedValue } from '@mantine/hooks';
import { useNavigate, useParams, useSearch } from '@tanstack/react-router';

import { useCluster } from '../clusters/index.ts';
import { useConsumerHealth, type ConsumerHealthView as HealthRow } from './api.ts';
import { consumerHealthColumns } from './columns.ts';
import { DataTable } from '../../ui/table/index.ts';
import { EmptyState } from '../../ui/EmptyState.tsx';
import { ErrorState } from '../../ui/ErrorState.tsx';
import { Page } from '../../ui/Page.tsx';
import { PageHeader } from '../../ui/PageHeader.tsx';
import { Pager } from '../../ui/Pager.tsx';
import { Toolbar } from '../../ui/Toolbar.tsx';
import { ResourceActions } from '../../kernel/actions/ResourceActions.tsx';
import { resourceName } from '../../kernel/api/resourceId.ts';
import { useFilterShortcut } from '../../kernel/keyboard/filterShortcut.ts';

const PAGE_SIZE = 200;

const rowKey = (r: HealthRow) => `${r.destination}::${r.subscription}`;

/**
 * How many rows on this page need attention, in words. There is always a sentence, so the line it
 * sits on keeps its height while the rows load and when there are none.
 */
function attentionWords(needing: number, rows: number, loading: boolean): string {
  if (loading) return 'Reading queue health…';
  if (needing > 0) return `${needing} of ${rows} on this page need attention`;
  return rows > 0 ? 'Nothing on this page needs attention' : 'No queues on this page';
}

/** Why the grid is empty: the filter, nodes that did not answer, or genuinely nothing yet. */
function HealthEmpty({
  filterText,
  unreachable,
  onClearFilter,
}: Readonly<{ filterText: string | undefined; unreachable: string[]; onClearFilter: () => void }>) {
  if (filterText) {
    return (
      <EmptyState
        kind="filtered"
        title={`No queue matches "${filterText}"`}
        description="Queues may still exist on this cluster; none of them match this filter."
        onClearFilters={onClearFilter}
      />
    );
  }
  if (unreachable.length > 0) {
    return (
      <EmptyState
        kind="unreachable"
        title={
          unreachable.length === 1
            ? `${unreachable[0]} could not be reached`
            : `${unreachable.length} nodes could not be reached`
        }
        description={`There may be queues here that Rahdar cannot currently see. ${
          unreachable.length === 1 ? 'This node' : 'These nodes'
        } did not answer the last scrape, so this is an incomplete view rather than a healthy cluster.`}
        nodes={unreachable}
      />
    );
  }
  return (
    <EmptyState
      kind="empty"
      title="No queues to report on yet"
      description="Consumer health ranks every queue by whether its consumers are keeping up: whether anything is attached, whether it is acknowledging, and whether the backlog is growing. Queues appear here within a scrape tick of being created, so create a queue or produce to an address and check back."
    />
  );
}

/**
 * Every queue in the cluster ranked by how badly it needs attention.
 *
 * This view exists for the question asked during an incident:
 * "which of my queues have consumer trouble", and no other screen answers
 * it — Metrics charts one queue at a time and Flow is bounded to the busiest
 * paths. Sorting, filtering and paging are URL-owned, so a triage view can be
 * pasted into a channel and reopened exactly as it was.
 */
export function ConsumerHealthView() {
  // `/` focuses this view's filter (ADR-0109).
  const filterRef = useRef<HTMLInputElement>(null);
  useFilterShortcut(filterRef);
  const { clusterId } = useParams({ strict: false }) as { clusterId: string };
  const search = useSearch({ strict: false }) as {
    q?: string;
    sort?: string;
    page?: number;
  };
  const navigate = useNavigate();

  const [filter, setFilter] = useState(search.q ?? '');
  const [debounced] = useDebouncedValue(filter, 250);
  const page = search.page ?? 1;

  useEffect(() => {
    if ((search.q ?? '') === debounced) return;
    void navigate({
      to: '.',
      search: (prev: Record<string, unknown>) => ({ ...prev, q: debounced || undefined, page: undefined }),
    });
  }, [debounced, navigate, search.q]);

  const query = useConsumerHealth(clusterId, {
    q: search.q,
    sort: search.sort,
    page,
    size: PAGE_SIZE,
  });

  // An empty grid has three different causes, and presenting an absence as a fact
  // is the one that misleads: a node Rahdar could not reach contributes no rows,
  // which looks exactly like a cluster whose queues are all healthy.
  const cluster = useCluster(clusterId);
  const unreachable = (cluster.data?.topology.nodes ?? [])
    .flatMap((n) => n.endpoints)
    .filter((e) => e.lastError)
    .map((e) => e.name);

  const setSort = (sort: string | undefined) =>
    navigate({ to: '.', search: (prev: Record<string, unknown>) => ({ ...prev, sort, page: undefined }) });
  const setPage = (next: number) =>
    navigate({ to: '.', search: (prev: Record<string, unknown>) => ({ ...prev, page: next > 1 ? next : undefined }) });

  const columns = useMemo(consumerHealthColumns, []);
  const rows = query.data?.data ?? [];
  const total = query.data?.count ?? 0;
  const needingAttention = rows.filter((r) => r.severity >= 2).length;
  const unmeasured = rows.filter((r) => r.verdict === 'INSUFFICIENT_DATA').length;

  return (
    <Page fill>
      <PageHeader
        title="Consumer health"
        description="Every queue ranked by whether its consumers are keeping up: whether anything is attached, whether it is acknowledging, and whether the backlog is growing."
      />
      <Toolbar
        label="Queue filters"
        start={
          <TextInput
            ref={filterRef}
            label="Filter queues"
            placeholder="Queue or address name"
            value={filter}
            onChange={(e) => setFilter(e.currentTarget.value)}
            w="17.5rem"
            size="xs"
          />
        }
        end={
          // Stated in words.
          <Text size="xs" c="dimmed" role="status">
            {attentionWords(needingAttention, rows.length, query.isPending)}
            {unmeasured > 0 ? ` · ${unmeasured} not yet measured` : ''}
          </Text>
        }
      />

      <DataTable
        label="Consumer health"
        storageKey="consumer-health"
        height="fill"
        columns={columns}
        data={rows}
        loading={query.isPending}
        error={query.isError ? <ErrorState error={query.error} onRetry={() => void query.refetch()} /> : undefined}
        sort={search.sort}
        onSortChange={setSort}
        rowKey={rowKey}
        toolbar={{
          // The count and position live in the pager, stated once.
          end: <Pager page={page} pageSize={PAGE_SIZE} total={total} onChange={setPage} label="queues" />,
        }}
        rowMenu={{
          label: (r) => resourceName(r.subscription),
          render: (r, menu) => (
            <ResourceActions
              kind="queue"
              clusterId={clusterId}
              target={{ queueName: resourceName(r.subscription), address: resourceName(r.destination) }}
              restoreFocus={menu.restoreFocus}
            />
          ),
        }}
        empty={
          <HealthEmpty
            filterText={search.q}
            unreachable={unreachable}
            onClearFilter={() => {
              setFilter('');
              void navigate({
                to: '.',
                search: (prev: Record<string, unknown>) => ({ ...prev, q: undefined, page: undefined }),
              });
            }}
          />
        }
      />
    </Page>
  );
}
