import { useMemo, useState } from 'react';
import { Select, TextInput } from '@mantine/core';
import { useDebouncedCallback } from '@mantine/hooks';
import { useNavigate, useSearch } from '@tanstack/react-router';

import { ResourceActions } from '../../kernel/actions/ResourceActions.tsx';
import { useDisplayZone } from '../../kernel/time/timezone.ts';
import { EmptyState } from '../../ui/EmptyState.tsx';
import { ErrorState } from '../../ui/ErrorState.tsx';
import { LoadingState } from '../../ui/LoadingState.tsx';
import { Notice } from '../../ui/Notice.tsx';
import { Pager } from '../../ui/Pager.tsx';
import { Section } from '../../ui/Section.tsx';
import { Toolbar } from '../../ui/Toolbar.tsx';
import { DataTable } from '../../ui/table/index.ts';
import { useClusters, useEnvironments, useEstateSubscriptions, type EstateSubscription } from './api.ts';
import { clusterColumns, estateSubscriptionColumns } from './estateColumns.tsx';
import { RegisterClusterForm } from './RegisterCluster.tsx';

const PAGE_SIZE = 50;

interface EstateSearch {
  q?: string;
  cluster?: string;
  sort?: string;
  page?: number;
}

const rowKey = (r: EstateSubscription) => `${r.cluster.id}:${r.subscription.id}`;

/** The landing page's content (`home.estate`) when no cluster is registered: what a cluster is, and the way to add one. */
function NoClusters() {
  return (
    <>
      <EmptyState
        kind="empty"
        title="No clusters yet"
        description="A cluster is a set of brokers of one system that Rahdar watches and manages together. Register the first one below; it appears here and in the cluster switcher once it is saved."
      />
      <Section title="Register a cluster">
        <RegisterClusterForm />
      </Section>
    </>
  );
}

/** The estate's subscriptions: filter and page in the URL, one row per subscription with its cluster. */
function EstateSubscriptions({
  silent,
  clusterNames,
}: Readonly<{ silent: string[]; clusterNames: { value: string; label: string }[] }>) {
  const search = useSearch({ strict: false }) as EstateSearch;
  const navigate = useNavigate();
  const [filter, setFilter] = useState(search.q ?? '');
  const page = search.page ?? 1;
  const query = useEstateSubscriptions({ ...search, page, size: PAGE_SIZE });
  const columns = useMemo(estateSubscriptionColumns, []);

  const write = (patch: EstateSearch) =>
    void navigate({
      to: '.',
      search: (prev: Record<string, unknown>) => ({ ...prev, page: undefined, ...patch }),
    });
  const writeFilter = useDebouncedCallback((q: string) => write({ q: q || undefined }), 250);
  const typeFilter = (q: string) => {
    setFilter(q);
    writeFilter(q);
  };
  // Cancelling first: a write still waiting on the debounce would put the old filter back.
  const clearFilters = () => {
    writeFilter.cancel();
    setFilter('');
    write({ q: undefined, cluster: undefined });
  };

  const rows = query.data?.data ?? [];
  const filtered = Boolean(search.q || search.cluster);
  return (
    <Section
      title="Subscriptions"
      description="Every subscription of every cluster you may read. Open a row’s menu to browse, pause or purge it on its own cluster."
    >
      {silent.length > 0 ? (
        <Notice tone="warning" title="Some clusters are not answering">
          Subscriptions of {silent.join(', ')} are unavailable because Rahdar has no answer from{' '}
          {silent.length === 1 ? 'its' : 'their'} brokers. The rest are shown.
        </Notice>
      ) : null}
      <Toolbar
        label="Subscription filters"
        start={
          <>
            <TextInput
              label="Filter subscriptions"
              placeholder="Subscription or destination name"
              value={filter}
              onChange={(e) => typeFilter(e.currentTarget.value)}
              w="17.5rem"
              size="xs"
            />
            <Select
              label="Cluster"
              data={clusterNames}
              value={search.cluster ?? null}
              onChange={(cluster) => write({ cluster: cluster ?? undefined })}
              placeholder="All clusters"
              clearable
              size="xs"
              w="14rem"
            />
          </>
        }
      />
      <DataTable
        label="Subscriptions"
        storageKey="estate.subscriptions"
        height={{ maxRows: 15 }}
        columns={columns}
        data={rows}
        rowKey={rowKey}
        loading={query.isPending}
        error={query.isError ? <ErrorState error={query.error} onRetry={() => void query.refetch()} /> : undefined}
        sort={search.sort}
        onSortChange={(sort) => write({ sort })}
        toolbar={{
          end: query.data ? (
            <Pager
              page={page}
              pageSize={PAGE_SIZE}
              total={query.data.count ?? 0}
              onChange={(next) =>
                void navigate({
                  to: '.',
                  search: (prev: Record<string, unknown>) => ({ ...prev, page: next > 1 ? next : undefined }),
                })
              }
              label="subscriptions"
            />
          ) : undefined,
        }}
        rowMenu={{
          label: (r) => r.subscription.name,
          render: (r, menu) => (
            <ResourceActions
              kind="subscription"
              clusterId={r.cluster.id}
              target={{
                id: r.subscription.id,
                kind: r.subscription.kind,
                name: r.subscription.name,
                destination: r.subscription.destination,
                snapshot: r.subscription,
              }}
              restoreFocus={menu.restoreFocus}
            />
          ),
        }}
        empty={
          filtered ? (
            <EmptyState
              kind="filtered"
              title="No subscriptions match this filter"
              description="Subscriptions may still exist on these clusters; none of them match the filter."
              onClearFilters={clearFilters}
            />
          ) : (
            <EmptyState
              kind="empty"
              title="No subscriptions yet"
              description="A subscription is where messages wait for a consumer. They appear here within a scrape tick of being created on any cluster you may read."
            />
          )
        }
      />
    </Section>
  );
}

/**
 * The landing page's content (`home.estate`): every cluster of every connected system with comparable health,
 * and the subscriptions across them. With no cluster it explains what a cluster is and offers to register one.
 * `HomeView` owns the page and its h1, so this holds none.
 */
export function EstateView() {
  const clusters = useClusters();
  const environments = useEnvironments();
  const zone = useDisplayZone();
  const byId = useMemo(() => new Map((environments.data ?? []).map((e) => [e.id, e])), [environments.data]);
  const columns = useMemo(() => clusterColumns(byId, zone), [byId, zone]);

  if (clusters.isPending) return <LoadingState label="Loading clusters" blockSize="24rem" />;
  if (clusters.isError) return <ErrorState error={clusters.error} onRetry={() => void clusters.refetch()} />;
  if (clusters.data.length === 0) return <NoClusters />;

  const silent = clusters.data.filter((c) => c.health === 'UNKNOWN').map((c) => c.name);
  return (
    <>
      <Section
        title="Clusters"
        description="Health is read the same way for every system, from each cluster’s conditions."
      >
        <DataTable
          variant="static"
          label="Clusters"
          storageKey="estate.clusters"
          columns={columns}
          data={clusters.data}
          rowKey={(c) => c.id}
          height={{ maxRows: 12 }}
          empty={<EmptyState kind="empty" title="No clusters yet" description="Register a cluster to see it here." />}
        />
      </Section>
      <EstateSubscriptions silent={silent} clusterNames={clusters.data.map((c) => ({ value: c.id, label: c.name }))} />
    </>
  );
}
