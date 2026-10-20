import { useEffect, useState, type ReactNode } from 'react';
import { Button, Group, Modal, Stack, Text } from '@mantine/core';
import {
  IconClipboard,
  IconEdit,
  IconLink,
  IconListDetails,
  IconPlayerPause,
  IconPlayerPlay,
  IconTrash,
} from '@tabler/icons-react';
import { Link, useNavigate } from '@tanstack/react-router';

import { useCluster } from '../clusters/index.ts';
import { useResourceGate } from '../../kernel/auth/useResourceGate.ts';
import type {
  ActionProps,
  AddressTarget,
  ConsumerTarget,
  HostedDialogProps,
  LinkProps,
  ProducerTarget,
  QueueTarget,
} from '../../kernel/actions/types.ts';
import { absoluteHref, clusterHref } from '../../kernel/routing/href.ts';
import { queueHref } from './queueHref.ts';
import { ActionMenuItem } from '../../ui/ActionMenuItem.tsx';
import { ConfirmDialog } from '../../ui/ConfirmDialog.tsx';
import { ErrorState } from '../../ui/ErrorState.tsx';
import { Notice } from '../../ui/Notice.tsx';
import { LoadingState } from '../../ui/LoadingState.tsx';
import { NodeOutcomeSummary } from '../../ui/NodeOutcomeSummary.tsx';
import { useQueue, useSetQueuePaused, type LifecycleOutcomeView, type QueueView } from './api.ts';
import { EditQueueForm } from './EditQueueForm.tsx';
import { DeleteQueueDialog } from './QueueLifecycleActions.tsx';
import linkClasses from '../../ui/InlineLink.module.css';

/**
 * The state of a management write on one queue: hidden when the caller cannot take it anywhere on the cluster,
 * otherwise a verdict, which is blocked on this queue when the row does not allow it (non-negotiable #5).
 */
function useWriteGate(clusterId: string, target: QueueTarget, permission: string, label: string) {
  const cluster = useCluster(clusterId);
  return useResourceGate({
    clusterId,
    noun: 'queue',
    permission,
    label,
    resource: target.snapshot,
    capability: cluster.data?.capabilities.managementWrite,
    pending: cluster.isPending,
  });
}

/**
 * A hosted dialog that needs the queue's row. The row comes with the target when the menu was
 * opened from the queues grid; elsewhere it is looked up by name, and a queue that is gone is
 * stated rather than opening a dialog about nothing. The inner dialog is opened a frame after the
 * row arrives, so its `onEnterTransitionEnd` preview still runs.
 */
function WithQueue({
  opened,
  onClose,
  clusterId,
  target,
  render,
}: HostedDialogProps & {
  clusterId: string;
  target: QueueTarget;
  render: (queue: QueueView, dialog: HostedDialogProps) => ReactNode;
}) {
  const { queue, isPending, isError, error } = useQueue(clusterId, target.queueName, target.snapshot);
  const [ready, setReady] = useState(false);
  useEffect(() => {
    if (!queue || !opened) return;
    const frame = requestAnimationFrame(() => setReady(true));
    return () => cancelAnimationFrame(frame);
  }, [queue, opened]);

  if (queue) return <>{render(queue, { opened: opened && ready, onClose })}</>;
  return (
    <Modal opened={opened} onClose={onClose} title={target.queueName}>
      <Stack gap="sm" aria-live="polite">
        <LookupState queueName={target.queueName} isPending={isPending} isError={isError} error={error} />
        <Group justify="flex-end">
          <Button size="xs" variant="default" onClick={onClose}>
            Close
          </Button>
        </Group>
      </Stack>
    </Modal>
  );
}

