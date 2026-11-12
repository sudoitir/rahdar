import { useRef, useState } from 'react';
import { NumberInput, Select, Stack, Text, TextInput } from '@mantine/core';
import { useForm } from '@mantine/form';

import { ApiError } from '../../kernel/api/request.ts';
import { ConfirmDialog } from '../../ui/ConfirmDialog.tsx';
import { focusFirstInvalid } from '../../ui/formErrors.ts';
import {
  TOPIC_SETTINGS,
  useCreateTopic,
  usePreviewCreateTopic,
  type TopicCreateBody,
  type TopicPreview,
} from './api.ts';

/**
 * Form paths cannot contain a dot: Mantine treats `retention.ms` as `retention` then `ms` and drops the value.
 * The label is the setting name the operator sees. The field is the path the form stores.
 */
const FIELDS = [
  { name: 'name', label: 'Name', control: 'text' },
  { name: 'partitions', label: 'Partition count', control: 'number' },
  { name: 'replicationFactor', label: 'Replication factor', control: 'number' },
  { name: 'cleanupPolicy', label: 'cleanup.policy', control: 'select' },
  { name: 'retentionMs', label: 'retention.ms', control: 'number' },
  { name: 'retentionBytes', label: 'retention.bytes', control: 'number' },
  { name: 'maxMessageBytes', label: 'max.message.bytes', control: 'number' },
  { name: 'minInsyncReplicas', label: 'min.insync.replicas', control: 'number' },
] as const;

const FORM_KEY = {
  'cleanup.policy': 'cleanupPolicy',
  'retention.ms': 'retentionMs',
  'retention.bytes': 'retentionBytes',
  'max.message.bytes': 'maxMessageBytes',
  'min.insync.replicas': 'minInsyncReplicas',
} as const satisfies Record<(typeof TOPIC_SETTINGS)[number], (typeof FIELDS)[number]['name']>;

type SettingValue = string | number;

interface FormValues {
  name: string;
  partitions: number | string;
  replicationFactor: number | string;
  cleanupPolicy: string;
  retentionMs: SettingValue;
  retentionBytes: SettingValue;
  maxMessageBytes: SettingValue;
  minInsyncReplicas: SettingValue;
}

/** A number from a field and a string the operator typed are the same setting once they are text. */
function settingText(value: SettingValue | null | undefined): string {
  if (typeof value === 'number') return Number.isFinite(value) ? String(value) : '';
  if (typeof value === 'string') return value.trim();
  return '';
}

function sameTopic(left: TopicCreateBody | null, right: TopicCreateBody | null): boolean {
  if (!left || !right) return false;
  if (
    left.name !== right.name ||
    left.partitions !== right.partitions ||
    left.replicationFactor !== right.replicationFactor
  ) {
    return false;
  }
  const keys = new Set([...Object.keys(left.settings), ...Object.keys(right.settings)]);
  for (const key of keys) {
    if (left.settings[key] !== right.settings[key]) return false;
  }
  return true;
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
  const [draft, setDraft] = useState<TopicCreateBody | null>(null);
  const clearPlan = () => {
    setPlanned(null);
    setDraft(null);
  };
  const form = useForm<FormValues>({
    initialValues: {
      name: '',
      partitions: '',
      replicationFactor: '',
      cleanupPolicy: 'delete',
      retentionMs: '',
      retentionBytes: '',
      maxMessageBytes: '',
      minInsyncReplicas: '',
    },
    validateInputOnBlur: true,
    onValuesChange: clearPlan,
    validate: {
      name: (value) => (value.trim() ? null : 'Enter the topic name.'),
      partitions: (value) => (Number(value) >= 1 ? null : 'Enter a partition count of at least 1.'),
      replicationFactor: (value) => (Number(value) >= 1 ? null : 'Enter a replication factor of at least 1.'),
    },
  });

  const settings = () => {
    const values = form.getValues();
    const out: Record<string, string> = {};
    for (const setting of TOPIC_SETTINGS) {
      const value = settingText(values[FORM_KEY[setting]]);
      if (value) out[setting] = value;
    }
    return out;
  };

  const body = (): TopicCreateBody => ({
    name: form.getValues().name.trim(),
    partitions: Number(form.getValues().partitions),
    replicationFactor: Number(form.getValues().replicationFactor),
    settings: settings(),
  });
  const live = useRef(body());
  live.current = body();

  const errorText = (error: unknown) =>
    error instanceof ApiError ? error.message : 'The cluster could not be reached.';

  let blocked: string | undefined;
  if (permissionReason) {
    blocked = permissionReason;
  } else if (!planned || !draft) {
    blocked = 'Preview the topic first.';
  }

  return (
    <ConfirmDialog
      opened={opened}
      hold
      title="Create topic"
      confirmLabel="Create topic"
      pending={preview.isPending || create.isPending}
      blocked={blocked}
      onClose={() => {
        clearPlan();
        form.reset();
        onClose();
      }}
      onConfirm={() => {
        if (!planned || !draft) return;
        create.mutate(
          { ...draft, etag: planned.etag },
          {
            onSuccess: () => {
              clearPlan();
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
          {FIELDS.map((field) => {
            const props = form.getInputProps(field.name);
            if (field.control === 'select') {
              return (
                <Select
                  key={field.name}
                  label={field.label}
                  data={['delete', 'compact', 'compact,delete']}
                  {...props}
                />
              );
            }
            if (field.control === 'text') {
              return <TextInput key={field.name} label={field.label} {...props} />;
            }
            return <NumberInput key={field.name} label={field.label} {...props} />;
          })}
          {planned && draft ? (
            <Text size="sm">
              {planned.note} {planned.name} will have {planned.partitions ?? draft.partitions} partitions and
              replication factor {planned.replicationFactor ?? draft.replicationFactor}.
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
              const sent = body();
              preview.mutate(sent, {
                onSuccess: (value) => {
                  if (!sameTopic(sent, live.current)) return;
                  setDraft(sent);
                  setPlanned(value);
                },
              });
            }}
          >
            Preview topic
          </button>
        </Stack>
      }
    />
  );
}
