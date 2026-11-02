import { describe, expect, it } from 'vitest';

import { flowFeature } from './feature.ts';

describe('flowFeature', () => {
  it('offers the Flow page on every broker system, Kafka and RabbitMQ included', () => {
    expect(flowFeature.nav).toHaveLength(1);
    expect((flowFeature.nav?.[0] as { system?: string }).system).toBeUndefined();
  });

  it('opens a subscription, a destination and a client in Flow on any system', () => {
    const slots = flowFeature.slots ?? {};
    for (const slot of ['subscription.actions', 'destination.actions', 'client.actions']) {
      const first = (slots as Record<string, { id: string; system?: string }[]>)[slot]?.[0];
      expect(first?.system, slot).toBeUndefined();
    }
  });

  it('keeps the actions that only make sense for Artemis connections and routes on Artemis', () => {
    const slots = (flowFeature.slots ?? {}) as Record<string, { id: string; system?: string }[]>;
    expect(slots['connection.actions']?.[0]?.system).toBe('artemis');
    expect(slots['route.actions']?.[0]?.system).toBe('artemis');
    expect(slots['client.actions']?.find((a) => a.id === 'flow.client.connections')?.system).toBe('artemis');
  });
});
