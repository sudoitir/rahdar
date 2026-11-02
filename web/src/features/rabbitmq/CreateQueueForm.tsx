import { useState } from 'react';
import { NumberInput, Radio, Select, Stack, Switch, Text, TextInput } from '@mantine/core';
import { useForm } from '@mantine/form';

import { ConfirmDialog } from '../../ui/ConfirmDialog.tsx';
import { focusFirstInvalid } from '../../ui/formErrors.ts';
import type { ActionVerb } from '../../ui/notify.ts';
import { op, useChange, useVhosts } from './api.ts';
import { argumentsOf, type ArgumentRow } from './arguments.ts';
import { ArgumentRows } from './ArgumentRows.tsx';
import { ChangeDialog } from './ChangeDialog.tsx';
import { QUEUE_TYPE_MEANING, QUEUE_TYPES, type QueueType } from './words.ts';

const CREATE: ActionVerb = { verb: 'Create', past: 'Created', progressive: 'Creating' };

interface QueueForm {
  vhost: string;
  name: string;
  type: QueueType;
  durable: boolean;
  deliveryLimit: number | string;
  maxAge: string;
  arguments: ArgumentRow[];
}

/** What the create sends. */
interface QueueCreate {
  vhost: string;
  name: string;
  type: QueueType;
  durable: boolean;
  arguments: Record<string, string | number | boolean>;
}

/** The create-queue dialog of a RabbitMQ cluster (`subscriptions.create`): previewed, then confirmed. */
export function CreateQueueForm({
  clusterId,
  opened,
  onClose,
}: Readonly<{ clusterId: string; opened: boolean; onClose: () => void }>) {
  const vhosts = useVhosts(clusterId);
  const names = vhosts.data?.pages.flatMap((p) => p.items.map((v) => v.name)) ?? [];
  const [formOpen, setFormOpen] = useState(true);
  const form = useForm<QueueForm>({
    initialValues: { vhost: '', name: '', type: 'quorum', durable: true, deliveryLimit: '', maxAge: '', arguments: [] },
    validateInputOnBlur: true,
    validate: {
      vhost: (v) => (v ? null : 'Choose the virtual host.'),
      name: (v) => (v.trim() ? null : 'Enter the name of the queue.'),
      arguments: { k: (v) => (v.trim() ? null : 'Name the argument, or remove the row.') },
    },
  });
  const finish = () => {
    setFormOpen(true);
    form.reset();
    onClose();
  };
  const create = useChange<QueueCreate>(clusterId, (q) => op(clusterId, 'POST', 'queues', q), finish);
  const replicated = form.values.type !== 'classic';
  const submit = form.onSubmit((v) => {
    const args = argumentsOf(v.arguments);
    if (v.type === 'quorum' && v.deliveryLimit !== '') args['x-delivery-limit'] = Number(v.deliveryLimit);
    if (v.type === 'stream' && v.maxAge.trim()) args['x-max-age'] = v.maxAge.trim();
    create.start(
      { vhost: v.vhost, name: v.name.trim(), type: v.type, durable: replicated || v.durable, arguments: args },
      () => setFormOpen(false),
    );
  }, focusFirstInvalid(form.getInputNode));
  const made = create.reviewed?.vars;
  return (
    <>
      <ConfirmDialog
        opened={opened && formOpen}
        onClose={create.close}
        title="Create a queue"
        size="lg"
        confirmLabel="Preview the queue"
        pending={create.previewing}
        onConfirm={() => submit()}
        consequence={
          <Stack gap="sm">
            <Select
              label="Virtual host"
              data={names}
              searchable
              placeholder="Choose a virtual host"
              {...form.getInputProps('vhost')}
            />
            <TextInput label="Name" data-autofocus {...form.getInputProps('name')} />
            <Radio.Group label="Type" {...form.getInputProps('type')}>
              <Stack gap="xs" mt="xs">
                {QUEUE_TYPES.map((t) => (
                  <Radio
                    key={t}
                    value={t}
                    label={t.charAt(0).toUpperCase() + t.slice(1)}
                    description={QUEUE_TYPE_MEANING[t]}
                  />
                ))}
              </Stack>
            </Radio.Group>
            <Switch
              label="Durable"
              description={
                replicated ? 'Quorum queues and streams are always durable.' : 'A durable queue survives a restart.'
              }
              checked={replicated || form.values.durable}
              disabled={replicated}
              onChange={(e) => form.setFieldValue('durable', e.currentTarget.checked)}
            />
            {form.values.type === 'quorum' ? (
              <NumberInput
                label="Delivery limit"
                description="How many times a message is redelivered before it is dropped or dead-lettered."
                allowDecimal={false}
                min={1}
                {...form.getInputProps('deliveryLimit')}
              />
            ) : null}
            {form.values.type === 'stream' ? (
              <TextInput
                label="Maximum age"
                description="How long a stream keeps a message, such as 7D, 12h or 30m."
                {...form.getInputProps('maxAge')}
              />
            ) : null}
            <ArgumentRows form={form} />
            <Text size="xs" c="dimmed">
              Nothing is created yet: the next step shows what the broker would do.
            </Text>
          </Stack>
        }
      />
      <ChangeDialog
        change={create}
        title={`Create queue ${made?.name ?? ''}`}
        action={CREATE}
        confirmLabel="Create the queue"
        subject={`queue "${made?.name ?? ''}"`}
      />
    </>
  );
}
