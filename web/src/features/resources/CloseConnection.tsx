import { useCallback, useEffect, useState } from 'react';
import { Button, Stack, Text } from '@mantine/core';

import {
  useCloseDestinationConsumers,
  useCloseNodeTarget,
  type ConnectionCloseKind,
  type ConnectionCloseView,
} from './api.ts';
import { CapabilityGate } from '../../ui/CapabilityGate.tsx';
import { ConfirmDialog } from '../../ui/ConfirmDialog.tsx';
import { DescriptionList } from '../../ui/DescriptionList.tsx';
import { ErrorState } from '../../ui/ErrorState.tsx';
import { NodeOutcomeSummary } from '../../ui/NodeOutcomeSummary.tsx';
import { Notice } from '../../ui/Notice.tsx';
import { elapsedLabel, toServerMs, useServerNow } from '../../kernel/time/time.ts';
import { focusBack } from '../../kernel/actions/focusBack.ts';
import { useCloseDestinationGate, useCloseGate } from './closeGates.ts';
import { useActionHost } from '../../kernel/actions/hostContext.ts';

/** What "already gone" is called in the outcome rows, instead of the lifecycle wording. */
const ALREADY_GONE = 'already gone';

type NodeKind = Exclude<ConnectionCloseKind, 'destination-consumers'>;

const NOUN: Record<NodeKind, string> = {
  connection: 'connection',
  channel: 'channel',
  consumer: "consumer's connection",
};

/**
 * The preview → result state both close dialogs share: a dry run when the dialog opens, then the
 * result, cleared on dismiss.
 */
function useClosePreview(close: ReturnType<typeof useCloseDestinationConsumers>, opened: boolean, onClose: () => void) {
  const [preview, setPreview] = useState<ConnectionCloseView | null>(null);
  const [result, setResult] = useState<ConnectionCloseView | null>(null);
  const [previewFailed, setPreviewFailed] = useState<Error | null>(null);
  const { mutate } = close;

  const start = useCallback(() => {
    setPreview(null);
    setResult(null);
    setPreviewFailed(null);
    mutate({ dryRun: true }, { onSuccess: setPreview, onError: setPreviewFailed });
  }, [mutate]);

  // The estimate is what the operator needs in order to decide, so it is taken when the dialog opens.
  useEffect(() => {
    if (opened) start();
  }, [opened, start]);

  const dismiss = () => {
    setPreview(null);
    setResult(null);
    setPreviewFailed(null);
    close.reset();
    onClose();
  };

  return { preview, result, setResult, previewFailed, dismiss };
}

/**
 * The row action on the connections, channels and consumers views (ADR-0057).
 *
 * <p>The finding and the verb it implies sit on the same row: a consumer the
 * broker has marked slow is one click from the disconnect, rather than a change
 * of tool. What is closed is stated before the confirmation can be armed, and it
 * is confirmed against a name a human recognises — the client id, or the remote
 * destination — never the opaque connection id an operator cannot check.
 */
export function CloseConnectionAction({
  clusterId,
  kind,
  nodeId,
  nodeName,
  targetId,
  rowLabel,
  /** When the row on screen was fetched, in epoch ms — the action depends on it. */
  fetchedAt,
}: Readonly<{
  clusterId: string;
  kind: NodeKind;
  nodeId: string;
  nodeName: string;
  targetId: string;
  /** How the row names itself, for the trigger's accessible name. */
  rowLabel: string;
  fetchedAt: number | null;
}>) {
  const gate = useCloseGate(clusterId, kind, targetId);
  const host = useActionHost();

  return (
    <CapabilityGate verdict={gate} what={`closing this ${NOUN[kind]}`}>
      <Button
        size="compact-xs"
        variant="default"
        disabled={gate.kind === 'blocked'}
        // Named, not an icon alone: a row of identical glyphs tells a screen
        // reader nothing about which connection it is about to disconnect.
        aria-label={`Close the ${NOUN[kind]} for ${rowLabel}`}
        // Hosted outside the grid (ADR-0107): a close that succeeds removes this row on the next
        // refresh, and a dialog mounted in the row would take its outcome with it.
        onClick={(e) =>
          host.open(
            CloseDialog,
            { clusterId, kind, nodeId, nodeName, targetId, fetchedAt },
            { restoreFocus: focusBack(e.currentTarget) },
          )
        }
      >
        Close
      </Button>
    </CapabilityGate>
  );
}

/**
 * The destructive flow. It opens on a preview, so the typed confirmation is
 * armed only once the operator has been shown who is about to be disconnected
 * and what happens to the messages that client is holding.
 *
 * <p>One dialog carries every state. While the target is being read, or could not be read, the
 * confirmation says why it cannot be armed; once the target is known it shows it and asks for its
 * name; once the close has settled, the outcome replaces the confirmation.
 */
