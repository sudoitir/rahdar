import { describe, expect, it } from 'vitest';

import { family } from './columns.ts';

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
