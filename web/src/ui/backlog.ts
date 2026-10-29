import type { components } from '../kernel/api/schema.d.ts';

/** How much a backlog figure can be trusted: its value, accuracy, the reason, and whether a breakdown can be read. */
export type BacklogValue = components['schemas']['Backlog'];

/** The figure as the table sorts and measures it: exact as a number, an estimate marked, and never 0 for unknown. */
export function backlogWords(backlog: BacklogValue): string {
  if (backlog.accuracy === 'UNAVAILABLE' || backlog.value == null) return 'Unavailable';
  const figure = backlog.value.toLocaleString();
  return backlog.accuracy === 'ESTIMATE' ? `≈ ${figure}` : figure;
}

/** The figure with its unit for running text, "≈ 12 400 messages", or "backlog unavailable" when there is none. */
export function backlogPhrase(backlog: BacklogValue, unit: string): string {
  return backlog.accuracy === 'UNAVAILABLE' || backlog.value == null
    ? 'backlog unavailable'
    : `${backlogWords(backlog)} ${unit}`;
}
