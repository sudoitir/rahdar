import { useState } from 'react';
import { Button, Code, Progress, Stack, Text } from '@mantine/core';
import { Link, useParams } from '@tanstack/react-router';

import { useClusters } from '../clusters/index.ts';
import { useCan } from '../../kernel/auth/useCan.ts';
import { nameOf } from '../../kernel/api/resourceId.ts';
import { absoluteLabel } from '../../kernel/time/time.ts';
import { useDisplayZone } from '../../kernel/time/timezone.ts';
import { CapabilityGate } from '../../ui/CapabilityGate.tsx';
import { gateFor } from '../../ui/capabilityGate.ts';
import { ConfirmDialog } from '../../ui/ConfirmDialog.tsx';
import { ErrorState } from '../../ui/ErrorState.tsx';
import linkClasses from '../../ui/InlineLink.module.css';
import { LoadingState } from '../../ui/LoadingState.tsx';
import { OutcomeSummary } from '../../ui/NodeOutcomeSummary.tsx';
import { notify, type ActionVerb } from '../../ui/notify.ts';
import { Page } from '../../ui/Page.tsx';
import { PageHeader } from '../../ui/PageHeader.tsx';
import { Section } from '../../ui/Section.tsx';
import { Stat } from '../../ui/Stat.tsx';
import { useTransferCommand, useTransferRun, type TransferRunView as Run } from './api.ts';
import classes from './TransferRunView.module.css';
import { ACTIVE, MODE, plural, stages, stateWords, toneColor } from './words.ts';

const STOP: ActionVerb = { verb: 'Stop', past: 'Stopped', progressive: 'Stopping' };
const RESUME: ActionVerb = { verb: 'Resume', past: 'Resumed', progressive: 'Resuming' };
const RETURN: ActionVerb = { verb: 'Return', past: 'Returned', progressive: 'Returning' };

/** What the page says before the run has loaded, so the header holds its lines when it arrives. */
const ABOUT = 'One transfer: where its messages are, how far it has got and what you can do next.';

const n = (v: number) => v.toLocaleString();

/** The state as a sentence that says where the messages are and what the operator can do next. */
function sentence(run: Run): string {
  const held =
    stages(run) && run.held > 0 ? ` ${plural(run.held, 'message')} held in staging on ${run.source.nodeName}.` : '';
  switch (run.state) {
    case 'PREVIEWED':
      return 'Previewed and never started. Nothing was moved or sent.';
    case 'RUNNING':
      return `Running.${held}`;
    case 'WAITING_FOR_CAPACITY':
      return `Waiting for the target to have room, and continuing by itself once it does.${held}`;
    case 'RETURNING':
      return `Returning the held messages to ${nameOf(run.source.subscription)}.${held}`;
    case 'SUCCEEDED':
      return `This run finished. Succeeded: every selected message was ${MODE[run.mode].past}.`;
    case 'PARTIAL':
      return `This run finished. Partial: ${plural(run.notTransferred, 'selected message')} could not be taken, because ${run.notTransferred === 1 ? 'it was' : 'they were'} being delivered to a consumer, scheduled, or already gone.`;
    case 'STOPPED':
      return `Stopped.${held} Resume it${stages(run) ? ', or return the held messages to the source' : ''}.`;
    case 'INTERRUPTED':
      return `Interrupted: Rahdar stopped while it ran.${held} Nothing is lost; resume it${stages(run) ? ' or return it' : ''}.`;
    case 'FAILED':
      return `This run failed.${held} Nothing held is lost; resume it once the cause is fixed${stages(run) ? ', or return the held messages' : ''}.`;
    case 'RETURNED':
      return `Returned: ${plural(run.returned, 'held message')} ${run.returned === 1 ? 'is' : 'are'} back on ${nameOf(run.source.subscription)}.`;
  }
}

/**
 * How fast it is going, or — once it has finished — how fast it went. A finished run has no
 * pending rate, and saying "no rate yet" of one that ended minutes ago reads as a stall.
 */
function pace(run: Run, rate: number | null | undefined, left: string | null): string {
  if (ACTIVE.has(run.state)) {
    if (!rate) return 'No rate yet.';
    const remaining = left ? `, ${left}` : '';
    return `${rate.toLocaleString(undefined, { maximumFractionDigits: 1 })} messages a second${remaining}.`;
  }
  const seconds =
    run.startedAt && run.finishedAt ? (Date.parse(run.finishedAt) - Date.parse(run.startedAt)) / 1000 : null;
  if (run.delivered === 0 || seconds == null || seconds <= 0) {
    return `${plural(run.delivered, 'message')} ${MODE[run.mode].past}.`;
  }
  const perSecond = run.delivered / seconds;
  return `${plural(run.delivered, 'message')} ${MODE[run.mode].past} in ${plural(Math.max(1, Math.round(seconds)), 'second')}, ${perSecond.toLocaleString(undefined, { maximumFractionDigits: 1 })} a second.`;
}

