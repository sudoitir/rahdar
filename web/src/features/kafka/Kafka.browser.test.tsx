import { afterAll, beforeAll, describe, expect, it, vi } from 'vitest';
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
import type { ReactNode } from 'react';

import { axeViolations, contentWidth, Frame, SCHEMES, settle, Themed, type Scheme } from '../../test/browser.tsx';
import { AclsView } from './AclsView.tsx';
import { keys, type Acl, type GroupDetail, type GroupSummary, type TopicConfig, type Transaction } from './api.ts';
import { BrokersView } from './BrokersView.tsx';
import { GroupsView } from './GroupsView.tsx';
import { GroupView } from './GroupView.tsx';
import { TopicConfigView } from './TopicConfigView.tsx';
import { TransactionsView } from './TransactionsView.tsx';

/**
 * The Kafka views as Chromium lays them out and a keyboard walks them: every state is seeded into the query
 * cache (there is no network), checked for accessibility in both colour schemes and for a page that stays
 * inside its box at the width of a 1280 px window.
 */

beforeAll(() => onlineManager.setOnline(false));
afterAll(() => onlineManager.setOnline(true));

const LONG = 'customer-order-fulfilment-backlog-on-the-eu-west-primary-consumer-group-v2';

const exact = (value: number) => ({ value, accuracy: 'EXACT' as const, reason: null, breakdown: false });

const GROUPS: GroupSummary[] = [
  { id: LONG, type: 'classic', state: 'STABLE', members: 12, lag: exact(1_234_567) },
  {
    id: 'billing',
    type: 'consumer',
    state: 'PREPARING_REBALANCE',
    members: 3,
    lag: { value: 12400, accuracy: 'ESTIMATE', reason: 'Two partitions have no committed offset.', breakdown: true },
  },
  {
    id: 'shares',
    type: 'share',
    state: 'EMPTY',
    members: 0,
    lag: { value: null, accuracy: 'UNAVAILABLE', reason: 'A share group has no lag.', breakdown: false },
    readOnly: 'Share group offsets cannot be reset.',
  },
];

const GROUP: GroupDetail = {
  id: 'billing',
  type: 'classic',
  state: 'EMPTY',
  members: [],
  sampledAt: '2026-10-28T09:00:00Z',
  topics: [
    {
      topic: LONG,
      deleted: false,
      partitions: [
        { partition: 0, committed: 10, end: 100, lag: 90 },
        { partition: 1, end: 50, reason: 'No offset has been committed here.' },
      ],
    },
    { topic: 'old-orders', deleted: true, partitions: [{ partition: 0, committed: 5, end: 5, lag: 0 }] },
  ],
};

const CONFIG: TopicConfig = {
  internal: false,
  entries: [
    {
      name: 'retention.ms',
      value: '604800000',
      source: 'TOPIC',
      defaultValue: '604800000',
      readOnly: false,
      sensitive: false,
      unit: 'ms',
    },
    { name: 'ssl.key.password', value: null, source: 'STATIC', readOnly: false, sensitive: true },
    {
      name: 'message.format.version',
      value: '3.0',
      source: 'STATIC',
      defaultValue: '3.0',
      readOnly: true,
      sensitive: false,
    },
  ],
};

const ACL: Acl = {
  principal: 'User:app',
  host: '*',
  operation: 'read',
  permission: 'allow',
  resourceType: 'topic',
  pattern: 'literal',
  name: LONG,
};

const TRANSACTIONS: Transaction[] = [
  {
    id: 'payments-1',
    state: 'Ongoing',
    startedAt: '2026-10-28T08:00:00Z',
    timeoutMs: 60_000,
    hanging: true,
    blocked: [{ topic: 'payments', partition: 2 }],
  },
  { id: 'audit-7', state: 'Empty', startedAt: null, timeoutMs: 60_000, hanging: false, blocked: [] },
];

type Seed = (client: QueryClient) => void;

interface Case {
  name: string;
  route: string;
  path: string;
  element: ReactNode;
  seed: Seed;
}

const infinite = <T,>(items: T[], extra: object = {}) => ({
  pages: [{ items, next: null, ...extra }],
  pageParams: [undefined],
});

