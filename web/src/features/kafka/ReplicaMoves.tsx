import { useEffect, useId, useMemo, useRef, useState } from 'react';
import { Button, Group, NumberInput, Stack, Text, TextInput } from '@mantine/core';

import { useCan } from '../../kernel/auth/useCan.ts';
import { ApiError } from '../../kernel/api/request.ts';
import { CapabilityGate } from '../../ui/CapabilityGate.tsx';
import type { GateVerdict } from '../../ui/capabilityGate.ts';
import { ConfirmDialog } from '../../ui/ConfirmDialog.tsx';
import { DescriptionList } from '../../ui/DescriptionList.tsx';
import { ErrorState } from '../../ui/ErrorState.tsx';
import { LoadingState } from '../../ui/LoadingState.tsx';
import { notify, type ActionVerb } from '../../ui/notify.ts';
import { Section } from '../../ui/Section.tsx';
import { StatusBadge } from '../../ui/StatusBadge.tsx';
import { DataTable, type Column } from '../../ui/table/index.ts';
import {
  factorProblem,
  parseReplicas,
  THROTTLE_PROBLEM,
  useCancelReassignment,
  useElectLeaders,
  usePreviewCancel,
  usePreviewElection,
  usePreviewReassign,
  useReassign,
  useReassignments,
  useReplicaSuggestion,
  wholeThrottle,
  type CancelPreview,
  type ElectionPreview,
  type PartitionRow,
  type ReassignPreview,
  type ReassignmentRow,
  type ReplicaSuggestion,
} from './placement.ts';

const ELECT: ActionVerb = { verb: 'Elect', past: 'Elected', progressive: 'Electing' };
const REASSIGN: ActionVerb = { verb: 'Reassign', past: 'Reassigned', progressive: 'Reassigning' };
const CANCEL: ActionVerb = { verb: 'Cancel', past: 'Cancelled', progressive: 'Cancelling' };

const UPDATE_REASON =
  'You do not have the destination:update permission on this cluster. An administrator can grant it in Settings → Roles.';

function message(error: unknown): string {
  return error instanceof ApiError ? error.message : 'The cluster could not be reached.';
}

function blockedMessage(previewError: unknown, applyError: unknown): string | undefined {
  if (previewError) return message(previewError);
  if (applyError) return message(applyError);
  return undefined;
}

/** Election, reassignment and cancel all need destination:update. Offered until the grants are known. */
function useReplicaGate(clusterId: string): GateVerdict {
  const { can, loading } = useCan();
  const permitted = loading || can('destination:update', clusterId);
  return useMemo(
    () =>
      permitted ? { kind: 'allowed' as const, uncertain: false } : { kind: 'blocked' as const, reason: UPDATE_REASON },
    [permitted],
  );
}

function selectionReason(count: number, one: boolean): string | undefined {
  if (count === 0) return 'Select a partition.';
  if (one && count !== 1) return 'Select one partition.';
  return undefined;
}

function ActionControl({
  label,
  what,
  gate,
  reason,
  onClick,
}: Readonly<{
  label: string;
  what: string;
  gate: GateVerdict;
  reason?: string;
  onClick: () => void;
}>) {
  const reasonId = useId();
  return (
    <Stack gap={4} align="flex-start">
      <CapabilityGate verdict={gate} what={what}>
        <Button
          type="button"
          variant="default"
          disabled={Boolean(reason)}
          aria-describedby={reason ? reasonId : undefined}
          onClick={onClick}
        >
          {label}
        </Button>
      </CapabilityGate>
      {reason ? (
        <Text id={reasonId} size="xs">
          {reason}
        </Text>
      ) : null}
    </Stack>
  );
}

/** Elect and reassign, both shown when they cannot be used, with the reason beside them. */
export function ReplicationActions({ clusterId, chosen }: Readonly<{ clusterId: string; chosen: PartitionRow[] }>) {
  const gate = useReplicaGate(clusterId);
  const [electing, setElecting] = useState<PartitionRow[] | null>(null);
  const [moving, setMoving] = useState<PartitionRow | null>(null);
  const denied = gate.kind === 'blocked' ? gate.reason : undefined;
  const electReason = denied ?? selectionReason(chosen.length, false);
  const reassignReason = denied ?? selectionReason(chosen.length, true);
  return (
    <>
      <Group align="flex-start" gap="lg">
        <ActionControl
          label="Elect preferred leaders"
          what="electing preferred leaders"
          gate={gate}
          reason={electReason}
          onClick={() => {
            if (!electReason) setElecting([...chosen]);
          }}
        />
        <ActionControl
          label="Reassign partition"
          what="reassigning a partition"
          gate={gate}
          reason={reassignReason}
          onClick={() => {
            const row = chosen[0];
            if (!reassignReason && row) setMoving(row);
          }}
        />
      </Group>
      {electing ? <ElectDialog clusterId={clusterId} rows={electing} onClose={() => setElecting(null)} /> : null}
      {moving ? <ReassignDialog clusterId={clusterId} row={moving} onClose={() => setMoving(null)} /> : null}
    </>
  );
}

