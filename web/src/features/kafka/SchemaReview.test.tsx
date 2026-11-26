import { useState } from 'react';
import { describe, expect, it, vi } from 'vitest';
import { Button } from '@mantine/core';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

import { holdButton, holdByKeyboard } from '../../test/hold.ts';
import { renderWithProviders } from '../../test/render.tsx';
import { RegisterSchemaDialog } from './RegisterSchemaDialog.tsx';
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

describe('the registration form', () => {
  it('keeps the schema text behind a disclosure, and says what is in it', async () => {
    const user = userEvent.setup();
    renderWithProviders(<RegisterSchemaDialog clusterId="k1" opened onClose={() => {}} />);
    const dialog = await screen.findByRole('dialog', { name: 'Register a schema' });
    expect(within(dialog).queryByRole('textbox', { name: 'Schema' })).not.toBeInTheDocument();

    const disclosure = within(dialog).getByRole('button', { name: 'Schema text' });
    expect(disclosure).toHaveAttribute('aria-expanded', 'false');
    await user.click(disclosure);
    expect(disclosure).toHaveAttribute('aria-expanded', 'true');
    expect(within(dialog).getByRole('textbox', { name: 'Schema' })).toBeInTheDocument();
  });

  it('opens the disclosure when a submit is rejected for the schema', async () => {
    const user = userEvent.setup();
    renderWithProviders(<RegisterSchemaDialog clusterId="k1" opened onClose={() => {}} />);
    const dialog = await screen.findByRole('dialog', { name: 'Register a schema' });
    // Submit with nothing filled in: both fields are refused, and the closed one must open to show why.
    await user.click(within(dialog).getByRole('button', { name: 'Preview registration' }));

    const disclosure = within(dialog).getByRole('button', { name: 'Schema text' });
    expect(disclosure).toHaveAttribute('aria-expanded', 'true');
    expect(await within(dialog).findByText('Enter the schema text.')).toBeInTheDocument();
  });
});

describe('a schema review', () => {
  it('dismisses without writing, and says nothing was written', async () => {
    const confirm = vi.fn();
    const user = userEvent.setup();
    renderWithProviders(<DeleteReview onConfirm={confirm} />);

    await user.click(screen.getByRole('button', { name: 'Delete subject orders-value' }));
    const dialog = await screen.findByRole('dialog', { name: 'Delete subject orders-value' });
    expect(dialog).toHaveTextContent('Nothing has been written.');
    expect(dialog).toHaveTextContent('Removed');
    expect(dialog).not.toHaveTextContent('None');
    await user.click(screen.getByRole('button', { name: 'Cancel' }));

    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
    expect(confirm).not.toHaveBeenCalled();
  });

  it('says a registration is compatible once, not twice', () => {
    const outcome = 'Nothing has been written. The registry judges this schema compatible.';
    renderWithProviders(
      <SchemaReview
        opened
        onClose={() => {}}
        title="Register schema orders-value"
        confirmLabel="Register schema orders-value"
        preview={{
          ...PREVIEW,
          action: 'register',
          compatibilityOutcome: outcome,
          note: outcome,
          before: [],
          after: ['subject=orders-value', 'schema.type=AVRO'],
        }}
        onConfirm={() => {}}
      />,
    );
    expect(screen.getAllByText(outcome)).toHaveLength(1);
  });

  it('says the statement once, and the rest of the note after it', () => {
    renderWithProviders(
      <SchemaReview
        opened
        onClose={() => {}}
        title="Permanently delete subject orders-value"
        confirmLabel="Permanently delete subject orders-value"
        tone="danger"
        preview={{
          ...PREVIEW,
          note: 'This soft-deletes the subject and then deletes the schema. The schemas cannot be restored. Nothing has been written.',
        }}
        onConfirm={() => {}}
      />,
    );
    const dialog = screen.getByRole('dialog', { name: 'Permanently delete subject orders-value' });
    expect(dialog.textContent?.match(/Nothing has been written\./g)).toHaveLength(1);
    expect(dialog).toHaveTextContent('This soft-deletes the subject and then deletes the schema');
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
