import { useMemo, useState } from 'react';
import { Button, Select, Stack, Text, TextInput } from '@mantine/core';
import { useForm } from '@mantine/form';
import { useParams, useSearch } from '@tanstack/react-router';

import { CapabilityGate } from '../../ui/CapabilityGate.tsx';
import type { GateVerdict } from '../../ui/capabilityGate.ts';
import { ConfirmDialog } from '../../ui/ConfirmDialog.tsx';
import { EmptyState } from '../../ui/EmptyState.tsx';
import { ErrorState } from '../../ui/ErrorState.tsx';
import { focusFirstInvalid } from '../../ui/formErrors.ts';
import type { ActionVerb } from '../../ui/notify.ts';
import { Page } from '../../ui/Page.tsx';
import { PageHeader } from '../../ui/PageHeader.tsx';
import { DataTable, type Column } from '../../ui/table/index.ts';
import { op, useBindings, useChange, type Binding } from './api.ts';
import { ChangeDialog } from './ChangeDialog.tsx';
import { useWriteGate } from './gate.ts';
import { FilterBar } from './FilterBar.tsx';
import type { FilterSearch } from './search.ts';
import { bindingWords } from './words.ts';

const CREATE: ActionVerb = { verb: 'Create', past: 'Created', progressive: 'Creating' };
const DELETE: ActionVerb = { verb: 'Delete', past: 'Deleted', progressive: 'Deleting' };

interface BindingForm {
  vhost: string;
  source: string;
  destination: string;
  destinationType: 'queue' | 'exchange';
  routingKey: string;
}

const EMPTY: BindingForm = { vhost: '', source: '', destination: '', destinationType: 'queue', routingKey: '' };

function columns(gate: GateVerdict, onDelete: (b: Binding) => void): Column<Binding>[] {
  return [
    { id: 'vhost', header: 'Virtual host', accessor: (b) => b.vhost, kind: 'identifier', priority: 'high' },
    {
      id: 'source',
      header: 'Source exchange',
      accessor: (b) => b.source || '(default)',
      kind: 'identifier',
      priority: 'essential',
    },
    {
      id: 'destination',
      header: 'Destination',
      accessor: (b) => b.destination,
      kind: 'identifier',
      priority: 'essential',
    },
    { id: 'type', header: 'Type', accessor: (b) => b.destinationType, kind: 'status', priority: 'high' },
    { id: 'key', header: 'Routing key', accessor: (b) => b.routingKey, kind: 'code', priority: 'essential' },
    {
      id: 'arguments',
      header: 'Arguments',
      accessor: (b) =>
        Object.entries(b.arguments)
          .map(([k, v]) => `${k}=${v}`)
          .join(', '),
      kind: 'code',
      priority: 'low',
    },
    {
      id: 'delete',
      header: 'Change',
      accessor: () => '',
      cell: (b) => (
        <CapabilityGate verdict={gate} what="deleting a binding">
          <Button
            size="compact-xs"
            variant="subtle"
            disabled={gate.kind === 'blocked'}
            aria-label={`Delete binding ${bindingWords(b)}`}
            onClick={() => onDelete(b)}
          >
            Delete
          </Button>
        </CapabilityGate>
      ),
      kind: 'status',
      priority: 'essential',
    },
  ];
}

/** The dialog that takes a new binding to its preview. */
function CreateBindingDialog({
  opened,
  onClose,
  onSubmit,
  pending,
  initial,
}: Readonly<{
  opened: boolean;
  onClose: () => void;
  onSubmit: (b: BindingForm) => void;
  pending: boolean;
  initial: Partial<BindingForm>;
}>) {
  const form = useForm<BindingForm>({
    initialValues: { ...EMPTY, ...initial },
    validateInputOnBlur: true,
    validate: {
      vhost: (v) => (v.trim() ? null : 'Enter the virtual host.'),
      source: (v) => (v.trim() ? null : 'Enter the exchange the messages come from.'),
      destination: (v) => (v.trim() ? null : 'Enter the queue or exchange that receives them.'),
    },
  });
  const submit = form.onSubmit(
    (v) => onSubmit({ ...v, vhost: v.vhost.trim(), source: v.source.trim(), destination: v.destination.trim() }),
    focusFirstInvalid(form.getInputNode),
  );
  return (
    <ConfirmDialog
      opened={opened}
      onClose={onClose}
      title="Create a binding"
      confirmLabel="Preview the binding"
      pending={pending}
      onConfirm={() => submit()}
      consequence={
        <Stack gap="sm">
          <Text size="sm">
            A binding sends the messages of an exchange that match its routing key to a queue or exchange.
          </Text>
          <TextInput label="Virtual host" data-autofocus {...form.getInputProps('vhost')} />
          <TextInput label="Source exchange" {...form.getInputProps('source')} />
          <Select
            label="Destination type"
            data={['queue', 'exchange']}
            allowDeselect={false}
            {...form.getInputProps('destinationType')}
          />
          <TextInput label="Destination" {...form.getInputProps('destination')} />
          <TextInput label="Routing key" {...form.getInputProps('routingKey')} />
        </Stack>
      }
    />
  );
}

