import type { ApiError } from '../../kernel/api/request.ts';
import { readError } from '../../ui/errorReading.tsx';
import type { SqlPlanView } from './api.ts';

/** What the console says about the cost of the query in the editor, before it runs. */
export interface CostVerdict {
  /** The state in one word; the sentence carries the figures. */
  badge: 'Unavailable' | 'Estimating' | 'No cost' | 'Index' | 'Scan' | 'Broker-filtered';
  /** Colour only emphasises the badge: `danger` for a query the server rejected, `warning` for a cost or a failure to estimate. */
  tone: 'neutral' | 'warning' | 'danger';
  sentence: string;
}

export interface CostInput {
  /** What the editor holds now. */
  text: string;
  /** What the plan was last asked about: the text, after the operator paused typing. */
  debounced: string;
  /** Why the console cannot read messages here, when it cannot. */
  blockedReason: string | null;
  plan: SqlPlanView | undefined;
  planError: ApiError | null;
  planPending: boolean;
}

const count = (n: number, noun: string) => `${n.toLocaleString()} ${noun}${n === 1 ? '' : 's'}`;

/** A figure the plan may not carry. An unknown figure is never shown as 0. */
const quantity = (n: number | undefined, noun: string) =>
  n === undefined ? `an unknown number of ${noun}s` : count(n, noun);

const unavailable = (sentence: string, tone: CostVerdict['tone'] = 'neutral'): CostVerdict => ({
  badge: 'Unavailable',
  tone,
  sentence,
});

/** The server rejects a query it cannot parse with a 400 that names the offending token. */
export function isSyntaxError(error: ApiError): boolean {
  return error.status === 400;
}

function failed(error: ApiError): CostVerdict {
  if (isSyntaxError(error)) {
    const { detail, suggestion } = error.problem;
    const words = typeof detail === 'string' && detail ? detail : error.message;
    const didYouMean = typeof suggestion === 'string' && suggestion ? ` Did you mean ${suggestion}?` : '';
    return unavailable(`Not estimated: ${words}${didYouMean}`, 'danger');
  }
  return unavailable(`Not estimated: ${readError(error).title}.`, 'warning');
}

/** What a plan costs, worked out from the plan alone. */
function planned(plan: SqlPlanView): CostVerdict {
  const targets = plan.targets ?? [];
  const queues = new Set(targets.map((t) => t.subscription)).size;
  const nodes = new Set(targets.map((t) => t.nodeId)).size;
  if (targets.length === 0) {
    return { badge: 'No cost', tone: 'neutral', sentence: 'Reads nothing: no queue matches the FROM pattern.' };
  }
  if (plan.source === 'INDEX') {
    return {
      badge: 'Index',
      tone: 'neutral',
      sentence: `Reads Rahdar's index, up to ${quantity(plan.effectiveLimit, 'row')} from ${count(queues, 'queue')}. No broker is read.`,
    };
  }
  if (plan.requiresScan) {
    return {
      badge: 'Scan',
      tone: 'warning',
      sentence: `Rahdar reads and examines about ${quantity(plan.estimatedMessagesExamined, 'message')} on ${count(queues, 'queue')} across ${count(nodes, 'node')}.`,
    };
  }
  return {
    badge: 'Broker-filtered',
    tone: 'neutral',
    sentence: `The brokers filter; Rahdar examines at most ${quantity(plan.effectiveLimit, 'message')} from ${count(queues, 'queue')}.`,
  };
}

/**
 * The cost of the query in the editor, in one sentence of words and numbers. Pure: the same inputs
 * give the same verdict, and a figure the plan does not carry is stated as unknown, never as zero.
 */
export function costVerdict({ text, debounced, blockedReason, plan, planError, planPending }: CostInput): CostVerdict {
  if (!text.trim()) return unavailable('Not estimated: the editor is empty.');
  if (blockedReason) return unavailable(`Not estimated: ${blockedReason.replace(/\.$/, '')}.`);
  if (text !== debounced || planPending) {
    return { badge: 'Estimating', tone: 'neutral', sentence: 'Estimating the edited query…' };
  }
  if (planError) return failed(planError);
  if (!plan) return unavailable('Not estimated: Rahdar has no plan for this query.');
  return planned(plan);
}
