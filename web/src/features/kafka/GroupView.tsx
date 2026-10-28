import { useState } from 'react';
import { Button, Stack, Text } from '@mantine/core';
import { Link, useParams } from '@tanstack/react-router';

import { useCan } from '../../kernel/auth/useCan.ts';
import { Ago } from '../../kernel/time/Ago.tsx';
import { useServerNow } from '../../kernel/time/time.ts';
import { partitionColumns } from '../../kernel/backlog/partitionColumns.tsx';
import { CapabilityGate } from '../../ui/CapabilityGate.tsx';
import { gateFor, type GateVerdict } from '../../ui/capabilityGate.ts';
import { DescriptionList } from '../../ui/DescriptionList.tsx';
import { EmptyState } from '../../ui/EmptyState.tsx';
import { ErrorState } from '../../ui/ErrorState.tsx';
import linkClasses from '../../ui/InlineLink.module.css';
import { LoadingState } from '../../ui/LoadingState.tsx';
import { Notice } from '../../ui/Notice.tsx';
import { Page } from '../../ui/Page.tsx';
import { PageHeader } from '../../ui/PageHeader.tsx';
import { Section } from '../../ui/Section.tsx';
import { StatusBadge } from '../../ui/StatusBadge.tsx';
import { DataTable, type Column } from '../../ui/table/index.ts';
import { useGroup, type GroupDetail, type GroupMember } from './api.ts';
import { OffsetResetDialog } from './OffsetReset.tsx';
import { stateWords, typeWords } from './words.ts';

const MEMBER_COLUMNS: Column<GroupMember>[] = [
  { id: 'id', header: 'Member', accessor: (m) => m.id, kind: 'identifier', priority: 'essential' },
  { id: 'client', header: 'Client id', accessor: (m) => m.clientId, kind: 'identifier', priority: 'high' },
  { id: 'host', header: 'Host', accessor: (m) => m.host, kind: 'code', priority: 'high' },
  {
    id: 'assignments',
    header: 'Assigned partitions',
    short: 'Assigned',
    accessor: (m) => m.assignments.join(', '),
    kind: 'text',
    priority: 'high',
    wrap: true,
  },
];

/** Whether the offsets may be reset from here: the grant first, then what the group itself says. */
function useResetGate(clusterId: string, group: GroupDetail | undefined): GateVerdict {
  const { can, loading } = useCan();
  const permitted = gateFor(can('subscription:seek', clusterId), 'Reset offsets', undefined, loading);
  if (permitted.kind === 'blocked') return permitted;
  if (group?.readOnly) return { kind: 'blocked', reason: group.readOnly };
  return permitted;
}

/**
 * One consumer group: its state, the position of every partition it reads, its members, and the way to reset
 * its offsets. A partition whose lag could not be read says why instead of showing 0; a deleted topic the
 * group still holds offsets for is said to be gone.
 */
export function GroupView() {
  const { clusterId, group: groupId } = useParams({ strict: false }) as { clusterId: string; group: string };
  const group = useGroup(clusterId, groupId);
  const now = useServerNow(5_000);
  const [resetting, setResetting] = useState(false);
  const gate = useResetGate(clusterId, group.data);
  const columns = partitionColumns();

  const header = (
    <PageHeader
      title={groupId}
      meta={
        <Link to={`/clusters/${clusterId}/kafka/groups`} className={linkClasses.link}>
          All groups
        </Link>
      }
      description={
        group.data ? (
          <>
            Sampled <Ago at={group.data.sampledAt} now={now} />
          </>
        ) : undefined
      }
      actions={
        <CapabilityGate verdict={gate} what="resetting this group's offsets">
          <Button disabled={gate.kind === 'blocked' || !group.data} onClick={() => setResetting(true)}>
            Reset offsets…
          </Button>
        </CapabilityGate>
      }
    />
  );

  if (group.isError) {
    return (
      <Page>
        {header}
        <ErrorState error={group.error} onRetry={() => void group.refetch()} />
      </Page>
    );
  }
  if (!group.data) {
    return (
      <Page>
        {header}
        <LoadingState label="Loading the group" blockSize="12rem" />
      </Page>
    );
  }
  const data = group.data;
  const state = stateWords(data.state);
  return (
    <Page>
      {header}
      {data.readOnly ? (
        <Notice tone="info" title="Its offsets cannot be changed here">
          {data.readOnly}
        </Notice>
      ) : null}
      <DescriptionList
        label="The group"
        columns={2}
        items={[
          { term: 'Type', value: typeWords(data.type) },
          { term: 'State', value: <StatusBadge tone={state.tone}>{state.text}</StatusBadge> },
          { term: 'Members', value: String(data.members.length) },
          { term: 'Topics', value: String(data.topics.length) },
        ]}
      />

      <Section title="Partitions" description="Where the group is in each partition it holds an offset for.">
        {data.topics.length === 0 ? (
          <EmptyState
            kind="empty"
            title="This group holds no offsets"
            description="It has not committed an offset to any topic, so there is no position to show."
          />
        ) : (
          <Stack gap="md">
            {data.topics.map((topic) => (
              <Stack key={topic.topic} gap="xs">
                <Text fw={600} size="sm">
                  {topic.topic}
                </Text>
                {topic.deleted ? (
                  <Notice tone="warning" title="This topic was deleted">
                    The group still holds offsets for {topic.topic}, but the topic is gone. Its offsets are shown as
                    last committed.
                  </Notice>
                ) : null}
                <DataTable
                  variant="static"
                  label={`${topic.topic} partitions`}
                  storageKey="kafka.group.partitions"
                  columns={columns}
                  data={topic.partitions}
                  rowKey={(p) => String(p.partition)}
                  height={{ maxRows: 12 }}
                  empty={null}
                />
              </Stack>
            ))}
          </Stack>
        )}
      </Section>

      <Section title="Members">
        <DataTable
          variant="static"
          label="Group members"
          storageKey="kafka.group.members"
          columns={MEMBER_COLUMNS}
          data={data.members}
          rowKey={(m) => m.id}
          height={{ maxRows: 12 }}
          empty={
            <EmptyState
              kind="empty"
              title="No member is connected"
              description="An empty group has no consumer attached. Its offsets can be reset."
            />
          }
        />
      </Section>

      <OffsetResetDialog clusterId={clusterId} group={data} opened={resetting} onClose={() => setResetting(false)} />
    </Page>
  );
}
