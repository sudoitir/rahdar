/** A group's state as the operator reads it: "Stable", "Rebalancing", "Empty". */
export function stateWords(state: string): { text: string; tone: 'neutral' | 'info' | 'warning' | 'danger' } {
  switch (state.toUpperCase()) {
    case 'STABLE':
      return { text: 'Stable', tone: 'neutral' };
    case 'EMPTY':
      return { text: 'Empty', tone: 'neutral' };
    case 'PREPARING_REBALANCE':
    case 'PREPARINGREBALANCE':
    case 'COMPLETING_REBALANCE':
    case 'COMPLETINGREBALANCE':
    case 'ASSIGNING':
    case 'RECONCILING':
      return { text: 'Rebalancing', tone: 'warning' };
    case 'DEAD':
      return { text: 'Dead', tone: 'danger' };
    default:
      return { text: state.charAt(0).toUpperCase() + state.slice(1).toLowerCase().replaceAll('_', ' '), tone: 'info' };
  }
}

/** A group's type: "Classic", "Consumer", "Share". */
export function typeWords(type: string): string {
  return type.charAt(0).toUpperCase() + type.slice(1).toLowerCase().replaceAll('_', ' ');
}

export const GROUP_TYPES = ['classic', 'consumer', 'share'] as const;

export const GROUP_STATES = ['stable', 'empty', 'rebalancing'] as const;

/** The kind of a Kafka topic, which the topic configuration page is for. */
export const KAFKA_TOPIC = 'kafka.topic';
