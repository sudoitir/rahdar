import { Stack, Text } from '@mantine/core';
import { useForm } from '@mantine/form';
import {
  IconArrowBackUp,
  IconArrowsRightLeft,
  IconClipboard,
  IconFileText,
  IconLink,
  IconMail,
  IconTrash,
} from '@tabler/icons-react';
import { useNavigate } from '@tanstack/react-router';

import { useResourceGate } from '../../kernel/auth/useResourceGate.ts';
import type { ActionProps, HostedDialogProps, MessageTarget, SubscriptionTarget } from '../../kernel/actions/types.ts';
import { absoluteHref, clusterHref } from '../../kernel/routing/href.ts';
import { ActionMenuItem } from '../../ui/ActionMenuItem.tsx';
import { useRoleIds } from '../clusters/index.ts';
import { ConfirmDialog } from '../../ui/ConfirmDialog.tsx';
import { focusFirstInvalid } from '../../ui/formErrors.ts';
import { notify } from '../../ui/notify.ts';
import { useMessageAction } from './api.ts';
import { useActionGate } from './gates.ts';
import { announceFailure, VERBS } from './outcomes.ts';
import { DestinationPicker } from '../resources/index.ts';

/** "Browse messages" on a subscription's row: the message browser for that subscription. */
export function BrowseSubscriptionMessages({ clusterId, target }: Readonly<ActionProps<SubscriptionTarget>>) {
  const navigate = useNavigate();
  const { hidden, verdict: gate } = useResourceGate({
    clusterId,
    noun: 'subscription',
    permission: 'message:read',
    label: 'Browse messages',
    resource: target.snapshot,
  });
  if (hidden) return null;
  const path = `subscriptions/${encodeURIComponent(target.name)}/messages`;
  return (
    <ActionMenuItem
      label="Browse messages"
      icon={<IconMail size="1rem" aria-hidden />}
      verdict={gate}
      href={clusterHref(clusterId, path)}
      onSelect={() => navigate({ to: `/clusters/${clusterId}/${path}` })}
    />
  );
}

/** Where one message opens: its subscription's browser with the message's detail, on the node browsed. */
function messagePath(target: MessageTarget) {
  return `subscriptions/${encodeURIComponent(target.subscription.name)}/messages`;
}

function messageSearch(target: MessageTarget) {
  return { message: target.position, node: target.node };
}

export function OpenMessage({ clusterId, target }: Readonly<ActionProps<MessageTarget>>) {
  const navigate = useNavigate();
  return (
    <ActionMenuItem
      label="Open details"
      icon={<IconFileText size="1rem" aria-hidden />}
      href={clusterHref(clusterId, messagePath(target), messageSearch(target))}
      onSelect={() =>
        navigate({
          to: `/clusters/${clusterId}/${messagePath(target)}`,
          search: (prev: Record<string, unknown>) => ({ ...prev, ...messageSearch(target) }),
        } as never)
      }
    />
  );
}

export function CopyMessage({ clusterId, target, host }: Readonly<ActionProps<MessageTarget>>) {
  return (
    <>
      <ActionMenuItem
        label="Copy message id"
        icon={<IconClipboard size="1rem" aria-hidden />}
        onSelect={() => host.copy(target.position, 'message id')}
      />
      <ActionMenuItem
        label="Copy link to this message"
        icon={<IconLink size="1rem" aria-hidden />}
        onSelect={() =>
          host.copy(
            absoluteHref(clusterHref(clusterId, messagePath(target), messageSearch(target))),
            'link to the message',
          )
        }
      />
    </>
  );
}

type OneAction = 'move' | 'retry' | 'delete';

const ONE: Record<OneAction, { what: string; confirmLabel: string; intro: (subscriptionName: string) => string }> = {
  move: {
    what: 'moving this message',
    confirmLabel: 'Move message',
    intro: (subscriptionName) =>
      `Moves this one message from ${subscriptionName} to the subscription you name, on the node it was read from.`,
  },
  retry: {
    what: 'retrying this message',
    confirmLabel: 'Retry message',
    intro: () => 'Sends this dead-lettered message back to the subscription it originally came from.',
  },
  delete: {
    what: 'deleting this message',
    confirmLabel: 'Delete this message',
    intro: (subscriptionName) => `Removes this one message from ${subscriptionName}. It cannot be brought back.`,
  },
};

