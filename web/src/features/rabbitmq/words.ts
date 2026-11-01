/** The system id RabbitMQ clusters report, which scopes every navigation entry and slot contribution. */
export const RABBITMQ = 'rabbitmq';

/** The kind of an exchange, which the Publish action and the Bindings link are for. */
export const RABBITMQ_EXCHANGE = 'rabbitmq.exchange';

/** The kinds of queue, which the queue panel is for. */
export const RABBITMQ_QUEUE_KINDS: readonly string[] = [
  'rabbitmq.classic-queue',
  'rabbitmq.quorum-queue',
  'rabbitmq.stream',
];

export const QUEUE_TYPES = ['classic', 'quorum', 'stream'] as const;
export type QueueType = (typeof QUEUE_TYPES)[number];

/** What each kind of queue is, in one line, so choosing one needs no manual. */
export const QUEUE_TYPE_MEANING: Record<QueueType, string> = {
  classic: 'A single-node queue; fast, and lost with its node unless the node comes back.',
  quorum: 'A replicated queue that keeps its messages while a majority of its nodes live; the safe default.',
  stream: 'A replicated, append-only log that consumers read by offset; reading never removes a message.',
};

export const EXCHANGE_TYPES = ['direct', 'fanout', 'topic', 'headers'] as const;

export const EXCHANGE_TYPE_MEANING: Record<(typeof EXCHANGE_TYPES)[number], string> = {
  direct: 'Routes a message to the queues bound with exactly its routing key.',
  fanout: 'Routes a message to every bound queue and ignores the routing key.',
  topic: 'Routes by pattern: * stands for one word and # for any number of words.',
  headers: 'Routes on the message headers instead of the routing key.',
};

export const POLICY_APPLY_TO = ['all', 'queues', 'classic_queues', 'quorum_queues', 'streams', 'exchanges'] as const;

/** The kinds of resource a policy can match, as people read them. */
export function matchKindWords(kind: string): string {
  return kind.replaceAll('_', ' ');
}

/** An alarm as the operator reads it: a node that raised one blocks publishers. */
export function alarmWords(alarm: string): string {
  switch (alarm) {
    case 'memory':
      return 'Memory alarm';
    case 'disk':
      return 'Disk alarm';
    default:
      return `${alarm.charAt(0).toUpperCase()}${alarm.slice(1)} alarm`;
  }
}

/** The reason a broker-defined shovel cannot be removed from here. */
export const STATIC_SHOVEL_REASON =
  'This shovel is defined in the node configuration. Remove it there; it would return on the next restart otherwise.';

/** A binding in words: where its messages come from, which key selects them and where they go. */
export const bindingWords = (b: {
  source: string;
  routingKey?: string;
  destination: string;
  destinationType: string;
}) =>
  `${b.source || 'the default exchange'} to ${b.destinationType} ${b.destination}${b.routingKey ? ` with key ${b.routingKey}` : ''}`;