const CASES: Case[] = [
  {
    name: 'groups',
    route: '/clusters/$clusterId/kafka/groups',
    path: '/clusters/k1/kafka/groups',
    element: <GroupsView />,
    seed: (c) => c.setQueryData(keys.groups('k1', {}), infinite(GROUPS)),
  },
  {
    name: 'no groups',
    route: '/clusters/$clusterId/kafka/groups',
    path: '/clusters/k1/kafka/groups',
    element: <GroupsView />,
    seed: (c) => c.setQueryData(keys.groups('k1', {}), infinite([])),
  },
  {
    name: 'a group',
    route: '/clusters/$clusterId/kafka/groups/$group',
    path: '/clusters/k1/kafka/groups/billing',
    element: <GroupView />,
    seed: (c) => c.setQueryData(keys.group('k1', 'billing'), GROUP),
  },
  {
    name: 'a topic configuration',
    route: '/clusters/$clusterId/kafka/topics/$topic/config',
    path: '/clusters/k1/kafka/topics/orders/config',
    element: <TopicConfigView />,
    seed: (c) => c.setQueryData(keys.config('k1', 'orders'), CONFIG),
  },
  {
    name: 'ACLs',
    route: '/clusters/$clusterId/kafka/acls',
    path: '/clusters/k1/kafka/acls',
    element: <AclsView />,
    seed: (c) => c.setQueryData(keys.acls('k1', {}), infinite([ACL], { available: true })),
  },
  {
    name: 'ACLs without an authoriser',
    route: '/clusters/$clusterId/kafka/acls',
    path: '/clusters/k1/kafka/acls',
    element: <AclsView />,
    seed: (c) =>
      c.setQueryData(
        keys.acls('k1', {}),
        infinite([], { available: false, reason: 'The cluster runs without an authorizer.' }),
      ),
  },
  {
    name: 'transactions',
    route: '/clusters/$clusterId/kafka/transactions',
    path: '/clusters/k1/kafka/transactions',
    element: <TransactionsView />,
    seed: (c) => c.setQueryData(keys.transactions('k1'), { items: TRANSACTIONS }),
  },
  {
    name: 'brokers on ZooKeeper',
    route: '/clusters/$clusterId/kafka/brokers',
    path: '/clusters/k1/kafka/brokers',
    element: <BrokersView />,
    seed: (c) =>
      c.setQueryData(keys.brokers('k1'), {
        brokers: [
          { id: '1', rack: 'a', address: 'k1:9092', reachable: true, leaders: 10, underReplicated: 0, offline: 0 },
          {
            id: '2',
            address: 'k2:9092',
            reachable: false,
            reason: 'Connection timed out.',
            leaders: 0,
            underReplicated: 4,
            offline: 1,
          },
        ],
        mode: 'ZooKeeper',
        quorum: { available: false, reason: 'The cluster runs ZooKeeper.', voters: [], majority: false },
      }),
  },
];

async function mount(c: Case, scheme: Scheme, width = contentWidth(1280)) {
  const root = createRootRoute({
    component: () => (
      <Frame width={width} height={900}>
        {c.element}
      </Frame>
    ),
  });
  const page = createRoute({ getParentRoute: () => root, path: c.route });
  const router = createRouter({
    routeTree: root.addChildren([page]),
    history: createMemoryHistory({ initialEntries: [c.path] }),
  });
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  c.seed(client);
  const view = render(
    <Themed scheme={scheme}>
      <QueryClientProvider client={client}>
        <RouterProvider router={router} />
      </QueryClientProvider>
    </Themed>,
  );
  await screen.findByRole('heading', { level: 1 });
  await settle(() => `${document.body.scrollHeight}`);
  return view;
}

describe.each(SCHEMES)('the Kafka views in the %s scheme', (scheme) => {
  it.each(CASES)('$name is axe-clean and inside its box', async (c) => {
    const { container } = await mount(c, scheme);
    expect(await axeViolations(container)).toEqual([]);
    const frame = container.querySelector<HTMLElement>(':scope > div')!;
    expect(frame.scrollWidth).toBeLessThanOrEqual(frame.clientWidth);
  });
});

describe('resetting a group by keyboard alone', () => {
  const group = CASES.find((c) => c.name === 'a group')!;

  it('opens the dialog, keeps focus inside it, and Escape returns focus to the button', async () => {
    await mount(group, 'light');
    const trigger = await screen.findByRole('button', { name: 'Reset offsets…' });
    trigger.focus();
    await userEvent.keyboard('{Enter}');

    const dialog = await screen.findByRole('dialog', { name: 'Reset the offsets of billing' });
    await settle(() => `${dialog.getBoundingClientRect().height}`);
    expect(dialog).toContainElement(document.activeElement as HTMLElement);
    // The confirmation waits for a preview, and says so.
    expect(screen.getByRole('button', { name: 'Reset offsets' })).toBeDisabled();
    expect(screen.getByText(/Preview the reset first/)).toBeInTheDocument();
    expect(await axeViolations(dialog)).toEqual([]);

    await userEvent.keyboard('{Escape}');
    await vi.waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
    expect(trigger).toHaveFocus();
  });
});