/** The one message was not there to act on: consumed, expired or moved since the page was read. */
const NOT_FOUND =
  'The message was not found on the node. It may have been consumed, expired or moved since this page was read.';

/**
 * One message moved, retried or deleted by id, from its row. It confirms with what it does; a
 * delete is armed by typing the message id. The outcome is announced: done, not found (nothing was
 * changed), or failed with its cause. A move names its target, checked on activation.
 */
function OneMessageDialog({
  opened,
  onClose,
  clusterId,
  target,
  action,
}: HostedDialogProps & { clusterId: string; target: MessageTarget; action: OneAction }) {
  const run = useMessageAction(clusterId, target.subscription.name);
  const ids = useRoleIds(clusterId);
  const form = useForm({
    initialValues: { destination: '' },
    validateInputOnBlur: true,
    // Checked on activation, so the confirm button is never disabled with no reason given.
    validate: {
      destination: (value) =>
        action === 'move' && !value.trim() ? 'Name the target subscription to move the message.' : null,
    },
  });
  const one = ONE[action];
  const subject = `message ${target.position} in subscription "${target.subscription.name}"`;

  const submit = form.onSubmit(({ destination }) => {
    run.mutate(
      {
        action,
        body: { positions: [target.position], target: action === 'move' ? ids.subscription(destination) : undefined },
        node: target.node,
      },
      {
        onSuccess: (r) => {
          if ('cap' in r) return;
          if ('notDone' in r || r.affectedCount !== 1) {
            notify.failed({
              action: VERBS[action],
              subject,
              cause: 'notDone' in r ? r.error : NOT_FOUND,
              next: 'Nothing was changed. Reload the subscription to see what is there now.',
            });
          } else {
            notify.succeeded({ action: VERBS[action], subject });
          }
          onClose();
        },
        onError: (e) => announceFailure(action, subject, e, onClose),
      },
    );
  }, focusFirstInvalid(form.getInputNode));

  return (
    <ConfirmDialog
      opened={opened}
      onClose={onClose}
      title={`${VERBS[action].verb} message ${target.position}`}
      consequence={
        <Stack gap="sm">
          <Text size="sm">{one.intro(target.subscription.name)}</Text>
          {action === 'move' ? (
            <DestinationPicker
              clusterId={clusterId}
              label="Target subscription"
              description="The subscription receives the message on the same node."
              permission="message:send"
              {...form.getInputProps('destination')}
              value={form.values.destination}
            />
          ) : null}
        </Stack>
      }
      confirmLabel={one.confirmLabel}
      tone={action === 'delete' ? 'danger' : 'default'}
      pending={run.isPending}
      onConfirm={() => submit()}
    />
  );
}

function OneMessageItem({
  clusterId,
  target,
  host,
  action,
  icon,
}: ActionProps<MessageTarget> & { action: OneAction; icon: React.ReactNode }) {
  const gate = useActionGate(clusterId, action);
  return (
    <ActionMenuItem
      label={`${VERBS[action].verb}…`}
      icon={icon}
      tone={action === 'delete' ? 'danger' : undefined}
      verdict={gate}
      onExplain={(verdict) => host.explain(verdict, ONE[action].what)}
      onSelect={() => host.open(OneMessageDialog, { clusterId, target, action })}
    />
  );
}

export const MoveMessage = (p: ActionProps<MessageTarget>) => (
  <OneMessageItem {...p} action="move" icon={<IconArrowsRightLeft size="1rem" aria-hidden />} />
);
export const RetryMessage = (p: ActionProps<MessageTarget>) => (
  <OneMessageItem {...p} action="retry" icon={<IconArrowBackUp size="1rem" aria-hidden />} />
);
export const DeleteMessage = (p: ActionProps<MessageTarget>) => (
  <OneMessageItem {...p} action="delete" icon={<IconTrash size="1rem" aria-hidden />} />
);
