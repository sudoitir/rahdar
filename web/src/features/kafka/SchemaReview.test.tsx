import { useState } from 'react';
import { describe, expect, it, vi } from 'vitest';
import { Button } from '@mantine/core';
import { http, HttpResponse } from 'msw';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

import { holdButton, holdByKeyboard } from '../../test/hold.ts';
import { renderWithProviders } from '../../test/render.tsx';
import { server } from '../../test/setup.ts';
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

  it('says how many characters are entered while the schema text stays closed', async () => {
    const user = userEvent.setup();
    renderWithProviders(<RegisterSchemaDialog clusterId="k1" opened onClose={() => {}} />);
    const dialog = await screen.findByRole('dialog', { name: 'Register a schema' });
    expect(within(dialog).getByText('Not entered yet.')).toBeInTheDocument();

    const disclosure = within(dialog).getByRole('button', { name: 'Schema text' });
    await user.click(disclosure);
    await user.type(within(dialog).getByRole('textbox', { name: 'Schema' }), 'abc');
    await user.click(disclosure);

    expect(disclosure).toHaveAttribute('aria-expanded', 'false');
    expect(within(dialog).getByText('3 characters entered.')).toBeInTheDocument();
    expect(within(dialog).queryByRole('textbox', { name: 'Schema' })).not.toBeInTheDocument();
  });

  it('registers the previewed schema after a hold, and names the version', async () => {
    const calls: string[] = [];
    server.use(
      http.post('*/api/v1/clusters/k1/kafka/schemas\\:register-preview', async ({ request }) => {
        calls.push(`preview:${((await request.json()) as { subject: string }).subject}`);
        return HttpResponse.json({ ...PREVIEW, action: 'register', subject: 'orders-value' });
      }),
      http.post('*/api/v1/clusters/k1/kafka/schemas\\:register', () => {
        calls.push('register');
        return HttpResponse.json({ version: 3, id: 9 });
      }),
    );
    const user = userEvent.setup();
    renderWithProviders(<RegisterSchemaDialog clusterId="k1" opened onClose={() => {}} />);
    const dialog = await screen.findByRole('dialog', { name: 'Register a schema' });
    await user.type(within(dialog).getByRole('textbox', { name: 'Subject' }), 'orders-value');
    await user.click(within(dialog).getByRole('button', { name: 'Schema text' }));
    await user.click(within(dialog).getByRole('textbox', { name: 'Schema' }));
    await user.paste('record Order');
    await user.click(within(dialog).getByRole('button', { name: 'Preview registration' }));

    const review = await screen.findByRole('dialog', { name: 'Register schema orders-value' });
    expect(review).toHaveTextContent('Nothing has been written.');
    expect(calls).toEqual(['preview:orders-value']);
    await holdButton(within(review).getByRole('button', { name: 'Register schema orders-value' }));
    expect(await within(review).findByText('Registered version 3 (id 9).')).toBeInTheDocument();
    expect(calls).toEqual(['preview:orders-value', 'register']);
  });

  it('says the registry moved and a fresh preview reads the schema again', async () => {
    let previews = 0;
    server.use(
      http.post('*/api/v1/clusters/k1/kafka/schemas\\:register-preview', () => {
        previews += 1;
        return HttpResponse.json({ ...PREVIEW, action: 'register', subject: 'orders-value' });
      }),
      http.post('*/api/v1/clusters/k1/kafka/schemas\\:register', () =>
        HttpResponse.json({ title: 'Precondition failed', detail: 'A version landed.' }, { status: 412 }),
      ),
    );
    const user = userEvent.setup();
    renderWithProviders(<RegisterSchemaDialog clusterId="k1" opened onClose={() => {}} />);
    const dialog = await screen.findByRole('dialog', { name: 'Register a schema' });
    await user.type(within(dialog).getByRole('textbox', { name: 'Subject' }), 'orders-value');
    await user.click(within(dialog).getByRole('button', { name: 'Schema text' }));
    await user.click(within(dialog).getByRole('textbox', { name: 'Schema' }));
    await user.paste('record Order');
    await user.click(within(dialog).getByRole('button', { name: 'Preview registration' }));

    const review = await screen.findByRole('dialog', { name: 'Register schema orders-value' });
    await holdButton(within(review).getByRole('button', { name: 'Register schema orders-value' }));
    expect(await within(review).findByText(/The registry moved since this preview/)).toBeInTheDocument();
    expect(within(review).getByRole('button', { name: 'Register schema orders-value' })).toBeDisabled();
    await user.click(within(review).getByRole('button', { name: 'Preview again' }));
    await waitFor(() => expect(previews).toBe(2));
    expect(within(review).queryByText(/The registry moved since this preview/)).not.toBeInTheDocument();
  });

  it('dismisses the form without writing', async () => {
    const onClose = vi.fn();
    function Host() {
      const [open, setOpen] = useState(true);
      return (
        <RegisterSchemaDialog
          clusterId="k1"
          opened={open}
          onClose={() => {
            onClose();
            setOpen(false);
          }}
        />
      );
    }
    const user = userEvent.setup();
    renderWithProviders(<Host />);
    await user.click(
      within(await screen.findByRole('dialog', { name: 'Register a schema' })).getByRole('button', { name: 'Cancel' }),
    );
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
    expect(onClose).toHaveBeenCalledOnce();
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
