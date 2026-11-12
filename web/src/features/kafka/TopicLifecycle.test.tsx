import { Menu } from '@mantine/core';
import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { useActionHost } from '../../kernel/actions/hostContext.ts';
import { ApiError } from '../../kernel/api/request.ts';
import { renderWithProviders } from '../../test/render.tsx';
import { GroupDelete } from './GroupDelete.tsx';
import { DeleteTopic, IncreasePartitions } from './TopicLifecycleActions.tsx';
import type { GroupDetail, TopicPreview } from './api.ts';

const gate = vi.hoisted(() => ({ kind: 'allowed' as 'allowed' | 'blocked', reason: 'You do not have permission.' }));
const auth = vi.hoisted(() => ({ permitted: true, loading: false }));
const api = vi.hoisted(() => ({
  deletePending: false,
  deleteError: null as unknown,
  partitionsPending: false,
  partitionsError: null as unknown,
  groupPending: false,
  groupError: null as unknown,
  removedError: null as unknown,
  planned: null as TopicPreview | null,
}));

vi.mock('../../kernel/auth/useResourceGate.ts', () => ({
  useResourceGate: () => ({
    hidden: false,
    verdict: gate.kind === 'blocked' ? { kind: 'blocked', reason: gate.reason } : { kind: 'allowed', uncertain: false },
  }),
}));

vi.mock('../../kernel/auth/useCan.ts', () => ({
  useCan: () => ({
    can: () => auth.permitted,
    loading: auth.loading,
    canAnywhere: () => true,
    canOn: () => auth.permitted,
  }),
}));

function mutation(pending: () => boolean, error: () => unknown) {
  return {
    mutate: (_input: unknown, opts?: { onSuccess?: (value: TopicPreview) => void }) => {
      if (api.planned) opts?.onSuccess?.(api.planned);
    },
    isPending: pending(),
    error: error(),
  };
}

vi.mock('./api.ts', () => ({
  usePreviewDeleteTopic: () => mutation(() => api.deletePending, () => api.deleteError),
  useDeleteTopic: () => mutation(() => false, () => api.removedError),
  usePreviewIncreasePartitions: () => mutation(() => api.partitionsPending, () => api.partitionsError),
  useIncreasePartitions: () => mutation(() => false, () => null),
  usePreviewDeleteGroup: () => mutation(() => api.groupPending, () => api.groupError),
  useDeleteGroup: () => mutation(() => false, () => api.removedError),
}));

const target = { id: 'kafka.topic/orders', kind: 'kafka.topic', name: 'orders' };

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

function scheme(value: 'light' | 'dark') {
  document.documentElement.setAttribute('data-mantine-color-scheme', value);
}

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

async function open(name: RegExp) {
  const user = userEvent.setup();
  await user.click(screen.getByRole('menuitem', { name }));
  return screen.findByRole('dialog');
}

beforeEach(() => {
  gate.kind = 'allowed';
  auth.permitted = true;
  auth.loading = false;
  api.deletePending = false;
  api.deleteError = null;
  api.partitionsPending = false;
  api.partitionsError = null;
  api.groupPending = false;
  api.groupError = null;
  api.removedError = null;
  api.planned = preview();
});

