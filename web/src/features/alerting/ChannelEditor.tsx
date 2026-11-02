import { useState } from 'react';
import {
  Button,
  Group,
  Modal,
  PasswordInput,
  SegmentedControl,
  Select,
  Stack,
  Switch,
  Text,
  Textarea,
  TextInput,
} from '@mantine/core';
import { useForm, type UseFormReturnType } from '@mantine/form';

import type { ApiError } from '../../kernel/api/request.ts';
import { ErrorState } from '../../ui/ErrorState.tsx';
import { focusFirstInvalid } from '../../ui/formErrors.ts';
import { notify, type ActionVerb } from '../../ui/notify.ts';
import { StatusBadge } from '../../ui/StatusBadge.tsx';
import {
  useCreateNotificationChannel,
  useTestChannelConfig,
  useUpdateNotificationChannel,
  type ChannelTestResultView,
  type NotificationChannelView,
} from './api.ts';
import {
  CHANNEL_KINDS,
  EMPTY_FIELDS,
  KIND_ORDER,
  PAGERDUTY_ENDPOINTS,
  configFromFields,
  fieldsFromConfig,
  generateSigningSecret,
  kindLabel,
  serverField,
  validateField,
  type ChannelFields,
  type ChannelKind,
} from './channelKinds.ts';
import classes from './Alerting.module.css';

const ADD: ActionVerb = { verb: 'Add', past: 'Added', progressive: 'Adding' };
const SAVE: ActionVerb = { verb: 'Save', past: 'Saved', progressive: 'Saving' };

type Field = keyof ChannelFields | 'name' | 'secret';

/** The fields each kind shows, in order; validated on submit in this order too. */
const FIELDS: Record<ChannelKind, Field[]> = {
  SLACK: ['name', 'secret'],
  TEAMS: ['name', 'secret'],
  PAGERDUTY: ['name', 'secret', 'url'],
  EMAIL: ['name', 'host', 'port', 'from', 'to', 'secret'],
  WEBHOOK: ['name', 'url', 'secret'],
};

const kindOf = (value: string | null | undefined): ChannelKind | undefined => KIND_ORDER.find((k) => k === value);

const securityOf = (value: string): ChannelFields['security'] =>
  value === 'TLS' || value === 'NONE' ? value : 'STARTTLS';

/**
 * Create or edit a notification channel (ADR-0105). The kind is chosen once: it is a
 * fact about an existing channel, shown as text, never a disabled select. The secret is
 * write-only — blank on edit, and a blank secret keeps the stored one — and a test can
 * be sent before saving, so a wrong URL is found here rather than at 3 a.m.
 */
export function ChannelEditor({
  opened,
  channel,
  onClose,
}: Readonly<{
  opened: boolean;
  channel: NotificationChannelView | null;
  onClose: () => void;
}>) {
  return (
    <Modal
      opened={opened}
      onClose={onClose}
      title={channel ? `Edit ${channel.name}` : 'Add a notification channel'}
      size="lg"
    >
      {/* Remounted per channel, so the form never shows a previous channel's values. */}
      {opened ? <ChannelForm key={channel?.id ?? 'new'} channel={channel} onClose={onClose} /> : null}
    </Modal>
  );
}

/** Everything the form holds: the kind, the common fields and the per-kind ones, side by side. */
type ChannelValues = ChannelFields & { kind: ChannelKind; name: string; secret: string; enabled: boolean };

/** What the per-kind field groups need from the form. */
type KindFieldsProps = Readonly<{ form: UseFormReturnType<ChannelValues> }>;

