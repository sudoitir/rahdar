import { afterEach, describe, expect, it } from 'vitest';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

import { renderWithProviders } from '../../test/render.tsx';
import { setShortcutsHelpOpen } from './shortcuts.ts';
import { ShortcutsHelp } from './ShortcutsHelp.tsx';

describe('ShortcutsHelp', () => {
  // The open state is shared by the header, the palette and `?`, so it outlives a test's render.
  afterEach(() => setShortcutsHelpOpen(false));

  it('opens from its header button as a popover, and Escape returns focus to the button', async () => {
    renderWithProviders(<ShortcutsHelp />);
    const button = screen.getByRole('button', { name: 'Keyboard shortcuts' });

    await userEvent.click(button);
    const popover = await screen.findByRole('dialog', { name: 'Keyboard shortcuts' });
    expect(popover).toHaveTextContent('Search views, clusters and subscriptions');
    const toggle = within(popover).getByLabelText(/Single-key shortcuts/);
    expect(toggle).toBeChecked();
    // Focus moves into the popover, onto its one control.
    await waitFor(() => expect(toggle).toHaveFocus());

    await userEvent.keyboard('{Escape}');
    await waitFor(() => expect(screen.queryByRole('dialog', { name: 'Keyboard shortcuts' })).not.toBeInTheDocument());
    expect(button).toHaveFocus();
  });

  it('lists each shortcut as an action and its keys, under headings that nest below the popover title', async () => {
    renderWithProviders(<ShortcutsHelp />);
    await userEvent.click(screen.getByRole('button', { name: 'Keyboard shortcuts' }));
    const popover = await screen.findByRole('dialog', { name: 'Keyboard shortcuts' });

    expect(within(popover).getByRole('heading', { level: 2, name: 'Keyboard shortcuts' })).toBeInTheDocument();
    expect(within(popover).getByRole('heading', { level: 3, name: 'Everywhere' })).toBeInTheDocument();
    expect(within(popover).getByRole('heading', { level: 3, name: 'In a grid' })).toBeInTheDocument();
    const topology = within(popover).getByRole('region', { name: 'In the topology' });
    expect(within(topology).getByText('First or last node').nextElementSibling).toHaveTextContent('HomeorEnd');
    expect(within(popover).queryByRole('table')).not.toBeInTheDocument();
    const everywhere = within(popover).getByRole('region', { name: 'Everywhere' });
    const term = within(everywhere).getByText('Search views, clusters and subscriptions');
    expect(term.tagName).toBe('DT');
    expect(term.nextElementSibling).toHaveTextContent('⌘KorCtrlK');
  });

  it('lists the SQL console’s keys: Mod+. cancels and Escape never does', async () => {
    renderWithProviders(<ShortcutsHelp />);
    await userEvent.click(screen.getByRole('button', { name: 'Keyboard shortcuts' }));
    const popover = await screen.findByRole('dialog', { name: 'Keyboard shortcuts' });

    const sql = within(popover).getByRole('region', { name: 'In the SQL console' });
    expect(within(sql).getByText('Cancel the running query').nextElementSibling).toHaveTextContent('⌘.orCtrl.');
    expect(within(sql).getByText(/Escape never cancels/)).toBeInTheDocument();
  });
});
