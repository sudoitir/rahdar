import {
  useCallback,
  useEffect,
  useLayoutEffect,
  useMemo,
  useRef,
  useState,
  type CSSProperties,
  type ReactNode,
} from 'react';
import { useMantineTheme, VisuallyHidden } from '@mantine/core';

import type { Column } from './columns.ts';
import { ColumnsMenu } from './ColumnsMenu.tsx';
import { useDensity, type Density } from './density.ts';
import { GridTable, type GridOnlyProps } from './GridTable.tsx';

import { solveColumns, type SolveResult, type SolverColumn } from './layout.ts';
import { Measurer } from './measure.tsx';
import { columnSpec, useMeasurement } from './measurement.ts';
import { StaticTable } from './StaticTable.tsx';
import { parseSort } from './sort.ts';
import { RefetchBar, SKELETON_ROWS, StateSlot } from './TableStates.tsx';
import {
  orderColumns,
  useTableState,
  withMovedColumn,
  withoutWidth,
  withoutWidths,
  withVisibility,
  withWidth,
} from './tableState.ts';
import classes from './DataTable.module.css';

/** Above this many rows a static table is drawn as the grid, so a list that grows never falls off a cliff. */
export const STATIC_ROW_LIMIT = 200;

/** The controls a table's toolbar carries beside its Columns control. */
export interface TableToolbar {
  start?: ReactNode;
  end?: ReactNode;
}

interface DataTableBaseProps<T> {
  columns: Column<T>[];
  data: T[];
  rowKey: (row: T) => string;
  /** What the table lists, as its accessible name: "Subscriptions", "Connections". */
  label: string;
  /** Shown in place of the rows when there are none: an `EmptyState`. */
  empty: ReactNode;
  /** The first load: the header and placeholder rows. Once there are rows it is a refetch, and they stay. */
  loading?: boolean;
  /** Shown below the rows in place of them when set: an `ErrorState`. */
  error?: ReactNode;
  /**
   * Remembers the widths, hidden columns and order each viewer chooses, in this browser. Stable and
   * unique per table; a plugin prefixes its id. Read again whenever it changes. Without one, the
   * choices last as long as the table is shown.
   */
  storageKey?: string;
  /** The current `sort` query value: a column's `sortKey`, with a leading `-` for descending. The URL owns it. */
  sort?: string;
  onSortChange?: (sort: string | undefined) => void;
  /**
   * An extra class per row, for state the caller owns, such as a live tail's fresh rows. A class that
   * tints the row sets `--row-tint`, so the sticky cells carry the tint too.
   */
  rowClassName?: (row: T) => string | undefined;
  /** `'fill'` takes the height its parent gives it; `{ maxRows }` is as tall as its rows, up to that many. */
  height?: 'fill' | { maxRows: number };
  toolbar?: TableToolbar;
}

export interface GridVariantProps<T> extends DataTableBaseProps<T>, GridOnlyProps<T> {
  variant?: 'grid';
}

interface StaticVariantProps<T> extends DataTableBaseProps<T> {
  /** A small, read-only set in a native table. Above 200 rows it is drawn as the grid. */
  variant: 'static';
  caption?: ReactNode;
  /**
   * `false` drops the Columns control, for a short fixed set where every column is needed and there is
   * nothing to hide, reorder or resize, such as a diff in a dialog. The grid always has it.
   */
  columnsMenu?: boolean;
}

export type DataTableProps<T> = GridVariantProps<T> | StaticVariantProps<T>;

/** What the renderers share: the solved columns, the viewer's state and the ways to change it. */
export interface TableModel<T> {
  label: string;
  data: T[];
  rowKey: (row: T) => string;
  rowClassName?: (row: T) => string | undefined;
  sort?: string;
  onSortChange?: (sort: string | undefined) => void;
  /** The columns drawn: those that fit and that the viewer has not hidden, in their order. */
  columns: Column<T>[];
  /** Every column's id, in the viewer's order, hidden ones included. */
  order: string[];
  /** `grid-template-columns` for the drawn columns, and the px each gets. */
  template: string;
  solved: Record<string, number>;
  overflow: boolean;
  /** The inline size the tracks need, when they overflow. */
  minInline: number;
  /** The widths a viewer has set, including one being dragged. */
  widths: Record<string, number>;
  rowHeight: number;
  maxRows: number | undefined;
  loading: boolean;
  measurer: ReactNode;
  onWidth: (px: number) => void;
  announce: (text: string) => void;
  previewWidth: (id: string, width: number | undefined) => void;
  setWidth: (id: string, width: number) => void;
  fit: (id: string) => void;
  interaction: { set: (kind: 'pointer' | 'focus' | 'scrolled', on: boolean) => void };
}

