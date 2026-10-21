import type { ReactNode } from 'react';

import { MiddleTruncate } from './table/MiddleTruncate.tsx';
import classes from './DescriptionList.module.css';

/** One term and its value; `hint` is a dimmed second line under the value. */
export type DescriptionItem = Readonly<{
  /** Also the item's key, so it is unique within the list. */
  term: string;
  value: ReactNode;
  hint?: ReactNode;
}>;

/**
 * Terms and their values, as a native description list. It replaces a two-column key and value
 * table: the terms share one column and the values wrap instead of overflowing. `columns={2}` sets
 * two term and value pairs side by side for a short list in a wide space. A `label` names the list
 * for assistive technology.
 *
 * `oneLine` is for terms and values that are names a broker supplies (a subscription, an address, a role), in
 * a cell with a width of its own: each stays on one line, shortened in the middle when it does not fit
 * and named in full on hover and focus, and neither track shrinks below a readable width, so a long
 * name never squeezes its neighbour into a column of characters. A value that is not a string shortens
 * itself.
 */
export function DescriptionList({
  items,
  columns = 1,
  label,
  oneLine = false,
}: Readonly<{
  items: readonly DescriptionItem[];
  columns?: 1 | 2;
  label?: string;
  oneLine?: boolean;
}>) {
  const list = (
    <dl className={classes.list} data-columns={columns} data-one-line={oneLine || undefined}>
      {items.map((item) => (
        <DescriptionRow key={item.term} item={item} oneLine={oneLine} />
      ))}
    </dl>
  );
  return label ? (
    <fieldset className={classes.group} aria-label={label}>
      {list}
    </fieldset>
  ) : (
    list
  );
}

function DescriptionRow({ item, oneLine }: Readonly<{ item: DescriptionItem; oneLine: boolean }>) {
  const shorten = (text: ReactNode) =>
    oneLine && typeof text === 'string' ? <MiddleTruncate text={text} tooltip /> : text;
  return (
    <>
      <dt className={classes.term}>{shorten(item.term)}</dt>
      <dd className={classes.value}>
        {shorten(item.value)}
        {item.hint ? <div className={classes.hint}>{item.hint}</div> : null}
      </dd>
    </>
  );
}