/** What the lookup dialog shows in place of a queue: the wait, the failure, or the queue not being there. */
function LookupState({
  queueName,
  isPending,
  isError,
  error,
}: Readonly<{ queueName: string; isPending: boolean; isError: boolean; error: Error | null }>) {
  if (isPending) return <LoadingState label="Looking the queue up" blockSize="4rem" />;
  if (isError) {
    return (
      <ErrorState
        error={error}
        variant="inline"
        next="Rahdar could not read the queue list just now. Try again in a moment."
      />
    );
  }
  return (
    <Notice tone="info" title="The queue is not there">
      No queue named {queueName} is on this cluster now. It may have been deleted since this view was loaded.
    </Notice>
  );
}

/**
 * Pause or resume one queue on every live node, from a menu. Reversible, so it is confirmed once;
 * its per-node outcome — including a partial one — stays until dismissed, because the menu that
 * started it is gone.
 */
function PauseQueueDialog({
  opened,
  onClose,
  clusterId,
  queue,
}: HostedDialogProps & { clusterId: string; queue: QueueView }) {
  const setPaused = useSetQueuePaused(clusterId, queue.queueName);
  const [outcome, setOutcome] = useState<LifecycleOutcomeView | null>(null);
  const paused = queue.perNode.some((n) => n.paused);
  const verb = paused ? 'Resume' : 'Pause';

  return (
    <ConfirmDialog
      opened={opened}
      onClose={onClose}
      title={`${verb} ${queue.queueName}`}
      confirmLabel={`${verb} queue`}
      pending={setPaused.isPending}
      onConfirm={() => setPaused.mutate({ paused: !paused }, { onSuccess: setOutcome })}
      result={outcome ? <NodeOutcomeSummary outcome={outcome} /> : undefined}
      consequence={
        <Stack gap="md">
          <Text size="sm">
            {paused
              ? 'Resuming restarts delivery to this queue’s consumers on every live node.'
              : 'Pausing stops delivery to this queue’s consumers on every live node. Messages keep arriving and wait; nothing is lost. Resume to deliver them.'}
          </Text>
          <div aria-live="polite">
            {setPaused.isPending ? <Text size="sm">{paused ? 'Resuming' : 'Pausing'} on every live node…</Text> : null}
            {setPaused.isError ? <ErrorState error={setPaused.error} variant="inline" /> : null}
          </div>
        </Stack>
      }
    />
  );
}

export function OpenQueue({ clusterId, target }: Readonly<ActionProps<QueueTarget>>) {
  const navigate = useNavigate();
  return (
    <ActionMenuItem
      label="Open details"
      icon={<IconListDetails size={16} aria-hidden />}
      href={queueHref(clusterId, target.queueName)}
      onSelect={() => navigate({ to: `/clusters/${clusterId}/queues`, search: { queue: target.queueName } })}
    />
  );
}

export function CopyQueueName({ target, host }: Readonly<ActionProps<QueueTarget>>) {
  return (
    <ActionMenuItem
      label="Copy queue name"
      icon={<IconClipboard size={16} aria-hidden />}
      onSelect={() => host.copy(target.queueName, 'queue name')}
    />
  );
}

export function CopyQueueLink({ clusterId, target, host }: Readonly<ActionProps<QueueTarget>>) {
  return (
    <ActionMenuItem
      label="Copy link to this queue"
      icon={<IconLink size={16} aria-hidden />}
      onSelect={() => host.copy(absoluteHref(queueHref(clusterId, target.queueName)), 'link to the queue')}
    />
  );
}

/** The menu entry: which of pause or resume applies, or both while it is not known. */
function pauseLabel(paused: boolean | undefined): string {
  if (paused === undefined) return 'Pause or resume…';
  return paused ? 'Resume…' : 'Pause…';
}

export function PauseResumeQueue({ clusterId, target, host }: Readonly<ActionProps<QueueTarget>>) {
  const { hidden, verdict: gate } = useWriteGate(clusterId, target, 'subscription:pause', 'Pause and resume queues');
  const { queue } = useQueue(clusterId, target.queueName, target.snapshot);
  const paused = queue ? queue.perNode.some((n) => n.paused) : undefined;
  const label = pauseLabel(paused);
  if (hidden) return null;
  return (
    <ActionMenuItem
      label={label}
      icon={paused ? <IconPlayerPlay size={16} aria-hidden /> : <IconPlayerPause size={16} aria-hidden />}
      verdict={gate}
      onExplain={(verdict) => host.explain(verdict, 'pausing this queue')}
      onSelect={() =>
        host.open(WithQueue, {
          clusterId,
          target,
          render: (q, dialog) => <PauseQueueDialog {...dialog} clusterId={clusterId} queue={q} />,
        })
      }
    />
  );
}

