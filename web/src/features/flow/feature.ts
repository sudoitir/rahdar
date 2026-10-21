import { IconChartSankey } from '@tabler/icons-react';
import { createRoute } from '@tanstack/react-router';

import { CONTRACT, defineFeature } from '../../kernel/feature.ts';
import { clusterRoute } from '../../kernel/routing/roots.ts';
import { lazyFeatureView } from '../../kernel/routing/lazy.tsx';
import { validateFlowSearch } from './flowSearch.ts';
import {
  DestinationInFlow,
  ClientConnections,
  ConnectionInFlow,
  RouteInFlow,
  FocusClient,
  SubscriptionInFlow,
} from './rowActions.tsx';

const flowRoute = createRoute({
  getParentRoute: () => clusterRoute,
  path: 'flow',
  component: lazyFeatureView('flow', () => import('./FlowView.tsx'), 'FlowView'),
  validateSearch: validateFlowSearch,
});

/** Client connectivity and message flow: who sends where, how it routes, who consumes it. */
export const flowFeature = defineFeature({
  contract: CONTRACT,
  id: 'flow',
  routes: { cluster: [flowRoute] },
  nav: [
    {
      group: 'observe',
      order: 15,
      label: 'Flow',
      icon: IconChartSankey,
      path: 'flow',
      hotkey: 'f',
      permission: 'cluster:read',
    },
  ],
  slots: {
    'subscription.actions': [{ id: 'flow.subscription', order: 40, section: 'open', Component: SubscriptionInFlow }],
    'destination.actions': [{ id: 'flow.destination', order: 40, section: 'open', Component: DestinationInFlow }],
    'connection.actions': [{ id: 'flow.connection', order: 40, section: 'open', Component: ConnectionInFlow }],
    'route.actions': [{ id: 'flow.route', order: 40, section: 'open', Component: RouteInFlow }],
    'client.actions': [
      { id: 'flow.client.focus', order: 10, section: 'open', Component: FocusClient },
      { id: 'flow.client.connections', order: 20, section: 'open', Component: ClientConnections },
    ],
  },
  streamTopics: {
    flow: ({ clusterId, invalidate }) => invalidate(['clusters', clusterId, 'flow']),
  },
});
