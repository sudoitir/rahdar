import { Button, Popover, Stack, Text, VisuallyHidden } from '@mantine/core';

import { backlogWords, type BacklogValue } from './backlog.ts';
import classes from './BacklogCell.module.css';

/**
 * The messages waiting in a subscription. An exact figure is plain; an estimate reads "≈ 12 400" with
 * "estimate" in its accessible name, and an unavailable one reads "Unavailable", never 0 (an absent number
 * reads as zero, the most dangerous misreading). The reason is a popover on the figure itself, so a
 * keyboard reaches it, and the per-partition breakdown is offered there when the system has one.
 */
export function BacklogCell({
  backlog,
  subject,
  onBreakdown,
}: Readonly<{
  backlog: BacklogValue;
  /** What the figure belongs to, such as the subscription's name, for the popover's name. */
  subject: string;
  /** Opens the breakdown; offered when `backlog.breakdown` is true. */
  onBreakdown?: () => void;
}>) {
  const words = backlogWords(backlog);
  const inexact = backlog.accuracy !== 'EXACT';
  const offersBreakdown = backlog.breakdown && onBreakdown !== undefined;
  if (!inexact && !offersBreakdown) return <span className={classes.figure}>{words}</span>;

  const unavailable = backlog.accuracy === 'UNAVAILABLE';
  const fallback = unavailable ? 'The backlog could not be read.' : 'This figure is an estimate.';
  return (
    <Popover width="21.25rem" position="bottom-start" withArrow shadow="md" trapFocus returnFocus>
      <Popover.Target>
        <button type="button" className={classes.explain} aria-haspopup="dialog">
          {words}
          {inexact ? (
            <VisuallyHidden>{unavailable ? ', unavailable, why' : ', estimate, why'}</VisuallyHidden>
          ) : (
            <VisuallyHidden>, partitions</VisuallyHidden>
          )}
        </button>
      </Popover.Target>
      <Popover.Dropdown aria-label={`Backlog of ${subject}`} tabIndex={-1}>
        <Stack gap="xs">
          {inexact ? <Text size="xs">{backlog.reason ?? fallback}</Text> : null}
          {offersBreakdown ? (
            <Button size="compact-xs" variant="default" onClick={onBreakdown}>
              Show partitions
            </Button>
          ) : null}
        </Stack>
      </Popover.Dropdown>
    </Popover>
  );
}
