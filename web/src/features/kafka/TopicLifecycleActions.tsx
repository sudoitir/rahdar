import { useEffect, useRef, useState } from 'react';
import { Button, NumberInput, Stack, Text } from '@mantine/core';
import { IconArrowsVertical, IconTrash } from '@tabler/icons-react';

import type { ActionProps, DestinationTarget, HostedDialogProps } from '../../kernel/actions/types.ts';
import { useResourceGate } from '../../kernel/auth/useResourceGate.ts';
import { ApiError } from '../../kernel/api/request.ts';
import { ActionMenuItem } from '../../ui/ActionMenuItem.tsx';
import { ConfirmDialog } from '../../ui/ConfirmDialog.tsx';
import {
  useDeleteTopic,
  useIncreasePartitions,
  usePreviewDeleteTopic,
  usePreviewIncreasePartitions,
  type TopicPreview,
} from './api.ts';
import { KAFKA_TOPIC } from './words.ts';

function message(error: unknown) {
  return error instanceof ApiError ? error.message : 'The cluster could not be reached.';
}

/** The preview in words: who still has offsets, that those offsets stay, and the record count. */
export function PreviewWords({ planned }: Readonly<{ planned: TopicPreview }>) {
  const count =
    planned.accuracy === 'unavailable' || planned.estimate == null
      ? 'The record count is unavailable.'
      : `About ${planned.estimate} records (${planned.accuracy}).`;
  return (
    <>
      <Text size="sm">{planned.note}</Text>
      <Text size="sm">
        {planned.groups.length === 0
          ? 'No group has offsets on this topic.'
          : `Groups that still have offsets: ${planned.groups.join(', ')}. Their offsets are not erased.`}
      </Text>
      <Text size="sm">{count}</Text>
      {planned.overCap ? (
        <Text size="sm">
          That estimate is over the safety cap of {planned.cap}. Holding delete overrides the cap, and the audit records
          the override.
        </Text>
      ) : null}
    </>
  );
}

/** Delete a topic after a preview that names who still has offsets. The dialog outlives the row menu. */
export function DeleteTopic({ clusterId, target, host }: Readonly<ActionProps<DestinationTarget>>) {
  const gate = useResourceGate({
    clusterId,
    noun: 'destination',
    permission: 'destination:delete',
    label: 'Delete topic',
    resource: target.snapshot,
  });
  if (target.kind !== KAFKA_TOPIC || gate.hidden) return null;
  return (
    <ActionMenuItem
      label="Delete topic"
      icon={<IconTrash size="1rem" aria-hidden />}
      verdict={gate.verdict}
      onExplain={(verdict) => host.explain(verdict, 'deleting this topic')}
      onSelect={() => host.open(DeleteTopicDialog, { clusterId, topic: target.name })}
    />
  );
}

export function DeleteTopicDialog({
  clusterId,
  topic,
  opened,
  onClose,
}: HostedDialogProps & Readonly<{ clusterId: string; topic: string }>) {
  const preview = usePreviewDeleteTopic(clusterId);
  const remove = useDeleteTopic(clusterId);
  const [planned, setPlanned] = useState<TopicPreview | null>(null);
  const run = useRef(preview.mutate);
  run.current = preview.mutate;
  useEffect(() => {
    if (opened) run.current(topic, { onSuccess: setPlanned });
  }, [opened, topic]);
  const blocked = preview.error != null ? message(preview.error) : undefined;
  let confirmLabel = 'Delete topic';
  if (planned?.overCap) {
    confirmLabel = `Delete ${topic} over the safety cap`;
  } else if (planned) {
    const count = planned.estimate ?? 'an unknown number of';
    confirmLabel = `Delete ${topic}, about ${count} records`;
  }
  return (
    <ConfirmDialog
      opened={opened}
      tone="danger"
      title={`Delete ${topic}`}
      confirmLabel={confirmLabel}
      pending={preview.isPending || remove.isPending}
      blocked={blocked}
      onClose={onClose}
      onConfirm={() => {
        if (!planned) return;
        remove.mutate(
          {
            topic,
            etag: planned.etag,
            fingerprint: planned.fingerprint,
            override: Boolean(planned.overCap),
          },
          { onSuccess: () => onClose() },
        );
      }}
      consequence={
        <Stack gap="xs">
          {preview.isPending ? <Text size="sm">Loading the preview.</Text> : null}
          {planned ? <PreviewWords planned={planned} /> : null}
          {remove.error ? <Text size="sm">{message(remove.error)}</Text> : null}
        </Stack>
      }
    />
  );
}

/** Raise the partition count. A lower count leaves confirm disabled and names both counts. */
export function IncreasePartitions({ clusterId, target, host }: Readonly<ActionProps<DestinationTarget>>) {
  const gate = useResourceGate({
    clusterId,
    noun: 'destination',
    permission: 'destination:update',
    label: 'Add partitions',
    resource: target.snapshot,
  });
  if (target.kind !== KAFKA_TOPIC || gate.hidden) return null;
  return (
    <ActionMenuItem
      label="Add partitions"
      icon={<IconArrowsVertical size="1rem" aria-hidden />}
      verdict={gate.verdict}
      onExplain={(verdict) => host.explain(verdict, 'adding partitions')}
      onSelect={() => host.open(IncreasePartitionsDialog, { clusterId, topic: target.name })}
    />
  );
}

export function IncreasePartitionsDialog({
  clusterId,
  topic,
  opened,
  onClose,
}: HostedDialogProps & Readonly<{ clusterId: string; topic: string }>) {
  const preview = usePreviewIncreasePartitions(clusterId);
  const apply = useIncreasePartitions(clusterId);
  const [count, setCount] = useState<number | string>('');
  const [planned, setPlanned] = useState<TopicPreview | null>(null);
  const live = useRef(count);
  live.current = count;
  const asked = Number(count);
  const previewed = planned?.partitions != null && planned.partitions === asked;
  let blocked: string | undefined;
  if (preview.error != null) {
    blocked = message(preview.error);
  } else if (!previewed) {
    blocked = 'Preview the new partition count first.';
  }
  return (
    <ConfirmDialog
      opened={opened}
      tone="danger"
      title={`Add partitions to ${topic}`}
      confirmLabel="Add partitions"
      pending={preview.isPending || apply.isPending}
      blocked={blocked}
      onClose={onClose}
      onConfirm={() => {
        if (!planned || planned.partitions == null) return;
        apply.mutate({ topic, partitions: planned.partitions, etag: planned.etag }, { onSuccess: () => onClose() });
      }}
      consequence={
        <Stack gap="sm">
          <Text size="sm">
            Records already written stay on their partitions. A key can land on a new partition afterwards.
          </Text>
          <NumberInput
            label="New partition count"
            value={count}
            onChange={(value) => {
              setCount(value);
              setPlanned(null);
            }}
          />
          <Button
            type="button"
            variant="default"
            disabled={!asked}
            onClick={() => {
              const next = Number(live.current);
              preview.mutate(
                { topic, partitions: next },
                {
                  onSuccess: (value) => {
                    if (Number(live.current) !== next) return;
                    setPlanned(value);
                  },
                },
              );
            }}
          >
            Preview partitions
          </Button>
          {planned ? <Text size="sm">{planned.note}</Text> : null}
        </Stack>
      }
    />
  );
}
