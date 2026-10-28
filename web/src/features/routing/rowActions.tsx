import { IconClipboard, IconTrash } from '@tabler/icons-react';

import type { ActionProps, RouteTarget } from '../../kernel/actions/types.ts';
import { ActionMenuItem } from '../../ui/ActionMenuItem.tsx';
import { DeleteDivertDialog } from './DivertActions.tsx';
import { useClusterKinds } from '../../kernel/shell/useClusterSystem.ts';
import { useDivertWriteGate } from './divertGate.ts';

export function CopyRouteName({ target, host }: Readonly<ActionProps<RouteTarget>>) {
  return (
    <ActionMenuItem
      label="Copy route name"
      icon={<IconClipboard size={16} aria-hidden />}
      onSelect={() => host.copy(target.name, 'route name')}
    />
  );
}

/**
 * "Delete divert…" on a route of a kind its system lets Rahdar delete (a divert); no other kind of route offers it. A capture divert is
 * Rahdar's own and reconciliation would put it back, so it is not deletable here, and the item says where
 * it is managed instead.
 */
export function DeleteDivert({ clusterId, target, host }: Readonly<ActionProps<RouteTarget>>) {
  const gate = useDivertWriteGate(clusterId);
  const divert = target.snapshot;
  const kinds = useClusterKinds(clusterId);
  // Hooks first: only a kind the cluster's system lets Rahdar delete offers the item.
  if (!kinds?.kinds?.find((k) => k.id === target.kind)?.verbs.includes('delete')) return null;
  const verdict =
    divert?.owner === 'MESSAGE_CAPTURE'
      ? ({
          kind: 'blocked',
          reason:
            'This divert belongs to a message capture subscription. Delete the subscription in Settings → Message index; deleting the divert here would be undone on its next reconciliation.',
        } as const)
      : gate;
  return (
    <ActionMenuItem
      label="Delete divert…"
      icon={<IconTrash size={16} aria-hidden />}
      tone="danger"
      verdict={verdict}
      onExplain={(v) => host.explain(v, 'deleting this divert')}
      onSelect={() => divert && host.open(DeleteDivertDialog, { clusterId, divert })}
    />
  );
}
