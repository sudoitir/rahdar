import {
  memo,
  useCallback,
  useEffect,
  useLayoutEffect,
  useMemo,
  useRef,
  useState,
  type CSSProperties,
  type FocusEvent,
  type KeyboardEvent,
  type MouseEvent,
  type PointerEvent,
  type ReactNode,
  type RefObject,
  type SyntheticEvent,
} from 'react';
import { ActionIcon, Checkbox, CopyButton, Portal, VisuallyHidden } from '@mantine/core';
import { IconDots } from '@tabler/icons-react';
import { defaultRangeExtractor, useVirtualizer, type Range } from '@tanstack/react-virtual';

import { AnchoredMenu } from '../AnchoredMenu.tsx';
import type { Column } from './columns.ts';
import type { TableModel } from './DataTable.tsx';
import { stepMatch } from './gridFind.ts';
import { GridFind } from './GridFind.tsx';
import { useGridFind } from './useGridFind.ts';
import { HeaderCell } from './HeaderCell.tsx';
import { nextSort, sortingOf } from './sort.ts';
import { MiddleTruncate } from './MiddleTruncate.tsx';
import { anchorBelow, clampToViewport, type MenuAnchor } from './menuAnchor.ts';
import { cellText, columnSpec, useInlineSize } from './measurement.ts';
import { nextCell, resolveRow, type GridPos } from './rovingGrid.ts';
import { LoadingLabel, SkeletonRows } from './TableStates.tsx';
import { MIN_COLUMN_WIDTH } from './tableState.ts';
import classes from './DataTable.module.css';

/** What a row menu's items get: a way to close the menu, and to put focus back on the row. */
export interface RowMenuContext {
  close: () => void;
  /**
   * Puts focus back on the row's Actions control, scrolling it into view if it has left; on the
   * grid when the row is gone. For a dialog opened from the menu to call when it closes.
   */
  restoreFocus: () => void;
}

/** A per-row action menu (ADR-0107): its items are rendered only while it is open. */
export interface RowMenu<T> {
  /** Names the row in "Actions for <label>". */
  label: (row: T) => string;
  render: (row: T, context: RowMenuContext) => ReactNode;
}

/** One Ctrl+Shift+Arrow step. */
const RESIZE_STEP = 16;
/** The select and actions columns' ids in the focus model: no data column's id can be this. */
const SELECT_ID = '\u0000select';
const ACTIONS_ID = '\u0000actions';
/** The header row's key in the focus model. */
const HEADER = null;
/** The elements that take focus themselves when they are a cell's one control. */
const WIDGETS = 'a[href], button, input, select, textarea, [role="button"], [role="checkbox"]';
const OVERSCAN = 10;
/**
 * How long the pointer rests on a clipped cell before its full value shows, so sweeping across the
 * grid does not flicker a panel under every cell. Once one is showing, the next opens at once.
 */
const REVEAL_INTENT_MS = 400;

/** The full value a cell can reveal, when its value is something plain text can say. */
function plainText(value: unknown): string | undefined {
  if (typeof value === 'string') return value || undefined;
  if (typeof value === 'number' || typeof value === 'bigint') return String(value);
  return undefined;
}

function isRtl(): boolean {
  return document.dir === 'rtl' || getComputedStyle(document.documentElement).direction === 'rtl';
}

/** Whether a cell cuts its value short: itself, or the shrinking start of a middle-truncated identifier. */
function isClipped(cell: HTMLElement): boolean {
  if (cell.scrollWidth > cell.clientWidth) return true;
  const start = cell.querySelector<HTMLElement>('[data-clip]');
  return start !== null && start.scrollWidth > start.clientWidth;
}

/** A cell's single enabled control, which then takes the cell's focus; otherwise the cell itself. */
function focusTarget(cell: HTMLElement): HTMLElement {
  const widgets = [...cell.querySelectorAll<HTMLElement>(WIDGETS)].filter((el) => !(el as HTMLButtonElement).disabled);
  return widgets.length === 1 ? widgets[0] : cell;
}

/**
 * Makes exactly one element of the grid tabbable: the active cell's focus target. Returns that
 * target, so the caller can move focus to it.
 */
function syncTabOrder(grid: HTMLElement, activeRow: number, activeCol: number): HTMLElement | null {
  let target: HTMLElement | null = null;
  for (const cell of grid.querySelectorAll<HTMLElement>('[data-grid-col]')) {
    const row = Number(cell.parentElement?.dataset.gridRow);
    const col = Number(cell.dataset.gridCol);
    const isActive = row === activeRow && col === activeCol;
    const focusable = focusTarget(cell);
    cell.tabIndex = isActive && focusable === cell ? 0 : -1;
    for (const widget of cell.querySelectorAll<HTMLElement>(WIDGETS)) {
      widget.tabIndex = isActive && widget === focusable ? 0 : -1;
    }
    if (isActive) target = focusable;
  }
  return target;
}

/**
 * The column the focus model puts the active cell in: its own, or when that column is hidden the
 * nearest visible one (the one after it first), so a resize that hides it does not strand focus.
 */
