import { resourceId } from '../kernel/api/resourceId.ts';

/** The kinds the Artemis driver offers, as a cluster view lists them. */
export const ARTEMIS_KINDS = [
  {
    id: 'artemis.queue',
    resourceClass: 'SUBSCRIPTION',
    partitioned: false,
    label: { singular: 'Queue', plural: 'Queues' },
    verbs: [
      'browse',
      'create',
      'delete',
      'send',
      'update',
      'pause',
      'resume',
      'purge',
      'move-messages',
      'retry-messages',
      'delete-messages',
      'reset-counters',
      'expire',
    ],
  },
  {
    id: 'artemis.address',
    resourceClass: 'DESTINATION',
    partitioned: false,
    label: { singular: 'Address', plural: 'Addresses' },
    verbs: ['create', 'delete', 'close'],
  },
  {
    id: 'artemis.connection',
    resourceClass: 'CONNECTION',
    partitioned: false,
    label: { singular: 'Connection', plural: 'Connections' },
    verbs: ['close'],
  },
  {
    id: 'artemis.session',
    resourceClass: 'CHANNEL',
    partitioned: false,
    label: { singular: 'Session', plural: 'Sessions' },
    verbs: ['close'],
  },
  {
    id: 'artemis.consumer',
    resourceClass: 'CONSUMER',
    partitioned: false,
    label: { singular: 'Consumer', plural: 'Consumers' },
    verbs: ['close'],
  },
  {
    id: 'artemis.producer',
    resourceClass: 'PRODUCER',
    partitioned: false,
    label: { singular: 'Producer', plural: 'Producers' },
    verbs: [],
  },
  {
    id: 'artemis.divert',
    resourceClass: 'ROUTE',
    partitioned: false,
    label: { singular: 'Divert', plural: 'Diverts' },
    verbs: ['create', 'delete'],
  },
  {
    id: 'artemis.bridge',
    resourceClass: 'ROUTE',
    partitioned: false,
    label: { singular: 'Bridge', plural: 'Bridges' },
    verbs: [],
  },
];

/** The kinds the Kafka driver offers, labelled as its kinds declare them. */
export const KAFKA_KINDS = [
  {
    id: 'kafka.topic',
    resourceClass: 'DESTINATION',
    partitioned: true,
    label: { singular: 'Topic', plural: 'Topics' },
    verbs: ['create', 'delete', 'update', 'purge', 'truncate'],
  },
  {
    id: 'kafka.partition',
    resourceClass: 'CHANNEL',
    partitioned: false,
    label: { singular: 'Partition', plural: 'Partitions' },
    verbs: [],
  },
  {
    id: 'kafka.group',
    resourceClass: 'CONSUMER',
    partitioned: false,
    label: { singular: 'Group', plural: 'Groups' },
    verbs: ['delete'],
  },
  {
    id: 'kafka.group-topic',
    resourceClass: 'SUBSCRIPTION',
    partitioned: true,
    label: { singular: 'Group on topic', plural: 'Groups on topics' },
    verbs: ['browse', 'purge'],
  },
  {
    id: 'kafka.acl',
    resourceClass: 'ROUTE',
    partitioned: false,
    label: { singular: 'ACL', plural: 'ACLs' },
    verbs: ['create', 'delete'],
  },
  {
    id: 'kafka.transactional-id',
    resourceClass: 'PRODUCER',
    partitioned: false,
    label: { singular: 'Transactional ID', plural: 'Transactional IDs' },
    verbs: [],
  },
];

/** The system a Kafka cluster reports. */
export const KAFKA_SYSTEM = { id: 'kafka', name: 'Apache Kafka' };