export function CloseDialog({
  clusterId,
  kind,
  nodeId,
  nodeName,
  targetId,
  fetchedAt,
  opened,
  onClose,
}: Readonly<{
  clusterId: string;
  kind: NodeKind;
  nodeId: string;
  nodeName: string;
  targetId: string;
  fetchedAt: number | null;
  opened: boolean;
  onClose: () => void;
}>) {
  const close = useCloseNodeTarget(clusterId, nodeId, targetId);
  const now = useServerNow();

  const { preview, result, setResult, previewFailed, dismiss } = useClosePreview(close, opened, onClose);

  const settled = result ?? (preview?.alreadyGone ? preview : null);
  const target = preview?.target ?? null;
  const title = `Close this ${NOUN[kind]}`;
  // Without a target there is nothing to name, so the close is not offered: it is stated, not hidden.
  let blocked: string | undefined;
  if (!target) {
    blocked = previewFailed
      ? 'Rahdar cannot tell you what this would disconnect, so the close is not offered until the read succeeds.'
      : 'Reading what this would disconnect…';
  }

  return (
    <ConfirmDialog
      opened={opened}
      onClose={dismiss}
      title={title}
      tone="danger"
      confirmLabel={title}
      // Only the close itself locks the dialog; the read before it can always be walked away from.
      pending={close.isPending && preview !== null}
      blocked={blocked}
      onConfirm={() => close.mutate({}, { onSuccess: setResult })}
      result={settled ? <SettledOutcome settled={settled} kind={kind} /> : undefined}
      consequence={
        <Stack gap="md">
          <Text size="sm">
            This disconnects a running application from {nodeName}. It cannot be undone from here — a healthy client
            will reconnect on its own, and a wedged one will not.
          </Text>
          {/* The action depends on how current the row is, so the age is stated
              where the decision is made rather than only in the header. */}
          {fetchedAt && target && !settled ? (
            <Text size="xs" c="dimmed">
              This row was read {elapsedLabel(now - toServerMs(fetchedAt))} ago. The check below is taken now, against
              the broker.
            </Text>
          ) : null}
          {/* An unavailable estimate is stated, never omitted: an absent number
              reads as zero, which is the most dangerous thing to infer here. */}
          <div aria-live="polite">
            {previewFailed ? (
              <ErrorState variant="inline" error={previewFailed} next="The target could not be read." />
            ) : null}
          </div>
          {target && !settled ? <TargetSummary target={target} nodeName={nodeName} /> : null}
          {close.isError && !previewFailed ? <ErrorState error={close.error} variant="inline" /> : null}
        </Stack>
      }
    />
  );
}

/** The outcome once the close is settled: already gone, or the per-node summary. */
function SettledOutcome({ settled, kind }: Readonly<{ settled: ConnectionCloseView; kind: NodeKind }>) {
  if (settled.alreadyGone) {
    // The sentence is the whole outcome. A per-node summary beside it would
    // add nothing and read as a second, different verdict.
    return (
      <Text size="sm">
        Nothing to close — this {NOUN[kind]} had already gone. That is the state you asked for, so nothing was done and
        nothing failed.
      </Text>
    );
  }
  return (
    <NodeOutcomeSummary
      outcome={settled.outcome}
      alreadyLabel={ALREADY_GONE}
      countNoun="in-flight message"
      verbFuture="would return"
      verbPast="returned"
      destructive
    />
  );
}

/** Who is about to be disconnected, and what it costs the messages they hold. */
function TargetSummary({
  target,
  nodeName,
}: Readonly<{ target: NonNullable<ConnectionCloseView['target']>; nodeName: string }>) {
  return (
    <Stack gap="xs">
      <DescriptionList
        label="What this closes"
        items={[
          { term: 'Client id', value: target.clientId || 'none reported' },
          { term: 'Remote address', value: target.remoteAddress || 'not reported' },
          { term: 'User', value: target.user || 'none' },
          { term: 'Protocol', value: target.protocol || 'unknown' },
          { term: 'Node', value: nodeName },
          { term: 'Channels closed with it', value: target.sessionCount.toLocaleString() },
          { term: 'Consumers closed with it', value: target.consumerCount.toLocaleString() },
        ]}
      />

      {/* Stated before the confirmation, never discovered afterwards from a DLQ. */}
      <Notice tone="info" title="Messages in flight return to their subscription">
        {target.messagesInTransit == null ? (
          <>
            This broker did not report how many messages this client is holding. Any that are in flight return to their
            subscription with an increased delivery count, which can push a message past its maximum delivery attempts
            and into the dead letters.
          </>
        ) : (
          <>
            {target.messagesInTransit.toLocaleString()} in-flight message
            {target.messagesInTransit === 1 ? '' : 's'} will return to their subscription with an increased delivery
            count. A message already near its maximum delivery attempts can be moved to the dead letters by that
            increase.
          </>
        )}
      </Notice>
    </Stack>
  );
}

