import type { ApiError } from '../../kernel/api/request.ts';
import { notify, type ActionVerb } from '../../ui/notify.ts';
import type { AffectedView, DryRunView, MessageActionKind, PartialView } from './api.ts';

/** Every message operation's verb in its three forms, so a toast names it as its button does. */
export const VERBS: Record<MessageActionKind | 'send' | 'purge', ActionVerb> = {
  move: { verb: 'Move', past: 'Moved', progressive: 'Moving' },
  retry: { verb: 'Retry', past: 'Retried', progressive: 'Retrying' },
  delete: { verb: 'Delete', past: 'Deleted', progressive: 'Deleting' },
  expire: { verb: 'Expire', past: 'Expired', progressive: 'Expiring' },
  send: { verb: 'Send', past: 'Sent', progressive: 'Sending' },
  purge: { verb: 'Purge', past: 'Purged', progressive: 'Purging' },
};

/** Whether the operation destroys messages, which is confirmed by typing the subscription's name. */
export const destroys = (action: MessageActionKind): boolean => action === 'delete' || action === 'expire';

export const messageCount = (n: number): string => `${n.toLocaleString()} message${n === 1 ? '' : 's'}`;

/**
 * Announces how an operation ended: done everywhere, or stopped part-way (the outcome that matters
 * most, because the part done cannot be undone and the operator needs to know which ids are left).
 * A dry run changes nothing and says nothing.
 */
export function announceResult(
  kind: MessageActionKind | 'purge',
  subscriptionName: string,
  result: AffectedView | DryRunView | PartialView,
  requested: number,
): void {
  if ('cap' in result) return;
  if ('notDone' in result) {
    notify.partial({
      action: VERBS[kind],
      subject: `${result.affectedCount.toLocaleString()} of ${messageCount(requested)}`,
      reached: `subscription "${subscriptionName}"`,
      next: `${result.error} Not ${VERBS[kind].past.toLowerCase()}: ${result.notDone.join(', ')}. Select them again to retry.`,
    });
    return;
  }
  if (result.skippedCount > 0) {
    notify.succeeded({
      action: VERBS[kind],
      subject: `subscription "${subscriptionName}", passing over ${result.skippedCount.toLocaleString()} record${result.skippedCount === 1 ? '' : 's'}`,
    });
    return;
  }
  notify.succeeded({
    action: VERBS[kind],
    subject: `${messageCount(result.affectedCount)} in subscription "${subscriptionName}"`,
  });
}

/** Announces that an operation did not happen, with its cause and what to do next. */
export function announceFailure(kind: keyof typeof VERBS, subject: string, error: ApiError, onHeld?: () => void): void {
  notify.settle(error, {
    action: VERBS[kind],
    subject,
    cause: error.message,
    next: 'Check the audit log for what ran, then try again.',
    onHeld,
  });
}
