import { nameOf } from '../../kernel/api/resourceId.ts';
import { useEffect, useMemo, useState, useRef } from 'react';
import { plural } from './plural.ts';
import { TextInput } from '@mantine/core';
import { useNavigate, useParams, useSearch } from '@tanstack/react-router';
import { useDebouncedValue } from '@mantine/hooks';

import {
  useAddresses,
  useConnections,
  useConsumers,
  useProducers,
  useSessions,
  type DestinationView,
  type ConnectionView,
  type ConsumerView,
  type ProducerView,
  type ChannelView,
} from './api.ts';
import { type PagedView, type ResourceParams } from '../../kernel/api/paging.ts';
import { DataTable, type Column } from '../../ui/table/index.ts';
import { EmptyState } from '../../ui/EmptyState.tsx';
import { ErrorState } from '../../ui/ErrorState.tsx';
import { Page } from '../../ui/Page.tsx';
import { PageHeader } from '../../ui/PageHeader.tsx';
import { Pager } from '../../ui/Pager.tsx';
import { Toolbar } from '../../ui/Toolbar.tsx';
import { useCluster } from '../clusters/index.ts';
import { resourceColumns, type ColumnDeps } from './columns.tsx';
import { ResourceActions } from '../../kernel/actions/ResourceActions.tsx';
import type { ActionKind, ActionTargets } from '../../kernel/actions/types.ts';
import type { ApiError } from '../../kernel/api/request.ts';
import type { UseQueryResult } from '@tanstack/react-query';
import { useCan } from '../../kernel/auth/useCan.ts';
import { useFilterShortcut } from '../../kernel/keyboard/filterShortcut.ts';

const PAGE_SIZE = 200;

type Kind = 'addresses' | 'consumers' | 'sessions' | 'connections' | 'producers';

interface KindConfig<T> {
  hook: (id: string, p: ResourceParams) => UseQueryResult<PagedView<T>, ApiError>;
  columns: (deps: ColumnDeps) => Column<T>[];
  rowKey: (row: T) => string;
  filter: string;
  noun: string;
  /** What one is and how one comes to exist: what an empty view teaches. */
  about: string;
  /** What the view lists, for the page's description. */
  summary: string;
  /** The row's menu (ADR-0107): which resource it is, and how the row names itself. */
  menu: RowMenuConfig<T>;
}

/** What a grid row's action menu is about. */
interface RowMenuConfig<T> {
  kind: ActionKind;
  target: (row: T) => ActionTargets[ActionKind];
  label: (row: T) => string;
}

