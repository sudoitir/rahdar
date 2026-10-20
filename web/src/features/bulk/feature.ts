import { IconStack2 } from '@tabler/icons-react';
import { createRoute } from '@tanstack/react-router';

import { CONTRACT, defineFeature } from '../../kernel/feature.ts';
import { clusterRoute } from '../../kernel/routing/roots.ts';
import { lazyFeatureView } from '../../kernel/routing/lazy.tsx';
import { keys } from './api.ts';
import { BulkActionBar } from './BulkActionBar.tsx';
import { PurgeSubscription } from './rowActions.tsx';

const runsRoute = createRoute({
  getParentRoute: () => clusterRoute,
  path: 'bulk',
  component: lazyFeatureView('bulk', () => import('./BulkRunsView.tsx'), 'BulkRunsView'),
});

const runRoute = createRoute({
  getParentRoute: () => clusterRoute,
  path: 'bulk/$runId',
  component: lazyFeatureView('bulk', () => import('./BulkRunView.tsx'), 'BulkRunView'),
});

/** Pause, resume, purge or delete many subscriptions as one previewed, persisted run (ADR-0093). */
export const bulkFeature = defineFeature({
  contract: CONTRACT,
  id: 'bulk',
  routes: { cluster: [runsRoute, runRoute] },
  nav: [
    {
      group: 'activity',
      order: 15,
      label: 'Bulk runs',
      icon: IconStack2,
      path: 'bulk',
      hotkey: 'b',
      permission: 'cluster:read',
    },
  ],
  slots: {
    'subscription.actions': [{ id: 'bulk.purge', order: 5, section: 'destroy', Component: PurgeSubscription }],
    'subscriptions.selection': [{ id: 'bulk-actions', order: 10, Component: BulkActionBar }],
  },
  streamTopics: {
    bulk: ({ clusterId, invalidate }) => invalidate(keys.all(clusterId)),
  },
});