function settle(error: unknown, action: ActionVerb, subject: string, onHeld: () => void) {
  if (error instanceof ApiError && error.status === 412) return;
  notify.settle(error, {
    action,
    subject,
    cause: message(error),
    next: 'Check the audit log for what ran, then try again.',
    onHeld,
  });
}

function ElectDialog({
  clusterId,
  rows,
  onClose,
}: Readonly<{ clusterId: string; rows: PartitionRow[]; onClose: () => void }>) {
  const preview = usePreviewElection(clusterId);
  const apply = useElectLeaders(clusterId);
  const [planned, setPlanned] = useState<ElectionPreview | null>(null);
  const run = useRef(preview.mutate);
  run.current = preview.mutate;
  const selection = useMemo(() => rows.map((row) => ({ topic: row.topic, partition: row.partition })), [rows]);
  useEffect(() => {
    run.current({ partitions: selection }, { onSuccess: setPlanned });
  }, [selection]);
  const blocked = blockedMessage(preview.error, apply.error);
  const only = rows.length === 1 ? rows[0] : undefined;
  const confirmLabel = only
    ? `Elect the preferred leader of ${only.topic} partition ${only.partition}`
    : `Elect preferred leaders of ${rows.length} partitions`;
  return (
    <ConfirmDialog
      opened
      tone="danger"
      title="Elect preferred leaders"
      confirmLabel={confirmLabel}
      pending={preview.isPending || apply.isPending}
      blocked={blocked ?? (planned ? undefined : 'Loading the preview.')}
      onClose={onClose}
      onConfirm={() => {
        if (!planned) return;
        apply.mutate(
          { partitions: selection, etag: planned.etag },
          {
            onSuccess: () => {
              notify.succeeded({ action: ELECT, subject: 'preferred leaders' });
              onClose();
            },
            onError: (error) => settle(error, ELECT, 'preferred leaders', onClose),
          },
        );
      }}
      consequence={
        <Stack gap="sm">
          <Text size="sm">There is no unclean election. Only a preferred leader can be elected here.</Text>
          {planned ? (
            <>
              <DescriptionList
                label="Preferred leader election"
                columns={2}
                items={[
                  { term: 'Would change', value: String(planned.wouldChange) },
                  { term: 'Already preferred', value: String(planned.alreadyPreferred) },
                ]}
              />
              <Text size="sm">{planned.note}</Text>
            </>
          ) : null}
        </Stack>
      }
    />
  );
}

function bytesWords(bytes: number | null, unavailable: string | null): string {
  if (bytes != null) return `${bytes.toLocaleString()} bytes`;
  return unavailable ?? 'Not reported';
}

