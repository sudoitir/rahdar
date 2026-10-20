import { useMemo, useState } from 'react';
import { Button, Group, Progress, Stack, Text } from '@mantine/core';
import { Link, useParams } from '@tanstack/react-router';

import { useCan } from '../../kernel/auth/useCan.ts';
import { resourceName } from '../../kernel/api/resourceId.ts';
import { absoluteLabel } from '../../kernel/time/time.ts';
import { useDisplayZone } from '../../kernel/time/timezone.ts';
import { CapabilityGate } from '../../ui/CapabilityGate.tsx';
import { gateFor } from '../../ui/capabilityGate.ts';
import { ConfirmDialog } from '../../ui/ConfirmDialog.tsx';
import { EmptyState } from '../../ui/EmptyState.tsx';
import { ErrorState } from '../../ui/ErrorState.tsx';
import linkClasses from '../../ui/InlineLink.module.css';
import { LoadingState } from '../../ui/LoadingState.tsx';
import { NodeOutcomeSummary } from '../../ui/NodeOutcomeSummary.tsx';
import { notify } from '../../ui/notify.ts';
import { Page } from '../../ui/Page.tsx';
import { PageHeader } from '../../ui/PageHeader.tsx';
import { Section } from '../../ui/Section.tsx';
import { StatusBadge } from '../../ui/StatusBadge.tsx';
import { DataTable } from '../../ui/table/index.ts';
import {
  useBulkRun,
  useBulkStop,
  type BulkItemView,
  type BulkRunView as Run,
  type LifecycleOutcomeView,
} from './api.ts';
import classes from './BulkRunView.module.css';
import { itemColumns } from './columns.ts';
import { OPERATIONS, plural, runStatus } from './words.ts';

const STOP = { verb: 'Stop', past: 'Stopped', progressive: 'Stopping' } as const;

/** What the page says before the run has loaded, so the header holds its two lines when it arrives. */
const ABOUT = "One bulk run: its status, how far it has got and each queue's outcome.";

/** An acted-on queue's per-node result, in the single-queue command's shape. */
function outcomeOf(item: BulkItemView, run: Run): LifecycleOutcomeView | null {
  if (!item.outcome) return null;
  const landed = item.outcome.some((n) => n.status === 'APPLIED' || n.status === 'ALREADY');
  return {
    dryRun: false,
    cap: run.cap,
    overCap: false,
    partial: landed && item.outcome.some((n) => n.status === 'FAILED'),
    totalAffected: item.affected ?? 0,
    nodes: item.outcome.map((n) => ({
      nodeId: n.nodeId ?? '',
      nodeName: n.nodeName ?? '',
      status: n.status ?? 'FAILED',
      affected: n.affected ?? null,
      error: n.error ?? null,
    })),
  };
}

/** The counts in words, so the outcome does not depend on reading a bar. */
function counts(run: Run): string {
  const done = run.succeeded + run.failed + run.skipped;
  return `${run.succeeded.toLocaleString()} succeeded, ${run.failed.toLocaleString()} failed, ${run.skipped.toLocaleString()} skipped, ${done.toLocaleString()} of ${plural(run.total, 'queue')} settled`;
}

const TONE_COLOR = { danger: 'var(--as-danger)', warning: 'var(--as-warning)' } as const;

const TERMINAL = new Set<Run['status']>(['SUCCEEDED', 'PARTIAL', 'FAILED', 'STOPPED', 'INTERRUPTED']);

const rowKey = (i: BulkItemView) => i.subscription;

/** One queue's per-node result, or why it has none. */
function QueueDetail({
  item,
  outcome,
  destructive,
  onHide,
}: Readonly<{
  item: BulkItemView;
  outcome: LifecycleOutcomeView | null;
  destructive: boolean;
  onHide: () => void;
}>) {
  return (
    <Section
      title={resourceName(item.subscription)}
      headingLevel={3}
      actions={
        <Button size="xs" variant="subtle" onClick={onHide}>
          Hide node detail
        </Button>
      }
    >
      {outcome ? (
        <NodeOutcomeSummary outcome={outcome} destructive={destructive} />
      ) : (
        <Text size="sm">
          {item.status === 'REFUSED'
            ? `Refused at preview: ${item.error ?? 'no reason recorded'}. Nothing was sent to the broker.`
            : 'This queue has not been acted on, so there is no per-node result.'}
        </Text>
      )}
    </Section>
  );
}

/**
 * One bulk run at its own address: its status in words, its progress, and each queue's outcome,
 * expandable to the per-node detail. Kept live by the `bulk` stream topic, so a reload picks the run
 * up where it is.
 */
