/**
 * The console's column catalogue, for the editor's completion list and the help
 * panel.
 *
 * <p>This mirrors `ColumnCatalogue.java`, which is the authority: the server
 * validates every query against its own copy and rejects an unknown column by
 * name with a suggestion. Drift here therefore costs a wrong autocomplete hint
 * and a good error message, never a wrong result — which is why this is a second
 * copy rather than a request. Keep the two in step when a column is added.
 */

/** Where a predicate over this column can be evaluated, which is what it costs. */
export type Evaluation = 'TARGET' | 'PUSHDOWN' | 'SCAN';

export interface CatalogueColumn {
  name: string;
  type: 'string' | 'number' | 'boolean' | 'timestamp';
  evaluation: Evaluation;
  /** True for a column that describes an observation, not a message: index only. */
  indexOnly?: boolean;
  description: string;
}

export const COLUMNS: CatalogueColumn[] = [
  {
    name: 'messageId',
    type: 'string',
    evaluation: 'SCAN',
    description: 'The broker-assigned message id. Node-local.',
  },
  {
    name: 'subscription',
    type: 'string',
    evaluation: 'TARGET',
    description: 'The subscription the message is on.',
  },
  {
    name: 'destination',
    type: 'string',
    evaluation: 'TARGET',
    description: 'The destination the subscription is bound to.',
  },
  {
    name: 'node',
    type: 'string',
    evaluation: 'TARGET',
    description: 'The broker node holding the message.',
  },
  {
    name: 'priority',
    type: 'number',
    evaluation: 'PUSHDOWN',
    description: 'JMS priority, 0-9.',
  },
  {
    name: 'durable',
    type: 'boolean',
    evaluation: 'PUSHDOWN',
    description: 'Whether the message is persistent.',
  },
  {
    name: 'timestamp',
    type: 'timestamp',
    evaluation: 'PUSHDOWN',
    description: 'When the broker took the message.',
  },
  {
    name: 'expiration',
    type: 'timestamp',
    evaluation: 'PUSHDOWN',
    description: 'When the message expires; 0 means never.',
  },
  {
    name: 'size',
    type: 'number',
    evaluation: 'PUSHDOWN',
    description: 'Encoded size in bytes.',
  },
  {
    name: 'jmsType',
    type: 'string',
    evaluation: 'PUSHDOWN',
    description: 'The JMSType header.',
  },
  {
    name: 'correlationId',
    type: 'string',
    evaluation: 'PUSHDOWN',
    description: 'The correlation id.',
  },
  {
    name: 'groupId',
    type: 'string',
    evaluation: 'PUSHDOWN',
    description: 'The message group id.',
  },
  {
    name: 'userId',
    type: 'string',
    evaluation: 'PUSHDOWN',
    description: 'The user id the producer set.',
  },
  {
    name: 'messageType',
    type: 'number',
    evaluation: 'SCAN',
    description: 'The numeric core message type.',
  },
  {
    name: 'replyTo',
    type: 'string',
    evaluation: 'SCAN',
    description: 'The reply-to destination, when the message has one.',
  },
  {
    name: 'body',
    type: 'string',
    evaluation: 'SCAN',
    description: 'The message body as text. Every predicate over it is a scan.',
  },
  {
    name: 'observedAt',
    type: 'timestamp',
    evaluation: 'SCAN',
    indexOnly: true,
    description: 'When the index first observed the message.',
  },
  {
    name: 'lastSeenAt',
    type: 'timestamp',
    evaluation: 'SCAN',
    indexOnly: true,
    description: 'When the index last still saw it on its queue.',
  },
  {
    name: 'origin',
    type: 'string',
    evaluation: 'SCAN',
    indexOnly: true,
    description:
      'SAMPLED or CAPTURED. A sampled row says a poll saw the message; a captured one says the address routed it.',
  },
  {
    name: 'orig_destination',
    type: 'string',
    evaluation: 'SCAN',
    indexOnly: true,
    description: 'The destination the broker said a captured copy came from, when it said so.',
  },
  {
    name: 'sourceMessageId',
    type: 'string',
    evaluation: 'SCAN',
    indexOnly: true,
    description: "A captured message's id on its source queue. Null when the broker did not copy it.",
  },
];

