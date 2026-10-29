import { ScrollArea, type ScrollAreaAutosizeProps } from '@mantine/core';

/**
 * The scroll area of a drawer or dialog whose content is only text: a scrollable region with nothing focusable in it
 * cannot be scrolled from the keyboard, so the region itself takes focus (WCAG 2.1.1). Pass it as
 * `scrollAreaComponent`.
 */
export function FocusableScroll(props: Readonly<ScrollAreaAutosizeProps>) {
  return (
    <ScrollArea.Autosize
      {...props}
      viewportProps={{ ...props.viewportProps, tabIndex: 0, role: 'region', 'aria-label': 'Details, scrollable' }}
    />
  );
}
