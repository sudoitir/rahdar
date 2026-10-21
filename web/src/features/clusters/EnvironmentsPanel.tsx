import { useCallback, useMemo, useState } from 'react';
import { Button, ColorInput, Modal, NumberInput, Stack, TextInput } from '@mantine/core';
import { useForm } from '@mantine/form';

import { ApiError } from '../../kernel/api/request.ts';
import { useCan } from '../../kernel/auth/useCan.ts';
import { ConfirmDialog } from '../../ui/ConfirmDialog.tsx';
import { EmptyState } from '../../ui/EmptyState.tsx';
import { ErrorState } from '../../ui/ErrorState.tsx';
import { focusFirstInvalid, serverFieldErrors } from '../../ui/formErrors.ts';
import { notify, type ActionVerb } from '../../ui/notify.ts';
import { Section } from '../../ui/Section.tsx';
import { DataTable } from '../../ui/table/index.ts';
import {
  useCreateEnvironment,
  useDeleteEnvironment,
  useEnvironments,
  useUpdateEnvironment,
  type EnvironmentView,
} from './api.ts';
import classes from './Clusters.module.css';
import { environmentColumns } from './environmentColumns.tsx';

const CREATE: ActionVerb = { verb: 'Create', past: 'Created', progressive: 'Creating' };
const SAVE: ActionVerb = { verb: 'Save', past: 'Saved', progressive: 'Saving' };
const DELETE: ActionVerb = { verb: 'Delete', past: 'Deleted', progressive: 'Deleting' };

function editorTitle(editing: EnvironmentView | 'new' | null): string {
  if (editing === 'new') return 'New environment';
  return `Edit "${editing?.name ?? ''}"`;
}

/** Environment grouping CRUD (environments spec). Cluster assignment happens from the cluster's own settings. */
export function EnvironmentsPanel() {
  const environments = useEnvironments();
  const remove = useDeleteEnvironment();
  // Without the permission the list is never requested, so it would stay pending: say why instead of loading.
  const { can, loading: grantsLoading } = useCan();
  const mayRead = grantsLoading || can('environment:read');

  const [editing, setEditing] = useState<EnvironmentView | 'new' | null>(null);
  const [deleting, setDeleting] = useState<EnvironmentView | null>(null);

  const rows = environments.data ?? [];

  const { reset: resetRemove } = remove;

  const openNew = useCallback(() => setEditing('new'), []);
  const openEdit = useCallback((env: EnvironmentView) => setEditing(env), []);

  const askDelete = useCallback(
    (env: EnvironmentView) => {
      resetRemove();
      setDeleting(env);
    },
    [resetRemove],
  );

  // Stable, so the table does not measure its columns again on every render.
  const columns = useMemo(() => environmentColumns(openEdit, askDelete), [openEdit, askDelete]);

  return (
    <Section
      title="Environments"
      description={`${rows.length} environment${rows.length === 1 ? '' : 's'}. An environment groups clusters, such as production or staging.`}
      actions={
        <Button size="xs" onClick={openNew}>
          New environment
        </Button>
      }
    >
      <DataTable
        variant="static"
        label="Environments"
        storageKey="clusters.environments"
        columns={columns}
        data={rows}
        rowKey={(e) => e.id}
        height={{ maxRows: 12 }}
        loading={mayRead && environments.isPending}
        error={
          mayRead ? (
            environments.isError && (
              <ErrorState error={environments.error} onRetry={() => void environments.refetch()} />
            )
          ) : (
            <ErrorState error={new ApiError(403, { permission: 'environment:read' })} />
          )
        }
        empty={
          <EmptyState
            kind="empty"
            title="No environments yet"
            description="An environment groups clusters, such as production or staging, and marks them in the cluster list. Create one, then assign a cluster to it from the cluster's own settings."
            action={
              <Button size="xs" onClick={openNew}>
                New environment
              </Button>
            }
          />
        }
      />

      <Modal opened={editing !== null} onClose={() => setEditing(null)} title={editorTitle(editing)}>
        {/* Remounted per environment, so the editor never shows a previous one's values. */}
        {editing === null ? null : (
          <EnvironmentEditor
            key={editing === 'new' ? 'new' : editing.id}
            environment={editing === 'new' ? null : editing}
            nextOrder={rows.length}
            onDone={() => setEditing(null)}
          />
        )}
      </Modal>

      <ConfirmDialog
        opened={deleting !== null}
        onClose={() => setDeleting(null)}
        title="Delete environment"
        consequence={
          <Stack gap="xs">
            <span>
              Deleting <strong>{deleting?.name}</strong> removes the grouping. Its clusters stay registered and have no
              environment afterwards; permission grants scoped to this environment are removed with it.
            </span>
            {remove.isError ? <ErrorState variant="inline" error={remove.error} /> : null}
          </Stack>
        }
        confirmLabel="Delete environment"
        tone="danger"
        pending={remove.isPending}
        onConfirm={() =>
          deleting &&
          remove.mutate(deleting.id, {
            onSuccess: () => {
              notify.succeeded({ action: DELETE, subject: `environment ${deleting.name}` });
              setDeleting(null);
            },
          })
        }
      />
    </Section>
  );
}

/** The environment form: a name, an optional colour and where it sorts. */
function EnvironmentEditor({
  environment,
  nextOrder,
  onDone,
}: Readonly<{ environment: EnvironmentView | null; nextOrder: number; onDone: () => void }>) {
  const create = useCreateEnvironment();
  const update = useUpdateEnvironment();
  const form = useForm<{ name: string; colour: string; sortOrder: number | string }>({
    initialValues: {
      name: environment?.name ?? '',
      colour: environment?.colour ?? '',
      sortOrder: environment?.sortOrder ?? nextOrder,
    },
    validateInputOnBlur: true,
    validate: { name: (v) => (v.trim() ? null : 'Give the environment a name.') },
  });

  const save = form.onSubmit((values) => {
    const body = { name: values.name.trim(), colour: values.colour || null, sortOrder: Number(values.sortOrder) || 0 };
    const subject = `environment ${body.name}`;
    const onError = (error: unknown) => form.setErrors(serverFieldErrors(error, ['name']));
    if (environment === null) {
      create.mutate(body, {
        onSuccess: () => {
          notify.succeeded({ action: CREATE, subject });
          onDone();
        },
        onError,
      });
    } else {
      update.mutate(
        { environmentId: environment.id, body },
        {
          onSuccess: () => {
            notify.succeeded({ action: SAVE, subject });
            onDone();
          },
          onError,
        },
      );
    }
  }, focusFirstInvalid(form.getInputNode));

  const failure = environment === null ? create.error : update.error;

  return (
    <form noValidate onSubmit={save}>
      <Stack gap="sm">
        <TextInput label="Name" {...form.getInputProps('name')} data-autofocus required />
        <ColorInput
          label="Colour"
          description="Optional. Marks the environment beside its name in the cluster list."
          {...form.getInputProps('colour')}
          format="hex"
          closeOnColorSwatchClick
        />
        <NumberInput
          label="Sort order"
          description="Environments are listed from the lowest number up."
          {...form.getInputProps('sortOrder')}
        />
        {failure ? <ErrorState variant="inline" error={failure} /> : null}
        <Button type="submit" loading={create.isPending || update.isPending} className={classes.start}>
          Save
        </Button>
      </Stack>
    </form>
  );
}