/** The SMTP-specific fields of an EMAIL channel. */
function EmailFields({ form }: KindFieldsProps) {
  const { security, port } = form.values;
  return (
    <>
      <div className={classes.form}>
        <TextInput label="SMTP server" {...form.getInputProps('host')} placeholder="smtp.example.com" required />
        <TextInput label="Port" inputMode="numeric" {...form.getInputProps('port')} required />
        <Stack gap={4}>
          <Text size="sm" fw={500} id="smtp-security-label">
            Transport security
          </Text>
          <SegmentedControl
            aria-labelledby="smtp-security-label"
            size="xs"
            {...form.getInputProps('security')}
            onChange={(v) => {
              form.setFieldValue('security', securityOf(v));
              if (v === 'TLS' && port === '587') form.setFieldValue('port', '465');
              if (v === 'STARTTLS' && port === '465') form.setFieldValue('port', '587');
            }}
            data={[
              { value: 'STARTTLS', label: 'STARTTLS' },
              { value: 'TLS', label: 'TLS' },
              { value: 'NONE', label: 'None' },
            ]}
          />
        </Stack>
      </div>
      {security === 'NONE' ? (
        <Text size="xs" className={classes.warning}>
          Without TLS the password and the alert cross the network in clear. STARTTLS, when chosen, is required — a
          server that does not offer it fails the delivery rather than receiving it unencrypted.
        </Text>
      ) : null}
      <div className={classes.form}>
        <TextInput label="From" {...form.getInputProps('from')} placeholder="rahdar@example.com" required />
        <TextInput
          label="Username"
          description="Blank when the server needs no authentication."
          {...form.getInputProps('username')}
          autoComplete="off"
        />
      </div>
      <Textarea
        label="Recipients"
        description="Separated by commas or new lines."
        {...form.getInputProps('to')}
        autosize
        minRows={2}
        required
      />
      <TextInput
        label="Subject prefix"
        description="Put before every subject, so a mail rule can file alerts."
        {...form.getInputProps('subjectPrefix')}
      />
    </>
  );
}

/** The endpoint choice of a PAGERDUTY channel, with a URL field for a custom one. */
function PagerDutyFields({ form }: KindFieldsProps) {
  return (
    <>
      <Select
        label="Endpoint"
        data={PAGERDUTY_ENDPOINTS.map((e) => ({ value: e.value, label: e.label }))}
        {...form.getInputProps('pagerDutyEndpoint')}
        allowDeselect={false}
      />
      {form.values.pagerDutyEndpoint === 'custom' ? (
        <TextInput
          label="Events API v2 URL"
          {...form.getInputProps('url')}
          placeholder="https://oncall.example.com/v2/enqueue"
          required
        />
      ) : null}
    </>
  );
}

