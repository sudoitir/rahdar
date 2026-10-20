import { useEffect, useState, type ReactNode } from 'react';
import { Button, Modal, Select, Stack, Switch, Text, TextInput } from '@mantine/core';
import { useForm } from '@mantine/form';

import { ConfirmDialog } from '../../ui/ConfirmDialog.tsx';
import { DescriptionList } from '../../ui/DescriptionList.tsx';
import { DialogActions, useDiscardGuard } from '../../ui/DialogActions.tsx';
import { EmptyState } from '../../ui/EmptyState.tsx';
import { ErrorState } from '../../ui/ErrorState.tsx';
import { FieldRow } from '../../ui/FieldRow.tsx';
import { focusFirstInvalid } from '../../ui/formErrors.ts';
import { LoadingState } from '../../ui/LoadingState.tsx';
import { Notice } from '../../ui/Notice.tsx';
import { notify, type ActionVerb } from '../../ui/notify.ts';
import { Section } from '../../ui/Section.tsx';
import { DataTable } from '../../ui/table/index.ts';
import {
  useCreateRole,
  useDeleteRole,
  usePermissionsCatalogue,
  useRoles,
  useUpdateRole,
  type RoleView,
} from './api.ts';
import { roleColumns } from './columns.ts';
import { diffRoles } from './diffRoles.ts';
import { PermissionPicker } from './PermissionPicker.tsx';
import classes from './Security.module.css';

const CREATE: ActionVerb = { verb: 'Create', past: 'Created', progressive: 'Creating' };
const SAVE: ActionVerb = { verb: 'Save', past: 'Saved', progressive: 'Saving' };
const DELETE: ActionVerb = { verb: 'Delete', past: 'Deleted', progressive: 'Deleting' };

const NAME_ERROR = 'Name the role after what its holders do.';

const rowKey = (r: RoleView) => r.id;

/**
 * Role CRUD (authorization spec). A built-in role (ADMIN/OPERATOR/VIEWER) keeps its name and permissions and is shown
 * as facts; the one thing an administrator can change on it is whether it requires two-step verification.
 */
export function RolesPanel() {
  const roles = useRoles();
  const [editing, setEditing] = useState<RoleView | 'new' | null>(null);
  const [comparing, setComparing] = useState(false);
  // The dialog keeps what it was about while it fades out, so its words do not change under the reader.
  const [deleting, setDeleting] = useState<RoleView | null>(null);
  const [deleteOpen, setDeleteOpen] = useState(false);
  const [dirty, setDirty] = useState(false);
  const closeEditor = () => {
    setEditing(null);
    setDirty(false);
  };
  const guard = useDiscardGuard(dirty, closeEditor);

  const columns = roleColumns({
    onEdit: setEditing,
    onDelete: (r) => {
      setDeleting(r);
      setDeleteOpen(true);
    },
  });

  const count = roles.data?.length;

  return (
    <Section title="Roles" description="A role is a named set of permissions that is granted to users.">
      <DataTable
        variant="static"
        label="Roles"
        storageKey="security.roles"
        columns={columns}
        data={roles.data ?? []}
        rowKey={rowKey}
        loading={roles.isPending}
        error={roles.isError ? <ErrorState error={roles.error} onRetry={() => void roles.refetch()} /> : undefined}
        toolbar={{
          start: (
            <>
              <Button onClick={() => setEditing('new')}>New role</Button>
              <Button variant="default" onClick={() => setComparing(true)}>
                Compare roles
              </Button>
            </>
          ),
          end:
            count === undefined ? undefined : (
              <Text size="sm" c="dimmed">
                {count} role{count === 1 ? '' : 's'}
              </Text>
            ),
        }}
        empty={
          <EmptyState
            kind="empty"
            title="No roles"
            description="A role is a named set of permissions that is granted to users. Create one to give a group of users the same access."
          />
        }
      />

      {/* Remounted per role, so the editor never shows a previous role's values. */}
      <Modal
        opened={editing !== null}
        {...guard.modalProps}
        title={editing === 'new' ? 'New role' : `Edit "${editing?.name ?? ''}"`}
        size="lg"
      >
        {guard.prompt}
        {editing === null ? null : (
          <RoleEditor
            key={editing === 'new' ? 'new' : editing.id}
            role={editing}
            onDirtyChange={setDirty}
            onCancel={guard.modalProps.onClose}
            onDone={closeEditor}
          />
        )}
      </Modal>

      <DeleteRole role={deleting} opened={deleteOpen} onClose={() => setDeleteOpen(false)} />
      <CompareRolesModal opened={comparing} onClose={() => setComparing(false)} roles={roles.data ?? []} />
    </Section>
  );
}

