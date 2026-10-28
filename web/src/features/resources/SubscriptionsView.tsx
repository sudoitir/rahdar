import { useEffect, useMemo, useState, useRef } from 'react';
import { Button, Group, Text, TextInput } from '@mantine/core';
import { useNavigate, useParams, useSearch } from '@tanstack/react-router';
import { useDebouncedCallback } from '@mantine/hooks';

import { useCluster } from '../clusters/index.ts';
import { destinationOf, routingTypeOf, useSubscription, useSubscriptions, type SubscriptionView } from './api.ts';
import { DataTable } from '../../ui/table/index.ts';
import { EmptyState } from '../../ui/EmptyState.tsx';
import { ErrorState } from '../../ui/ErrorState.tsx';
import { Page } from '../../ui/Page.tsx';
import { PageHeader } from '../../ui/PageHeader.tsx';
import { Pager } from '../../ui/Pager.tsx';
import { Toolbar } from '../../ui/Toolbar.tsx';
import { BacklogDrawer, type BacklogTarget } from '../../kernel/backlog/BacklogDrawer.tsx';
import { SubscriptionDetailDrawer } from './SubscriptionDetailDrawer.tsx';
import { CreateSubscriptionForm } from './CreateSubscriptionForm.tsx';
import { useCan } from '../../kernel/auth/useCan.ts';
import { useSlot, type SubscriptionSelection } from '../../kernel/slots.ts';
import { ResourceActions } from '../../kernel/actions/ResourceActions.tsx';
import { useTitlePart } from '../../kernel/shell/pageTitle.ts';
import { CapabilityGate } from '../../ui/CapabilityGate.tsx';
import { gateFor } from '../../ui/capabilityGate.ts';
import { useFilterShortcut } from '../../kernel/keyboard/filterShortcut.ts';
import { subscriptionColumns } from './subscriptionColumns.ts';

const PAGE_SIZE = 200;

const rowKey = (r: SubscriptionView) => `${destinationOf(r)}::${r.name}::${routingTypeOf(r)}`;

/** What the selection says, in words. */
function selectionWords(count: number, total: number, matching: string, allMatching: boolean): string {
  if (count === 0) return 'No subscriptions selected. Select subscriptions in the grid to act on them together.';
  if (allMatching) return `All ${total.toLocaleString()} subscriptions${matching} are selected.`;
  return `${count.toLocaleString()} ${count === 1 ? 'subscription' : 'subscriptions'} selected`;
}

/** How many subscriptions are picked, and the actions that apply to them. */
function SelectionBar({
  count,
  total,
  matching,
  allMatching,
  canSelectAll,
  onSelectAll,
  onClear,
  children,
}: Readonly<{
  count: number;
  total: number;
  matching: string;
  allMatching: boolean;
  canSelectAll: boolean;
  onSelectAll: () => void;
  onClear: () => void;
  children: React.ReactNode;
}>) {
  return (
    <Group gap="sm" justify="space-between" role="region" aria-label="Selected subscriptions">
      <Group gap="xs">
        <Text size="sm" fw={count > 0 ? 600 : undefined}>
          {selectionWords(count, total, matching, allMatching)}
        </Text>
        {canSelectAll ? (
          <Button size="xs" variant="subtle" onClick={onSelectAll}>
            {`Select all ${total.toLocaleString()} subscriptions${matching}`}
          </Button>
        ) : null}
        {count > 0 ? (
          <Button size="xs" variant="subtle" onClick={onClear}>
            Clear selection
          </Button>
        ) : null}
      </Group>
      <Group gap="xs">{children}</Group>
    </Group>
  );
}

