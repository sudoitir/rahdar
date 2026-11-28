import { type ReactNode } from 'react';
import { SegmentedControl, Stack, Text } from '@mantine/core';
import { Link, useNavigate, useParams, useSearch } from '@tanstack/react-router';

import { branding } from '../../branding.ts';
import { DescriptionList } from '../../ui/DescriptionList.tsx';
import { DiagramView } from '../../ui/DiagramView.tsx';
import { EmptyState } from '../../ui/EmptyState.tsx';
import { ErrorState } from '../../ui/ErrorState.tsx';
import linkClasses from '../../ui/InlineLink.module.css';
import { LoadingState } from '../../ui/LoadingState.tsx';
import { Notice } from '../../ui/Notice.tsx';
import { Page } from '../../ui/Page.tsx';
import { PageHeader } from '../../ui/PageHeader.tsx';
import { Section } from '../../ui/Section.tsx';
import { Stat } from '../../ui/Stat.tsx';
import { StatusBadge } from '../../ui/StatusBadge.tsx';
import { DataTable, type Column } from '../../ui/table/index.ts';
import { useKafkaCluster, type ClusterBroker, type ClusterView as Cluster, type QuorumVoter } from './api.ts';
import { GRAPH_EDGE_CAP, brokerNodeId, clusterGraph } from './clusterGraph.ts';
import type { ClusterSearch } from './search.ts';
import styles from './ClusterView.module.css';

type SetSearch = (patch: Partial<ClusterSearch>) => void;

function figure(n: number | null | undefined): string {
  return n == null ? 'Unavailable' : n.toLocaleString();
}

/** Placement was not read: a zero would be an invented total. A capped read is a floor, and its numbers stand. */
function counted(view: Cluster, n: number): number | null {
  return view.totals.topics == null && !view.totals.atLeast ? null : n;
}

/** A floor, not a total. The word sits with the figure so a partial read is not read as everything. */
function floorStat(view: Cluster, n: number): ReactNode {
  const known = counted(view, n);
  if (known == null) return null;
  const text = known.toLocaleString();
  if (!view.totals.atLeast) return text;
  return (
    <>
      <span className={styles.atLeast}>at least </span>
      {text}
    </>
  );
}

function floorText(view: Cluster, n: number): string {
  const known = counted(view, n);
  if (known == null) return 'Unavailable';
  const text = known.toLocaleString();
  return view.totals.atLeast ? `at least ${text}` : text;
}

function leaderWord(view: Cluster): string {
  const id = view.quorum.leader;
  if (!id) return 'None elected';
  if (view.brokers.some((b) => b.id === id)) return `Broker ${id}`;
  if (view.controllers.some((c) => c.id === id)) return `Controller ${id}`;
  return id;
}

const voterColumns: Column<QuorumVoter>[] = [
  { id: 'id', header: 'Voter', accessor: (v) => `Voter ${v.id}`, kind: 'identifier', priority: 'essential' },
  {
    id: 'lag',
    header: 'Lag',
    description: 'Records behind the quorum leader',
    accessor: (v) => v.lag ?? null,
    cell: (v) => figure(v.lag),
    kind: 'number',
    priority: 'high',
  },
  {
    id: 'caughtUp',
    header: 'Caught up',
    accessor: (v) => (v.caughtUp ? 'Yes' : 'Lagging'),
    cell: (v) => <StatusBadge tone={v.caughtUp ? 'neutral' : 'warning'}>{v.caughtUp ? 'Yes' : 'Lagging'}</StatusBadge>,
    kind: 'text',
    badge: true,
    priority: 'essential',
  },
];

