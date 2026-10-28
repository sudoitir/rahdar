import { useCallback, useEffect, useState } from 'react';
import { Button, Checkbox, Code, CopyButton, Group, Modal, Stack, Switch, Text, TextInput } from '@mantine/core';
import { useForm } from '@mantine/form';

import { useCluster } from '../clusters/index.ts';
import {
  useCreateDivert,
  useDeleteDivert,
  type DivertMutationView,
  type RouteView,
  type LifecycleOutcomeView,
} from './api.ts';
import { nameOf } from '../../kernel/api/resourceId.ts';
import { useCan } from '../../kernel/auth/useCan.ts';
import { CapabilityGate } from '../../ui/CapabilityGate.tsx';
import { gateFor } from '../../ui/capabilityGate.ts';
import { ConfirmDialog } from '../../ui/ConfirmDialog.tsx';
import { ErrorState } from '../../ui/ErrorState.tsx';
import { focusFirstInvalid } from '../../ui/formErrors.ts';
import { NodeOutcomeSummary } from '../../ui/NodeOutcomeSummary.tsx';
import { Notice } from '../../ui/Notice.tsx';
import { focusBack } from '../../kernel/actions/focusBack.ts';
import { useActionHost } from '../../kernel/actions/hostContext.ts';
import type { HostedDialogProps } from '../../kernel/actions/types.ts';
import { useClusterKinds } from '../../kernel/shell/useClusterSystem.ts';
import { useDivertWriteGate } from './divertGate.ts';

const PERMISSION_LABEL = 'Create and delete diverts';

/**
 * What a divert costs a broker's configuration, stated wherever a divert is
 * created or listed.
 *
 * <p>Deliberately not "temporary". A divert created over the management API
 * survives a broker restart, along with the address and security settings that
 * come with it — measured on Artemis 2.56.0 (ADR-0065). The hazard is the
 * opposite one: the broker goes on diverting and its own configuration says
 * nothing about it, so the next deployment of that configuration silently
 * disagrees with the running broker.
 */
export const DRIFT_SENTENCE =
  'This divert stays on the broker across restarts and will not appear in the broker.xml your ' +
  'brokers deploy from, so the running broker and its configuration will disagree until you ' +
  'add it there. Nothing removes it for you — deleting it here is what takes it away.';

/** The broker.xml that closes the gap, copyable in one action. */
export function BrokerXmlRemedy({ xml }: Readonly<{ xml: string }>) {
  return (
    <Stack gap="xs">
      <Group justify="space-between" align="center">
        <Text size="xs" fw={600} c="dimmed">
          Add this to broker.xml to make the configuration match
        </Text>
        <CopyButton value={xml}>
          {({ copied, copy }) => (
            <Button size="compact-xs" variant="subtle" onClick={copy}>
              {copied ? 'Copied' : 'Copy'}
            </Button>
          )}
        </CopyButton>
      </Group>
      <Code block>{xml}</Code>
    </Stack>
  );
}

type DivertField = 'name' | 'address' | 'forwardingAddress' | 'filter';

const FIELD_ORDER: DivertField[] = ['name', 'address', 'forwardingAddress', 'filter'];

const tooLong = (value: string, max: number) => (value.length > max ? `At most ${max} characters.` : null);

