import { Stack, Text } from '@mantine/core';
import { useParams } from '@tanstack/react-router';

import { DescriptionList } from '../../ui/DescriptionList.tsx';
import { EmptyState } from '../../ui/EmptyState.tsx';
import { ErrorState } from '../../ui/ErrorState.tsx';
import { LoadingState } from '../../ui/LoadingState.tsx';
import { Notice } from '../../ui/Notice.tsx';
import { Page } from '../../ui/Page.tsx';
import { PageHeader } from '../../ui/PageHeader.tsx';
import { Section } from '../../ui/Section.tsx';
import { StatusBadge } from '../../ui/StatusBadge.tsx';
import { DataTable, type Column } from '../../ui/table/index.ts';
import { useKafkaBrokers, type KafkaBroker, type QuorumVoter } from './api.ts';

const BROKER_COLUMNS: Column<KafkaBroker>[] = [
  { id: 'id', header: 'Broker', accessor: (b) => `Broker ${b.id}`, kind: 'identifier', priority: 'essential' },
  {
    id: 'address',
    header: 'Address',
    accessor: (b) => b.address ?? 'Not learned',
    kind: 'code',
    priority: 'essential',
  },
  { id: 'rack', header: 'Rack', accessor: (b) => b.rack ?? '', kind: 'text', priority: 'low' },
  {
    id: 'reach',
    header: 'Reach',
    accessor: (b) => (b.reachable ? 'Reached' : `Not reached ${b.reason ?? ''}`),
    cell: (b) => (
      <Stack gap={2} miw={0} align="flex-start">
        <StatusBadge tone={b.reachable ? 'neutral' : 'warning'}>{b.reachable ? 'Reached' : 'Not reached'}</StatusBadge>
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
    description: 'Partitions this broker leads',
    accessor: (b) => b.leaders,
    kind: 'number',
    priority: 'high',
  },
  {
    id: 'underReplicated',
    header: 'Under-replicated',
    short: 'Under-rep.',
    accessor: (b) => b.underReplicated,
    kind: 'number',
    priority: 'high',
  },
  { id: 'offline', header: 'Offline', accessor: (b) => b.offline, kind: 'number', priority: 'high' },
];

const VOTER_COLUMNS: Column<QuorumVoter>[] = [
  { id: 'id', header: 'Voter', accessor: (v) => `Broker ${v.id}`, kind: 'identifier', priority: 'essential' },
  {
    id: 'lag',
    header: 'Lag',
    description: 'Records behind the quorum leader',
    accessor: (v) => v.lag ?? null,
    cell: (v) => (v.lag == null ? 'Unknown' : v.lag.toLocaleString()),
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

/** The brokers of a Kafka cluster with what each leads, and the controller quorum or why it cannot be shown. */
export function BrokersView() {
  const { clusterId } = useParams({ strict: false }) as { clusterId: string };
  const brokers = useKafkaBrokers(clusterId);
  if (brokers.isError) {
    return (
      <Page>
        <PageHeader title="Brokers" />
        <ErrorState error={brokers.error} onRetry={() => void brokers.refetch()} />
      </Page>
    );
  }
  if (!brokers.data) {
    return (
      <Page>
        <PageHeader title="Brokers" />
        <LoadingState label="Loading the brokers" blockSize="12rem" />
      </Page>
    );
  }
  const { quorum } = brokers.data;
  const caughtUp = quorum.voters.filter((v) => v.caughtUp).length;
  return (
    <Page>
      <PageHeader
        title="Brokers"
        description="The brokers of the cluster, and the controllers that elect their leaders."
      />
      <Section title="Brokers">
        <DataTable
          variant="static"
          label="Kafka brokers"
          storageKey="kafka.brokers"
          columns={BROKER_COLUMNS}
          data={brokers.data.brokers}
          rowKey={(b) => b.id}
          height={{ maxRows: 16 }}
          empty={<EmptyState kind="empty" title="No broker was found" description="The cluster reported no broker." />}
        />
      </Section>
      <Section title="Controller quorum">
        {quorum.available ? (
          <Stack gap="md">
            <DescriptionList
              label="Controller quorum"
              columns={2}
              items={[
                { term: 'Mode', value: brokers.data.mode },
                { term: 'Leader', value: quorum.leader ? `Broker ${quorum.leader}` : 'None elected' },
              ]}
            />
            <Notice
              tone={quorum.majority ? 'neutral' : 'danger'}
              title={quorum.majority ? 'The quorum has a majority' : 'The quorum has no majority'}
            >
              {quorum.majority
                ? `${caughtUp} of ${quorum.voters.length} voters are caught up, a majority.`
                : `Only ${caughtUp} of ${quorum.voters.length} voters are caught up, which is not a majority. The controllers cannot commit metadata changes.`}
            </Notice>
            <DataTable
              variant="static"
              label="Controller voters"
              storageKey="kafka.voters"
              columns={VOTER_COLUMNS}
              data={quorum.voters}
              rowKey={(v) => v.id}
              height={{ maxRows: 8 }}
              empty={<EmptyState kind="empty" title="No voter was found" description="The quorum reported no voter." />}
            />
          </Stack>
        ) : (
          <Notice tone="info" title="The controller quorum is not shown">
            {quorum.reason ?? 'This cluster does not expose its quorum.'} It is not read as healthy; it is unknown.
          </Notice>
        )}
      </Section>
    </Page>
  );
}
