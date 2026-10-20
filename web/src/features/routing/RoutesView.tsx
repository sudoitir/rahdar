import { useEffect, useMemo, useRef, useState } from 'react';
import { Tabs, TextInput } from '@mantine/core';
import { useNavigate, useParams, useSearch } from '@tanstack/react-router';
import { useDebouncedValue } from '@mantine/hooks';

import { useRoutes, type RouteView } from './api.ts';
import type { RoutesSearch } from './feature.ts';
import { useSlot } from '../../kernel/slots.ts';
import { DataTable } from '../../ui/table/index.ts';
import { EmptyState } from '../../ui/EmptyState.tsx';
import { ErrorState } from '../../ui/ErrorState.tsx';
import { Page } from '../../ui/Page.tsx';
import { PageHeader } from '../../ui/PageHeader.tsx';
import { Pager } from '../../ui/Pager.tsx';
import { Toolbar } from '../../ui/Toolbar.tsx';
import { useCluster } from '../clusters/index.ts';
import { routeColumns } from './columns.ts';
import { CreateDivertAction } from './DivertActions.tsx';
import { ResourceActions } from '../../kernel/actions/ResourceActions.tsx';
import { useClusterSystem } from '../../kernel/shell/useClusterSystem.ts';
import { useFilterShortcut } from '../../kernel/keyboard/filterShortcut.ts';

const PAGE_SIZE = 200;

const NO_ROWS: never[] = [];

const routeKey = (r: RouteView) => r.id;

/** What an empty list teaches: what a route is, and where one comes from on this cluster. */
function routesIntro(hasBuilder: boolean, artemis: boolean): string {
  const what =
    'A route copies, takes or carries the messages of one destination or subscription to another, without the producers knowing.';
  if (!artemis) return what;
  return hasBuilder
    ? `${what} Create a divert above, or declare a bridge on the Builder tab and apply it with the rest of the declaration; the plan names the hazard, because a bridge rewires how this cluster reaches other brokers.`
    : `${what} Create a divert above; a bridge is declared in the cluster configuration, which is not enabled on this Rahdar.`;
}

/** Why the list has no rows: the filter, nodes that did not answer, or genuinely nothing yet. */
function RoutesEmpty({
  intro,
  filtered,
  unreachable,
  onClearFilters,
}: Readonly<{ intro: string; filtered: boolean; unreachable: string[]; onClearFilters: () => void }>) {
  if (filtered) {
    return (
      <EmptyState
        kind="filtered"
        title="No route matches this filter"
        description="Clear it to see every route on the cluster."
        onClearFilters={onClearFilters}
      />
    );
  }
  if (unreachable.length > 0) {
    return <EmptyState kind="unreachable" title="No routes could be listed" nodes={unreachable} />;
  }
  return <EmptyState kind="empty" title="No routes yet" description={intro} />;
}

/**
 * A cluster's routes: what copies or takes its traffic, what carries it to another broker, and
 * the tabs other features contribute through `routes.tabs` — the routing builder among them.
 *
 * <p>The routes are a live read across every serving node, merged. Which tab is open, the filter and
 * the page all live in the URL, so a routes view can be shared as it was seen.
 */