/** The destinations view's row action: the trigger and its gate, around the dialog below. */
export function CloseDestinationConsumersAction({
  clusterId,
  destination,
  resourceKind,
}: Readonly<{ clusterId: string; destination: string; resourceKind?: string }>) {
  const gate = useCloseDestinationGate(clusterId, resourceKind);
  const host = useActionHost();

  return (
    <CapabilityGate verdict={gate} what={`closing every consumer on ${destination}`}>
      <Button
        size="compact-xs"
        variant="default"
        disabled={gate.kind === 'blocked'}
        aria-label={`Close consumers on ${destination}`}
        onClick={(e) =>
          host.open(CloseDestinationConsumers, { clusterId, destination }, { restoreFocus: focusBack(e.currentTarget) })
        }
      >
        Close consumers
      </Button>
    </CapabilityGate>
  );
}

/**
 * Closing every consumer connection bound to an destination, across the cluster.
 *
 * <p>The one close with an unbounded blast radius, so it is previewed per node
 * and checked against the bulk cap before it can be armed — a disconnect is not
 * exempt from the cap because nothing is deleted: every consumer it closes hands
 * its in-flight messages back to a subscription.
 */
export function CloseDestinationConsumers({
  clusterId,
  destination,
  opened,
  onClose,
}: Readonly<{
  clusterId: string;
  destination: string;
  opened: boolean;
  onClose: () => void;
}>) {
  const close = useCloseDestinationConsumers(clusterId, destination);
  const { preview, result, setResult, previewFailed, dismiss } = useClosePreview(close, opened, onClose);

  const overCap = preview?.outcome.overCap ?? false;
  const title = `Close every consumer on ${destination}`;

  return (
    <ConfirmDialog
      opened={opened}
      onClose={dismiss}
      title={result ? `Result of closing the consumers on ${destination}` : title}
      tone="danger"
      confirmLabel={overCap ? 'Close them anyway, over the cap' : 'Close these consumers'}
      // Also locked while the count is being taken, so the typed name cannot arm a close that has
      // not been shown how many consumers it reaches.
      pending={close.isPending}
      onConfirm={() => close.mutate({ override: overCap }, { onSuccess: setResult })}
      result={
        result ? (
          <NodeOutcomeSummary
            outcome={result.outcome}
            destructive
            alreadyLabel="no consumers were bound"
            countNoun="consumer"
            verbFuture="would close"
            verbPast="closed"
          />
        ) : undefined
      }
      consequence={
        result ? null : (
          <DestinationConsequence
            destination={destination}
            pending={close.isPending}
            preview={preview}
            previewFailed={previewFailed}
            closeError={close.isError ? close.error : null}
          />
        )
      }
    />
  );
}

/** What closing every consumer on an destination does, as the confirmation states it: the count, the cap and any failure. */
function DestinationConsequence({
  destination,
  pending,
  preview,
  previewFailed,
  closeError,
}: Readonly<{
  destination: string;
  pending: boolean;
  preview: ConnectionCloseView | null;
  previewFailed: Error | null;
  closeError: Error | null;
}>) {
  return (
    <Stack gap="md">
      <Text size="sm">
        This disconnects every application consuming from {destination}, on every live node. The messages those
        consumers hold return to their subscriptions with an increased delivery count.
      </Text>

      <div aria-live="polite">
        {pending && !preview ? (
          <Text size="sm" c="dimmed">
            Counting the consumers on each node…
          </Text>
        ) : null}

        {previewFailed ? (
          <ErrorState
            variant="inline"
            error={previewFailed}
            next="The count could not be taken. The close can still proceed, but Rahdar cannot tell you how many consumers it would disconnect."
          />
        ) : null}

        {preview ? (
          <NodeOutcomeSummary
            outcome={preview.outcome}
            destructive
            alreadyLabel={ALREADY_GONE}
            countNoun="consumer"
            verbFuture="would close"
            verbPast="closed"
          />
        ) : null}
      </div>

      {preview?.outcome.overCap ? (
        <Notice tone="warning" title="Over the safety cap">
          This would disconnect {preview.outcome.totalAffected.toLocaleString()} consumers, over the cap of{' '}
          {preview.outcome.cap.toLocaleString()}. Confirming will override the cap for this operation, and the override
          is recorded in the audit log.
        </Notice>
      ) : null}

      {closeError && !previewFailed ? <ErrorState error={closeError} variant="inline" /> : null}
    </Stack>
  );
}
