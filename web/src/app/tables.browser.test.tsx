import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import { userEvent } from 'vitest/browser';
import { onlineManager, QueryClient, QueryClientProvider } from '@tanstack/react-query';
import {
  createMemoryHistory,
  createRootRoute,
  createRoute,
  createRouter,
  RouterProvider,
} from '@tanstack/react-router';
import { render, screen } from '@testing-library/react';

import { ActionHostProvider } from '../kernel/actions/ActionHost.tsx';
import { FeatureProvider } from '../kernel/FeatureProvider.tsx';
import { KIND_PRESETS } from '../ui/table/index.ts';
import { axeViolations, contentWidth, Frame, SCHEMES, settle, Themed, type Scheme } from '../test/browser.tsx';
import { VIEWS, type Fixture, type TableView_ } from '../test/tableViews.tsx';
import { FEATURES } from './features.ts';

/**
 * Every grid's real columns, laid out by Chromium (ADR-0165). jsdom has no layout, which is how the
 * column-fitting defect of ADR-0161 passed its tests, so nothing here is mocked: the views' own
 * `columns.ts` factories, the real theme, the real style sheets and the bundled typefaces.
 */

/**
 * The two windows the console is laid out for at either end of its range (ADR-0164), at 100% zoom with
 * the navigation expanded. A table gets the content box of its window, not the window: at 1280 px that is
 * `contentWidth(1280)`, the window less the navigation, the shell's padding and the page's scroll bar, and
 * it is what every width-dependent assertion here is made against.
 */
const WINDOWS = [1280, 1920] as const;

// No network: queries behind a cell (permissions, the cluster's capabilities) stay pending, which
// the controls render as "not known yet", enabled.
beforeAll(() => onlineManager.setOnline(false));
afterAll(() => onlineManager.setOnline(true));

interface Mounted {
  grid: HTMLElement;
  scroller: HTMLElement;
  container: HTMLElement;
}

/** The view's table in a box `width` px wide, in `scheme`, once its columns have been sized. */
async function mount(view: TableView_, fixture: Fixture, width: number, scheme: Scheme = 'light'): Promise<Mounted> {
  const root = createRootRoute();
  const page = createRoute({
    getParentRoute: () => root,
    path: '/clusters/$clusterId',
    component: () => <Frame width={width}>{view.table(fixture)}</Frame>,
  });
  const router = createRouter({
    routeTree: root.addChildren([page]),
    history: createMemoryHistory({ initialEntries: ['/clusters/cluster-1'] }),
  });
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  const { container } = render(
    <Themed scheme={scheme}>
      <QueryClientProvider client={client}>
        <FeatureProvider features={FEATURES}>
          <ActionHostProvider>
            <RouterProvider router={router} />
          </ActionHostProvider>
        </FeatureProvider>
      </QueryClientProvider>
    </Themed>,
  );
  const grid = await screen.findByRole('grid');
  const scroller = grid.parentElement!;
  await settle(
    () => [...headerCells(grid)].map((c) => c.getBoundingClientRect().width).join(',') + scroller.scrollWidth,
  );
  return { grid, scroller, container };
}

const headerCells = (grid: HTMLElement) => grid.querySelectorAll<HTMLElement>('[role="columnheader"]');
const frame = () => new Promise<void>((resolve) => requestAnimationFrame(() => resolve()));

/** The ids of the data columns the grid draws, in order: its header cells by name, the checkbox and menu columns aside. */
function visibleIds(view: TableView_, grid: HTMLElement): string[] {
  return [...headerCells(grid)].flatMap((cell) => {
    const column = view.columns.find((c) => c.label === cell.textContent.trim());
    return column ? [column.id] : [];
  });
}

const overflows = (scroller: HTMLElement) => scroller.scrollWidth > scroller.clientWidth;
const ALL = (view: TableView_) => view.columns.map((c) => c.id);
const identity = (view: TableView_) =>
  view.columns.filter((c, i) => i === 0 || c.priority === 'essential').map((c) => c.id);

/** A header cell and the cell of the first row under it share their edges, to within a pixel. */
function expectAligned(grid: HTMLElement) {
  for (const head of headerCells(grid)) {
    const body = grid.querySelector<HTMLElement>(`[data-grid-row="1"] > [data-grid-col="${head.dataset.gridCol}"]`);
    expect(body, `a cell under header ${head.textContent}`).not.toBeNull();
    const h = head.getBoundingClientRect();
    const b = body!.getBoundingClientRect();
    expect(Math.abs(h.left - b.left), `${head.textContent.trim() || 'select'} column inline-start`).toBeLessThanOrEqual(
      1,
    );
    expect(Math.abs(h.width - b.width), `${head.textContent.trim() || 'select'} column width`).toBeLessThanOrEqual(1);
  }
}

/** The inline-end edge of the last text a cell draws: where its value or its label stops. */
function textEnd(cell: HTMLElement): number | undefined {
  const walker = document.createTreeWalker(cell, NodeFilter.SHOW_TEXT);
  let last: Text | null = null;
  for (let node = walker.nextNode(); node; node = walker.nextNode()) if (node.textContent?.trim()) last = node as Text;
  if (!last) return undefined;
  const range = document.createRange();
  range.selectNodeContents(last);
  return range.getBoundingClientRect().right;
}