/** The role form: a name and permissions for a custom role, only the two-step setting for a built-in one. */
function RoleEditor({
  role,
  onDirtyChange,
  onCancel,
  onDone,
}: Readonly<{
  role: RoleView | 'new';
  onDirtyChange: (dirty: boolean) => void;
  onCancel: () => void;
  onDone: () => void;
}>) {
  const catalogue = usePermissionsCatalogue();
  const create = useCreateRole();
  const update = useUpdateRole();
  const edited = role === 'new' ? null : role;
  const form = useForm({
    initialValues: {
      name: edited?.name ?? '',
      permissions: edited?.permissions ?? [],
      requiresMfa: edited?.requiresMfa ?? false,
      teamAssignable: edited?.teamAssignable ?? false,
    },
    validateInputOnBlur: true,
    validate: { name: (v) => (edited?.builtin || v.trim() ? null : NAME_ERROR) },
  });

  const dirty = form.isDirty();
  useEffect(() => onDirtyChange(dirty), [dirty, onDirtyChange]);

  // What a role grants applies to its members' next request; only a new second-factor requirement ends sessions.
  const endsSessions = edited !== null && form.values.requiresMfa !== edited.requiresMfa;
  const diff = edited && !edited.builtin ? diffRoles(edited.permissions, form.values.permissions) : null;

  const save = form.onSubmit((body) => {
    const subject = `role "${body.name}"`;
    if (edited === null) {
      create.mutate(body, {
        onSuccess: () => {
          notify.succeeded({ action: CREATE, subject });
          onDone();
        },
        onError: (error) =>
          notify.settle(error, {
            action: CREATE,
            subject,
            cause: error.message,
            next: 'No role was created. Try again.',
            onHeld: onDone,
          }),
      });
    } else {
      update.mutate(
        { roleId: edited.id, body },
        {
          onSuccess: () => {
            notify.succeeded({ action: SAVE, subject });
            onDone();
          },
          onError: (error) =>
            notify.settle(error, {
              action: SAVE,
              subject,
              cause: error.message,
              next: 'The role is unchanged. Try again.',
              onHeld: onDone,
            }),
        },
      );
    }
  }, focusFirstInvalid(form.getInputNode));

  return (
    <form noValidate onSubmit={save}>
      <Stack gap="sm">
        {edited?.builtin ? (
          <DescriptionList
            items={[
              { term: 'Name', value: edited.name },
              { term: 'Team role', value: edited.teamAssignable ? 'Yes' : 'No' },
              {
                term: 'Permissions',
                value: <span className={classes.code}>{edited.permissions.join(', ')}</span>,
                hint: 'Built-in roles keep their name and permissions; only the setting below can change.',
              },
            ]}
          />
        ) : (
          <TextInput label="Name" {...form.getInputProps('name')} required />
        )}
        {edited?.builtin ? null : (
          <Switch
            label="Team role"
            description="Lets the role be given to a team's members or in a share. A team role holds only permissions that act on a subscription or destination, and team:admin."
            {...form.getInputProps('teamAssignable', { type: 'checkbox' })}
          />
        )}
        {edited?.builtin
          ? null
          : (catalogueNotice(catalogue) ?? (
              <PermissionPicker
                catalogue={catalogue.data ?? []}
                value={form.values.permissions}
                onChange={(next) => form.setFieldValue('permissions', next)}
                teamRole={form.values.teamAssignable}
              />
            ))}
        <Switch
          label="Require two-step verification"
          description="Applies to local accounts. Single sign-on users rely on their identity provider."
          {...form.getInputProps('requiresMfa', { type: 'checkbox' })}
        />
        <div className={classes.dialogActions}>
          {diff ? <PermissionDiff added={diff.onlyB} removed={diff.onlyA} /> : null}
          {endsSessions ? (
            <Notice tone="warning" title="Signs everyone out">
              Saving signs out everyone who holds this role.
            </Notice>
          ) : null}
          <DialogActions>
            <Button variant="default" onClick={onCancel}>
              Cancel
            </Button>
            <Button type="submit" loading={create.isPending || update.isPending}>
              {edited === null ? 'Create role' : 'Save role'}
            </Button>
          </DialogActions>
        </div>
      </Stack>
    </form>
  );
}

