import { useState } from 'react';
import { Button, Stack, Text } from '@mantine/core';

import type { ApiError } from '../../kernel/api/request.ts';
import { CapabilityGate } from '../../ui/CapabilityGate.tsx';
import { ConfirmDialog } from '../../ui/ConfirmDialog.tsx';
import { ErrorState } from '../../ui/ErrorState.tsx';
import { Notice } from '../../ui/Notice.tsx';
import { usePurgeQueue, type DryRunView } from './api.ts';
import { useMessageGate } from './gates.ts';
import { announceFailure, announceResult, messageCount } from './outcomes.ts';

/**
 * What the dialog says before the purge can be armed: the estimate, or that it could not be taken.
 * An unavailable estimate is stated, never omitted: an absent number reads as zero, and the operator
 * must know the blast radius is unknown. The cap is overridden only where the operator was told the
 * number it is being overridden for, never on an unknown depth.
 */
function Consequence({
  queueName,
  preview,
  failure,
}: Readonly<{ queueName: string; preview: DryRunView | null; failure: ApiError | null }>) {
  if (failure) {
    return (
      <Stack gap="sm">
        <Text size="sm">
          Rahdar could not take an estimate, so it cannot tell you how many messages purging {queueName} would destroy.
          The purge can still proceed. This cannot be undone. The broker's bulk safety cap still applies: if the depth
          turns out to be over it, the purge is refused.
        </Text>
        <ErrorState variant="inline" error={failure} />
      </Stack>
    );
  }
  if (!preview) return null;
  return (
    <Stack gap="sm">
      <Text size="sm">
        This will remove approximately {messageCount(preview.affectedCount)} from {queueName} (point-in-time estimate).
        This cannot be undone.
      </Text>
      {preview.overCap ? (
        <Notice tone="warning" title="Over the safety cap">
          This would remove {preview.affectedCount.toLocaleString()} messages, over the cap of{' '}
          {preview.cap.toLocaleString()}. Confirming will override the cap for this operation, and the override is
          recorded in the audit log.
        </Notice>
      ) : null}
    </Stack>
  );
}

/**
 * Purge the whole queue: the estimate first (the button is busy while it is taken), then a
 * confirmation that states it and arms only when the queue's name is typed.
 */
export function PurgeQueue({
  clusterId,
  queueName,
  node,
}: Readonly<{ clusterId: string; queueName: string; node?: string }>) {
  const purge = usePurgeQueue(clusterId, queueName);
  const [estimating, setEstimating] = useState(false);
  const [opened, setOpened] = useState(false);
  // The whole preview, not just its count: the cap and whether the estimate is over it decide both
  // what the dialog says and whether the purge may override it.
  const [preview, setPreview] = useState<DryRunView | null>(null);
  const [failure, setFailure] = useState<ApiError | null>(null);
  const overCap = preview?.overCap ?? false;
  const gate = useMessageGate(clusterId, 'subscription:purge', 'Purge queues', 'messageIo');

  const estimate = () => {
    setPreview(null);
    setFailure(null);
    setEstimating(true);
    purge.mutate(
      { node, dryRun: true },
      {
        onSuccess: (r) => setPreview('cap' in r ? r : null),
        onError: setFailure,
        onSettled: () => {
          setEstimating(false);
          setOpened(true);
        },
      },
    );
  };

  const confirm = () =>
    purge.mutate(
      { node, override: overCap },
      {
        onSuccess: (r) => {
          announceResult('purge', queueName, r, preview?.affectedCount ?? 0);
          setOpened(false);
        },
        onError: (e) => announceFailure('purge', `queue "${queueName}"`, e, () => setOpened(false)),
      },
    );

  return (
    <>
      <CapabilityGate verdict={gate} what="purging this queue">
        <Button variant="default" disabled={gate.kind === 'blocked'} loading={estimating} onClick={estimate}>
          Purge queue
        </Button>
      </CapabilityGate>
      <ConfirmDialog
        opened={opened}
        onClose={() => setOpened(false)}
        title={`Purge queue ${queueName}`}
        consequence={<Consequence queueName={queueName} preview={preview} failure={failure} />}
        confirmLabel={overCap ? 'Purge anyway, over the cap' : 'Purge queue'}
        tone="danger"
        pending={purge.isPending}
        onConfirm={confirm}
      />
    </>
  );
}
