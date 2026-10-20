import type { TransferMode, TransferRunView, TransferState } from './api.ts';

export type Tone = 'warning' | 'danger' | undefined;

export const plural = (n: number, one: string, many = `${one}s`) => `${n.toLocaleString()} ${n === 1 ? one : many}`;

export const MODE: Record<TransferMode, { verb: string; gerund: string; past: string }> = {
  MOVE: { verb: 'Move', gerund: 'moving', past: 'moved' },
  COPY: { verb: 'Copy', gerund: 'copying', past: 'copied' },
};

/** A byte figure an operator reads at a glance; an unknown one is said to be unknown. */
export function bytes(n: number | null | undefined): string {
  if (n == null) return 'size unknown';
  const units = ['bytes', 'KB', 'MB', 'GB', 'TB'];
  let value = n;
  let unit = 0;
  while (value >= 1000 && unit < units.length - 1) {
    value /= 1000;
    unit += 1;
  }
  return unit === 0
    ? plural(n, 'byte')
    : `${value.toLocaleString(undefined, { maximumFractionDigits: 1 })} ${units[unit]}`;
}

/** The run's state in words; colour only where something went wrong or needs the operator. */
export function stateWords(state: TransferState): { text: string; tone: Tone } {
  switch (state) {
    case 'PREVIEWED':
      return { text: 'Previewed, never started', tone: undefined };
    case 'RUNNING':
      return { text: 'Running', tone: undefined };
    case 'WAITING_FOR_CAPACITY':
      return { text: 'Waiting for the target to have room', tone: 'warning' };
    case 'RETURNING':
      return { text: 'Returning held messages to the source', tone: undefined };
    case 'SUCCEEDED':
      return { text: 'Succeeded', tone: undefined };
    case 'PARTIAL':
      return { text: 'Partial: some selected messages were not transferred', tone: 'warning' };
    case 'STOPPED':
      return { text: 'Stopped', tone: 'warning' };
    case 'INTERRUPTED':
      return { text: 'Interrupted: Rahdar stopped while it ran', tone: 'danger' };
    case 'FAILED':
      return { text: 'Failed', tone: 'danger' };
    case 'RETURNED':
      return { text: 'Returned: the held messages are back on the source', tone: undefined };
  }
}

export const ACTIVE = new Set<TransferState>(['RUNNING', 'WAITING_FOR_CAPACITY', 'RETURNING']);

/** Whether this run parks messages in a staging subscription: a move between two nodes. */
export const stages = (run: TransferRunView) => run.mode === 'MOVE' && !run.sameNode;

const TONE_COLOR = { danger: 'var(--as-danger)', warning: 'var(--as-warning)' } as const;

export const toneColor = (tone: Tone) => (tone ? TONE_COLOR[tone] : undefined);
