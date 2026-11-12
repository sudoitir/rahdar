import { describe, expect, it } from 'vitest';
import { screen, within } from '@testing-library/react';
import {
  createMemoryHistory,
  createRootRoute,
  createRoute,
  createRouter,
  RouterProvider,
} from '@tanstack/react-router';

import { axeViolations, Frame, renderThemed, SCHEMES } from '../../test/browser.tsx';
import { SectionNav, type SectionGroup } from './SectionNav.tsx';

const GROUPS: SectionGroup[] = [
  {
    id: 'access',
    label: 'Access',
    tabs: [
      { id: 'users', title: 'Users', panel: <p>Who can sign in.</p> },
      {
        id: 'roles',
        title: 'Roles and the permissions they carry across every cluster in the installation',
        panel: <p>Roles.</p>,
      },
    ],
  },
  {
    id: 'installation',
    label: 'Installation',
    tabs: Array.from({ length: 24 }, (_, i) => ({ id: `t${i}`, title: `Section ${i}`, panel: <p>Panel {i}.</p> })),
  },
];

function page() {
  const root = createRootRoute();
  const route = createRoute({
    getParentRoute: () => root,
    path: '/admin',
    component: () => <SectionNav label="Administration sections" groups={GROUPS} />,
  });
  return createRouter({
    routeTree: root.addChildren([route]),
    history: createMemoryHistory({ initialEntries: ['/admin'] }),
  });
}

describe('SectionNav in a real browser', () => {
  describe.each(SCHEMES)('in the %s scheme', (scheme) => {
    it('has no accessibility violations', async () => {
      const { container } = renderThemed(
        <Frame width={960} height={600}>
          <RouterProvider router={page()} />
        </Frame>,
        scheme,
      );
      await screen.findByRole('navigation', { name: 'Administration sections' });
      expect(await axeViolations(container)).toEqual([]);
    });
  });

  it('gives the open section the whole width beside the list, however wide the window', async () => {
    const { container } = renderThemed(
      <Frame width={1920} height={800}>
        <RouterProvider router={page()} />
      </Frame>,
      'light',
    );
    await screen.findByRole('navigation', { name: 'Administration sections' });
    const panel = container.querySelector('[data-section-panel]')!;
    // The grid holding the list and the panel: the panel ends where it ends.
    const grid = panel.parentElement!;
    expect(Math.round(panel.getBoundingClientRect().right)).toBe(Math.round(grid.getBoundingClientRect().right));
    expect(panel.getBoundingClientRect().width).toBeGreaterThan(1500);
  });

  it('starts every label and every group heading on one left edge, with whole-row links of one height', async () => {
    renderThemed(
      <Frame width={960} height={600}>
        <RouterProvider router={page()} />
      </Frame>,
      'light',
    );
    const nav = await screen.findByRole('navigation', { name: 'Administration sections' });
    const links = within(nav).getAllByRole('link');
    const headings = ['Access', 'Installation'].map((name) => screen.getByText(name));
    const labelEdges = links.map((link) => link.querySelector('span')!.getBoundingClientRect().left);
    // A heading's text, not its box: the box starts at the list's edge and the padding puts the text on the row's.
    const textLeft = (element: Element) => {
      const range = document.createRange();
      range.selectNodeContents(element);
      return range.getBoundingClientRect().left;
    };
    const headingEdges = headings.map(textLeft);

    expect(new Set([...labelEdges, ...headingEdges].map((edge) => Math.round(edge))).size).toBe(1);
    for (const link of links) {
      const style = getComputedStyle(link);
      expect(style.display).toBe('flex');
      // Chromium's computed value of `justify-content: flex-start` is `flow-start`.
      expect(['flex-start', 'start', 'flow-start']).toContain(style.justifyContent);
      expect(style.textAlign).toBe('start');
      expect(link.getBoundingClientRect().height).toBeGreaterThanOrEqual(36);
      expect(link.getBoundingClientRect().width).toBeGreaterThanOrEqual(nav.clientWidth - 24);
    }
    // Sentence case, and a long label is cut with an ellipsis rather than wrapped.
    const long = links.find((link) => link.textContent?.startsWith('Roles'))!;
    expect(getComputedStyle(long.querySelector('span')!).textOverflow).toBe('ellipsis');
  });

  it('scrolls inside itself when taller than the window, its headings sticky, the open row marked', async () => {
    renderThemed(
      <Frame width={960} height={600}>
        <RouterProvider router={page()} />
      </Frame>,
      'light',
    );
    const nav = await screen.findByRole('navigation', { name: 'Administration sections' });
    expect(getComputedStyle(nav).overflowY).toBe('auto');
    expect(getComputedStyle(nav).position).toBe('sticky');
    expect(getComputedStyle(screen.getByText('Installation')).position).toBe('sticky');
    expect(screen.getByRole('link', { name: 'Users' })).toHaveAttribute('aria-current', 'page');
  });
});
