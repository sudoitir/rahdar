import { ADDRESS_KIND, addressId, QUEUE_KIND, queueId } from '../kernel/api/resourceId.ts';

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