function resolveColumn(id: string, visible: string[], order: string[], firstData: number): string {
  if (visible.includes(id)) return id;
  const at = order.indexOf(id);
  if (at < 0) return visible[firstData] ?? visible[0];
  for (let distance = 1; distance < order.length; distance++) {
    const found = [order[at + distance], order[at - distance]].find((other) => other && visible.includes(other));
    if (found) return found;
  }
  return visible[0];
}

/** The keys that open a row's menu: Shift+F10 or the ContextMenu key. */
function isMenuKey(e: KeyboardEvent): boolean {
  return e.key === 'ContextMenu' || (e.key === 'F10' && e.shiftKey);
}

function isCopyKey(e: KeyboardEvent): boolean {
  return (e.key === 'c' || e.key === 'C') && (e.ctrlKey || e.metaKey) && !e.altKey;
}

/** Ctrl+Shift+Left/Right, which resizes the column of a header cell (ADR-0161). */
function isFindKey(e: KeyboardEvent): boolean {
  return (e.key === 'f' || e.key === 'F') && (e.ctrlKey || e.metaKey) && !e.altKey && !e.shiftKey;
}

function isResizeKey(e: KeyboardEvent): boolean {
  return e.ctrlKey && e.shiftKey && !e.altKey && (e.key === 'ArrowLeft' || e.key === 'ArrowRight');
}

interface Reveal {
  text: string;
  /** Distance from the viewport's inline-start edge to the cell's, and from its block-start edge to the cell's end. */
  x: number;
  y: number;
}

interface OpenMenu {
  key: string;
  anchor: MenuAnchor;
}

/** What the rows call, kept stable so toggling one row re-renders that row alone. */
interface RowHandlers<T> {
  hover: (el: HTMLElement) => void;
  hide: (e: SyntheticEvent) => void;
  activate: (e: MouseEvent, row: T) => void;
  toggleRow: (key: string) => void;
  toggleMenu: (key: string, trigger: HTMLElement) => void;
}

/** The header's select-all checkbox, across the rows loaded. */
function SelectAllCell({
  keys,
  selected,
  onToggleAll,
}: Readonly<{
  keys: string[];
  selected: ReadonlySet<string> | undefined;
  onToggleAll: ((keys: string[], allSelected: boolean) => void) | undefined;
}>) {
  const count = selected ? keys.filter((k) => selected.has(k)).length : 0;
  const all = keys.length > 0 && count === keys.length;
  return (
    <div
      role="columnheader"
      aria-colindex={1}
      data-grid-col={0}
      className={`${classes.cell} ${classes.headCell} ${classes.selectCell} ${classes.stickyStart}`}
    >
      <Checkbox
        size="xs"
        aria-label={all ? 'Deselect all on this page' : 'Select all on this page'}
        checked={all}
        indeterminate={count > 0 && !all}
        onChange={() => onToggleAll?.(keys, all)}
      />
    </div>
  );
}

/** A body row's selection checkbox. */
function SelectCell({
  rowKey,
  checked,
  onToggle,
}: Readonly<{ rowKey: string; checked: boolean; onToggle: (key: string) => void }>) {
  return (
    <div
      role="gridcell"
      aria-colindex={1}
      data-grid-col={0}
      className={`${classes.cell} ${classes.selectCell} ${classes.stickyStart}`}
      onClick={(e) => e.stopPropagation()}
    >
      <Checkbox size="xs" aria-label={`Select row ${rowKey}`} checked={checked} onChange={() => onToggle(rowKey)} />
    </div>
  );
}

interface DataCellProps<T> {
  column: Column<T>;
  row: T;
  /** Its position in the focus model, and its 1-based `aria-colindex`. */
  col: number;
  first: boolean;
  handlers: RowHandlers<T>;
}

/** One value of a body row. The first column of a row is its header. */
function DataCell<T>({ column, row, col, first, handlers }: Readonly<DataCellProps<T>>) {
  const value = column.accessor(row);
  const full = plainText(value);
  const spec = columnSpec(column);
  const text = cellText(value);
  let content: ReactNode = text;
  if (column.cell) content = column.cell(row);
  else if (spec.truncate === 'middle') content = <MiddleTruncate text={text} />;
  return (
    <div
      role={first ? 'rowheader' : 'gridcell'}
      aria-colindex={col + 1}
      data-end={spec.alignEnd || undefined}
      data-tabular={spec.tabular || undefined}
      data-mono={spec.mono || undefined}
      data-full={full}
      data-grid-col={col}
      data-first={first || undefined}
      className={`${classes.cell} ${first ? classes.stickyFirst : ''}`}
      // A clipped cell is still readable in full: the shared panel shows it, with a copy control, on a
      // resting pointer or at once on keyboard focus. The value stays in the cell for a screen reader.
      onPointerEnter={(e) => handlers.hover(e.currentTarget)}
      onPointerLeave={handlers.hide}
    >
      {content}
    </div>
  );
}

