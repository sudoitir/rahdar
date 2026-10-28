import { Stack, Text } from '@mantine/core';
import { Link } from '@tanstack/react-router';

import { DescriptionList } from '../../ui/DescriptionList.tsx';
import { EmptyState } from '../../ui/EmptyState.tsx';
import linkClasses from '../../ui/InlineLink.module.css';
import { Notice } from '../../ui/Notice.tsx';
import { Section } from '../../ui/Section.tsx';
import { DataTable } from '../../ui/table/index.ts';
import type { KafkaConnectionCheck } from './api.ts';
import { unreachedBrokers } from './kafkaCheck.ts';
import { CAUSE_WORDS, KAFKA_NODE_COLUMNS } from './kafkaCheckColumns.tsx';

function quorumWords(check: KafkaConnectionCheck): string {
  const { quorum } = check;
  if (!quorum) return 'Not read';
  if (quorum.available) return `${quorum.voters ?? 'Unknown'} voters, leader is broker ${quorum.leader ?? 'unknown'}`;
  return `Unavailable: ${quorum.reason ?? 'the cluster does not expose it'}`;
}

/**
 * What a Kafka connection check found: why it failed and what to do, the version with its bound and mode,
 * every broker with its advertised address and whether Rahdar reached it there, the controller quorum, what
 * the account may do, and the capabilities the cluster lacks, each with its reason.
 */
export function KafkaCheckPanel({ check }: Readonly<{ check: KafkaConnectionCheck }>) {
  const unreached = unreachedBrokers(check);
  const lacking = check.access.filter((a) => a.status !== 'AVAILABLE');
  return (
    <Stack gap="sm" aria-live="polite">
      {check.failure ? (
        <Notice title={CAUSE_WORDS[check.failure.cause]} tone="danger">
          <Stack gap={4}>
            <Text size="sm">{check.failure.message}</Text>
            <Text size="sm">{`Next: ${check.failure.next}`}</Text>
          </Stack>
        </Notice>
      ) : null}
      {check.alreadyRegistered ? (
        <Notice
          title="This cluster is already registered"
          tone="danger"
          action={
            <Link to={`/clusters/${check.alreadyRegistered.id}`} className={linkClasses.link}>
              {`Open ${check.alreadyRegistered.name}`}
            </Link>
          }
        >
          {`Its cluster id matches ${check.alreadyRegistered.name}, so registering it again would show the same brokers twice.`}
        </Notice>
      ) : null}
      {!check.failure && unreached.length > 0 && unreached.length < check.nodes.length ? (
        <Notice title="Some brokers were not reached" tone="warning">
          {`${unreached.join(', ')} did not answer at the address it advertises. A broker's advertised listeners must be reachable from Rahdar. You can still register the cluster; it will be shown degraded.`}
        </Notice>
      ) : null}
      {!check.failure && unreached.length > 0 && unreached.length === check.nodes.length ? (
        <Notice title="No broker was reached at its advertised address" tone="danger">
          A broker&apos;s advertised listeners must be reachable from Rahdar. Fix the advertised listeners or the
          network, then check again.
        </Notice>
      ) : null}

      <DescriptionList
        label="What the check found"
        columns={2}
        items={[
          {
            term: 'Version',
            value: check.version ? `Apache Kafka ${check.version.text}` : 'Not read: no broker answered',
            hint:
              check.version && !check.version.exact
                ? 'A lower bound: the cluster reports no exact version.'
                : undefined,
          },
          { term: 'Mode', value: check.version?.mode ?? 'Not reported' },
          { term: 'Cluster id', value: check.clusterId ?? 'Not reported' },
          { term: 'Controller quorum', value: quorumWords(check) },
        ]}
      />

      <DataTable
        variant="static"
        label="Brokers found by the check"
        storageKey="clusters.kafka-check"
        columns={KAFKA_NODE_COLUMNS}
        data={check.nodes}
        rowKey={(n) => n.id}
        height={{ maxRows: Math.max(check.nodes.length, 1) }}
        empty={<EmptyState kind="empty" title="No broker was found" description="The check reached no broker." />}
      />

      {lacking.length > 0 ? (
        <Section title="What this account cannot do" headingLevel={3}>
          <Stack gap="xs" component="ul" m={0} pl="md">
            {lacking.map((a) => (
              <li key={a.capability}>
                <Text size="sm">
                  <strong>{a.capability}</strong>
                  {a.status === 'UNKNOWN' ? ' (not established)' : ' (unavailable)'}
                  {a.reason ? `: ${a.reason}` : ''}
                </Text>
              </li>
            ))}
          </Stack>
        </Section>
      ) : null}
    </Stack>
  );
}