export function BulkRunView() {
  useDisplayZone();
  const { clusterId, runId } = useParams({ strict: false }) as { clusterId: string; runId: string };
  const query = useBulkRun(clusterId, runId);
  const stop = useBulkStop(clusterId, runId);
  const { can, loading } = useCan();
  const [open, setOpen] = useState<string | null>(null);
  const [stopOpen, setStopOpen] = useState(false);
  const destructive = query.data ? OPERATIONS[query.data.run.operation].destructive : false;
  const columns = useMemo(() => itemColumns(destructive, setOpen), [destructive]);

  if (!query.data) {
    return (
      <Page>
        <PageHeader title="Bulk run" description={ABOUT} />
        {query.isError ? (
          <ErrorState error={query.error} onRetry={() => void query.refetch()} />
        ) : (
          <LoadingState label="Loading the run" blockSize="16rem" />
        )}
      </Page>
    );
  }

  const { run, items } = query.data;
  const op = OPERATIONS[run.operation];
  const status = runStatus(run.status);
  const done = run.succeeded + run.failed + run.skipped;
  const opened = items.find((i) => i.subscription === open) ?? null;
  const openedOutcome = opened ? outcomeOf(opened, run) : null;
  // The server re-checks; this only explains. Offered while grants load.
  const stopGate = gateFor(can(op.permission, clusterId), op.permissionLabel, undefined, loading);

  const stopRun = () => {
    const pendingId = notify.pending({ action: STOP, subject: 'this run' });
    stop.mutate(undefined, {
      onSuccess: () => notify.succeeded({ action: STOP, subject: 'this run', pendingId }),
      onError: (error) =>
        notify.settle(error, {
          action: STOP,
          subject: 'this run',
          pendingId,
          cause: error.message,
          next: 'Reload to see how far the run got, then try again.',
        }),
      onSettled: () => setStopOpen(false),
    });
  };

  return (
    <Page>
      <PageHeader
        title={`${op.verb} ${plural(run.total, 'queue')}`}
        description={
          <>
            Started by {run.username}
            {run.startedAt ? ` at ${absoluteLabel(run.startedAt)}` : ''}
            {run.continueOnFailure ? ', continuing past failures' : ', stopping at the first failure'}
            {run.overrideCap ? ', with the safety cap overridden' : ''}
          </>
        }
        actions={
          <>
            {run.auditEventId != null ? (
              <Link
                to={`/clusters/${clusterId}/audit`}
                // The untyped router cannot type another feature's search; `QueueHistoryPanels` does the same.
                search={{ parentId: run.auditEventId } as never}
                className={linkClasses.link}
              >
                Audit trail for this run
              </Link>
            ) : null}
            {run.status === 'RUNNING' ? (
              <CapabilityGate verdict={stopGate} what="stopping this run">
                <Button
                  size="xs"
                  variant="default"
                  disabled={stopGate.kind === 'blocked'}
                  onClick={() => setStopOpen(true)}
                >
                  Stop run
                </Button>
              </CapabilityGate>
            ) : null}
          </>
        }
      />

      <Section title="Outcome">
        {/* Announced as it changes; the terminal outcome is what a screen-reader user waits for. */}
        <Stack gap="xs" role="status" aria-live="polite" aria-label="Run outcome">
          <Group gap="sm">
            <StatusBadge tone={status.tone}>{status.text}</StatusBadge>
            {TERMINAL.has(run.status) ? <Text size="sm">This run finished.</Text> : null}
          </Group>
          <Text size="sm" className={classes.figures}>
            {counts(run)}.
          </Text>
          {run.error ? <Text size="sm">{run.error}</Text> : null}
        </Stack>

        {/* The bar fills on the theme's motion token, which reduced motion zeroes, so it jumps instead. */}
        <Progress
          aria-label="Queues settled"
          value={run.total === 0 ? 0 : (done / run.total) * 100}
          color={status.tone ? TONE_COLOR[status.tone] : undefined}
        />
      </Section>

      <Section title="Queues">
        <DataTable
          label="Queues in this run"
          storageKey="bulk.run"
          height={{ maxRows: 12 }}
          columns={columns}
          data={items}
          rowKey={rowKey}
          empty={
            <EmptyState
              kind="empty"
              title="No queues in this run"
              description="A run acts on the queues frozen at its preview. This one froze none, so nothing was or will be acted on. Start a new run from the Queues screen with a selection."
            />
          }
        />

        {opened ? (
          <QueueDetail
            item={opened}
            outcome={openedOutcome}
            destructive={op.destructive}
            onHide={() => setOpen(null)}
          />
        ) : null}
      </Section>

      <ConfirmDialog
        opened={stopOpen}
        onClose={() => setStopOpen(false)}
        title="Stop this run?"
        consequence="The queue being acted on now finishes; every queue after it is cancelled and left as it is. What has already been done is not undone."
        confirmLabel="Stop run"
        pending={stop.isPending}
        onConfirm={stopRun}
      />
    </Page>
  );
}