function ReassignDialog({
  clusterId,
  row,
  onClose,
}: Readonly<{ clusterId: string; row: PartitionRow; onClose: () => void }>) {
  const suggest = useReplicaSuggestion(clusterId, row.topic, row.partition);
  const preview = usePreviewReassign(clusterId, row.topic, row.partition);
  const apply = useReassign(clusterId, row.topic, row.partition);
  const [replicasText, setReplicasText] = useState(row.replicas.join(', '));
  const [beforeSuggestion, setBeforeSuggestion] = useState<string | null>(null);
  const [suggestion, setSuggestion] = useState<ReplicaSuggestion | null>(null);
  const [throttle, setThrottle] = useState<number | string>('');
  const [replicasTouched, setReplicasTouched] = useState(false);
  const [throttleTouched, setThrottleTouched] = useState(false);
  const [planned, setPlanned] = useState<ReassignPreview | null>(null);
  const parsed = parseReplicas(replicasText);
  const throttleValue = wholeThrottle(throttle);
  const replicaError = replicasTouched && 'error' in parsed ? parsed.error : undefined;
  const throttleError = throttleTouched && throttleValue == null ? THROTTLE_PROBLEM : undefined;
  let blocked: string | undefined;
  if ('error' in parsed) blocked = parsed.error;
  else if (throttleValue == null) blocked = THROTTLE_PROBLEM;
  else if (preview.error) blocked = message(preview.error);
  else if (apply.error) blocked = message(apply.error);
  else if (!planned) blocked = 'Preview the reassignment first.';
  else blocked = factorProblem(planned.replicationFactor, planned.minInSyncReplicas);

  const clearPlan = () => {
    setPlanned(null);
    preview.reset();
    apply.reset();
  };
  const askPreview = () => {
    setReplicasTouched(true);
    setThrottleTouched(true);
    if ('error' in parsed || throttleValue == null) return;
    preview.mutate({ replicas: parsed.replicas, throttleBytesPerSecond: throttleValue }, { onSuccess: setPlanned });
  };
  return (
    <ConfirmDialog
      opened
      tone="danger"
      align="top"
      size="lg"
      title={`Reassign partition ${row.partition} of ${row.topic}`}
      confirmLabel={`Reassign partition ${row.partition} of ${row.topic}`}
      pending={apply.isPending}
      blocked={blocked}
      onClose={onClose}
      onConfirm={() => {
        if (!planned || 'error' in parsed || throttleValue == null) return;
        if (factorProblem(planned.replicationFactor, planned.minInSyncReplicas)) return;
        apply.mutate(
          { replicas: parsed.replicas, throttleBytesPerSecond: throttleValue, etag: planned.etag },
          {
            onSuccess: () => {
              notify.succeeded({ action: REASSIGN, subject: `partition ${row.partition} of ${row.topic}` });
              onClose();
            },
            onError: (error) => settle(error, REASSIGN, `partition ${row.partition} of ${row.topic}`, onClose),
          },
        );
      }}
      consequence={
        <Stack gap="sm">
          <Text size="sm">
            Starts from the current replicas. Nothing is sent until you hold the confirmation. Closing this, or
            dismissing a suggestion, sends nothing.
          </Text>
          <TextInput
            label="Replicas"
            description="Broker numbers, in order."
            value={replicasText}
            error={replicaError}
            onBlur={() => setReplicasTouched(true)}
            onChange={(event) => {
              setReplicasText(event.currentTarget.value);
              setSuggestion(null);
              clearPlan();
            }}
          />
          <Group gap="xs">
            <Button
              type="button"
              variant="default"
              loading={suggest.isPending}
              onClick={() => {
                setBeforeSuggestion(replicasText);
                suggest.mutate(undefined, {
                  onSuccess: (value) => {
                    setSuggestion(value);
                    setReplicasText(value.replicas.join(', '));
                    clearPlan();
                  },
                });
              }}
            >
              Suggest placement
            </Button>
            {suggestion ? (
              <Button
                type="button"
                variant="subtle"
                onClick={() => {
                  if (beforeSuggestion != null) setReplicasText(beforeSuggestion);
                  setSuggestion(null);
                  setBeforeSuggestion(null);
                  suggest.reset();
                  clearPlan();
                }}
              >
                Dismiss suggestion
              </Button>
            ) : null}
          </Group>
          {suggestion ? (
            <Stack gap={4}>
              <StatusBadge>{suggestion.label}</StatusBadge>
              <Text size="sm">{suggestion.note}</Text>
            </Stack>
          ) : null}
          {suggest.error ? <Text size="sm">{message(suggest.error)}</Text> : null}
          <NumberInput
            label="Throttle"
            description="Bytes per second. A positive whole number is required."
            allowDecimal={false}
            clampBehavior="none"
            min={1}
            value={throttle}
            error={throttleError}
            onBlur={() => setThrottleTouched(true)}
            onChange={(value) => {
              setThrottle(value);
              clearPlan();
            }}
          />
          <Button type="button" variant="default" loading={preview.isPending} onClick={askPreview}>
            Preview reassignment
          </Button>
          {planned ? <ReassignFacts planned={planned} /> : null}
        </Stack>
      }
    />
  );
}

function ReassignFacts({ planned }: Readonly<{ planned: ReassignPreview }>) {
  return (
    <DescriptionList
      label="Reassignment preview"
      columns={2}
      items={[
        { term: 'Current replicas', value: planned.currentReplicas.join(', ') },
        { term: 'New replicas', value: planned.replicas.join(', ') },
        { term: 'Current replication factor', value: String(planned.currentReplicationFactor) },
        { term: 'Replication factor', value: String(planned.replicationFactor) },
        { term: 'min.insync.replicas', value: String(planned.minInSyncReplicas) },
        { term: 'Bytes', value: bytesWords(planned.bytes, planned.bytesUnavailable) },
        {
          term: 'Duration',
          value: planned.duration ?? planned.durationUnavailable ?? 'Not reported',
        },
        { term: 'Throttle outcome', value: planned.throttleOutcome },
      ]}
    />
  );
}

const where = (row: ReassignmentRow) => `${row.topic} partition ${row.partition}`;

