import { IconAdjustments } from '@tabler/icons-react';
import { useNavigate } from '@tanstack/react-router';

import { useResourceGate } from '../../kernel/auth/useResourceGate.ts';
import type { ActionProps, DestinationTarget } from '../../kernel/actions/types.ts';
import { clusterHref } from '../../kernel/routing/href.ts';
import { ActionMenuItem } from '../../ui/ActionMenuItem.tsx';
import { KAFKA_TOPIC } from './words.ts';

/** "Configuration" on a topic's row: its settings, with where each value comes from. */
export function TopicConfiguration({ clusterId, target }: Readonly<ActionProps<DestinationTarget>>) {
  const navigate = useNavigate();
  const { hidden, verdict } = useResourceGate({
    clusterId,
    noun: 'destination',
    permission: 'destination:read',
    label: 'Read topic configuration',
    resource: target.snapshot,
  });
  if (target.kind !== KAFKA_TOPIC || hidden) return null;
  const path = `kafka/topics/${encodeURIComponent(target.name)}/config`;
  return (
    <ActionMenuItem
      label="Configuration"
      icon={<IconAdjustments size="1rem" aria-hidden />}
      verdict={verdict}
      href={clusterHref(clusterId, path)}
      onSelect={() => navigate({ to: `/clusters/${clusterId}/${path}` })}
    />
  );
}
