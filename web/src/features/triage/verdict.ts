import type { ConsumerHealthView } from './api.ts';

/** The ladder's verdicts, as the API names them (ADR-0089). */
export type Verdict = ConsumerHealthView['verdict'];

/**
 * How a verdict is said, in words. The word is the carrier — colour is redundant
 * emphasis and never the only signal (the frontend rules, 20-frontend.md).
 *
 * `label` is deliberately short enough for a grid cell; `headline` is what the
 * drawer says, where there is room to be plain.
 */
interface VerdictCopy {
  label: string;
  headline: string;
  /** Which semantic token carries it, or `null` for the near-monochrome default. */
  tone: 'danger' | 'warning' | null;
}

const COPY: Record<string, VerdictCopy> = {
  NO_CONSUMERS: { label: 'No consumers', headline: 'Nothing is draining this subscription', tone: 'danger' },
  STALLED: { label: 'Stalled', headline: 'Consumers are holding messages and acknowledging none', tone: 'danger' },
  BROKER_SLOW: { label: 'Slow consumer', headline: 'The broker reported a slow consumer', tone: 'danger' },
  STARVED: { label: 'Starved', headline: 'Consumers are attached but receiving nothing', tone: 'warning' },
  FALLING_BEHIND: {
    label: 'Falling behind',
    headline: 'Acknowledging slower than the subscription fills',
    tone: 'warning',
  },
  DRAINING: { label: 'Draining', headline: 'Recovering — the backlog is clearing', tone: null },
  PAUSED: { label: 'Paused', headline: 'Paused, so the backlog is expected', tone: null },
  HEALTHY: { label: 'Healthy', headline: 'Keeping up with what arrives', tone: null },
  INSUFFICIENT_DATA: { label: 'Not measured', headline: 'Not enough samples yet to judge', tone: null },
};

const UNKNOWN: VerdictCopy = {
  // A verdict this build does not know is reported as unrecognised rather than
  // dropped: a newer server adding a rung must not render as a blank cell.
  label: 'Unrecognised',
  headline: 'This Rahdar build does not recognise the verdict the server reported',
  tone: null,
};

export function verdictCopy(verdict: string): VerdictCopy {
  return COPY[verdict] ?? UNKNOWN;
}

/** The verdict as the plain text a cell shows, copies and is measured by, with its staleness. */
export function verdictText(row: ConsumerHealthView): string {
  const { label } = verdictCopy(row.verdict);
  return row.stale ? `${label} · stale` : label;
}

/** True where the server could not reach a verdict. Never the same as healthy. */
export function isUnmeasured(row: ConsumerHealthView): boolean {
  return row.verdict === 'INSUFFICIENT_DATA';
}

/**
 * A rate for display, or the reason there is none.
 *
 * An absent rate is stated, never rendered as `0` — on this screen a zero
 * acknowledgement rate and an unmeasured one mean opposite things and lead to
 * opposite actions (the frontend rules, 20-frontend.md).
 */
export function formatRate(value: number | null | undefined): string {
  if (value === null || value === undefined) return 'not measured';
  return `${value.toFixed(2)} msg/s`;
}

export function formatCount(value: number | null | undefined): string {
  if (value === null || value === undefined) return 'not measured';
  return value.toLocaleString();
}

/** A duration in seconds as a short human string; `null` where there is none to state. */
export function formatDuration(seconds: number | null | undefined): string | null {
  if (seconds === null || seconds === undefined) return null;
  if (seconds < 60) return `${Math.round(seconds)}s`;
  const minutes = Math.round(seconds / 60);
  if (minutes < 60) return `${minutes}m`;
  const hours = Math.round(minutes / 60);
  return hours < 48 ? `${hours}h` : `${Math.round(hours / 24)}d`;
}

/** The depth trend in words, with its rate. Null when the slope was not measurable. */
export function trendPhrase(row: ConsumerHealthView): string {
  const slope = row.depthSlopePerSecond;
  if (slope === null || slope === undefined) return 'trend not measured';
  if (Math.abs(slope) < 0.01) return 'depth steady';
  return slope > 0 ? `depth rising ${slope.toFixed(2)}/s` : `depth falling ${Math.abs(slope).toFixed(2)}/s`;
}
