import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import { onlineManager, QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import {
  createMemoryHistory,
  createRootRoute,
  createRoute,
  createRouter,
  RouterProvider,
} from '@tanstack/react-router';

import { keys as authKeys } from '../../kernel/auth/api.ts';
import { FeatureProvider } from '../../kernel/FeatureProvider.tsx';
import { axeViolations, contentWidth, renderThemed, SCHEMES, settle, type Scheme } from '../../test/browser.tsx';
import { keys } from './api.ts';
import { IndexSubscriptions } from './IndexSubscriptions.tsx';

/**
 * The message index's subscriptions in a real browser at the narrowest window the console is designed for,
 * in both colour schemes: the table, its empty state, the form for a capture with its notices, and the
 * confirmation that deletes one, with no accessibility violations and nothing scrolling sideways.
 */
beforeAll(() => onlineManager.setOnline(false));
afterAll(() => onlineManager.setOnline(true));

const subscription = (over: Record<string, unknown> = {}) => ({
  id: 's1',
  subscriptionPattern: 'ORDER.IN',
  retentionDays: 7,
  intervalMs: 5000,
  captureFrom: '2026-09-01T09:00:00Z',
  createdAt: '2026-09-01T09:00:00Z',
  createdBy: 'op',
  enabled: true,
  mode: 'SAMPLE',
  messagesHeld: 1284,
  bytesHeld: 2_600_000,
  oldestObservedAt: '2026-09-01T09:00:03Z',
  ...over,
});

const SUBSCRIPTIONS = [
  subscription(),
  subscription({
    id: 's2',
    subscriptionPattern: 'PAYMENT.#',
    mode: 'CAPTURE',
    enabled: false,
    maxBytes: 536_870_912,
    nodes: [
      { nodeId: 'n1', nodeName: 'broker-1', state: 'ACTIVE', capturedFrom: '2026-09-01T09:00:00Z' },
      { nodeId: 'n2', nodeName: 'broker-2', state: 'DEGRADED', droppedEstimate: 12, detail: 'ring full' },
    ],
  }),
];

function mount(scheme: Scheme, data: unknown[]) {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false, staleTime: Infinity, gcTime: Infinity } },
  });
  client.setQueryData(authKeys.me, {
    id: 'u1',
    username: 'admin',
    mustChangePassword: false,
    grants: [{ scopeType: 'GLOBAL', scopeId: null, permissions: ['*'] }],
  });
  client.setQueryData(keys.sqlIndex('c1'), data);
  const root = createRootRoute();
  const route = createRoute({
    getParentRoute: () => root,
    path: '/clusters/$clusterId/settings',
    component: IndexSubscriptions,
  });
  const router = createRouter({
    routeTree: root.addChildren([route]),
    history: createMemoryHistory({ initialEntries: ['/clusters/c1/settings'] }),
  });
  return renderThemed(
    <QueryClientProvider client={client}>
      <FeatureProvider features={[]}>
        <div style={{ inlineSize: contentWidth(1280) }}>
          <RouterProvider router={router} />
        </div>
      </FeatureProvider>
    </QueryClientProvider>,
    scheme,
  );
}

async function settled(container: HTMLElement) {
  await screen.findByRole('heading', { level: 3, name: 'Subscriptions' });
  await settle(() => `${container.querySelectorAll('tr').length}:${container.scrollHeight}`);
}

async function check(container: HTMLElement) {
  expect(await axeViolations(container)).toEqual([]);
  expect(container.scrollWidth).toBeLessThanOrEqual(container.clientWidth + 1);
}

describe.each(SCHEMES)('Index subscriptions in the %s scheme at 1280 px', (scheme) => {
  it('lists a sampling and a capturing subscription in a table, and the form beneath it', async () => {
    const { container } = mount(scheme, SUBSCRIPTIONS);
    await settled(container);
    expect(screen.getByRole('table', { name: 'Index subscriptions' })).toBeVisible();
    expect(screen.getByText(/broker-2: capturing, losing messages · about 12 missed — ring full/)).toBeVisible();
    await check(container);
  });

  it('teaches what an index is when none exists', async () => {
    const { container } = mount(scheme, []);
    await settled(container);
    expect(await screen.findByText('Nothing is being indexed')).toBeVisible();
    await check(container);
  });

  it('states what capture changes on the brokers, before it can be started', async () => {
    const { container } = mount(scheme, []);
    await settled(container);
    await userEvent.click(screen.getByRole('radio', { name: 'Capture everything' }));
    expect(await screen.findByText('This changes routing on every live node')).toBeVisible();
    expect(screen.getByText('This stores message bodies')).toBeVisible();
    await check(container);
  });

  it('opens the confirmation that deletes a subscription, with its blast radius', async () => {
    const { container } = mount(scheme, SUBSCRIPTIONS);
    await settled(container);
    await userEvent.click(screen.getAllByRole('button', { name: 'Delete' })[1]);

    const dialog = await screen.findByRole('dialog', { name: 'Delete index subscription PAYMENT.#' });
    await settle(() => getComputedStyle(dialog).opacity);
    expect(screen.getByText(/It also removes the divert, the capture subscription/)).toBeVisible();
    expect(await axeViolations(dialog)).toEqual([]);
  });
});
