import { elapsedLabel } from '../../kernel/time/time.ts';
import type { ExpectationDiagnosticsView, ExpectationView } from './api.ts';

export function resolutionWords(capped: boolean, resolved: number): string {
  if (capped) return `too broad — only the first ${resolved} addresses are traced`;
  return resolved === 0 ? 'no matching queue yet' : `${resolved} matching now`;
}

/** The plain text of an expectation's reply addresses, as a cell copies and measures it. */
export function replyDestinationsText(e: ExpectationView): string {
  if (e.replyDestinations.length === 0) return 'temporary queues';
  const patterns = e.replyDestinations.some((a) => a.includes('*'));
  const resolution =
    patterns || e.replyDestinationsCapped
      ? ` · ${resolutionWords(e.replyDestinationsCapped, e.resolvedReplyDestinations.length)}`
      : '';
  return `${e.replyDestinations.join(', ')}${resolution}`;
}

/** `4s ago`, `3m ago` — the shared duration label, plus the word. */
export function ago(iso: string | null | undefined, now: number): string {
  if (!iso) return 'never';
  return `${elapsedLabel(now - Date.parse(iso))} ago`;
}

/** The plain text of {@link ExpectationStatus}, as a cell copies and measures it. */
export function statusText(status: ExpectationDiagnosticsView | undefined, now: number): string {
  if (!status) return 'not sampled yet';
  if (!status.enabled) return 'disabled';
  return `${status.nodesSampled}/${status.nodesTotal} nodes · sampled ${ago(status.lastSuccessAt, now)}`;
}
