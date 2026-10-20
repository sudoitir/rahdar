import { useEffect, useMemo, useRef, useState, type ReactNode } from 'react';
import { Tabs, TextInput } from '@mantine/core';
import { useNavigate, useParams, useSearch } from '@tanstack/react-router';
import { useDebouncedValue } from '@mantine/hooks';

import { useBridges, useDiverts, type BridgeRow, type DivertRow } from './api.ts';
import type { RoutingSearch } from './feature.ts';
import { useSlot } from '../../kernel/slots.ts';
import { DataTable } from '../../ui/table/index.ts';
import { EmptyState } from '../../ui/EmptyState.tsx';
import { ErrorState } from '../../ui/ErrorState.tsx';
import { Page } from '../../ui/Page.tsx';
import { PageHeader } from '../../ui/PageHeader.tsx';
import { Pager } from '../../ui/Pager.tsx';
import { Toolbar } from '../../ui/Toolbar.tsx';
import { useCluster } from '../clusters/index.ts';
import { bridgeColumns, divertColumns } from './columns.ts';
import { CreateDivertAction } from './DivertActions.tsx';
import { ResourceActions } from '../../kernel/actions/ResourceActions.tsx';
import { useFilterShortcut } from '../../kernel/keyboard/filterShortcut.ts';

const PAGE_SIZE = 200;

const NO_ROWS: never[] = [];

const divertKey = (r: DivertRow) => `${r.name}:${r.address}:${r.forwardingAddress}`;
const bridgeKey = (r: BridgeRow) => r.name;

type Tab = 'diverts' | 'bridges';

/** What an empty bridge list teaches: where a bridge comes from, which depends on the cluster configuration. */
function bridgesIntro(hasBuilder: boolean): string {
  return hasBuilder
    ? 'A bridge forwards a queue to an address on another broker. Declare one on the Builder tab and apply it with the rest of the declaration; the plan names the hazard, because a bridge rewires how this cluster reaches other brokers.'
    : 'A bridge forwards a queue to an address on another broker, and is declared in the cluster configuration — which is not enabled on this Rahdar.';
}

const DIVERTS_INTRO =
  'A divert copies — or, when exclusive, redirects — the messages arriving at one address to another, without the producers knowing. Use Create divert above to add one.';

/** Why a list has no rows: the filter, nodes that did not answer, or genuinely nothing yet. */
function ListingEmpty({
  noun,
  intro,
  filtered,
  unreachable,
  onClearFilters,
}: Readonly<{
  noun: 'divert' | 'bridge';
  intro: string;
  filtered: boolean;
  unreachable: string[];
  onClearFilters: () => void;
}>) {
  if (filtered) {
    return (
      <EmptyState
        kind="filtered"
        title={`No ${noun} matches this filter`}
        description={`Clear it to see every ${noun} on the cluster.`}
        onClearFilters={onClearFilters}
      />
    );
  }
  if (unreachable.length > 0) {
    return <EmptyState kind="unreachable" title={`No ${noun}s could be listed`} nodes={unreachable} />;
  }
  return <EmptyState kind="empty" title={`No ${noun}s yet`} description={intro} />;
}

/** What the two tables share: where their rows come from and how they fail. */
interface ListingProps<T> {
  clusterId: string;
  rows: T[];
  q: string | undefined;
  sort: string | undefined;
  unreachable: string[];
  loading: boolean;
  error: ReactNode;
  /** The page position, once the first page has landed. */
  pager: ReactNode;
  onSort: (sort: string | undefined) => void;
  onClearFilters: () => void;
}

function DivertsTable({
  clusterId,
  rows,
  q,
  sort,
  unreachable,
  loading,
  error,
  pager,
  onSort,
  onClearFilters,
}: Readonly<ListingProps<DivertRow>>) {
  const columns = useMemo(() => divertColumns(clusterId), [clusterId]);
  return (
    <DataTable
      label="Diverts"
      storageKey="routing.diverts"
      height="fill"
      columns={columns}
      data={rows}
      sort={sort}
      onSortChange={onSort}
      rowKey={divertKey}
      loading={loading}
      error={error}
      toolbar={{ end: pager }}
      rowMenu={{
        label: (r) => r.name,
        render: (r, menu) => (
          <ResourceActions
            kind="divert"
            clusterId={clusterId}
            target={{ name: r.name, snapshot: r }}
            restoreFocus={menu.restoreFocus}
          />
        ),
      }}
      empty={
        <ListingEmpty
          noun="divert"
          intro={DIVERTS_INTRO}
          filtered={Boolean(q)}
          unreachable={unreachable}
          onClearFilters={onClearFilters}
        />
      }
    />
  );
}

function BridgesTable({
  rows,
  q,
  sort,
  unreachable,
  loading,
  error,
  pager,
  onSort,
  onClearFilters,
  hasBuilder,
}: Readonly<Omit<ListingProps<BridgeRow>, 'clusterId'> & { hasBuilder: boolean }>) {
  const columns = useMemo(() => bridgeColumns(), []);
  return (
    <DataTable
      label="Bridges"
      storageKey="routing.bridges"
      height="fill"
      columns={columns}
      data={rows}
      sort={sort}
      onSortChange={onSort}
      rowKey={bridgeKey}
      loading={loading}
      error={error}
      toolbar={{ end: pager }}
      empty={
        <ListingEmpty
          noun="bridge"
          intro={bridgesIntro(hasBuilder)}
          filtered={Boolean(q)}
          unreachable={unreachable}
          onClearFilters={onClearFilters}
        />
      }
    />
  );
}