describe('topic delete and partition increase', () => {
  it.each(['light', 'dark'] as const)('states the preview in words in %s', async (color) => {
    scheme(color);
    renderWithProviders(<TopicMenu action="delete" />);
    await open(/Delete topic/);
    expect(await screen.findByText(/does not erase/)).toBeInTheDocument();
    expect(screen.getByText(/Groups that still have offsets: billing/)).toBeInTheDocument();
    expect(screen.getByText(/Their offsets are not erased/)).toBeInTheDocument();
    expect(screen.getByText(/About 4 records \(estimate\)/)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /Delete orders, about 4 records/ })).toBeInTheDocument();
  });

  it.each(['light', 'dark'] as const)(
    'shows loading, an empty group list, an error, and an unavailable count in %s',
    async (color) => {
      scheme(color);
      api.deletePending = true;
      api.planned = preview({ groups: [], estimate: null, accuracy: 'unavailable' });
      api.deleteError = new ApiError(503, { detail: 'The cluster could not be reached.' });
      renderWithProviders(<TopicMenu action="delete" />);
      await open(/Delete topic/);
      expect(await screen.findByText('Loading the preview.')).toBeInTheDocument();
      expect(screen.getByText('No group has offsets on this topic.')).toBeInTheDocument();
      expect(screen.getByText('The record count is unavailable.')).toBeInTheDocument();
      expect(screen.getAllByText('The cluster could not be reached.').length).toBeGreaterThan(0);
    },
  );

  it.each(['light', 'dark'] as const)('keeps a missing permission visible in %s', (color) => {
    scheme(color);
    gate.kind = 'blocked';
    gate.reason = 'You do not have the destination:delete permission.';
    renderWithProviders(<TopicMenu action="delete" />);
    expect(screen.getByText('You do not have the destination:delete permission.')).toBeInTheDocument();
    expect(screen.getByRole('menuitem', { name: /Delete topic/ })).toHaveAttribute('aria-disabled', 'true');
  });

  it('lets the operator override the safety cap, and says the audit will record it', async () => {
    scheme('light');
    api.planned = preview({ estimate: 5000, overCap: true, cap: 1000 });
    renderWithProviders(<TopicMenu action="delete" />);
    await open(/Delete topic/);
    expect(await screen.findByText(/over the safety cap of 1000/)).toBeInTheDocument();
    expect(screen.getByText(/audit records the override/)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Delete orders over the safety cap' })).toBeEnabled();
  });

  it.each(['light', 'dark'] as const)('names a shrink and keeps the hold disabled in %s', async (color) => {
    scheme(color);
    api.planned = null;
    api.partitionsError = new ApiError(400, {
      detail: "A topic's partitions can only be increased: it has 6, not 2.",
    });
    renderWithProviders(<TopicMenu action="partitions" />);
    await open(/Add partitions/);
    expect(await screen.findByText(/Records already written stay on their partitions/)).toBeInTheDocument();
    expect(screen.getByText(/A key can land on a new partition afterwards/)).toBeInTheDocument();
    expect(screen.getAllByText(/it has 6, not 2/).length).toBeGreaterThan(0);
    expect(screen.getByRole('button', { name: 'Add partitions' })).toBeDisabled();
  });

  it.each(['light', 'dark'] as const)('keeps an internal topic visible and says why in %s', async (color) => {
    scheme(color);
    api.planned = null;
    api.deleteError = new ApiError(409, { detail: 'The cluster manages internal topics.' });
    renderWithProviders(<TopicMenu action="delete" />);
    expect(screen.getByRole('menuitem', { name: /Delete topic/ })).toBeInTheDocument();
    await open(/Delete topic/);
    expect((await screen.findAllByText('The cluster manages internal topics.')).length).toBeGreaterThan(0);
    expect(screen.getByRole('button', { name: 'Delete topic' })).toBeDisabled();
  });

  it('shows a stale apply without changing the hold into a second action', async () => {
    scheme('dark');
    api.removedError = new ApiError(409, {
      detail: 'The groups or the record estimate changed since the preview. Preview again.',
    });
    renderWithProviders(<TopicMenu action="delete" />);
    await open(/Delete topic/);
    expect(await screen.findByText(/Preview again/)).toBeInTheDocument();
    await waitFor(() => expect(screen.getAllByRole('button', { name: /Delete orders/ })).toHaveLength(1));
  });
});

describe('group delete', () => {
  async function why() {
    const user = userEvent.setup();
    await user.click(screen.getByRole('button', { name: 'Why deleting this group is unavailable' }));
  }

  it.each(['light', 'dark'] as const)(
    'says why members, a rebalance, streams, or a missing permission block it in %s',
    async (color) => {
      scheme(color);
      const { rerender } = renderWithProviders(
        <GroupDelete
          clusterId="k1"
          groupId="billing"
          group={detail({ members: [{ id: 'm1', clientId: 'c', host: 'h', assignments: [] }] })}
        />,
      );
      expect(screen.getByRole('button', { name: 'Delete group' })).toBeDisabled();
      await why();
      expect(await screen.findByText(/does not remove them/, {}, { timeout: 1000 })).toBeInTheDocument();

      rerender(<GroupDelete clusterId="k1" groupId="billing" group={detail({ state: 'PREPARING_REBALANCE' })} />);
      await why();
      expect(screen.getByText('This group is rebalancing.')).toBeInTheDocument();

      rerender(<GroupDelete clusterId="k1" groupId="billing" group={detail({ type: 'streams' })} />);
      await why();
      expect(screen.getByText('Streams owns this group.')).toBeInTheDocument();

      auth.permitted = false;
      rerender(<GroupDelete clusterId="k1" groupId="billing" group={detail()} />);
      expect(screen.getByRole('button', { name: 'Delete group' })).toBeDisabled();
      await why();
      expect(screen.getByText(/do not have the "Delete group" permission/)).toBeInTheDocument();
    },
  );

  it.each(['light', 'dark'] as const)(
    'shows loading, an error, a stale refusal, and an unavailable group in %s',
    async (color) => {
      scheme(color);
      api.groupPending = true;
      api.groupError = new ApiError(409, { detail: 'The cluster does not support this.' });
      api.removedError = new ApiError(409, { detail: 'Preview again.' });
      const user = userEvent.setup();
      const { rerender } = renderWithProviders(<GroupDelete clusterId="k1" groupId="billing" group={undefined} />);
      await why();
      expect(await screen.findByText('Loading the group.')).toBeInTheDocument();
      expect(screen.getByRole('button', { name: 'Delete group' })).toBeDisabled();

      rerender(<GroupDelete clusterId="k1" groupId="billing" group={detail()} unavailable />);
      await why();
      expect(screen.getByText('The group could not be loaded.')).toBeInTheDocument();

      rerender(<GroupDelete clusterId="k1" groupId="billing" group={detail()} />);
      await user.click(screen.getByRole('button', { name: 'Delete group' }));
      expect(await screen.findByRole('dialog')).toBeInTheDocument();
      expect(screen.getByText('Loading the preview.')).toBeInTheDocument();
      expect(screen.getAllByText('The cluster does not support this.').length).toBeGreaterThan(0);
      expect(screen.getAllByText('Preview again.').length).toBeGreaterThan(0);
    },
  );
});
