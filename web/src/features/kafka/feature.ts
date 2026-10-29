import { IconBuildingBroadcastTower, IconLock, IconUsersGroup, IconArrowsTransferUp } from '@tabler/icons-react';
import { createRoute } from '@tanstack/react-router';

import { CONTRACT, defineFeature } from '../../kernel/feature.ts';
import { lazyFeatureView } from '../../kernel/routing/lazy.tsx';
import { clusterRoute } from '../../kernel/routing/roots.ts';
import { TopicConfiguration } from './rowActions.tsx';
import { validateAclSearch, validateGroupsSearch } from './search.ts';

const view = <K extends string>(load: () => Promise<Record<K, () => React.ReactNode>>, name: K) =>
  lazyFeatureView('kafka', load, name, 'kafka');

const groupsRoute = createRoute({
  getParentRoute: () => clusterRoute,
  path: 'kafka/groups',
  component: view(() => import('./GroupsView.tsx'), 'GroupsView'),
  validateSearch: validateGroupsSearch,
});

const groupRoute = createRoute({
  getParentRoute: () => clusterRoute,
  path: 'kafka/groups/$group',
  component: view(() => import('./GroupView.tsx'), 'GroupView'),
});

const topicConfigRoute = createRoute({
  getParentRoute: () => clusterRoute,
  path: 'kafka/topics/$topic/config',
  component: view(() => import('./TopicConfigView.tsx'), 'TopicConfigView'),
});

const aclsRoute = createRoute({
  getParentRoute: () => clusterRoute,
  path: 'kafka/acls',
  component: view(() => import('./AclsView.tsx'), 'AclsView'),
  validateSearch: validateAclSearch,
});

const transactionsRoute = createRoute({
  getParentRoute: () => clusterRoute,
  path: 'kafka/transactions',
  component: view(() => import('./TransactionsView.tsx'), 'TransactionsView'),
});

const brokersRoute = createRoute({
  getParentRoute: () => clusterRoute,
  path: 'kafka/brokers',
  component: view(() => import('./BrokersView.tsx'), 'BrokersView'),
});

/**
 * Apache Kafka (ADR-0216): consumer groups with their offsets and the reset of them, topic configuration,
 * ACLs, transactions and brokers. Every view is for a Kafka cluster; another system's cluster answers its
 * addresses with the not-found page.
 */
export const kafkaFeature = defineFeature({
  contract: CONTRACT,
  id: 'kafka',
  routes: { cluster: [groupsRoute, groupRoute, topicConfigRoute, aclsRoute, transactionsRoute, brokersRoute] },
  nav: [
    {
      group: 'messaging',
      order: 30,
      label: 'Consumer groups',
      icon: IconUsersGroup,
      path: 'kafka/groups',
      system: 'kafka',
      permission: 'subscription:read',
    },
    {
      group: 'activity',
      order: 30,
      label: 'Transactions',
      icon: IconArrowsTransferUp,
      path: 'kafka/transactions',
      system: 'kafka',
      permission: 'transaction:read',
    },
    {
      group: 'observe',
      order: 40,
      label: 'Brokers',
      icon: IconBuildingBroadcastTower,
      path: 'kafka/brokers',
      system: 'kafka',
      permission: 'cluster:read',
    },
    {
      group: 'configuration',
      order: 40,
      label: 'ACLs',
      icon: IconLock,
      path: 'kafka/acls',
      system: 'kafka',
      permission: 'access-rule:read',
    },
  ],
  slots: {
    'destination.actions': [
      { id: 'kafka.topic.configuration', order: 15, section: 'open', Component: TopicConfiguration },
    ],
  },
});