export function RoutesView() {
  const { clusterId } = useParams({ strict: false }) as { clusterId: string };
  const search = useSearch({ strict: false }) as RoutesSearch;
  const navigate = useNavigate();
  const systemId = useClusterSystem(clusterId);
  // A tab that exists for one broker system (the builder) is listed only on a cluster that runs it.
  const contributed = useSlot('routes.tabs').filter((c) => !c.system || c.system === systemId);
  const slot = contributed.find((c) => c.id === search.tab);
  const tab: string = slot?.id ?? 'routes';

  // Switching tab drops whatever the previous tab kept in the URL, so a filter or an open editor
  // never follows the operator onto a view it does not belong to.
  const setTab = (next: string | null) =>
    navigate({
      to: '.',
      search: () => ({ tab: next && next !== 'routes' ? next : undefined }),
    });

  return (
    // A contributed tab (the builder) sizes itself; the listing fills the window.
    <Page fill={!slot}>
      <PageHeader
        title="Routes"
        description="What copies or takes this cluster's traffic, and what carries it to another broker."
      />
      <Tabs value={tab} onChange={setTab}>
        <Tabs.List>
          <Tabs.Tab value="routes">Routes</Tabs.Tab>
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
        // By tab id, not by import: the builder belongs to brokerconfig, and naming its tab adds
        // no dependency edge between the two.
        <RoutesListing clusterId={clusterId} hasBuilder={contributed.some((c) => c.id === 'builder')} />
      )}
    </Page>
  );
}

/** The Routes tab: one live, filtered, paged listing. */
function RoutesListing({ clusterId, hasBuilder }: Readonly<{ clusterId: string; hasBuilder: boolean }>) {
  // `/` focuses this view's filter (ADR-0109).
  const filterRef = useRef<HTMLInputElement>(null);
  useFilterShortcut(filterRef);
  const search = useSearch({ strict: false }) as RoutesSearch;
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

  const query = useRoutes(clusterId, { q: search.q, sort: search.sort, page, size: PAGE_SIZE });
  const columns = useMemo(() => routeColumns(clusterId), [clusterId]);

  const setSearch = (patch: Record<string, unknown>) =>
    navigate({ to: '.', search: (prev: Record<string, unknown>) => ({ ...prev, ...patch }) });

  // A node Rahdar could not reach contributes no rows, which looks exactly like a cluster with none.
  const cluster = useCluster(clusterId);
  const unreachable = (cluster.data?.topology.nodes ?? [])
    .flatMap((n) => n.endpoints)
    .filter((e) => e.lastError)
    .map((e) => e.name);
  // Diverts are Artemis's own, so creating one is offered only on a cluster that runs it.
  const artemis = cluster.data?.system.id === 'artemis';

  const total = query.data?.count ?? 0;

  return (
    <>
      <Toolbar
        label="Route filters"
        start={
          <TextInput
            ref={filterRef}
            label="Filter by destination or name"
            placeholder="Destination or route name"
            value={filter}
            onChange={(e) => setFilter(e.currentTarget.value)}
            w="17.5rem"
            size="xs"
          />
        }
        end={artemis ? <CreateDivertAction clusterId={clusterId} /> : undefined}
      />

      <DataTable
        label="Routes"
        storageKey="routing.routes"
        height="fill"
        columns={columns}
        data={query.data?.data ?? NO_ROWS}
        sort={search.sort}
        onSortChange={(sort) => setSearch({ sort, page: undefined })}
        rowKey={routeKey}
        loading={query.isPending}
        error={query.isError ? <ErrorState error={query.error} onRetry={() => void query.refetch()} /> : undefined}
        toolbar={{
          // Not before the first page lands: "No routes" while loading would claim there are none.
          end: query.data ? (
            <Pager
              page={page}
              pageSize={PAGE_SIZE}
              total={total}
              onChange={(next) => setSearch({ page: next > 1 ? next : undefined })}
              label="routes"
            />
          ) : undefined,
        }}
        rowMenu={{
          label: (r) => r.name,
          render: (r, menu) => (
            <ResourceActions
              kind="route"
              clusterId={clusterId}
              target={{ id: r.id, kind: r.kind, name: r.name, snapshot: r }}
              restoreFocus={menu.restoreFocus}
            />
          ),
        }}
        empty={
          <RoutesEmpty
            intro={routesIntro(hasBuilder, artemis)}
            filtered={Boolean(search.q)}
            unreachable={unreachable}
            // The address follows the field once typing pauses, so clearing the field clears the filter.
            onClearFilters={() => setFilter('')}
          />
        }
      />
    </>
  );
}