/**
 * A cluster's routing: what copies or takes its traffic, what carries it to another broker, and
 * the tabs other features contribute through `routing.tabs` — the routing builder among them.
 *
 * <p>Diverts and Bridges are live reads across every serving node, merged. Which tab is open,
 * the filter and the page all live in the URL, so a routing view can be shared as it was seen.
 */
export function RoutingView() {
  const { clusterId } = useParams({ strict: false }) as { clusterId: string };
  const search = useSearch({ strict: false }) as RoutingSearch;
  const navigate = useNavigate();
  const contributed = useSlot('routing.tabs');
  const slot = contributed.find((c) => c.id === search.tab);
  const tab: string = slot?.id ?? (search.tab === 'bridges' ? 'bridges' : 'diverts');

  // Switching tab drops whatever the previous tab kept in the URL, so a filter or an open editor
  // never follows the operator onto a view it does not belong to.
  const setTab = (next: string | null) =>
    navigate({
      to: '.',
      search: () => ({ tab: next && next !== 'diverts' ? next : undefined }),
    });

  return (
    // A contributed tab (the builder) sizes itself; the listings fill the window.
    <Page fill={!slot}>
      <PageHeader
        title="Routing"
        description="What copies or takes this cluster's traffic, and what carries it to another broker."
      />
      <Tabs value={tab} onChange={setTab}>
        <Tabs.List>
          <Tabs.Tab value="diverts">Diverts</Tabs.Tab>
          <Tabs.Tab value="bridges">Bridges</Tabs.Tab>
          {contributed.map(({ id, title }) => (
            <Tabs.Tab key={id} value={id}>
              {title}
            </Tabs.Tab>
          ))}
        </Tabs.List>
      </Tabs>

      {slot ? (
        <slot.Component clusterId={clusterId} />
      ) : (
        <RoutingListing
          clusterId={clusterId}
          tab={tab === 'bridges' ? 'bridges' : 'diverts'}
          // By tab id, not by import: the builder belongs to brokerconfig, and naming its tab adds
          // no dependency edge between the two.
          hasBuilder={contributed.some((c) => c.id === 'builder')}
        />
      )}
    </Page>
  );
}

/** The Diverts or the Bridges tab: one live, filtered, paged listing. */
function RoutingListing({
  clusterId,
  tab,
  hasBuilder,
}: Readonly<{ clusterId: string; tab: Tab; hasBuilder: boolean }>) {
  // `/` focuses this view's filter (ADR-0109).
  const filterRef = useRef<HTMLInputElement>(null);
  useFilterShortcut(filterRef);
  const search = useSearch({ strict: false }) as RoutingSearch;
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

  const params = { q: search.q, sort: search.sort, page, size: PAGE_SIZE };
  const diverts = useDiverts(clusterId, tab === 'diverts' ? params : { size: 1 });
  const bridges = useBridges(clusterId, tab === 'bridges' ? params : { size: 1 });
  const query = tab === 'diverts' ? diverts : bridges;

  const setSearch = (patch: Record<string, unknown>) =>
    navigate({ to: '.', search: (prev: Record<string, unknown>) => ({ ...prev, ...patch }) });

  // A node Rahdar could not reach contributes no rows, which looks exactly like a cluster with none.
  const cluster = useCluster(clusterId);
  const unreachable = (cluster.data?.topology.nodes ?? [])
    .flatMap((n) => n.endpoints)
    .filter((e) => e.lastError)
    .map((e) => e.name);

  // The address follows the field once typing pauses, so clearing the field clears the filter.
  const clearFilters = () => setFilter('');
  const total = query.data?.count ?? 0;
  const listing = {
    q: search.q,
    sort: search.sort,
    unreachable,
    loading: query.isPending,
    error: query.isError ? <ErrorState error={query.error} onRetry={() => void query.refetch()} /> : undefined,
    // Not before the first page lands: "No diverts" while loading would claim there are none.
    pager: query.data ? (
      <Pager
        page={page}
        pageSize={PAGE_SIZE}
        total={total}
        onChange={(next) => setSearch({ page: next > 1 ? next : undefined })}
        label={tab}
      />
    ) : undefined,
    onSort: (sort: string | undefined) => setSearch({ sort, page: undefined }),
    onClearFilters: clearFilters,
  };

  return (
    <>
      <Toolbar
        label="Routing filters"
        start={
          <TextInput
            ref={filterRef}
            label="Filter by address or name"
            placeholder="Address or divert/bridge name"
            value={filter}
            onChange={(e) => setFilter(e.currentTarget.value)}
            w="17.5rem"
            size="xs"
          />
        }
        end={tab === 'diverts' ? <CreateDivertAction clusterId={clusterId} /> : undefined}
      />

      {tab === 'diverts' ? (
        <DivertsTable clusterId={clusterId} rows={diverts.data?.data ?? NO_ROWS} {...listing} />
      ) : (
        <BridgesTable rows={bridges.data?.data ?? NO_ROWS} hasBuilder={hasBuilder} {...listing} />
      )}
    </>
  );
}