/** What shows under the table instead of rows: its failure, or its empty state once nothing is loading. */
function stateSlot({
  error,
  empty,
  reserve,
  idle,
}: Readonly<{ error: ReactNode; empty: ReactNode; reserve: boolean; idle: boolean }>): ReactNode {
  if (error) return <StateSlot reserveRows={reserve ? SKELETON_ROWS : undefined}>{error}</StateSlot>;
  return idle ? <StateSlot>{empty}</StateSlot> : null;
}

/** The px of a row at `density`, from the theme, in the root font size the page is using. */
function rowHeightPx(rowH: number): number {
  const root = Number.parseFloat(getComputedStyle(document.documentElement).fontSize);
  return Math.round((rowH / 16) * (Number.isFinite(root) ? root : 16));
}

/**
 * The table's live region: what it announces itself (columns hidden by narrowing, a sort once its rows
 * land) and `announce` for everything else.
 *
 * The live region is mounted by the first announcement, empty, before its text arrives: a region
 * that exists before it changes is what screen readers announce reliably, and a table nobody
 * announces from adds none.
 * Cleared before every message, so the same words twice (copying one cell twice) are announced twice.
 */
function useTableAnnouncer<T>({
  hiddenCount,
  sort,
  data,
  loading,
  columns,
}: Readonly<{ hiddenCount: number; sort: string | undefined; data: T[]; loading: boolean; columns: Column<T>[] }>) {
  const [message, setMessage] = useState<string | null>(null);
  const announce = useCallback((text: string) => {
    setMessage('');
    requestAnimationFrame(() => setMessage(text));
  }, []);

  const lastHidden = useRef(hiddenCount);
  useEffect(() => {
    if (lastHidden.current === hiddenCount) return;
    lastHidden.current = hiddenCount;
    const noun = hiddenCount === 1 ? 'column' : 'columns';
    announce(hiddenCount === 0 ? 'No columns hidden' : `${hiddenCount} ${noun} hidden`);
  }, [hiddenCount, announce]);

  // A sort is announced once the sorted rows have landed: the data differs from the rows shown before
  // the sort changed, with nothing loading. Rows already cached for that sort land in the same render
  // as the sort, so the comparison is with the previous render's rows, not this one's.
  const pendingSort = useRef<{ sort: string | undefined; data: T[] } | null>(null);
  const lastSort = useRef(sort);
  const previousData = useRef(data);
  useEffect(() => {
    if (lastSort.current !== sort) {
      lastSort.current = sort;
      pendingSort.current = { sort, data: previousData.current };
    }
    previousData.current = data;
  }, [sort, data]);
  useEffect(() => {
    const pending = pendingSort.current;
    if (!pending || loading || pending.data === data) return;
    pendingSort.current = null;
    const { field, desc } = parseSort(pending.sort);
    const column = columns.find((c) => c.sortKey === field);
    const direction = desc ? 'descending' : 'ascending';
    announce(column ? `Sorted by ${column.header}, ${direction}` : 'Sorting cleared');
  }, [data, loading, columns, announce]);

  return { message, announce };
}