function brokerColumns(
  clusterId: string,
  chosen: string | undefined,
  choose: (id: string) => void,
): Column<ClusterBroker>[] {
  return [
    {
      id: 'id',
      header: 'Broker',
      accessor: (b) => `Broker ${b.id}`,
      cell: (b) => (
        <button
          type="button"
          className={`${linkClasses.link} ${styles.choose}`}
          aria-pressed={b.id === chosen}
          onClick={() => choose(b.id)}
        >
          {`Broker ${b.id}`}
        </button>
      ),
      kind: 'identifier',
      priority: 'essential',
    },
    {
      id: 'address',
      header: 'Address',
      accessor: (b) => b.address ?? 'Not learned',
      kind: 'code',
      priority: 'essential',
    },
    { id: 'rack', header: 'Rack', accessor: (b) => b.rack ?? 'Not learned', kind: 'text', priority: 'low' },
    {
      id: 'reach',
      header: 'Reach',
      accessor: (b) => (b.reachable ? 'Reached' : `Not reached ${b.reason ?? ''}`),
      cell: (b) => (
        <Stack gap={2} miw={0} align="flex-start">
          <StatusBadge tone={b.reachable ? 'neutral' : 'warning'}>
            {b.reachable ? 'Reached' : 'Not reached'}
          </StatusBadge>
          {!b.reachable && b.reason ? (
            <Text size="xs" c="dimmed">
              {b.reason}
            </Text>
          ) : null}
        </Stack>
      ),
      kind: 'text',
      badge: true,
      wrap: true,
      priority: 'essential',
    },
    {
      id: 'leaders',
      header: 'Leaders',
      description: 'Partitions this broker leads. Unavailable when placement was not read or its id is not a number.',
      accessor: (b) => b.leaders ?? null,
      cell: (b) => figure(b.leaders),
      kind: 'number',
      priority: 'high',
    },
    {
      id: 'replicasHeld',
      header: 'Replicas held',
      short: 'Held',
      description: 'Replicas this broker holds. Unavailable when placement was not read or its id is not a number.',
      accessor: (b) => b.replicasHeld ?? null,
      cell: (b) => figure(b.replicasHeld),
      kind: 'number',
      priority: 'high',
    },
    {
      id: 'outOfSyncReplicas',
      header: 'Out of sync',
      short: 'Out of sync',
      description:
        'Replicas it holds that are out of sync. Unavailable when placement was not read or its id is not a number.',
      accessor: (b) => b.outOfSyncReplicas ?? null,
      cell: (b) => figure(b.outOfSyncReplicas),
      kind: 'number',
      priority: 'high',
    },
    {
      id: 'offlineReplicas',
      header: 'Offline',
      description:
        'Partitions it replicates that have no leader. Unavailable when placement was not read or its id is not a number.',
      accessor: (b) => b.offlineReplicas ?? null,
      cell: (b) => figure(b.offlineReplicas),
      kind: 'number',
      priority: 'high',
    },
    {
      id: 'config',
      header: 'Configuration',
      accessor: () => '',
      cell: (b) => (
        <Link
          to={`/clusters/${clusterId}/kafka/brokers/${encodeURIComponent(b.id)}/config`}
          className={linkClasses.link}
          aria-label={`Configuration of broker ${b.id}`}
        >
          Configuration
        </Link>
      ),
      kind: 'status',
      priority: 'essential',
    },
  ];
}

function Totals({ view }: Readonly<{ view: Cluster }>) {
  const reason = view.reason ?? 'The total is not known.';
  return (
    <fieldset className={styles.stats} aria-label="Cluster totals">
      <Stat label="Brokers reached" value={`${view.totals.brokersReached} of ${view.totals.brokers}`} />
      <Stat label="Controllers" value={view.totals.controllers.toLocaleString()} />
      <Stat
        label="Topics"
        value={view.totals.topics == null ? null : floorStat(view, view.totals.topics)}
        unavailableReason={reason}
      />
      <Stat
        label="Partitions"
        value={view.totals.partitions == null ? null : floorStat(view, view.totals.partitions)}
        unavailableReason={reason}
      />
      <Stat label="Under-replicated" value={floorStat(view, view.totals.underReplicated)} unavailableReason={reason} />
      <Stat label="No leader" value={floorStat(view, view.totals.offline)} unavailableReason={reason} />
      <Stat
        label="Not preferred leader"
        value={floorStat(view, view.totals.preferredLeaderMismatches)}
        unavailableReason={reason}
      />
      <Stat
        label="Quorum majority"
        value={view.quorum.available ? (view.quorum.majority ? 'Yes' : 'No') : null}
        unavailableReason={view.quorum.reason ?? 'The quorum is not known.'}
      />
    </fieldset>
  );
}

function votersCaughtUp(caughtUp: number, voters: number, majority: boolean): string {
  const verb = caughtUp === 1 ? 'is' : 'are';
  const count = `${caughtUp} of ${voters} voters ${verb} caught up`;
  return majority
    ? `${count}, a majority.`
    : `Only ${count}, which is not a majority. The controllers cannot commit metadata changes.`;
}

