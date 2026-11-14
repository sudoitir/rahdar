import { useState, type ReactElement, type ReactNode } from 'react';
import { createTheme, MantineProvider, mergeThemeOverrides, Popover } from '@mantine/core';
import { Menu } from '@mantine/core';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { cleanup, render, screen } from '@testing-library/react';
import { page, userEvent } from 'vitest/browser';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { ActionHostProvider } from '../../kernel/actions/ActionHost.tsx';
import { useActionHost } from '../../kernel/actions/hostContext.ts';
import { ApiError } from '../../kernel/api/request.ts';
import { theme } from '../../theme.ts';
import { CreateTopicForm } from './CreateTopicForm.tsx';
import { GroupDelete } from './GroupDelete.tsx';
import { GroupsView } from './GroupsView.tsx';
import { DeleteTopic, IncreasePartitions } from './TopicLifecycleActions.tsx';
import type { GroupDetail, TopicPreview } from './api.ts';

const search = vi.hoisted(() => ({
  q: undefined as string | undefined,
  type: undefined as string | undefined,
  state: undefined as string | undefined,
}));
const query = vi.hoisted(() => ({
  pending: false,
  error: false,
  items: [] as { id: string }[],
}));
const gate = vi.hoisted(() => ({ kind: 'allowed' as 'allowed' | 'blocked', reason: 'You do not have permission.' }));
const auth = vi.hoisted(() => ({ permitted: true }));
const api = vi.hoisted(() => ({
  deleteError: null as unknown,
  partitionsError: null as unknown,
  planned: null as TopicPreview | null,
}));

vi.mock('@tanstack/react-router', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@tanstack/react-router')>();
  return {
    ...actual,
    useParams: () => ({ clusterId: 'k1' }),
    useSearch: () => search,
    useNavigate: () => () => undefined,
  };
});

vi.mock('../../kernel/auth/useResourceGate.ts', () => ({
  useResourceGate: () => ({
    hidden: false,
    verdict: gate.kind === 'blocked' ? { kind: 'blocked', reason: gate.reason } : { kind: 'allowed', uncertain: false },
  }),
}));

vi.mock('../../kernel/auth/useCan.ts', () => ({
  useCan: () => ({
    can: () => auth.permitted,
    loading: false,
    canAnywhere: () => true,
    canOn: () => auth.permitted,
  }),
}));

function mutation(error: () => unknown) {
  return {
    mutate: (_input: unknown, opts?: { onSuccess?: (value: TopicPreview) => void }) => {
      if (api.planned) opts?.onSuccess?.(api.planned);
    },
    isPending: false,
    error: error(),
  };
}

vi.mock('./api.ts', async (importOriginal) => {
  const actual = await importOriginal<typeof import('./api.ts')>();
  return {
    ...actual,
    useGroups: () => ({
      data: { pages: [{ items: query.items }] },
      isPending: query.pending,
      isError: query.error,
      error: query.error ? new Error('The cluster could not be reached.') : null,
      hasNextPage: false,
      isFetchingNextPage: false,
      fetchNextPage: () => undefined,
      refetch: () => undefined,
    }),
    usePreviewDeleteTopic: () => mutation(() => api.deleteError),
    useDeleteTopic: () => mutation(() => null),
    usePreviewIncreasePartitions: () => mutation(() => api.partitionsError),
    useIncreasePartitions: () => mutation(() => null),
    usePreviewDeleteGroup: () => mutation(() => null),
    useDeleteGroup: () => mutation(() => null),
  };
});

const testTheme = mergeThemeOverrides(
  theme,
  createTheme({ components: { Popover: Popover.extend({ defaultProps: { hideDetached: false } }) } }),
);

const WINDOWS = [
  { name: '1920', width: 1920, height: 1080 },
  { name: '1440', width: 1440, height: 900 },
  { name: '1280', width: 1280, height: 800 },
  { name: '200pct', width: 640, height: 400 },
] as const;

const SCHEMES = ['light', 'dark'] as const;

function preview(over: Partial<TopicPreview> = {}): TopicPreview {
  return {
    name: 'orders',
    etag: 'etag-1',
    fingerprint: 'fp-1',
    note: "Deleting this topic does not erase those groups' offsets.",
    groups: ['billing'],
    estimate: 4,
    accuracy: 'estimate',
    partitions: 6,
    replicationFactor: 3,
    currentPartitions: 6,
    cap: 1000,
    overCap: false,
    ...over,
  };
}

function detail(over: Partial<GroupDetail> = {}): GroupDetail {
  return {
    id: 'billing',
    type: 'classic',
    state: 'EMPTY',
    topics: [],
    members: [],
    sampledAt: '2026-11-12T00:00:00Z',
    ...over,
  };
}

function Providers({ scheme, children }: { scheme: (typeof SCHEMES)[number]; children: ReactNode }) {
  const [client] = useState(() => new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0 } } }));
  return (
    <MantineProvider theme={testTheme} forceColorScheme={scheme}>
      <QueryClientProvider client={client}>
        <ActionHostProvider>{children}</ActionHostProvider>
      </QueryClientProvider>
    </MantineProvider>
  );
}

function draw(ui: ReactElement, scheme: (typeof SCHEMES)[number]) {
  return render(ui, { wrapper: ({ children }) => <Providers scheme={scheme}>{children}</Providers> });
}

const target = { id: 'kafka.topic/orders', kind: 'kafka.topic', name: 'orders' };