function reassignmentColumns(gate: GateVerdict, onCancel: (row: ReassignmentRow) => void): Column<ReassignmentRow>[] {
  return [
    { id: 'topic', header: 'Topic', accessor: (row) => row.topic, kind: 'identifier', priority: 'essential' },
    { id: 'partition', header: 'Partition', accessor: (row) => row.partition, kind: 'number', priority: 'essential' },
    {
      id: 'reassignment',
      header: 'Reassignment',
      accessor: (row) => row.reassignment ?? 'Not listed by the cluster',
      kind: 'text',
      wrap: true,
      priority: 'essential',
    },
    {
      id: 'started',
      header: 'Started',
      accessor: (row) => (row.startedHere ? 'Started here' : 'Started elsewhere'),
      kind: 'text',
      priority: 'high',
    },
    {
      id: 'cancel',
      header: 'Change',
      accessor: () => '',
      cell: (row) => (
        <CapabilityGate verdict={gate} what={`cancelling the move of ${where(row)}`}>
          <Button
            type="button"
            size="compact-xs"
            variant="subtle"
            disabled={gate.kind === 'blocked'}
            aria-label={`Cancel the move of ${where(row)}`}
            onClick={() => onCancel(row)}
          >
            Cancel move
          </Button>
        </CapabilityGate>
      ),
      kind: 'status',
      priority: 'essential',
    },
  ];
}

/** Moves the cluster still lists, or that this product is waiting to put a throttle back for. */
export function Reassignments({ clusterId }: Readonly<{ clusterId: string }>) {
  const gate = useReplicaGate(clusterId);
  const { reach, loading } = useCan();
  const read = reach('destination:read', clusterId);
  const query = useReassignments(clusterId, read !== 'none');
  const [cancelling, setCancelling] = useState<ReassignmentRow | null>(null);
  const columns = useMemo(() => reassignmentColumns(gate, setCancelling), [gate]);
  let body;
  if (!loading && read === 'none') {
    body = <ErrorState error={new ApiError(403, { title: 'Forbidden', permission: 'destination:read' })} />;
  } else if (query.isError) {
    body = <ErrorState error={query.error} onRetry={() => void query.refetch()} />;
  } else if (!query.data) {
    body = <LoadingState label="Loading replica moves" blockSize="6rem" />;
  } else if (query.data.items.length === 0) {
    body = <Text size="sm">No replica move is in progress.</Text>;
  } else {
    body = (
      <DataTable
        variant="static"
        label="Replica moves in progress"
        storageKey="kafka.reassignments"
        columns={columns}
        data={query.data.items}
        rowKey={(row) => `${row.topic}-${row.partition}`}
        height={{ maxRows: 8 }}
        empty={null}
      />
    );
  }
  return (
    <Section title="Replica moves in progress">
      {body}
      {cancelling ? <CancelDialog clusterId={clusterId} row={cancelling} onClose={() => setCancelling(null)} /> : null}
    </Section>
  );
}

function CancelDialog({
  clusterId,
  row,
  onClose,
}: Readonly<{ clusterId: string; row: ReassignmentRow; onClose: () => void }>) {
  const preview = usePreviewCancel(clusterId);
  const apply = useCancelReassignment(clusterId);
  const [planned, setPlanned] = useState<CancelPreview | null>(null);
  const run = useRef(preview.mutate);
  run.current = preview.mutate;
  const selection = useMemo(() => [{ topic: row.topic, partition: row.partition }], [row]);
  useEffect(() => {
    run.current({ partitions: selection }, { onSuccess: setPlanned });
  }, [selection]);
  const blocked = blockedMessage(preview.error, apply.error);
  return (
    <ConfirmDialog
      opened
      tone="danger"
      title={`Cancel the move of ${where(row)}`}
      confirmLabel={`Cancel the move of ${where(row)}`}
      dismissLabel="Keep the move"
      pending={preview.isPending || apply.isPending}
      blocked={blocked ?? (planned ? undefined : 'Loading the preview.')}
      onClose={onClose}
      onConfirm={() => {
        if (!planned) return;
        apply.mutate(
          { partitions: selection, etag: planned.etag },
          {
            onSuccess: () => {
              notify.succeeded({ action: CANCEL, subject: `the move of ${where(row)}` });
              onClose();
            },
            onError: (error) => settle(error, CANCEL, `the move of ${where(row)}`, onClose),
          },
        );
      }}
      consequence={
        <Stack gap="xs">
          {planned ? (
            <>
              <Text size="sm">{planned.note}</Text>
              {planned.partitions.map((part) => (
                <Text key={`${part.topic}-${part.partition}`} size="sm">
                  {part.topic} partition {part.partition}: {part.currentReplicas.join(', ') || 'none'} returns to{' '}
                  {part.replicas.join(', ') || 'none'}.
                </Text>
              ))}
            </>
          ) : (
            <Text size="sm">Loading the preview.</Text>
          )}
        </Stack>
      }
    />
  );
}