/**
 * The bindings of a RabbitMQ cluster: which exchange sends which messages to which queue or exchange. Creating
 * one and deleting one are previewed, and the deletion is held.
 */
export function BindingsView() {
  const { clusterId } = useParams({ strict: false }) as { clusterId: string };
  const search = useSearch({ strict: false }) as FilterSearch;
  const bindings = useBindings(clusterId, search);
  const gate = useWriteGate(clusterId, 'route:write', 'Change bindings');
  const [creating, setCreating] = useState(false);
  const create = useChange<BindingForm>(clusterId, (v) => op(clusterId, 'POST', 'bindings', v));
  const remove = useChange<Binding>(clusterId, (b) => op(clusterId, 'DELETE', `bindings/${encodeURIComponent(b.id)}`));
  const rows = bindings.data?.pages.flatMap((p) => p.items) ?? [];
  const { start: startRemove } = remove;
  const cols = useMemo(() => columns(gate, startRemove), [gate, startRemove]);
  const filtered = Boolean(search.vhost || search.source || search.destination);
  const doomed = remove.reviewed?.vars;

  return (
    <Page fill>
      <PageHeader title="Bindings" description="Which exchange sends which messages to which queue or exchange." />
      <FilterBar
        label="Binding filters"
        fields={[
          { name: 'vhost', label: 'Virtual host' },
          { name: 'source', label: 'Source exchange' },
          { name: 'destination', label: 'Destination' },
        ]}
        end={
          <CapabilityGate verdict={gate} what="creating a binding">
            <Button size="xs" disabled={gate.kind === 'blocked'} onClick={() => setCreating(true)}>
              Create a binding
            </Button>
          </CapabilityGate>
        }
      />
      <DataTable
        label="Bindings"
        storageKey="rabbitmq.bindings"
        height="fill"
        columns={cols}
        data={rows}
        rowKey={(b) => b.id}
        loading={bindings.isPending}
        error={
          bindings.isError ? <ErrorState error={bindings.error} onRetry={() => void bindings.refetch()} /> : undefined
        }
        empty={
          filtered ? (
            <EmptyState
              kind="empty"
              title="No binding matches this filter"
              description="Bindings may still exist; none of them match the filter."
            />
          ) : (
            <EmptyState
              kind="empty"
              title="No bindings"
              description="Without a binding an exchange has nowhere to send a message, and a publish to it is not routed."
            />
          )
        }
      />
      {bindings.hasNextPage ? (
        <Button
          size="xs"
          variant="default"
          loading={bindings.isFetchingNextPage}
          onClick={() => void bindings.fetchNextPage()}
        >
          Load more bindings
        </Button>
      ) : null}
      <CreateBindingDialog
        key={String(creating)}
        opened={creating}
        onClose={() => setCreating(false)}
        pending={create.previewing}
        initial={{ vhost: search.vhost ?? '', source: search.source ?? '', destination: search.destination ?? '' }}
        onSubmit={(b) => create.start(b, () => setCreating(false))}
      />
      <ChangeDialog
        change={create}
        title="Create a binding"
        action={CREATE}
        confirmLabel="Create the binding"
        subject={create.reviewed ? `the binding ${bindingWords({ ...create.reviewed.vars })}` : 'the binding'}
      />
      <ChangeDialog
        change={remove}
        title="Delete a binding"
        action={DELETE}
        confirmLabel="Delete 1 binding"
        destructive
        subject={doomed ? `the binding ${bindingWords(doomed)}` : 'the binding'}
      />
    </Page>
  );
}
