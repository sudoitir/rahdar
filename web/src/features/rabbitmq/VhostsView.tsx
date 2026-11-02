import { useCallback, useMemo, useState } from 'react';
import { Button, Group, Select, Stack, Text, TextInput } from '@mantine/core';
import { useForm } from '@mantine/form';
import { useParams } from '@tanstack/react-router';

import { CapabilityGate } from '../../ui/CapabilityGate.tsx';
import type { GateVerdict } from '../../ui/capabilityGate.ts';
import { ConfirmDialog } from '../../ui/ConfirmDialog.tsx';
import { EmptyState } from '../../ui/EmptyState.tsx';
import { ErrorState } from '../../ui/ErrorState.tsx';
import { focusFirstInvalid } from '../../ui/formErrors.ts';
import { Notice } from '../../ui/Notice.tsx';
import type { ActionVerb } from '../../ui/notify.ts';
import { Page } from '../../ui/Page.tsx';
import { PageHeader } from '../../ui/PageHeader.tsx';
import { Toolbar } from '../../ui/Toolbar.tsx';
import { DataTable, type Column } from '../../ui/table/index.ts';
import { op, seg, useChange, useVhosts, type Vhost } from './api.ts';
import { ChangeDialog } from './ChangeDialog.tsx';
import { countOf, useWriteGate } from './gate.ts';
import { QUEUE_TYPES } from './words.ts';

const CREATE: ActionVerb = { verb: 'Create', past: 'Created', progressive: 'Creating' };
const UPDATE: ActionVerb = { verb: 'Update', past: 'Updated', progressive: 'Updating' };
const DELETE: ActionVerb = { verb: 'Delete', past: 'Deleted', progressive: 'Deleting' };

interface VhostForm {
  name: string;
  description: string;
  tags: string;
  defaultQueueType: string;
}

/** What a create or update of a virtual host sends, and which one it is. */
interface VhostChange {
  existing: boolean;
  name: string;
  description: string;
  tags: string[];
  defaultQueueType?: string;
}

const splitTags = (text: string) =>
  text
    .split(',')
    .map((t) => t.trim())
    .filter(Boolean);

function columns(
  gate: GateVerdict,
  deleteGate: GateVerdict,
  onEdit: (v: Vhost) => void,
  onDelete: (v: Vhost) => void,
  complete: boolean,
): Column<Vhost>[] {
  const approx = complete ? '' : ' or more';
  return [
    { id: 'name', header: 'Virtual host', accessor: (v) => v.name, kind: 'identifier', priority: 'essential' },
    { id: 'description', header: 'Description', accessor: (v) => v.description, kind: 'text', priority: 'high' },
    {
      id: 'type',
      header: 'Default queue type',
      accessor: (v) => v.defaultQueueType ?? 'Broker default',
      kind: 'status',
      priority: 'high',
    },
    { id: 'tags', header: 'Tags', accessor: (v) => v.tags.join(', '), kind: 'text', priority: 'low' },
    {
      id: 'queues',
      header: 'Queues',
      accessor: (v) => v.queues,
      cell: (v) => `${v.queues.toLocaleString()}${approx}`,
      kind: 'number',
      priority: 'essential',
    },
    {
      id: 'messages',
      header: 'Messages ready',
      description: 'An estimate, read from the queues',
      accessor: (v) => v.messages,
      cell: (v) => `${v.messages.toLocaleString()}${approx}`,
      kind: 'number',
      priority: 'high',
    },
    {
      id: 'change',
      header: 'Change',
      accessor: () => 'Edit Delete',
      cell: (v) => (
        <Group gap={4} wrap="nowrap">
          <CapabilityGate verdict={gate} what="changing a virtual host">
            <Button
              size="compact-xs"
              variant="subtle"
              disabled={gate.kind === 'blocked'}
              aria-label={`Edit virtual host ${v.name}`}
              onClick={() => onEdit(v)}
            >
              Edit
            </Button>
          </CapabilityGate>
          <CapabilityGate verdict={deleteGate} what="deleting a virtual host">
            <Button
              size="compact-xs"
              variant="subtle"
              disabled={deleteGate.kind === 'blocked'}
              aria-label={`Delete virtual host ${v.name}`}
              onClick={() => onDelete(v)}
            >
              Delete
            </Button>
          </CapabilityGate>
        </Group>
      ),
      kind: 'status',
      priority: 'essential',
    },
  ];
}

/** The dialog that takes a new or edited virtual host to its preview. */
function VhostDialog({
  existing,
  initial,
  onClose,
  onSubmit,
  pending,
}: Readonly<{
  existing: boolean;
  initial: VhostForm | null;
  onClose: () => void;
  onSubmit: (c: VhostChange) => void;
  pending: boolean;
}>) {
  const form = useForm<VhostForm>({
    initialValues: initial ?? { name: '', description: '', tags: '', defaultQueueType: '' },
    validateInputOnBlur: true,
    validate: { name: (v) => (v.trim() ? null : 'Enter the name of the virtual host.') },
  });
  const submit = form.onSubmit(
    (v) =>
      onSubmit({
        existing,
        name: v.name.trim(),
        description: v.description,
        tags: splitTags(v.tags),
        defaultQueueType: v.defaultQueueType || undefined,
      }),
    focusFirstInvalid(form.getInputNode),
  );
  const title = existing ? `Edit virtual host ${initial?.name}` : 'Create a virtual host';
  return (
    <ConfirmDialog
      opened={initial !== null}
      onClose={onClose}
      title={title}
      confirmLabel="Preview the change"
      pending={pending}
      onConfirm={() => submit()}
      consequence={
        <Stack gap="sm">
          <Text size="sm">A virtual host is a separate namespace of queues, exchanges, bindings and permissions.</Text>
          <TextInput label="Name" disabled={existing} data-autofocus={!existing} {...form.getInputProps('name')} />
          <TextInput label="Description" {...form.getInputProps('description')} />
          <TextInput
            label="Tags"
            description="Separated by commas, such as production, eu."
            {...form.getInputProps('tags')}
          />
          <Select
            label="Default queue type"
            description="What a queue declared without a type becomes."
            data={QUEUE_TYPES.map((t) => ({ value: t, label: t }))}
            clearable
            placeholder="The broker's default"
            {...form.getInputProps('defaultQueueType')}
          />
        </Stack>
      }
    />
  );
}