const CONFIG: {
  addresses: KindConfig<DestinationView>;
  consumers: KindConfig<ConsumerView>;
  sessions: KindConfig<ChannelView>;
  connections: KindConfig<ConnectionView>;
  producers: KindConfig<ProducerView>;
} = {
  addresses: {
    hook: useAddresses,
    columns: resourceColumns.addresses,
    rowKey: (r) => `${r.nodeId}:${r.name}`,
    filter: 'Address name',
    noun: 'address',
    summary: 'Every address on the cluster, with its queues and messages, read live from each node.',
    about:
      'An address is a named destination that producers send to; the broker routes each message to the queues bound to it. One appears when a queue is created or a producer first sends to it.',
    menu: {
      kind: 'address',
      target: (r) => ({ address: r.name, snapshot: r }),
      label: (r) => r.name,
    },
  },
  consumers: {
    hook: useConsumers,
    columns: resourceColumns.consumers,
    rowKey: (r) => `${r.nodeId}:${r.id}`,
    filter: 'Queue name or session id',
    noun: 'consumer',
    summary: 'Every consumer attached to a queue, read live from each node.',
    about:
      'A consumer is a client subscribed to a queue and receiving its messages. There are none while no client is subscribed; one appears as soon as a client subscribes.',
    menu: {
      kind: 'consumer',
      target: (r) => ({
        nodeId: r.nodeId,
        nodeName: r.nodeName,
        id: r.id,
        consumerId: nameOf(r.id),
        queueName: nameOf(r.subscription),
        sessionId: nameOf(r.channel),
        snapshot: r,
      }),
      label: (r) => `${nameOf(r.subscription) || 'consumer'} (${nameOf(r.id) || 'no id'})`,
    },
  },
  sessions: {
    hook: useSessions,
    columns: resourceColumns.sessions,
    rowKey: (r) => `${r.nodeId}:${r.id}`,
    filter: 'Session id, connection id or user',
    noun: 'session',
    summary: 'Every client session, read live from each node.',
    about:
      "A session is a client's unit of work on a connection; it owns that client's producers and consumers. There are none while no client is connected; one appears when a client opens a session.",
    menu: {
      kind: 'session',
      target: (r) => ({
        nodeId: r.nodeId,
        nodeName: r.nodeName,
        id: r.id,
        sessionId: nameOf(r.id),
        connectionId: nameOf(r.connection),
        snapshot: r,
      }),
      label: (r) => nameOf(r.id) || 'session',
    },
  },
  connections: {
    hook: useConnections,
    columns: resourceColumns.connections,
    rowKey: (r) => `${r.nodeId}:${r.id}`,
    filter: 'Remote address, client id or connection id',
    noun: 'connection',
    summary: 'Every client connection, read live from each node.',
    about:
      "A connection is a client's network link to a broker node. There are none while no client is connected; one appears when a client connects to a node's acceptor.",
    menu: {
      kind: 'connection',
      target: (r) => ({
        nodeId: r.nodeId,
        nodeName: r.nodeName,
        id: r.id,
        connectionId: nameOf(r.id),
        snapshot: r,
      }),
      label: (r) => r.clientId || r.remoteAddress || nameOf(r.id) || 'connection',
    },
  },
  producers: {
    hook: useProducers,
    columns: resourceColumns.producers,
    rowKey: (r) => `${r.nodeId}:${r.id}`,
    filter: 'Address, producer name or session id',
    noun: 'producer',
    summary: 'Every producer sending to an address, read live from each node.',
    about:
      "A producer is a client's sender to an address. There are none while no client holds one open; one appears when a client creates a producer on a session.",
    menu: {
      kind: 'producer',
      target: (r) => ({
        nodeId: r.nodeId,
        nodeName: r.nodeName,
        producerId: nameOf(r.id),
        address: nameOf(r.destination),
        sessionId: nameOf(r.channel),
        snapshot: r,
      }),
      label: (r) => r.clientId || nameOf(r.destination) || nameOf(r.id) || 'producer',
    },
  },
};

/** The nodes of the cluster whose last scrape failed: rows they would have held are absent, not zero. */
function useUnreachableNodes(clusterId: string): string[] {
  const cluster = useCluster(clusterId);
  return (cluster.data?.topology.nodes ?? [])
    .flatMap((n) => n.endpoints)
    .filter((e) => e.lastError)
    .map((e) => e.name);
}

/** Why the grid is empty: the filter, nodes that did not answer, or genuinely nothing right now. */
function ResourceEmpty({
  noun,
  about,
  filterText,
  unreachable,
  reach,
  onClearFilter,
}: Readonly<{
  noun: string;
  about: string;
  filterText: string;
  unreachable: string[];
  /** How the caller reaches this resource on the cluster; only addresses are owned by teams. */
  reach: 'grant' | 'teams' | 'none' | 'unknown';
  onClearFilter: () => void;
}>) {
  const nouns = plural(noun);
  if (filterText) {
    return (
      <EmptyState
        kind="filtered"
        title={`No ${noun} matches "${filterText}"`}
        description={`There may still be ${nouns} on this cluster; none of them match this filter.`}
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
        description={`There may be ${nouns} here that Rahdar cannot currently see. ${
          unreachable.length === 1 ? 'This node' : 'These nodes'
        } did not answer the last scrape, so this is an incomplete view rather than an empty cluster.`}
        nodes={unreachable}
      />
    );
  }
  if (reach === 'none') {
    return (
      <EmptyState
        kind="empty"
        title="You're not in any team on this cluster"
        description={`${nouns.charAt(0).toUpperCase()}${nouns.slice(1)} here belong to teams, and you are in none that owns ${nouns} on this cluster. Ask a platform administrator to add you to a team.`}
      />
    );
  }
  return (
    <EmptyState
      kind="empty"
      title={reach === 'teams' ? `No ${nouns} in your teams on this cluster` : `No ${nouns} right now`}
      description={`${about} This view is a live read across every serving node, one request per node per load.`}
    />
  );
}