/** The trailing cell of a body row: the control that opens its menu, or closes it again. */
function ActionsCell({
  col,
  label,
  expanded,
  onToggle,
}: Readonly<{ col: number; label: string; expanded: boolean; onToggle: (trigger: HTMLElement) => void }>) {
  return (
    <div
      role="gridcell"
      aria-colindex={col + 1}
      data-grid-col={col}
      className={`${classes.cell} ${classes.actionsCell} ${classes.stickyEnd}`}
    >
      <ActionIcon
        variant="subtle"
        color="var(--as-text-dimmed)"
        size="sm"
        aria-label={`Actions for ${label}`}
        aria-haspopup="menu"
        aria-expanded={expanded}
        onClick={(e) => {
          e.stopPropagation();
          onToggle(e.currentTarget);
        }}
      >
        <IconDots size="1em" aria-hidden />
      </ActionIcon>
    </div>
  );
}

interface GridRowProps<T> {
  row: T;
  rowKey: string;
  /** Zero-based position among the rows, and the px its row starts at. */
  index: number;
  start: number;
  columns: Column<T>[];
  selectable: boolean;
  selected: boolean;
  menuOpen: boolean;
  /** The row the grid's find stands on. */
  found: boolean;
  /** Names the row in its Actions control; undefined when the grid has no row menu. */
  menuLabel: string | undefined;
  clickable: boolean;
  className: string | undefined;
  handlers: RowHandlers<T>;
}

/**
 * One body row, memoised by its row's data and its own state: a selection or a menu opening in
 * another row leaves it alone. Everything it is given is stable unless it is its own.
 */
function GridRowView<T>({
  row,
  rowKey,
  index,
  start,
  columns,
  selectable,
  selected,
  menuOpen,
  found,
  menuLabel,
  clickable,
  className,
  handlers,
}: Readonly<GridRowProps<T>>) {
  const firstCol = selectable ? 1 : 0;
  return (
    <div
      role="row"
      aria-rowindex={index + 2}
      aria-selected={selectable ? selected : undefined}
      data-grid-row={index + 1}
      data-selected={selected || undefined}
      data-menu-open={menuOpen || undefined}
      data-found={found || undefined}
      className={`${classes.row} ${classes.bodyRow} ${clickable ? classes.clickable : ''} ${className ?? ''}`}
      onClick={clickable ? (e) => handlers.activate(e, row) : undefined}
      style={{ transform: `translateY(${start}px)` }}
    >
      {selectable ? <SelectCell rowKey={rowKey} checked={selected} onToggle={handlers.toggleRow} /> : null}
      {columns.map((column, i) => (
        <DataCell key={column.id} column={column} row={row} col={firstCol + i} first={i === 0} handlers={handlers} />
      ))}
      {menuLabel === undefined ? null : (
        <ActionsCell
          col={firstCol + columns.length}
          label={menuLabel}
          expanded={menuOpen}
          onToggle={(trigger) => handlers.toggleMenu(rowKey, trigger)}
        />
      )}
    </div>
  );
}

const GridRow = memo(GridRowView) as typeof GridRowView;

/** The full value of a clipped cell, with a way to copy it, anchored to that cell. */
function RevealPanel({
  reveal,
  panelRef,
  onHide,
}: Readonly<{
  reveal: Reveal;
  panelRef: RefObject<HTMLDialogElement | null>;
  onHide: (e: SyntheticEvent) => void;
}>) {
  return (
    <Portal>
      <dialog
        open
        ref={panelRef}
        className={classes.reveal}
        aria-label="Full value"
        style={{ '--reveal-x': `${reveal.x}px`, insetBlockStart: reveal.y } as CSSProperties}
        onPointerLeave={onHide}
      >
        <span className={classes.revealText}>{reveal.text}</span>
        <CopyButton value={reveal.text} timeout={1500}>
          {({ copied, copy }) => (
            <button type="button" className={classes.revealCopy} onClick={copy} onBlur={onHide}>
              {copied ? 'Copied' : 'Copy'}
            </button>
          )}
        </CopyButton>
      </dialog>
    </Portal>
  );
}

/**
 * Marks the scroll container when its content is scrolled away from either end, so the sticky
 * columns show their shadow only then. An IntersectionObserver over two sentinels, not a scroll
 * handler.
 */
function useStickyEdges(scrollRef: RefObject<HTMLElement | null>, overflow: boolean) {
  useEffect(() => {
    const scroll = scrollRef.current;
    if (!scroll || !overflow || typeof IntersectionObserver === 'undefined') return;
    const observer = new IntersectionObserver(
      (entries) => {
        for (const entry of entries) {
          const attribute = (entry.target as HTMLElement).dataset.sentinel === 'start' ? 'data-scrolled' : 'data-more';
          scroll.toggleAttribute(attribute, !entry.isIntersecting);
        }
      },
      { root: scroll },
    );
    for (const sentinel of scroll.querySelectorAll('[data-sentinel]')) observer.observe(sentinel);
    return () => {
      observer.disconnect();
      delete scroll.dataset.scrolled;
      delete scroll.dataset.more;
    };
  }, [scrollRef, overflow]);
}

