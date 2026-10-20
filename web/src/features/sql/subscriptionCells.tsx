import { useState } from 'react';
import { Button, Stack, Switch, Text } from '@mantine/core';

import { ConfirmDialog } from '../../ui/ConfirmDialog.tsx';
import { ErrorState } from '../../ui/ErrorState.tsx';
import { notify, type ActionVerb } from '../../ui/notify.ts';
import { useDeleteIndexSubscription, useUpdateIndexSubscription, type SqlIndexSubscriptionView } from './api.ts';
import classes from './IndexSubscriptions.module.css';
import { bytes, when } from './subscriptionFormat.ts';

type CaptureNode = NonNullable<SqlIndexSubscriptionView['nodes']>[number];

const CAPTURE_STATE_WORDS: Record<string, string> = {
  DEGRADED: 'capturing, losing messages',
  FAILED: 'not capturing',
};

function captureStateWords(node: CaptureNode): string {
  if (node.state === 'ACTIVE') return `capturing since ${when(node.capturedFrom)}`;
  return CAPTURE_STATE_WORDS[node.state ?? ''] ?? 'not reached yet';
}

/** How many messages a degraded node missed, when that can be said. */
function missedNote(node: CaptureNode, filterString: string | null | undefined): string {
  if (node.state === 'DEGRADED' && filterString) {
    return " · how many were missed is unavailable — a capture filter makes the broker's routed count incomparable with what was stored";
  }
  return node.droppedEstimate ? ` · about ${node.droppedEstimate.toLocaleString()} missed` : '';
}

/** Capture state on one node, in words. Colour is redundant emphasis, never the carrier. */
function CaptureNodes({ subscription }: Readonly<{ subscription: SqlIndexSubscriptionView }>) {
  const nodes = subscription.nodes ?? [];
  if (subscription.mode !== 'CAPTURE') return null;
  if (nodes.length === 0) {
    return (
      <Text size="xs" c="dimmed">
        No node has been reached yet. Capture is asserted on the next reconcile pass.
      </Text>
    );
  }
  return (
    <Stack gap={2}>
      {nodes.map((node) => (
        <Text key={node.nodeId} size="xs" c={node.state === 'ACTIVE' ? undefined : 'var(--as-warning)'}>
          {node.nodeName ?? node.nodeId}: {captureStateWords(node)}
          {missedNote(node, subscription.filterString)}
          {node.detail ? ` — ${node.detail}` : ''}
        </Text>
      ))}
    </Stack>
  );
}

/** What is not recorded, what is still being indexed, and what sampling cannot see. */
function SubscriptionNotes({ subscription }: Readonly<{ subscription: SqlIndexSubscriptionView }>) {
  return (
    <>
      {subscription.notCapturing ? (
        <Text size="xs" c="var(--as-warning)">
          Recording nothing — {subscription.notCapturing}
        </Text>
      ) : null}
      {subscription.backlogInProgress ? (
        <Text size="xs" c="dimmed">
          Still indexing the messages that were already on these subscriptions, a few pages per poll; until that
          finishes the index is not up to date.
        </Text>
      ) : null}
      {subscription.mode !== 'CAPTURE' ? (
        <Text size="xs" c="dimmed">
          Just sampling: a message consumed between two polls is never recorded. Capture everything to record all of
          them.
        </Text>
      ) : null}
    </>
  );
}

/** The subscriptions a subscription covers, since when, by whom, and what each node is doing about it. */
export function SubscriptionsCell({ subscription }: Readonly<{ subscription: SqlIndexSubscriptionView }>) {
  return (
    <Stack gap={2}>
      <Text size="sm" fw={600}>
        {subscription.subscriptionPattern}
      </Text>
      <Text size="xs" c="dimmed">
        {subscription.mode === 'CAPTURE' ? 'capturing' : 'sampling'} since {when(subscription.captureFrom)}
        {subscription.createdBy ? ` · created by ${subscription.createdBy}` : ''}
        {subscription.filterString ? ` · filter ${subscription.filterString}` : ''}
      </Text>
      <SubscriptionNotes subscription={subscription} />
      <CaptureNodes subscription={subscription} />
    </Stack>
  );
}

