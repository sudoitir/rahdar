import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { delay, http, HttpResponse } from 'msw';
import { fireEvent, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

import { IconSitemap } from '@tabler/icons-react';
import { createRoute } from '@tanstack/react-router';

import { CONTRACT, defineFeature } from '../../kernel/feature.ts';
import { clusterRoute } from '../../kernel/routing/roots.ts';
import { renderAppAt } from '../../test/render.tsx';
import { server } from '../../test/setup.ts';
import { paged } from '../../kernel/api/paging.ts';
import type { ClusterSummary, EnvironmentView } from './api.ts';

const option = (name: RegExp | string) => screen.findByRole('option', { name });

const ENVIRONMENTS: EnvironmentView[] = [
  { id: 'e-prod', name: 'Production', colour: '#b3541e', sortOrder: 1 },
  { id: 'e-stage', name: 'Staging', colour: '#2f6f8f', sortOrder: 2 },
];

function cluster(id: string, name: string, over: Partial<ClusterSummary> = {}): ClusterSummary {
  return {
    id,
    name,
    health: 'OK',
    nodeCount: 2,
    updatedAt: '2026-01-01T00:00:00Z',
    environmentId: null,
    system: { id: 'artemis', name: 'Apache ActiveMQ Artemis' },
    kinds: [],
    ...over,
  };
}

/** A cheap view of a cluster, at the path of the real Subscriptions view, so what is under test is the switcher and not a screen. */
const QUEUES = defineFeature({
  contract: CONTRACT,
  id: 'resources',
  routes: {
    cluster: [
      createRoute({
        getParentRoute: () => clusterRoute,
        path: 'subscriptions',
        component: () => <p>A cluster's subscriptions</p>,
      }),
    ],
  },
});

/** A view for one system only, as the Artemis configuration is. */
const ARTEMIS_ONLY = defineFeature({
  contract: CONTRACT,
  id: 'brokerconfig',
  routes: {
    cluster: [
      createRoute({
        getParentRoute: () => clusterRoute,
        path: 'artemis/configuration',
        component: () => <p>An Artemis cluster's configuration</p>,
      }),
    ],
  },
  nav: [
    {
      group: 'observe',
      order: 10,
      label: 'Configuration',
      icon: IconSitemap,
      path: 'artemis/configuration',
      system: 'artemis',
    },
  ],
});

const SMALL = [
  cluster('c1', 'prod-emea', { environmentId: 'e-prod', health: 'DEGRADED' }),
  cluster('c2', 'prod-apac', { environmentId: 'e-prod', nodeCount: 1 }),
  cluster('c3', 'stage-eu', { environmentId: 'e-stage' }),
  cluster('c4', 'scratch'),
];

function mockApi(clusters: ClusterSummary[] = SMALL, { latency = 0 }: { latency?: number } = {}) {
  server.use(
    http.get('*/api/v1/auth/me', () =>
      HttpResponse.json({
        id: 'u1',
        username: 'operator',
        mustChangePassword: false,
        grants: [{ scopeType: 'GLOBAL', scopeId: null, permissions: ['*'] }],
      }),
    ),
    http.get('*/api/v1/clusters', async () => {
      await delay(latency);
      return HttpResponse.json(paged(clusters));
    }),
    http.get('*/api/v1/environments', () => HttpResponse.json(paged(ENVIRONMENTS))),
    http.get('*/api/v1/alerts/firing', () => HttpResponse.json(paged([]))),
    // Whatever a view of the open cluster asks for: the switcher is what is under test, not the view.
    http.all('*/api/v1/clusters/*', () =>
      HttpResponse.json({ status: 404, title: 'Not found', detail: 'Not stubbed' }, { status: 404 }),
    ),
  );
}

describe('ClusterSwitcher', () => {
  beforeEach(() => window.localStorage.clear());
  afterEach(() => vi.restoreAllMocks());

  it('says "Choose a cluster" outside a cluster, in the same row it shows one in', async () => {
    mockApi();
    // A cluster address that names no cluster: the shell has no sidebar outside clusters, so this is the way to one with no cluster chosen.
    renderAppAt('/clusters/unknown-id/topology');

    const trigger = await screen.findByRole('button', { name: 'Choose a cluster' });
    expect(trigger).toHaveAttribute('aria-expanded', 'false');
  });

  it('shows the open cluster with its environment, its health in words and its node count', async () => {
    mockApi();
    renderAppAt('/clusters/c1/subscriptions', [QUEUES]);

    // The clusters and the environments are two requests, answered in either order: the trigger shows the cluster
    // as soon as the clusters arrive, and its environment once the environments do.
    await waitFor(() => expect(screen.getByRole('button', { name: /prod-emea/ })).toHaveTextContent('Production'));
    const trigger = screen.getByRole('button', { name: /prod-emea/ });
    expect(trigger).toHaveTextContent('Degraded');
    expect(trigger).toHaveTextContent('2 nodes');
  });

  it('holds the place of the cluster at its final size while the clusters load', async () => {
    mockApi(SMALL, { latency: 150 });
    renderAppAt('/clusters/c1/subscriptions', [QUEUES]);

    const placeholder = (await screen.findByText('Loading clusters')).closest('output');
    const faceClass = placeholder?.className;
    expect(faceClass).toBeTruthy();

    const trigger = await screen.findByRole('button', { name: /prod-emea/ });
    // One class sets the box's size; the placeholder and the loaded face both carry it.
    expect(trigger.className).toBe(faceClass);
  });

  it('lists every cluster grouped by environment, each with its environment and health, and registration last', async () => {
    mockApi();
    const user = userEvent.setup();
    renderAppAt('/clusters/c1/subscriptions', [QUEUES]);
    await user.click(await screen.findByRole('button', { name: /prod-emea/ }));

    const list = await screen.findByRole('listbox', { name: 'Clusters' });
    const groups = within(list)
      .getAllByRole('group')
      .map((g) => g.getAttribute('aria-labelledby'))
      .map((id) => document.getElementById(id ?? '')?.textContent);
    expect(groups).toEqual(['Production', 'Staging', 'No environment']);

    const emea = await option(/prod-emea/);
    expect(emea).toHaveTextContent('Production');
    expect(emea).toHaveTextContent('Degraded');
    expect(emea).toHaveAttribute('aria-selected', 'true');
    const options = within(list).getAllByRole('option');
    expect(options.at(-1)).toHaveTextContent('Register cluster');
  });

  it('keeps the view when another cluster is chosen', async () => {
    mockApi();
    const error = vi.spyOn(console, 'error');
    const user = userEvent.setup();
    const { router } = renderAppAt('/clusters/c1/subscriptions', [QUEUES]);
    await user.click(await screen.findByRole('button', { name: /prod-emea/ }));

    await user.click(await option(/stage-eu/));

    await screen.findByRole('button', { name: /stage-eu/ });
    expect(router.state.location.pathname).toBe('/clusters/c3/subscriptions');
    // Opening a cluster builds its queries while the shell is on screen: that must not update the shell mid-render.
    expect(error.mock.calls.map((call) => String(call[0]))).not.toContainEqual(
      expect.stringContaining('Cannot update a component'),
    );
  });

  it('leads to the estate from "All clusters"', async () => {
    mockApi();
    const user = userEvent.setup();
    const { router } = renderAppAt('/clusters/c1/subscriptions', [QUEUES]);
    await user.click(await screen.findByRole('button', { name: /prod-emea/ }));

    await user.click(await option('All clusters'));

    await waitFor(() => expect(router.state.location.pathname).toBe('/'));
  });

  it("falls back to a cluster's home when its system lacks the system-only view being kept", async () => {
    mockApi([cluster('c1', 'prod-emea'), cluster('c5', 'other-one', { system: { id: 'other', name: 'Other' } })]);
    const user = userEvent.setup();
    const { router } = renderAppAt('/clusters/c1/artemis/configuration', [ARTEMIS_ONLY]);
    await user.click(await screen.findByRole('button', { name: /prod-emea/ }));

    await user.click(await option(/other-one/));

    await waitFor(() => expect(router.state.location.pathname).toBe('/clusters/c5'));
  });

  it('keeps a system-only view when the other cluster runs the same system', async () => {
    mockApi([cluster('c1', 'prod-emea'), cluster('c2', 'prod-apac')]);
    const user = userEvent.setup();
    const { router } = renderAppAt('/clusters/c1/artemis/configuration', [ARTEMIS_ONLY]);
    await user.click(await screen.findByRole('button', { name: /prod-emea/ }));

    await user.click(await option(/prod-apac/));

    await waitFor(() => expect(router.state.location.pathname).toBe('/clusters/c2/artemis/configuration'));
  });

  it('is operated by keyboard alone: arrows open it, the search takes focus, Enter chooses, Escape returns focus', async () => {
    mockApi();
    const user = userEvent.setup();
    const { router } = renderAppAt('/clusters/c1/subscriptions', [QUEUES]);
    const trigger = await screen.findByRole('button', { name: /prod-emea/ });

    trigger.focus();
    await user.keyboard('{ArrowDown}');
    const search = await screen.findByRole('textbox', { name: 'Search clusters' });
    await waitFor(() => expect(search).toHaveFocus());
    fireEvent.change(search, { target: { value: 'apac' } });
    expect(await option(/prod-apac/)).toBeInTheDocument();
    expect(screen.queryByRole('option', { name: /stage-eu/ })).toBeNull();
    await user.keyboard('{Enter}');

    await screen.findByRole('button', { name: /prod-apac/ });
    expect(router.state.location.pathname).toBe('/clusters/c2/subscriptions');

    await user.keyboard('{ArrowDown}{Escape}');
    await waitFor(() => expect(screen.getByRole('button', { name: /prod-apac/ })).toHaveFocus());
    expect(screen.getByRole('button', { name: /prod-apac/ })).toHaveAttribute('aria-expanded', 'false');
  });

  it('says so when no cluster matches the search', async () => {
    mockApi();
    const user = userEvent.setup();
    renderAppAt('/clusters/c1/subscriptions', [QUEUES]);
    await user.click(await screen.findByRole('button', { name: /prod-emea/ }));

    fireEvent.change(await screen.findByRole('textbox', { name: 'Search clusters' }), {
      target: { value: 'zzz' },
    });

    expect(await screen.findByText('No cluster matches this search.')).toBeInTheDocument();
  });

  it('scales to dozens of clusters: all are listed, and the search narrows them', async () => {
    const many = Array.from({ length: 40 }, (_, i) =>
      cluster(`m${i}`, `cluster-${String(i).padStart(2, '0')}`, { environmentId: i % 2 ? 'e-prod' : 'e-stage' }),
    );
    mockApi(many);
    const user = userEvent.setup();
    renderAppAt('/clusters/m0/subscriptions', [QUEUES]);
    await user.click(await screen.findByRole('button', { name: /cluster-00/ }));

    const list = await screen.findByRole('listbox', { name: 'Clusters' });
    expect(within(list).getAllByRole('option')).toHaveLength(42);

    fireEvent.change(screen.getByRole('textbox', { name: 'Search clusters' }), {
      target: { value: 'cluster-3' },
    });
    expect(within(list).getAllByRole('option')).toHaveLength(11);
  });

  it('opens registration from the end of the list', async () => {
    mockApi();
    const user = userEvent.setup();
    renderAppAt('/clusters/c1/subscriptions', [QUEUES]);
    await user.click(await screen.findByRole('button', { name: /prod-emea/ }));

    await user.click(await option('Register cluster'));

    const dialog = await screen.findByRole('dialog', { name: 'Register cluster' });
    // The form is a chunk of its own, loaded when the dialog first opens; the wait is on the dialog, not the page.
    await waitFor(() => expect(dialog.querySelector('input')).not.toBeNull(), { timeout: 15_000 });
    expect(within(dialog).getByLabelText('Broker management URL')).toBeInTheDocument();
  });

  it('in the collapsed rail is the cluster monogram, named by the cluster and its environment', async () => {
    window.localStorage.setItem('as:nav:collapsed', 'true');
    mockApi();
    renderAppAt('/clusters/c1/subscriptions', [QUEUES]);

    const trigger = await screen.findByRole('button', {
      name: 'PR, Switch cluster, now prod-emea, Production, Degraded',
    });
    expect(trigger).toHaveTextContent(/^PR$/);
  });
});
