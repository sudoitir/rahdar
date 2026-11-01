import { Button, ColorSwatch, Stack, Text } from '@mantine/core';
import { Link } from '@tanstack/react-router';

import { useDismissedNotice } from '../../kernel/useDismissedNotice.ts';
import { useTitlePart } from '../../kernel/shell/pageTitle.ts';
import { ErrorState } from '../../ui/ErrorState.tsx';
import { LoadingState } from '../../ui/LoadingState.tsx';
import linkClasses from '../../ui/InlineLink.module.css';
import { Notice } from '../../ui/Notice.tsx';
import { useCluster, useEnvironments, type CapabilitiesView, type ClusterDetail } from './api.ts';
import { CapabilityLedger } from './CapabilityLedger.tsx';
import styles from './ClusterHeader.module.css';

/** The capabilities the connection reports as unavailable. */
function capabilityGaps(caps: CapabilitiesView | undefined): string[] {
  if (!caps) return [];
  return (['managementRead', 'managementWrite', 'messageIo', 'notifications'] as const).filter(
    (k) => caps[k].status === 'UNAVAILABLE',
  );
}

/**
 * The line's summary: how many nodes, whether they replicate, and whether they were reached. Replication in live and
 * backup pairs is Artemis's: a Kafka cluster replicates per partition, so its line does not call it standalone.
 */
function clusterMeta({ topology, health, system }: ClusterDetail): string {
  const nodeCount = topology.nodes.reduce((n, node) => n + node.endpoints.length, 0);
  const hasPair = topology.nodes.some((n) => n.endpoints.length > 1);
  return [
    `${nodeCount} node${nodeCount === 1 ? '' : 's'}`,
    system?.id === 'artemis' ? (hasPair ? 'replication' : 'standalone') : null,
    health.level === 'UNKNOWN' ? 'not yet contacted' : 'reachable',
  ]
    .filter((part) => part !== null)
    .join(' · ');
}

/**
 * Above every view of a cluster (`cluster.header`): the cluster's facts at the end of the shell's
 * context line (`data-context-line`), its environment beside the environment's colour and what is
 * known of its nodes and health, then the health banner and a notice for a capability the connection
 * lacks, each below the line.
 *
 * The name is not repeated here: the breadcrumb at the line's start already says it, and the facts
 * are named for it. It holds no heading. Each view's `PageHeader` renders the page's one h1
 * (ADR-0163). How current the data is stays with the shell's freshness indicator, which answers it
 * for every route.
 */
export function ClusterHeader({ clusterId }: Readonly<{ clusterId: string }>) {
  const { data, isPending, isError, error, refetch } = useCluster(clusterId);
  const environments = useEnvironments();
  // The cluster's name, for the shell's title and breadcrumb (ADR-0109).
  useTitlePart('cluster', data?.name);

  const caps = data?.capabilities;
  // Nag only on a real, actionable gap. UNKNOWN is not one: since ADR-0049 D5
  // managementWrite and messageIo stay UNKNOWN until a write has actually been
  // attempted, and a notice on every freshly registered cluster — for a broker
  // that is very likely fine — is noise the operator learns to dismiss unread.
  const gaps = capabilityGaps(caps);
  // Keyed on which capabilities are short, so dismissing today's gap does not
  // also hide a different one that appears tomorrow. Computed before the early
  // returns below so the hook order never depends on the query state.
  const [capsDismissed, dismissCaps] = useDismissedNotice(`capabilities:${clusterId}:${gaps.join(',')}`);

  // The facts' own box, holding the line's height, so the page below does not move when the cluster arrives.
  if (isPending) {
    return (
      <div className={styles.strip} data-context-line>
        <LoadingState label="Loading the cluster" variant="inline" />
      </div>
    );
  }
  if (isError) return <ErrorState error={error} onRetry={() => void refetch()} />;

  const environment = environments.data?.find((e) => e.id === data.environmentId);
  const meta = clusterMeta(data);
  const critical = data.health.splitBrain === 'CRITICAL';

  return (
    <>
      <fieldset aria-label={`Cluster ${data.name}`} className={styles.strip} data-context-line>
        {environment ? (
          <>
            <Text component="span" size="xs" className={styles.environment}>
              <ColorSwatch component="span" color={environment.colour ?? 'var(--as-border)'} size="0.625rem" />
              {environment.name}
            </Text>
            <span aria-hidden="true">·</span>
          </>
        ) : null}
        <Text component="span" size="xs">
          {meta}
        </Text>
      </fieldset>

      {data.health.level !== 'OK' && data.health.notes.length > 0 ? (
        <Notice
          title={critical ? 'Two nodes are live in one pair' : 'Needs attention'}
          tone={critical ? 'danger' : 'warning'}
          action={
            data.health.credentialRejections.length > 0 ? (
              <Link to={`/clusters/${clusterId}/settings?tab=clusters-connection`} className={linkClasses.link}>
                Open the connection settings
              </Link>
            ) : undefined
          }
        >
          <Stack gap="xs">
            {data.health.notes.map((n) => (
              <Text key={n} size="sm">
                {n}
              </Text>
            ))}
          </Stack>
        </Notice>
      ) : null}

      {gaps.length > 0 && !capsDismissed ? (
        <Notice
          title="Some broker capabilities need setup"
          tone="warning"
          action={
            <Button variant="default" size="xs" onClick={dismissCaps}>
              Dismiss until you sign out
            </Button>
          }
        >
          <Stack gap="xs">
            <Text size="sm">
              One or more features are limited by this connection. Each row below expands with the reason and the{' '}
              <code>broker.xml</code> change that closes the gap.
            </Text>
            <CapabilityLedger capabilities={data.capabilities} clusterId={clusterId} />
          </Stack>
        </Notice>
      ) : null}
    </>
  );
}
