import { Anchor, Collapse, Group, Stack, Text } from '@mantine/core';
import { useState } from 'react';

import { Notice } from '../../ui/Notice.tsx';
import { StatusBadge } from '../../ui/StatusBadge.tsx';
import type { SqlResultView } from './api.ts';
import { boundWords, noticeWords } from './notices.ts';
import classes from './ResultMetaBar.module.css';

function sourceLabel(fromIndex: boolean, captured: boolean): string {
  if (!fromIndex) return 'from the brokers';
  return captured ? 'from the index — captured' : 'from the index — sampled';
}

function IndexProvenance({ captured }: Readonly<{ captured: boolean }>) {
  return (
    <Notice title={captured ? 'Captured rows' : 'Sampled rows'}>
      {captured
        ? 'These rows were captured: a divert copied every message the destination routed into a subscription Rahdar drains, so a message that was consumed immediately is still here. A message may have been consumed since it was captured — "verify on broker", on any row, asks a broker whether it is still there.'
        : 'These rows are what Rahdar observed while sampling these subscriptions. A message may have been consumed since it was seen, and one that arrived and left between two samples was never indexed at all.'}
    </Notice>
  );
}

function BoundsNotice({ bounds }: Readonly<{ bounds: NonNullable<SqlResultView['boundsReached']> }>) {
  if (bounds.length === 0) return null;
  return (
    <Notice title="Stopped early" tone="warning">
      <ul className={classes.statements}>
        {bounds.map((bound, i) => (
          <li key={`${bound.kind}-${i}`}>{boundWords(bound)}</li>
        ))}
      </ul>
    </Notice>
  );
}

function ResultNotices({ notices }: Readonly<{ notices: NonNullable<SqlResultView['notices']> }>) {
  if (notices.length === 0) return null;
  return (
    <Notice title="About this result" tone={notices.some((n) => noticeWords(n).tone) ? 'warning' : 'neutral'}>
      <ul className={classes.statements}>
        {notices.map((notice, i) => (
          <li key={`${notice.kind}-${i}`}>{noticeWords(notice).text}</li>
        ))}
      </ul>
    </Notice>
  );
}

/**
 * One line about the result, with everything true about it behind a disclosure.
 *
 * <p>The screen used to stack up to five full-width alerts above the grid, each
 * correct and each pushing the rows further below the fold. During an incident that
 * is the worst possible arrangement: the operator scrolls past the explanations to
 * reach the data, which is exactly the text they needed to read.
 *
 * <p>So nothing is removed and nothing is softened — every bound, every notice and
 * the index's provenance statement are all still here, in full, one click away and
 * summarised in the bar itself. What changes is that the rows stay on screen.
 */
export function ResultMetaBar({
  result,
  rowCount,
  verdict,
}: Readonly<{
  result: SqlResultView;
  rowCount: number;
  verdict: { text: string; tone?: 'warning' | 'danger' };
}>) {
  const [open, setOpen] = useState(false);
  const bounds = result.boundsReached ?? [];
  const notices = result.notices ?? [];
  const fromIndex = result.plan?.source === 'INDEX';
  const captured = fromIndex && result.plan?.captured === true;
  const statements = bounds.length + notices.length + (fromIndex ? 1 : 0);
  const things = statements === 1 ? 'thing' : 'things';

  return (
    <div className={classes.bar}>
      <Group gap="xs" justify="space-between" wrap="wrap">
        <Group gap="xs" wrap="wrap">
          {/* State goes in words. Colour is redundant emphasis and appears only
              where something is actually wrong. */}
          <Text size="sm" fw={600} c={verdict.tone ? `var(--as-${verdict.tone})` : undefined}>
            {verdict.text}
          </Text>
          <StatusBadge>{sourceLabel(fromIndex, captured)}</StatusBadge>
          {bounds.length > 0 ? <StatusBadge tone="warning">Incomplete</StatusBadge> : null}
        </Group>
        {statements > 0 ? (
          <Anchor
            component="button"
            type="button"
            size="xs"
            onClick={() => setOpen((was) => !was)}
            aria-expanded={open}
          >
            {open ? 'Hide what this means' : `What this means (${statements} ${things})`}
          </Anchor>
        ) : null}
      </Group>

      <Collapse expanded={open}>
        <Stack gap={6} className={classes.detail}>
          {fromIndex ? <IndexProvenance captured={captured} /> : null}
          <BoundsNotice bounds={bounds} />
          <ResultNotices notices={notices} />
          <Text size="xs" c="dimmed">
            {rowCount.toLocaleString()} row{rowCount === 1 ? '' : 's'} in view.
          </Text>
        </Stack>
      </Collapse>
    </div>
  );
}
