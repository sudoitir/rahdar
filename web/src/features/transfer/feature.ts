import { IconTransfer } from '@tabler/icons-react';
import { createRoute } from '@tanstack/react-router';

import { CONTRACT, defineFeature } from '../../kernel/feature.ts';
import { clusterRoute } from '../../kernel/routing/roots.ts';
import { lazyFeatureView } from '../../kernel/routing/lazy.tsx';
import { keys } from './api.ts';
import { TransferActions } from './TransferActions.tsx';
import { TransferSubscriptionMessages } from './rowActions.tsx';

const runsRoute = createRoute({
  getParentRoute: () => clusterRoute,
  path: 'transfers',
  component: lazyFeatureView('transfer', () => import('./TransfersView.tsx'), 'TransfersView'),
});

const runRoute = createRoute({
  getParentRoute: () => clusterRoute,
  path: 'transfers/$runId',
  component: lazyFeatureView('transfer', () => import('./TransferRunView.tsx'), 'TransferRunView'),
});

/** Move or copy messages to a subscription on another node or another cluster, as a resumable run (ADR-0097). */
export const transferFeature = defineFeature({
  contract: CONTRACT,
  id: 'transfer',
  routes: { cluster: [runsRoute, runRoute] },
  nav: [
    {
      group: 'messaging',
      order: 30,
      label: 'Transfers',
      icon: IconTransfer,
      path: 'transfers',
      hotkey: 'x',
      permission: 'message:read',
    },
  ],
  slots: {
    'subscription.actions': [
      { id: 'transfer.subscription', order: 30, section: 'operate', Component: TransferSubscriptionMessages },
    ],
    'messages.selection': [{ id: 'transfer-actions', order: 10, Component: TransferActions }],
  },
  streamTopics: {
    transfer: ({ clusterId, invalidate }) => invalidate(keys.all(clusterId)),
  },
});