/** The kinds the RabbitMQ driver offers, labelled and verbed as its kinds declare them. */
export const RABBITMQ_KINDS = [
  {
    id: 'rabbitmq.vhost',
    resourceClass: 'NAMESPACE',
    partitioned: false,
    label: { singular: 'Virtual host', plural: 'Virtual hosts' },
    verbs: ['create', 'delete', 'get', 'list', 'update'],
  },
  {
    id: 'rabbitmq.exchange',
    resourceClass: 'DESTINATION',
    partitioned: false,
    label: { singular: 'Exchange', plural: 'Exchanges' },
    verbs: ['create', 'delete', 'get', 'list', 'send'],
  },
  {
    id: 'rabbitmq.classic-queue',
    resourceClass: 'SUBSCRIPTION',
    partitioned: false,
    label: { singular: 'Classic queue', plural: 'Classic queues' },
    verbs: ['create', 'delete', 'get', 'list', 'purge', 'receive', 'send'],
  },
  {
    id: 'rabbitmq.quorum-queue',
    resourceClass: 'SUBSCRIPTION',
    partitioned: false,
    label: { singular: 'Quorum queue', plural: 'Quorum queues' },
    verbs: ['create', 'delete', 'get', 'list', 'purge', 'receive', 'send'],
  },
  {
    id: 'rabbitmq.stream',
    resourceClass: 'SUBSCRIPTION',
    partitioned: true,
    label: { singular: 'Stream', plural: 'Streams' },
    verbs: ['browse', 'create', 'delete', 'get', 'list', 'send'],
  },
  {
    id: 'rabbitmq.binding',
    resourceClass: 'ROUTE',
    partitioned: false,
    label: { singular: 'Binding', plural: 'Bindings' },
    verbs: ['create', 'delete', 'get', 'list'],
  },
  {
    id: 'rabbitmq.exchange-binding',
    resourceClass: 'ROUTE',
    partitioned: false,
    label: { singular: 'Exchange binding', plural: 'Exchange bindings' },
    verbs: ['create', 'delete', 'get', 'list'],
  },
  {
    id: 'rabbitmq.shovel',
    resourceClass: 'ROUTE',
    partitioned: false,
    label: { singular: 'Shovel', plural: 'Shovels' },
    verbs: ['delete', 'get', 'list'],
  },
  {
    id: 'rabbitmq.federation-link',
    resourceClass: 'ROUTE',
    partitioned: false,
    label: { singular: 'Federation link', plural: 'Federation links' },
    verbs: ['get', 'list'],
  },
  {
    id: 'rabbitmq.policy',
    resourceClass: 'POLICY',
    partitioned: false,
    label: { singular: 'Policy', plural: 'Policies' },
    verbs: ['create', 'delete', 'get', 'list', 'update'],
  },
  {
    id: 'rabbitmq.operator-policy',
    resourceClass: 'POLICY',
    partitioned: false,
    label: { singular: 'Operator policy', plural: 'Operator policies' },
    verbs: ['create', 'delete', 'get', 'list', 'update'],
  },
  {
    id: 'rabbitmq.permission',
    resourceClass: 'ACCESS_RULE',
    partitioned: false,
    label: { singular: 'Permission', plural: 'Permissions' },
    verbs: ['create', 'delete', 'get', 'list', 'update'],
  },
  {
    id: 'rabbitmq.topic-permission',
    resourceClass: 'ACCESS_RULE',
    partitioned: false,
    label: { singular: 'Topic permission', plural: 'Topic permissions' },
    verbs: ['create', 'delete', 'get', 'list', 'update'],
  },
  {
    id: 'rabbitmq.user',
    resourceClass: 'PRINCIPAL',
    partitioned: false,
    label: { singular: 'User', plural: 'Users' },
    verbs: ['get', 'list'],
  },
  {
    id: 'rabbitmq.connection',
    resourceClass: 'CONNECTION',
    partitioned: false,
    label: { singular: 'Connection', plural: 'Connections' },
    verbs: ['close', 'get', 'list'],
  },
  {
    id: 'rabbitmq.channel',
    resourceClass: 'CHANNEL',
    partitioned: false,
    label: { singular: 'Channel', plural: 'Channels' },
    verbs: ['get', 'list'],
  },
  {
    id: 'rabbitmq.consumer',
    resourceClass: 'CONSUMER',
    partitioned: false,
    label: { singular: 'Consumer', plural: 'Consumers' },
    verbs: ['list'],
  },
];

/** The system a RabbitMQ cluster reports. */
export const RABBITMQ_SYSTEM = { id: 'rabbitmq', name: 'RabbitMQ' };

/** The systems `GET /systems` lists when every driver is loaded. */
export const LOADED_SYSTEMS = [
  { id: 'artemis', name: 'Apache ActiveMQ Artemis', supportedVersions: '[2.30.0,2.60.0]', connection: 'jolokia-seeds' },
  { id: 'kafka', name: 'Apache Kafka', supportedVersions: '[2.8.0,)', connection: 'bootstrap' },
  { id: 'rabbitmq', name: 'RabbitMQ', supportedVersions: '[3.13.0,)', connection: 'management-api' },
];

/** The system an Artemis cluster reports, which decides which system-only views it is offered. */
export const ARTEMIS_SYSTEM = { id: 'artemis', name: 'Artemis' };

export const QUEUE_KIND = 'artemis.queue';
export const ADDRESS_KIND = 'artemis.address';
export const subscriptionId = (name: string): string => resourceId(QUEUE_KIND, name);
export const destinationId = (name: string): string => resourceId(ADDRESS_KIND, name);

/** The identity and binding fields of a queue as the API lists a subscription. */
export function subscriptionIdentity(name: string, address = name, routingType = 'ANYCAST') {
  return {
    id: subscriptionId(name),
    kind: QUEUE_KIND,
    kindLabel: { singular: 'Queue', plural: 'Queues' },
    name,
    destination: destinationId(address),
    nativeAttributes: { routingType },
  };
}

/** The identity fields of an address as the API lists a destination. */
export function destinationIdentity(name: string) {
  return { id: destinationId(name), kind: ADDRESS_KIND, kindLabel: { singular: 'Address', plural: 'Addresses' }, name };
}