/** A column of figures ends its header's name where its figures end, so a name sits over its values. */
function expectFiguresUnderTheirHeaders(grid: HTMLElement) {
  for (const head of headerCells(grid)) {
    const body = grid.querySelector<HTMLElement>(`[data-grid-row="1"] > [data-grid-col="${head.dataset.gridCol}"]`);
    if (!body?.hasAttribute('data-end')) continue;
    const name = textEnd(head);
    const value = textEnd(body);
    if (name === undefined || value === undefined) continue;
    expect(
      Math.abs(name - value),
      `${head.textContent.trim()} ends at ${name}, its figures at ${value}`,
    ).toBeLessThanOrEqual(1);
  }
}

/** The Columns control says how many columns are not drawn, in its name. */
function expectHiddenAnnounced(view: TableView_, grid: HTMLElement) {
  const hidden = view.columns.length - visibleIds(view, grid).length;
  const control = screen.getByRole('button', { name: /^Columns/ });
  if (hidden === 0) expect(control).toHaveAccessibleName('Columns');
  else expect(control).toHaveAccessibleName(new RegExp(String.raw`^Columns\s*,\s*${hidden} hidden$`));
}

/** The cells that must stay in view when the table scrolls sideways: the checkbox, identity and menu columns. */
function stickyCells(view: TableView_, grid: HTMLElement) {
  const edge = (selector: string) => [...grid.querySelectorAll<HTMLElement>(selector)];
  const first = edge('[data-first], [role="columnheader"][data-grid-col="' + (view.selectable ? 1 : 0) + '"]');
  const select = view.selectable ? edge('[data-grid-col="0"]') : [];
  const last = grid.querySelector('[role="row"]')!.lastElementChild!.getAttribute('data-grid-col');
  const menu = view.menu ? edge(`[data-grid-col="${last}"]`) : [];
  return { first, select, menu };
}

async function expectStickyWhenScrolled(view: TableView_, { grid, scroller }: Mounted) {
  const { first, select, menu } = stickyCells(view, grid);
  for (const cell of [...first, ...select, ...menu]) {
    expect(getComputedStyle(cell).position, `${cell.getAttribute('role')} at column ${cell.dataset.gridCol}`).toBe(
      'sticky',
    );
  }
  for (const to of [scroller.scrollWidth, scroller.scrollWidth / 2]) {
    scroller.scrollLeft = to;
    await frame();
    const box = scroller.getBoundingClientRect();
    for (const cell of [...first, ...select, ...menu]) {
      const r = cell.getBoundingClientRect();
      expect(r.left, `column ${cell.dataset.gridCol} stays inside after scrolling to ${to}`).toBeGreaterThanOrEqual(
        box.left - 1,
      );
      expect(r.left).toBeLessThan(box.right);
      if (cell.dataset.gridCol !== undefined && !first.includes(cell))
        expect(r.right).toBeLessThanOrEqual(box.right + 1);
    }
  }
  scroller.scrollLeft = 0;
}

/**
 * Whether the last `n` characters of a cell's name are drawn inside the cell rather than clipped by it. The
 * name is the last text of the cell that is long enough to hold them: a figure or a count after it is not.
 * The whole value a shortened identifier keeps for assistive technology is out of sight, so it is not drawn text.
 */
function endIsVisible(cell: HTMLElement, n = 8): boolean {
  const walker = document.createTreeWalker(cell, NodeFilter.SHOW_TEXT);
  let last: Text | null = null;
  for (let node = walker.nextNode(); node; node = walker.nextNode()) {
    if ((node.textContent?.trim().length ?? 0) >= n && !node.parentElement?.closest('[data-whole]'))
      last = node as Text;
  }
  if (!last?.textContent) return false;
  const range = document.createRange();
  range.setStart(last, last.textContent.length - n);
  range.setEnd(last, last.textContent.length);
  const text = range.getBoundingClientRect();
  const box = cell.getBoundingClientRect();
  return text.width > 0 && text.left >= box.left - 0.5 && text.right <= box.right + 0.5;
}

