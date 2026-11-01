import { useState } from 'react';
import { Radio, Select, Stack, Switch, Text, TextInput } from '@mantine/core';
import { useForm } from '@mantine/form';

import { ConfirmDialog } from '../../ui/ConfirmDialog.tsx';
import { focusFirstInvalid } from '../../ui/formErrors.ts';
import type { ActionVerb } from '../../ui/notify.ts';
import { op, useChange, useVhosts } from './api.ts';
import { argumentsOf, type ArgumentRow } from './arguments.ts';
import { ArgumentRows } from './ArgumentRows.tsx';
import { ChangeDialog } from './ChangeDialog.tsx';
import { EXCHANGE_TYPE_MEANING, EXCHANGE_TYPES } from './words.ts';

const CREATE: ActionVerb = { verb: 'Create', past: 'Created', progressive: 'Creating' };

interface ExchangeForm {
  vhost: string;
  name: string;
  type: (typeof EXCHANGE_TYPES)[number];
  durable: boolean;
  autoDelete: boolean;
  internal: boolean;
  arguments: ArgumentRow[];
}

interface ExchangeCreate {
  vhost: string;
  name: string;
  type: string;
  durable: boolean;
  autoDelete: boolean;
  internal: boolean;
  arguments: Record<string, string | number | boolean>;
}

/** The create-exchange dialog of a RabbitMQ cluster (`destinations.create`): previewed, then confirmed. */
export function CreateExchangeForm({
  clusterId,
  opened,
  onClose,
}: Readonly<{ clusterId: string; opened: boolean; onClose: () => void }>) {
  const vhosts = useVhosts(clusterId);
  const names = vhosts.data?.pages.flatMap((p) => p.items.map((v) => v.name)) ?? [];
  const [formOpen, setFormOpen] = useState(true);
  const form = useForm<ExchangeForm>({
    initialValues: {
      vhost: '',
      name: '',
      type: 'direct',
      durable: true,
      autoDelete: false,
      internal: false,
      arguments: [],
    },
    validateInputOnBlur: true,
    validate: {
      vhost: (v) => (v ? null : 'Choose the virtual host.'),
      name: (v) => (v.trim() ? null : 'Enter the name of the exchange.'),
      arguments: { k: (v) => (v.trim() ? null : 'Name the argument, or remove the row.') },
    },
  });
  const finish = () => {
    setFormOpen(true);
    form.reset();
    onClose();
  };
  const create = useChange<ExchangeCreate>(clusterId, (e) => op(clusterId, 'POST', 'exchanges', e), finish);
  const submit = form.onSubmit((v) => {
    create.start(
      {
        vhost: v.vhost,
        name: v.name.trim(),
        type: v.type,
        durable: v.durable,
        autoDelete: v.autoDelete,
        internal: v.internal,
        arguments: argumentsOf(v.arguments),
      },
      () => setFormOpen(false),
    );
  }, focusFirstInvalid(form.getInputNode));
  const made = create.reviewed?.vars;
  return (
    <>
      <ConfirmDialog
        opened={opened && formOpen}
        onClose={create.close}
        title="Create an exchange"
        size="lg"
        confirmLabel="Preview the exchange"
        pending={create.previewing}
        onConfirm={() => submit()}
        consequence={
          <Stack gap="sm">
            <Select
              label="Virtual host"
              data={names}
              searchable
              placeholder="Choose a virtual host"
              data-autofocus
              {...form.getInputProps('vhost')}
            />
            <TextInput label="Name" {...form.getInputProps('name')} />
            <Radio.Group label="Type" {...form.getInputProps('type')}>
              <Stack gap="xs" mt="xs">
                {EXCHANGE_TYPES.map((t) => (
                  <Radio
                    key={t}
                    value={t}
                    label={t.charAt(0).toUpperCase() + t.slice(1)}
                    description={EXCHANGE_TYPE_MEANING[t]}
                  />
                ))}
              </Stack>
            </Radio.Group>
            <Switch
              label="Durable"
              description="A durable exchange survives a restart."
              {...form.getInputProps('durable', { type: 'checkbox' })}
            />
            <Switch
              label="Auto-delete"
              description="Removed when its last binding is removed."
              {...form.getInputProps('autoDelete', { type: 'checkbox' })}
            />
            <Switch
              label="Internal"
              description="Only other exchanges can publish to it, never a client."
              {...form.getInputProps('internal', { type: 'checkbox' })}
            />
            <ArgumentRows form={form} />
            <Text size="xs" c="dimmed">
              Nothing is created yet: the next step shows what the broker would do.
            </Text>
          </Stack>
        }
      />
      <ChangeDialog
        change={create}
        title={`Create exchange ${made?.name ?? ''}`}
        action={CREATE}
        confirmLabel="Create the exchange"
        subject={`exchange "${made?.name ?? ''}"`}
      />
    </>
  );
}
