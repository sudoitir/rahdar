import { useMemo, useState } from 'react';
import { Button, Checkbox, Group, Modal, Stack, Switch, Text } from '@mantine/core';
import { useNavigate } from '@tanstack/react-router';

import type { SubscriptionSelection } from '../../kernel/slots.ts';
import { HoldToConfirm } from '../../ui/HoldToConfirm.tsx';
import { EmptyState } from '../../ui/EmptyState.tsx';
import { ErrorState } from '../../ui/ErrorState.tsx';
import { LoadingState } from '../../ui/LoadingState.tsx';
import { notify } from '../../ui/notify.ts';
import { Section } from '../../ui/Section.tsx';
import { DataTable } from '../../ui/table/index.ts';
import {
  useBulkExecute,
  useBulkPreview,
  type BulkItemView,
  type BulkOperation,
  type BulkRunDetailView,
} from './api.ts';
import { useCluster } from '../clusters/index.ts';
import { previewColumns } from './columns.ts';
import { OPERATIONS, plural } from './words.ts';

const hasProblem = (i: BulkItemView) => i.status === 'REFUSED' || Boolean(i.warning);

const rowKey = (i: BulkItemView) => i.subscription;

const START = { verb: 'Start', past: 'Started', progressive: 'Starting' } as const;

/**
 * What the run would do, as one sentence an operator can check before arming it. A purge of subscriptions on a
 * partitioned log moves each group to the end of its log and deletes no record, and says so.
 */
function blastRadius(operation: BulkOperation, preview: BulkRunDetailView, moves: boolean): string {
  const { run, items } = preview;
  const op = OPERATIONS[operation];
  const acting = items.filter((i) => i.status !== 'REFUSED');
  const nodes = new Set(acting.flatMap((i) => i.nodes.map((n) => n.nodeId))).size;
  const scope = `${op.verb} ${plural(acting.length, 'subscription')} on ${plural(nodes, 'node')}`;
  if (!op.destructive) return `${scope}.`;
  if (moves) {
    const to = `${plural(acting.length, 'group')} to the end of the log`;
    if (run.estimate == null) {
      return `Moves ${to}, skipping a number of records that is unavailable. No record is deleted; the groups will not read the records they skip.`;
    }
    const records = plural(run.estimate, 'record');
    return `Moves ${to}, skipping ${run.estimateComplete ? '' : 'at least '}${records}. No record is deleted; the groups will not read the records they skip.`;
  }
  if (run.estimate == null || (acting.length > 0 && acting.every((i) => i.affected == null))) {
    return `${scope}. How many messages that destroys is unavailable. This cannot be undone.`;
  }
  const messages = plural(run.estimate, 'message');
  return run.estimateComplete
    ? `${scope}, destroying ${messages}. This cannot be undone.`
    : `${scope}, destroying at least ${messages}. This cannot be undone.`;
}

/** What the preview says while it reads, when it fails, and once it has a plan. */
function PreviewStatus({
  preview,
  operation,
  moves,
  unknown,
  refused,
  onRetry,
}: Readonly<{
  preview: ReturnType<typeof useBulkPreview>;
  operation: BulkOperation;
  moves: boolean;
  unknown: number;
  refused: number;
  onRetry: () => void;
}>) {
  const data = preview.data;
  return (
    <div aria-live="polite">
      {preview.isPending ? <LoadingState label="Reading what these subscriptions hold" blockSize="6rem" /> : null}
      {preview.isError ? (
        <ErrorState
          error={preview.error}
          actions={
            <Button size="xs" variant="default" onClick={onRetry}>
              Preview again
            </Button>
          }
        />
      ) : null}
      {data ? (
        <Stack gap="xs">
          <Text size="sm" fw={600}>
            {blastRadius(operation, data, moves)}
          </Text>
          {unknown > 0 ? (
            <Text size="sm">
              {plural(unknown, 'subscription')} {unknown === 1 ? 'has a figure' : 'have figures'} Rahdar does not know,
              because a node has not answered recently; the total is a floor.
            </Text>
          ) : null}
          {refused > 0 ? (
            <Text size="sm">
              {plural(refused, 'subscription')} {refused === 1 ? 'is' : 'are'} refused and will not be touched. The
              reason is beside each one below.
            </Text>
          ) : null}
        </Stack>
      ) : null}
    </div>
  );
}