function Quorum({ view }: Readonly<{ view: Cluster }>) {
  const { quorum } = view;
  const caughtUp = quorum.voters.filter((v) => v.caughtUp).length;
  return (
    <Section title="Controller quorum">
      {quorum.available ? (
        <Stack gap="md">
          <DescriptionList
            label="Controller quorum"
            columns={2}
            items={[
              { term: 'Mode', value: view.mode },
              { term: 'Leader', value: leaderWord(view) },
            ]}
          />
          <Notice
            tone={quorum.majority ? 'neutral' : 'danger'}
            title={quorum.majority ? 'The quorum has a majority' : 'The quorum has no majority'}
          >
            {votersCaughtUp(caughtUp, quorum.voters.length, quorum.majority)}
          </Notice>
          <DataTable
            variant="static"
            label="Controller voters"
            storageKey="kafka.cluster.voters"
            columns={voterColumns}
            data={quorum.voters}
            rowKey={(v) => v.id}
            height={{ maxRows: 8 }}
            empty={<EmptyState kind="empty" title="No voter was found" description="The quorum reported no voter." />}
          />
        </Stack>
      ) : (
        <Notice tone="info" title="The controller quorum is not shown">
          {quorum.reason ?? 'This cluster does not expose its quorum.'} It is not counted as healthy; it is unknown.
        </Notice>
      )}
    </Section>
  );
}

function Attention({ clusterId, view }: Readonly<{ clusterId: string; view: Cluster }>) {
  const { problems } = view;
  const names = problems.topicsOmitted === 1 ? 'name is' : 'names are';
  return (
    <Section title="Partitions needing attention">
      <Stack gap="sm">
        <DescriptionList
          label="Partitions needing attention"
          columns={2}
          items={[
            { term: 'No leader', value: floorText(view, problems.offline) },
            { term: 'Under-replicated', value: floorText(view, problems.underReplicated) },
            { term: 'Not preferred leader', value: floorText(view, problems.preferredLeaderMismatches) },
          ]}
        />
        {problems.topicsWithheld ? (
          <Notice tone="info" title="Topic names are withheld">
            {problems.topicsWithheld}
          </Notice>
        ) : null}
        {problems.topicsOmitted > 0 ? (
          <Text size="sm">{`${problems.topicsOmitted.toLocaleString()} topic ${names} not listed.`}</Text>
        ) : null}
        {problems.topics.length > 0 ? (
          <ul className={styles.topics}>
            {problems.topics.map((topic) => (
              <li key={topic}>
                <Link
                  to={`/clusters/${clusterId}/kafka/replication?topic=${encodeURIComponent(topic)}`}
                  className={linkClasses.link}
                >
                  {topic}
                </Link>
              </li>
            ))}
          </ul>
        ) : view.complete && !problems.topicsWithheld && problems.topicsOmitted === 0 ? (
          <Text size="sm">No partition needs attention.</Text>
        ) : null}
      </Stack>
    </Section>
  );
}

function BrokerAside({
  clusterId,
  view,
  brokerId,
  onClear,
}: Readonly<{ clusterId: string; view: Cluster; brokerId: string | undefined; onClear: () => void }>) {
  const broker = brokerId ? view.brokers.find((b) => b.id === brokerId) : undefined;
  let body;
  if (!brokerId) {
    body = (
      <Text size="sm" c="dimmed">
        Choose a broker to see its rack, address and what it holds.
      </Text>
    );
  } else if (!broker) {
    body = (
      <Notice tone="info" title="This broker is not in the snapshot">
        Broker {brokerId} is not in what the cluster just reported.{' '}
        <button type="button" className={`${linkClasses.link} ${styles.choose}`} onClick={onClear}>
          Clear the choice
        </button>
      </Notice>
    );
  } else {
    body = (
      <Stack gap="sm">
        <DescriptionList
          label={`Broker ${broker.id}`}
          columns={1}
          items={[
            { term: 'Rack', value: broker.rack ?? 'Not learned' },
            { term: 'Address', value: broker.address ?? 'Not learned' },
            { term: 'Reach', value: broker.reachable ? 'Reached' : `Not reached. ${broker.reason ?? ''}` },
            { term: 'Leaders', value: figure(broker.leaders) },
            { term: 'Replicas held', value: figure(broker.replicasHeld) },
            { term: 'Replicas out of sync', value: figure(broker.outOfSyncReplicas) },
          ]}
        />
        {broker.leaders == null ? (
          <Text size="sm" c="dimmed">
            {view.reason ?? "The cluster cannot count this broker's partitions: its id is not a number."}
          </Text>
        ) : null}
        <Link
          to={`/clusters/${clusterId}/kafka/brokers/${encodeURIComponent(broker.id)}/config`}
          className={linkClasses.link}
          aria-label={`Configuration of broker ${broker.id}`}
        >
          Configuration
        </Link>
      </Stack>
    );
  }
  return (
    <aside className={styles.aside} aria-label="Chosen broker">
      {body}
    </aside>
  );
}

