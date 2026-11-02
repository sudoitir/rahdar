import { describe, expect, it } from 'vitest';

import { navGroups } from '../../kernel/registry.ts';
import { sqlFeature } from './feature.ts';

describe('the SQL console feature', () => {
  const labels = (system: string) =>
    navGroups([sqlFeature], system, undefined).flatMap((g) => g.items.map((i) => i.label));

  it('is offered on Kafka and RabbitMQ clusters as well as Artemis ones', () => {
    for (const system of ['artemis', 'kafka', 'rabbitmq']) {
      expect(labels(system)).toContain('SQL Console');
    }
  });

  it('contributes the message index to Artemis clusters only', () => {
    const index = sqlFeature.slots?.['settings.sections']?.find((s) => s.id === 'sql-index');
    expect(index?.system).toBe('artemis');
  });
});
