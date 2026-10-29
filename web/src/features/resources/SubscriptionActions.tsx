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

import { nameOf } from '../../kernel/api/resourceId.ts';
import { useCluster } from '../clusters/index.ts';
import { useResourceGate } from '../../kernel/auth/useResourceGate.ts';
import type {
  ActionProps,
  DestinationTarget,
  ConsumerTarget,
  HostedDialogProps,
  LinkProps,
  ProducerTarget,
  SubscriptionTarget,
} from '../../kernel/actions/types.ts';
import { absoluteHref, clusterHref } from '../../kernel/routing/href.ts';
import { subscriptionHref } from './subscriptionHref.ts';
import { ActionMenuItem } from '../../ui/ActionMenuItem.tsx';
import { ConfirmDialog } from '../../ui/ConfirmDialog.tsx';
import { ErrorState } from '../../ui/ErrorState.tsx';
import { Notice } from '../../ui/Notice.tsx';
import { LoadingState } from '../../ui/LoadingState.tsx';
import { NodeOutcomeSummary } from '../../ui/NodeOutcomeSummary.tsx';
import { useSubscription, useSetSubscriptionPaused, type LifecycleOutcomeView, type SubscriptionView } from './api.ts';
import { EditSubscriptionForm } from './EditSubscriptionForm.tsx';
import { DeleteSubscriptionDialog } from './SubscriptionLifecycleActions.tsx';
import linkClasses from '../../ui/InlineLink.module.css';

/**
 * The state of a management write on one subscription: hidden when the caller cannot take it anywhere on the cluster,
 * otherwise a verdict, which is blocked on this subscription when the row does not allow it (non-negotiable #5).
 */
function useWriteGate(clusterId: string, target: SubscriptionTarget, verb: string, permission: string, label: string) {
  const cluster = useCluster(clusterId);
  return useResourceGate({
    clusterId,
    noun: 'subscription',
    permission,
    label,
    resource: target.snapshot,
    kind: { id: target.kind, verb },
    capability: cluster.data?.capabilities.managementWrite,
    pending: cluster.isPending,
  });
}

/**
 * A hosted dialog that needs the subscription's row. The row comes with the target when the menu was
 * opened from the subscriptions grid; elsewhere it is looked up by name, and a subscription that is gone is
 * stated rather than opening a dialog about nothing. The inner dialog is opened a frame after the
 * row arrives, so its `onEnterTransitionEnd` preview still runs.
 */
function WithSubscription({
  opened,
  onClose,
  clusterId,
  target,
  render,
}: HostedDialogProps & {
  clusterId: string;
  target: SubscriptionTarget;
  render: (subscription: SubscriptionView, dialog: HostedDialogProps) => ReactNode;
}) {
  const { subscription, isPending, isError, error } = useSubscription(clusterId, target.id, target.snapshot);
  const [ready, setReady] = useState(false);
  useEffect(() => {
    if (!subscription || !opened) return;
    const frame = requestAnimationFrame(() => setReady(true));
    return () => cancelAnimationFrame(frame);
  }, [subscription, opened]);

  if (subscription) return <>{render(subscription, { opened: opened && ready, onClose })}</>;
  return (
    <Modal opened={opened} onClose={onClose} title={target.name}>
      <Stack gap="sm" aria-live="polite">
        <LookupState subscriptionName={target.name} isPending={isPending} isError={isError} error={error} />
        <Group justify="flex-end">
          <Button size="xs" variant="default" onClick={onClose}>
            Close
          </Button>
        </Group>
      </Stack>
    </Modal>
  );
}

/** What the lookup dialog shows in place of a subscription: the wait, the failure, or the subscription not being there. */
function LookupState({
  subscriptionName,
  isPending,
  isError,
  error,
}: Readonly<{ subscriptionName: string; isPending: boolean; isError: boolean; error: Error | null }>) {
  if (isPending) return <LoadingState label="Looking the subscription up" blockSize="4rem" />;
  if (isError) {
    return (
      <ErrorState
        error={error}
        variant="inline"
        next="Rahdar could not read the subscription list just now. Try again in a moment."
      />
    );
  }
  return (
    <Notice tone="info" title="The subscription is not there">
      No subscription named {subscriptionName} is on this cluster now. It may have been deleted since this view was
      loaded.
    </Notice>
  );
}

