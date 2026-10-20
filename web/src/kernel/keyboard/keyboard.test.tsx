import { useRef } from 'react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { Modal } from '@mantine/core';
import { fireEvent, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

import { renderWithProviders } from '../../test/render.tsx';

const navigate = vi.fn();
vi.mock('@tanstack/react-router', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@tanstack/react-router')>()),
  useNavigate: () => navigate,
  useParams: () => ({ clusterId: 'c1' }),
}));

const { useKeySequences, viewHotkeys } = await import('./useKeySequences.ts');
const { useFilterShortcut } = await import('./filterShortcut.ts');
const { setSingleKeyShortcuts, useShortcutsHelpOpen, setShortcutsHelpOpen } = await import('./shortcuts.ts');

function Harness({ dialog = false }: { dialog?: boolean }) {
  useKeySequences();
  const filter = useRef<HTMLInputElement>(null);
  useFilterShortcut(filter);
  const help = useShortcutsHelpOpen();
  return (
    <>
      <label>
        Filter subscriptions
        <input ref={filter} />
      </label>
      <button type="button">elsewhere</button>
      <p>{help ? 'help is open' : 'help is closed'}</p>
      <Modal opened={dialog} onClose={() => {}} title="Delete orders">
        <button type="button">inside</button>
      </Modal>
    </>
  );
}

afterEach(() => {
  navigate.mockClear();
  setSingleKeyShortcuts(true);
  setShortcutsHelpOpen(false);
});

describe('single-key shortcuts (ADR-0109)', () => {
  it('goes to a view with g and its letter', async () => {
    const user = userEvent.setup();
    renderWithProviders(<Harness />);
    screen.getByRole('button', { name: 'elsewhere' }).focus();
    await user.keyboard('gq');
    expect(navigate).toHaveBeenCalledWith({ to: '/clusters/c1/subscriptions' });
  });

  it('is not taken while typing in a field', async () => {
    const user = userEvent.setup();
    renderWithProviders(<Harness />);
    await user.click(screen.getByRole('textbox', { name: 'Filter subscriptions' }));
    await user.keyboard('gq');
    expect(navigate).not.toHaveBeenCalled();
    expect(screen.getByRole('textbox', { name: 'Filter subscriptions' })).toHaveValue('gq');
  });

  it('never navigates away from an open dialog', async () => {
    const user = userEvent.setup();
    renderWithProviders(<Harness dialog />);
    (await screen.findByRole('button', { name: 'inside' })).focus();
    await user.keyboard('gq');
    expect(navigate).not.toHaveBeenCalled();
  });

  it('does nothing when turned off', async () => {
    const user = userEvent.setup();
    setSingleKeyShortcuts(false);
    renderWithProviders(<Harness />);
    screen.getByRole('button', { name: 'elsewhere' }).focus();
    await user.keyboard('gq?');
    expect(navigate).not.toHaveBeenCalled();
    expect(screen.getByText('help is closed')).toBeInTheDocument();
  });

  it('opens the list of shortcuts with ?, and focuses the filter with /', async () => {
    const user = userEvent.setup();
    renderWithProviders(<Harness />);
    screen.getByRole('button', { name: 'elsewhere' }).focus();
    await user.keyboard('?');
    expect(screen.getByText('help is open')).toBeInTheDocument();
    screen.getByRole('button', { name: 'elsewhere' }).focus();
    await user.keyboard('/');
    expect(screen.getByRole('textbox', { name: 'Filter subscriptions' })).toHaveFocus();
  });

  it('reads the physical key on a non-Latin layout', () => {
    renderWithProviders(<Harness />);
    const target = screen.getByRole('button', { name: 'elsewhere' });
    // A Persian keyboard: the keys under G and Q.
    fireEvent.keyDown(target, { key: 'گ', code: 'KeyG' });
    fireEvent.keyDown(target, { key: 'ض', code: 'KeyQ' });
    expect(navigate).toHaveBeenCalledWith({ to: '/clusters/c1/subscriptions' });
  });
});

describe('view letters', () => {
  it("ignore a plugin's letter", () => {
    const plugin = {
      id: 'acme-notes',
      nav: [{ group: 'observe', order: 1, label: 'Notes', icon: () => null, path: 'p/acme-notes', hotkey: 'z' }],
    };
    expect(viewHotkeys([plugin as never]).has('z')).toBe(false);
  });
});
