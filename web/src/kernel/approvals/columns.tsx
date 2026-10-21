import { Anchor, Text } from '@mantine/core';
import { IconLock } from '@tabler/icons-react';
import { Link } from '@tanstack/react-router';

import { approvalPath } from '../../ui/held.ts';
import { StatusBadge } from '../../ui/StatusBadge.tsx';
import type { Column } from '../../ui/table/index.ts';
import { absoluteLabel } from '../time/time.ts';
import { REDACTED, type HeldDisplayRow, type HeldOperationSummary } from './api.ts';
import classes from './Approvals.module.css';
import { STATE } from './words.ts';

/** A value as the change table shows it: a secret says it is hidden, and an absent value says which absence. */
export function valueText(value: string | null | undefined, absent: string): string {
  if (value === null || value === undefined) return absent;
  return value === REDACTED ? 'Hidden' : value;
}

/**
 * Whether the rows only name what the request acts on (a subscription, a user) rather than change values: every row
 * lacks a current value. Those read as a list of facts; rows with a current value read as a change table.
 */
export const namesTargets = (rows: readonly HeldDisplayRow[]) =>
  rows.every((r) => r.from === null || r.from === undefined);

/** A row that names a target in words, as a confirmation repeats it: its value. */
export function targetText(row: HeldDisplayRow): string {
  return valueText(row.to, 'Removed');
}

/** A change row in words, as a confirmation repeats it: `before → after`. */
export function changeText(row: HeldDisplayRow): string {
  return `${valueText(row.from, 'Not set')} → ${valueText(row.to, 'Removed')}`;
}

function valueCell(value: string | null | undefined, absent: string) {
  if (value === REDACTED) {
    return (
      <Text span size="sm" className={classes.hidden}>
        <IconLock size="0.875rem" aria-hidden />
        Hidden
      </Text>
    );
  }
  if (value === null || value === undefined) {
    return (
      <Text span size="sm" c="dimmed">
        {absent}
      </Text>
    );
  }
  return value;
}

/** What a request changes, row by row: the item, its value now and its value after. */
export function changeColumns(): Column<HeldDisplayRow>[] {
  return [
    { id: 'label', header: 'Item', accessor: (r) => r.label, kind: 'text', priority: 'essential', max: 32 },
    {
      id: 'from',
      header: 'Current',
      accessor: (r) => valueText(r.from, 'Not set'),
      cell: (r) => valueCell(r.from, 'Not set'),
      kind: 'text',
      priority: 'essential',
      wrap: true,
    },
    {
      id: 'to',
      header: 'New',
      accessor: (r) => valueText(r.to, 'Removed'),
      cell: (r) => valueCell(r.to, 'Removed'),
      kind: 'text',
      priority: 'essential',
      wrap: true,
    },
  ];
}

/**
 * A list of requests: what each would do (a link to its page), its state, who asked and when. The caller's own
 * requests (`mine`) leave out who asked: it is always them.
 */
export function requestColumns({ mine = false }: Readonly<{ mine?: boolean }> = {}): Column<HeldOperationSummary>[] {
  const columns: Column<HeldOperationSummary>[] = [
    {
      id: 'summary',
      header: 'Request',
      accessor: (r) => r.summary,
      cell: (r) => (
        <Anchor component={Link} to={approvalPath(r.id)} size="sm">
          {r.summary}
        </Anchor>
      ),
      kind: 'text',
      priority: 'essential',
      max: 64,
      wrap: true,
    },
    {
      id: 'state',
      header: 'State',
      accessor: (r) => STATE[r.state].word,
      cell: (r) => <StatusBadge tone={STATE[r.state].tone}>{STATE[r.state].word}</StatusBadge>,
      kind: 'status',
      priority: 'essential',
      badge: true,
    },
    {
      id: 'requester',
      header: 'Requested by',
      accessor: (r) => r.requesterUsername,
      kind: 'identifier',
      priority: 'high',
      min: 12,
      max: 32,
    },
    {
      id: 'requestedAt',
      header: 'Requested',
      accessor: (r) => absoluteLabel(r.requestedAt),
      kind: 'time',
      priority: 'high',
    },
    {
      id: 'expiresAt',
      header: 'Expires',
      accessor: (r) => (r.state === 'HELD' ? absoluteLabel(r.expiresAt) : 'No longer waiting'),
      kind: 'time',
      priority: 'low',
    },
  ];
  return mine ? columns.filter((column) => column.id !== 'requester') : columns;
}
