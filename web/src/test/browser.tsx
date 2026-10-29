import type { ReactElement, ReactNode } from 'react';
import { DEFAULT_THEME, MantineProvider, mergeMantineTheme } from '@mantine/core';
import { render } from '@testing-library/react';
import axe from 'axe-core';

import { cssVariablesResolver, theme } from '../theme.ts';

/** The two colour schemes every browser test checks (ADR-0159). */
export const SCHEMES = ['light', 'dark'] as const;
export type Scheme = (typeof SCHEMES)[number];

/** The size of the browser's own vertical scroll bar, which a page with more content than its window shows. */
function scrollBarWidth(): number {
  const probe = document.createElement('div');
  probe.style.cssText = 'position:absolute;inline-size:100px;block-size:100px;overflow:scroll;visibility:hidden';
  document.body.append(probe);
  const width = probe.offsetWidth - probe.clientWidth;
  probe.remove();
  return width;
}

/**
 * The width of the content box of a window `windowWidth` px wide at 100% zoom, with the navigation
 * expanded: what the application shell leaves a page. It is the window, less the navigation's width and
 * the shell's padding on both sides (`padding="lg"` of `AppShell`), from the same theme values the shell
 * reads, and less the scroll bar of a page taller than its window, which is the browser's own.
 */
export function contentWidth(windowWidth: number): number {
  const shell = mergeMantineTheme(DEFAULT_THEME, theme);
  const { navW } = shell.other.layout as { navW: number };
  const rootFont = Number.parseFloat(getComputedStyle(document.documentElement).fontSize);
  const padding = Number.parseFloat(shell.spacing.lg) * rootFont;
  return windowWidth - navW - 2 * padding - scrollBarWidth();
}

/**
 * A box of a fixed size, standing in for the content area of a window of that width. It is a column,
 * as the shell's main area is, so a page that fills grows into the height and its grid has a viewport.
 */
export function Frame({
  width,
  height = 480,
  children,
}: Readonly<{ width: number; height?: number; children: ReactNode }>) {
  return (
    <div style={{ display: 'flex', flexDirection: 'column', inlineSize: width, blockSize: height }}>{children}</div>
  );
}

/** `ui` under Mantine with the application's real theme and variables resolver, in one colour scheme. */
export function Themed({ scheme, children }: Readonly<{ scheme: Scheme; children: ReactNode }>) {
  return (
    <MantineProvider theme={theme} cssVariablesResolver={cssVariablesResolver} forceColorScheme={scheme}>
      {children}
    </MantineProvider>
  );
}

/** The `Frame` of a render: Mantine puts its own `<style>` elements in the container before it. */
export const frameOf = (container: HTMLElement) => container.querySelector<HTMLElement>(':scope > div')!;

export function renderThemed(ui: ReactElement, scheme: Scheme) {
  return render(ui, { wrapper: ({ children }) => <Themed scheme={scheme}>{children}</Themed> });
}

/** Frames, by browser animation frame, until `signature()` has said the same thing for a few in a row. */
export async function settle(signature: () => string, stableFrames = 4): Promise<void> {
  await document.fonts.ready;
  let last = '';
  let stable = 0;
  for (let frame = 0; frame < 240 && stable < stableFrames; frame++) {
    await new Promise<void>((resolve) => requestAnimationFrame(() => resolve()));
    const now = signature();
    stable = now === last ? stable + 1 : 0;
    last = now;
  }
}

/** WCAG 2.2 AA, which is what ADR-0165 holds every page and part to. */
const WCAG = ['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa', 'wcag22aa'];

/** What axe finds wrong inside `root`, one readable line each; an empty list is a pass. */
export async function axeViolations(root: Element): Promise<string[]> {
  const { violations } = await axe.run(root, { runOnly: { type: 'tag', values: WCAG } });
  return violations.map(
    (v) =>
      `${v.id} (${v.impact}): ${v.help} at ${v.nodes.map((n) => `${n.target.join(' ')} ${n.failureSummary}`).join(' | ')}`,
  );
}
