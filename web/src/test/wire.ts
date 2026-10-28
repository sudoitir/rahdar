import { resourceId } from '../kernel/api/resourceId.ts';

/** The kinds the Artemis driver offers, as a cluster view lists them. */
export const ARTEMIS_KINDS = [
  {
    id: 'artemis.queue',
    resourceClass: 'SUBSCRIPTION',
    label: { singular: 'Queue', plural: 'Queues' },
    verbs: [
      'create',
      'delete',
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
    label: { singular: 'Address', plural: 'Addresses' },
    verbs: ['create', 'delete', 'close'],
  },
  {
    id: 'artemis.connection',
    resourceClass: 'CONNECTION',
    label: { singular: 'Connection', plural: 'Connections' },
    verbs: ['close'],
  },
  {
    id: 'artemis.session',
    resourceClass: 'CHANNEL',
    label: { singular: 'Session', plural: 'Sessions' },
    verbs: ['close'],
  },
  {
    id: 'artemis.consumer',
    resourceClass: 'CONSUMER',
    label: { singular: 'Consumer', plural: 'Consumers' },
    verbs: ['close'],
  },
  {
    id: 'artemis.producer',
    resourceClass: 'PRODUCER',
    label: { singular: 'Producer', plural: 'Producers' },
    verbs: [],
  },
  {
    id: 'artemis.divert',
    resourceClass: 'ROUTE',
    label: { singular: 'Divert', plural: 'Diverts' },
    verbs: ['create', 'delete'],
  },
  { id: 'artemis.bridge', resourceClass: 'ROUTE', label: { singular: 'Bridge', plural: 'Bridges' }, verbs: [] },
];

/** The kinds the Kafka driver offers, labelled as its kinds declare them. */
export const KAFKA_KINDS = [
  {
    id: 'kafka.topic',
    resourceClass: 'DESTINATION',
    label: { singular: 'Topic', plural: 'Topics' },
    verbs: ['create', 'delete', 'update', 'purge', 'truncate'],
  },
  {
    id: 'kafka.partition',
    resourceClass: 'CHANNEL',
    label: { singular: 'Partition', plural: 'Partitions' },
    verbs: [],
  },
  {
    id: 'kafka.group',
    resourceClass: 'CONSUMER',
    label: { singular: 'Group', plural: 'Groups' },
    verbs: ['delete'],
  },
  {
    id: 'kafka.group-topic',
    resourceClass: 'SUBSCRIPTION',
    label: { singular: 'Group on topic', plural: 'Groups on topics' },
    verbs: ['purge'],
  },
  { id: 'kafka.acl', resourceClass: 'ROUTE', label: { singular: 'ACL', plural: 'ACLs' }, verbs: ['create', 'delete'] },
  {
    id: 'kafka.transactional-id',
    resourceClass: 'PRODUCER',
    label: { singular: 'Transactional ID', plural: 'Transactional IDs' },
    verbs: [],
  },
];

/** The system a Kafka cluster reports. */
export const KAFKA_SYSTEM = { id: 'kafka', name: 'Apache Kafka' };

/** The systems `GET /systems` lists when both drivers are loaded. */
export const LOADED_SYSTEMS = [
  { id: 'artemis', name: 'Apache ActiveMQ Artemis', supportedVersions: '[2.30.0,2.60.0]', connection: 'jolokia-seeds' },
  { id: 'kafka', name: 'Apache Kafka', supportedVersions: '[3.0.0,)', connection: 'bootstrap' },
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
