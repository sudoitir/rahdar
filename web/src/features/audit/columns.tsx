import { absoluteLabel } from '../../kernel/time/time.ts';
import { AUTO, localZone } from '../../kernel/time/timezone.ts';
import type { Column } from '../../ui/table/index.ts';
import { StatusBadge } from '../../ui/StatusBadge.tsx';
import type { AuditEventView } from './api.ts';

/**
 * The outcome in a word, and how much attention it needs: a failure is marked, and so is what is unfinished or
 * was refused for lack of a permission, or in doubt because the broker's confirmation never came back.
 */
export function outcome(o: string): { word: string; tone: 'neutral' | 'warning' | 'danger' } {
  if (o === 'SUCCESS') return { word: 'success', tone: 'neutral' };
  if (o === 'FAILURE') return { word: 'failure', tone: 'danger' };
  if (o === 'REFUSED') return { word: 'refused', tone: 'warning' };
  if (o === 'IN_DOUBT') return { word: 'in doubt', tone: 'warning' };
  return { word: 'pending', tone: 'warning' };
}

export function at(e: AuditEventView): string {
  return absoluteLabel(e.ts);
}

/** The target, with the fact that nothing was changed when the operation was a dry run. */
function targetOf(e: AuditEventView): string {
  const target = e.targetName ?? '—';
  return e.dryRun ? `${target} · dry run` : target;
}

/**
 * The audit grid's columns. The time identifies a row and is never hidden; who did what to which
 * target, and how it ended, go next. The time is written in the display zone `zone`, which the
 * header states, so a view builds its columns again when the zone changes.
 */
export function auditColumns(zone: string): Column<AuditEventView>[] {
  return [
    {
      id: 'time',
      header: 'Time',
      accessor: at,
      description: `When the operation was recorded, in ${zone === AUTO ? localZone() : zone}`,
      kind: 'time',
      priority: 'essential',
    },
    { id: 'user', header: 'User', accessor: (e) => e.username ?? 'anonymous', kind: 'identifier', priority: 'high' },
    { id: 'action', header: 'Action', accessor: (e) => e.action, kind: 'code', priority: 'high' },
    { id: 'target', header: 'Target', accessor: targetOf, kind: 'identifier', priority: 'high' },
    { id: 'count', header: 'Count', accessor: (e) => e.affectedCount ?? '—', kind: 'number', priority: 'high' },
    {
      id: 'outcome',
      header: 'Outcome',
      accessor: (e) => outcome(e.outcome).word,
      cell: (e) => {
        const { word, tone } = outcome(e.outcome);
        return <StatusBadge tone={tone}>{word}</StatusBadge>;
      },
      kind: 'status',
      badge: true,
      priority: 'high',
    },
  ];
}
