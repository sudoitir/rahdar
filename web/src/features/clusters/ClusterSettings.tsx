import type { ReactNode } from 'react';
import { Button, Group, Text } from '@mantine/core';
import { Link } from '@tanstack/react-router';

import { ErrorState } from '../../ui/ErrorState.tsx';
import { LoadingState } from '../../ui/LoadingState.tsx';
import { useCluster } from './api.ts';
import { CapabilityLedger } from './CapabilityLedger.tsx';
import { ConnectionForm } from './ConnectionSettings.tsx';
import { KafkaConnectionForm } from './KafkaConnectionSettings.tsx';
import type { KafkaConnection } from './api.ts';
import classes from './Clusters.module.css';

/** Settings section: register another cluster. */
export function RegisterSection() {
  return (
    <>
      <Text size="sm" c="dimmed" mb="sm" className={classes.intro}>
        Register another cluster; remove this one under This cluster → Remove cluster. Rahdar finds brokers that join a
        registered cluster on its own.
      </Text>
      {/* A button is as wide as its words, not the section's. */}
      <Group>
        <Button component={Link} to="/clusters/new" variant="default" size="xs">
          Register cluster
        </Button>
      </Group>
    </>
  );
}

/** A section's content once the cluster has loaded; until then a frame of its size, so nothing below moves. */
function SectionBody({
  cluster,
  blockSize,
  children,
}: Readonly<{
  cluster: ReturnType<typeof useCluster>;
  /** The height of the content that replaces the frame. */
  blockSize: string;
  children: (data: NonNullable<ReturnType<typeof useCluster>['data']>) => ReactNode;
}>) {
  if (cluster.data) return <>{children(cluster.data)}</>;
  if (cluster.isError) return <ErrorState error={cluster.error} onRetry={() => void cluster.refetch()} />;
  return <LoadingState label="Loading the cluster" blockSize={blockSize} />;
}

/** What each system's connection is made of, for the section's opening line. */
function connectionWords(system: string | undefined): ReactNode {
  return system === 'kafka'
    ? 'the bootstrap servers, the security protocol and mechanism, the credentials and the TLS bundle. A secret left empty keeps the stored one.'
    : 'the management address you gave, the pattern that gives every other node its own, and the management and Core accounts, which are stored separately.';
}

/** Settings section: how Rahdar connects to this cluster, checked before it is saved. */
export function ConnectionSection({ clusterId }: Readonly<{ clusterId: string }>) {
  const cluster = useCluster(clusterId);
  return (
    <>
      <Text size="sm" c="dimmed" mb="sm" className={classes.intro}>
        How Rahdar reaches <strong>{cluster.data?.name ?? 'this cluster'}</strong>:{' '}
        {connectionWords(cluster.data?.system.id)} Check a change first; nothing is saved until you save it.
      </Text>
      <SectionBody cluster={cluster} blockSize="24rem">
        {(data) => {
          if (!data.connection) {
            return <Text size="sm">You can see this cluster only through a team, so its connection is not shown.</Text>;
          }
          if (data.system.id === 'kafka') {
            // The generated connection type is Artemis-shaped until the schema carries the union.
            return <KafkaConnectionForm cluster={data} stored={data.connection as unknown as KafkaConnection} />;
          }
          return <ConnectionForm cluster={data} connection={data.connection} />;
        }}
      </SectionBody>
    </>
  );
}

/** The Artemis wording: its management channel is Jolokia and its fixes are `broker.xml` changes. */
const ARTEMIS_CAPABILITY_WORDS = (
  <>
    What this connection can and cannot do over Jolokia. Rows that are not plainly available expand with the reason and
    the exact <code>broker.xml</code> change to close the gap.
  </>
);

/** Settings section: what this connection can and cannot do. */
export function CapabilitiesSection({ clusterId }: Readonly<{ clusterId: string }>) {
  const cluster = useCluster(clusterId);
  return (
    <>
      <Text size="sm" c="dimmed" mb="sm" className={classes.intro}>
        {cluster.data?.system.id === 'artemis'
          ? ARTEMIS_CAPABILITY_WORDS
          : 'What this connection can and cannot do. Rows that are not plainly available expand with the reason.'}
      </Text>
      <SectionBody cluster={cluster} blockSize="14rem">
        {(data) => <CapabilityLedger capabilities={data.capabilities} clusterId={clusterId} />}
      </SectionBody>
    </>
  );
}
