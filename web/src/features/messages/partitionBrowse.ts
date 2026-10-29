/** Where a browse of a partitioned log starts. */
export type BrowseFrom = 'earliest' | 'latest' | 'offset' | 'time';

export const BROWSE_FROM: { value: BrowseFrom; label: string }[] = [
  { value: 'earliest', label: 'The earliest record' },
  { value: 'latest', label: 'The latest records' },
  { value: 'offset', label: 'An offset' },
  { value: 'time', label: 'A time' },
];

/** What a browse of one partition asks for; the address holds it. */
export interface PartitionBrowse {
  partition: number;
  from: BrowseFrom;
  value?: string;
}

/** The browse the address describes, partition 0 from the earliest record when it says nothing. */
export function browseOf(search: { partition?: number; from?: string; value?: string }): PartitionBrowse {
  const from = BROWSE_FROM.find((f) => f.value === search.from)?.value ?? 'earliest';
  const needsValue = from === 'offset' || from === 'time';
  return {
    partition: Number.isInteger(search.partition) && (search.partition ?? 0) >= 0 ? (search.partition ?? 0) : 0,
    from,
    value: needsValue ? search.value : undefined,
  };
}

/** What is wrong with the value the start needs, or null; an offset is a whole number, a time an ISO-8601 instant. */
export function startProblem(from: BrowseFrom, value: string | undefined): string | null {
  if (from === 'offset') {
    return value && /^\d+$/.test(value.trim()) ? null : 'Enter the offset as a whole number, such as 120.';
  }
  if (from === 'time') {
    return value && !Number.isNaN(Date.parse(value.trim()))
      ? null
      : 'Enter the time as ISO-8601, such as 2026-10-28T09:30:00Z.';
  }
  return null;
}

/** The reason the selector is unavailable on a partitioned log; the web disables the input with it. */
export const SELECTOR_REFUSAL =
  'This system cannot select records on the broker. Browse by partition, offset or time instead.';
