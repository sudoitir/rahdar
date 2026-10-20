import { useEffect, useRef, type KeyboardEvent, type ReactNode } from 'react';

import classes from './Toolbar.module.css';

/** What a toolbar's arrow keys move between: the controls that can take focus now. */
const ITEMS =
  'button:not(:disabled), a[href], input:not(:disabled), select:not(:disabled), textarea:not(:disabled), [tabindex]';

/** Controls that use the arrow keys themselves; the toolbar leaves them alone. */
const OWN_ARROWS = 'input:not([type=button], [type=checkbox], [type=radio], [type=submit]), select, textarea';

/**
 * The row of controls above a view.
 *
 * <p>By default it is a labelled group of controls, each its own tab stop. With `arrowNavigation` it
 * is an ARIA toolbar: one tab stop for the whole row, the arrow keys, Home and End move between the
 * controls, and the toolbar remembers the one last used. Use that only for a row of several
 * controls that belong together; a toolbar of one or two is better as plain tab stops.
 */
export function Toolbar({
  label,
  start,
  end,
  arrowNavigation = false,
}: Readonly<{
  /** Names the toolbar for assistive technology, such as "Subscription filters". */
  label: string;
  /** Controls that act on the view, on the start side. */
  start?: ReactNode;
  /** Controls for the page's state or layout, on the end side. */
  end?: ReactNode;
  /** Makes it an ARIA toolbar with a single tab stop and arrow-key movement. */
  arrowNavigation?: boolean;
}>) {
  const ref = useRef<HTMLDivElement>(null);
  const active = useRef<HTMLElement | null>(null);

  // Only the active control is a tab stop. Runs after every render, so controls that were added or
  // removed are covered.
  const sync = () => {
    if (!ref.current) return;
    const items = Array.from(ref.current.querySelectorAll<HTMLElement>(ITEMS));
    const current = active.current && items.includes(active.current) ? active.current : items[0];
    for (const item of items) item.tabIndex = item === current ? 0 : -1;
  };
  useEffect(() => {
    if (arrowNavigation) sync();
  });

  const onKeyDown = (event: KeyboardEvent<HTMLDivElement>) => {
    const root = ref.current;
    const target = event.target as HTMLElement;
    if (!root || target.matches(OWN_ARROWS)) return;
    const items = Array.from(root.querySelectorAll<HTMLElement>(ITEMS));
    const index = items.indexOf(target);
    if (index < 0) return;
    const rtl = getComputedStyle(root).direction === 'rtl';
    const step = { ArrowRight: rtl ? -1 : 1, ArrowLeft: rtl ? 1 : -1 }[event.key];
    let next: number;
    if (step !== undefined) next = (index + step + items.length) % items.length;
    else if (event.key === 'Home') next = 0;
    else if (event.key === 'End') next = items.length - 1;
    else return;
    event.preventDefault();
    items[next]?.focus();
  };

  return (
    <div
      ref={ref}
      className={classes.toolbar}
      data-as-toolbar=""
      role={arrowNavigation ? 'toolbar' : 'group'}
      aria-label={label}
      onKeyDown={arrowNavigation ? onKeyDown : undefined}
      onFocus={
        arrowNavigation
          ? (event) => {
              // Focus bubbles through portals, so a menu's items are not the toolbar's.
              if (!ref.current?.contains(event.target)) return;
              active.current = event.target as HTMLElement;
              sync();
            }
          : undefined
      }
    >
      {start ? <div className={classes.start}>{start}</div> : null}
      {end ? <div className={classes.end}>{end}</div> : null}
    </div>
  );
}
