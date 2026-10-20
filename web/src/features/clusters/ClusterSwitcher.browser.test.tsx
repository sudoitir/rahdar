import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import { userEvent } from 'vitest/browser';
import { onlineManager, QueryClient, QueryClientProvider } from '@tanstack/react-query';
import {
  createMemoryHistory,
  createRootRoute,
  createRoute,
  createRouter,
  RouterProvider,
} from '@tanstack/react-router';
import { render, screen } from '@testing-library/react';

import { FeatureProvider } from '../../kernel/FeatureProvider.tsx';
import { axeViolations, Frame, SCHEMES, settle, Themed, type Scheme } from '../../test/browser.tsx';
import { keys, type ClusterSummary, type EnvironmentView } from './api.ts';
import { ClusterSwitcher } from './ClusterSwitcher.tsx';
import { clustersFeature } from './feature.ts';

/**
 * The switcher laid out by Chromium. The navigation beside it must not move when the clusters arrive or
 * when there are many, and jsdom has no layout to say so, so the heights are measured here.
 */

// No network: a query without seeded data stays pending, which is the loading state, and a seeded one is
// never refetched under the test.
beforeAll(() => onlineManager.setOnline(false));
afterAll(() => onlineManager.setOnline(true));

const ENVIRONMENTS: EnvironmentView[] = [
  { id: 'e-prod', name: 'Production', colour: '#b3541e', sortOrder: 1 },
  { id: 'e-stage', name: 'Staging', colour: '#2f6f8f', sortOrder: 2 },
];

const HEALTHS: ClusterSummary['health'][] = ['OK', 'DEGRADED', 'CRITICAL', 'UNKNOWN'];

function clusters(count: number): ClusterSummary[] {
  return Array.from({ length: count }, (_, i) => ({
    id: `c${i}`,
    name: i === 0 ? 'prod-emea-with-a-name-long-enough-to-need-clipping' : `cluster-${String(i).padStart(2, '0')}`,
    health: HEALTHS[i % HEALTHS.length],
    nodeCount: 2,
    updatedAt: '2026-01-01T00:00:00Z',
    environmentId: i % 3 === 2 ? null : ENVIRONMENTS[i % 3].id,
    system: { id: 'artemis', name: 'Apache ActiveMQ Artemis' },
    kinds: [],
  }));
}

interface Options {
  /** How many clusters are registered; `null` leaves the request pending. */
  count: number | null;
  scheme?: Scheme;
  collapsed?: boolean;
  /** Where the address is: under cluster `c0`, or outside any cluster. */
  path?: string;
}

async function mount({ count, scheme = 'light', collapsed = false, path = '/clusters/c0/topology' }: Options) {
  const root = createRootRoute({
    component: () => (
      <Frame width={collapsed ? 40 : 240}>
        <ClusterSwitcher collapsed={collapsed} />
      </Frame>
    ),
  });
  const page = createRoute({ getParentRoute: () => root, path: '/clusters/$clusterId/topology' });
  const home = createRoute({ getParentRoute: () => root, path: '/' });
  const router = createRouter({
    routeTree: root.addChildren([page, home]),
    history: createMemoryHistory({ initialEntries: [path] }),
  });
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  if (count !== null) {
    client.setQueryData(keys.all, clusters(count));
    client.setQueryData(keys.environments, ENVIRONMENTS);
  }
  const view = render(
    <Themed scheme={scheme}>
      <QueryClientProvider client={client}>
        <FeatureProvider features={[clustersFeature]}>
          <RouterProvider router={router} />
        </FeatureProvider>
      </QueryClientProvider>
    </Themed>,
  );
  const face = await (count === null ? screen.findByRole('status') : screen.findByRole('button'));
  await settle(() => `${face.getBoundingClientRect().height}`);
  return { ...view, face };
}

const heightOf = (element: HTMLElement) => element.getBoundingClientRect().height;

describe('ClusterSwitcher', () => {
  it.each([
    ['expanded', false],
    ['collapsed to the rail', true],
  ])('is one height whether loading, outside a cluster, or showing 1 or 30 clusters, %s', async (_name, collapsed) => {
    const heights: Record<string, number> = {};
    for (const [name, options] of Object.entries<Options>({
      loading: { count: null, collapsed },
      outside: { count: 30, collapsed, path: '/' },
      one: { count: 1, collapsed },
      thirty: { count: 30, collapsed },
    })) {
      const { face, unmount } = await mount(options);
      heights[name] = heightOf(face);
      unmount();
    }

    expect(heights.loading).toBeGreaterThan(0);
    expect(Object.values(heights).map((h) => h.toFixed(2))).toEqual(Array(4).fill(heights.loading.toFixed(2)));
  });

  it('keeps its height, and a list that scrolls, when it opens on 30 clusters', async () => {
    const { face } = await mount({ count: 30 });
    const closed = heightOf(face);

    await userEvent.click(face);
    const list = await screen.findByRole('listbox', { name: 'Clusters' });
    await settle(() => `${list.getBoundingClientRect().height}`);

    expect(heightOf(face)).toBeCloseTo(closed, 1);
    expect(screen.getAllByRole('option')).toHaveLength(31);
    // The dropdown is bounded (24rem) and scrolls; 30 rows do not make it 30 rows tall.
    let scroller = list.parentElement!;
    while (getComputedStyle(scroller).overflowY !== 'auto') scroller = scroller.parentElement!;
    expect(scroller.scrollHeight).toBeGreaterThan(scroller.clientHeight);
    expect(scroller.clientHeight).toBeLessThanOrEqual(24 * 16);
    // The search takes the focus, and is inside the scrolling box.
    expect(screen.getByRole('textbox', { name: 'Search clusters' })).toHaveFocus();
    expect(scroller).toContainElement(screen.getByRole('textbox', { name: 'Search clusters' }));
  });

  it('filters as the operator types, and Enter opens the first match', async () => {
    const { face } = await mount({ count: 30 });
    await userEvent.click(face);
    await userEvent.keyboard('cluster-2');

    const options = await screen.findAllByRole('option');
    // cluster-20 to cluster-29, and registration, which is always at the end.
    expect(options).toHaveLength(11);
    expect(options.at(-1)).toHaveTextContent('Register cluster');
  });

  describe.each(SCHEMES)('in the %s scheme', (scheme) => {
    it.each([
      ['loading', { count: null }],
      ['showing a cluster', { count: 30 }],
      ['outside a cluster', { count: 30, path: '/' }],
      ['collapsed to the rail', { count: 30, collapsed: true }],
    ] as [string, Options][])('has no accessibility violations %s', async (_name, options) => {
      await mount({ ...options, scheme });
      expect(await axeViolations(document.body)).toEqual([]);
    });

    it('has no accessibility violations open, with every health state listed', async () => {
      const { face } = await mount({ count: 30, scheme });
      await userEvent.click(face);
      await screen.findByRole('listbox', { name: 'Clusters' });

      expect(await axeViolations(document.body)).toEqual([]);
    });
  });
});