/** How the run behaves on failure, and the control that starts it — confirmed by holding when it destroys. */
function RunControls({
  op,
  execute,
  continueOnFailure,
  onContinueOnFailure,
  overCap,
  override,
  confirmLabel,
  onStart,
  onRetry,
}: Readonly<{
  op: (typeof OPERATIONS)[BulkOperation];
  execute: ReturnType<typeof useBulkExecute>;
  continueOnFailure: boolean;
  onContinueOnFailure: (on: boolean) => void;
  overCap: boolean;
  override: boolean;
  confirmLabel: string;
  onStart: () => void;
  onRetry: () => void;
}>) {
  return (
    <>
      <Switch
        label="Continue past failures"
        description={
          continueOnFailure
            ? 'Every subscription is attempted, whatever happens to the ones before it.'
            : 'The run stops at the first subscription that fails, and the subscriptions after it are skipped.'
        }
        checked={continueOnFailure}
        onChange={(e) => onContinueOnFailure(e.currentTarget.checked)}
      />

      {execute.isError ? (
        <ErrorState
          error={execute.error}
          next="Nothing was run. Preview again to confirm the subscriptions as they are now."
          actions={
            <Button size="xs" variant="default" onClick={onRetry}>
              Preview again
            </Button>
          }
        />
      ) : null}

      {op.destructive ? (
        <HoldToConfirm
          label={confirmLabel}
          loading={execute.isPending}
          disabled={(overCap && !override) || execute.isPending}
          onConfirm={onStart}
        />
      ) : (
        <Group>
          <Button size="xs" loading={execute.isPending} onClick={onStart}>
            {confirmLabel}
          </Button>
        </Group>
      )}
    </>
  );
}

/** The frozen subscription set, optionally narrowed to the ones with a refusal or warning. */
function SubscriptionsInRun({
  destructive,
  shown,
  onlyProblems,
  onOnlyProblems,
}: Readonly<{
  destructive: boolean;
  shown: BulkItemView[];
  onlyProblems: boolean;
  onOnlyProblems: (on: boolean) => void;
}>) {
  const columns = useMemo(() => previewColumns(destructive), [destructive]);
  return (
    <Section
      title="The subscriptions in this run"
      headingLevel={3}
      actions={
        <Switch
          size="xs"
          label="Only subscriptions with a refusal or warning"
          checked={onlyProblems}
          onChange={(e) => onOnlyProblems(e.currentTarget.checked)}
        />
      }
    >
      <DataTable
        label="Subscriptions in this run"
        storageKey="bulk.preview"
        height={{ maxRows: 8 }}
        columns={columns}
        data={shown}
        rowKey={rowKey}
        empty={
          onlyProblems ? (
            <EmptyState
              kind="filtered"
              title="No subscription has a refusal or a warning"
              description="Every subscription in this run will act without a warning. Clear the filter to review the whole set."
              onClearFilters={() => onOnlyProblems(false)}
            />
          ) : (
            <EmptyState
              kind="empty"
              title="No subscription matched the selection"
              description="A run acts on the subscriptions the selection matched when this preview was taken. None matched, so there is nothing to run. Close this and change the selection."
            />
          )
        }
      />
    </Section>
  );
}

/**
 * Preview → confirm → execute for one bulk operation (ADR-0093). The preview freezes the subscription set
 * and states the blast radius; only that frozen plan can be confirmed, and a destroy is confirmed by
 * pressing and holding a button that names the action and the count. Once the run is accepted the operator is taken to it.
 */
