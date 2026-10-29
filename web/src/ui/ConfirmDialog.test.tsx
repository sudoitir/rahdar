import { useState } from 'react';
import { holdButton, holdByKeyboard } from '../test/hold.ts';
import { describe, expect, it, vi } from 'vitest';
import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

import { renderWithProviders } from '../test/render.tsx';
import { ConfirmDialog, type ConfirmDialogProps } from './ConfirmDialog.tsx';

function Host(props: Partial<ConfirmDialogProps>) {
  const [opened, setOpened] = useState(false);
  return (
    <>
      <button type="button" onClick={() => setOpened(true)}>
        Open
      </button>
      <ConfirmDialog
        title="Delete queue"
        consequence="Removes queue orders on 3 nodes and its 1,204 messages."
        confirmLabel="Delete queue"
        onConfirm={() => {}}
        {...props}
        opened={opened}
        onClose={() => setOpened(false)}
      />
    </>
  );
}

describe('ConfirmDialog', () => {
  it('states the consequence and runs the named action', async () => {
    const onConfirm = vi.fn();
    const user = userEvent.setup();
    renderWithProviders(<Host onConfirm={onConfirm} />);
    await user.click(screen.getByRole('button', { name: 'Open' }));
    const dialog = await screen.findByRole('dialog', { name: 'Delete queue' });
    expect(dialog).toHaveTextContent('Removes queue orders on 3 nodes');
    await user.click(screen.getByRole('button', { name: 'Delete queue' }));
    expect(onConfirm).toHaveBeenCalledOnce();
  });

  it('is operable by keyboard alone: focus enters, escape closes, focus returns to the trigger', async () => {
    const user = userEvent.setup();
    renderWithProviders(<Host tone="danger" />);
    const trigger = screen.getByRole('button', { name: 'Open' });
    trigger.focus();
    await user.keyboard('{Enter}');
    const dialog = await screen.findByRole('dialog', { name: 'Delete queue' });
    // The safe choice holds focus, so an Enter pressed by habit cancels.
    await waitFor(() => expect(screen.getByRole('button', { name: 'Cancel' })).toHaveFocus());
    expect(dialog).toContainElement(document.activeElement as HTMLElement);
    await user.keyboard('{Escape}');
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
    await waitFor(() => expect(trigger).toHaveFocus());
  });

  it('confirms a destructive action only by holding it, by keyboard, with focus starting on Cancel', async () => {
    const onConfirm = vi.fn();
    const user = userEvent.setup();
    renderWithProviders(<Host tone="danger" onConfirm={onConfirm} />);
    const trigger = screen.getByRole('button', { name: 'Open' });
    trigger.focus();
    await user.keyboard('{Enter}');
    const cancel = await screen.findByRole('button', { name: 'Cancel' });
    await waitFor(() => expect(cancel).toHaveFocus());
    const confirm = screen.getByRole('button', { name: 'Delete queue' });
    // A click, or a key pressed and let go, is not a confirmation.
    await user.click(confirm);
    await user.keyboard('{Enter}');
    expect(onConfirm).not.toHaveBeenCalled();
    await holdByKeyboard(confirm);
    expect(onConfirm).toHaveBeenCalledOnce();
  });

  it('names the dismiss button apart from a cancelling action', async () => {
    const user = userEvent.setup();
    renderWithProviders(
      <Host title="Cancel this request?" confirmLabel="Cancel request" dismissLabel="Keep request" />,
    );
    await user.click(screen.getByRole('button', { name: 'Open' }));
    await screen.findByRole('dialog', { name: 'Cancel this request?' });
    await waitFor(() => expect(screen.getByRole('button', { name: 'Keep request' })).toHaveFocus());
    expect(screen.queryByRole('button', { name: 'Cancel' })).not.toBeInTheDocument();
  });

  it('cancels from the keyboard and returns focus', async () => {
    const user = userEvent.setup();
    renderWithProviders(<Host />);
    const trigger = screen.getByRole('button', { name: 'Open' });
    await user.click(trigger);
    await screen.findByRole('dialog');
    await user.keyboard('{Enter}');
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
    await waitFor(() => expect(trigger).toHaveFocus());
  });

  it('is busy while pending: the action cannot be pressed again and the dialog stays open', async () => {
    const onConfirm = vi.fn();
    const user = userEvent.setup();
    renderWithProviders(<Host pending onConfirm={onConfirm} />);
    await user.click(screen.getByRole('button', { name: 'Open' }));
    const dialog = await screen.findByRole('dialog');
    const confirm = screen.getByRole('button', { name: 'Delete queue' });
    expect(confirm).toBeDisabled();
    await user.click(confirm);
    expect(onConfirm).not.toHaveBeenCalled();
    expect(screen.getByRole('button', { name: 'Cancel' })).toBeDisabled();
    await user.keyboard('{Escape}');
    expect(dialog).toBeInTheDocument();
  });
  it('cannot be armed while blocked, says why in words, and still dismisses', async () => {
    const onConfirm = vi.fn();
    const user = userEvent.setup();
    renderWithProviders(
      <Host blocked="Another node is still applying. Wait for it to finish, then try again." onConfirm={onConfirm} />,
    );
    const trigger = screen.getByRole('button', { name: 'Open' });
    await user.click(trigger);
    await screen.findByRole('dialog');
    const confirm = screen.getByRole('button', { name: 'Delete queue' });
    expect(confirm).toBeDisabled();
    expect(confirm).toHaveAccessibleDescription(/Another node is still applying/);
    expect(screen.getByText(/Wait for it to finish/)).toBeInTheDocument();
    await user.click(confirm);
    expect(onConfirm).not.toHaveBeenCalled();
    await user.keyboard('{Escape}');
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
    await waitFor(() => expect(trigger).toHaveFocus());
  });

  it('keeps a held confirmation unarmed while blocked, even when held', async () => {
    const onConfirm = vi.fn();
    const user = userEvent.setup();
    renderWithProviders(<Host tone="danger" blocked="The queue is in use." onConfirm={onConfirm} />);
    await user.click(screen.getByRole('button', { name: 'Open' }));
    const confirm = await screen.findByRole('button', { name: 'Delete queue' });
    expect(confirm).toBeDisabled();
    await holdButton(confirm);
    expect(onConfirm).not.toHaveBeenCalled();
    expect(confirm).toHaveAccessibleDescription(/^The queue is in use\./);
    expect(screen.getByRole('button', { name: 'Cancel' })).toBeEnabled();
  });

  it('carries the confirmation and then the outcome in one dialog, by keyboard, and returns focus on close', async () => {
    function Flow() {
      const [result, setResult] = useState<string>();
      return (
        <Host
          tone="danger"
          result={result ? <p>{result}</p> : undefined}
          onConfirm={() => setResult('Deleted queue orders on 3 nodes.')}
        />
      );
    }
    const user = userEvent.setup();
    renderWithProviders(<Flow />);
    const trigger = screen.getByRole('button', { name: 'Open' });
    trigger.focus();
    await user.keyboard('{Enter}');
    const dialog = await screen.findByRole('dialog', { name: 'Delete queue' });
    await holdByKeyboard(await screen.findByRole('button', { name: 'Delete queue' }));

    // The confirm controls give way to the outcome, which holds focus; what was confirmed stays above it.
    const outcome = await screen.findByRole('group', { name: 'Result' });
    expect(outcome).toHaveTextContent('Deleted queue orders on 3 nodes.');
    await waitFor(() => expect(outcome).toHaveFocus());
    expect(screen.getAllByRole('dialog')).toHaveLength(1);
    expect(dialog).toHaveTextContent('Removes queue orders on 3 nodes');
    expect(screen.queryByRole('button', { name: 'Delete queue' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Cancel' })).not.toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: 'Close' }));
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
    await waitFor(() => expect(trigger).toHaveFocus());
  });

  it('gives focus to Cancel when the dialog opened while the caller was busy, once the caller settles', async () => {
    const user = userEvent.setup();
    const { rerender } = renderWithProviders(<Host pending />);
    await user.click(screen.getByRole('button', { name: 'Open' }));
    await screen.findByRole('dialog', { name: 'Delete queue' });
    // An inert Cancel cannot hold focus; nothing in the dialog has it.
    expect(screen.getByRole('button', { name: 'Cancel' })).toBeDisabled();
    expect(screen.getByRole('dialog', { name: 'Delete queue' })).not.toContainElement(
      document.activeElement as HTMLElement,
    );

    rerender(<Host pending={false} />);
    await waitFor(() => expect(screen.getByRole('button', { name: 'Cancel' })).toHaveFocus());
  });
});
