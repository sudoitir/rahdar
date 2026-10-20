import { IconChartLine } from '@tabler/icons-react';
import { useNavigate } from '@tanstack/react-router';

import type { ActionProps, SubscriptionTarget } from '../../kernel/actions/types.ts';
import { clusterHref } from '../../kernel/routing/href.ts';
import { ActionMenuItem } from '../../ui/ActionMenuItem.tsx';

/** "Open history" on a subscription's row: the metrics view scoped to that subscription. */
export function OpenSubscriptionHistory({ clusterId, target }: Readonly<ActionProps<SubscriptionTarget>>) {
  const navigate = useNavigate();
  return (
    <ActionMenuItem
      label="Open history"
      icon={<IconChartLine size="1rem" aria-hidden />}
      href={clusterHref(clusterId, 'metrics', { subject: target.name })}
      onSelect={() => navigate({ to: `/clusters/${clusterId}/metrics`, search: { subject: target.name } as never })}
    />
  );
}
