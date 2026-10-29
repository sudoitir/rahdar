import { describe, expect, it } from 'vitest';

import type { BrokerEventView } from './api.ts';
import { family, summaryOf } from './columns.ts';

describe('family', () => {
  it.each([
    ['openbroker.consumer.attached', 'consumer'],
    ['openbroker.channel.closed', 'channel'],
    ['openbroker.client.connected', 'connection'],
    ['openbroker.resource.created', 'resource'],
    ['openbroker.message.delivered', 'message'],
    ['openbroker.route.class', 'route'],
    ['openbroker.placement.node', 'other'],
    ['vendor.thing', 'other'],
  ])('names %s as %s', (type, word) => {
    expect(family(type)).toBe(word);
  });
});

describe('summaryOf', () => {
  const delivered = (over: Partial<BrokerEventView>): BrokerEventView =>
    ({
      seq: 1,
      type: 'openbroker.message.delivered',
      occurredAt: '2026-10-29T10:00:00Z',
      destination: 'ORDERS.events',
      routingName: 'billing',
      props: { count: 120, from: '2026-10-29T09:59:00Z', to: '2026-10-29T10:00:00Z' },
      ...over,
    }) as BrokerEventView;

  it('says how many were delivered where and between when', () => {
    const text = summaryOf(delivered({}));
    expect(text).toMatch(/^120 messages delivered on ORDERS\.events\/billing between .+ and .+$/);
  });

  it('is singular for one, and drops the subscription when there is none', () => {
    const text = summaryOf(
      delivered({ routingName: null, props: { count: 1, from: '2026-10-29T09:59:00Z', to: '2026-10-29T09:59:30Z' } }),
    );
    expect(text).toMatch(/^1 message delivered on ORDERS\.events between/);
  });

  it('is nothing for another event, or a summary without its figures', () => {
    expect(summaryOf(delivered({ type: 'openbroker.consumer.attached' }))).toBeNull();
    expect(summaryOf(delivered({ props: { count: 'many' } }))).toBeNull();
  });
});
