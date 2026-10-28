import { describe, expect, it } from 'vitest';

import { subscriptionId, subscriptionRef, subscriptionRefWords } from './resourceId.ts';

const KAFKA = [
  { id: 'kafka.topic', resourceClass: 'DESTINATION' },
  { id: 'kafka.group-topic', resourceClass: 'SUBSCRIPTION' },
];

describe('subscription references', () => {
  it('name a queue by its name and a group on a topic by its whole id', () => {
    expect(subscriptionRef({ id: 'artemis.queue/orders', name: 'orders' })).toBe(
      'artemis.queue/orders'.slice(0, 0) + 'orders',
    );
    expect(subscriptionRef({ id: 'kafka.group-topic/billing/orders', name: 'billing' })).toBe(
      'kafka.group-topic/billing/orders',
    );
  });

  it('are read back to the id they name', () => {
    expect(subscriptionId(KAFKA, 'billing')).toBe('kafka.group-topic/billing');
    expect(subscriptionId(KAFKA, 'kafka.group-topic/billing/orders')).toBe('kafka.group-topic/billing/orders');
  });

  it('are written for people without the kind', () => {
    expect(subscriptionRefWords('kafka.group-topic/billing/orders')).toBe('billing/orders');
    expect(subscriptionRefWords('orders')).toBe('orders');
  });
});
