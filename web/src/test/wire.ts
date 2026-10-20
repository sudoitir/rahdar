import { resourceId } from '../kernel/api/resourceId.ts';

/** The kinds the Artemis driver offers, as a cluster view lists them. */
export const ARTEMIS_KINDS = [
  {
    id: 'artemis.queue',
    resourceClass: 'SUBSCRIPTION',
    label: 'Queue',
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
  { id: 'artemis.address', resourceClass: 'DESTINATION', label: 'Address', verbs: ['create', 'delete', 'close'] },
  { id: 'artemis.connection', resourceClass: 'CONNECTION', label: 'Connection', verbs: ['close'] },
  { id: 'artemis.session', resourceClass: 'CHANNEL', label: 'Session', verbs: ['close'] },
  { id: 'artemis.consumer', resourceClass: 'CONSUMER', label: 'Consumer', verbs: ['close'] },
  { id: 'artemis.producer', resourceClass: 'PRODUCER', label: 'Producer', verbs: [] },
  { id: 'artemis.divert', resourceClass: 'ROUTE', label: 'Divert', verbs: ['create', 'delete'] },
  { id: 'artemis.bridge', resourceClass: 'ROUTE', label: 'Bridge', verbs: [] },
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
    kindLabel: 'Queue',
    name,
    destination: destinationId(address),
    nativeAttributes: { routingType },
  };
}

/** The identity fields of an address as the API lists a destination. */
export function destinationIdentity(name: string) {
  return { id: destinationId(name), kind: ADDRESS_KIND, kindLabel: 'Address', name };
}
