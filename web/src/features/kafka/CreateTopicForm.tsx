import { useRef, useState } from 'react';
import { Button, Collapse, NumberInput, Select, Stack, Text, TextInput } from '@mantine/core';
import { useForm } from '@mantine/form';

import { ConfirmDialog } from '../../ui/ConfirmDialog.tsx';
import { ErrorState } from '../../ui/ErrorState.tsx';
import { FieldRow } from '../../ui/FieldRow.tsx';
import { focusFirstInvalid, serverFieldErrors } from '../../ui/formErrors.ts';
import { heldOf } from '../../ui/held.ts';
import { notify, type ActionVerb } from '../../ui/notify.ts';
import {
  TOPIC_SETTINGS,
  useCreateTopic,
  useKafkaBrokers,
  usePreviewCreateTopic,
  type TopicCreateBody,
  type TopicPreview,
} from './api.ts';

const CREATE: ActionVerb = { verb: 'Create', past: 'Created', progressive: 'Creating' };

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

const FIELD_NAMES = FIELDS.map((field) => field.name);

const FORM_KEY = {
  'cleanup.policy': 'cleanupPolicy',
  'retention.ms': 'retentionMs',
  'retention.bytes': 'retentionBytes',
  'max.message.bytes': 'maxMessageBytes',
  'min.insync.replicas': 'minInsyncReplicas',
} as const satisfies Record<(typeof TOPIC_SETTINGS)[number], (typeof FIELDS)[number]['name']>;

const SETTINGS = FIELDS.filter(
  (field) => field.name !== 'name' && field.name !== 'partitions' && field.name !== 'replicationFactor',
);

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

function replicationProblem(value: number | string, brokers: number | null): string | null {
  const factor = Number(value);
  // Number('') is 0. A value that is not a number must stay refused: NaN < 1 is false.
  if (Number.isNaN(factor) || factor < 1) return 'Enter a replication factor of at least 1.';
  if (brokers === 0) return 'This connection reached no brokers, so a topic cannot be created yet.';
  if (brokers != null && factor > brokers) {
    return `Enter a replication factor from 1 to ${brokers}, the brokers this connection reached.`;
  }
  return null;
}

/** New topic on a Kafka cluster. Preview first, then create. Creating a topic is not a destruction. */
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
  const listed = useKafkaBrokers(clusterId, opened && brokersReached == null);
  const reached = brokersReached ?? listed.data?.brokers.length ?? null;
  const reachedRef = useRef(reached);
  reachedRef.current = reached;
  const preview = usePreviewCreateTopic(clusterId);
  const create = useCreateTopic(clusterId);
  const [planned, setPlanned] = useState<TopicPreview | null>(null);
  const [draft, setDraft] = useState<TopicCreateBody | null>(null);
  const [advanced, setAdvanced] = useState(false);
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
      replicationFactor: (value) => replicationProblem(value, reachedRef.current),
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

  const close = () => {
    clearPlan();
    setAdvanced(false);
    form.reset();
    preview.reset();
    create.reset();
    onClose();
  };

  const previewTopic = form.onSubmit(() => {
    const sent = body();
    preview.mutate(sent, {
      onSuccess: (value) => {
        if (!sameTopic(sent, live.current)) return;
        setDraft(sent);
        setPlanned(value);
      },
      onError: (error) => form.setErrors(serverFieldErrors(error, FIELD_NAMES)),
    });
  }, focusFirstInvalid(form.getInputNode));

  const runCreate = () => {
    if (!planned || !draft || !sameTopic(draft, live.current)) return;
    const subject = `topic "${draft.name}"`;
    create.mutate(
      { ...draft, etag: planned.etag },
      {
        onSuccess: () => {
          notify.succeeded({ action: CREATE, subject });
          close();
        },
        onError: (error) => {
          if (!heldOf(error)) return;
          notify.settle(error, {
            action: CREATE,
            subject,
            cause: 'The topic was not created.',
            next: 'It waits for a second person.',
            onHeld: close,
          });
        },
      },
    );
  };

  let blocked: string | undefined;
  if (permissionReason) {
    blocked = permissionReason;
  } else if (!planned || !draft || !sameTopic(draft, live.current)) {
    blocked = 'Preview the topic first.';
  }

  const intro =
    reached == null
      ? 'Nothing is created until you preview the topic, then choose Create topic.'
      : `This cluster reached ${reached} brokers. Nothing is created until you preview the topic, then choose Create topic.`;

  return (
    <ConfirmDialog
      opened={opened}
      title="Create topic"
      confirmLabel="Create topic"
      pending={create.isPending}
      blocked={blocked}
      onClose={close}
      onConfirm={runCreate}
      consequence={
        <form noValidate onSubmit={previewTopic}>
          <Stack gap="sm">
            <Text size="sm">{intro}</Text>
            <TextInput label="Name" required data-autofocus {...form.getInputProps('name')} />
            <FieldRow>
              <NumberInput
                label="Partition count"
                required
                allowDecimal={false}
                clampBehavior="none"
                {...form.getInputProps('partitions')}
              />
              <NumberInput
                label="Replication factor"
                required
                allowDecimal={false}
                clampBehavior="none"
                {...form.getInputProps('replicationFactor')}
              />
            </FieldRow>
            <div>
              <Button
                type="button"
                variant="subtle"
                size="xs"
                px={0}
                aria-expanded={advanced}
                onClick={() => setAdvanced((open) => !open)}
              >
                {advanced ? 'Hide advanced configuration' : 'Advanced configuration'}
              </Button>
              <Collapse expanded={advanced}>
                <Stack gap="sm" mt="sm">
                  {SETTINGS.map((field) =>
                    field.control === 'select' ? (
                      <Select
                        key={field.name}
                        label={field.label}
                        data={['delete', 'compact', 'compact,delete']}
                        allowDeselect={false}
                        {...form.getInputProps(field.name)}
                      />
                    ) : (
                      <NumberInput
                        key={field.name}
                        label={field.label}
                        allowDecimal={false}
                        clampBehavior="none"
                        {...form.getInputProps(field.name)}
                      />
                    ),
                  )}
                </Stack>
              </Collapse>
            </div>
            <div>
              <Button type="submit" variant="default" size="xs" loading={preview.isPending}>
                Preview topic
              </Button>
            </div>
            <div aria-live="polite">
              {planned && draft ? (
                <Text size="sm">
                  {planned.note} {planned.name} will have {planned.partitions ?? draft.partitions} partitions and
                  replication factor {planned.replicationFactor ?? draft.replicationFactor}.
                </Text>
              ) : null}
            </div>
            {preview.isError ? <ErrorState variant="inline" error={preview.error} /> : null}
            {create.isError && !heldOf(create.error) ? <ErrorState variant="inline" error={create.error} /> : null}
          </Stack>
        </form>
      }
    />
  );
}
