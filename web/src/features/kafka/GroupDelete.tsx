import { useState } from 'react';
import { Button, Stack, Text } from '@mantine/core';
import { IconTrash } from '@tabler/icons-react';

import { useCan } from '../../kernel/auth/useCan.ts';
import { ApiError } from '../../kernel/api/request.ts';
import { CapabilityGate } from '../../ui/CapabilityGate.tsx';
import { gateFor, type GateVerdict } from '../../ui/capabilityGate.ts';
import { ConfirmDialog } from '../../ui/ConfirmDialog.tsx';
import { useDeleteGroup, usePreviewDeleteGroup, type GroupDetail, type TopicPreview } from './api.ts';

function message(error: unknown) {
  return error instanceof ApiError ? error.message : 'The cluster could not be reached.';
}

const REBALANCING = new Set([
  'PREPARING_REBALANCE',
  'PREPARINGREBALANCE',
  'COMPLETING_REBALANCE',
  'COMPLETINGREBALANCE',
  'ASSIGNING',
  'RECONCILING',
]);

/** Why delete stays visible and cannot be used, or that it can. */
export function groupDeleteVerdict(
  permitted: GateVerdict,
  group: GroupDetail | undefined,
  unavailable: boolean,
): GateVerdict {
  if (unavailable) return { kind: 'blocked', reason: 'The group could not be loaded.' };
  if (!group) return { kind: 'blocked', reason: 'Loading the group.' };
  if (group.readOnly) return { kind: 'blocked', reason: group.readOnly };
  if (group.type.toLowerCase() === 'streams') return { kind: 'blocked', reason: 'Streams owns this group.' };
  if (group.members.length > 0) {
    return { kind: 'blocked', reason: 'This group has members. Deleting it does not remove them.' };
  }
  if (REBALANCING.has(group.state.toUpperCase())) {
    return { kind: 'blocked', reason: 'This group is rebalancing.' };
  }
  return permitted;
}

/**
 * Delete an empty group. The control stays when it cannot be used and says why. Members are never removed
 * to make the delete possible.
 */
export function GroupDelete({
  clusterId,
  groupId,
  group,
  unavailable = false,
}: Readonly<{
  clusterId: string;
  groupId: string;
  group: GroupDetail | undefined;
  unavailable?: boolean;
}>) {
  const { can, loading } = useCan();
  const permitted = gateFor(can('subscription:delete', clusterId), 'Delete group', undefined, loading);
  const verdict = groupDeleteVerdict(permitted, group, unavailable);
  const preview = usePreviewDeleteGroup(clusterId);
  const remove = useDeleteGroup(clusterId);
  const [open, setOpen] = useState(false);
  const [planned, setPlanned] = useState<TopicPreview | null>(null);
  let blocked: string | undefined;
  if (verdict.kind === 'blocked') {
    blocked = verdict.reason;
  } else if (preview.error) {
    blocked = message(preview.error);
  } else if (!planned) {
    blocked = 'Loading the preview.';
  }

  return (
    <>
      <CapabilityGate verdict={verdict} what="deleting this group">
        <Button
          color="red"
          variant="light"
          disabled={verdict.kind === 'blocked'}
          leftSection={<IconTrash size="1rem" aria-hidden />}
          onClick={() => {
            setOpen(true);
            preview.mutate(groupId, { onSuccess: setPlanned });
          }}
        >
          Delete group
        </Button>
      </CapabilityGate>
      <ConfirmDialog
        opened={open}
        tone="danger"
        title={`Delete ${groupId}`}
        confirmLabel="Delete group"
        pending={preview.isPending || remove.isPending}
        blocked={blocked}
        onClose={() => {
          setOpen(false);
          setPlanned(null);
        }}
        onConfirm={() => {
          if (!planned) return;
          remove.mutate({ group: groupId, etag: planned.etag });
        }}
        consequence={
          <Stack gap="xs">
            {preview.isPending ? <Text size="sm">Loading the preview.</Text> : null}
            {planned ? <Text size="sm">{planned.note}</Text> : null}
            {remove.error ? <Text size="sm">{message(remove.error)}</Text> : null}
          </Stack>
        }
      />
    </>
  );
}