function ChannelForm({
  channel,
  onClose,
}: Readonly<{
  channel: NotificationChannelView | null;
  onClose: () => void;
}>) {
  const editing = channel !== null;
  const hasSecret = channel?.hasSecret ?? false;
  const [testResult, setTestResult] = useState<ChannelTestResultView | null>(null);
  const [formError, setFormError] = useState<ApiError | null>(null);

  const create = useCreateNotificationChannel();
  const update = useUpdateNotificationChannel();
  const test = useTestChannelConfig();

  const form = useForm<ChannelValues>({
    initialValues: {
      ...(channel ? fieldsFromConfig(channel.kind, channel.config) : EMPTY_FIELDS),
      kind: kindOf(channel?.kind) ?? 'SLACK',
      name: channel?.name ?? '',
      secret: '',
      enabled: channel?.enabled ?? true,
    },
    validateInputOnBlur: true,
    // Only the fields this kind shows are checked, in the order they are shown.
    validate: (values) =>
      Object.fromEntries(
        FIELDS[values.kind].flatMap((f) => {
          const message = validateField(values.kind, f, values[f], { editing, hasSecret, fields: values });
          return message ? [[f, message]] : [];
        }),
      ),
    // A test result describes the values it was run with, not the ones typed since.
    onValuesChange: () => setTestResult(null),
  });
  const { kind, enabled } = form.values;
  const info = CHANNEL_KINDS[kind];

  /** Validates the given fields; on failure focuses the first invalid one. */
  const validateOnly = (only: Field[]): boolean => {
    const invalid = only.filter((f) => form.validateField(f).hasError);
    focusFirstInvalid(form.getInputNode)(Object.fromEntries(invalid.map((f) => [f, true])));
    return invalid.length === 0;
  };

  const rejected = (e: ApiError) => {
    const field = serverField(e.message);
    const shown = FIELDS[kind].find((f) => f === field);
    if (shown) {
      form.setErrors({ [shown]: e.message.replace(/^[A-Za-z]+:\s/, '') });
      form.getInputNode(shown)?.focus();
    } else {
      setFormError(e);
    }
  };

  const runTest = () => {
    setFormError(null);
    setTestResult(null);
    if (!validateOnly(FIELDS[kind].filter((f) => f !== 'name'))) return;
    test.mutate(
      {
        channelId: channel?.id ?? null,
        kind,
        config: configFromFields(kind, form.values),
        secret: form.values.secret.trim() || undefined,
      },
      { onSuccess: setTestResult, onError: rejected },
    );
  };

  const save = form.onSubmit((values) => {
    setFormError(null);
    const body = {
      name: values.name.trim(),
      kind,
      config: configFromFields(kind, values),
      secret: values.secret.trim() || undefined,
      enabled: values.enabled,
    };
    const done = () => {
      notify.succeeded({ action: editing ? SAVE : ADD, subject: `channel "${body.name}"` });
      onClose();
    };
    if (channel) {
      update.mutate({ channelId: channel.id, body }, { onSuccess: done, onError: rejected });
    } else {
      create.mutate(body, { onSuccess: done, onError: rejected });
    }
  }, focusFirstInvalid(form.getInputNode));

  const saving = create.isPending || update.isPending;

  return (
    <form noValidate onSubmit={save}>
      <Stack gap="sm">
        {editing ? (
          <Text size="sm">
            <Text span fw={600}>
              Kind:
            </Text>{' '}
            {kindLabel(kind)} — a channel’s kind cannot change; add a new channel for another kind.
          </Text>
        ) : (
          <Select
            label="Kind"
            data={KIND_ORDER.map((k) => ({ value: k, label: CHANNEL_KINDS[k].label }))}
            {...form.getInputProps('kind')}
            onChange={(v) => {
              const next = kindOf(v);
              if (!next) return;
              form.setFieldValue('kind', next);
              form.clearErrors();
            }}
            allowDeselect={false}
          />
        )}
        <Text size="sm" c="dimmed">
          {info.description}
        </Text>

        <TextInput
          label="Name"
          description="How rules and the channel list refer to it."
          {...form.getInputProps('name')}
          required
        />

        {kind === 'WEBHOOK' ? (
          <TextInput
            label="Receiver URL"
            {...form.getInputProps('url')}
            placeholder="https://alerts.example.com/hooks/brokers"
            required
          />
        ) : null}

        {kind === 'EMAIL' ? <EmailFields form={form} /> : null}

        <PasswordInput
          label={info.secretLabel}
          description={
            editing && hasSecret
              ? `${info.secretDescription} A secret is stored; leave blank to keep it.`
              : info.secretDescription
          }
          placeholder={editing && hasSecret ? '•••••••• (stored — unchanged)' : info.secretPlaceholder}
          {...form.getInputProps('secret')}
          autoComplete="new-password"
          required={!info.secretOptional && !(editing && hasSecret)}
        />
        {kind === 'WEBHOOK' ? (
          <Button
            variant="subtle"
            size="compact-xs"
            type="button"
            onClick={() => form.setFieldValue('secret', generateSigningSecret())}
            className={classes.start}
          >
            Generate a random signing secret
          </Button>
        ) : null}

        {kind === 'PAGERDUTY' ? <PagerDutyFields form={form} /> : null}

        <Switch
          label={enabled ? 'Enabled — bound rules deliver here' : 'Disabled — bound rules skip this channel'}
          {...form.getInputProps('enabled', { type: 'checkbox' })}
        />

        <div role="status" aria-live="polite">
          {testResult ? <TestOutcome result={testResult} kind={kind} /> : null}
        </div>
        {formError ? <ErrorState variant="inline" error={formError} /> : null}

        <Group justify="space-between">
          <Button variant="default" onClick={runTest} loading={test.isPending} disabled={saving}>
            Send a test
          </Button>
          <Group gap="xs">
            <Button variant="subtle" onClick={onClose} disabled={saving}>
              Cancel
            </Button>
            <Button type="submit" loading={saving} disabled={test.isPending}>
              {editing ? 'Save' : 'Add channel'}
            </Button>
          </Group>
        </Group>
      </Stack>
    </form>
  );
}

/** A test outcome with its cause and what to do next — never just "failed". */
export function TestOutcome({ result, kind }: Readonly<{ result: ChannelTestResultView; kind: string }>) {
  if (result.delivered) {
    return (
      <Stack gap={4}>
        <Text size="sm" fw={600}>
          Test delivered in {result.durationMs} ms
        </Text>
        <Text size="sm">
          {kind === 'PAGERDUTY'
            ? 'PagerDuty accepted a test incident and its resolution; it will appear already resolved.'
            : 'Check the destination for a message titled "Test notification from Rahdar".'}
        </Text>
      </Stack>
    );
  }
  return (
    <Stack gap={4} align="flex-start">
      <StatusBadge tone="danger">Test not delivered</StatusBadge>
      <Text size="sm">{result.error ?? 'The receiver gave no reason.'}</Text>
      <Text size="sm">
        {result.permanent
          ? 'Retrying will not help: fix the URL, key or credentials and test again.'
          : 'This may be temporary — the receiver was unreachable or overloaded. Real deliveries are retried with backoff.'}
      </Text>
    </Stack>
  );
}