export function BulkPreviewDialog({
  clusterId,
  operation,
  selection,
  opened,
  onClose,
  onStarted,
}: Readonly<{
  clusterId: string;
  operation: BulkOperation;
  selection: SubscriptionSelection;
  opened: boolean;
  onClose: () => void;
  onStarted: () => void;
}>) {
  const op = OPERATIONS[operation];
  const navigate = useNavigate();
  const preview = useBulkPreview(clusterId);
  const execute = useBulkExecute(clusterId);
  const [onlyProblems, setOnlyProblems] = useState(false);
  const [continueOnFailure, setContinueOnFailure] = useState(false);
  const [override, setOverride] = useState(false);

  const takePreview = () => {
    execute.reset();
    preview.mutate({
      operation,
      ids: selection.kind === 'ids' ? selection.ids : null,
      q: selection.kind === 'filter' ? selection.q : null,
      // A bulk delete refuses a subscription with consumers; closing them is not offered in bulk yet.
      disconnectConsumers: false,
    });
  };

  const close = () => {
    preview.reset();
    execute.reset();
    setOnlyProblems(false);
    setContinueOnFailure(false);
    setOverride(false);
    onClose();
  };

  const data = preview.data;
  const acting = data?.items.filter((i) => i.status !== 'REFUSED') ?? [];
  const refused = (data?.items.length ?? 0) - acting.length;
  const unknown = op.destructive ? acting.filter((i) => i.affected == null).length : 0;
  const overCap = data?.run.overCap ?? false;
  const shown = onlyProblems ? (data?.items.filter(hasProblem) ?? []) : (data?.items ?? []);
  const cluster = useCluster(clusterId);
  // A purge of a partitioned log moves the groups to its end: nothing is destroyed, and the cap does not apply to it.
  const moves =
    operation === 'PURGE' &&
    acting.length > 0 &&
    acting.every((i) => cluster.data?.kinds?.find((k) => k.id === i.kind)?.partitioned === true);
  const confirmLabel = moves
    ? `Skip ${data?.run.estimate == null ? 'records' : plural(data.run.estimate, 'record')} for ${plural(acting.length, 'group')}`
    : `${op.verb} ${plural(acting.length, 'subscription')}`;

  const start = () => {
    if (!data) return;
    execute.mutate(
      { runId: data.run.id, body: { planHash: data.run.planHash, override: overCap && override, continueOnFailure } },
      {
        onSuccess: (run) => {
          close();
          notify.succeeded({
            action: START,
            subject: `the ${op.verb.toLowerCase()} of ${plural(run.total, 'subscription')}`,
          });
          void navigate({ to: `/clusters/${clusterId}/bulk/${run.id}` });
          onStarted();
        },
      },
    );
  };

  return (
    <Modal
      opened={opened}
      onClose={close}
      onEnterTransitionEnd={takePreview}
      title={`${op.verb} the selected subscriptions`}
      size="xl"
    >
      <Stack gap="md">
        <PreviewStatus
          preview={preview}
          operation={operation}
          moves={moves}
          unknown={unknown}
          refused={refused}
          onRetry={takePreview}
        />

        {data && overCap ? (
          <Section title="Over the safety cap" headingLevel={3} variant="card">
            <Stack gap="xs">
              <Text size="sm">
                This run would {moves ? 'skip' : 'destroy'} {data.run.estimate?.toLocaleString()}{' '}
                {moves ? 'records' : 'messages'}, over the cap of {data.run.cap.toLocaleString()}. It can run only with
                the cap overridden for this run, and the override is recorded in the audit log.
              </Text>
              <Checkbox
                label="Override the safety cap for this run"
                checked={override}
                onChange={(e) => setOverride(e.currentTarget.checked)}
              />
            </Stack>
          </Section>
        ) : null}

        {data && acting.length > 0 ? (
          <RunControls
            op={op}
            execute={execute}
            continueOnFailure={continueOnFailure}
            onContinueOnFailure={setContinueOnFailure}
            overCap={overCap}
            override={override}
            confirmLabel={confirmLabel}
            onStart={start}
            onRetry={takePreview}
          />
        ) : null}

        {data && acting.length === 0 ? (
          <Text size="sm">
            Every selected subscription is refused, so there is nothing to run. Close this and change the selection.
          </Text>
        ) : null}

        {data ? (
          <SubscriptionsInRun
            destructive={op.destructive}
            shown={shown}
            onlyProblems={onlyProblems}
            onOnlyProblems={setOnlyProblems}
          />
        ) : null}
      </Stack>
    </Modal>
  );
}
