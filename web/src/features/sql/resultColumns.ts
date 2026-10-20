import type { SqlRowView } from './api.ts';

/**
 * The result grid's column vocabulary, apart from the component that renders it so
 * the grid and the cost-free `sourceOf` helper cannot disagree about what a column is called.
 */

export const COLUMN_LABELS: Record<string, string> = {
  source: 'Source',
  node: 'Node',
  subscription: 'Queue',
  messageId: 'Message ID',
  timestamp: 'Enqueued',
  priority: 'Prio',
  size: 'Size',
  body: 'Body',
  verify: 'On broker',
};

/**
 * Where a row came from, in its own words, and what that claims. Three provenances, not two: "indexed"
 * covers a sampled row and a captured one, which make different claims. A sampled row says a poll saw
 * this message, a captured one says the address routed it, and a live one was read from the broker.
 */
export function sourceOf(r: SqlRowView): { word: string; claim: string } {
  if (r.source !== 'INDEX') return { word: 'live', claim: 'Read from the live broker just now' };
  if (r.origin === 'CAPTURED') {
    return {
      word: 'captured',
      claim: 'Copied by a divert as the address routed it; it may have been consumed since',
    };
  }
  return {
    word: 'sampled',
    claim: 'Seen by a poll of this queue; a message consumed between polls was never recorded',
  };
}
