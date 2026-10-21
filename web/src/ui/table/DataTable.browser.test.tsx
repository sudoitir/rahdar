import { describe, expect, it, vi } from 'vitest';
import { userEvent } from 'vitest/browser';
import { screen } from '@testing-library/react';

import { axeViolations, Frame, renderThemed, SCHEMES, settle } from '../../test/browser.tsx';
import { DataTable } from './DataTable.tsx';
import type { Column } from './columns.ts';

/**
 * The column-width cases that needed a real layout (ADR-0116, replaced by ADR-0161). In jsdom they
 * mocked `scrollWidth` and so passed while the browser did something else; here Chromium lays the
 * table out with the bundled typefaces, and the widths are measured, not set.
 */

interface Q {
  name: string;
  type: string;
  depth: number;
}

const LONG = 'X'.repeat(30);

const rows: Q[] = [
  { name: 'ORDERS', type: 'MULTICAST', depth: 12 },
  { name: 'SHIPMENTS', type: 'ANYCAST', depth: 0 },
  { name: LONG, type: 'ANYCAST', depth: 431 },
];

const identifier: Column<Q> = {
  id: 'name',
  header: 'Queue',
  accessor: (r) => r.name,
  kind: 'identifier',
  priority: 'essential',
};

const depth: Column<Q> = { id: 'depth', header: 'Depth', accessor: (r) => r.depth, kind: 'number', priority: 'high' };

/** A text column that is as wide as its content, so its width is the fit and not a share of spare room. */
const nameColumn: Column<Q> = {
  id: 'name',
  header: 'Queue',
  accessor: (r) => r.name,
  kind: 'text',
  priority: 'essential',
  grow: false,
};

async function mount(columns: Column<Q>[], data: Q[], onSortChange?: (sort: string | undefined) => void) {
  const { container } = renderThemed(
    <Frame width={960}>
      <DataTable
        label="Queues"
        columns={columns}
        data={data}
        rowKey={(r) => r.name}
        empty={null}
        onSortChange={onSortChange}
      />
    </Frame>,
    'light',
  );
  const grid = await screen.findByRole('grid');
  const scroller = grid.parentElement!;
  const heads = () => [...grid.querySelectorAll<HTMLElement>('[role="columnheader"]')];
  await settle(() =>
    heads()
      .map((h) => h.getBoundingClientRect().width)
      .join(','),
  );
  return { container, grid, scroller, heads };
}

const header = (heads: () => HTMLElement[], name: string) => heads().find((h) => h.textContent.trim() === name)!;

/** What `ch` and a cell's padding come to in the table's own typeface. */
function metrics(scroller: HTMLElement, cell: HTMLElement) {
  const probe = document.createElement('div');
  probe.style.cssText = 'position:absolute;visibility:hidden;inline-size:100ch';
  scroller.append(probe);
  const ch = probe.getBoundingClientRect().width / 100;
  probe.remove();
  const style = getComputedStyle(cell);
  return { ch, pad: Number.parseFloat(style.paddingInlineStart) + Number.parseFloat(style.paddingInlineEnd) };
}

const textWidth = (cell: HTMLElement) => {
  const range = document.createRange();
  range.selectNodeContents(cell);
  return range.getBoundingClientRect().width;
};

const bodyCell = (grid: HTMLElement, row: number, col: string | undefined) =>
  grid.querySelector<HTMLElement>(`[data-grid-row="${row}"] > [data-grid-col="${col}"]`)!;

