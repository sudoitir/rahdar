import { useState } from 'react';
import { NumberInput, Select, Stack, Text, TextInput } from '@mantine/core';
import { useForm } from '@mantine/form';

import { ApiError } from '../../kernel/api/request.ts';
import { ConfirmDialog } from '../../ui/ConfirmDialog.tsx';
import { focusFirstInvalid } from '../../ui/formErrors.ts';
import { TOPIC_SETTINGS, useCreateTopic, usePreviewCreateTopic, type TopicPreview } from './api.ts';

const FIELDS = [
  { name: 'name', label: 'Name' },
  { name: 'partitions', label: 'Partition count' },
  { name: 'replicationFactor', label: 'Replication factor' },
  { name: 'cleanup.policy', label: 'cleanup.policy' },
  { name: 'retention.ms', label: 'retention.ms' },
  { name: 'retention.bytes', label: 'retention.bytes' },
  { name: 'max.message.bytes', label: 'max.message.bytes' },
  { name: 'min.insync.replicas', label: 'min.insync.replicas' },
] as const;

interface FormValues {
  name: string;
  partitions: number | string;
  replicationFactor: number | string;
  'cleanup.policy': string;
  'retention.ms': string;
  'retention.bytes': string;
  'max.message.bytes': string;
  'min.insync.replicas': string;
}

/** New destination on a Kafka cluster: the five declared settings, then a hold that is not a destruction. */
export function CreateTopicForm({
  clusterId,
  opened,
  onClose,
  brokersReached = null,
  permissionReason = null,
}: Readonly<{
  clusterId: string;
  opened: boolean;
  onClose: () => void;
  brokersReached?: number | null;
  permissionReason?: string | null;
}>) {
  const preview = usePreviewCreateTopic(clusterId);
  const create = useCreateTopic(clusterId);
  const [planned, setPlanned] = useState<TopicPreview | null>(null);
  const form = useForm<FormValues>({
    initialValues: {
      name: '',
      partitions: '',
      replicationFactor: '',
      'cleanup.policy': 'delete',
      'retention.ms': '',
      'retention.bytes': '',
      'max.message.bytes': '',
      'min.insync.replicas': '',
    },
    validateInputOnBlur: true,
    validate: {
      name: (value) => (value.trim() ? null : 'Enter the topic name.'),
      partitions: (value) => (Number(value) >= 1 ? null : 'Enter a partition count of at least 1.'),
      replicationFactor: (value) => (Number(value) >= 1 ? null : 'Enter a replication factor of at least 1.'),
    },
  });

  const settings = () => {
    const values = form.getValues();
    const out: Record<string, string> = {};
    for (const key of TOPIC_SETTINGS) {
      const value = values[key].trim();
      if (value) out[key] = value;
    }
    return out;
  };

  const body = () => ({
    name: form.getValues().name.trim(),
    partitions: Number(form.getValues().partitions),
    replicationFactor: Number(form.getValues().replicationFactor),
    settings: settings(),
  });

  const errorText = (error: unknown) =>
    error instanceof ApiError ? error.message : 'The cluster could not be reached.';

  return (
    <ConfirmDialog
      opened={opened}
      hold
      title="Create topic"
      confirmLabel="Create topic"
      pending={preview.isPending || create.isPending}
      blocked={permissionReason ?? undefined}
      onClose={() => {
        setPlanned(null);
        form.reset();
        onClose();
      }}
      onConfirm={() => {
        if (!planned) return;
        create.mutate(
          { ...body(), etag: planned.etag },
          {
            onSuccess: () => {
              setPlanned(null);
              form.reset();
              onClose();
            },
          },
        );
      }}
      consequence={
        <Stack gap="sm">
          <Text size="sm">
            {brokersReached == null
              ? 'The partition count and replication factor are required.'
              : `This cluster reached ${brokersReached} brokers. Nothing is created until you hold Create topic.`}
          </Text>
          {FIELDS.map((field) =>
            field.name === 'cleanup.policy' ? (
              <Select
                key={field.name}
                label={field.label}
                data={['delete', 'compact', 'compact,delete']}
                {...form.getInputProps(field.name)}
              />
            ) : field.name === 'name' ? (
              <TextInput key={field.name} label={field.label} {...form.getInputProps(field.name)} />
            ) : (
              <NumberInput key={field.name} label={field.label} {...form.getInputProps(field.name)} />
            ),
          )}
          {planned ? (
            <Text size="sm">
              {planned.note} {planned.name} will have {planned.partitions ?? body().partitions} partitions and
              replication factor {planned.replicationFactor ?? body().replicationFactor}.
            </Text>
          ) : null}
          {preview.error ? <Text size="sm">{errorText(preview.error)}</Text> : null}
          {create.error ? <Text size="sm">{errorText(create.error)}</Text> : null}
          <button
            type="button"
            onClick={() => {
              const invalid = form.validate();
              if (invalid.hasErrors) {
                focusFirstInvalid(form.getInputNode)(invalid.errors);
                return;
              }
              preview.mutate(body(), { onSuccess: setPlanned });
            }}
          >
            Preview topic
          </button>
        </Stack>
      }
    />
  );
}
