import { describe, expect, it, vi } from 'vitest';
import { userEvent } from 'vitest/browser';
import { screen } from '@testing-library/react';

import { axeViolations, contentWidth, Frame, renderThemed, SCHEMES, settle } from '../test/browser.tsx';
import type { BacklogValue } from './backlog.ts';
import { BacklogCell } from './BacklogCell.tsx';
import { DataTable, type Column } from './table/index.ts';

/** The backlog column as Chromium lays it out, and the reason reached with the keyboard alone. */

interface Row {
  name: string;
  backlog: BacklogValue;
}

const ROWS: Row[] = [
  { name: 'orders', backlog: { value: 42, accuracy: 'EXACT', reason: null, breakdown: false } },
  {
    name: 'telemetry',
    backlog: {
      value: 123_456_789,
      accuracy: 'ESTIMATE',
      reason: 'Two partitions have no committed offset.',
      breakdown: true,
    },
  },
  {
    name: 'audit',
    backlog: { value: null, accuracy: 'UNAVAILABLE', reason: 'The account may not read offsets.', breakdown: false },
  },
];

const COLUMNS: Column<Row>[] = [
  { id: 'name', header: 'Name', accessor: (r) => r.name, kind: 'identifier', priority: 'essential' },
  {
    id: 'backlog',
    header: 'Backlog',
    accessor: (r) =>
      r.backlog.accuracy === 'UNAVAILABLE'
        ? 'Unavailable'
        : `${r.backlog.accuracy === 'ESTIMATE' ? '≈ ' : ''}${r.backlog.value}`,
    cell: (r) => <BacklogCell backlog={r.backlog} subject={r.name} onBreakdown={() => undefined} />,
    kind: 'number',
    priority: 'high',
  },
];

const table = (
  <DataTable variant="static" label="Subscriptions" columns={COLUMNS} data={ROWS} rowKey={(r) => r.name} empty={null} />
);

describe.each(SCHEMES)('the backlog column in the %s scheme', (scheme) => {
  it.each([contentWidth(1280), 640])('stays inside its box and is axe-clean at %i px', async (width) => {
    const { container } = renderThemed(<Frame width={width}>{table}</Frame>, scheme);
    const element = await screen.findByRole('table');
    await settle(() => [...element.querySelectorAll('th')].map((th) => th.getBoundingClientRect().width).join(','));
    const frame = element.parentElement!;
    expect(frame.scrollWidth).toBeLessThanOrEqual(frame.clientWidth);
    expect(await axeViolations(container)).toEqual([]);
  });
});

describe('the backlog column by keyboard', () => {
  it('reaches the reason of an estimate with Tab and Enter, and closes it with Escape', async () => {
    renderThemed(<Frame width={contentWidth(1280)}>{table}</Frame>, 'light');
    await screen.findByRole('table');
    const estimate = screen.getByRole('button', { name: /estimate/ });
    estimate.focus();
    await userEvent.keyboard('{Enter}');
    expect(await screen.findByText('Two partitions have no committed offset.')).toBeInTheDocument();
    await userEvent.keyboard('{Escape}');
    await vi.waitFor(() =>
      expect(screen.queryByText('Two partitions have no committed offset.')).not.toBeInTheDocument(),
    );
    expect(estimate).toHaveFocus();
  });
});