function eta(run: Run): string | null {
  const rate = run.messagesPerSecond;
  if (!ACTIVE.has(run.state) || run.estimate == null || !rate) return null;
  const left = Math.max(0, run.estimate - run.delivered - run.notTransferred);
  const seconds = Math.ceil(left / rate);
  return seconds < 90
    ? `about ${plural(seconds, 'second')} left`
    : `about ${plural(Math.ceil(seconds / 60), 'minute')} left`;
}

/** Selected, held in staging, delivered, and what did not go through. */
function Pipeline({ run, staging }: Readonly<{ run: Run; staging: boolean }>) {
  const held = staging && run.held > 0 && !ACTIVE.has(run.state);
  return (
    <fieldset className={classes.strip} aria-label="Transfer pipeline">
      <div className={classes.stage}>
        <Stat
          label="Selected"
          value={run.estimate == null ? null : n(run.estimate)}
          unavailableReason="The selection's size was not known at preview."
        />
      </div>
      <div className={classes.stage} data-tone={held ? 'warning' : undefined}>
        <Stat label="Held in staging" value={staging ? n(run.held) : 'not used'} />
      </div>
      <div className={classes.stage}>
        <Stat label="Delivered" value={n(run.delivered)} />
      </div>
      {run.notTransferred > 0 ? (
        <div className={classes.stage} data-tone="warning">
          <Stat label="Not transferred" value={n(run.notTransferred)} />
        </div>
      ) : null}
      {run.expired > 0 ? (
        <div className={classes.stage}>
          <Stat label="Expired while held" value={n(run.expired)} />
        </div>
      ) : null}
      {run.returned > 0 ? (
        <div className={classes.stage}>
          <Stat label="Returned to source" value={n(run.returned)} />
        </div>
      ) : null}
    </fieldset>
  );
}

/** How far along the run is, or why that cannot be shown. */
function DeliveredProgress({ run, tone }: Readonly<{ run: Run; tone: string | undefined }>) {
  if (run.estimate == null) {
    return (
      <Text size="sm">How far along this is cannot be shown: the selection&rsquo;s size was not known at preview.</Text>
    );
  }
  return (
    <Progress
      aria-label="Messages delivered"
      value={run.estimate === 0 ? 100 : Math.min(100, ((run.delivered + run.notTransferred) / run.estimate) * 100)}
      color={tone}
    />
  );
}

/** What became of the messages at the source, in words. */
function sourceStatus(mode: Run['mode'], staging: boolean): string {
  if (mode !== 'MOVE') return 'read, left in place';
  return staging ? 'taken off into staging' : 'moved by the broker';
}

/** Stopping is safe: the batch in flight finishes and nothing is lost. */
function StopDialog({
  opened,
  staging,
  pending,
  onClose,
  onStop,
}: Readonly<{ opened: boolean; staging: boolean; pending: boolean; onClose: () => void; onStop: () => void }>) {
  return (
    <ConfirmDialog
      opened={opened}
      onClose={onClose}
      title="Stop this transfer?"
      consequence={
        <>
          The batch in flight finishes, then the run stops. Nothing is lost:{' '}
          {staging ? 'held messages stay in staging, and ' : ''}
          the run can be resumed{staging ? ' or returned' : ''} later.
        </>
      }
      confirmLabel="Stop"
      pending={pending}
      onConfirm={onStop}
    />
  );
}

/** Returning the held messages ends the run; it is armed by typing the source subscription's name. */
function ReturnDialog({
  opened,
  run,
  pending,
  onClose,
  onReturn,
}: Readonly<{ opened: boolean; run: Run; pending: boolean; onClose: () => void; onReturn: () => void }>) {
  return (
    <ConfirmDialog
      opened={opened}
      onClose={onClose}
      title="Return the held messages to the source?"
      consequence={
        <>
          Every message held in staging, {plural(run.held, 'message')} now, goes back on{' '}
          {nameOf(run.source.subscription)} on {run.source.nodeName}, and the staging subscription is removed. Messages
          already delivered to {nameOf(run.target.subscription)} stay there. The run ends as returned and cannot be
          resumed.
        </>
      }
      confirmLabel={`Return ${plural(run.held, 'message')}`}
      tone="danger"
      pending={pending}
      onConfirm={onReturn}
    />
  );
}