export interface GridOnlyProps<T> {
  /** Activating a row, by click or by Enter on any of its cells. */
  onRowClick?: (row: T) => void;
  /** A per-row action menu, opened by right-click, by the row's Actions control, or by Shift+F10. */
  rowMenu?: RowMenu<T>;
  /** Opt-in leading checkbox column. Selection state is owned by the caller. */
  selectable?: boolean;
  selected?: ReadonlySet<string>;
  onToggleRow?: (key: string) => void;
  /** Header select-all across the loaded page. `allSelected` is the current state; the caller flips it. */
  onToggleAll?: (keys: string[], allSelected: boolean) => void;
  /**
   * Called when the grid's own scroll leaves or returns to the top. A live feed that prepends rows
   * moves the content under a reader who has scrolled away, so the caller needs to know in order
   * to hold new rows back.
   */
  onAtTopChange?: (atTop: boolean) => void;
}

/** The scroll container's custom properties: the one track list the header and every row share, and what bounds it. */
function scrollStyle<T>(model: TableModel<T>, selectable: boolean, hasMenu: boolean): CSSProperties {
  const tracks = [selectable ? 'var(--as-select-w)' : null, model.template, hasMenu ? 'var(--as-actions-w)' : null]
    .filter(Boolean)
    .join(' ');
  return {
    '--as-cols': tracks,
    '--as-min-inline': model.overflow ? `${model.minInline}px` : undefined,
    '--as-max-rows': model.maxRows,
    '--as-sticky-first': selectable ? 'var(--as-select-w)' : '0rem',
  } as CSSProperties;
}

/** The data columns with the selection column in front and the row-menu column behind, when there are any. */
function withEdgeColumns(ids: readonly string[], selectable: boolean, hasMenu: boolean): string[] {
  return [...(selectable ? [SELECT_ID] : []), ...ids, ...(hasMenu ? [ACTIONS_ID] : [])];
}

/**
 * The interactive renderer of `DataTable`: one CSS grid track list, declared once and shared by the
 * header row and every body row, row-virtualised with `@tanstack/react-virtual` (ADR-0020).
 *
 * <p>The keyboard model is the WAI-ARIA grid (ADR-0108): the grid is one tab stop, the arrow keys
 * move a roving focus between cells (the header row included), Enter activates a row, Space selects
 * it, Shift+F10 opens its menu, Ctrl/Cmd+C copies a focused cell, and Ctrl+Shift+Left/Right on a
 * header cell resizes its column. The active cell is remembered by row key and column id, so a
 * refresh, a re-sort or a hidden column does not move it, and its row is always rendered however far
 * it is scrolled.
 */