/**
 * The console's one data table (ADR-0160), in one of two renderers over one column model:
 *
 * <ul>
 *   <li>`'grid'`, the default: interactive and always virtualised, with a roving-focus keyboard
 *       model, a row menu, selection and resizable columns.
 *   <li>`'static'`: a native `<table>` for a small read-only set, drawn as the grid above 200 rows.
 * </ul>
 *
 * Columns are sized by what they hold, never by pixel widths (ADR-0161). When they do not fit, the
 * longest values are shortened, then the least important columns are hidden (and counted in the
 * Columns control), and only then does the table scroll sideways. Sorting is a round trip through
 * the URL: pass `sort` and `onSortChange`.
 *
 * The frame is the toolbar, then the table, then a state slot, as siblings: an empty, error or
 * loading state is never inside `role="grid"`. Build `columns` once (in a `useMemo`) so rows are not
 * redrawn for nothing.
 */
export function DataTable<T>(props: Readonly<DataTableProps<T>>) {
  const { columns, data, rowKey, label, empty, loading = false, error, storageKey, sort, onSortChange } = props;
  const { rowClassName, height = 'fill', toolbar } = props;
  const gridProps: GridOnlyProps<T> = props.variant === 'static' ? {} : props;
  const renderStatic = props.variant === 'static' && data.length <= STATIC_ROW_LIMIT;
  const columnsMenu = props.variant !== 'static' || props.columnsMenu !== false;
  const selectable = Boolean(gridProps.selectable);
  const hasMenu = gridProps.rowMenu !== undefined;

  const theme = useMantineTheme();
  const [density, setDensity] = useDensity();
  const [state, update] = useTableState(storageKey);
  const [drag, setDrag] = useState<{ id: string; width: number } | null>(null);
  const [inlineSize, setInlineSize] = useState(0);

  // ── What the viewer is doing, which holds live growth back ─────────────────
  const busy = useRef({ pointer: false, focus: false, scrolled: false });
  const resumeRef = useRef<() => void>(() => {});
  const isBusy = useCallback(() => busy.current.pointer || busy.current.focus || busy.current.scrolled, []);
  const interaction = useMemo(
    () => ({
      set: (kind: 'pointer' | 'focus' | 'scrolled', on: boolean) => {
        busy.current[kind] = on;
        if (!on) resumeRef.current();
      },
    }),
    [],
  );

  const { measurement, measurerProps, refit, resume } = useMeasurement({
    columns,
    data,
    density,
    isBusy,
    shown: inlineSize > 0,
  });
  resumeRef.current = resume;

  // ── Which columns, in what order ───────────────────────────────────────────
  const ordered = useMemo(() => orderColumns(columns, state.order), [columns, state.order]);
  const eligible = useMemo(() => {
    const hidden = new Set(state.hidden);
    // The column that identifies a row, and any essential one, cannot be hidden, whatever storage says.
    return ordered.filter((c, i) => i === 0 || c.priority === 'essential' || !hidden.has(c.id));
  }, [ordered, state.hidden]);

  const widths = useMemo(
    () => (drag ? { ...state.widths, [drag.id]: drag.width } : state.widths),
    [state.widths, drag],
  );
  const fixed = (selectable ? measurement.metrics.select : 0) + (hasMenu ? measurement.metrics.actions : 0);

  const solverColumns = useMemo<SolverColumn[]>(() => {
    const { metrics, intrinsic } = measurement;
    return eligible.map((c) => {
      const spec = columnSpec(c);
      const chPx = spec.mono ? metrics.mono : metrics.ch;
      return {
        id: c.id,
        intrinsic: intrinsic[c.id] ?? 0,
        min: spec.min === undefined ? 0 : spec.min * chPx + metrics.pad,
        max: spec.max === undefined ? Infinity : spec.max * chPx + metrics.pad,
        grow: spec.grow,
        truncates: spec.truncates,
        priority: c.priority,
        userWidth: widths[c.id],
        userShown: state.shown.includes(c.id),
      };
    });
  }, [eligible, measurement, widths, state.shown]);

  const previous = useRef<SolveResult>(undefined);
  const solved = useMemo(
    () => solveColumns({ W: inlineSize, cols: solverColumns, prev: previous.current, fixed }),
    [inlineSize, solverColumns, fixed],
  );
  useLayoutEffect(() => {
    previous.current = solved;
  }, [solved]);

  const visible = useMemo(() => eligible.filter((c) => !solved.hidden.includes(c.id)), [eligible, solved.hidden]);
  const hiddenCount = columns.length - visible.length;
  const order = useMemo(() => ordered.map((c) => c.id), [ordered]);

  // ── Announcements ──────────────────────────────────────────────────────────
  const { message, announce } = useTableAnnouncer({ hiddenCount, sort, data, loading, columns });

  // ── Widths ─────────────────────────────────────────────────────────────────
  const setWidth = useCallback(
    (id: string, width: number) => {
      update((s) => withWidth(s, id, width));
      setDrag(null);
    },
    [update],
  );
  const previewWidth = useCallback(
    (id: string, width: number | undefined) => setDrag(width === undefined ? null : { id, width }),
    [],
  );
  const columnsRef = useRef(columns);
  columnsRef.current = columns;
  const fit = useCallback(
    (id: string) => {
      update((s) => withoutWidth(s, id));
      refit();
      const header = columnsRef.current.find((c) => c.id === id)?.header ?? id;
      announce(`${header} column fitted to its content`);
    },
    [update, refit, announce],
  );

  const move = useCallback(
    (id: string, by: -1 | 1) => {
      const ids = orderColumns(columnsRef.current, state.order).map((c) => c.id);
      const next = withMovedColumn(state, ids, id, by);
      if (next === state) return;
      update((s) => withMovedColumn(s, ids, id, by));
      const header = columnsRef.current.find((c) => c.id === id)?.header ?? id;
      announce(`${header} moved to position ${next.order.indexOf(id) + 1} of ${ids.length}`);
    },
    [state, update, announce],
  );

  const rowHeight = useMemo(() => rowHeightPx(theme.other.density[density as Density].rowH), [theme, density]);

  const model: TableModel<T> = {
    label,
    data,
    rowKey,
    rowClassName,
    sort,
    onSortChange,
    columns: visible,
    order,
    template: solved.template,
    solved: solved.widths,
    overflow: solved.overflow,
    minInline: fixed + Object.values(solved.widths).reduce((total, width) => total + width, 0),
    widths,
    rowHeight,
    maxRows: typeof height === 'object' ? height.maxRows : undefined,
    loading,
    measurer: <Measurer {...measurerProps} />,
    onWidth: setInlineSize,
    announce,
    previewWidth,
    setWidth,
    fit,
    interaction,
  };

  const menuColumns = ordered.map((c, i) => ({
    id: c.id,
    header: c.header,
    visible: visible.includes(c),
    locked: i === 0 || c.priority === 'essential',
  }));

  // A static table that never had rows shows placeholder rows while it loads; its failure holds their height.
  const slot = stateSlot({
    error,
    empty,
    reserve: renderStatic && data.length === 0,
    idle: data.length === 0 && !loading,
  });

  return (
    <div
      className={classes.frame}
      data-fill={height === 'fill' || undefined}
      style={
        {
          '--as-row-h': `var(--as-row-h-${density})`,
          '--as-cell-pad': `var(--as-cell-pad-${density})`,
        } as CSSProperties
      }
    >
      {columnsMenu || toolbar ? (
        <fieldset className={classes.toolbar} aria-label={`${label} controls`}>
          <div className={classes.toolbarStart}>{toolbar?.start}</div>
          <div className={classes.toolbarEnd}>
            {toolbar?.end}
            {columnsMenu ? (
              <ColumnsMenu
                columns={menuColumns}
                hiddenCount={hiddenCount}
                onToggle={(id, show) => update((s) => withVisibility(s, id, show))}
                onMove={move}
                density={density}
                onDensity={setDensity}
                canResetWidths={columns.some((c) => c.id in state.widths)}
                onResetWidths={() => update(withoutWidths)}
              />
            ) : null}
          </div>
        </fieldset>
      ) : null}
      <RefetchBar active={loading && data.length > 0} />
      {renderStatic ? (
        <StaticTable model={model} caption={props.variant === 'static' ? props.caption : undefined} />
      ) : (
        <GridTable model={model} {...gridProps} />
      )}
      {slot}
      {message === null ? null : (
        <VisuallyHidden role="status" aria-live="polite">
          {message}
        </VisuallyHidden>
      )}
    </div>
  );
}
