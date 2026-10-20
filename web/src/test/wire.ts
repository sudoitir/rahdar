import { resourceId } from '../kernel/api/resourceId.ts';

/** The kinds the Artemis driver offers, as a cluster view lists them. */
export const ARTEMIS_KINDS = [
  { id: 'artemis.queue', resourceClass: 'SUBSCRIPTION', label: 'Queue', verbs: [] },
  { id: 'artemis.address', resourceClass: 'DESTINATION', label: 'Address', verbs: [] },
];

export const QUEUE_KIND = 'artemis.queue';
export const ADDRESS_KIND = 'artemis.address';
export const queueId = (name: string): string => resourceId(QUEUE_KIND, name);
export const addressId = (name: string): string => resourceId(ADDRESS_KIND, name);

/** The identity and binding fields of a queue as the API lists a subscription. */
export function subscriptionIdentity(name: string, address = name, routingType = 'ANYCAST') {
  return {
    id: queueId(name),
    kind: QUEUE_KIND,
    kindLabel: 'Queue',
    name,
    destination: addressId(address),
    nativeAttributes: { routingType },
  };
}

/** The identity fields of an address as the API lists a destination. */
export function destinationIdentity(name: string) {
  return { id: addressId(name), kind: ADDRESS_KIND, kindLabel: 'Address', name };
}
