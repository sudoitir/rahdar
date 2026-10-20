import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import { onlineManager, QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import {
  createMemoryHistory,
  createRootRoute,
  createRoute,
  createRouter,
  RouterProvider,
} from '@tanstack/react-router';

import { contentWidth, Frame, renderThemed } from '../../test/browser.tsx';
import type { ConfigDiffView as Diff } from './api.ts';
import { cleanDiff, diff } from './configDiffFixtures.ts';
import { ConfigDiffView } from './ConfigDiffView.tsx';
import { validateConfigDiffSearch } from './feature.ts';

/**
 * The node comparison in a real browser, under the real route and its search validation: it opens on
 * drift, says a clean cluster is clean, and keeps its view, filter and nodes in the address so a
 * shared link shows the same thing.
 */
// No network: the one query is answered from the cache the test seeds, and nothing is fetched.
beforeAll(() => onlineManager.setOnline(false));
afterAll(() => onlineManager.setOnline(true));

function open(data: Diff, search = '') {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: Infinity } } });
  client.setQueryData(['clusters', 'c1', 'config-diff'], data);
  const root = createRootRoute();
  const route = createRoute({
    getParentRoute: () => root,
    path: '/clusters/$clusterId/artemis/config-diff',
    component: ConfigDiffView,
    validateSearch: validateConfigDiffSearch,
  });
  const router = createRouter({
    routeTree: root.addChildren([route]),
    history: createMemoryHistory({ initialEntries: [`/clusters/c1/artemis/config-diff${search}`] }),
  });
  renderThemed(
    <QueryClientProvider client={client}>
      <Frame width={contentWidth(1280)} height={900}>
        <RouterProvider router={router} />
      </Frame>
    </QueryClientProvider>,
    'light',
  );
  return router;
}

describe('Config diff', () => {
  it('opens on the drift, one sentence and only the keys that drift', async () => {
    open(diff());

    expect(await screen.findByText(/2 keys drift on 1 node\./)).toBeInTheDocument();
    expect(screen.getByRole('radio', { name: 'Drift' })).toBeChecked();
    const grid = await screen.findByRole('grid', { name: 'Configuration keys' });
    expect(within(grid).getByRole('row', { name: /\/MaxDiskUsage/ })).toHaveTextContent('broker-3 differs: 80');
    expect(within(grid).queryByRole('row', { name: /\/Name/ })).toBeNull();
  });

  it('says a clean cluster is clean, with the expected differences it set aside, and lists no rows', async () => {
    open(cleanDiff());

    expect(await screen.findByText(/No key drifts across the 3 nodes compared\./)).toHaveTextContent(
      '1 expected difference set aside.',
    );
    expect(screen.getByText('Nothing drifts')).toBeInTheDocument();
  });

  it('shows the view, search and nodes a shared address names', async () => {
    open(diff(), '?view=all&q=disk&nodes=%5B%22n-c%22%5D');

    expect(await screen.findByRole('radio', { name: 'All keys' })).toBeChecked();
    expect(screen.getByRole('textbox', { name: 'Search keys and values' })).toHaveValue('disk');
    expect(
      screen.getByText('broker-3', { selector: '.mantine-MultiSelect-pill span, .mantine-Pill-root' }),
    ).toBeVisible();
    const grid = await screen.findByRole('grid', { name: 'Configuration keys' });
    expect(within(grid).getByRole('row', { name: /\/MaxDiskUsage/ })).toBeInTheDocument();
    expect(within(grid).queryByRole('row', { name: /\/orders/ })).toBeNull();
  });

  it('writes what the operator chooses into the address, and leaves the default out of it', async () => {
    const router = open(diff());
    const user = userEvent.setup();

    await screen.findByRole('grid', { name: 'Configuration keys' });
    await user.click(screen.getByRole('radio', { name: 'Expected' }));
    await waitFor(() => expect(router.state.location.search).toEqual({ view: 'expected' }));

    await user.type(screen.getByRole('textbox', { name: 'Search keys and values' }), 'name');
    await waitFor(() => expect(router.state.location.search).toEqual({ view: 'expected', q: 'name' }));

    await user.click(screen.getByRole('radio', { name: 'Drift' }));
    await waitFor(() => expect(router.state.location.search).toEqual({ q: 'name' }));
  });
});
