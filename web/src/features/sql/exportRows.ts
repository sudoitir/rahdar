import { resourceName } from '../../kernel/api/resourceId.ts';
import type { SqlRowView } from './api.ts';

/**
 * Export of the rows currently in view — not of the query.
 *
 * <p>That distinction is the whole honesty of this feature. A result may be a
 * prefix of the answer: bounded by the row limit, by the scan cap, by a node that
 * did not answer, or by a tail's own view bound. Re-running the query server-side
 * to produce a "complete" file would be a second fan-out the operator did not ask
 * for, and would quietly answer a different question. So what is written is
 * exactly what is on screen, and the caller says so beside the control.
 */

/** The columns an export carries, in the order they appear. */
const FIELDS: { key: string; of: (row: SqlRowView) => unknown }[] = [
  { key: 'source', of: (r) => r.source },
  { key: 'origin', of: (r) => r.origin },
  { key: 'node', of: (r) => r.nodeName },
  { key: 'subscription', of: (r) => resourceName(r.subscription) },
  { key: 'destination', of: (r) => resourceName(r.destination) },
  { key: 'messageId', of: (r) => r.messageId },
  { key: 'sourceMessageId', of: (r) => r.sourceMessageId },
  {
    key: 'timestamp',
    of: (r) => (r.timestamp ? new Date(r.timestamp).toISOString() : ''),
  },
  { key: 'priority', of: (r) => r.priority },
  { key: 'durable', of: (r) => r.durable },
  { key: 'size', of: (r) => r.size },
  { key: 'correlationId', of: (r) => r.correlationId },
  { key: 'groupId', of: (r) => r.groupId },
  { key: 'replyTo', of: (r) => r.replyTo },
  { key: 'body', of: (r) => r.body },
  { key: 'bodyTruncated', of: (r) => r.bodyTruncated },
  { key: 'properties', of: (r) => JSON.stringify(r.properties ?? {}) },
];

/** RFC 4180: quote everything, double the quotes inside. Bodies contain commas and newlines. */
function csvCell(value: unknown): string {
  if (value === null || value === undefined) return '';
  const text =
    typeof value === 'string' || typeof value === 'number' || typeof value === 'boolean'
      ? String(value)
      : JSON.stringify(value);
  return `"${text.replaceAll('"', '""')}"`;
}

export function toCsv(rows: SqlRowView[]): string {
  const header = FIELDS.map((f) => f.key).join(',');
  const body = rows.map((row) => FIELDS.map((f) => csvCell(f.of(row))).join(','));
  return [header, ...body].join('\r\n');
}

export function toJson(rows: SqlRowView[]): string {
  return JSON.stringify(
    rows.map((row) => Object.fromEntries(FIELDS.map((f) => [f.key, f.of(row)]))),
    null,
    2,
  );
}
