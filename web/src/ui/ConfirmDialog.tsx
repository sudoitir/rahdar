import { useEffect, useId, useRef, type ReactNode } from 'react';
import { Button, Modal, Stack, Text, useMantineTheme } from '@mantine/core';

import { DialogActions } from './DialogActions.tsx';
import { HoldToConfirm } from './HoldToConfirm.tsx';
import classes from './ConfirmDialog.module.css';

/**
 * Confirmation before an action that cannot be taken back (non-negotiable #2). The dialog states
 * the consequence first and names the action on its button.
 *
 * <ul>
 *   <li>With `tone="danger"` the action is confirmed by pressing and holding the button until it
 *       fills ({@link HoldToConfirm}), never by a click or by typing; use it for anything that removes
 *       or overwrites. The button names the action and its count, such as "Delete 37 subscriptions".
 *   <li>Focus enters the dialog on Cancel, Escape closes it, and focus returns to the control that
 *       opened it.
 *   <li>While `pending` the button is busy and cannot be pressed again, and the dialog stays
 *       open: Escape, the overlay and Cancel are inert until the caller settles it.
 *   <li>With `blocked` the button cannot be armed, and the reason is stated beside it; the dialog
 *       can still be dismissed.
 *   <li>With `result` the confirm controls are replaced by the outcome, so one dialog carries the
 *       confirmation and then what happened. Focus moves to the outcome, the only button left is
 *       Close, and closing returns focus to the control that opened the dialog.
 * </ul>
 */
export function ConfirmDialog({
  opened,
  onClose,
  title,
  consequence,
  confirmLabel,
  dismissLabel = 'Cancel',
  tone = 'default',
  pending = false,
  blocked,
  result,
  size = 'md',
  align = 'center',
  onConfirm,
}: ConfirmDialogProps) {
  const theme = useMantineTheme();
  const color = tone === 'danger' ? 'signal' : theme.primaryColor;
  const reasonId = useId();
  const outcome = useRef<HTMLFieldSetElement>(null);
  const done = result !== undefined && result !== null;
  // The confirm button that held focus is gone: the outcome takes it, so the keyboard stays in the dialog.
  useEffect(() => {
    if (done) outcome.current?.focus();
  }, [done]);
  const cancelRef = useRef<HTMLButtonElement>(null);
  // Cancel is inert while the caller is busy, such as while the estimate the dialog states is taken, and an
  // inert button cannot hold focus: the dialog that opened in that moment gave it to the page. When the caller
  // settles and focus is not in the dialog, it is Cancel's, as it is when the dialog opens idle.
  useEffect(() => {
    if (!opened || pending || done) return;
    const button = cancelRef.current;
    const dialog = button?.closest('[role="dialog"]');
    if (button && dialog && !dialog.contains(document.activeElement)) button.focus();
  }, [opened, pending, done]);
  const cancel = (
    <Button
      ref={cancelRef}
      variant="default"
      data-autofocus={done ? undefined : true}
      disabled={pending}
      onClick={onClose}
    >
      {done ? 'Close' : dismissLabel}
    </Button>
  );
  const reason = blocked ? (
    <Text id={reasonId} size="sm" className={classes.blocked}>
      {blocked}
    </Text>
  ) : null;
  let controls: ReactNode;
  if (done) {
    controls = (
      <>
        <fieldset ref={outcome} aria-label="Result" tabIndex={-1} className={classes.outcome}>
          {result}
        </fieldset>
        <DialogActions>{cancel}</DialogActions>
      </>
    );
  } else if (tone === 'danger') {
    controls = (
      <>
        {reason}
        <HoldToConfirm
          label={confirmLabel}
          loading={pending}
          disabled={Boolean(blocked)}
          describedBy={blocked ? reasonId : undefined}
          onConfirm={onConfirm}
        />
        <DialogActions>{cancel}</DialogActions>
      </>
    );
  } else {
    controls = (
      <>
        {reason}
        <DialogActions>
          {cancel}
          <Button
            color={color}
            loading={pending}
            disabled={Boolean(blocked)}
            aria-describedby={blocked ? reasonId : undefined}
            onClick={onConfirm}
          >
            {confirmLabel}
          </Button>
        </DialogActions>
      </>
    );
  }
  return (
    <Modal
      opened={opened}
      onClose={onClose}
      title={title}
      size={size}
      centered={align === 'center'}
      withCloseButton={false}
      closeOnEscape={!pending}
      closeOnClickOutside={!pending}
    >
      <Stack gap="md" aria-busy={pending}>
        <Text component="div" size="sm">
          {consequence}
        </Text>
        {controls}
      </Stack>
    </Modal>
  );
}

export type ConfirmDialogProps = Readonly<{
  opened: boolean;
  onClose: () => void;
  /** The action as a plain line, such as "Delete subscription". */
  title: string;
  /** What it affects: the resource, the nodes and how much data goes. */
  consequence: ReactNode;
  /** The exact action, such as "Delete subscription". */
  confirmLabel: string;
  /** A wider dialog for a consequence that holds a table. */
  size?: 'md' | 'lg';
  /** `top` keeps the top edge where it opened, for a consequence that grows as it is read; the default centres it. */
  align?: 'center' | 'top';
  /**
   * The button that dismisses without acting; defaults to "Cancel". Name what is kept, such as "Keep
   * request", when the action itself is a cancel, so the two buttons never both read "Cancel".
   */
  dismissLabel?: string;
  /** `danger` for an action that removes or overwrites, confirmed by holding; defaults to `default`. */
  tone?: 'default' | 'danger';
  /** The action is running: the button is busy and the dialog cannot be dismissed. */
  pending?: boolean;
  /**
   * Why the action cannot be armed right now, in a sentence that says what to do about it. The button
   * is disabled and the reason is shown beside it, in words; Cancel and Escape still dismiss.
   */
  blocked?: string;
  /**
   * What happened, once the action ran: replaces the confirm controls, leaving Close. Pass the
   * outcome as a `Notice`, an `ErrorState` or a `NodeOutcomeSummary`.
   */
  result?: ReactNode;
  onConfirm: () => void;
}>;
