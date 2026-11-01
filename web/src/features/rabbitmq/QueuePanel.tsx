import type { components } from '../../kernel/api/schema.d.ts';
import type { ResourceRef } from '../../kernel/api/resourceId.ts';
import { Stack, Text } from '@mantine/core';

import { DescriptionList } from '../../ui/DescriptionList.tsx';
import { ErrorState } from '../../ui/ErrorState.tsx';
import { LoadingState } from '../../ui/LoadingState.tsx';
import { Section } from '../../ui/Section.tsx';
import { StatusBadge } from '../../ui/StatusBadge.tsx';
import { useQueueAttributes } from './api.ts';
import { QUEUE_TYPE_MEANING, RABBITMQ_QUEUE_KINDS, type QueueType } from './words.ts';

const ARGUMENT_PREFIX = 'rabbitmq.argument.';

const TYPE_OF_KIND: Record<string, QueueType> = {
  'rabbitmq.classic-queue': 'classic',
  'rabbitmq.quorum-queue': 'quorum',
  'rabbitmq.stream': 'stream',
};

type Placement = NonNullable<components['schemas']['SubscriptionConfiguration']['placements']>[number];

/** The replicas of a replicated queue, each marked with its role, and in sync or not where the broker says. */
function Members({ placements }: Readonly<{ placements: Placement[] }>) {
  return (
    <Stack gap={2} component="ul" m={0} pl={0} style={{ listStyle: 'none' }} aria-label="Members">
      {placements.map((m) => (
        <Text key={m.node} component="li" size="sm">
          {m.node} {m.role === 'leader' ? <StatusBadge tone="info">Leader</StatusBadge> : null}{' '}
          {m.inSync == null ? null : (
            <StatusBadge tone={m.inSync ? 'neutral' : 'warning'}>{m.inSync ? 'In sync' : 'Not in sync'}</StatusBadge>
          )}
        </Text>
      ))}
    </Stack>
  );
}

function Panel({ clusterId, subscription }: Readonly<{ clusterId: string; subscription: ResourceRef }>) {
  const queue = useQueueAttributes(clusterId, subscription.id);
  if (queue.isError) return <ErrorState variant="inline" error={queue.error} onRetry={() => void queue.refetch()} />;
  if (!queue.data) return <LoadingState label="Loading the queue" blockSize="6rem" />;
  const attrs = queue.data.nativeAttributes ?? {};
  const type = TYPE_OF_KIND[subscription.kind];
  const placements = queue.data.placements ?? [];
  const leader = attrs['rabbitmq.leader'] ?? placements.find((m) => m.role === 'leader')?.node;
  const args = Object.entries(attrs).filter(([k]) => k.startsWith(ARGUMENT_PREFIX));
  const items = [
    { term: 'Type', value: `${type.charAt(0).toUpperCase()}${type.slice(1)}: ${QUEUE_TYPE_MEANING[type]}` },
    // The broker names the policy only when it reports one; an unreported policy is not claimed absent.
    ...(attrs['rabbitmq.policy'] ? [{ term: 'Effective policy', value: attrs['rabbitmq.policy'] }] : []),
    ...(leader ? [{ term: 'Leader', value: leader }] : []),
    ...(attrs['rabbitmq.delivery_limit'] ? [{ term: 'Delivery limit', value: attrs['rabbitmq.delivery_limit'] }] : []),
    ...(attrs['rabbitmq.max_age'] ? [{ term: 'Maximum age', value: attrs['rabbitmq.max_age'] }] : []),
    ...(attrs['rabbitmq.committed_offset']
      ? [{ term: 'Committed offset', value: attrs['rabbitmq.committed_offset'] }]
      : []),
    ...(attrs['rabbitmq.first_timestamp']
      ? [{ term: 'First message at', value: attrs['rabbitmq.first_timestamp'] }]
      : []),
  ];
  return (
    <Stack gap="md">
      <DescriptionList label="Queue" columns={1} items={items} />
      {placements.length > 0 ? (
        <Section title="Members">
          <Members placements={placements} />
        </Section>
      ) : null}
      <Section title="Arguments">
        {args.length === 0 ? (
          <Text size="sm" c="dimmed">
            No arguments. The queue takes its settings from the policy, if any.
          </Text>
        ) : (
          <DescriptionList
            label="Queue arguments"
            columns={1}
            items={args.map(([k, v]) => ({ term: k.slice(ARGUMENT_PREFIX.length), value: v }))}
          />
        )}
      </Section>
    </Stack>
  );
}

/** A RabbitMQ queue's own figures in the subscription drawer (`subscription.detail.panels`). */
export function QueuePanel({
  clusterId,
  subscription,
}: Readonly<{ clusterId: string; subscription: ResourceRef; onClose: () => void }>) {
  if (!RABBITMQ_QUEUE_KINDS.includes(subscription.kind)) return null;
  return <Panel clusterId={clusterId} subscription={subscription} />;
}