/**
 * Pause or resume one subscription on every live node, from a menu. Reversible, so it is confirmed once;
 * its per-node outcome — including a partial one — stays until dismissed, because the menu that
 * started it is gone.
 */
function PauseSubscriptionDialog({
  opened,
  onClose,
  clusterId,
  subscription,
}: HostedDialogProps & { clusterId: string; subscription: SubscriptionView }) {
  const setPaused = useSetSubscriptionPaused(clusterId, subscription.name);
  const [outcome, setOutcome] = useState<LifecycleOutcomeView | null>(null);
  const paused = subscription.perNode.some((n) => n.paused);
  const verb = paused ? 'Resume' : 'Pause';

  return (
    <ConfirmDialog
      opened={opened}
      onClose={onClose}
      title={`${verb} ${subscription.name}`}
      confirmLabel={`${verb} subscription`}
      pending={setPaused.isPending}
      onConfirm={() => setPaused.mutate({ paused: !paused }, { onSuccess: setOutcome })}
      result={outcome ? <NodeOutcomeSummary outcome={outcome} /> : undefined}
      consequence={
        <Stack gap="md">
          <Text size="sm">
            {paused
              ? 'Resuming restarts delivery to this subscription’s consumers on every live node.'
              : 'Pausing stops delivery to this subscription’s consumers on every live node. Messages keep arriving and wait; nothing is lost. Resume to deliver them.'}
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

export function OpenSubscription({ clusterId, target }: Readonly<ActionProps<SubscriptionTarget>>) {
  const navigate = useNavigate();
  return (
    <ActionMenuItem
      label="Open details"
      icon={<IconListDetails size={16} aria-hidden />}
      href={subscriptionHref(clusterId, target.id)}
      onSelect={() => navigate({ to: `/clusters/${clusterId}/subscriptions`, search: { subscription: target.id } })}
    />
  );
}

export function CopySubscriptionName({ target, host }: Readonly<ActionProps<SubscriptionTarget>>) {
  return (
    <ActionMenuItem
      label="Copy subscription name"
      icon={<IconClipboard size={16} aria-hidden />}
      onSelect={() => host.copy(target.name, 'subscription name')}
    />
  );
}

export function CopySubscriptionLink({ clusterId, target, host }: Readonly<ActionProps<SubscriptionTarget>>) {
  return (
    <ActionMenuItem
      label="Copy link to this subscription"
      icon={<IconLink size={16} aria-hidden />}
      onSelect={() => host.copy(absoluteHref(subscriptionHref(clusterId, target.id)), 'link to the subscription')}
    />
  );
}

/** The menu entry: which of pause or resume applies, or both while it is not known. */
function pauseLabel(paused: boolean | undefined): string {
  if (paused === undefined) return 'Pause or resume…';
  return paused ? 'Resume…' : 'Pause…';
}

export function PauseResumeSubscription({ clusterId, target, host }: Readonly<ActionProps<SubscriptionTarget>>) {
  const { hidden, verdict: gate } = useWriteGate(
    clusterId,
    target,
    'pause',
    'subscription:pause',
    'Pause and resume subscriptions',
  );
  const { subscription } = useSubscription(clusterId, target.id, target.snapshot);
  const paused = subscription ? subscription.perNode.some((n) => n.paused) : undefined;
  const label = pauseLabel(paused);
  if (hidden) return null;
  return (
    <ActionMenuItem
      label={label}
      icon={paused ? <IconPlayerPlay size={16} aria-hidden /> : <IconPlayerPause size={16} aria-hidden />}
      verdict={gate}
      onExplain={(verdict) => host.explain(verdict, 'pausing this subscription')}
      onSelect={() =>
        host.open(WithSubscription, {
          clusterId,
          target,
          render: (q, dialog) => <PauseSubscriptionDialog {...dialog} clusterId={clusterId} subscription={q} />,
        })
      }
    />
  );
}

export function EditSubscription({ clusterId, target, host }: Readonly<ActionProps<SubscriptionTarget>>) {
  const { hidden, verdict: gate } = useWriteGate(
    clusterId,
    target,
    'update',
    'subscription:update',
    "Change a subscription's configuration",
  );
  if (hidden) return null;
  return (
    <ActionMenuItem
      label="Edit configuration…"
      icon={<IconEdit size={16} aria-hidden />}
      verdict={gate}
      onExplain={(verdict) => host.explain(verdict, 'editing this subscription')}
      onSelect={() =>
        host.open(WithSubscription, {
          clusterId,
          target,
          render: (q, dialog) => <EditSubscriptionForm {...dialog} clusterId={clusterId} subscription={q} />,
        })
      }
    />
  );
}

export function DeleteSubscription({ clusterId, target, host }: Readonly<ActionProps<SubscriptionTarget>>) {
  const { hidden, verdict: gate } = useWriteGate(
    clusterId,
    target,
    'delete',
    'subscription:delete',
    'Destroy subscriptions and destinations',
  );
  if (hidden) return null;
  return (
    <ActionMenuItem
      label="Delete subscription…"
      icon={<IconTrash size={16} aria-hidden />}
      tone="danger"
      verdict={gate}
      onExplain={(verdict) => host.explain(verdict, 'deleting this subscription')}
      onSelect={() =>
        host.open(WithSubscription, {
          clusterId,
          target,
          render: (q, dialog) => (
            <DeleteSubscriptionDialog {...dialog} clusterId={clusterId} subscription={q} onDeleted={() => {}} />
          ),
        })
      }
    />
  );
}

/** A subscription's name as a link to its detail (ADR-0107). */
export function SubscriptionLink({ clusterId, target, children }: Readonly<LinkProps<SubscriptionTarget>>) {
  return (
    <Link to={`/clusters/${clusterId}/subscriptions`} search={{ subscription: target.id }} className={linkClasses.link}>
      {children}
    </Link>
  );
}

/** On a consumer's row: the subscription it consumes from. */
export function ConsumerOpenSubscription({ clusterId, target }: Readonly<ActionProps<ConsumerTarget>>) {
  const navigate = useNavigate();
  const name = nameOf(target.subscription);
  return (
    <ActionMenuItem
      label={name ? `Open subscription ${name}` : 'Open its subscription'}
      icon={<IconListDetails size={16} aria-hidden />}
      verdict={name ? undefined : { kind: 'blocked', reason: 'The broker reported no subscription for this consumer.' }}
      href={target.subscription ? subscriptionHref(clusterId, target.subscription) : undefined}
      onSelect={() =>
        target.subscription &&
        navigate({ to: `/clusters/${clusterId}/subscriptions`, search: { subscription: target.subscription } })
      }
    />
  );
}

/** The subscriptions bound to an destination, from any row that names one. */
function OpenSubscriptionsOn({
  clusterId,
  destination,
}: Readonly<{ clusterId: string; destination: string | null | undefined }>) {
  const navigate = useNavigate();
  return (
    <ActionMenuItem
      label="Open its subscriptions"
      icon={<IconListDetails size={16} aria-hidden />}
      verdict={
        destination ? undefined : { kind: 'blocked', reason: 'The broker reported no destination for this row.' }
      }
      href={destination ? clusterHref(clusterId, 'subscriptions', { q: destination }) : undefined}
      onSelect={() =>
        destination && navigate({ to: `/clusters/${clusterId}/subscriptions`, search: { q: destination } })
      }
    />
  );
}

export function DestinationOpenSubscriptions({ clusterId, target }: Readonly<ActionProps<DestinationTarget>>) {
  return <OpenSubscriptionsOn clusterId={clusterId} destination={target.name} />;
}

export function ProducerOpenSubscriptions({ clusterId, target }: Readonly<ActionProps<ProducerTarget>>) {
  return <OpenSubscriptionsOn clusterId={clusterId} destination={nameOf(target.destination)} />;
}
