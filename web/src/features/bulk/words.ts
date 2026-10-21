import type { BulkItemView, BulkOperation, BulkRunView } from './api.ts';

type Tone = 'warning' | 'danger' | undefined;

/** Each operation's names, and the single-subscription permission it is gated on (ADR-0093 D8). */
export const OPERATIONS: Record<
  BulkOperation,
  { verb: string; gerund: string; permission: string; permissionLabel: string; destructive: boolean }
> = {
  PAUSE: {
    verb: 'Pause',
    gerund: 'pausing',
    permission: 'subscription:pause',
    permissionLabel: 'Pause and resume subscriptions',
    destructive: false,
  },
  RESUME: {
    verb: 'Resume',
    gerund: 'resuming',
    permission: 'subscription:pause',
    permissionLabel: 'Pause and resume subscriptions',
    destructive: false,
  },
  PURGE: {
    verb: 'Purge',
    gerund: 'purging',
    permission: 'subscription:purge',
    permissionLabel: 'Purge subscriptions',
    destructive: true,
  },
  DELETE: {
    verb: 'Delete',
    gerund: 'deleting',
    permission: 'subscription:delete',
    permissionLabel: 'Destroy subscriptions and destinations',
    destructive: true,
  },
};

export const plural = (n: number, one: string, many = `${one}s`) => `${n.toLocaleString()} ${n === 1 ? one : many}`;

/** The run's status in words; colour only where something went wrong. */
export function runStatus(status: BulkRunView['status']): { text: string; tone: Tone } {
  switch (status) {
    case 'PREVIEWED':
      return { text: 'Previewed, never started', tone: undefined };
    case 'RUNNING':
      return { text: 'Running', tone: undefined };
    case 'SUCCEEDED':
      return { text: 'Succeeded', tone: undefined };
    case 'PARTIAL':
      return { text: 'Partial: some subscriptions succeeded and some did not', tone: 'warning' };
    case 'FAILED':
      return { text: 'Failed: no subscription succeeded', tone: 'danger' };
    case 'STOPPED':
      return { text: 'Stopped by an operator', tone: 'warning' };
    case 'INTERRUPTED':
      return { text: 'Interrupted: Rahdar stopped while it ran, and did not resume it', tone: 'danger' };
  }
}

export function itemStatus(status: BulkItemView['status']): { text: string; tone: Tone } {
  switch (status) {
    case 'PENDING':
      return { text: 'waiting', tone: undefined };
    case 'REFUSED':
      return { text: 'refused', tone: 'warning' };
    case 'RUNNING':
      return { text: 'in progress', tone: undefined };
    case 'SUCCEEDED':
      return { text: 'succeeded', tone: undefined };
    case 'PARTIAL':
      return { text: 'partial', tone: 'warning' };
    case 'FAILED':
      return { text: 'failed', tone: 'danger' };
    case 'SKIPPED':
      return { text: 'skipped after a failure', tone: 'warning' };
    case 'CANCELLED':
      return { text: 'cancelled', tone: undefined };
    case 'UNKNOWN':
      return { text: 'unknown: check the broker', tone: 'danger' };
  }
}
