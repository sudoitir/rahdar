/** How much a backlog figure can be trusted. */
export interface BacklogValue {
  value?: number | null;
  accuracy: 'EXACT' | 'ESTIMATE' | 'UNAVAILABLE';
  /** Why the figure is an estimate or unavailable. */
  reason?: string | null;
  /** Whether a per-partition breakdown can be read. */
  breakdown: boolean;
}

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