function ClusterBody({
  clusterId,
  view,
  search,
  setSearch,
}: Readonly<{ clusterId: string; view: Cluster; search: ClusterSearch; setSearch: SetSearch }>) {
  const graph = clusterGraph(view);
  const table = search.view === 'table';
  const notes = view.notes.filter((n) => n !== view.reason && n !== view.quorum.reason);
  return (
    <>
      {!view.complete ? (
        <Notice tone="info" title="This read is incomplete">
          {view.reason ??
            'The cluster was not fully described, so the counts below are at least what was read, not a total.'}
        </Notice>
      ) : null}
      <Totals view={view} />
      <div className={styles.body}>
        <div className={styles.main}>
          {table ? (
            <Section title="Brokers">
              <DataTable
                variant="static"
                label="Kafka brokers"
                storageKey="kafka.cluster.brokers"
                columns={brokerColumns(clusterId, search.broker, (id) => setSearch({ broker: id }))}
                data={view.brokers}
                rowKey={(b) => b.id}
                height={{ maxRows: 16 }}
                empty={
                  <EmptyState kind="empty" title="No broker was found" description="The cluster reported no broker." />
                }
              />
            </Section>
          ) : (
            <Stack gap="sm">
              <div className={styles.frame}>
                <DiagramView
                  nodes={graph.nodes}
                  edges={graph.edges}
                  direction="RIGHT"
                  height="100%"
                  aria-label="Cluster"
                  selectedId={search.broker ? brokerNodeId(search.broker) : null}
                  onSelect={(id) =>
                    setSearch({ broker: id.startsWith('broker:') ? id.slice('broker:'.length) : undefined })
                  }
                />
              </div>
              {graph.summary.omittedEdges > 0 ? (
                <Notice tone="info" title="Not every pair is drawn">
                  {`${graph.summary.omittedEdges.toLocaleString()} co-replication ${graph.summary.omittedEdges === 1 ? 'pair' : 'pairs'} beyond the strongest ${GRAPH_EDGE_CAP} ${graph.summary.omittedEdges === 1 ? 'is' : 'are'} not available on this screen; open Replication, or a topic's partitions, to see a pair.`}
                </Notice>
              ) : null}
            </Stack>
          )}
        </div>
        <BrokerAside
          clusterId={clusterId}
          view={view}
          brokerId={search.broker}
          onClear={() => setSearch({ broker: undefined })}
        />
      </div>
      <Quorum view={view} />
      <Attention clusterId={clusterId} view={view} />
      {notes.length > 0 ? (
        <Section title="What this cluster cannot show">
          <Stack gap="sm">
            {notes.map((note) => (
              <Notice key={note} tone="info" title="Not counted as healthy">
                {note}
              </Notice>
            ))}
          </Stack>
        </Section>
      ) : null}
    </>
  );
}

/** One Kafka cluster, read once: the graph and the same facts in words. Nothing here writes. */
export function ClusterView() {
  const { clusterId } = useParams({ strict: false }) as { clusterId: string };
  const search = useSearch({ strict: false }) as ClusterSearch;
  const navigate = useNavigate();
  const cluster = useKafkaCluster(clusterId);
  const setSearch: SetSearch = (patch) => {
    void navigate({ to: '.', search: (prev: Record<string, unknown>) => ({ ...prev, ...patch }) });
  };
  const view = search.view ?? 'graph';

  let body;
  if (cluster.isError) {
    body = <ErrorState error={cluster.error} onRetry={() => void cluster.refetch()} />;
  } else if (!cluster.data) {
    body = (
      <div className={styles.placeholder}>
        <LoadingState label="Loading the cluster" blockSize="100%" />
      </div>
    );
  } else if (cluster.data.brokers.length === 0 && cluster.data.controllers.length === 0) {
    body = (
      <EmptyState
        kind="empty"
        title="No broker has answered yet"
        description={`${branding.productName} keeps looking.`}
      />
    );
  } else {
    body = <ClusterBody clusterId={clusterId} view={cluster.data} search={search} setSearch={setSearch} />;
  }

  return (
    <Page>
      <PageHeader
        title="Cluster"
        description="The brokers of this cluster, the controllers that serve no clients, and which brokers share replicas."
        meta={
          <Link to={`/clusters/${clusterId}/kafka/brokers/default/config`} className={linkClasses.link}>
            Cluster default configuration
          </Link>
        }
        actions={
          <SegmentedControl
            size="xs"
            aria-label="Show as"
            data={[
              { value: 'graph', label: 'Graph' },
              { value: 'table', label: 'Table' },
            ]}
            value={view}
            onChange={(value) => setSearch({ view: value === 'table' ? 'table' : undefined })}
          />
        }
      />
      {body}
    </Page>
  );
}