describe.each(VIEWS)('the $name table', (view) => {
  describe.each(WINDOWS)('in a %i px window', (viewport) => {
    const width = contentWidth(viewport);

    it('fits with the expected columns, aligned, with every identifying column showing a normal fixture', async () => {
      const m = await mount(view, 'normal', width);
      expect(
        overflows(m.scroller),
        `scrollWidth ${m.scroller.scrollWidth} > clientWidth ${m.scroller.clientWidth}`,
      ).toBe(false);
      const shown = visibleIds(view, m.grid);
      const used = [...headerCells(m.grid)].reduce((total, cell) => total + cell.getBoundingClientRect().width, 0);
      expect(
        shown,
        `${shown.length} of ${view.columns.length} columns take ${used} of ${m.scroller.clientWidth} px`,
      ).toEqual(viewport === 1920 ? ALL(view) : view.visible1280);
      expectHiddenAnnounced(view, m.grid);
      expectAligned(m.grid);
      expectFiguresUnderTheirHeaders(m.grid);
    });

    it('keeps the essential columns, names what it hides and stays aligned with long names', async () => {
      const m = await mount(view, 'long', width);
      const shown = visibleIds(view, m.grid);
      expect(shown).toEqual(expect.arrayContaining(identity(view)));
      expectHiddenAnnounced(view, m.grid);
      expectAligned(m.grid);
      if (overflows(m.scroller)) {
        await expectStickyWhenScrolled(view, m);
        expectAligned(m.grid);
      }
    });
  });

  // A table with one essential column only scrolls once that column alone does not fit, and a sticky
  // column wider than the window cannot stay in it, so those are not forced.
  it.runIf(identity(view).length >= 2)(
    'keeps its identity, checkbox and menu columns in view when it must scroll',
    async () => {
      // The widest window in which the essential columns do not fit: the sticky columns still do.
      let m: Mounted | undefined;
      for (let width = 700; width >= 160 && !(m && overflows(m.scroller)); width -= 20) {
        m?.container.remove();
        m = await mount(view, 'long', width);
      }
      expect(m && overflows(m.scroller), 'a window narrower than the essential columns scrolls').toBe(true);
      expect(m!.scroller).toHaveAttribute('data-overflow');
      await expectStickyWhenScrolled(view, m!);
      m!.scroller.scrollLeft = m!.scroller.scrollWidth;
      await frame();
      expectAligned(m!.grid);
    },
  );

  it.runIf(view.identifiers.length > 0)('keeps names that differ only in their last characters apart', async () => {
    for (const viewport of WINDOWS) {
      const width = contentWidth(viewport);
      const m = await mount(view, 'long', width);
      const shown = visibleIds(view, m.grid);
      for (const id of view.identifiers.filter((i) => shown.includes(i))) {
        const { header, label } = view.columns.find((c) => c.id === id)!;
        const col = [...headerCells(m.grid)].find((c) => c.textContent.trim() === label)!.dataset.gridCol;
        const cells = [1, 2].map((row) =>
          m.grid.querySelector<HTMLElement>(`[data-grid-row="${row}"] > [data-grid-col="${col}"]`)!,
        );
        for (const cell of cells) {
          expect
            .soft(
              endIsVisible(cell),
              `${header} in a ${viewport} px window (${Math.round(cell.getBoundingClientRect().width)} px wide): the end of "${cell.textContent}" is clipped`,
            )
            .toBe(true);
        }
      }
      m.container.remove();
    }
  });

  describe('Ctrl+Shift+ArrowRight twice', () => {
    async function widen(head: HTMLElement) {
      const before = head.getBoundingClientRect().width;
      head.focus();
      await userEvent.keyboard('{Control>}{Shift>}{ArrowRight}{ArrowRight}{/Shift}{/Control}');
      await settle(() => String(head.getBoundingClientRect().width));
      return head.getBoundingClientRect().width - before;
    }

    it('widens a column that is as wide as its content by exactly 32 px', async () => {
      const m = await mount(view, 'normal', 1920);
      const fixed = view.columns.find((c) => !KIND_PRESETS[c.kind].grow)!;
      const head = [...headerCells(m.grid)].find((c) => c.textContent.trim() === fixed.label)!;
      expect(await widen(head)).toBe(32);
    });

    it('widens the identifying column, which shares spare width, by 32 px to within a rounding', async () => {
      const m = await mount(view, 'normal', 1920);
      const head = [...headerCells(m.grid)].find((c) => c.textContent.trim() === view.columns[0].label)!;
      // Its width before the first press is a share of the spare room and so is rarely whole; the
      // first step rounds it, so the second ends up to half a pixel from 32 beyond where it began.
      expect(Math.abs((await widen(head)) - 32)).toBeLessThanOrEqual(0.5);
    });
  });

  describe.each(SCHEMES)('in the %s scheme', (scheme) => {
    it('has no accessibility violations', async () => {
      const m = await mount(view, 'normal', contentWidth(1280), scheme);
      expect(await axeViolations(m.container)).toEqual([]);
    });
  });
});

describe('the subscriptions table in a 1280 px window', () => {
  const view = VIEWS.find((v) => v.name === 'subscriptions')!;
  // Two operators' screens differ by a scroll bar or a wider figure; the table is sized to hold both with room over.
  const SPARE = 16;

  it('shows every column but the lowest-priority ones, whole, with 16 px to spare', async () => {
    const m = await mount(view, 'normal', contentWidth(1280) - SPARE);
    // Backlog is a character wider than Depth was: with a scroll bar's width to spare, the lowest-priority
    // column, Scheduled, is the one left out. With none to spare it still fits (the test above).
    expect(visibleIds(view, m.grid)).toEqual(view.visible1280.filter((id) => id !== 'scheduled'));
    expect(overflows(m.scroller), `scrollWidth ${m.scroller.scrollWidth} > clientWidth ${m.scroller.clientWidth}`).toBe(
      false,
    );
  });
});
