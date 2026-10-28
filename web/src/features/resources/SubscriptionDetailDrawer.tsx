import { useMemo } from 'react';
import { Button, Drawer, Group, Stack } from '@mantine/core';
import { Link, useParams } from '@tanstack/react-router';

import { subscriptionRef } from '../../kernel/api/resourceId.ts';
import { destinationOf, routingTypeOf, type SubscriptionView } from './api.ts';
import { OwnerChip } from '../../kernel/auth/OwnerChip.tsx';
import { useSlot } from '../../kernel/slots.ts';
import { EmptyState } from '../../ui/EmptyState.tsx';
import { Section } from '../../ui/Section.tsx';
import { StatusBadge } from '../../ui/StatusBadge.tsx';
import { DataTable } from '../../ui/table/index.ts';
import { subscriptionNodeColumns } from './subscriptionColumns.ts';
import { SubscriptionLifecycleActions } from './SubscriptionLifecycleActions.tsx';

type NodeCell = SubscriptionView['perNode'][number];

const nodeKey = (cell: NodeCell) => cell.nodeId;

/**
 * Per-node breakdown for one subscription row, its lifecycle actions, a jump into the message browser,
 * and whatever the enabled features add below (`subscription.detail.panels`), such as its recent history.
 */
export function SubscriptionDetailDrawer({
  subscription,
  missing,
  onClose,
}: Readonly<{
  subscription: SubscriptionView | null;
  /** The name of the open subscription when it can no longer be found, to say so in place of its detail. */
  missing?: string;
  onClose: () => void;
}>) {
  const { clusterId } = useParams({ strict: false }) as { clusterId: string };
  const panels = useSlot('subscription.detail.panels');
  const nodeColumns = useMemo(subscriptionNodeColumns, []);

  return (
    <Drawer
      opened={subscription !== null || Boolean(missing)}
      onClose={onClose}
      position="right"
      size="lg"
      title={subscription ? `${destinationOf(subscription)} / ${subscription.name}` : (missing ?? '')}
    >
      {!subscription && missing ? (
        // Access lost while the subscription was open looks the same as a subscription that is gone: the listing no longer has it.
        <EmptyState
          kind="empty"
          title="Subscription not found"
          description={`No subscription named ${missing} is on this cluster that you can see. It may have been deleted, or your access to it may have been removed.`}
          action={
            <Button size="xs" variant="default" onClick={onClose}>
              Close
            </Button>
          }
        />
      ) : null}
      {subscription ? (
        <Stack gap="md">
          <Group gap="xs" justify="space-between">
            <Group gap="xs">
              <StatusBadge>{routingTypeOf(subscription).toLowerCase()}</StatusBadge>
              <StatusBadge>{subscription.durable ? 'durable' : 'non-durable'}</StatusBadge>
              <StatusBadge>{`${subscription.nodesPresent}/${subscription.nodesTotal} nodes`}</StatusBadge>
              <OwnerChip team={subscription.ownerTeam} />
            </Group>
            <Button
              size="xs"
              variant="default"
              component={Link}
              to={`/clusters/${clusterId}/subscriptions/${encodeURIComponent(subscriptionRef(subscription))}/messages`}
              onClick={onClose}
            >
              Browse messages
            </Button>
          </Group>

          <SubscriptionLifecycleActions clusterId={clusterId} subscription={subscription} onClose={onClose} />

          <Section title="Per node" headingLevel={3}>
            <DataTable
              variant="static"
              label={`${subscription.name} per node`}
              columns={nodeColumns}
              data={subscription.perNode}
              rowKey={nodeKey}
              height={{ maxRows: 8 }}
              empty={
                <EmptyState
                  kind="empty"
                  title="No node reported this subscription"
                  description="The last scrape returned no per-node figures for it."
                />
              }
            />
          </Section>

          {panels.map(({ id, Component }) => (
            <Component
              key={id}
              clusterId={clusterId}
              subscription={{ id: subscription.id, kind: subscription.kind, name: subscription.name }}
              onClose={onClose}
            />
          ))}
        </Stack>
      ) : null}
    </Drawer>
  );
}
