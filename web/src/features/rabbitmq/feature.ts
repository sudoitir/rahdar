import {
  IconAdjustmentsHorizontal,
  IconArrowsShuffle,
  IconBoxMultiple,
  IconKey,
  IconLink,
  IconServer,
} from '@tabler/icons-react';
import { createRoute } from '@tanstack/react-router';

import { CONTRACT, defineFeature } from '../../kernel/feature.ts';
import { lazyFeatureView } from '../../kernel/routing/lazy.tsx';
import { clusterRoute } from '../../kernel/routing/roots.ts';
import { CreateExchangeForm } from './CreateExchangeForm.tsx';
import { CreateQueueForm } from './CreateQueueForm.tsx';
import { ExchangeBindings, PublishToExchange } from './PublishToExchange.tsx';
import { QueuePanel } from './QueuePanel.tsx';
import { validateFilterSearch } from './search.ts';

const view = <K extends string>(load: () => Promise<Record<K, () => React.ReactNode>>, name: K) =>
  lazyFeatureView('rabbitmq', load, name, 'rabbitmq');

const nodesRoute = createRoute({
  getParentRoute: () => clusterRoute,
  path: 'rabbitmq/nodes',
  component: view(() => import('./NodesView.tsx'), 'NodesView'),
});

const bindingsRoute = createRoute({
  getParentRoute: () => clusterRoute,
  path: 'rabbitmq/bindings',
  component: view(() => import('./BindingsView.tsx'), 'BindingsView'),
  validateSearch: validateFilterSearch,
});

const linksRoute = createRoute({
  getParentRoute: () => clusterRoute,
  path: 'rabbitmq/links',
  component: view(() => import('./LinksView.tsx'), 'LinksView'),
  validateSearch: validateFilterSearch,
});

const vhostsRoute = createRoute({
  getParentRoute: () => clusterRoute,
  path: 'rabbitmq/vhosts',
  component: view(() => import('./VhostsView.tsx'), 'VhostsView'),
});

const policiesRoute = createRoute({
  getParentRoute: () => clusterRoute,
  path: 'rabbitmq/policies',
  component: view(() => import('./PoliciesView.tsx'), 'PoliciesView'),
  validateSearch: validateFilterSearch,
});

const permissionsRoute = createRoute({
  getParentRoute: () => clusterRoute,
  path: 'rabbitmq/permissions',
  component: view(() => import('./PermissionsView.tsx'), 'PermissionsView'),
  validateSearch: validateFilterSearch,
});

/**
 * RabbitMQ (ADR-0222): the nodes with their alarms and partitions, bindings, shovels and federation links, virtual
 * hosts, policies and permissions, and the queue, publish and create contributions to the shared screens. Every
 * change is previewed first and every destructive one is held. Another system's cluster answers these addresses
 * with the not-found page.
 */
export const rabbitmqFeature = defineFeature({
  contract: CONTRACT,
  id: 'rabbitmq',
  routes: { cluster: [nodesRoute, bindingsRoute, linksRoute, vhostsRoute, policiesRoute, permissionsRoute] },
  nav: [
    {
      group: 'observe',
      order: 10,
      label: 'Nodes',
      icon: IconServer,
      path: 'rabbitmq/nodes',
      system: 'rabbitmq',
      permission: 'cluster:read',
    },
    {
      group: 'messaging',
      order: 30,
      label: 'Bindings',
      icon: IconLink,
      path: 'rabbitmq/bindings',
      system: 'rabbitmq',
      permission: 'destination:read',
    },
    {
      group: 'messaging',
      order: 35,
      label: 'Shovels and federation',
      icon: IconArrowsShuffle,
      path: 'rabbitmq/links',
      system: 'rabbitmq',
      permission: 'destination:read',
    },
    {
      group: 'configuration',
      order: 20,
      label: 'Virtual hosts',
      icon: IconBoxMultiple,
      path: 'rabbitmq/vhosts',
      system: 'rabbitmq',
      permission: 'namespace:read',
    },
    {
      group: 'configuration',
      order: 30,
      label: 'Policies',
      icon: IconAdjustmentsHorizontal,
      path: 'rabbitmq/policies',
      system: 'rabbitmq',
      permission: 'policy:read',
    },
    {
      group: 'configuration',
      order: 40,
      label: 'Permissions',
      icon: IconKey,
      path: 'rabbitmq/permissions',
      system: 'rabbitmq',
      permission: 'access-rule:read',
    },
  ],
  slots: {
    'subscription.detail.panels': [{ id: 'rabbitmq.queue', order: 20, system: 'rabbitmq', Component: QueuePanel }],
    'destination.actions': [
      { id: 'rabbitmq.publish', order: 10, section: 'open', system: 'rabbitmq', Component: PublishToExchange },
      { id: 'rabbitmq.bindings', order: 20, section: 'open', system: 'rabbitmq', Component: ExchangeBindings },
    ],
    'subscriptions.create': [
      { id: 'rabbitmq.queue.create', order: 10, system: 'rabbitmq', Component: CreateQueueForm },
    ],
    'destinations.create': [
      { id: 'rabbitmq.exchange.create', order: 10, system: 'rabbitmq', Component: CreateExchangeForm },
    ],
  },
});