/** The audit trails, and the commands the run's state allows, each behind its gate. */
function RunActions({
  run,
  runGate,
  returnGate,
  stop,
  resume,
  back,
  onDialog,
  onResume,
}: Readonly<{
  run: Run;
  runGate: ReturnType<typeof gateFor>;
  returnGate: ReturnType<typeof gateFor>;
  stop: ReturnType<typeof useTransferCommand>;
  resume: ReturnType<typeof useTransferCommand>;
  back: ReturnType<typeof useTransferCommand>;
  onDialog: (dialog: 'stop' | 'return') => void;
  onResume: () => void;
}>) {
  return (
    <>
      {run.auditEventId != null ? (
        <Link
          to={`/clusters/${run.source.clusterId}/audit`}
          // The untyped router cannot type another feature's search; `BulkRunView` does the same.
          search={{ parentId: run.auditEventId } as never}
          className={linkClasses.link}
        >
          Audit trail
        </Link>
      ) : null}
      {run.targetAuditEventId != null ? (
        <Link
          to={`/clusters/${run.target.clusterId}/audit`}
          search={{ parentId: run.targetAuditEventId } as never}
          className={linkClasses.link}
        >
          Target&rsquo;s audit trail
        </Link>
      ) : null}
      {ACTIVE.has(run.state) && run.state !== 'RETURNING' ? (
        <CapabilityGate verdict={runGate} what="stopping this transfer">
          <Button
            size="xs"
            variant="default"
            disabled={runGate.kind === 'blocked'}
            loading={stop.isPending}
            onClick={() => onDialog('stop')}
          >
            Stop
          </Button>
        </CapabilityGate>
      ) : null}
      {run.resumable ? (
        <CapabilityGate verdict={runGate} what="resuming this transfer">
          <Button size="xs" disabled={runGate.kind === 'blocked'} loading={resume.isPending} onClick={onResume}>
            Resume
          </Button>
        </CapabilityGate>
      ) : null}
      {run.returnable ? (
        <CapabilityGate verdict={returnGate} what="returning these messages">
          <Button
            size="xs"
            variant="default"
            disabled={returnGate.kind === 'blocked' || back.isPending}
            loading={back.isPending}
            onClick={() => onDialog('return')}
          >
            Return to source…
          </Button>
        </CapabilityGate>
      ) : null}
    </>
  );
}

/** The server re-checks every command; these gates only explain. Offered while grants load. */
function commandGates(run: Run, canAnywhere: (action: string, clusterId: string) => boolean, loading: boolean) {
  const sourcePermission = run.mode === 'MOVE' ? 'message:move' : 'message:read';
  const sourceLabel = run.mode === 'MOVE' ? 'Move or retry messages' : 'Browse messages';
  const runGate = canAnywhere(sourcePermission, run.source.clusterId)
    ? gateFor(canAnywhere('message:send', run.target.clusterId), 'Send messages', undefined, loading)
    : gateFor(false, sourceLabel, undefined, loading);
  const returnGate = gateFor(
    canAnywhere('message:move', run.source.clusterId),
    'Move or retry messages',
    undefined,
    loading,
  );
  return { runGate, returnGate };
}

/**
 * One transfer run at its own address (ADR-0097): the pipeline from the selection through staging to
 * the target, the state in words, where each end stands, and the commands the state allows. Kept
 * live by the `transfer` stream topic, so a reload picks the run up where it is.
 */
