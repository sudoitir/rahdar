import {
  IconLock,
  IconSchema,
  IconStack2,
  IconUsersGroup,
  IconArrowsTransferUp,
  IconTopologyStar3,
} from '@tabler/icons-react';
import { createRoute } from '@tanstack/react-router';

import { CONTRACT, defineFeature } from '../../kernel/feature.ts';
import { lazyFeatureView } from '../../kernel/routing/lazy.tsx';
import { clusterRoute } from '../../kernel/routing/roots.ts';
import { CreateTopicForm } from './CreateTopicForm.tsx';
import { TopicConfiguration, TopicPartitions } from './rowActions.tsx';
import { DeleteTopic, IncreasePartitions } from './TopicLifecycleActions.tsx';
import {
  validateAclSearch,
  validateClusterSearch,
  validateGroupsSearch,
  validateReplicationSearch,
  validateSchemasSearch,
} from './search.ts';

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

const partitionsRoute = createRoute({
  getParentRoute: () => clusterRoute,
  path: 'kafka/topics/$topic',
  component: view(() => import('./PartitionsView.tsx'), 'PartitionsView'),
});

const replicationRoute = createRoute({
  getParentRoute: () => clusterRoute,
  path: 'kafka/replication',
  component: view(() => import('./ReplicationView.tsx'), 'ReplicationView'),
  validateSearch: validateReplicationSearch,
});

const brokerConfigRoute = createRoute({
  getParentRoute: () => clusterRoute,
  path: 'kafka/brokers/$broker/config',
  component: view(() => import('./BrokerConfigView.tsx'), 'BrokerConfigView'),
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

const clusterPageRoute = createRoute({
  getParentRoute: () => clusterRoute,
  path: 'kafka/cluster',
  component: view(() => import('./ClusterView.tsx'), 'ClusterView'),
  validateSearch: validateClusterSearch,
});

const schemasRoute = createRoute({
  getParentRoute: () => clusterRoute,
  path: 'kafka/schemas',
  component: view(() => import('./SchemasView.tsx'), 'SchemasView'),
  validateSearch: validateSchemasSearch,
});

const schemaSubjectRoute = createRoute({
  getParentRoute: () => clusterRoute,
  path: 'kafka/schemas/$subject',
  component: view(() => import('./SubjectView.tsx'), 'SubjectView'),
});

/**
 * Apache Kafka (ADR-0216): consumer groups with their offsets and the reset of them, topic and broker configuration,
 * ACLs, transactions, the cluster view, partition placement and replication. Every view is for a Kafka cluster;
 * another system's cluster answers its addresses with the not-found page.
 */
export const kafkaFeature = defineFeature({
  contract: CONTRACT,
  id: 'kafka',
  routes: {
    cluster: [
      groupsRoute,
      groupRoute,
      topicConfigRoute,
      partitionsRoute,
      aclsRoute,
      transactionsRoute,
      clusterPageRoute,
      brokerConfigRoute,
      replicationRoute,
      schemasRoute,
      schemaSubjectRoute,
    ],
  },
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
      order: 10,
      label: 'Cluster',
      icon: IconTopologyStar3,
      path: 'kafka/cluster',
      system: 'kafka',
      permission: 'cluster:read',
    },
    {
      group: 'observe',
      order: 12,
      label: 'Replication',
      icon: IconStack2,
      path: 'kafka/replication',
      system: 'kafka',
      permission: 'destination:read',
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
    {
      group: 'configuration',
      order: 50,
      label: 'Schemas',
      icon: IconSchema,
      path: 'kafka/schemas',
      system: 'kafka',
      permission: 'schema:read',
    },
  ],
  slots: {
    'destination.actions': [
      { id: 'kafka.topic.placement', order: 14, section: 'open', Component: TopicPartitions },
      { id: 'kafka.topic.configuration', order: 15, section: 'open', Component: TopicConfiguration },
      { id: 'kafka.topic.partitions', order: 16, section: 'operate', Component: IncreasePartitions },
      { id: 'kafka.topic.delete', order: 20, section: 'destroy', Component: DeleteTopic },
    ],
    'destinations.create': [{ id: 'kafka.topic.create', order: 10, system: 'kafka', Component: CreateTopicForm }],
  },
});