function TopicMenu({ action }: { action: 'delete' | 'partitions' }) {
  const host = useActionHost();
  const Item = action === 'delete' ? DeleteTopic : IncreasePartitions;
  return (
    <Menu opened>
      <Menu.Dropdown>
        <Item clusterId="k1" target={target} host={host} mode="act" />
      </Menu.Dropdown>
    </Menu>
  );
}

async function shot(name: string) {
  await document.fonts.ready;
  expect(document.documentElement.scrollWidth).toBeLessThanOrEqual(document.documentElement.clientWidth + 1);
  await page.screenshot({ path: `__screenshots__/${name}.png`, save: true });
}

beforeEach(() => {
  search.q = undefined;
  search.type = undefined;
  search.state = undefined;
  query.pending = false;
  query.error = false;
  query.items = [];
  gate.kind = 'allowed';
  auth.permitted = true;
  api.deleteError = null;
  api.partitionsError = null;
  api.planned = preview();
});

describe('topic and group lifecycle on a desktop', () => {
  it('shows each named state in light and dark at every desktop width', async () => {
    for (const frame of WINDOWS) {
      await page.viewport(frame.width, frame.height);
      for (const scheme of SCHEMES) {
        const prefix = `${frame.name}-${scheme}`;

        api.planned = preview();
        api.deleteError = null;
        draw(<TopicMenu action="delete" />, scheme);
        await userEvent.click(screen.getByRole('menuitem', { name: /Delete topic/ }));
        expect(await screen.findByText(/does not erase/)).toBeTruthy();
        expect(screen.getByText(/Their offsets are not erased/)).toBeTruthy();
        expect(screen.getByText(/About 4 records/)).toBeTruthy();
        await shot(`${prefix}-delete-preview`);
        cleanup();

        api.planned = null;
        api.deleteError = new ApiError(409, { detail: 'The cluster manages internal topics.' });
        draw(<TopicMenu action="delete" />, scheme);
        await userEvent.click(screen.getByRole('menuitem', { name: /Delete topic/ }));
        expect((await screen.findAllByText('The cluster manages internal topics.')).length).toBeGreaterThan(0);
        expect(screen.getByRole('button', { name: 'Delete topic' })).toBeDisabled();
        await shot(`${prefix}-internal`);
        cleanup();

        api.partitionsError = new ApiError(400, {
          detail: "A topic's partitions can only be increased: it has 6, not 2.",
        });
        draw(<TopicMenu action="partitions" />, scheme);
        await userEvent.click(screen.getByRole('menuitem', { name: /Add partitions/ }));
        expect(await screen.findByText(/A key can land on a new partition afterwards/)).toBeTruthy();
        expect(screen.getByRole('button', { name: 'Add partitions' })).toBeDisabled();
        await shot(`${prefix}-shrink`);
        cleanup();

        gate.kind = 'blocked';
        gate.reason = 'You do not have the destination:delete permission.';
        draw(<TopicMenu action="delete" />, scheme);
        expect(screen.getByText('You do not have the destination:delete permission.')).toBeTruthy();
        await shot(`${prefix}-no-permission`);
        cleanup();
        gate.kind = 'allowed';

        draw(
          <CreateTopicForm
            clusterId="k1"
            opened
            onClose={() => undefined}
            brokersReached={3}
            permissionReason={null}
          />,
          scheme,
        );
        await userEvent.click(screen.getByRole('button', { name: 'Advanced configuration' }));
        const create = screen.getByRole('button', { name: 'Create topic' });
        const box = create.getBoundingClientRect();
        expect(box.bottom).toBeLessThanOrEqual(document.documentElement.clientHeight + 1);
        expect(box.top).toBeGreaterThanOrEqual(0);
        expect(screen.getByRole('button', { name: 'Preview topic' })).toHaveAttribute('data-variant', 'default');
        expect(screen.getAllByLabelText('cleanup.policy').length).toBeGreaterThan(0);
        await shot(`${prefix}-create`);
        cleanup();

        draw(
          <GroupDelete
            clusterId="k1"
            groupId="billing"
            group={detail({ members: [{ id: 'm1', clientId: 'c', host: 'h', assignments: [] }] })}
          />,
          scheme,
        );
        expect(screen.getByRole('button', { name: 'Delete group' })).toBeDisabled();
        await userEvent.click(screen.getByRole('button', { name: 'Why deleting this group is unavailable' }));
        expect(await screen.findByText(/does not remove them/)).toBeTruthy();
        await shot(`${prefix}-members`);
        cleanup();

        query.items = [];
        draw(<GroupsView />, scheme);
        expect(screen.getByText('No consumer groups yet')).toBeTruthy();
        await shot(`${prefix}-groups-empty`);
        cleanup();

        search.q = 'missing';
        draw(<GroupsView />, scheme);
        expect(screen.getByText('No group matches this filter')).toBeTruthy();
        await shot(`${prefix}-groups-filtered`);
        cleanup();
        search.q = undefined;

        query.pending = true;
        draw(<GroupsView />, scheme);
        expect(screen.getByText('Loading Consumer groups')).toBeTruthy();
        await shot(`${prefix}-groups-loading`);
        cleanup();
        query.pending = false;

        query.error = true;
        draw(<GroupsView />, scheme);
        expect(screen.getByText('The cluster could not be reached.')).toBeTruthy();
        await shot(`${prefix}-groups-error`);
        cleanup();
        query.error = false;
      }
    }
  }, 120_000);
});
