import { IconEraser } from '@tabler/icons-react';

import { useCluster } from '../clusters/index.ts';
import { useResourceGate } from '../../kernel/auth/useResourceGate.ts';
import type { ActionProps, SubscriptionTarget } from '../../kernel/actions/types.ts';
import { ActionMenuItem } from '../../ui/ActionMenuItem.tsx';
import { BulkPreviewDialog } from './BulkPreviewDialog.tsx';
import { OPERATIONS } from './words.ts';

/**
 * "Purge messages…" on a subscription's row. A cluster-wide purge with a per-node outcome is a bulk run of
 * one subscription (ADR-0093): the same preview, blast radius, cap and audit, confirmed by pressing and holding (ADR-0186).
 * Accepting it opens the run, so focus is not handed back to a row the operator has left.
 */
export function PurgeSubscription({ clusterId, target, host }: Readonly<ActionProps<SubscriptionTarget>>) {
  const cluster = useCluster(clusterId);
  const op = OPERATIONS.PURGE;
  const { hidden, verdict: gate } = useResourceGate({
    clusterId,
    noun: 'subscription',
    permission: op.permission,
    label: op.permissionLabel,
    resource: target.snapshot,
    kind: { id: target.kind, verb: 'purge' },
    capability: cluster.data?.capabilities.managementWrite,
    pending: cluster.isPending,
  });
  if (hidden) return null;
  return (
    <ActionMenuItem
      label="Purge messages…"
      icon={<IconEraser size="1rem" aria-hidden />}
      tone="danger"
      verdict={gate}
      onExplain={(verdict) => host.explain(verdict, 'purging this subscription')}
      onSelect={() =>
        host.open(BulkPreviewDialog, {
          clusterId,
          operation: 'PURGE',
          selection: { kind: 'ids', ids: [target.id] },
          onStarted: () => {},
        })
      }
    />
  );
}