/** The functions the dialect accepts. Anything else is rejected by name. */
export const FUNCTIONS = ['now', 'lower', 'upper'];

/**
 * Full-text search over stored bodies, index-only (ADR-0063). `MATCH` is a
 * condition rather than a value, which is why it is not in FUNCTIONS: it takes the
 * column to search and a search-box string, and `match_rank` orders by how well a
 * row matched — valid only where a `MATCH()` is present to rank against.
 */
export const FULL_TEXT = {
  predicate: "MATCH (body) AGAINST ('terms')",
  rank: 'match_rank',
  note: 'Quoted phrases, -exclusion and or work as they do in a search box. Binary bodies are not full-text indexed.',
};

/** How each evaluation class reads to an operator, in words rather than a colour. */
export const EVALUATION_WORDS: Record<Evaluation, string> = {
  TARGET: 'chooses the queues — costs nothing',
  PUSHDOWN: 'the broker filters — costs nothing',
  SCAN: 'Rahdar examines every message returned',
};

export interface Example {
  title: string;
  sql: string;
  note: string;
}

/**
 * Worked examples, ordered cheapest first so the shape an operator copies is the
 * one that costs least. Each one loads into the editor in a single action.
 */
export const EXAMPLES: Example[] = [
  {
    title: 'Everything on one queue',
    sql: 'SELECT * FROM "ORDER.IN"\nORDER BY timestamp DESC\nLIMIT 100',
    note: 'No predicate at all — the broker returns its head pages and nothing is examined.',
  },
  {
    title: 'A header predicate, across a wildcard',
    sql: 'SELECT * FROM "ORDER.*"\nWHERE priority > 4\n  AND durable = true\nLIMIT 200',
    note: 'Both predicates become a JMS selector, so the brokers filter and Rahdar reads only matches.',
  },
  {
    title: 'An application property',
    sql: 'SELECT * FROM "ORDER.IN"\nWHERE props.tenant = \'acme\'\nLIMIT 200',
    note: 'Application properties are selector-eligible too. Still free.',
  },
  {
    title: 'A body fragment',
    sql: 'SELECT * FROM "ORDER.IN"\nWHERE body LIKE \'%4471%\'\nLIMIT 50',
    note: 'Nothing but Rahdar can read a body, so every message returned is examined. Narrow it with a header predicate first.',
  },
  {
    title: 'A JSON field inside the body',
    sql: "SELECT * FROM \"ORDER.IN\"\nWHERE body->>'orderId' = '4471'\nLIMIT 50",
    note: 'Also a scan. Against the index it is a JSONB lookup instead.',
  },
  {
    title: 'A time window',
    sql: 'SELECT * FROM "ORDER.*"\nWHERE timestamp > now() - interval \'2 hours\'\nORDER BY timestamp DESC\nLIMIT 500',
    note: "Relative time is normalised through each node's measured clock offset, so a skewed broker still answers.",
  },
  {
    title: 'Full-text over stored bodies',
    sql: 'SELECT * FROM index."ORDER.*"\nWHERE MATCH (body) AGAINST (\'"order 4471" -cancelled\')\nORDER BY match_rank DESC\nLIMIT 50',
    note: 'Index only, and served from a GIN index rather than scanned. Binary bodies are not indexed.',
  },
  {
    title: 'Force the live brokers',
    sql: 'SELECT * FROM broker."ORDER.IN"\nWHERE priority = 9\nLIMIT 100',
    note: 'The schema qualifier picks the backend. `broker` is current truth.',
  },
  {
    title: 'Force the historical index',
    sql: 'SELECT * FROM index."ORDER.IN"\nWHERE observedAt > now() - interval \'1 day\'\nLIMIT 500',
    note: 'The index still holds messages that have since been consumed. It only covers queues with a subscription.',
  },
];
