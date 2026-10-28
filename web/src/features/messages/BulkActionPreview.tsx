import { useState } from 'react';
import { Button, Modal, Stack, Text, TextInput } from '@mantine/core';
import { useForm } from '@mantine/form';

import { useRoleIds } from '../clusters/index.ts';
import { useClusterKinds } from '../../kernel/shell/useClusterSystem.ts';
import { ConfirmDialog } from '../../ui/ConfirmDialog.tsx';
import { focusFirstInvalid } from '../../ui/formErrors.ts';
import { useMessageAction, type DryRunView, type MessageActionKind } from './api.ts';
import { announceFailure, announceResult, destroys, messageCount, VERBS } from './outcomes.ts';
import { DestinationPicker } from '../resources/index.ts';

/**
 * By-selector action with a mandatory preview (ADR-0022). "Preview" runs `dryRun=true` and takes the
 * broker's point-in-time estimate; the confirmation then states it and the cap. Deleting or expiring,
 * or going over the cap, is confirmed by pressing and holding (ADR-0186), and an over-cap run resends with
 * `override=true`. The selector and the target are checked on activation, with the reason beside the
 * field.
 */
export function BulkActionPreview({
  clusterId,
  subscriptionName,
  action,
  node,
  opened,
  onClose,
  onDone,
}: Readonly<{
  clusterId: string;
  subscriptionName: string;
  action: MessageActionKind;
  node?: string;
  opened: boolean;
  onClose: () => void;
  onDone: () => void;
}>) {
  const run = useMessageAction(clusterId, subscriptionName);
  const ids = useRoleIds(clusterId);
  const systemName = useClusterKinds(clusterId)?.system?.name ?? 'This system';
  const [preview, setPreview] = useState<DryRunView | null>(null);
  const form = useForm({
    initialValues: { filter: '', target: '' },
    validateInputOnBlur: true,
    validate: {
      filter: (value) => (action !== 'retry' && !value.trim() ? 'Enter the selector the messages must match.' : null),
      target: (value) => (action === 'move' && !value.trim() ? 'Name the subscription to move the messages to.' : null),
    },
  });
  const verb = VERBS[action].verb;

  const body = () => ({
    filter: form.values.filter,
    target: action === 'move' ? ids.subscription(form.values.target) : undefined,
  });

  const doPreview = form.onSubmit(
    () =>
      run.mutate(
        { action, body: body(), node, dryRun: true },
        {
          onSuccess: (r) => setPreview('cap' in r ? r : null),
          onError: (e) =>
            announceFailure(action, `the preview of the selector on subscription "${subscriptionName}"`, e),
        },
      ),
    focusFirstInvalid(form.getInputNode),
  );

  const reset = () => {
    form.reset();
    setPreview(null);
  };

  const close = () => {
    reset();
    onClose();
  };

  const execute = () =>
    run.mutate(
      { action, body: body(), node, override: preview?.overCap ?? false },
      {
        onSuccess: (r) => {
          announceResult(action, subscriptionName, r, preview?.affectedCount ?? 0);
          reset();
          onDone();
        },
        onError: (e) =>
          announceFailure(
            action,
            `${messageCount(preview?.affectedCount ?? 0)} in subscription "${subscriptionName}"`,
            e,
            () => {
              reset();
              onDone();
            },
          ),
      },
    );

  const overCap = preview?.overCap ?? false;
  const armed = destroys(action) || overCap;
  return (
    <>
      <Modal opened={opened && preview === null} onClose={close} title={`${verb} by selector`} size="lg">
        <form noValidate onSubmit={doPreview}>
          <Stack gap="sm">
            {action === 'retry' ? (
              <Text size="sm" c="dimmed">
                {systemName} has no by-selector retry — this replays <strong>every</strong> message on the subscription.
                Preview to see how many.
              </Text>
            ) : (
              <TextInput
                label="Selector"
                placeholder="region = 'eu' AND priority > 4"
                {...form.getInputProps('filter')}
                size="xs"
                data-autofocus
              />
            )}
            {action === 'move' ? (
              <DestinationPicker
                clusterId={clusterId}
                label="Target subscription"
                permission="message:send"
                {...form.getInputProps('target')}
                value={form.values.target}
              />
            ) : null}
            <div>
              <Button type="submit" size="xs" variant="default" loading={run.isPending}>
                Preview
              </Button>
            </div>
          </Stack>
        </form>
      </Modal>

      <ConfirmDialog
        opened={opened && preview !== null}
        onClose={close}
        title={`${verb} by selector`}
        consequence={
          preview ? (
            <Stack gap="xs">
              <Text size="sm">
                ≈ {messageCount(preview.affectedCount)} (estimate). Point-in-time count from the broker. Safety cap:{' '}
                {preview.cap.toLocaleString()}.
              </Text>
              {overCap ? (
                <Text size="sm">
                  That is over the cap. Confirming overrides it for this operation, and the override is recorded in the
                  audit log.
                </Text>
              ) : null}
              {destroys(action) ? <Text size="sm">This cannot be undone.</Text> : null}
            </Stack>
          ) : null
        }
        confirmLabel={`${verb} ${messageCount(preview?.affectedCount ?? 0)}${overCap ? ' anyway' : ''}`}
        tone={armed ? 'danger' : 'default'}
        pending={run.isPending}
        onConfirm={execute}
      />
    </>
  );
}