export function TransferRunView() {
  useDisplayZone();
  const { clusterId, runId } = useParams({ strict: false }) as { clusterId: string; runId: string };
  const query = useTransferRun(clusterId, runId);
  const clusters = useClusters();
  const stop = useTransferCommand(clusterId, runId, 'stop');
  const resume = useTransferCommand(clusterId, runId, 'resume');
  const back = useTransferCommand(clusterId, runId, 'return');
  const { canAnywhere, loading } = useCan();
  const [dialog, setDialog] = useState<'stop' | 'return' | null>(null);

  if (!query.data) {
    return (
      <Page>
        <PageHeader title="Transfer" description={ABOUT} meta={<AllTransfers clusterId={clusterId} />} />
        {query.isError ? (
          <ErrorState error={query.error} onRetry={() => void query.refetch()} />
        ) : (
          <LoadingState label="Loading the transfer" blockSize="16rem" />
        )}
      </Page>
    );
  }

  const run = query.data;
  const state = stateWords(run.state);
  const clusterName = (id: string) => clusters.data?.find((c) => c.id === id)?.name ?? 'another cluster';
  const staging = stages(run);
  const rate = run.messagesPerSecond;
  const left = eta(run);

  const { runGate, returnGate } = commandGates(run, canAnywhere, loading);

  // The toast carries the outcome: pending while it runs, then done, or why it did not and what to do.
  const command = (
    mutation: ReturnType<typeof useTransferCommand>,
    action: ActionVerb,
    subject: string,
    then?: () => void,
  ) => {
    const pendingId = notify.pending({ action, subject });
    mutation.mutate(undefined, {
      onSuccess: () => notify.succeeded({ action, subject, pendingId }),
      onError: (error) =>
        notify.settle(error, {
          action,
          subject,
          pendingId,
          cause: error.message,
          next: 'The run is as shown on this page; nothing further was done.',
        }),
      onSettled: then,
    });
  };

  return (
    <Page>
      <PageHeader
        title={`${MODE[run.mode].verb} ${run.estimate == null ? 'messages' : plural(run.estimate, 'message')}`}
        meta={<AllTransfers clusterId={clusterId} />}
        description={
          <>
            <div>
              From {nameOf(run.source.subscription)} on {run.source.nodeName} ({clusterName(run.source.clusterId)}) to{' '}
              {nameOf(run.target.subscription)} on {run.target.nodeName} ({clusterName(run.target.clusterId)}
              ).
            </div>
            <div>
              By {run.username}
              {run.startedAt ? `, started ${absoluteLabel(run.startedAt)}` : ''}
              {`, selecting messages up to ${absoluteLabel(run.t0)}`}
              {run.overrideCap ? ', with the safety cap overridden' : ''}
            </div>
          </>
        }
        actions={
          <RunActions
            run={run}
            runGate={runGate}
            returnGate={returnGate}
            stop={stop}
            resume={resume}
            back={back}
            onDialog={setDialog}
            onResume={() => command(resume, RESUME, 'this transfer')}
          />
        }
      />

      <Section title="State">
        {/* Only the state is announced; the figures change every batch and would drown it out. */}
        <div role="status" aria-live="polite" aria-label="Transfer state">
          <Text size="sm" fw={600} c={toneColor(state.tone)}>
            {sentence(run)}
          </Text>
        </div>
        {run.lastError ? (
          <Stack gap="xs">
            <Text size="sm">{run.lastError}</Text>
            {run.errorSnippet ? (
              <>
                <Text size="xs" fw={600}>
                  Add this to <Code>broker.xml</Code>:
                </Text>
                <Code block>{run.errorSnippet}</Code>
              </>
            ) : null}
          </Stack>
        ) : null}

        <Pipeline run={run} staging={staging} />

        {/* The bar fills on the theme's motion token, which reduced motion zeroes, so it jumps instead. */}
        <DeliveredProgress run={run} tone={toneColor(state.tone)} />
        <Text size="sm" className={classes.figures}>
          {pace(run, rate, left)}
        </Text>
      </Section>

      <Section title="Where each end stands">
        <OutcomeSummary
          verdict={state.text}
          verdictTone={state.tone}
          rows={[
            {
              key: 'source',
              name: `${run.source.nodeName}, source (${clusterName(run.source.clusterId)})`,
              count: n(run.mode === 'MOVE' ? run.staged : run.delivered),
              status: sourceStatus(run.mode, staging),
            },
            {
              key: 'target',
              name: `${run.target.nodeName}, target (${clusterName(run.target.clusterId)})`,
              count: n(run.delivered),
              status: 'delivered',
              tone: run.state === 'FAILED' ? 'danger' : undefined,
              detail: run.state === 'FAILED' ? run.lastError : null,
            },
          ]}
        />
      </Section>

      {run.notes.length > 0 ? (
        <Section title="Good to know">
          <Stack gap="xs">
            {run.notes.map((note) => (
              <Text key={note} size="sm">
                {note}
              </Text>
            ))}
          </Stack>
        </Section>
      ) : null}

      <StopDialog
        opened={dialog === 'stop'}
        staging={staging}
        pending={stop.isPending}
        onClose={() => setDialog(null)}
        onStop={() => command(stop, STOP, 'this transfer', () => setDialog(null))}
      />

      <ReturnDialog
        opened={dialog === 'return'}
        run={run}
        pending={back.isPending}
        onClose={() => setDialog(null)}
        onReturn={() => command(back, RETURN, 'the held messages to the source', () => setDialog(null))}
      />
    </Page>
  );
}

/** The way back to the list, beside the title. */
function AllTransfers({ clusterId }: Readonly<{ clusterId: string }>) {
  return (
    <Link to={`/clusters/${clusterId}/transfers`} className={linkClasses.link}>
      All transfers
    </Link>
  );
}
