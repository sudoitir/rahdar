import { createElement } from 'react';

import { StatusBadge } from '../../ui/StatusBadge.tsx';
import type { Column } from '../../ui/table/index.ts';
import type { SqlIndexSubscriptionView } from './api.ts';
import { DeleteCell, HeldCell, SubscriptionsCell, StateCell } from './subscriptionCells.tsx';

const retentionWords = (days: number) => `${days} day${days === 1 ? '' : 's'}`;

/**
 * The columns of the subscriptions table: what each covers, how it is run, what it holds, for how long,
 * whether it is on, and how to be rid of it. The switch and the delete are gated on the authority the
 * subscription needs: capture changes broker routing, which is a different permission from sampling.
 */
export function subscriptionColumns({
  clusterId,
  canWrite,
  canCapture,
}: Readonly<{ clusterId: string; canWrite: boolean; canCapture: boolean }>): Column<SqlIndexSubscriptionView>[] {
  const mayChange = (s: SqlIndexSubscriptionView) => (s.mode === 'CAPTURE' ? canCapture : canWrite);
  return [
    {
      id: 'subscriptions',
      header: 'Subscriptions',
      accessor: (s) => s.subscriptionPattern ?? '',
      cell: (s) => createElement(SubscriptionsCell, { subscription: s }),
      kind: 'text',
      priority: 'essential',
      wrap: true,
    },
    {
      id: 'mode',
      header: 'Mode',
      accessor: (s) => (s.mode === 'CAPTURE' ? 'Capture' : 'Sample'),
      cell: (s) => createElement(StatusBadge, null, s.mode === 'CAPTURE' ? 'Capture' : 'Sample'),
      kind: 'status',
      badge: true,
      priority: 'high',
    },
    {
      id: 'held',
      header: 'Held',
      accessor: (s) => s.messagesHeld ?? 0,
      cell: (s) => createElement(HeldCell, { subscription: s }),
      kind: 'number',
      priority: 'high',
      wrap: true,
    },
    {
      id: 'retention',
      header: 'Retention',
      accessor: (s) => retentionWords(s.retentionDays ?? 0),
      kind: 'number',
      priority: 'low',
    },
    {
      id: 'state',
      header: 'State',
      accessor: (s) => (s.enabled ? 'Capturing' : 'Paused'),
      cell: (s) => createElement(StateCell, { clusterId, subscription: s, canWrite: mayChange(s) }),
      kind: 'status',
      priority: 'essential',
    },
    {
      id: 'delete',
      header: 'Delete',
      accessor: () => '',
      cell: (s) => createElement(DeleteCell, { clusterId, subscription: s, canWrite: mayChange(s) }),
      kind: 'status',
      priority: 'essential',
    },
  ];
}
