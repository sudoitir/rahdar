import { Stack, Text } from '@mantine/core';
import { Link } from '@tanstack/react-router';

import { DescriptionList, type DescriptionItem } from '../../ui/DescriptionList.tsx';
import { EmptyState } from '../../ui/EmptyState.tsx';
import linkClasses from '../../ui/InlineLink.module.css';
import { Notice } from '../../ui/Notice.tsx';
import { Section } from '../../ui/Section.tsx';
import { DataTable } from '../../ui/table/index.ts';
import type { ConnectionCheck } from './api.ts';
import { CAUSE_WORDS, CHECK_NODE_COLUMNS } from './checkColumns.tsx';
import { systemWords, unreachedNodes } from './connectionCheck.ts';

function quorumWords(check: ConnectionCheck): string {
  const { quorum } = check;
  if (!quorum) return 'Not read';
  if (quorum.available) return `${quorum.voters ?? 'Unknown'} voters, leader is broker ${quorum.leader ?? 'unknown'}`;
  return `Unavailable: ${quorum.reason ?? 'the cluster does not expose it'}`;
}

/** What the check says about the cluster as a whole, for the facts it has. */
function facts(check: ConnectionCheck): DescriptionItem[] {
  const kafka = check.system === 'kafka';
  const items: DescriptionItem[] = [
    {
      term: 'Version',
      value: check.version ? `${systemWords(check.system)} ${check.version.text}` : 'Not read: no node answered',
      hint: check.version && !check.version.exact ? 'A lower bound: the cluster reports no exact version.' : undefined,
    },
  ];
  if (check.version?.mode || kafka) items.push({ term: 'Mode', value: check.version?.mode ?? 'Not reported' });
  if (check.clusterId || kafka) items.push({ term: 'Cluster id', value: check.clusterId ?? 'Not reported' });
  if (check.quorum || kafka) items.push({ term: 'Controller quorum', value: quorumWords(check) });
  return items;
}

/**
 * What a connection check found, for any system, in registering and in editing: why it failed and what to
 * do, the version with its bound and mode, every node with where Rahdar reached it and how, the quorum, what
 * the account may do, and the capabilities the cluster lacks, each with its reason. `selfId` is the cluster
 * being edited, which is not "another cluster" to itself.
 */
export function ConnectionCheckPanel({ check, selfId }: Readonly<{ check: ConnectionCheck; selfId?: string }>) {
  const unreached = unreachedNodes(check);
  const lacking = check.access.filter((a) => a.status !== 'AVAILABLE');
  const other = check.alreadyRegistered && check.alreadyRegistered.id !== selfId ? check.alreadyRegistered : null;
  const passiveBackup = check.nodes.some((n) => n.artemis?.core === 'NOT_TRIED' && n.artemis.haRole === 'BACKUP');
  return (
    <Stack gap="sm" aria-live="polite">
      {check.failure ? (
        <Notice title={CAUSE_WORDS[check.failure.cause] ?? check.failure.cause} tone="danger">
          <Stack gap={4}>
            <Text size="sm">{check.failure.message}</Text>
            <Text size="sm">{`Next: ${check.failure.next}`}</Text>
          </Stack>
        </Notice>
      ) : null}
      {other ? (
        <Notice
          title="This cluster is already registered"
          tone="danger"
          action={
            <Link to={`/clusters/${other.id}`} className={linkClasses.link}>
              {`Open ${other.name}`}
            </Link>
          }
        >
          {`It is the same cluster as ${other.name}, so registering it again would show the same brokers twice.`}
        </Notice>
      ) : null}
      {!check.failure && unreached.length > 0 && unreached.length < check.nodes.length ? (
        <Notice title="Some nodes were not reached" tone="warning">
          {`${unreached.join(', ')} did not answer at the address Rahdar was given for it. If it is a Kafka broker, its advertised listeners must be reachable from Rahdar. You can still register the cluster; it will be shown degraded.`}
        </Notice>
      ) : null}
      {!check.failure && unreached.length > 0 && unreached.length === check.nodes.length ? (
        <Notice title="No node was reached" tone="danger">
          Fix the addresses, the advertised listeners or the network, then check again.
        </Notice>
      ) : null}

      <DescriptionList label="What the check found" columns={2} items={facts(check)} />

      <DataTable
        variant="static"
        label="Nodes found by the check"
        storageKey="clusters.check"
        columns={CHECK_NODE_COLUMNS}
        data={check.nodes}
        rowKey={(n) => n.id}
        height={{ maxRows: Math.max(check.nodes.length, 1) }}
        empty={<EmptyState kind="empty" title="No node was found" description="The check reached no node." />}
      />
      {passiveBackup ? (
        <Text size="xs" c="dimmed">
          A passive backup opens no Core acceptor, so its Core account is not asked. It is tried once the backup takes
          over.
        </Text>
      ) : null}

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
