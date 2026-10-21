import { useState } from 'react';
import { Select, Tabs, TextInput } from '@mantine/core';
import { CodeHighlight } from '@mantine/code-highlight';
import { useNavigate, useParams, useSearch } from '@tanstack/react-router';
import { useDebouncedValue } from '@mantine/hooks';

import { useCluster } from '../clusters/index.ts';
import { useRrFlows } from './api.ts';
import { EmptyState } from '../../ui/EmptyState.tsx';
import { ErrorState } from '../../ui/ErrorState.tsx';
import { Page } from '../../ui/Page.tsx';
import { PageHeader } from '../../ui/PageHeader.tsx';
import { Pager } from '../../ui/Pager.tsx';
import { Section } from '../../ui/Section.tsx';
import { Toolbar } from '../../ui/Toolbar.tsx';
import { ExpectationsView } from './ExpectationsView.tsx';
import { FlowDetail } from './FlowDetail.tsx';
import { FlowsTable } from './FlowsTable.tsx';
import { LatencyPanel } from './LatencyPanel.tsx';
import { stateLabel } from './rrState.ts';
import { StuckPanel } from './StuckPanel.tsx';
import { TracingDiagnostics } from './TracingDiagnostics.tsx';

const PAGE_SIZE = 100;

const STATES = ['AWAITING_REPLY', 'COMPLETED', 'TIMED_OUT', 'ORPHANED', 'RESPONDER_DROPPED', 'ORPHANED_REPLY'];

/** The flows, or why there are none to show: the read failed, a filter excludes them, or nothing was traced. */
function FlowsResult({
  flows,
  clusterId,
  page,
  filtered,
  onClearFilters,
  onPage,
  onSelect,
}: Readonly<{
  flows: ReturnType<typeof useRrFlows>;
  clusterId: string;
  page: number;
  filtered: boolean;
  onClearFilters: () => void;
  onPage: (page: number) => void;
  onSelect: (flowId: string) => void;
}>) {
  const total = flows.data?.count ?? 0;
  return (
    <FlowsTable
      label="Flows"
      storageKey="rr.flows"
      flows={flows.data?.data ?? []}
      loading={flows.isPending}
      error={flows.isError ? <ErrorState error={flows.error} onRetry={() => void flows.refetch()} /> : undefined}
      empty={
        filtered ? (
          <EmptyState
            kind="filtered"
            title="No flow matches these filters"
            description="Request-reply flows are being traced on this cluster, but none has this state or destination. Clear the filters to see them all."
            onClearFilters={onClearFilters}
          />
        ) : (
          // A bare "0 flows" reads identically whether nothing was sent, nothing could be browsed,
          // or every request was consumed faster than the sampler ticks. Those have three different
          // answers, and the diagnostics give them.
          <TracingDiagnostics clusterId={clusterId} />
        )
      }
      toolbar={{ end: <Pager page={page} pageSize={PAGE_SIZE} total={total} onChange={onPage} label="flows" /> }}
      onSelect={onSelect}
    />
  );
}

/**
 * Request-reply tracing: the flagship screen. When the cluster's notification
 * capability is unavailable (no NOTIFICATIONS, or no resolvable Core URL),
 * shows why rather than an empty flows list — same stance as {@code EventsView}.
 */