describe('DataTable column widths in a real browser', () => {
  it('is only its header when it has no rows, so the empty message sits under it without a blank frame', async () => {
    const { scroller, heads } = await mount([nameColumn, depth], []);
    expect(scroller.getBoundingClientRect().height).toBeLessThanOrEqual(heads()[0]!.getBoundingClientRect().height * 2);
  });

  it('widens a fixed column whose value needs more than its header does', async () => {
    const type: Column<Q> = { id: 'type', header: 'T', accessor: (r) => r.type, kind: 'status', priority: 'high' };
    const { grid, scroller, heads } = await mount([nameColumn, type], rows);
    const head = header(heads, 'T');
    const cell = bodyCell(grid, 1, head.dataset.gridCol);
    // MULTICAST needs far more than the one-letter header does, and it is shown whole.
    expect(cell.textContent).toBe('MULTICAST');
    expect(cell.scrollWidth).toBeLessThanOrEqual(cell.clientWidth);
    expect(head.getBoundingClientRect().width).toBeGreaterThanOrEqual(textWidth(cell) + metrics(scroller, cell).pad);
  });

  it('fits a free-text column to its widest value, within the cap', async () => {
    const { grid, scroller, heads } = await mount([nameColumn, depth], rows);
    const head = header(heads, 'Queue');
    const widest = bodyCell(grid, 3, head.dataset.gridCol);
    const { ch, pad } = metrics(scroller, widest);
    const width = head.getBoundingClientRect().width;
    expect(widest.scrollWidth, 'the widest value is shown whole').toBeLessThanOrEqual(widest.clientWidth);
    // The column is its widest value and its padding, to within the pixel the measurement rounds up, and
    // that is between the free-text floor and cap.
    expect(width).toBeGreaterThanOrEqual(textWidth(widest) + pad);
    expect(width).toBeLessThanOrEqual(textWidth(widest) + pad + 2);
    expect(width).toBeGreaterThan(12 * ch + pad);
    expect(width).toBeLessThan(48 * ch + pad);
  });

  it('never fits a column below the free-text floor or past the cap', async () => {
    const short = await mount([nameColumn, depth], [{ name: 'a', type: '', depth: 1 }]);
    const shortHead = header(short.heads, 'Queue');
    const small = metrics(short.scroller, bodyCell(short.grid, 1, shortHead.dataset.gridCol));
    expect(Math.abs(shortHead.getBoundingClientRect().width - (12 * small.ch + small.pad))).toBeLessThanOrEqual(2);
    short.container.remove();

    const huge = await mount([nameColumn, depth], [{ name: 'X'.repeat(300), type: '', depth: 1 }]);
    const hugeHead = header(huge.heads, 'Queue');
    const cell = bodyCell(huge.grid, 1, hugeHead.dataset.gridCol);
    const big = metrics(huge.scroller, cell);
    expect(Math.abs(hugeHead.getBoundingClientRect().width - (48 * big.ch + big.pad))).toBeLessThanOrEqual(2);
    expect(cell.scrollWidth, 'a value past the cap is cut, not widened to').toBeGreaterThan(cell.clientWidth);
  });

  it('fits a column to its content on a double click, without sorting', async () => {
    const onSortChange = vi.fn();
    const { grid, heads } = await mount([{ ...nameColumn, sortKey: 'name' }, depth], rows, onSortChange);
    const head = header(heads, 'Queue');
    const fitted = head.getBoundingClientRect().width;

    head.focus();
    await userEvent.keyboard(
      '{Control>}{Shift>}{ArrowRight}{ArrowRight}{ArrowRight}{ArrowRight}{ArrowRight}{/Shift}{/Control}',
    );
    await settle(() => String(head.getBoundingClientRect().width));
    expect(head.getBoundingClientRect().width, 'five 16 px steps wider').toBe(fitted + 80);

    const handle = head.querySelector<HTMLElement>(':scope > span[aria-hidden="true"]:last-child')!;
    await userEvent.dblClick(handle);
    await settle(() => String(head.getBoundingClientRect().width));
    expect(Math.abs(head.getBoundingClientRect().width - fitted)).toBeLessThanOrEqual(1);
    expect(onSortChange).not.toHaveBeenCalled();
    expect(grid.querySelectorAll('[role="columnheader"]')).toHaveLength(2);
  });

  describe.each(SCHEMES)('in the %s scheme', (scheme) => {
    it('has no accessibility violations', async () => {
      const { container } = renderThemed(
        <Frame width={960}>
          <DataTable label="Queues" columns={[nameColumn, depth]} data={rows} rowKey={(r) => r.name} empty={null} />
        </Frame>,
        scheme,
      );
      await screen.findByRole('grid');
      expect(await axeViolations(container)).toEqual([]);
    });
  });

  describe('as a static table', () => {
    const staticTable = (width: number) => (
      <Frame width={width}>
        <DataTable
          variant="static"
          label="Queues"
          columns={[nameColumn, depth]}
          data={rows}
          rowKey={(r) => r.name}
          empty={null}
        />
      </Frame>
    );

    it.each(SCHEMES)('has no accessibility violations in the %s scheme', async (scheme) => {
      const { container } = renderThemed(staticTable(960), scheme);
      await screen.findByRole('table');
      expect(await axeViolations(container)).toEqual([]);
    });

    it('lays out fixed, so the solved widths hold and a long value is clipped by its column', async () => {
      const wide = 'q'.repeat(200);
      renderThemed(
        <Frame width={960}>
          <DataTable
            variant="static"
            label="Queues"
            columns={[identifier, depth]}
            data={[{ name: wide, type: 'ANYCAST', depth: 1 }]}
            rowKey={(r) => r.name}
            empty={null}
          />
        </Frame>,
        'light',
      );
      const table = await screen.findByRole('table');
      expect(getComputedStyle(table).tableLayout).toBe('fixed');
      const frame = table.parentElement!;
      await settle(() => `${table.getBoundingClientRect().width}`);
      expect(frame.scrollWidth).toBeLessThanOrEqual(frame.clientWidth);
      // The identifier keeps its end and gives up its start; it does not widen the column.
      expect(table.querySelector('tbody th [data-clip]')).not.toBeNull();
      expect(screen.getByText(wide)).toBeInTheDocument();
    });

    it.each(SCHEMES)('names and focuses a frame that scrolls sideways, in the %s scheme', async (scheme) => {
      const { container } = renderThemed(staticTable(120), scheme);
      const table = await screen.findByRole('table');
      const frame = table.parentElement!;
      await settle(() => `${frame.scrollWidth},${frame.clientWidth}`);
      expect(frame.scrollWidth).toBeGreaterThan(frame.clientWidth);
      expect(screen.getByRole('region', { name: 'Queues, scrollable' })).toBe(frame);
      expect(frame).toHaveAttribute('tabindex', '0');
      expect(await axeViolations(container)).toEqual([]);
    });

    it('starts each header where its cells start, and ends a number header where its figures end', async () => {
      renderThemed(staticTable(960), 'light');
      await screen.findByRole('table');
      const [name, count] = screen.getAllByRole('columnheader');
      expect(getComputedStyle(name!).textAlign).toBe('start');
      expect(getComputedStyle(count!).textAlign).toBe('end');
    });

    it('is not a stop of its own when it fits', async () => {
      renderThemed(staticTable(960), 'light');
      const table = await screen.findByRole('table');
      await settle(() => `${table.getBoundingClientRect().width}`);
      expect(table.parentElement).not.toHaveAttribute('tabindex');
      expect(screen.queryByRole('region')).not.toBeInTheDocument();
    });

    it('holds the height its loading rows had when a failure replaces them', async () => {
      const table = (state: { loading?: boolean; error?: boolean }) => (
        <Frame width={960} height={900}>
          <DataTable
            variant="static"
            height={{ maxRows: 8 }}
            label="Queues"
            columns={[nameColumn, depth]}
            data={[]}
            rowKey={(r) => r.name}
            empty={null}
            loading={state.loading}
            error={state.error ? <div role="alert">Could not load the queues.</div> : undefined}
          />
          <p>Below the table</p>
        </Frame>
      );
      const { rerender } = renderThemed(table({ loading: true }), 'light');
      const below = screen.getByText('Below the table');
      await settle(() => `${below.getBoundingClientRect().top}`);
      const loading = below.getBoundingClientRect().top;

      rerender(table({ error: true }));
      await screen.findByRole('alert');
      await settle(() => `${below.getBoundingClientRect().top}`);

      expect(Math.abs(below.getBoundingClientRect().top - loading)).toBeLessThanOrEqual(1);
    });

    it('lines each header cell up over its column and fits its box', async () => {
      renderThemed(staticTable(960), 'light');
      const table = await screen.findByRole('table');
      await settle(() => [...table.querySelectorAll('th')].map((th) => th.getBoundingClientRect().width).join(','));
      const frame = table.parentElement!;
      expect(frame.scrollWidth).toBeLessThanOrEqual(frame.clientWidth);
      const heads = [...table.querySelectorAll('thead th')];
      const first = [...table.querySelectorAll('tbody tr:first-child > *')];
      expect(first).toHaveLength(heads.length);
      heads.forEach((head, i) => {
        expect(Math.abs(head.getBoundingClientRect().left - first[i].getBoundingClientRect().left)).toBeLessThanOrEqual(
          1,
        );
      });
    });
  });
});
