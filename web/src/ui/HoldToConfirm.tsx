import { useEffect, useId, useRef, useState, type KeyboardEvent } from 'react';
import { Loader, VisuallyHidden } from '@mantine/core';
import { useLongPress } from '@mantine/hooks';

import classes from './HoldToConfirm.module.css';

const FALLBACK_HOLD_MS = 1500;

/**
 * How long the button is held, in milliseconds, from the `--as-hold-duration` token the fill's animation
 * runs on too, so the two cannot disagree. A test shortens it by setting the token on the root element.
 */
function readHoldMs(): number {
  const raw = getComputedStyle(document.documentElement).getPropertyValue('--as-hold-duration').trim();
  const value = Number.parseFloat(raw);
  if (!Number.isFinite(value)) return FALLBACK_HOLD_MS;
  return raw.endsWith('ms') ? value : value * 1000;
}

type Hint = 'idle' | 'holding' | 'early' | 'done';

const HINTS: Record<Hint, string> = {
  idle: 'Press and hold to confirm.',
  holding: 'Keep holding…',
  early: 'Released too early. Hold until the button is full.',
  done: 'Confirmed.',
};

/**
 * Confirms an action that cannot be taken back by pressing and holding (non-negotiable #2): the button
 * fills with red while it is held and acts only when it is full, so a stray click, a double click or a
 * key left down cannot do it. Letting go early drains the fill and does nothing. Held with the mouse, a
 * finger or a pen, or with Space or Enter on the keyboard; the hint under the button says so, and the
 * outcome is announced.
 *
 * <p>`label` is the action ("Delete 37 subscriptions") and names the blast radius the button is for; the button
 * reads exactly that, and the hint under it says to hold it. While `loading` it is busy and cannot be held again. Mouse and touch
 * presses are Mantine's `useLongPress`; it has no keyboard, which is handled here.
 */
export function HoldToConfirm({
  label,
  onConfirm,
  loading = false,
  disabled = false,
  describedBy,
  tone = 'danger',
}: Readonly<{
  /** The exact action, such as "Delete subscription ORDERS" or "Apply to 2 nodes". */
  label: string;
  onConfirm: () => void;
  loading?: boolean;
  disabled?: boolean;
  /** The id of text that says why the button is disabled, for assistive technology. */
  describedBy?: string;
  /** `danger` for an action that removes or overwrites (the default); `default` for one that does not. */
  tone?: 'default' | 'danger';
}>) {
  const hintId = useId();
  const [holdMs] = useState(readHoldMs);
  const [hint, setHint] = useState<Hint>('idle');
  const [announce, setAnnounce] = useState('');
  const keyTimer = useRef<ReturnType<typeof setTimeout> | null>(null);
  const keyHeld = useRef<string | null>(null);
  const inactive = disabled || loading;

  const confirm = () => {
    setHint('done');
    setAnnounce('Confirmed.');
    onConfirm();
  };
  const begin = () => {
    setHint('holding');
    setAnnounce('');
  };
  const releasedEarly = () => {
    setHint('early');
    setAnnounce(HINTS.early);
  };
  const press = useLongPress(confirm, {
    threshold: holdMs,
    onStart: () => {
      if (!inactive) begin();
    },
    onCancel: releasedEarly,
    onFinish: () => setHint('idle'),
  });

  const stopKey = () => {
    if (keyTimer.current) globalThis.clearTimeout(keyTimer.current);
    keyTimer.current = null;
    keyHeld.current = null;
  };
  useEffect(() => stopKey, []);
  // When the action ends (the button is busy, then free again) it can be held again from the start.
  useEffect(() => {
    if (!loading) setHint((current) => (current === 'done' ? 'idle' : current));
  }, [loading]);

  const onKeyDown = (event: KeyboardEvent) => {
    if (event.key !== ' ' && event.key !== 'Enter') return;
    event.preventDefault();
    if (inactive || event.repeat || keyHeld.current) return;
    keyHeld.current = event.key;
    begin();
    keyTimer.current = globalThis.setTimeout(() => {
      keyHeld.current = null;
      confirm();
    }, holdMs);
  };
  const onKeyUp = (event: KeyboardEvent) => {
    if (event.key !== ' ' && event.key !== 'Enter') return;
    event.preventDefault();
    if (keyHeld.current === event.key) {
      stopKey();
      releasedEarly();
    } else {
      setHint('idle');
    }
  };
  const onBlur = () => {
    if (keyHeld.current) {
      stopKey();
      releasedEarly();
    }
  };

  const holding = hint === 'holding';
  return (
    <div className={classes.wrap}>
      <button
        type="button"
        className={classes.root}
        data-tone={tone}
        data-holding={holding || undefined}
        data-done={hint === 'done' || undefined}
        disabled={inactive}
        aria-busy={loading || undefined}
        aria-describedby={[describedBy, hintId].filter(Boolean).join(' ')}
        onKeyDown={onKeyDown}
        onKeyUp={onKeyUp}
        onBlur={onBlur}
        onContextMenu={(event) => event.preventDefault()}
        {...press}
      >
        <span className={classes.label}>
          {loading ? <Loader size="xs" aria-hidden /> : null}
          <span>{label}</span>
        </span>
        {/* The same label in the fill's colours, revealed as the fill grows, so the text stays legible. */}
        <span className={classes.fill} aria-hidden="true">
          <span className={classes.label}>
            <span>{label}</span>
          </span>
        </span>
      </button>
      <span id={hintId} className={classes.hint}>
        {HINTS[hint]}
      </span>
      <VisuallyHidden role="status" aria-live="polite">
        {announce}
      </VisuallyHidden>
    </div>
  );
}