export function FlowsView() {
  const { clusterId } = useParams({ strict: false }) as { clusterId: string };
  const search = useSearch({ strict: false }) as {
    tab?: string;
    state?: string;
    destination?: string;
    page?: number;
  };
  const navigate = useNavigate();

  const cluster = useCluster(clusterId);
  const notificationsCap = cluster.data?.capabilities.notifications;

  const [selectedFlow, setSelectedFlow] = useState<string | null>(null);
  const [destination, setDestination] = useState(search.destination ?? '');
  const [debouncedDestination] = useDebouncedValue(destination, 250);

  const tab = search.tab ?? 'flows';
  const setTab = (v: string | null) =>
    navigate({ to: '.', search: (prev: Record<string, unknown>) => ({ ...prev, tab: v ?? undefined }) });

  const page = search.page ?? 1;
  const setPage = (next: number) =>
    navigate({
      to: '.',
      search: (prev: Record<string, unknown>) => ({ ...prev, page: next > 1 ? next : undefined }),
    });

  const flows = useRrFlows(clusterId, {
    state: search.state,
    destination: debouncedDestination || undefined,
    page,
    size: PAGE_SIZE,
  });

  const header = (
    <PageHeader
      title="Request-reply tracing"
      description="Which requests were answered, which are still waiting and which were never answered, reconstructed from the traffic on the destinations you trace."
    />
  );

  if (notificationsCap && notificationsCap.status !== 'AVAILABLE') {
    return (
      <Page>
        {header}
        <Section variant="card" title="Tracing not available" description={notificationsCap.reason}>
          {notificationsCap.brokerXmlSnippet ? (
            <CodeHighlight code={notificationsCap.brokerXmlSnippet} language="xml" />
          ) : null}
        </Section>
      </Page>
    );
  }

  const filtered = Boolean(search.state || debouncedDestination);
  const clearFilters = () => {
    setDestination('');
    void navigate({
      to: '.',
      search: (prev: Record<string, unknown>) => ({
        ...prev,
        state: undefined,
        destination: undefined,
        page: undefined,
      }),
    });
  };

  return (
    <Page>
      {header}

      <Tabs value={tab} onChange={setTab}>
        <Tabs.List>
          <Tabs.Tab value="flows">Flows</Tabs.Tab>
          <Tabs.Tab value="stuck">Stuck</Tabs.Tab>
          <Tabs.Tab value="latency">Latency</Tabs.Tab>
          <Tabs.Tab value="expectations">Expectations</Tabs.Tab>
        </Tabs.List>

        <Tabs.Panel value="flows" pt="md">
          <Section title="Flows">
            <Toolbar
              label="Flow filters"
              start={
                <>
                  <TextInput
                    label="Filter by destination"
                    placeholder="orders.request"
                    value={destination}
                    onChange={(e) => {
                      setDestination(e.currentTarget.value);
                      // A filter change invalidates the position: page 4 of the old
                      // result is page 4 of nothing.
                      if (page > 1) void setPage(1);
                    }}
                    size="xs"
                    w="13.75rem"
                  />
                  <Select
                    label="State"
                    placeholder="Any state"
                    size="xs"
                    w="13.75rem"
                    clearable
                    value={search.state ?? null}
                    onChange={(v) =>
                      navigate({
                        to: '.',
                        search: (prev: Record<string, unknown>) => ({
                          ...prev,
                          state: v || undefined,
                          page: undefined,
                        }),
                      })
                    }
                    data={STATES.map((value) => ({ value, label: stateLabel(value) }))}
                  />
                </>
              }
            />
            <FlowsResult
              flows={flows}
              clusterId={clusterId}
              page={page}
              filtered={filtered}
              onClearFilters={clearFilters}
              onPage={setPage}
              onSelect={setSelectedFlow}
            />
          </Section>
        </Tabs.Panel>

        <Tabs.Panel value="stuck" pt="md">
          <Section title="Stuck flows">
            <StuckPanel clusterId={clusterId} onSelect={setSelectedFlow} />
          </Section>
        </Tabs.Panel>

        <Tabs.Panel value="latency" pt="md">
          <Section title="Latency">
            <LatencyPanel clusterId={clusterId} />
          </Section>
        </Tabs.Panel>

        <Tabs.Panel value="expectations" pt="md">
          <ExpectationsView clusterId={clusterId} />
        </Tabs.Panel>
      </Tabs>

      <FlowDetail clusterId={clusterId} flowId={selectedFlow} onClose={() => setSelectedFlow(null)} />
    </Page>
  );
}
