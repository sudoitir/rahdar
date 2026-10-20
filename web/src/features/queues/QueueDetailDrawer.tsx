import { useMemo } from 'react';
import { Button, Drawer, Group, Stack } from '@mantine/core';
import { Link, useParams } from '@tanstack/react-router';

import { addressOf, routingTypeOf, type SubscriptionView } from './api.ts';
import { OwnerChip } from '../../kernel/auth/OwnerChip.tsx';
import { useSlot } from '../../kernel/slots.ts';
import { EmptyState } from '../../ui/EmptyState.tsx';
import { Section } from '../../ui/Section.tsx';
import { StatusBadge } from '../../ui/StatusBadge.tsx';
import { DataTable } from '../../ui/table/index.ts';
import { queueNodeColumns } from './columns.ts';
import { QueueLifecycleActions } from './QueueLifecycleActions.tsx';

type NodeCell = SubscriptionView['perNode'][number];

const nodeKey = (cell: NodeCell) => cell.nodeId;

/**
 * Per-node breakdown for one queue row, its lifecycle actions, a jump into the message browser,
 * and whatever the enabled features add below (`queue.detail.panels`), such as its recent history.
 */
export function QueueDetailDrawer({
  queue,
  missing,
  onClose,
}: Readonly<{
  queue: SubscriptionView | null;
  /** The name of the open queue when it can no longer be found, to say so in place of its detail. */
  missing?: string;
  onClose: () => void;
}>) {
  const { clusterId } = useParams({ strict: false }) as { clusterId: string };
  const panels = useSlot('queue.detail.panels');
  const nodeColumns = useMemo(queueNodeColumns, []);

  return (
    <Drawer
      opened={queue !== null || Boolean(missing)}
      onClose={onClose}
      position="right"
      size="lg"
      title={queue ? `${addressOf(queue)} / ${queue.name}` : (missing ?? '')}
    >
      {!queue && missing ? (
        // Access lost while the queue was open looks the same as a queue that is gone: the listing no longer has it.
        <EmptyState
          kind="empty"
          title="Queue not found"
          description={`No queue named ${missing} is on this cluster that you can see. It may have been deleted, or your access to it may have been removed.`}
          action={
            <Button size="xs" variant="default" onClick={onClose}>
              Close
            </Button>
          }
        />
      ) : null}
      {queue ? (
        <Stack gap="md">
          <Group gap="xs" justify="space-between">
            <Group gap="xs">
              <StatusBadge>{routingTypeOf(queue).toLowerCase()}</StatusBadge>
              <StatusBadge>{queue.durable ? 'durable' : 'non-durable'}</StatusBadge>
              <StatusBadge>{`${queue.nodesPresent}/${queue.nodesTotal} nodes`}</StatusBadge>
              <OwnerChip team={queue.ownerTeam} />
            </Group>
            <Button
              size="xs"
              variant="default"
              component={Link}
              to={`/clusters/${clusterId}/queues/${encodeURIComponent(queue.name)}/messages`}
              onClick={onClose}
            >
              Browse messages
            </Button>
          </Group>

          <QueueLifecycleActions clusterId={clusterId} queue={queue} onClose={onClose} />

          <Section title="Per node" headingLevel={3}>
            <DataTable
              variant="static"
              label={`${queue.name} per node`}
              columns={nodeColumns}
              data={queue.perNode}
              rowKey={nodeKey}
              height={{ maxRows: 8 }}
              empty={
                <EmptyState
                  kind="empty"
                  title="No node reported this queue"
                  description="The last scrape returned no per-node figures for it."
                />
              }
            />
          </Section>

          {panels.map(({ id, Component }) => (
            <Component key={id} clusterId={clusterId} queueName={queue.name} onClose={onClose} />
          ))}
        </Stack>
      ) : null}
    </Drawer>
  );
}