export function GridTable<T>({
  model,
  onRowClick,
  rowMenu,
  selectable = false,
  selected,
  onToggleRow,
  onToggleAll,
  onAtTopChange,
}: Readonly<{ model: TableModel<T> } & GridOnlyProps<T>>) {
  const { data, columns, rowKey, rowHeight, interaction } = model;
  const rowCount = data.length;
  const loadedKeys = useMemo(() => data.map(rowKey), [data, rowKey]);

  const scrollRef = useRef<HTMLDivElement>(null);
  const gridRef = useRef<HTMLDivElement>(null);
  const headerRef = useRef<HTMLDivElement>(null);
  useInlineSize(scrollRef, model.onWidth);
  useStickyEdges(scrollRef, model.overflow);

  // ── Focus model ────────────────────────────────────────────────────────────
  // The active cell, by row key (null for the header row) and column id. A key rather than an index,
  // so a refresh that reorders rows keeps focus on the row the operator was on, and an id so a
  // column that is hidden leaves focus on its neighbour.
  const firstDataCol = selectable ? 1 : 0;
  const hasMenu = rowMenu !== undefined;
  const colIds = useMemo(
    () =>
      withEdgeColumns(
        columns.map((c) => c.id),
        selectable,
        hasMenu,
      ),
    [selectable, columns, hasMenu],
  );
  const colOrder = useMemo(() => withEdgeColumns(model.order, selectable, hasMenu), [selectable, model.order, hasMenu]);
  const [active, setActive] = useState<{ key: string | null; col: string }>({ key: HEADER, col: '' });
  const touched = useRef(false);
  const lastIndex = useRef(1);
  const pendingFocus = useRef(false);
  const focusWithin = useRef(false);
  const quietScrollUntil = useRef(0);

  const activeRow = touched.current
    ? resolveRow(loadedKeys, active.key, lastIndex.current)
    : Math.min(loadedKeys.length, 1);
  const activeCol = Math.max(colIds.indexOf(resolveColumn(active.col, colIds, colOrder, firstDataCol)), 0);
  if (activeRow > 0) lastIndex.current = activeRow;

  const activeIndexRef = useRef(activeRow - 1);
  activeIndexRef.current = activeRow - 1;

  // One shared reveal for the whole grid: a clipped cell has no way to show its full value or let
  // you copy it, so on hover/focus of a cell that is actually clipped we anchor a small panel to it.
  // A single instance, not one per cell: safe against the virtualised row count.
  const panelRef = useRef<HTMLDialogElement>(null);
  const [reveal, setReveal] = useState<Reveal | null>(null);
  const openReveal = useCallback((el: HTMLElement) => {
    if (!isClipped(el)) return;
    const text = el.dataset.full;
    if (!text) return;
    const rect = el.getBoundingClientRect();
    setReveal({ text, x: isRtl() ? globalThis.innerWidth - rect.right : rect.left, y: rect.bottom + 4 });
  }, []);
  // A pointer's reveal waits for intent, unless a panel is showing or has just closed: moving from one
  // clipped cell to the next swaps the panel without the wait.
  const revealShown = useRef(false);
  revealShown.current = reveal !== null;
  const lastShownAt = useRef(Number.NEGATIVE_INFINITY);
  const intent = useRef<ReturnType<typeof setTimeout> | undefined>(undefined);
  const cancelIntent = useCallback(() => globalThis.clearTimeout(intent.current), []);
  useEffect(() => cancelIntent, [cancelIntent]);
  const hoverReveal = useCallback(
    (el: HTMLElement) => {
      cancelIntent();
      if (revealShown.current || performance.now() - lastShownAt.current < REVEAL_INTENT_MS) {
        openReveal(el);
        return;
      }
      intent.current = globalThis.setTimeout(() => {
        if (el.isConnected) openReveal(el);
      }, REVEAL_INTENT_MS);
    },
    [cancelIntent, openReveal],
  );
  const closeReveal = useCallback(
    (e: SyntheticEvent) => {
      cancelIntent();
      // Keep the panel while focus/pointer moves into it (the copy button lives there).
      const next = (e as FocusEvent).relatedTarget as Node | null;
      if (next instanceof Node && panelRef.current?.contains(next)) return;
      if (revealShown.current) lastShownAt.current = performance.now();
      setReveal(null);
    },
    [cancelIntent],
  );

  // The header's height, so a row scrolled into view is not left under the sticky header.
  const [headerHeight, setHeaderHeight] = useState(rowHeight);
  useLayoutEffect(() => {
    const h = headerRef.current?.getBoundingClientRect().height;
    if (h && h > 0 && h < rowHeight * 3 && Math.abs(h - headerHeight) > 0.5) setHeaderHeight(h);
  }, [headerHeight, rowHeight, rowCount]);

  const rangeExtractor = useCallback((range: Range) => {
    // The focused row stays rendered wherever it is scrolled, so focus is never dropped to <body>
    // by the virtualizer unmounting its cell.
    const base = defaultRangeExtractor(range);
    const keep = activeIndexRef.current;
    if (keep < 0 || keep >= range.count || base.includes(keep)) return base;
    return [...base, keep].sort((a, b) => a - b);
  }, []);

  const virtualizer = useVirtualizer({
    count: rowCount,
    getScrollElement: () => scrollRef.current,
    estimateSize: () => rowHeight,
    overscan: OVERSCAN,
    rangeExtractor,
    scrollPaddingStart: headerHeight,
  });
  // The virtualizer does not measure again when `estimateSize` changes: a density change says so.
  useEffect(() => virtualizer.measure(), [virtualizer, rowHeight]);

  const cellAt = useCallback((row: number, col: number): HTMLElement | null => {
    return gridRef.current?.querySelector<HTMLElement>(`[data-grid-row="${row}"] > [data-grid-col="${col}"]`) ?? null;
  }, []);

  const moveTo = useCallback(
    (pos: GridPos) => {
      touched.current = true;
      pendingFocus.current = true;
      const key = pos.row === 0 ? HEADER : (loadedKeys[pos.row - 1] ?? HEADER);
      setActive({ key, col: colIds[pos.col] });
      if (pos.row > 0) {
        quietScrollUntil.current = performance.now() + 250;
        virtualizer.scrollToIndex(pos.row - 1, { align: 'auto' });
      }
    },
    [loadedKeys, colIds, virtualizer],
  );

  // After every render: exactly one element of the grid is in the tab order (the active cell's
  // focus target) and, after a keyboard move, it takes focus. Also recovers focus when the focused
  // row was removed by a refresh.
  useLayoutEffect(() => {
    const grid = gridRef.current;
    if (!grid) return;
    const target = syncTabOrder(grid, activeRow, activeCol);
    const lost = focusWithin.current && (document.activeElement === document.body || document.activeElement === null);
    if (target && (pendingFocus.current || lost)) {
      pendingFocus.current = false;
      target.focus({ preventScroll: true });
    }
  });

  // ── Find ───────────────────────────────────────────────────────────────────
  // The browser's find cannot see rows the virtualizer has not rendered, so the grid searches its
  // data itself and scrolls the virtualizer to each match.
  const scrollToFound = useCallback(
    (row: number) => virtualizer.scrollToIndex(row, { align: 'center' }),
    [virtualizer],
  );
  const { find, setFind, matches, cursor, foundRow } = useGridFind(columns, data, scrollToFound);

  const closeFind = () => {
    const row = foundRow;
    setFind(null);
    if (row >= 0) moveTo({ row: row + 1, col: activeCol });
    else pendingFocus.current = true;
  };

  // ── Row menu ───────────────────────────────────────────────────────────────
  const [menu, setMenu] = useState<OpenMenu | null>(null);
  const suppressContextMenuUntil = useRef(0);
  const dataRef = useRef({ loadedKeys, data });
  dataRef.current = { loadedKeys, data };

  const restoreFocusTo = useCallback(
    (key: string) => {
      const { loadedKeys: keys } = dataRef.current;
      const index = keys.indexOf(key);
      touched.current = true;
      pendingFocus.current = true;
      focusWithin.current = true;
      if (index < 0) {
        // The row is gone: focus the neighbour that took its place, or the header.
        setActive((prev) => ({ key: keys[Math.min(lastIndex.current, keys.length) - 1] ?? HEADER, col: prev.col }));
        return;
      }
      setActive({ key, col: ACTIONS_ID });
      virtualizer.scrollToIndex(index, { align: 'auto' });
    },
    [virtualizer],
  );

  const openMenu = useCallback(
    (key: string, anchor: MenuAnchor) => {
      cancelIntent();
      setReveal(null);
      setMenu({ key, anchor });
    },
    [cancelIntent],
  );

  const menuRef = useRef(menu);
  menuRef.current = menu;
  const closeMenu = useCallback(
    (restore: boolean) => {
      const current = menuRef.current;
      setMenu(null);
      if (current && restore) restoreFocusTo(current.key);
    },
    [restoreFocusTo],
  );

  const menuRow = menu ? dataRef.current.data[dataRef.current.loadedKeys.indexOf(menu.key)] : undefined;

  // ── Events ─────────────────────────────────────────────────────────────────
  const posOf = (el: Element | null): GridPos | null => {
    const cell = el?.closest<HTMLElement>('[data-grid-col]');
    const row = cell?.parentElement?.dataset.gridRow;
    if (!cell || row === undefined) return null;
    return { row: Number(row), col: Number(cell.dataset.gridCol) };
  };

  const onGridFocus = (e: FocusEvent<HTMLDivElement>) => {
    focusWithin.current = true;
    interaction.set('focus', true);
    const pos = posOf(e.target);
    if (!pos) return;
    touched.current = true;
    const key = pos.row === 0 ? HEADER : (loadedKeys[pos.row - 1] ?? HEADER);
    if (key !== active.key || colIds[pos.col] !== active.col) setActive({ key, col: colIds[pos.col] });
    // The reveal follows focus: it closes on the cell focus left, and opens on the one it reached
    // if that cell's value is clipped.
    cancelIntent();
    setReveal(null);
    const cell = e.target.closest<HTMLElement>('[data-grid-col]');
    if (cell && e.target === cell) openReveal(cell);
  };

  const onGridBlur = (e: FocusEvent<HTMLDivElement>) => {
    const next = e.relatedTarget as Node | null;
    if (next && gridRef.current?.contains(next)) return;
    if (next && panelRef.current?.contains(next)) return;
    focusWithin.current = false;
    interaction.set('focus', false);
    closeReveal(e);
  };

  const copyCell = (cell: HTMLElement) => {
    const text = cell.dataset.full;
    if (!text || !navigator.clipboard) return false;
    void navigator.clipboard.writeText(text).then(
      () => model.announce(`Copied ${text}`),
      () => model.announce('Copy failed: the browser refused access to the clipboard.'),
    );
    return true;
  };

  /** Where a key was pressed: the cell, and the row it belongs to when it is in the body. */
  interface KeyTarget {
    pos: GridPos;
    cell: HTMLElement;
    onWidget: boolean;
    row: T | undefined;
    key: string | undefined;
  }

  // Each key handler reports whether the key was its own.
  const rowMenuKey = (e: KeyboardEvent, { pos, cell, key }: KeyTarget): boolean => {
    if (!isMenuKey(e)) return false;
    if (rowMenu && key) {
      e.preventDefault();
      suppressContextMenuUntil.current = performance.now() + 500;
      const trigger = cellAt(pos.row, colIds.length - 1)?.querySelector('button');
      openMenu(key, anchorBelow(trigger ?? cell));
    }
    return true;
  };

  const escapeKey = (e: KeyboardEvent): boolean => {
    if (e.key !== 'Escape' || !reveal) return false;
    setReveal(null);
    return true;
  };

  const copyKey = (e: KeyboardEvent, { cell }: KeyTarget): boolean => {
    if (!isCopyKey(e)) return false;
    if (!globalThis.getSelection()?.toString() && copyCell(cell)) e.preventDefault();
    return true;
  };

  /** Enter activates a row and Space selects it, from the row's cells but not from a control in one. */
  const activateKey = (e: KeyboardEvent, { pos, onWidget, row, key }: KeyTarget): boolean => {
    if (pos.row === 0 || onWidget) return false;
    if (e.key === 'Enter' && row !== undefined && onRowClick) {
      e.preventDefault();
      onRowClick(row);
      return true;
    }
    if (e.key === ' ' && selectable && key !== undefined) {
      e.preventDefault();
      onToggleRow?.(key);
      return true;
    }
    return false;
  };

  // Ctrl+Shift+Left/Right on a header cell resizes its column (ADR-0161); the grid keeps its one
  // tab stop, so this is the keyboard's way to what the border handle does.
  const resizeKey = (e: KeyboardEvent, { pos, cell }: KeyTarget): boolean => {
    const column = pos.row === 0 && isResizeKey(e) ? columns[pos.col - firstDataCol] : undefined;
    if (!column) return false;
    e.preventDefault();
    const grow = (e.key === 'ArrowRight') !== isRtl();
    const current = model.widths[column.id] ?? cell.getBoundingClientRect().width;
    const width = Math.round(Math.max(MIN_COLUMN_WIDTH, current + (grow ? RESIZE_STEP : -RESIZE_STEP)));
    model.setWidth(column.id, width);
    model.announce(`${column.header} column, ${width} pixels`);
    return true;
  };

  const moveKey = (e: KeyboardEvent, pos: GridPos) => {
    if (e.altKey) return;
    const page = Math.max(
      1,
      Math.floor(((scrollRef.current?.clientHeight ?? rowHeight * 10) - headerHeight) / rowHeight) - 1,
    );
    const next = nextCell(pos, e, { rows: rowCount, cols: colIds.length, page, rtl: isRtl() });
    if (!next) return;
    e.preventDefault();
    if (next.row !== pos.row || next.col !== pos.col) moveTo(next);
  };

  const onGridKeyDown = (e: KeyboardEvent<HTMLDivElement>) => {
    if (isFindKey(e)) {
      e.preventDefault();
      setFind((prev) => ({ query: prev?.query ?? '', cursor: prev?.cursor ?? 0, epoch: (prev?.epoch ?? 0) + 1 }));
      return;
    }
    const pos = posOf(e.target as Element);
    if (!pos) return;
    const target = e.target as HTMLElement;
    const cell = target.closest<HTMLElement>('[data-grid-col]')!;
    const body = pos.row > 0;
    const at: KeyTarget = {
      pos,
      cell,
      onWidget: target !== cell,
      row: body ? data[pos.row - 1] : undefined,
      key: body ? loadedKeys[pos.row - 1] : undefined,
    };
    if (rowMenuKey(e, at) || escapeKey(e) || copyKey(e, at) || activateKey(e, at) || resizeKey(e, at)) return;
    moveKey(e, pos);
  };

  const startResize = (e: PointerEvent<HTMLElement>, id: string) => {
    if (e.button !== 0) return;
    e.preventDefault();
    e.stopPropagation();
    const handle = e.currentTarget;
    const start = handle.parentElement!.getBoundingClientRect().width;
    const x0 = e.clientX;
    const sign = isRtl() ? -1 : 1;
    let width = start;
    let moved = false;
    handle.setPointerCapture(e.pointerId);
    handle.dataset.active = 'true';
    const move = (ev: globalThis.PointerEvent) => {
      moved = true;
      width = Math.round(Math.max(MIN_COLUMN_WIDTH, start + (ev.clientX - x0) * sign));
      model.previewWidth(id, width);
    };
    const end = () => {
      handle.removeEventListener('pointermove', move);
      handle.removeEventListener('pointerup', end);
      handle.removeEventListener('pointercancel', end);
      delete handle.dataset.active;
      if (moved) model.setWidth(id, width);
      else model.previewWidth(id, undefined);
    };
    handle.addEventListener('pointermove', move);
    handle.addEventListener('pointerup', end);
    handle.addEventListener('pointercancel', end);
  };

  const onBodyContextMenu = (e: MouseEvent<HTMLDivElement>) => {
    if (!rowMenu) return;
    if (performance.now() < suppressContextMenuUntil.current) {
      // The keyboard already opened the menu; this is the browser's echo of the same key.
      e.preventDefault();
      return;
    }
    const target = e.target as Element;
    // The browser's own menu stays where it is worth more than ours: on a link (open in a new tab),
    // in a field, when Shift asks for it, and when text is selected (copy).
    if (
      e.shiftKey ||
      target.closest('a[href], input, textarea, select, [contenteditable]') ||
      globalThis.getSelection()?.toString()
    ) {
      return;
    }
    const pos = posOf(target);
    if (!pos || pos.row === 0) return;
    const key = loadedKeys[pos.row - 1];
    if (key === undefined) return;
    e.preventDefault();
    touched.current = true;
    focusWithin.current = true;
    setActive({ key, col: colIds[pos.col] });
    openMenu(key, clampToViewport({ x: e.clientX, y: e.clientY }));
  };

  // The rows call these through one stable object, however often the grid renders.
  const latest = useRef({ onRowClick, onToggleRow, menu, closeMenu, openMenu, hoverReveal, closeReveal });
  latest.current = { onRowClick, onToggleRow, menu, closeMenu, openMenu, hoverReveal, closeReveal };
  const handlers = useMemo<RowHandlers<T>>(
    () => ({
      hover: (el) => latest.current.hoverReveal(el),
      hide: (e) => latest.current.closeReveal(e),
      activate: (e, row) => {
        // A control inside the row acts for itself, not for the row.
        if ((e.target as Element).closest(WIDGETS)) return;
        latest.current.onRowClick?.(row);
      },
      toggleRow: (key) => latest.current.onToggleRow?.(key),
      toggleMenu: (key, trigger) => {
        if (latest.current.menu?.key === key) latest.current.closeMenu(true);
        else latest.current.openMenu(key, anchorBelow(trigger));
      },
    }),
    [],
  );

  const menuContext = useMemo<RowMenuContext | null>(
    () => (menu ? { close: () => closeMenu(false), restoreFocus: () => restoreFocusTo(menu.key) } : null),
    [menu, closeMenu, restoreFocusTo],
  );

  const trackCount = colIds.length;
  const placeholder = model.loading && rowCount === 0;
  const actionsCol = colIds.length - 1;

  return (
    <>
      {find ? (
        <GridFind
          key={find.epoch}
          label={model.label}
          query={find.query}
          onQuery={(next) => setFind((prev) => prev && { ...prev, query: next, cursor: 0 })}
          count={matches.length}
          current={cursor}
          onStep={(by) => setFind((prev) => prev && { ...prev, cursor: stepMatch(cursor, matches.length, by) })}
          onClose={closeFind}
        />
      ) : null}
      <div
        ref={scrollRef}
        className={classes.scroll}
        data-overflow={model.overflow || undefined}
        data-capped={model.maxRows === undefined ? undefined : true}
        data-empty={rowCount === 0 && !model.loading ? true : undefined}
        style={scrollStyle(model, selectable, hasMenu)}
        onScroll={(e) => {
          if (performance.now() > quietScrollUntil.current) {
            cancelIntent();
            if (reveal) setReveal(null);
          }
          if (menu) closeMenu(false);
          const atTop = e.currentTarget.scrollTop <= 4;
          interaction.set('scrolled', !atTop);
          onAtTopChange?.(atTop);
        }}
        onPointerEnter={() => interaction.set('pointer', true)}
        onPointerLeave={() => interaction.set('pointer', false)}
      >
        {model.measurer}
        <div
          ref={gridRef}
          className={classes.grid}
          role="grid"
          aria-label={model.label}
          aria-busy={model.loading || undefined}
          aria-rowcount={rowCount + 1}
          aria-colcount={colIds.length}
          onFocus={onGridFocus}
          onBlur={onGridBlur}
          onKeyDown={onGridKeyDown}
        >
          {model.overflow ? (
            <>
              <span data-sentinel="start" className={classes.sentinelStart} />
              <span data-sentinel="end" className={classes.sentinelEnd} />
            </>
          ) : null}
          {/* Directly under the grid, not wrapped in a `rowgroup`: a sticky element can only travel
            inside its containing block, and a wrapper sized to the header itself would leave it
            nothing to travel through. */}
          <div
            ref={headerRef}
            className={`${classes.row} ${classes.headRow}`}
            role="row"
            aria-rowindex={1}
            data-grid-row={0}
          >
            {selectable ? <SelectAllCell keys={loadedKeys} selected={selected} onToggleAll={onToggleAll} /> : null}
            {columns.map((column, i) => (
              <HeaderCell
                key={column.id}
                as="div"
                column={column}
                className={i === 0 ? classes.stickyFirst : undefined}
                grid={{ col: firstDataCol + i, index: firstDataCol + i + 1 }}
                sorting={sortingOf(model.sort, column.sortKey, model.onSortChange !== undefined)}
                onSort={() => model.onSortChange?.(nextSort(model.sort, column.sortKey!))}
                resize={{ onStart: (e) => startResize(e, column.id), onFit: () => model.fit(column.id) }}
              />
            ))}
            {hasMenu ? (
              <div
                role="columnheader"
                aria-colindex={actionsCol + 1}
                data-grid-col={actionsCol}
                className={`${classes.cell} ${classes.headCell} ${classes.stickyEnd}`}
              >
                <VisuallyHidden>Actions</VisuallyHidden>
              </div>
            ) : null}
          </div>

          <div
            className={classes.body}
            role="rowgroup"
            style={{ blockSize: virtualizer.getTotalSize() }}
            onContextMenu={onBodyContextMenu}
          >
            {virtualizer.getVirtualItems().map((vi) => {
              const row = data[vi.index];
              const key = loadedKeys[vi.index];
              return (
                <GridRow
                  key={key}
                  row={row}
                  rowKey={key}
                  index={vi.index}
                  start={vi.start}
                  columns={columns}
                  selectable={selectable}
                  selected={selected?.has(key) ?? false}
                  menuOpen={menu?.key === key}
                  found={vi.index === foundRow}
                  menuLabel={rowMenu ? rowMenu.label(row) : undefined}
                  clickable={onRowClick !== undefined}
                  className={model.rowClassName?.(row)}
                  handlers={handlers}
                />
              );
            })}
          </div>
        </div>

        {placeholder ? (
          <>
            <SkeletonRows tracks={trackCount} />
            <LoadingLabel label={model.label} />
          </>
        ) : null}

        {rowMenu && menu && menuRow !== undefined && menuContext ? (
          <AnchoredMenu
            opened
            anchor={menu.anchor}
            label={`Actions for ${rowMenu.label(menuRow)}`}
            onClose={() => closeMenu(true)}
          >
            {rowMenu.render(menuRow, menuContext)}
          </AnchoredMenu>
        ) : null}

        {reveal ? <RevealPanel reveal={reveal} panelRef={panelRef} onHide={closeReveal} /> : null}
      </div>
    </>
  );
}