const VALIDATORS: Record<DivertField, (value: string, values: Record<DivertField, string>) => string | null> = {
  name: (value) => {
    if (!value) return 'A divert needs a name.';
    if (value.length > 200) return 'At most 200 characters.';
    if (/[\s,=:*?"\\]/.test(value)) return 'A divert name cannot contain whitespace or any of , = : * ? " \\';
    if (value.startsWith('rahdar.capture.')) return 'This namespace belongs to message capture.';
    return null;
  },
  address: (value) => (value ? tooLong(value, 200) : 'A divert needs the address it reads from.'),
  forwardingAddress: (value, values) => {
    if (!value) return 'A divert needs the address it forwards to.';
    if (value.length > 200) return 'At most 200 characters.';
    return value === values.address.trim() ? 'A divert cannot forward to the address it reads from.' : null;
  },
  filter: (value) => tooLong(value, 4000),
};

/** The server's rules (LifecycleRequests.CreateDivertRequest), checked on blur so the message sits beside its field. */
function divertError(field: DivertField, values: Record<DivertField, string>): string | null {
  return VALIDATORS[field](values[field].trim(), values);
}

const divertRule = (field: DivertField) => (_: string, values: Record<DivertField, string>) =>
  divertError(field, values);

/** A server field error, on the field the operator can correct. */
function serverFieldFor(field: string): DivertField | null {
  if (field === 'forwardingAddressDistinct') return 'forwardingAddress';
  return (FIELD_ORDER as string[]).includes(field) ? (field as DivertField) : null;
}

/** Done once created; Edit and Create once previewed; otherwise Preview. */
function CreateFooter({
  result,
  preview,
  refused,
  pending,
  onDone,
  onEdit,
  onCreate,
}: Readonly<{
  result: boolean;
  preview: DivertMutationView | null;
  refused: number;
  pending: boolean;
  onDone: () => void;
  onEdit: () => void;
  onCreate: () => void;
}>) {
  if (result) {
    return (
      <Group justify="flex-end">
        <Button size="xs" onClick={onDone}>
          Done
        </Button>
      </Group>
    );
  }
  if (!preview) {
    return (
      <Group justify="flex-end">
        <Button type="submit" size="xs" loading={pending}>
          Preview
        </Button>
      </Group>
    );
  }
  return (
    <Group justify="flex-end">
      <Button size="xs" variant="subtle" disabled={pending} onClick={onEdit}>
        Edit
      </Button>
      {refused === preview.outcome.nodes.length ? null : (
        <Button size="xs" loading={pending} onClick={onCreate}>
          Create on every live node
        </Button>
      )}
    </Group>
  );
}

/** The server's field errors, each on the field the operator can correct. */
function serverErrorsFor(fieldErrors: { field: string; message: string }[]): Partial<Record<DivertField, string>> {
  const mapped: Partial<Record<DivertField, string>> = {};
  for (const fe of fieldErrors) {
    const field = serverFieldFor(fe.field);
    if (field) mapped[field] = fe.message;
  }
  return mapped;
}

/** What the create shows: the result once made, or the preview with why it would be refused, and the remedy. */
function CreateOutcome({
  result,
  preview,
  refused,
}: Readonly<{ result: DivertMutationView | null; preview: DivertMutationView | null; refused: number }>) {
  if (result) {
    return (
      <Stack gap="sm">
        <NodeOutcomeSummary outcome={result.outcome} />
        <Notice tone="warning" title="Your broker configuration does not know about this">
          <Stack gap="sm">
            <Text size="xs">{DRIFT_SENTENCE}</Text>
            <BrokerXmlRemedy xml={result.brokerXml} />
          </Stack>
        </Notice>
      </Stack>
    );
  }
  if (!preview) return null;
  return (
    <Stack gap="sm">
      <NodeOutcomeSummary outcome={preview.outcome} />
      {refused > 0 ? (
        <Notice
          tone={refused === preview.outcome.nodes.length ? 'danger' : 'warning'}
          title="This divert would be refused"
        >
          {refused === preview.outcome.nodes.length
            ? 'Every node refuses it, for the reason under each node above. Edit it and preview again.'
            : 'Some nodes refuse it, for the reason under each node above. Creating it anyway applies it only where it is not refused.'}
        </Notice>
      ) : null}
      <Notice tone="warning" title="Before you create this">
        <Stack gap="sm">
          <Text size="xs">{DRIFT_SENTENCE}</Text>
          <BrokerXmlRemedy xml={preview.brokerXml} />
        </Stack>
      </Notice>
    </Stack>
  );
}

/**
 * Create a divert across every live node.
 *
 * <p>The preview and the configuration remedy arrive in the same response and are
 * rendered together above the creating control, so neither can be skipped past.
 * While a preview is shown the form is read-only: what is created is exactly what
 * was previewed, and changing it means going back to edit and previewing again.
 */
export function CreateDivertAction({ clusterId }: Readonly<{ clusterId: string }>) {
  const { can, loading } = useCan();
  const cluster = useCluster(clusterId);
  const write = cluster.data?.capabilities.managementWrite;
  const gate = gateFor(can('route:write', clusterId), PERMISSION_LABEL, write, loading || cluster.isPending);

  const [open, setOpen] = useState(false);
  const [preview, setPreview] = useState<DivertMutationView | null>(null);
  const [result, setResult] = useState<DivertMutationView | null>(null);

  const form = useForm({
    initialValues: { name: '', address: '', forwardingAddress: '', filter: '', exclusive: false, acknowledge: false },
    validateInputOnBlur: true,
    validate: {
      name: divertRule('name'),
      address: divertRule('address'),
      forwardingAddress: divertRule('forwardingAddress'),
      filter: divertRule('filter'),
    },
  });
  const { exclusive } = form.values;

  const create = useCreateDivert(clusterId);
  const frozen = preview !== null || result !== null;
  const bodyOf = (values: typeof form.values) => ({
    name: values.name.trim(),
    address: values.address.trim(),
    forwardingAddress: values.forwardingAddress.trim(),
    filter: values.filter.trim() || undefined,
    exclusive: values.exclusive,
    acknowledgeCaptureShadowing: values.exclusive && values.acknowledge,
  });

  const requestPreview = form.onSubmit((values) => {
    create.mutate(
      { body: bodyOf(values), dryRun: true },
      {
        onSuccess: setPreview,
        // The server's rules land beside the field they are about.
        onError: (error) => {
          const mapped = serverErrorsFor(error.fieldErrors);
          form.setErrors(mapped);
          focusFirstInvalid(form.getInputNode)(mapped);
        },
      },
    );
  }, focusFirstInvalid(form.getInputNode));

  const close = () => {
    // Closing mid-request would hide the outcome of a change that is still being made.
    if (create.isPending) return;
    setOpen(false);
    setPreview(null);
    setResult(null);
    form.reset();
    create.reset();
  };

  const refused = preview?.outcome.nodes.filter((n) => n.status === 'FAILED') ?? [];

  const input = (field: DivertField, label: string, description?: string) => (
    <TextInput label={label} description={description} {...form.getInputProps(field)} readOnly={frozen} size="xs" />
  );

  return (
    <>
      <CapabilityGate verdict={gate} what="creating a divert">
        <Button size="xs" variant="default" disabled={gate.kind === 'blocked'} onClick={() => setOpen(true)}>
          Create divert
        </Button>
      </CapabilityGate>

      <Modal opened={open} onClose={close} title="Create a divert" size="lg">
        <form noValidate onSubmit={requestPreview}>
          <Stack gap="sm">
            {input('name', 'Name', 'Unique on each node.')}
            {input('address', 'Divert messages from')}
            {input('forwardingAddress', 'Divert messages to')}
            {input('filter', 'Filter', 'Optional. Only messages matching it are diverted.')}
            <Switch
              size="xs"
              {...form.getInputProps('exclusive', { type: 'checkbox' })}
              disabled={frozen}
              label="Exclusive — take the message instead of copying it"
              description={
                exclusive
                  ? 'Traffic on this address stops reaching its current destinations. Artemis evaluates exclusive diverts before non-exclusive ones, so this also runs ahead of any copying divert on the same address.'
                  : 'Traffic is copied. Its current destinations keep receiving it.'
              }
            />
            {exclusive ? (
              <Checkbox
                size="xs"
                {...form.getInputProps('acknowledge', { type: 'checkbox' })}
                disabled={frozen}
                label="Create it even if this address is being captured"
                description="Capture of the address would then record nothing while this divert exists. Needed only when the preview says the address is captured."
              />
            ) : null}

            {create.isError && create.error.fieldErrors.length === 0 ? (
              <ErrorState error={create.error} variant="inline" />
            ) : null}

            <CreateOutcome result={result} preview={preview} refused={refused.length} />

            <CreateFooter
              result={result !== null}
              preview={preview}
              refused={refused.length}
              pending={create.isPending}
              onDone={close}
              onEdit={() => setPreview(null)}
              onCreate={() => create.mutate({ body: bodyOf(form.values), dryRun: false }, { onSuccess: setResult })}
            />
          </Stack>
        </form>
      </Modal>
    </>
  );
}

/**
 * Delete one divert, across every live node.
 *
 * <p>A capture divert is not deletable here. Reconciliation would reinstate it on
 * its next pass, so the deletion would appear to succeed and then silently undo
 * itself; the operator is sent to the capture subscription that owns it instead.
 */
export function DeleteDivertAction({ clusterId, divert }: Readonly<{ clusterId: string; divert: RouteView }>) {
  const gate = useDivertWriteGate(clusterId);
  const host = useActionHost();

  if (divert.owner === 'MESSAGE_CAPTURE') {
    return (
      <Text size="xs" c="dimmed">
        Owned by message capture
      </Text>
    );
  }

  return (
    <CapabilityGate verdict={gate} what={`deleting divert ${divert.name}`}>
      <Button
        size="compact-xs"
        variant="default"
        disabled={gate.kind === 'blocked'}
        aria-label={`Delete divert ${divert.name}`}
        // Hosted outside the grid (ADR-0107), so a delete that removes this row keeps its outcome.
        onClick={(e) =>
          host.open(DeleteDivertDialog, { clusterId, divert }, { restoreFocus: focusBack(e.currentTarget) })
        }
      >
        Delete
      </Button>
    </CapabilityGate>
  );
}

/**
 * The delete itself: it opens on the preview of what each node would do, and is armed by typing the
 * divert's name. Until a preview has been taken, and when it could not be, the delete is offered
 * disabled with the reason beside it; once the delete has run, its per-node result replaces the
 * confirmation in the same dialog.
 */
export function DeleteDivertDialog({
  clusterId,
  divert,
  opened,
  onClose,
}: HostedDialogProps & { clusterId: string; divert: RouteView }) {
  const [preview, setPreview] = useState<LifecycleOutcomeView | null>(null);
  const [result, setResult] = useState<LifecycleOutcomeView | null>(null);
  const [previewFailed, setPreviewFailed] = useState<Error | null>(null);
  const remove = useDeleteDivert(clusterId, divert.name);
  const from = nameOf(divert.source);
  const to = nameOf(divert.target);
  const { mutate } = remove;

  const takePreview = useCallback(() => {
    setPreview(null);
    setPreviewFailed(null);
    mutate({ dryRun: true }, { onSuccess: setPreview, onError: setPreviewFailed });
  }, [mutate]);

  useEffect(() => {
    if (opened) takePreview();
  }, [opened, takePreview]);

  const close = () => {
    setPreview(null);
    setResult(null);
    setPreviewFailed(null);
    remove.reset();
    onClose();
  };

  let blocked: string | undefined;
  if (!preview) {
    blocked = previewFailed
      ? 'Nothing was deleted. Close this and try again once the nodes answer.'
      : 'Asking each node what the delete would do…';
  }

  return (
    <ConfirmDialog
      opened={opened}
      onClose={close}
      title={`Delete divert "${divert.name}"`}
      tone="danger"
      confirmLabel="Delete on every live node"
      // Only the delete itself locks the dialog; the preview before it can always be walked away from.
      pending={remove.isPending && preview !== null}
      blocked={blocked}
      onConfirm={() => remove.mutate({ dryRun: false }, { onSuccess: setResult })}
      result={result ? <NodeOutcomeSummary outcome={result} /> : undefined}
      consequence={
        <Stack gap="sm">
          <Text size="sm">
            {divert.nativeAttributes.exclusive === 'true'
              ? `Messages on ${from} stop going to ${to} and resume reaching their original destinations.`
              : `${to} stops receiving a copy of the messages on ${from}. Traffic on ${from} itself is unaffected.`}
          </Text>
          <div aria-live="polite">
            {previewFailed ? (
              <ErrorState variant="inline" error={previewFailed} next="The preview could not be taken." />
            ) : null}
            {preview && !result ? <NodeOutcomeSummary outcome={preview} /> : null}
          </div>
          {remove.isError && preview ? <ErrorState error={remove.error} variant="inline" /> : null}
        </Stack>
      }
    />
  );
}

/**
 * The row's delete, on a route whose kind its system lets Rahdar delete (the kind's `delete` verb); nothing
 * on a route of a kind that has none, such as a bridge.
 */
export function DeleteRouteCell({ clusterId, route }: Readonly<{ clusterId: string; route: RouteView }>) {
  const kinds = useClusterKinds(clusterId);
  const deletable = kinds?.kinds?.find((k) => k.id === route.kind)?.verbs.includes('delete');
  return deletable ? <DeleteDivertAction clusterId={clusterId} divert={route} /> : null;
}