/** Why the grid is empty: the filter, nodes that did not answer, or genuinely nothing yet. */
function SubscriptionsEmpty({
  filterText,
  unreachable,
  reach,
  mayCreate,
  onClearFilter,
  onCreate,
}: Readonly<{
  filterText: string;
  unreachable: string[];
  /** How the caller reaches subscriptions on this cluster; a team member sees only theirs, and may see none. */
  reach: 'grant' | 'teams' | 'none' | 'unknown';
  mayCreate: boolean;
  onClearFilter: () => void;
  onCreate: () => void;
}>) {
  if (filterText) {
    return (
      <EmptyState
        kind="filtered"
        title={`No subscription matches "${filterText}"`}
        description="Subscriptions may still exist on this cluster; none of them match this filter."
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
        description={`There may be subscriptions here that Rahdar cannot currently see. ${
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
        description="Subscriptions here belong to teams, and you are in none that owns subscriptions on this cluster. Ask a platform administrator to add you to a team."
      />
    );
  }
  return (
    <EmptyState
      kind="empty"
      title={reach === 'teams' ? 'No subscriptions in your teams on this cluster' : 'No subscriptions yet'}
      description={
        <>
          A subscription is where messages wait for a consumer. Rahdar fills this grid from each broker's{' '}
          <code>listSubscriptions</code>, so a subscription appears here within a scrape tick of being created or of the
          first message produced to its destination.
        </>
      }
      action={
        mayCreate ? (
          <Button size="xs" variant="light" onClick={onCreate}>
            Create the first subscription
          </Button>
        ) : undefined
      }
    />
  );
}

/**
 * Selection is local and belongs to the filter it was made under: a new filter is a new set of
 * subscriptions, and carrying picks across it would act on subscriptions the operator can no longer see.
 * Picks are keyed by row, holding the subscription name the bulk actions need.
 */
function useSubscriptionSelection(rows: SubscriptionView[], total: number, q: string | undefined) {
  const [picked, setPicked] = useState<Map<string, string>>(new Map());
  const [allMatching, setAllMatching] = useState(false);
  const clearSelection = () => {
    setPicked(new Map());
    setAllMatching(false);
  };
  useEffect(() => {
    setPicked(new Map());
    setAllMatching(false);
  }, [q]);
  // "All matching" is a filter, not a list of names: the subscriptions on the other pages were never loaded.
  // Changing any one row turns it back into names, starting from this page.
  const pageNames = () => new Map(rows.map((r) => [rowKey(r), r.name]));
  const toggleRow = (key: string) => {
    const next = allMatching ? pageNames() : new Map(picked);
    setAllMatching(false);
    if (next.has(key)) next.delete(key);
    else next.set(key, rows.find((r) => rowKey(r) === key)?.name ?? key);
    setPicked(next);
  };
  const toggleAll = (keys: string[], allSelected: boolean) => {
    const next = allMatching ? pageNames() : new Map(picked);
    setAllMatching(false);
    if (allSelected) keys.forEach((k) => next.delete(k));
    else rows.forEach((r) => next.set(rowKey(r), r.name));
    setPicked(next);
  };
  const selectedKeys: ReadonlySet<string> = allMatching ? new Set(rows.map(rowKey)) : new Set(picked.keys());
  const count = allMatching ? total : picked.size;
  const pageAllPicked = rows.length > 0 && rows.every((r) => picked.has(rowKey(r)));
  const matching = q ? ` matching "${q}"` : ' on this cluster';
  const selection: SubscriptionSelection = allMatching
    ? { kind: 'filter', q: q ?? '', total }
    : { kind: 'names', names: [...picked.values()] };

  return {
    picked,
    allMatching,
    setAllMatching,
    clearSelection,
    toggleRow,
    toggleAll,
    selectedKeys,
    count,
    pageAllPicked,
    matching,
    selection,
  };
}

/**
 * The headline view: every subscription across every node, in one virtualized grid.
 * Sort, filter and page are URL-owned (non-negotiable #9); the grid is
 * server-driven through `useSubscriptions`.
 */
export function SubscriptionsView() {
  // `/` focuses this view's filter (ADR-0109).
  const filterRef = useRef<HTMLInputElement>(null);
  useFilterShortcut(filterRef);
  const { clusterId } = useParams({ strict: false }) as { clusterId: string };
  const search = useSearch({ strict: false }) as { q?: string; sort?: string; page?: number; subscription?: string };
  const navigate = useNavigate();

  const [filter, setFilter] = useState(search.q ?? '');
  const page = search.page ?? 1;

  const writeFilter = useDebouncedCallback(
    (q: string) =>
      void navigate({
        to: '.',
        search: (prev: Record<string, unknown>) => ({ ...prev, q: q || undefined, page: undefined }),
      }),
    250,
  );
  const typeFilter = (q: string) => {
    setFilter(q);
    writeFilter(q);
  };
  // Cancelling first: a write still waiting on the debounce would put the old filter back in the destination.
  const clearFilter = () => {
    writeFilter.cancel();
    setFilter('');
    void navigate({
      to: '.',
      search: (prev: Record<string, unknown>) => ({ ...prev, q: undefined, page: undefined }),
    });
  };

  const query = useSubscriptions(clusterId, {
    q: search.q,
    sort: search.sort,
    page,
    size: PAGE_SIZE,
  });

  // The open subscription is an destination, not a copy: held as its id and looked up in the
  // rows each render, so a pause, an edit or a delete that refetches the listing is
  // reflected in the panel instead of leaving it showing the subscription as it was when
  // it was opened (non-negotiable #9).
  //
  // A shared or palette link can name a subscription that is not on the loaded page; it is looked up by
  // id then, rather than the link silently opening nothing.
  const onPage = (query.data?.data ?? []).find((q) => q.id === search.subscription);
  const offPage = useSubscription(clusterId, onPage ? undefined : search.subscription);
  const selected = onPage ?? offPage.subscription ?? null;
  // Neither the page nor an exact lookup has it: gone, or no longer visible to the caller. Not while still asking.
  const missing =
    !selected && search.subscription && !offPage.isPending && !offPage.isError ? search.subscription : undefined;
  useTitlePart('resource', selected?.name);
  const setSelected = (subscription: SubscriptionView | null) =>
    navigate({ to: '.', search: (prev: Record<string, unknown>) => ({ ...prev, subscription: subscription?.id }) });
  const [createOpen, setCreateOpen] = useState(false);
  const { can, reach, createPatterns, loading: grantsLoading } = useCan();
  // Through a team or a share the caller may create under their patterns; only with neither is the control not theirs.
  const mayCreate =
    can('subscription:create', clusterId) || (createPatterns(clusterId, 'subscription')?.length ?? 0) > 0;

  // An empty grid has three quite different causes, and presenting an absence as
  // a fact is the one that misleads: a node Rahdar could not reach contributes no
  // rows, which looks exactly like a cluster with no subscriptions.
  const cluster = useCluster(clusterId);
  // Offered while grants load, and never hidden: an operator without the grant sees why (non-negotiable #5).
  const createGate = gateFor(
    mayCreate,
    'Create subscriptions and destinations',
    cluster.data?.capabilities.managementWrite,
    grantsLoading || cluster.isPending,
  );
  const unreachable = (cluster.data?.topology.nodes ?? [])
    .flatMap((n) => n.endpoints)
    .filter((e) => e.lastError)
    .map((e) => e.name);

  const selectionSlot = useSlot('subscriptions.selection');

  const setSort = (sort: string | undefined) =>
    navigate({ to: '.', search: (prev: Record<string, unknown>) => ({ ...prev, sort, page: undefined }) });
  const setPage = (next: number) =>
    navigate({ to: '.', search: (prev: Record<string, unknown>) => ({ ...prev, page: next > 1 ? next : undefined }) });

  const [backlogOf, setBacklogOf] = useState<BacklogTarget | null>(null);
  const columns = useMemo(
    () => subscriptionColumns((row) => setBacklogOf({ clusterId, id: row.id, name: row.name })),
    [clusterId],
  );
  const rows = query.data?.data ?? [];
  const total = query.data?.count ?? 0;

  const {
    allMatching,
    setAllMatching,
    clearSelection,
    toggleRow,
    toggleAll,
    selectedKeys,
    count,
    pageAllPicked,
    matching,
    selection,
  } = useSubscriptionSelection(rows, total, search.q);

  return (
    <Page fill>
      <PageHeader
        title="Subscriptions"
        description="Every subscription across the cluster's nodes, with its backlog and consumers. Select a row to open its detail and actions."
      />
      <Toolbar
        label="Subscription filters"
        start={
          <TextInput
            ref={filterRef}
            label="Filter subscriptions"
            placeholder="Subscription or destination name"
            value={filter}
            onChange={(e) => typeFilter(e.currentTarget.value)}
            w="17.5rem"
            size="xs"
          />
        }
        end={
          mayCreate ? (
            <CapabilityGate verdict={createGate} what="creating a subscription">
              <Button size="xs" disabled={createGate.kind === 'blocked'} onClick={() => setCreateOpen(true)}>
                New subscription
              </Button>
            </CapabilityGate>
          ) : undefined
        }
      />

      {/* Always mounted, so the first tick does not push the grid down under the cursor. */}
      <SelectionBar
        count={count}
        total={total}
        matching={matching}
        allMatching={allMatching}
        canSelectAll={!allMatching && pageAllPicked && total > rows.length}
        onSelectAll={() => setAllMatching(true)}
        onClear={clearSelection}
      >
        {selectionSlot.map(({ id, Component }) => (
          <Component key={id} clusterId={clusterId} selection={selection} count={count} clear={clearSelection} />
        ))}
      </SelectionBar>

      <DataTable
        label="Subscriptions"
        storageKey="subscriptions"
        height="fill"
        columns={columns}
        data={rows}
        loading={query.isPending}
        error={query.isError ? <ErrorState error={query.error} onRetry={() => void query.refetch()} /> : undefined}
        sort={search.sort}
        onSortChange={setSort}
        onRowClick={setSelected}
        rowKey={rowKey}
        selectable
        selected={selectedKeys}
        onToggleRow={toggleRow}
        onToggleAll={toggleAll}
        toolbar={{
          // The count and position live in the pager, stated once. Not before the first page lands:
          // "No subscriptions" while loading would claim there are none.
          end: query.data ? (
            <Pager page={page} pageSize={PAGE_SIZE} total={total} onChange={setPage} label="subscriptions" />
          ) : undefined,
        }}
        rowMenu={{
          label: (r) => r.name,
          render: (r, menu) => (
            <ResourceActions
              kind="subscription"
              clusterId={clusterId}
              target={{ id: r.id, kind: r.kind, name: r.name, destination: r.destination, snapshot: r }}
              restoreFocus={menu.restoreFocus}
            />
          ),
        }}
        empty={
          <SubscriptionsEmpty
            filterText={search.q ?? ''}
            unreachable={unreachable}
            reach={reach('subscription:read', clusterId)}
            mayCreate={mayCreate}
            onClearFilter={clearFilter}
            onCreate={() => setCreateOpen(true)}
          />
        }
      />

      <SubscriptionDetailDrawer subscription={selected} missing={missing} onClose={() => setSelected(null)} />
      <BacklogDrawer target={backlogOf} onClose={() => setBacklogOf(null)} />
      <CreateSubscriptionForm clusterId={clusterId} opened={createOpen} onClose={() => setCreateOpen(false)} />
    </Page>
  );
}
