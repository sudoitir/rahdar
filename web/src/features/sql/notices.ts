import type { SqlBoundView, SqlNoticeView } from './api.ts';

/**
 * Notices and bounds, as words. The server sends a stable kind; the sentence an
 * operator reads lives here, next to the screen that shows it.
 *
 * <p>Tone is `undefined` for anything that is merely true about the result. Only
 * the things that make a result mean less than it appears to carry a tone, so a
 * complete answer reads near-monochrome (frontend rule: colour is never the only
 * carrier, and a healthy view is not coloured at all).
 */
export type Tone = 'warning' | 'danger' | undefined;

const NOTICE_WORDS: Record<string, { text: string; tone: Tone }> = {
  NO_QUEUE_MATCHED: {
    text: 'No subscription matched. This is an empty target list, not an empty subscription.',
    tone: 'warning',
  },
  TARGET_CAPPED: {
    text: 'The FROM pattern matched more subscriptions than one query may read, so only some of them were read.',
    tone: 'warning',
  },
  EXCLUDED_BY_PERMISSION: {
    text: 'A subscription this query names was excluded — you do not have access to it.',
    tone: 'warning',
  },
  CLOCK_OFFSET_UNKNOWN: {
    text: "A node's clock offset has not been measured, so a relative time predicate against it may be off.",
    tone: 'warning',
  },
  INDEX_COVERAGE_GAP: {
    text: 'The index does not cover the whole window this query asks for. Rows before it were never captured.',
    tone: 'warning',
  },
  INDEX_ONLY_COLUMN: {
    text: 'This query uses a column that only exists in the index.',
    tone: undefined,
  },
  BODY_TRUNCATED: {
    text: 'The management channel cut at least one body, so a body predicate may have missed a match.',
    tone: 'warning',
  },
  CHANNEL_CHANGED: {
    text: 'A node changed channel mid-query, so its rows were not all read the same way.',
    tone: 'warning',
  },
  ADDRESS_SCOPED_CAPTURE: {
    // Not a warning: the rows are complete for what the destination routed. It is the
    // question "which subscription got it" that cannot be answered, and saying so
    // is the whole point.
    text:
      'These rows are what the destination routed. Capture copies before fan-out, so which of the ' +
      'subscriptions bound to this destination received a message is not recorded.',
    tone: undefined,
  },
  CAPTURE_NODE_GAP: {
    text: 'Capture is not running on every node this query reads from.',
    tone: 'warning',
  },
};

export function noticeWords(notice: SqlNoticeView): {
  text: string;
  tone: Tone;
} {
  const known = notice.kind ? NOTICE_WORDS[notice.kind] : undefined;
  const base = known?.text ?? notice.kind ?? 'Something about this result is worth knowing.';
  return {
    text: notice.detail ? `${base} ${notice.detail}` : base,
    tone: known?.tone,
  };
}

/**
 * A bound is why the query stopped early. Every one of them means the answer is
 * a prefix of the real answer, so all four are stated with their number — an
 * omitted limit reads as "there were no more", which is the dangerous misreading.
 */
export function boundWords(bound: SqlBoundView): string {
  const value = (bound.value ?? 0).toLocaleString();
  switch (bound.kind) {
    case 'SCAN_CAP':
      return `Stopped after examining ${value} messages — the scan cap. Narrow the query with a header predicate.`;
    case 'ROW_LIMIT':
      return `Stopped at ${value} rows — the row limit. There may be more matches.`;
    case 'TIMEOUT':
      return `Stopped after ${value} ms — the query timeout. There may be more matches.`;
    case 'TARGET_CAP':
      return `Only the first ${value} subscriptions were read — the target cap. Narrow the FROM pattern.`;
    default:
      return `Stopped at ${value} (${bound.kind}).`;
  }
}
