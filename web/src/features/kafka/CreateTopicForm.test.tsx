import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { renderWithProviders } from '../../test/render.tsx';
import { CreateTopicForm } from './CreateTopicForm.tsx';
import type { TopicCreateBody, TopicPreview } from './api.ts';

const calls = vi.hoisted(() => ({
  preview: [] as TopicCreateBody[],
  create: [] as TopicCreateBody[],
  held: false,
}));

vi.mock('./api.ts', () => ({
  TOPIC_SETTINGS: ['cleanup.policy', 'retention.ms', 'retention.bytes', 'max.message.bytes', 'min.insync.replicas'],
  usePreviewCreateTopic: () => ({
    mutate: (body: TopicCreateBody, opts?: { onSuccess?: (value: TopicPreview) => void }) => {
      calls.preview.push(body);
      opts?.onSuccess?.({
        name: body.name,
        etag: `etag-${calls.preview.length}`,
        fingerprint: 'fp',
        note: 'Nothing has been created.',
        groups: [],
        estimate: null,
        accuracy: 'estimate',
        partitions: body.partitions,
        replicationFactor: body.replicationFactor,
        currentPartitions: null,
        cap: 1000,
        overCap: false,
      });
    },
    isPending: false,
    isError: false,
    error: null,
    reset: () => undefined,
  }),
  useCreateTopic: () => ({
    mutate: (body: TopicCreateBody, opts?: { onSuccess?: () => void; onError?: (error: unknown) => void }) => {
      calls.create.push(body);
      if (calls.held) {
        opts?.onError?.({
          name: 'OperationHeldError',
          message: 'Sent for approval: Create topic "orders"',
          heldOperation: { id: 'h-1', summary: 'Create topic "orders"' },
        });
        return;
      }
      opts?.onSuccess?.();
    },
    isPending: false,
    isError: false,
    error: null,
    reset: () => undefined,
  }),
  useKafkaBrokers: () => ({ data: undefined, isPending: false, isError: false, error: null }),
}));

function renderForm(scheme: 'light' | 'dark', permissionReason: string | null = null) {
  document.documentElement.setAttribute('data-mantine-color-scheme', scheme);
  return renderWithProviders(
    <CreateTopicForm
      clusterId="k1"
      opened
      onClose={() => undefined}
      brokersReached={3}
      permissionReason={permissionReason}
    />,
  );
}

async function fill(label: string, value: string) {
  const user = userEvent.setup();
  const field = screen.getAllByLabelText(label, { exact: false })[0];
  if (!field) throw new Error(`missing ${label}`);
  await user.clear(field);
  await user.type(field, value);
}

describe('creating a topic', () => {
  beforeEach(() => {
    calls.preview = [];
    calls.create = [];
    calls.held = false;
  });

  it.each(['light', 'dark'] as const)(
    'asks only for the name, the counts and the five settings in %s',
    async (scheme) => {
      const user = userEvent.setup();
      renderForm(scheme);
      for (const label of ['Name', 'Partition count', 'Replication factor']) {
        expect(screen.getAllByLabelText(label, { exact: false }).length).toBeGreaterThan(0);
      }
      await user.click(screen.getByRole('button', { name: 'Advanced configuration' }));
      for (const label of [
        'cleanup.policy',
        'retention.ms',
        'retention.bytes',
        'max.message.bytes',
        'min.insync.replicas',
      ]) {
        expect(screen.getAllByLabelText(label, { exact: false }).length).toBeGreaterThan(0);
      }
      expect(screen.getByRole('button', { name: 'Create topic' })).toBeInTheDocument();
      expect(screen.getByText(/This cluster reached 3 brokers/)).toBeInTheDocument();
      const preview = screen.getByRole('button', { name: 'Preview topic' });
      expect(preview).toHaveAttribute('type', 'submit');
      expect(preview).toHaveAttribute('data-variant', 'default');
    },
  );

  it('keeps create visible and says why the permission is missing', () => {
    renderForm('light', 'You do not have the destination:create permission.');
    expect(screen.getByRole('button', { name: 'Create topic' })).toBeDisabled();
    expect(screen.getByText('You do not have the destination:create permission.')).toBeInTheDocument();
  });

  it('keeps create disabled until a preview exists, and a click does not create', async () => {
    const user = userEvent.setup();
    renderForm('light');
    const create = screen.getByRole('button', { name: 'Create topic' });
    expect(create).toBeDisabled();
    expect(screen.getByText('Preview the topic first.')).toBeInTheDocument();
    await user.click(create);
    expect(calls.create).toEqual([]);
    expect(screen.queryByText('Confirmed.')).not.toBeInTheDocument();
    expect(screen.queryByText('The cluster could not be reached.')).not.toBeInTheDocument();
  });

  it('does not preview a topic with no name', async () => {
    const user = userEvent.setup();
    renderForm('light');
    await user.click(screen.getByRole('button', { name: 'Preview topic' }));
    expect(calls.preview).toEqual([]);
    expect(screen.getByText('Enter the topic name.')).toBeInTheDocument();
  });

  it('sends a numeric setting as text, and creates the previewed topic on a click', async () => {
    const user = userEvent.setup();
    renderForm('light');
    await fill('Name', 'orders');
    await fill('Partition count', '6');
    await fill('Replication factor', '3');
    await user.click(screen.getByRole('button', { name: 'Advanced configuration' }));
    await fill('retention.ms', '86400000');
    await user.click(screen.getByRole('button', { name: 'Preview topic' }));

    expect(calls.preview).toHaveLength(1);
    expect(calls.preview[0]?.settings['retention.ms']).toBe('86400000');
    expect(screen.getByRole('button', { name: 'Create topic' })).toBeEnabled();
    expect(screen.getByText(/Nothing has been created/)).toBeInTheDocument();
    expect(screen.getByText(/6 partitions/)).toBeInTheDocument();

    await fill('Partition count', '9');
    expect(screen.getByRole('button', { name: 'Create topic' })).toBeDisabled();
    expect(screen.getByText('Preview the topic first.')).toBeInTheDocument();
    expect(calls.create).toEqual([]);

    await user.click(screen.getByRole('button', { name: 'Preview topic' }));
    await user.click(screen.getByRole('button', { name: 'Create topic' }));
    expect(calls.create).toEqual([
      {
        name: 'orders',
        partitions: 9,
        replicationFactor: 3,
        settings: { 'cleanup.policy': 'delete', 'retention.ms': '86400000' },
        etag: 'etag-2',
      },
    ]);
  });

  it('refuses a replication factor above the brokers this connection reached', async () => {
    const user = userEvent.setup();
    renderForm('light');
    await fill('Name', 'orders');
    await fill('Partition count', '1');
    await fill('Replication factor', '4');
    await user.click(screen.getByRole('button', { name: 'Preview topic' }));
    expect(calls.preview).toEqual([]);
    expect(screen.getByText(/from 1 to 3/)).toBeInTheDocument();
  });

  it('does not call a held create a failure to reach the cluster', async () => {
    calls.held = true;
    const user = userEvent.setup();
    renderForm('light');
    await fill('Name', 'orders');
    await fill('Partition count', '6');
    await fill('Replication factor', '3');
    await user.click(screen.getByRole('button', { name: 'Preview topic' }));
    await user.click(screen.getByRole('button', { name: 'Create topic' }));
    expect(calls.create).toHaveLength(1);
    expect(screen.queryByText('The cluster could not be reached.')).not.toBeInTheDocument();
  });
});