export function EditQueue({ clusterId, target, host }: Readonly<ActionProps<QueueTarget>>) {
  const { hidden, verdict: gate } = useWriteGate(clusterId, target, 'subscription:update', "Change a queue's configuration");
  if (hidden) return null;
  return (
    <ActionMenuItem
      label="Edit configuration…"
      icon={<IconEdit size={16} aria-hidden />}
      verdict={gate}
      onExplain={(verdict) => host.explain(verdict, 'editing this queue')}
      onSelect={() =>
        host.open(WithQueue, {
          clusterId,
          target,
          render: (q, dialog) => <EditQueueForm {...dialog} clusterId={clusterId} queue={q} />,
        })
      }
    />
  );
}

export function DeleteQueue({ clusterId, target, host }: Readonly<ActionProps<QueueTarget>>) {
  const { hidden, verdict: gate } = useWriteGate(clusterId, target, 'subscription:delete', 'Destroy queues and addresses');
  if (hidden) return null;
  return (
    <ActionMenuItem
      label="Delete queue…"
      icon={<IconTrash size={16} aria-hidden />}
      tone="danger"
      verdict={gate}
      onExplain={(verdict) => host.explain(verdict, 'deleting this queue')}
      onSelect={() =>
        host.open(WithQueue, {
          clusterId,
          target,
          render: (q, dialog) => <DeleteQueueDialog {...dialog} clusterId={clusterId} queue={q} onDeleted={() => {}} />,
        })
      }
    />
  );
}

/** A queue's name as a link to its detail (ADR-0107). */
export function QueueLink({ clusterId, target, children }: Readonly<LinkProps<QueueTarget>>) {
  return (
    <Link to={`/clusters/${clusterId}/queues`} search={{ queue: target.queueName }} className={linkClasses.link}>
      {children}
    </Link>
  );
}

/** On a consumer's row: the queue it consumes from. */
export function ConsumerOpenQueue({ clusterId, target }: Readonly<ActionProps<ConsumerTarget>>) {
  const navigate = useNavigate();
  const name = target.queueName;
  return (
    <ActionMenuItem
      label={name ? `Open queue ${name}` : 'Open its queue'}
      icon={<IconListDetails size={16} aria-hidden />}
      verdict={name ? undefined : { kind: 'blocked', reason: 'The broker reported no queue for this consumer.' }}
      href={name ? queueHref(clusterId, name) : undefined}
      onSelect={() => name && navigate({ to: `/clusters/${clusterId}/queues`, search: { queue: name } })}
    />
  );
}

/** The queues bound to an address, from any row that names one. */
function OpenQueuesOn({ clusterId, address }: Readonly<{ clusterId: string; address: string | null | undefined }>) {
  const navigate = useNavigate();
  return (
    <ActionMenuItem
      label="Open its queues"
      icon={<IconListDetails size={16} aria-hidden />}
      verdict={address ? undefined : { kind: 'blocked', reason: 'The broker reported no address for this row.' }}
      href={address ? clusterHref(clusterId, 'queues', { q: address }) : undefined}
      onSelect={() => address && navigate({ to: `/clusters/${clusterId}/queues`, search: { q: address } })}
    />
  );
}

export function AddressOpenQueues({ clusterId, target }: Readonly<ActionProps<AddressTarget>>) {
  return <OpenQueuesOn clusterId={clusterId} address={target.address} />;
}

export function ProducerOpenQueues({ clusterId, target }: Readonly<ActionProps<ProducerTarget>>) {
  return <OpenQueuesOn clusterId={clusterId} address={target.address} />;
}
