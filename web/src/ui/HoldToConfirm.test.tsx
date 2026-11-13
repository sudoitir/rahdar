import { readFileSync } from 'node:fs';

import { describe, expect, it, vi } from 'vitest';
import { act, fireEvent, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

import { holdButton, holdByKeyboard } from '../test/hold.ts';
import { renderWithProviders } from '../test/render.tsx';
import { HoldToConfirm } from './HoldToConfirm.tsx';

const wait = (ms: number) => act(async () => void (await new Promise((resolve) => setTimeout(resolve, ms))));

describe('HoldToConfirm', () => {
  it('names the action on the button and says how to confirm it', () => {
    renderWithProviders(<HoldToConfirm label="Delete 37 queues" onConfirm={() => {}} />);

    const button = screen.getByRole('button', { name: 'Delete 37 queues' });
    expect(button).toHaveAccessibleDescription('Press and hold to confirm.');
  });

  it('does nothing for a click, a double click or a press let go early', async () => {
    const onConfirm = vi.fn();
    const user = userEvent.setup();
    renderWithProviders(<HoldToConfirm label="Delete queue" onConfirm={onConfirm} />);
    const button = screen.getByRole('button', { name: 'Delete queue' });

    await user.click(button);
    await user.dblClick(button);
    fireEvent.mouseDown(button);
    await wait(5);
    fireEvent.mouseUp(button);

    expect(onConfirm).not.toHaveBeenCalled();
    expect(screen.getAllByText('Released too early. Hold until the button is full.').length).toBeGreaterThan(0);
  });

  it('confirms once when it is held with the mouse for the whole length, and says so', async () => {
    const onConfirm = vi.fn();
    renderWithProviders(<HoldToConfirm label="Delete queue" onConfirm={onConfirm} />);

    await holdButton(screen.getByRole('button', { name: 'Delete queue' }));

    expect(onConfirm).toHaveBeenCalledOnce();
    expect(screen.getByRole('status')).toHaveTextContent('Confirmed.');
  });

  it('confirms with Space or Enter held on the keyboard, and not when the key is let go early', async () => {
    const onConfirm = vi.fn();
    renderWithProviders(<HoldToConfirm label="Delete queue" onConfirm={onConfirm} />);
    const button = screen.getByRole('button', { name: 'Delete queue' });

    button.focus();
    fireEvent.keyDown(button, { key: ' ' });
    fireEvent.keyUp(button, { key: ' ' });
    expect(onConfirm).not.toHaveBeenCalled();

    await holdByKeyboard(button);
    expect(onConfirm).toHaveBeenCalledOnce();
    await holdByKeyboard(button, 'Enter');
    expect(onConfirm).toHaveBeenCalledTimes(2);
  });

  it('can be held again when the action starts before the pointer is released', async () => {
    const onConfirm = vi.fn();
    const { rerender } = renderWithProviders(<HoldToConfirm label="Delete queue" onConfirm={onConfirm} />);
    const button = screen.getByRole('button', { name: 'Delete queue' });

    fireEvent.mouseDown(button);
    await wait(80);
    expect(onConfirm).toHaveBeenCalledOnce();
    rerender(<HoldToConfirm label="Delete queue" loading onConfirm={onConfirm} />);
    expect(screen.getByRole('button', { name: 'Delete queue' })).toBeEnabled();
    fireEvent.mouseUp(screen.getByRole('button', { name: 'Delete queue' }));

    rerender(<HoldToConfirm label="Delete queue" onConfirm={onConfirm} />);
    await holdButton(screen.getByRole('button', { name: 'Delete queue' }));
    expect(onConfirm).toHaveBeenCalledTimes(2);
  });

  it('is busy while the action runs and cannot be held again, and disabled it cannot be held at all', async () => {
    const onConfirm = vi.fn();
    const { rerender } = renderWithProviders(<HoldToConfirm label="Delete queue" loading onConfirm={onConfirm} />);
    const busy = screen.getByRole('button', { name: 'Delete queue' });
    expect(busy).toBeDisabled();
    expect(busy).toHaveAttribute('aria-busy', 'true');
    await holdButton(busy);

    rerender(<HoldToConfirm label="Delete queue" disabled describedBy="why" onConfirm={onConfirm} />);
    await holdButton(screen.getByRole('button', { name: 'Delete queue' }));
    expect(onConfirm).not.toHaveBeenCalled();
  });

  it('stops the fill transition when reduced motion is on, and keeps the hold duration', () => {
    const css = readFileSync(`${import.meta.dirname}/HoldToConfirm.module.css`, 'utf8');
    expect(css).toContain('@media (prefers-reduced-motion: reduce)');
    expect(css).toContain('transition: none');
    expect(css).toContain('var(--as-hold-duration)');
  });
});
