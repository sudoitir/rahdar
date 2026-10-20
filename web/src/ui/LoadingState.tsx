import { Loader, VisuallyHidden } from '@mantine/core';

import classes from './LoadingState.module.css';

/**
 * Where a view's content will appear while it loads. Never a bare spinner: the label names what is
 * loading for assistive technology, and the frame reserves `blockSize` and `inlineSize` (any CSS
 * length) so nothing around it shifts when the content arrives. Tables and figures use their own
 * `loading` props, which hold their own size.
 */
export function LoadingState({
  label,
  variant = 'block',
  blockSize,
  inlineSize,
}: Readonly<{
  /** What is loading, such as "Loading subscriptions". Read aloud, not shown. */
  label: string;
  /** `block` fills the width it is in; `inline` sits in a line of text. */
  variant?: 'block' | 'inline';
  /** The height to hold, as a CSS length such as `12rem`. */
  blockSize?: string;
  /** The width to hold, as a CSS length. */
  inlineSize?: string;
}>) {
  return (
    <output
      className={classes.root}
      aria-busy="true"
      data-variant={variant}
      style={{ minBlockSize: blockSize, minInlineSize: inlineSize }}
    >
      <Loader size={variant === 'inline' ? 'xs' : 'sm'} aria-hidden="true" />
      <VisuallyHidden>{label}</VisuallyHidden>
    </output>
  );
}