/** What a subscription holds, and how much of what it may. */
export function HeldCell({ subscription }: Readonly<{ subscription: SqlIndexSubscriptionView }>) {
  const held = subscription.messagesHeld ?? 0;
  return (
    <Stack gap={2}>
      <Text size="sm" className={classes.numeric}>
        {held.toLocaleString()} message{held === 1 ? '' : 's'}
      </Text>
      <Text size="xs" c="dimmed" className={classes.numeric}>
        {bytes(subscription.bytesHeld ?? 0)}
        {subscription.mode === 'CAPTURE' && subscription.maxBytes
          ? ` of ${bytes(subscription.maxBytes)} allowed`
          : ' of payload'}
        {subscription.oldestObservedAt ? ` · oldest ${when(subscription.oldestObservedAt)}` : ''}
      </Text>
    </Stack>
  );
}

const UPDATE: ActionVerb = { verb: 'Change', past: 'Changed', progressive: 'Changing' };

/** Pauses or resumes a subscription. */
export function StateCell({
  clusterId,
  subscription,
  canWrite,
}: Readonly<{ clusterId: string; subscription: SqlIndexSubscriptionView; canWrite: boolean }>) {
  const update = useUpdateIndexSubscription(clusterId);
  return (
    <Switch
      size="xs"
      label={subscription.enabled ? 'Capturing' : 'Paused'}
      checked={subscription.enabled ?? false}
      disabled={!canWrite || update.isPending}
      onChange={(e) =>
        update.mutate(
          { id: subscription.id ?? '', body: { enabled: e.currentTarget.checked } },
          {
            onError: (error) =>
              notify.settle(error, {
                action: UPDATE,
                subject: `index subscription ${subscription.subscriptionPattern}`,
                cause: error.message,
                next: 'Try the switch again.',
              }),
          },
        )
      }
    />
  );
}

const DELETE: ActionVerb = { verb: 'Delete', past: 'Deleted', progressive: 'Deleting' };

/**
 * Deletes a subscription, which destroys what it captured. The blast radius is stated before the button
 * can be armed, and the button is armed by typing the pattern.
 */
export function DeleteCell({
  clusterId,
  subscription,
  canWrite,
}: Readonly<{ clusterId: string; subscription: SqlIndexSubscriptionView; canWrite: boolean }>) {
  const remove = useDeleteIndexSubscription(clusterId);
  const [confirming, setConfirming] = useState(false);
  const pattern = subscription.subscriptionPattern ?? '';
  const held = subscription.messagesHeld ?? 0;

  return (
    <>
      <Button size="compact-xs" color="signal" variant="light" disabled={!canWrite} onClick={() => setConfirming(true)}>
        Delete
      </Button>
      <ConfirmDialog
        opened={confirming}
        onClose={() => {
          remove.reset();
          setConfirming(false);
        }}
        title={`Delete index subscription ${pattern}`}
        consequence={
          <Stack gap="xs">
            <Text size="sm">
              This deletes the subscription and the {held.toLocaleString()} captured message{held === 1 ? '' : 's'} it
              holds. They cannot be recovered — a consumed message cannot be observed a second time.
              {subscription.mode === 'CAPTURE'
                ? ' It also removes the divert, the capture subscription, the address setting and the security setting from every node that answers now; a node that is unreachable is cleaned on its next reconcile pass. Nothing else removes them.'
                : ''}
            </Text>
            {remove.isError ? <ErrorState variant="inline" error={remove.error} /> : null}
          </Stack>
        }
        confirmLabel="Delete and destroy captured messages"
        tone="danger"
        pending={remove.isPending}
        onConfirm={() =>
          remove.mutate(subscription.id ?? '', {
            onSuccess: (result) => {
              notify.succeeded({
                action: DELETE,
                subject: `index subscription ${pattern} — ${result.messagesDestroyed.toLocaleString()} captured messages destroyed`,
              });
              setConfirming(false);
            },
          })
        }
      />
    </>
  );
}
