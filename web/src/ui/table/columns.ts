import type { ReactNode } from 'react';

/** A kind's name for people, as its driver gives it: "Queue" and "Queues". */
export interface KindName {
  singular: string;
  plural: string;
}

/** What a column holds. It sets the column's bounds, font and how an overlong value is shortened. */
export type ColumnKind = 'text' | 'identifier' | 'code' | 'number' | 'time' | 'status';

/**
 * How much a column matters when the table is too narrow for all of them. `essential` is never hidden;
 * `low` goes before `high`. The first data column and any column that names the node a row came from
 * are `essential`.
 */
export type ColumnPriority = 'essential' | 'high' | 'low';

export interface Column<T> {
  id: string;
  header: string;
  /**
   * What the header cell draws when `header` is too long for the column, which is then as wide as its
   * figures. `header` stays the column's name: the Columns menu, the announcements and assistive
   * technology use it, and `description` says what the short label stands for.
   */
  short?: string;
  /** The plain value of the cell: what is measured, copied, revealed and put in its `title`. */
  accessor: (row: T) => unknown;
  cell?: (row: T) => ReactNode;
  kind: ColumnKind;
  priority: ColumnPriority;
  /** Bounds in `ch`, overriding the kind's. */
  min?: number;
  max?: number;
  /** Whether the column shares spare width, overriding the kind's. */
  grow?: boolean;
  /**
   * The cell is drawn as a `StatusBadge`, whose padding and border widen it beyond its text, so the
   * Measurer sizes it with them. Plain-word `status` columns (a type, "yes"/"no") are not badges.
   */
  badge?: boolean;
  /** Static table only: wrap the value instead of shortening it. */
  wrap?: boolean;
  /** The `sort` query value this column sorts by, if sortable. */
  sortKey?: string;
  description?: string;
}

interface KindPreset {
  /** Bounds in `ch`; a kind without them is as wide as its content. */
  min?: number;
  max?: number;
  grow: boolean;
  truncate: 'end' | 'middle' | 'none';
  mono: boolean;
}

export const KIND_PRESETS: Record<ColumnKind, KindPreset> = {
  text: { min: 12, max: 48, grow: true, truncate: 'end', mono: false },
  identifier: { min: 16, max: 64, grow: true, truncate: 'middle', mono: false },
  code: { min: 12, max: 48, grow: true, truncate: 'end', mono: true },
  number: { grow: false, truncate: 'none', mono: false },
  time: { grow: false, truncate: 'none', mono: false },
  status: { grow: false, truncate: 'none', mono: false },
};

/**
 * The Kind column of a list of one role: what the row's system calls its kind ("Queue", "Divert"). It is
 * `low` so a narrow table drops it first; the name still identifies the row.
 */
export function kindColumn<T extends { kindLabel: KindName }>(): Column<T>;
/** The same column for rows that hold the label deeper, such as a dead-letter row's subscription. */
export function kindColumn<T>(label: (row: T) => KindName): Column<T>;
export function kindColumn<T>(label?: (row: T) => KindName): Column<T> {
  return {
    id: 'kind',
    header: 'Kind',
    accessor: (r) => (label ? label(r) : (r as { kindLabel: KindName }).kindLabel).singular,
    kind: 'text',
    priority: 'low',
    // A kind's label is a word or two, so the column holds no more than that and leaves the width to the names.
    min: 8,
    max: 16,
    grow: false,
  };
}
