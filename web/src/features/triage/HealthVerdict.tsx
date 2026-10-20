import { StatusBadge } from '../../ui/StatusBadge.tsx';
import type { ConsumerHealthView } from './api.ts';
import { isUnmeasured, verdictCopy, verdictText } from './verdict.ts';

/**
 * One subscription's verdict, as a word.
 *
 * Colour never carries the verdict alone: the same text is present in every scheme and at every
 * contrast, and a healthy or unmeasured row carries no colour at all (it must not read as a fault,
 * and it must not read as an assurance either).
 */
export function HealthVerdict({ row }: Readonly<{ row: ConsumerHealthView }>) {
  const tone = isUnmeasured(row) ? 'neutral' : (verdictCopy(row.verdict).tone ?? 'neutral');
  return <StatusBadge tone={tone}>{verdictText(row)}</StatusBadge>;
}