/** The remaining five cross-node views, all from one column-spec-driven grid (ADR-0017). */
export function ResourceView({ kind }: Readonly<{ kind: Kind }>) {
  // `/` focuses this view's filter (ADR-0109).
  const filterRef = useRef<HTMLInputElement>(null);
  useFilterShortcut(filterRef);
  const { clusterId } = useParams({ strict: false }) as { clusterId: string };
  const search = useSearch({ strict: false }) as { q?: string; sort?: string; page?: number };
  const navigate = useNavigate();
  const config = CONFIG[kind] as unknown as KindConfig<{ nodeName: string }>;
  const title = kind.charAt(0).toUpperCase() + kind.slice(1);

  const [filter, setFilter] = useState(search.q ?? '');
  const [debounced] = useDebouncedValue(filter, 250);
  const page = search.page ?? 1;

  useEffect(() => {
    if ((search.q ?? '') === debounced) return;
    void navigate({
      to: '.',
      search: (prev: Record<string, unknown>) => ({
        ...prev,
        q: debounced || undefined,
        page: undefined,
      }),
    });
  }, [debounced, navigate, search.q]);

  const query = config.hook(clusterId, {
    q: search.q,
    sort: search.sort,
    page,
    size: PAGE_SIZE,
  });
  const unreachable = useUnreachableNodes(clusterId);
  const { reach } = useCan();

  const fetchedAt = query.dataUpdatedAt > 0 ? query.dataUpdatedAt : null;
  const columns = useMemo(() => config.columns({ clusterId, fetchedAt }), [config, clusterId, fetchedAt]);

  const setSort = (sort: string | undefined) =>
    navigate({
      to: '.',
      search: (prev: Record<string, unknown>) => ({ ...prev, sort, page: undefined }),
    });
  const setPage = (next: number) =>
    navigate({
      to: '.',
      search: (prev: Record<string, unknown>) => ({ ...prev, page: next > 1 ? next : undefined }),
    });

  const rows = query.data?.data ?? [];
  const total = query.data?.count ?? 0;

  return (
    <Page fill>
      <PageHeader title={title} description={config.summary} />
      <Toolbar
        label={`${title} filters`}
        start={
          <TextInput
            ref={filterRef}
            label={`Filter ${kind}`}
            placeholder={config.filter}
            value={filter}
            onChange={(e) => setFilter(e.currentTarget.value)}
            w="17.5rem"
            size="xs"
          />
        }
      />

      <DataTable
        label={title}
        storageKey={`resources.${kind}`}
        height="fill"
        columns={columns}
        data={rows}
        loading={query.isPending}
        error={query.isError ? <ErrorState error={query.error} onRetry={() => void query.refetch()} /> : undefined}
        sort={search.sort}
        onSortChange={setSort}
        rowKey={config.rowKey}
        toolbar={{
          // Not before the first page lands: "No addresses" while loading would claim there are none.
          end: query.data ? (
            <Pager page={page} pageSize={PAGE_SIZE} total={total} onChange={setPage} label={plural(config.noun)} />
          ) : undefined,
        }}
        rowMenu={{
          label: config.menu.label,
          render: (row, menu) => (
            <ResourceActions
              kind={config.menu.kind}
              clusterId={clusterId}
              target={config.menu.target(row)}
              restoreFocus={menu.restoreFocus}
            />
          ),
        }}
        empty={
          <ResourceEmpty
            noun={config.noun}
            about={config.about}
            filterText={search.q ?? ''}
            unreachable={unreachable}
            reach={kind === 'addresses' ? reach('destination:read', clusterId) : 'grant'}
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
