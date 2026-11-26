import { useState } from 'react';
import { describe, expect, it, vi } from 'vitest';
import { Button } from '@mantine/core';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

import { holdButton, holdByKeyboard } from '../../test/hold.ts';
import { renderWithProviders } from '../../test/render.tsx';
import { SchemaReview } from './SchemaReview.tsx';
import type { SchemaPreview } from './schemasApi.ts';

const PREVIEW: SchemaPreview = {
  etag: 'e1',
  subject: 'orders-value',
  action: 'delete',
  before: ['compatibility=BACKWARD'],
  after: [],
  compatibilityOutcome: null,
  checkOnApply: false,
  alreadyRegistered: false,
  existingVersionId: null,
  note: null,
  statement: 'Nothing has been written.',
  writesNothing: true,
};

function DeleteReview({ onConfirm }: Readonly<{ onConfirm: () => void }>) {
  const [open, setOpen] = useState(false);
  return (
    <>
      <Button onClick={() => setOpen(true)}>Delete subject orders-value</Button>
      <SchemaReview
        opened={open}
        onClose={() => setOpen(false)}
        title="Delete subject orders-value"
        confirmLabel="Delete subject orders-value"
        tone="danger"
        preview={PREVIEW}
        onConfirm={onConfirm}
      />
    </>
  );
}

describe('a schema review', () => {
  it('dismisses without writing, and says nothing was written', async () => {
    const confirm = vi.fn();
    const user = userEvent.setup();
    renderWithProviders(<DeleteReview onConfirm={confirm} />);

    await user.click(screen.getByRole('button', { name: 'Delete subject orders-value' }));
    const dialog = await screen.findByRole('dialog', { name: 'Delete subject orders-value' });
    expect(dialog).toHaveTextContent('Nothing has been written.');
    await user.click(screen.getByRole('button', { name: 'Cancel' }));

    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
    expect(confirm).not.toHaveBeenCalled();
  });

  it('does nothing on a click and acts once when the button is held', async () => {
    const confirm = vi.fn();
    const user = userEvent.setup();
    renderWithProviders(<DeleteReview onConfirm={confirm} />);
    await user.click(screen.getByRole('button', { name: 'Delete subject orders-value' }));
    const dialog = await screen.findByRole('dialog', { name: 'Delete subject orders-value' });
    const hold = within(dialog).getByRole('button', { name: 'Delete subject orders-value' });
    await user.click(hold);
    expect(confirm).not.toHaveBeenCalled();

    await holdButton(hold);
    expect(confirm).toHaveBeenCalledOnce();
  });

  it('returns focus to the trigger when Escape dismisses it', async () => {
    const user = userEvent.setup();
    renderWithProviders(<DeleteReview onConfirm={vi.fn()} />);

    const trigger = screen.getByRole('button', { name: 'Delete subject orders-value' });
    await user.click(trigger);
    const dialog = await screen.findByRole('dialog');
    await waitFor(() => expect(dialog).toContainElement(document.activeElement as HTMLElement | null));

    await user.keyboard('{Escape}');
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
    await waitFor(() => expect(trigger).toHaveFocus());
  });

  it('can be confirmed from the keyboard alone', async () => {
    const confirm = vi.fn();
    const user = userEvent.setup();
    renderWithProviders(<DeleteReview onConfirm={confirm} />);
    await user.click(screen.getByRole('button', { name: 'Delete subject orders-value' }));
    const dialog = await screen.findByRole('dialog', { name: 'Delete subject orders-value' });
    const hold = within(dialog).getByRole('button', { name: 'Delete subject orders-value' });
    await holdByKeyboard(hold);
    expect(confirm).toHaveBeenCalledOnce();
  });
});
