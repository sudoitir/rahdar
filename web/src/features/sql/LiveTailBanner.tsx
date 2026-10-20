import { Button, Stack, Text } from '@mantine/core';

import { Notice } from '../../ui/Notice.tsx';
import type { SqlTailStatusView } from './api.ts';
import classes from './LiveTailBanner.module.css';

function time(iso?: string): string {
  if (!iso) return 'not yet';
  const at = new Date(iso);
  return Number.isNaN(at.getTime()) ? 'not yet' : at.toLocaleTimeString();
}

/**
 * What the tail can and cannot tell you.
 *
 * <p>The unobserved figure is only a figure when the query has no predicate: then
 * every enqueued message would have matched, and enqueued minus shown is exactly
 * what passed through between two reads. With a predicate the same subtraction
 * also contains messages that simply did not match, and the two cannot be
 * separated — so it is described rather than reported as a number, because a
 * number an operator cannot trust is worse than a sentence they can.
 */
function gapWords(tail: SqlTailStatusView | null): string {
  if (!tail || (tail.enqueued ?? 0) === 0) {
    return 'No message has been enqueued on these subscriptions since the tail started.';
  }
  const enqueued = (tail.enqueued ?? 0).toLocaleString();
  const shown = (tail.shown ?? 0).toLocaleString();
  if (tail.everyMessageMatches) {
    const missed = Math.max(0, (tail.enqueued ?? 0) - (tail.shown ?? 0));
    return missed === 0
      ? `${enqueued} enqueued, ${shown} shown — nothing has passed through unobserved yet.`
      : `${enqueued} enqueued, ${shown} shown: about ${missed.toLocaleString()} passed through between reads and were never seen.`;
  }
  return `${enqueued} enqueued on these subscriptions while tailing, ${shown} shown here. The difference holds both messages that did not match this query and messages consumed between reads, and the two cannot be told apart.`;
}

/**
 * The live tail's pinned header.
 *
 * <p>The sampling statement is not dismissable and is not a tooltip. A tail that
 * looks like a capture is read as one, and an operator concluding "the message
 * never arrived" from a sampled feed is the exact failure this screen exists to
 * prevent — so the limitation stays on screen for as long as the tail runs.
 *
 * <p>A captured tail makes a different claim, and it gets different words. Using
 * the sampling wording for a captured feed would understate what the operator has;
 * using the capture wording for a sampled one is the damaging direction, and the
 * two are never rendered from the same string (ADR-0062 D10). `captured` is true
 * only when every target is captured on the node it is read from — one node
 * without a tap and this is a sampled tail again, with the gap named below.
 */
export function LiveTailBanner({
  tail,
  shown,
  discarding,
  captured,
  paused,
  buffered,
  onPause,
  onResume,
  onStop,
}: Readonly<{
  tail: SqlTailStatusView | null;
  shown: number;
  /** True once the view has begun dropping its oldest rows to stay bounded. */
  discarding: boolean;
  /** True when every target is captured on the node it is read from. */
  captured: boolean;
  paused: boolean;
  buffered: number;
  onPause: () => void;
  onResume: () => void;
  onStop: () => void;
}>) {
  return (
    <div className={classes.pinned}>
      <Notice
        tone={captured ? 'neutral' : 'warning'}
        title={captured ? 'Live tail: capturing everything' : 'Live tail: a sample, not a capture'}
        action={
          <>
            {paused ? (
              <Button size="compact-xs" onClick={onResume}>
                Show {buffered.toLocaleString()} new row
                {buffered === 1 ? '' : 's'}
              </Button>
            ) : (
              <Button size="compact-xs" variant="default" onClick={onPause}>
                Pause the view
              </Button>
            )}
            <Button size="compact-xs" variant="default" onClick={onStop}>
              Stop tailing
            </Button>
          </>
        }
      >
        <Stack gap={6}>
          <Text size="sm">
            {captured
              ? 'A divert copies every message these destinations route into a subscription Rahdar drains, so a message that is consumed the instant it arrives still appears here. It is destination-scoped: for a destination with several bound subscriptions, which subscription received a message is not recorded.'
              : 'Rahdar re-reads these subscriptions every few seconds. A message that arrives and is consumed between two reads is never seen, so an empty tail is not evidence that nothing was sent.'}
          </Text>
          {paused ? (
            <Text size="sm">
              The view is paused. The tail is still running and still reading — {buffered.toLocaleString()} row
              {buffered === 1 ? '' : 's'} {buffered === 1 ? 'is' : 'are'} waiting. Pausing the view and stopping the
              query are different things, and this is the first.
            </Text>
          ) : null}
        </Stack>
      </Notice>
      {/* What changes with every read stays out of the notice, which is a live region: announcing each
          poll would bury the one statement that matters. */}
      <div className={classes.figures}>
        <Text size="sm">{gapWords(tail)}</Text>
        <Text size="xs" c="dimmed">
          {shown.toLocaleString()} row{shown === 1 ? '' : 's'}
          {discarding ? ' held' : ' so far'}
          {/* ADR-0056: a bounded view says so. A tail left running would otherwise silently drop its
              oldest rows, and an operator scrolling back would read the absence as "it never arrived". */}
          {discarding ? ' · the view is full, older rows are no longer shown' : ''}
          {' · '}
          {(tail?.polls ?? 0).toLocaleString()} read
          {(tail?.polls ?? 0) === 1 ? '' : 's'} · last at {time(tail?.lastPollAt)}
        </Text>
      </div>
    </div>
  );
}
