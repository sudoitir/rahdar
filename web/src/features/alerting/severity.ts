/** A severity in a word, and how much attention it needs: only a warning or a critical alert is marked. */
export function severityTone(severity: string): { word: string; tone: 'neutral' | 'warning' | 'danger' } {
  if (severity === 'CRITICAL') return { word: 'critical', tone: 'danger' };
  if (severity === 'WARNING') return { word: 'warning', tone: 'warning' };
  return { word: 'info', tone: 'neutral' };
}

export const GAUGE_METRICS = [
  'openbroker.backlog',
  'openbroker.consumer.count',
  'openbroker.in_flight',
  'openbroker.scheduled',
] as const;
export const RATE_METRICS = ['openbroker.enqueued', 'openbroker.acknowledged', 'openbroker.expired'] as const;

/**
 * Derived metrics are computed from more than one reading (ADR-0044). Their
 * subject universe is narrower than a raw metric's, so they carry their own
 * explanation in the rule form rather than looking like another gauge.
 */
export const DERIVED_METRICS = ['ackRatePerConsumer', 'consumerHealth'] as const;

export function metricKind(metric: string): 'gauge' | 'rate' | 'derived' {
  if ((DERIVED_METRICS as readonly string[]).includes(metric)) return 'derived';
  return (RATE_METRICS as readonly string[]).includes(metric) ? 'rate' : 'gauge';
}

const METRIC_LABELS: Record<string, string> = {
  'openbroker.backlog': 'openbroker.backlog — messages waiting',
  'openbroker.consumer.count': 'openbroker.consumer.count — consumers attached',
  'openbroker.in_flight': 'openbroker.in_flight — delivered, not yet acknowledged',
  'openbroker.scheduled': 'openbroker.scheduled — held for later delivery',
  'openbroker.enqueued': 'openbroker.enqueued — messages in per second',
  'openbroker.acknowledged': 'openbroker.acknowledged — messages acknowledged per second',
  'openbroker.expired': 'openbroker.expired — messages expired per second',
  ackRatePerConsumer: 'ackRatePerConsumer — slow consumers',
  consumerHealth: 'consumerHealth — consumer health verdict',
};

/**
 * `consumerHealth` compares a severity rank, not a measurement (ADR-0089). The rank
 * is an internal encoding, so the form offers these words and stores the number —
 * an operator never types or reads a bare rank.
 */
export const HEALTH_SEVERITIES = [
  { value: 2, label: 'Falling behind or worse' },
  { value: 3, label: 'Starved or worse' },
  { value: 4, label: 'Stalled, no consumers, or broker-reported slow' },
] as const;

/** True where the rule's threshold is a verdict severity rather than a measured value. */
export function isVerdictMetric(metric: string): boolean {
  return metric === 'consumerHealth';
}

export function metricLabel(metric: string): string {
  return METRIC_LABELS[metric] ?? metric;
}

/**
 * What a derived metric actually watches, stated in the form. `ackRatePerConsumer`
 * only evaluates subscriptions that have consumers attached AND a backlog AND are not
 * paused — the triple that stops it paging on every quiet subscription at 3am — and it
 * cannot name the individual consumer, only the subscription on a node.
 */
export const METRIC_NOTES: Record<string, string> = {
  consumerHealth:
    'Fires on the same verdict the Consumer health screen shows, so an alert and the screen can never disagree. A subscription whose verdict cannot be computed yet — too few samples — is excluded entirely, so it neither fires nor resolves a firing that is still true. A paused subscription ranks below every threshold offered here and never pages.',
  ackRatePerConsumer:
    "Only subscriptions with consumers attached, a non-zero backlog, and not paused are evaluated — an idle or paused subscription is not a slow consumer. Rahdar resolves this to a subscription on a node; naming the individual consumer needs the broker's own slow-consumer detection.",
};

/**
 * A prefilled starting point rather than a seeded rule: a slow-consumer threshold
 * is workload-specific, so any value shipped on by default would be wrong for
 * most deployments. The operator still chooses the number.
 */
export const SLOW_CONSUMER_TEMPLATE = {
  name: 'Slow consumers',
  metric: 'ackRatePerConsumer',
  comparator: 'LT',
  threshold: 1,
  forSeconds: 300,
  severity: 'WARNING',
} as const;

/** What an installation-scoped rule may watch (ADR-0135): Rahdar's own storage and audit delivery, not a cluster. */
export const INSTALLATION_CONDITIONS = ['STORAGE_QUOTA', 'STORAGE_HEALTH', 'AUDIT_DELIVERY'] as const;

export const STATE_CONDITIONS = [
  'SPLIT_BRAIN',
  'NODE_DOWN',
  'REPLICATION_BEHIND',
  'CLUSTER_DEGRADED',
  'CLOCK_SKEW',
  'CONFIG_DRIFT',
  'SETUP_RISK',
] as const;

/**
 * A prefilled state rule for configuration drift (ADR-0067 D8): a node that no
 * longer matches the cluster's declaration. Evaluation is scheduled; action
 * never is — the alert is the whole of the automated response.
 */
export const CONFIG_DRIFT_TEMPLATE = {
  name: 'Configuration drift',
  stateCondition: 'CONFIG_DRIFT',
  forSeconds: 0,
  severity: 'WARNING',
} as const;

/**
 * A prefilled state rule for setup risk (ADR-0106): one firing per open critical or
 * warning finding of the setup review that has not been accepted as a known risk.
 * A template, not a seeded rule — whether a single pair is acceptable is the
 * operator's call, and accepting the risk on the review silences it.
 */
export const SETUP_RISK_TEMPLATE = {
  name: 'Cluster setup risk',
  stateCondition: 'SETUP_RISK',
  forSeconds: 0,
  severity: 'WARNING',
} as const;

export const COMPARATORS = ['GT', 'GTE', 'LT', 'LTE', 'EQ', 'NE'] as const;

const COMPARATOR_WORDS: Record<string, string> = {
  GT: '>',
  GTE: '≥',
  LT: '<',
  LTE: '≤',
  EQ: '=',
  NE: '≠',
};

export function comparatorSymbol(comparator: string): string {
  return COMPARATOR_WORDS[comparator] ?? comparator;
}

const STATE_LABELS: Record<string, string> = {
  SPLIT_BRAIN: 'Split-brain',
  NODE_DOWN: 'Node down',
  REPLICATION_BEHIND: 'Replication behind',
  CLUSTER_DEGRADED: 'Cluster degraded',
  CLOCK_SKEW: 'Clock skew',
  CONFIG_DRIFT: 'Configuration drift',
  SETUP_RISK: 'Setup risk',
  STORAGE_QUOTA: 'Storage quota',
  STORAGE_HEALTH: 'Storage health',
  AUDIT_DELIVERY: 'Audit delivery',
};

export function stateConditionLabel(condition: string): string {
  return STATE_LABELS[condition] ?? condition;
}
