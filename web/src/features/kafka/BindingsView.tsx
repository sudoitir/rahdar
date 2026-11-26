import { useState } from 'react';
import { Button, Select, Stack, Text, TextInput } from '@mantine/core';
import { useForm } from '@mantine/form';
import { Link } from '@tanstack/react-router';

import { useCan } from '../../kernel/auth/useCan.ts';
import { useCluster } from '../clusters/index.ts';
import { CapabilityGate } from '../../ui/CapabilityGate.tsx';
import { EmptyState } from '../../ui/EmptyState.tsx';
import { ErrorState } from '../../ui/ErrorState.tsx';
import { FieldRow } from '../../ui/FieldRow.tsx';
import { focusFirstInvalid } from '../../ui/formErrors.ts';
import linkClasses from '../../ui/InlineLink.module.css';
import { Notice } from '../../ui/Notice.tsx';
import { notify, type ActionVerb } from '../../ui/notify.ts';
import { DataTable, type Column } from '../../ui/table/index.ts';
import { SchemaReview } from './SchemaReview.tsx';
import {
  registryReason,
  schemaWriteGate,
  useApplyBinding,
  usePreviewBinding,
  useSchemaBindings,
  type SchemaBinding,
  type SchemaPreview,
} from './schemasApi.ts';

const SAVE: ActionVerb = { verb: 'Save', past: 'Saved', progressive: 'Saving' };
const NAMED = 'named';

const SERDE_CHOICES = [
  { value: 'avro', label: 'Avro' },
  { value: 'protobuf', label: 'Protobuf' },
  { value: 'json', label: 'JSON Schema' },
  { value: NAMED, label: 'A named serde' },
];

const SIDES = [
  { value: 'key', label: 'Key' },
  { value: 'value', label: 'Value' },
];

function bindingColumns(): Column<SchemaBinding>[] {
  return [
    { id: 'topic', header: 'Topic', accessor: (row) => row.topic ?? '', kind: 'identifier', priority: 'essential' },
    { id: 'side', header: 'Side', accessor: (row) => row.side ?? '', kind: 'status', priority: 'essential' },
    { id: 'serde', header: 'Serde', accessor: (row) => row.serde ?? '', kind: 'code', priority: 'high' },
  ];
}

/**
 * Which serde decodes a topic's key or value. Saving previews, then is confirmed by holding:
 * it overwrites the binding. The named field says the installation runs only the serdes it ships.
 */
export function BindingsView({ clusterId }: Readonly<{ clusterId: string }>) {
  const cluster = useCluster(clusterId);
  const bindings = useSchemaBindings(clusterId);
  const preview = usePreviewBinding(clusterId);
  const apply = useApplyBinding(clusterId);
  const { can, loading } = useCan();
  const [shown, setShown] = useState<SchemaPreview | null>(null);
  const [applied, setApplied] = useState<string | null>(null);

  const unavailable = registryReason(cluster.data?.capabilities.findings, null);
  const gate = schemaWriteGate(can('schema:write', clusterId), loading, unavailable);
  const rows = bindings.data?.items ?? [];

  const form = useForm({
    initialValues: { topic: '', side: 'value', choice: 'avro', name: '' },
    validateInputOnBlur: true,
    validate: {
      topic: (value) => (value.trim() ? null : 'Enter the topic name.'),
      side: (value) => (value === 'key' || value === 'value' ? null : 'Choose key or value.'),
      name: (value, values) =>
        values.choice !== NAMED || value.trim() ? null : 'Enter the serde name this installation runs.',
    },
  });

  const serde = form.values.choice === NAMED ? form.values.name.trim() : form.values.choice;
  const where = `${form.values.topic.trim()} ${form.values.side}`;

  const close = () => {
    setShown(null);
    setApplied(null);
    preview.reset();
    apply.reset();
  };

  const fetchPreview = form.onSubmit((values) => {
    apply.reset();
    const chosen = values.choice === NAMED ? values.name.trim() : values.choice;
    preview.mutate(
      { topic: values.topic.trim(), side: values.side, serde: chosen },
      { onSuccess: setShown },
    );
  }, focusFirstInvalid(form.getInputNode));

  const run = () => {
    if (!shown?.etag) return;
    apply.mutate(
      { topic: form.values.topic.trim(), side: form.values.side, serde, etag: shown.etag },
      {
        onSuccess: () => {
          setApplied(`Saved the ${form.values.side} serde of ${form.values.topic.trim()}.`);
          notify.succeeded({ action: SAVE, subject: `the binding for ${where}` });
          form.reset();
        },
        onError: (error) => {
          if (error.status === 412) return;
          notify.settle(error, {
            action: SAVE,
            subject: `the binding for ${where}`,
            cause: error.message,
            next: 'Preview again. Nothing was written.',
            onHeld: close,
          });
        },
      },
    );
  };

  return (
    <Stack gap="md">
      {unavailable ? (
        <Notice
          tone="warning"
          title="The schema registry is unavailable"
          action={
            <Link to={`/clusters/${clusterId}/settings?tab=clusters-connection`} className={linkClasses.link}>
              Connection settings
            </Link>
          }
        >
          {unavailable}
        </Notice>
      ) : null}
      <form noValidate onSubmit={fetchPreview}>
        <FieldRow>
          <TextInput label="Topic" {...form.getInputProps('topic')} />
          <Select label="Side" data={SIDES} allowDeselect={false} {...form.getInputProps('side')} />
          <Select label="Serde" data={SERDE_CHOICES} allowDeselect={false} {...form.getInputProps('choice')} />
          {form.values.choice === NAMED ? (
            <TextInput
              label="Serde name"
              description="This installation runs only the serdes it ships."
              {...form.getInputProps('name')}
            />
          ) : null}
          <CapabilityGate verdict={gate} what="saving a binding">
            <Button type="submit" disabled={gate.kind === 'blocked'} loading={preview.isPending}>
              Save binding
            </Button>
          </CapabilityGate>
        </FieldRow>
      </form>
      {preview.isError ? <ErrorState variant="inline" error={preview.error} /> : null}
      <DataTable
        label="Schema bindings"
        storageKey="kafka.schema-bindings"
        variant="static"
        columns={bindingColumns()}
        data={rows}
        rowKey={(row) => `${row.topic ?? ''}:${row.side ?? ''}`}
        loading={bindings.isPending}
        error={bindings.isError ? <ErrorState error={bindings.error} onRetry={() => void bindings.refetch()} /> : undefined}
        empty={
          <EmptyState
            kind="empty"
            title="No bindings"
            description="A binding names the serde that decodes one topic's key or value. Without one, a payload that carries a schema id stays undecoded."
          />
        }
      />
      <SchemaReview
        opened={shown !== null}
        onClose={close}
        title={`Save binding for ${where}`}
        confirmLabel={`Save binding for ${where}`}
        tone="danger"
        lead={<Text size="sm">This overwrites the serde for {where}. The previous choice is replaced.</Text>}
        preview={shown}
        pending={apply.isPending}
        stale={apply.error?.status === 412}
        error={apply.isError && apply.error.status !== 412 ? apply.error : undefined}
        onConfirm={run}
        onRefresh={() => fetchPreview()}
        result={applied ? <Text size="sm">{applied}</Text> : undefined}
      />
    </Stack>
  );
}
