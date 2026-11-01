import { useCallback, useMemo, useState } from 'react';
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
import { op, seg, useChange, usePermissions, useUsers, type Permission } from './api.ts';
import { ChangeDialog } from './ChangeDialog.tsx';
import { useWriteGate } from './gate.ts';
import { FilterBar } from './FilterBar.tsx';
import type { FilterSearch } from './search.ts';

const SET: ActionVerb = { verb: 'Set', past: 'Set', progressive: 'Setting' };
const CLEAR: ActionVerb = { verb: 'Clear', past: 'Cleared', progressive: 'Clearing' };

interface PermissionForm {
  vhost: string;
  user: string;
  configure: string;
  write: string;
  read: string;
}

const EMPTY: PermissionForm = { vhost: '', user: '', configure: '.*', write: '.*', read: '.*' };

const isPattern = (text: string) => {
  try {
    new RegExp(text);
    return true;
  } catch {
    return false;
  }
};

function columns(
  gate: GateVerdict,
  onEdit: (p: Permission) => void,
  onClear: (p: Permission) => void,
): Column<Permission>[] {
  return [
    { id: 'user', header: 'User', accessor: (p) => p.user, kind: 'identifier', priority: 'essential' },
    { id: 'vhost', header: 'Virtual host', accessor: (p) => p.vhost, kind: 'identifier', priority: 'essential' },
    { id: 'configure', header: 'Configure', accessor: (p) => p.configure, kind: 'code', priority: 'high' },
    { id: 'write', header: 'Write', accessor: (p) => p.write, kind: 'code', priority: 'high' },
    { id: 'read', header: 'Read', accessor: (p) => p.read, kind: 'code', priority: 'high' },
    {
      id: 'topic',
      header: 'Topic permissions',
      accessor: (p) => p.topic.map((t) => `${t.exchange}: write ${t.write}, read ${t.read}`).join('; ') || 'None',
      kind: 'text',
      wrap: true,
      priority: 'low',
    },
    {
      id: 'change',
      header: 'Change',
      accessor: () => '',
      cell: (p) => (
        <Stack gap={0} align="flex-start">
          <CapabilityGate verdict={gate} what="changing permissions">
            <Button
              size="compact-xs"
              variant="subtle"
              disabled={gate.kind === 'blocked'}
              aria-label={`Edit permissions of ${p.user} on ${p.vhost}`}
              onClick={() => onEdit(p)}
            >
              Edit
            </Button>
            <Button
              size="compact-xs"
              variant="subtle"
              disabled={gate.kind === 'blocked'}
              aria-label={`Clear permissions of ${p.user} on ${p.vhost}`}
              onClick={() => onClear(p)}
            >
              Clear
            </Button>
          </CapabilityGate>
        </Stack>
      ),
      kind: 'status',
      priority: 'essential',
    },
  ];
}

/** The dialog that takes a user's permissions on a virtual host to their preview. */
function PermissionDialog({
  clusterId,
  initial,
  existing,
  onClose,
  onSubmit,
  pending,
}: Readonly<{
  clusterId: string;
  initial: PermissionForm | null;
  existing: boolean;
  onClose: () => void;
  onSubmit: (p: PermissionForm) => void;
  pending: boolean;
}>) {
  const users = useUsers(clusterId);
  const names = users.data?.pages.flatMap((p) => p.items.map((u) => u.name)) ?? [];
  const form = useForm<PermissionForm>({
    initialValues: initial ?? EMPTY,
    validateInputOnBlur: true,
    validate: {
      vhost: (v) => (v.trim() ? null : 'Enter the virtual host.'),
      user: (v) => (v ? null : 'Choose the user.'),
      configure: (v) => (isPattern(v) ? null : 'Enter a valid regular expression.'),
      write: (v) => (isPattern(v) ? null : 'Enter a valid regular expression.'),
      read: (v) => (isPattern(v) ? null : 'Enter a valid regular expression.'),
    },
  });
  const submit = form.onSubmit((v) => onSubmit({ ...v, vhost: v.vhost.trim() }), focusFirstInvalid(form.getInputNode));
  return (
    <ConfirmDialog
      opened={initial !== null}
      onClose={onClose}
      title={existing ? `Edit permissions of ${initial?.user}` : 'Set permissions'}
      confirmLabel="Preview the permissions"
      pending={pending}
      onConfirm={() => submit()}
      consequence={
        <Stack gap="sm">
          <Text size="sm">
            Each field is a regular expression over the names the user may configure, write to or read from. An empty
            expression allows none.
          </Text>
          <TextInput
            label="Virtual host"
            disabled={existing}
            data-autofocus={!existing}
            {...form.getInputProps('vhost')}
          />
          <Select
            label="User"
            data={names}
            searchable
            disabled={existing}
            placeholder="Choose a user"
            {...form.getInputProps('user')}
          />
          <TextInput label="Configure" {...form.getInputProps('configure')} />
          <TextInput label="Write" {...form.getInputProps('write')} />
          <TextInput label="Read" {...form.getInputProps('read')} />
        </Stack>
      }
    />
  );
}

