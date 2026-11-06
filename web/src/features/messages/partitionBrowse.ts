/** Where a browse of a partitioned log starts. */
export type BrowseFrom = 'earliest' | 'latest' | 'offset' | 'time';

export const BROWSE_FROM: { value: BrowseFrom; label: string }[] = [
  { value: 'earliest', label: 'The earliest record' },
  { value: 'latest', label: 'The latest records' },
  { value: 'offset', label: 'An offset' },
  { value: 'time', label: 'A time' },
];

/**
 * What a browse of a partitioned log asks for. {@code partition} null reads every partition. A number focuses one.
 * {@code at} continues a merged page, as {@code partition:offset} pairs. It is not one offset for every partition.
 */
export interface PartitionBrowse {
  partition: number | null;
  from: BrowseFrom;
  value?: string;
  at?: string;
}

const AT = /^\d+:\d+(?::\d+)?(?:,\d+:\d+(?::\d+)?)*$/;

/** The browse the address describes. Nothing in the address reads every partition from the earliest record. */
export function browseOf(search: { partition?: number; from?: string; value?: string; at?: string }): PartitionBrowse {
  const from = BROWSE_FROM.find((item) => item.value === search.from)?.value ?? 'earliest';
  const needsValue = from === 'offset' || from === 'time';
  const partition =
    typeof search.partition === 'number' && Number.isInteger(search.partition) && search.partition >= 0
      ? search.partition
      : null;
  return {
    partition,
    from,
    value: needsValue ? search.value : undefined,
    at: search.at && AT.test(search.at) ? search.at : undefined,
  };
}

/** The part of the address that restores a browse. Invalid values are dropped, so a bad link still opens. */
export function browseAddress(raw: Record<string, unknown>): {
  partition?: number;
  from?: BrowseFrom;
  value?: string;
  at?: string;
  messagePartition?: number;
} {
  const out: {
    partition?: number;
    from?: BrowseFrom;
    value?: string;
    at?: string;
    messagePartition?: number;
  } = {};
  // Null, blank and "all" read every partition. Number(null) is 0, so absence must not become partition 0.
  if (raw.partition !== 'all' && raw.partition != null && raw.partition !== '') {
    const partition = typeof raw.partition === 'number' ? raw.partition : Number(raw.partition);
    if (Number.isInteger(partition) && partition >= 0) out.partition = partition;
  }
  const from = BROWSE_FROM.find((item) => item.value === raw.from)?.value;
  if (from) out.from = from;
  if ((from === 'offset' || from === 'time') && typeof raw.value === 'string' && raw.value) out.value = raw.value;
  if (typeof raw.at === 'string' && AT.test(raw.at)) out.at = raw.at;
  if (raw.messagePartition != null && raw.messagePartition !== '') {
    const messagePartition = Number(raw.messagePartition);
    if (Number.isInteger(messagePartition) && messagePartition >= 0) out.messagePartition = messagePartition;
  }
  return out;
}

/** What the browse asks the API for. A stream is one log, so it is always partition 0. */
export function messageQuery(
  partitioned: boolean,
  singleLog: boolean,
  browse: PartitionBrowse,
  search: { node?: string; filter?: string },
  page: number,
  size: number,
): {
  node?: string;
  filter?: string;
  page?: number;
  size: number;
  partition?: number;
  from?: string;
  value?: string;
  at?: string;
} {
  if (partitioned) {
    return {
      partition: singleLog ? 0 : (browse.partition ?? undefined),
      from: browse.from,
      value: browse.value,
      at: singleLog ? undefined : browse.at,
      size,
    };
  }
  return { node: search.node, filter: search.filter, page, size };
}

/** A record with no timestamp, said in words. Zero is not a time. */
export const NO_TIMESTAMP = 'No timestamp';

/** How a merged page is ordered, said where the rows are. */
export const MERGED_ORDER =
  'Ordered by timestamp, then partition, then offset. A record with no timestamp says it has none. Offsets belong to their own partition, and the next page continues each partition from its own offset.';

/**
 * The order of a merged page: timestamp, then partition, then offset. A missing timestamp sorts last, and is not
 * treated as the epoch.
 */
export function orderLogRecords<T extends { timestamp?: number | null; partition?: number | null; position: string }>(
  rows: readonly T[],
): T[] {
  return [...rows].sort((left, right) => {
    const leftMissing = left.timestamp == null || left.timestamp <= 0;
    const rightMissing = right.timestamp == null || right.timestamp <= 0;
    if (leftMissing !== rightMissing) return leftMissing ? 1 : -1;
    if (!leftMissing && left.timestamp !== right.timestamp) return (left.timestamp ?? 0) - (right.timestamp ?? 0);
    const byPartition = (left.partition ?? 0) - (right.partition ?? 0);
    if (byPartition !== 0) return byPartition;
    return Number(left.position) - Number(right.position);
  });
}

/** The written time, or {@link NO_TIMESTAMP} when the record carries none. */
export function writtenLabel(value: number | null | undefined, withTime: (ms: number) => string): string {
  if (value == null || value <= 0) return NO_TIMESTAMP;
  return withTime(value);
}

/**
 * {@code partition:offset} pairs, in partition order. {@code partition:offset:end} stops before {@code end} so the
 * next read does not repeat a record already shown. The text is the merged page's continuation.
 */
export function formatResume(resume: readonly { partition: number; offset: number; end?: number | null }[]): string {
  return [...resume]
    .sort((left, right) => left.partition - right.partition)
    .map((item) =>
      item.end != null ? `${item.partition}:${item.offset}:${item.end}` : `${item.partition}:${item.offset}`,
    )
    .join(',');
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