const callOf = (clusterId: string) => (c: VhostChange) =>
  c.existing
    ? op(clusterId, 'PATCH', `vhosts/${seg(c.name)}`, {
        description: c.description,
        tags: c.tags,
        defaultQueueType: c.defaultQueueType,
      })
    : op(clusterId, 'POST', 'vhosts', {
        name: c.name,
        description: c.description,
        tags: c.tags,
        defaultQueueType: c.defaultQueueType,
      });

/**
 * The virtual hosts of a RabbitMQ cluster with their queues and messages. Deleting one removes every queue and
 * message in it, so its preview names each queue with its messages, and the deletion is held.
 */
export function VhostsView() {
  const { clusterId } = useParams({ strict: false }) as { clusterId: string };
  const vhosts = useVhosts(clusterId);
  const gate = useWriteGate(clusterId, 'namespace:write', 'Change virtual hosts');
  const deleteGate = useWriteGate(clusterId, 'namespace:delete', 'Delete virtual hosts');
  const [editing, setEditing] = useState<VhostForm | null>(null);
  const [existing, setExisting] = useState(false);
  const save = useChange<VhostChange>(clusterId, callOf(clusterId));
  const remove = useChange<Vhost>(clusterId, (v) => op(clusterId, 'DELETE', `vhosts/${seg(v.name)}`));
  const rows = vhosts.data?.pages.flatMap((p) => p.items) ?? [];
  const complete = vhosts.data?.pages.every((p) => p.countsComplete) ?? true;
  const { start: startRemove } = remove;
  const open = useCallback((v: Vhost | null) => {
    setExisting(v !== null);
    setEditing(
      v
        ? {
            name: v.name,
            description: v.description,
            tags: v.tags.join(', '),
            defaultQueueType: v.defaultQueueType ?? '',
          }
        : { name: '', description: '', tags: '', defaultQueueType: '' },
    );
  }, []);
  const cols = useMemo(
    () => columns(gate, deleteGate, open, startRemove, complete),
    [gate, deleteGate, open, startRemove, complete],
  );
  const doomed = remove.reviewed?.vars;
  const saved = save.reviewed?.vars;

  return (
    <Page fill>
      <PageHeader
        title="Virtual hosts"
        description="Separate namespaces of queues, exchanges, bindings and permissions on one cluster."
      />
      {complete ? null : (
        <Notice tone="warning" title="The counts are not complete">
          The read was cut short, so the queue and message counts are lower bounds.
        </Notice>
      )}
      <Toolbar
        label="Virtual host actions"
        end={
          <CapabilityGate verdict={gate} what="creating a virtual host">
            <Button size="xs" disabled={gate.kind === 'blocked'} onClick={() => open(null)}>
              Create a virtual host
            </Button>
          </CapabilityGate>
        }
      />
      <DataTable
        label="Virtual hosts"
        storageKey="rabbitmq.vhosts"
        height="fill"
        columns={cols}
        data={rows}
        rowKey={(v) => v.name}
        loading={vhosts.isPending}
        error={vhosts.isError ? <ErrorState error={vhosts.error} onRetry={() => void vhosts.refetch()} /> : undefined}
        empty={
          <EmptyState
            kind="empty"
            title="No virtual hosts"
            description="Every RabbitMQ cluster has at least one; this one reported none you can see."
          />
        }
      />
      {vhosts.hasNextPage ? (
        <Button
          size="xs"
          variant="default"
          loading={vhosts.isFetchingNextPage}
          onClick={() => void vhosts.fetchNextPage()}
        >
          Load more virtual hosts
        </Button>
      ) : null}
      <VhostDialog
        key={`${existing}/${editing?.name ?? ''}`}
        existing={existing}
        initial={editing}
        onClose={() => setEditing(null)}
        pending={save.previewing}
        onSubmit={(c) => save.start(c, () => setEditing(null))}
      />
      <ChangeDialog
        change={save}
        title={saved?.existing ? `Update virtual host ${saved.name}` : `Create virtual host ${saved?.name ?? ''}`}
        action={saved?.existing ? UPDATE : CREATE}
        confirmLabel={saved?.existing ? 'Update the virtual host' : 'Create the virtual host'}
        subject={`virtual host "${saved?.name ?? ''}"`}
      />
      <ChangeDialog
        change={remove}
        title={`Delete virtual host ${doomed?.name ?? ''}`}
        action={DELETE}
        confirmLabel={`Delete virtual host with ${countOf(doomed?.queues ?? 0, 'queue')}`}
        destructive
        subject={`virtual host "${doomed?.name ?? ''}"`}
      />
    </Page>
  );
}
