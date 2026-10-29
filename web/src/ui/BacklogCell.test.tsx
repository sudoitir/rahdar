import { describe, expect, it, vi } from 'vitest';
import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

import { renderWithProviders } from '../test/render.tsx';
import { BacklogCell } from './BacklogCell.tsx';
import { backlogWords } from './backlog.ts';

describe('backlogWords', () => {
  it('writes an exact figure plain, an estimate with ≈, and an unknown one as Unavailable, never 0', () => {
    expect(backlogWords({ value: 12400, accuracy: 'EXACT', reason: null, breakdown: false })).toBe(
      (12400).toLocaleString(),
    );
    expect(backlogWords({ value: 12400, accuracy: 'ESTIMATE', reason: null, breakdown: true })).toBe(
      `≈ ${(12400).toLocaleString()}`,
    );
    expect(backlogWords({ value: null, accuracy: 'UNAVAILABLE', reason: 'x', breakdown: false })).toBe('Unavailable');
    expect(backlogWords({ value: 0, accuracy: 'UNAVAILABLE', reason: 'x', breakdown: false })).toBe('Unavailable');
  });
});

describe('BacklogCell', () => {
  it('is plain text for an exact figure without a breakdown', () => {
    renderWithProviders(
      <BacklogCell backlog={{ value: 42, accuracy: 'EXACT', reason: null, breakdown: false }} subject="orders" />,
    );
    expect(screen.getByText('42')).toBeInTheDocument();
    expect(screen.queryByRole('button')).not.toBeInTheDocument();
  });

  it('marks an estimate in its accessible name and gives the reason by keyboard', async () => {
    const user = userEvent.setup();
    renderWithProviders(
      <BacklogCell
        backlog={{
          value: 12400,
          accuracy: 'ESTIMATE',
          reason: 'Two partitions have no committed offset.',
          breakdown: false,
        }}
        subject="orders"
      />,
    );
    const figure = screen.getByRole('button', { name: /^≈ 12.400.*estimate/ });
    await user.tab();
    expect(figure).toHaveFocus();
    await user.keyboard('{Enter}');
    expect(await screen.findByText('Two partitions have no committed offset.')).toBeInTheDocument();
  });

  it('says Unavailable with its reason, never 0', async () => {
    const user = userEvent.setup();
    renderWithProviders(
      <BacklogCell
        backlog={{
          value: null,
          accuracy: 'UNAVAILABLE',
          reason: 'The account may not read offsets.',
          breakdown: false,
        }}
        subject="orders"
      />,
    );
    expect(screen.queryByText('0')).not.toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: /^Unavailable.*why/ }));
    expect(await screen.findByText('The account may not read offsets.')).toBeInTheDocument();
  });

  it('offers the breakdown when the system has one', async () => {
    const user = userEvent.setup();
    const onBreakdown = vi.fn();
    renderWithProviders(
      <BacklogCell
        backlog={{ value: 12400, accuracy: 'ESTIMATE', reason: null, breakdown: true }}
        subject="orders"
        onBreakdown={onBreakdown}
      />,
    );
    await user.click(screen.getByRole('button', { name: /estimate/ }));
    expect(await screen.findByText('This figure is an estimate.')).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Show partitions' }));
    expect(onBreakdown).toHaveBeenCalledOnce();
    // The popover gives way to the breakdown it opened, rather than staying over it.
    await waitFor(() => expect(screen.queryByText('This figure is an estimate.')).not.toBeInTheDocument());
  });
});
