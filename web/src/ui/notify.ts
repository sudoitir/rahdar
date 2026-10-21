import { createElement, Fragment } from 'react';
import { notifications } from '@mantine/notifications';

import { readGateRefusal } from './errorReading.tsx';
import { heldOf, type Held } from './held.ts';
import { HeldRequestLink } from './HeldRequestLink.tsx';

/**
 * The three forms of one action's verb, so a mutation's toast names it as its button does: the
 * button "Delete" is followed by "Deleting…", then "Deleted". Written out rather than derived,
 * because English inflection is irregular.
 */
export type ActionVerb = Readonly<{
  /** As on the control, such as "Delete". */
  verb: string;
  /** The outcome, such as "Deleted". */
  past: string;
  /** While it runs, such as "Deleting". */
  progressive: string;
}>;

type Notice = Readonly<{
  action: ActionVerb;
  /** What the action acts on, such as `subscription "orders"`. */
  subject: string;
}>;

/** Settles a toast that {@link notify.pending} returned: it is replaced by the outcome. */
type Settles = Readonly<{ pendingId?: string }>;

// The edge of each toast takes its tone from the semantic layer; the words carry the meaning.
const tone = (token: string) => ({ '--notification-color': `var(${token})` });

// Mantine renders every toast as role="alert" (assertive). Only a failure should interrupt a screen
// reader, so everything else is role="status" (polite).
const POLITE = 'status';
const ASSERTIVE = 'alert';

/** Long enough to read and see the edge settle; a failure never closes by itself. */
const SUCCEEDED_MS = 6000;
/** A held operation's toast carries a link to follow, so it stays long enough to reach it (and pauses on hover). */
const HELD_MS = 15_000;

function show(settles: Settles, toast: Parameters<typeof notifications.show>[0]) {
  if (settles.pendingId) notifications.hide(settles.pendingId);
  return notifications.show(toast);
}

/**
 * One helper for the four outcomes of a mutation (pending, succeeded, failed, partial), over
 * Mantine's notifications, which must be mounted once at the root.
 *
 * <ul>
 *   <li>Every line names the outcome with the action's own verb ({@link ActionVerb}).
 *   <li>Pending and succeeded are announced politely. Failed and partial are announced
 *       assertively and stay until dismissed, because each carries a next step to act on.
 *   <li>A failure states its cause and the next action; both are required.
 *   <li>An operation held for approval is a fifth outcome, not a failure: {@link notify.held} says it was sent
 *       and links to the request. {@link notify.settle} routes a mutation's error to it or to
 *       {@link notify.failed}, so every mutation's error handler goes through `settle`.
 *   <li>A toast that stays until dismissed can be dismissed by the caller with {@link notify.dismiss},
 *       such as a failure the operator has since fixed by retrying.
 * </ul>
 */
export const notify = {
  /** The action is running. Returns the toast's id; pass it as `pendingId` to the outcome. */
  pending({ action, subject }: Notice): string {
    return notifications.show({
      message: `${action.progressive} ${subject}…`,
      loading: true,
      autoClose: false,
      withCloseButton: false,
      role: POLITE,
      style: tone('--as-accent'),
    });
  },

  /** The action finished everywhere. */
  succeeded({ action, subject, pendingId }: Notice & Settles) {
    return show(
      { pendingId },
      {
        message: `${action.past} ${subject}`,
        autoClose: SUCCEEDED_MS,
        role: POLITE,
        style: tone('--as-ok'),
      },
    );
  },

  /** The action did not happen. `cause` says why; `next` says what to do. */
  failed({ action, subject, pendingId, cause, next }: Notice & Settles & Readonly<{ cause: string; next: string }>) {
    return show(
      { pendingId },
      {
        title: `Could not ${action.verb.toLowerCase()} ${subject}`,
        message: `${cause} ${next}`,
        autoClose: false,
        role: ASSERTIVE,
        style: tone('--as-danger'),
      },
    );
  },

  /**
   * The action reached some of what it was asked to. `reached` says how far, such as "2 of 3
   * nodes"; `next` says what is left to do.
   */
  partial({
    action,
    subject,
    pendingId,
    reached,
    next,
  }: Notice & Settles & Readonly<{ reached: string; next: string }>) {
    return show(
      { pendingId },
      {
        title: `${action.past} ${subject} on ${reached}`,
        message: next,
        autoClose: false,
        role: ASSERTIVE,
        style: tone('--as-warning'),
      },
    );
  },

  /**
   * The action was sent for approval instead of running: it waits for a second person, and the toast links to
   * its request. Announced politely, because nothing went wrong.
   */
  held({ id, summary, pendingId }: Held & Settles) {
    return show(
      { pendingId },
      {
        title: 'Sent for approval',
        message: createElement(
          Fragment,
          null,
          `${summary} waits for a second person. `,
          createElement(HeldRequestLink, { id }),
        ),
        autoClose: HELD_MS,
        role: POLITE,
        style: tone('--as-accent'),
      },
    );
  },

  /**
   * Settles a mutation that threw: a held operation becomes {@link notify.held}, an approval gate refusal a
   * failure in the gate's own words, and anything else {@link notify.failed} as given. `onHeld` runs when it was
   * held, so a dialog that closes on success closes then too: the request waits elsewhere, and leaving the
   * dialog open and armed invites submitting it again.
   */
  settle(error: unknown, failure: Notice & Settles & Readonly<{ cause: string; next: string; onHeld?: () => void }>) {
    const held = heldOf(error);
    if (held) {
      failure.onHeld?.();
      return notify.held({ ...held, pendingId: failure.pendingId });
    }
    const refusal = readGateRefusal(error);
    if (refusal && typeof refusal.next === 'string') {
      return notify.failed({ ...failure, cause: refusal.cause, next: refusal.next });
    }
    return notify.failed(failure);
  },

  /** Closes a toast by the id one of the outcomes returned. */
  dismiss(id: string) {
    notifications.hide(id);
  },
};