/** What saving changes in the role's permissions, by name; nothing while the permissions are as they were. */
function PermissionDiff({ added, removed }: Readonly<{ added: string[]; removed: string[] }>) {
  if (added.length === 0 && removed.length === 0) return null;
  return (
    <output aria-label="Permission changes" className={classes.roleDiff}>
      {added.length > 0 ? (
        <Text size="sm">
          +{added.length} added: <span className={classes.code}>{added.join(', ')}</span>
        </Text>
      ) : null}
      {removed.length > 0 ? (
        <Text size="sm">
          −{removed.length} removed: <span className={classes.code}>{removed.join(', ')}</span>
        </Text>
      ) : null}
    </output>
  );
}

function deleteConsequence(role: RoleView): string {
  const count = role.permissions.length;
  const permissions = count === 1 ? 'permission' : 'permissions';
  return `This deletes the role ${role.name} and the ${count} ${permissions} it carries. A role that is still granted to a user cannot be deleted.`;
}

/** States what deleting a role means before it can be armed, then asks for the role's name. */
function DeleteRole({
  role,
  opened,
  onClose,
}: Readonly<{ role: RoleView | null; opened: boolean; onClose: () => void }>) {
  const remove = useDeleteRole();

  const confirm = (r: RoleView) =>
    remove.mutate(r.id, {
      onSuccess: () => {
        onClose();
        notify.succeeded({ action: DELETE, subject: `role "${r.name}"` });
      },
      onError: (error) =>
        notify.settle(error, {
          action: DELETE,
          subject: `role "${r.name}"`,
          cause: error.message,
          next:
            error.status === 409
              ? 'Remove it from the users who hold it, then delete it.'
              : 'The role still exists. Try again.',
          onHeld: onClose,
        }),
    });

  return (
    <ConfirmDialog
      opened={opened}
      onClose={onClose}
      title={role ? `Delete "${role.name}"` : 'Delete role'}
      tone="danger"
      pending={remove.isPending}
      confirmLabel="Delete role"
      consequence={role ? deleteConsequence(role) : ''}
      onConfirm={() => role && confirm(role)}
    />
  );
}

/** What differs between two roles, or how to pick them. */
function RoleComparison({ a, b }: Readonly<{ a: RoleView | undefined; b: RoleView | undefined }>) {
  if (!a || !b) {
    return (
      <Text size="sm" c="dimmed">
        Pick two roles to see the permissions only one of them holds.
      </Text>
    );
  }
  const diff = diffRoles(a.permissions, b.permissions);
  if (diff.onlyA.length === 0 && diff.onlyB.length === 0) {
    return (
      <Text size="sm" role="status">
        {a.name} and {b.name} hold the same permissions.
      </Text>
    );
  }
  return (
    <output className={classes.pair}>
      {[
        { name: a.name, only: diff.onlyA },
        { name: b.name, only: diff.onlyB },
      ].map((side) => (
        <Stack key={side.name} gap={4}>
          <Text size="sm" fw={500}>
            Only in {side.name} ({side.only.length})
          </Text>
          {side.only.length === 0 ? (
            <Text size="sm" c="dimmed">
              Nothing
            </Text>
          ) : (
            <ul aria-label={`Only in ${side.name}`} className={classes.only}>
              {side.only.map((p) => (
                <li key={p}>
                  <Text size="sm" className={classes.code}>
                    {p}
                  </Text>
                </li>
              ))}
            </ul>
          )}
        </Stack>
      ))}
    </output>
  );
}

/** What stands in for the permission picker while the catalogue loads or fails to load. */
function catalogueNotice(catalogue: ReturnType<typeof usePermissionsCatalogue>): ReactNode {
  if (catalogue.isError) {
    return <ErrorState error={catalogue.error} onRetry={() => void catalogue.refetch()} />;
  }
  if (catalogue.isPending) {
    return <LoadingState label="Loading permissions" blockSize="12rem" />;
  }
  return null;
}

function CompareRolesModal({
  opened,
  onClose,
  roles,
}: Readonly<{ opened: boolean; onClose: () => void; roles: RoleView[] }>) {
  const [a, setA] = useState<string | null>(null);
  const [b, setB] = useState<string | null>(null);
  const options = roles.map((r) => ({ value: r.id, label: r.name }));
  const roleA = roles.find((r) => r.id === a);
  const roleB = roles.find((r) => r.id === b);

  return (
    <Modal opened={opened} onClose={onClose} title="Compare roles" size="lg">
      <Stack gap="sm">
        <FieldRow>
          <Select label="First role" data={options} value={a} onChange={setA} searchable />
          <Select label="Second role" data={options} value={b} onChange={setB} searchable />
        </FieldRow>
        <RoleComparison a={roleA} b={roleB} />
      </Stack>
    </Modal>
  );
}