/**
 * The users' permissions on the virtual hosts of a RabbitMQ cluster. A change is previewed with the user's rights
 * before and after; clearing is held. Changing them needs the permission to change access rules.
 */
export function PermissionsView() {
  const { clusterId } = useParams({ strict: false }) as { clusterId: string };
  const search = useSearch({ strict: false }) as FilterSearch;
  const permissions = usePermissions(clusterId, search);
  const gate = useWriteGate(clusterId, 'access-rule:write', 'Change access rules');
  const [editing, setEditing] = useState<PermissionForm | null>(null);
  const [existing, setExisting] = useState(false);
  const set = useChange<PermissionForm>(clusterId, (p) =>
    op(clusterId, 'PUT', `permissions/${seg(p.vhost)}/${seg(p.user)}`, {
      configure: p.configure,
      write: p.write,
      read: p.read,
    }),
  );
  const clear = useChange<Permission>(clusterId, (p) =>
    op(clusterId, 'DELETE', `permissions/${seg(p.vhost)}/${seg(p.user)}`),
  );
  const rows = permissions.data?.pages.flatMap((p) => p.items) ?? [];
  const { start: startClear } = clear;
  const { vhost: filterVhost, user: filterUser } = search;
  const open = useCallback(
    (p: Permission | null) => {
      setExisting(p !== null);
      setEditing(
        p
          ? { vhost: p.vhost, user: p.user, configure: p.configure, write: p.write, read: p.read }
          : { ...EMPTY, vhost: filterVhost ?? '', user: filterUser ?? '' },
      );
    },
    [filterVhost, filterUser],
  );
  const cols = useMemo(() => columns(gate, open, startClear), [gate, open, startClear]);
  const chosen = set.reviewed?.vars;
  const cleared = clear.reviewed?.vars;

  return (
    <Page fill>
      <PageHeader
        title="Permissions"
        description="What each user may configure, write to and read from on each virtual host."
      />
      <FilterBar
        label="Permission filters"
        fields={[
          { name: 'vhost', label: 'Virtual host' },
          { name: 'user', label: 'User' },
        ]}
        end={
          <CapabilityGate verdict={gate} what="setting permissions">
            <Button size="xs" disabled={gate.kind === 'blocked'} onClick={() => open(null)}>
              Set permissions
            </Button>
          </CapabilityGate>
        }
      />
      <DataTable
        label="Permissions"
        storageKey="rabbitmq.permissions"
        height="fill"
        columns={cols}
        data={rows}
        rowKey={(p) => `${p.vhost}/${p.user}`}
        loading={permissions.isPending}
        error={
          permissions.isError ? (
            <ErrorState error={permissions.error} onRetry={() => void permissions.refetch()} />
          ) : undefined
        }
        empty={
          <EmptyState
            kind="empty"
            title="No permissions"
            description="A user with no permission on a virtual host cannot use it."
          />
        }
      />
      {permissions.hasNextPage ? (
        <Button
          size="xs"
          variant="default"
          loading={permissions.isFetchingNextPage}
          onClick={() => void permissions.fetchNextPage()}
        >
          Load more permissions
        </Button>
      ) : null}
      <PermissionDialog
        key={`${existing}/${editing?.user ?? ''}`}
        clusterId={clusterId}
        initial={editing}
        existing={existing}
        onClose={() => setEditing(null)}
        pending={set.previewing}
        onSubmit={(p) => set.start(p, () => setEditing(null))}
      />
      <ChangeDialog
        change={set}
        title={`Set permissions of ${chosen?.user ?? ''}`}
        action={SET}
        confirmLabel="Set the permissions"
        subject={`the permissions of "${chosen?.user ?? ''}" on "${chosen?.vhost ?? ''}"`}
      />
      <ChangeDialog
        change={clear}
        title={`Clear permissions of ${cleared?.user ?? ''}`}
        action={CLEAR}
        confirmLabel="Clear 1 permission"
        destructive
        subject={`the permissions of "${cleared?.user ?? ''}" on "${cleared?.vhost ?? ''}"`}
      />
    </Page>
  );
}
