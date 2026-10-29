import { Group, Text } from '@mantine/core';
import { Link } from '@tanstack/react-router';

import linkClasses from '../../ui/InlineLink.module.css';
import { StatusBadge } from '../../ui/StatusBadge.tsx';
import { MiddleTruncate } from '../../ui/table/index.ts';
import type { MessageSummaryView } from './api.ts';

/** The body's preview, and that it is cut short: a preview that looks complete misleads. */
export function BodyPreview({ message: m }: Readonly<{ message: MessageSummaryView }>) {
  if (m.tombstone) {
    return (
      <Text size="xs" c="dimmed">
        Tombstone (no value)
      </Text>
    );
  }
  return (
    <Group gap="xs" wrap="nowrap">
      <Text size="xs" truncate>
        {m.bodyPreview ?? (
          <Text span c="dimmed">
            (empty)
          </Text>
        )}
      </Text>
      {m.bodyTruncated ? <StatusBadge tone="warning">truncated</StatusBadge> : null}
    </Group>
  );
}

/** A dead-letter subscription's name, shortened in the middle so its distinguishing end stays in view, as a link to its messages. */
export function SubscriptionLink({
  clusterId,
  subscriptionName,
}: Readonly<{ clusterId: string; subscriptionName: string }>) {
  return (
    <Link
      to={`/clusters/${clusterId}/subscriptions/${encodeURIComponent(subscriptionName)}/messages`}
      className={linkClasses.link}
    >
      <MiddleTruncate text={subscriptionName} />
    </Link>
  );
}
