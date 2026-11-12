import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { holdButton } from '../../test/hold.ts';
import { renderWithProviders } from '../../test/render.tsx';
import { CreateTopicForm } from './CreateTopicForm.tsx';
import type { TopicCreateBody, TopicPreview } from './api.ts';

const calls = vi.hoisted(() => ({
  preview: [] as TopicCreateBody[],
  create: [] as TopicCreateBody[],
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
    error: null,
  }),
  useCreateTopic: () => ({
    mutate: (body: TopicCreateBody) => {
      calls.create.push(body);
    },
    isPending: false,
    error: null,
  }),
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
  const field = screen.getAllByLabelText(label)[0];
  if (!field) throw new Error(`missing ${label}`);
  await user.clear(field);
  await user.type(field, value);
}

describe('creating a topic', () => {
  beforeEach(() => {
    calls.preview = [];
    calls.create = [];
  });

  it.each(['light', 'dark'] as const)('asks only for the name, the counts and the five settings in %s', (scheme) => {
    renderForm(scheme);
    for (const label of [
      'Name',
      'Partition count',
      'Replication factor',
      'cleanup.policy',
      'retention.ms',
      'retention.bytes',
      'max.message.bytes',
      'min.insync.replicas',
    ]) {
      expect(screen.getAllByLabelText(label).length).toBeGreaterThan(0);
    }
    expect(screen.getByRole('button', { name: 'Create topic' })).toBeInTheDocument();
    expect(screen.getByText(/This cluster reached 3 brokers/)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Preview topic' })).toBeInTheDocument();
  });

  it('keeps create visible and says why the permission is missing', () => {
    renderForm('light', 'You do not have the destination:create permission.');
    expect(screen.getByRole('button', { name: 'Create topic' })).toBeDisabled();
    expect(screen.getByText('You do not have the destination:create permission.')).toBeInTheDocument();
  });

  it('keeps the hold disabled until a preview exists, and does not say it confirmed', async () => {
    renderForm('light');
    const create = screen.getByRole('button', { name: 'Create topic' });
    expect(create).toBeDisabled();
    expect(screen.getByText('Preview the topic first.')).toBeInTheDocument();
    await holdButton(create);
    expect(calls.create).toEqual([]);
    expect(screen.queryByText('Confirmed.')).not.toBeInTheDocument();
  });

  it('sends a numeric setting as text, and creates only the previewed topic', async () => {
    const user = userEvent.setup();
    renderForm('light');
    await fill('Name', 'orders');
    await fill('Partition count', '6');
    await fill('Replication factor', '3');
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
    await holdButton(screen.getByRole('button', { name: 'Create topic' }));
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
});
