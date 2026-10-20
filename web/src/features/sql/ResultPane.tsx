import { ActionIcon, Button, Group, Stack, Text, Tooltip } from '@mantine/core';
import { IconArrowsMaximize, IconArrowsMinimize } from '@tabler/icons-react';

import { nameOf } from '../../kernel/api/resourceId.ts';
import type { ApiError } from '../../kernel/api/request.ts';
import { download } from '../../ui/download.ts';
import { EmptyState } from '../../ui/EmptyState.tsx';
import { ErrorState } from '../../ui/ErrorState.tsx';
import { LoadingState } from '../../ui/LoadingState.tsx';
import { OutcomeSummary, type OutcomeRow } from '../../ui/NodeOutcomeSummary.tsx';
import { Notice } from '../../ui/Notice.tsx';
import type { SqlResultView, SqlRowView } from './api.ts';
import { toCsv, toJson } from './exportRows.ts';
import { LiveTailBanner } from './LiveTailBanner.tsx';
import { ResultGrid } from './ResultGrid.tsx';
import { ResultMetaBar } from './ResultMetaBar.tsx';
import type { SqlRun } from './useSqlTail.ts';
import classes from './SqlConsoleView.module.css';

type ResultNode = NonNullable<SqlResultView['nodes']>[number];

const NODE_TONE: Record<string, OutcomeRow['tone']> = { FAILED: 'danger', NOT_READ: 'warning' };

function nodeStatusWords(node: ResultNode): string {
  if (node.status === 'ANSWERED') return `answered · examined ${(node.examined ?? 0).toLocaleString()}`;
  return node.status === 'FAILED' ? 'failed' : 'not read';
}

/** A node's contribution to a result, in the words a query uses rather than a command's. */
function outcomeRows(nodes: SqlResultView['nodes']): OutcomeRow[] {
  return (nodes ?? []).map((node, i) => {
    const status = nodeStatusWords(node);
    return {
      key: `${node.nodeId}/${node.subscription}/${i}`,
      name: `${node.nodeName} · ${nameOf(node.subscription)}`,
      count: (node.matched ?? 0).toLocaleString(),
      status,
      tone: NODE_TONE[node.status ?? ''],
      detail: node.detail,
    };
  });
}

interface Verdict {
  text: string;
  tone?: 'warning' | 'danger';
}

const rowWord = (n: number) => `${n.toLocaleString()} row${n === 1 ? '' : 's'}`;

/** The headline: the shape of the result, before any row of it is read. */
function verdictFor(result: SqlResultView, rowCount: number): Verdict {
  const nodes = result.nodes ?? [];
  const answered = nodes.filter((n) => n.status === 'ANSWERED').length;

  if (answered === 0 && nodes.length > 0) {
    return { text: 'No node answered — this result is not an answer', tone: 'danger' };
  }
  if (result.partial) {
    return { text: `${rowWord(rowCount)} — incomplete, this is a prefix of the answer`, tone: 'warning' };
  }
  return { text: `${rowWord(rowCount)} from ${answered} of ${nodes.length} targets` };
}

/** Export what is in view, and say that it may be a prefix of the answer. */
function ExportBar({ rows }: Readonly<{ rows: SqlRowView[] }>) {
  return (
    <Group gap="xs">
      <Text size="xs" c="dimmed">
        Export writes the {rowWord(rows.length)} in view, which may be a prefix of the answer. Sensitive values are
        written masked, exactly as they are shown.
      </Text>
      <Button
        size="compact-xs"
        variant="default"
        disabled={rows.length === 0}
        onClick={() => download('sql-console.csv', toCsv(rows), 'text/csv;charset=utf-8')}
      >
        CSV
      </Button>
      <Button
        size="compact-xs"
        variant="default"
        disabled={rows.length === 0}
        onClick={() => download('sql-console.json', toJson(rows), 'application/json;charset=utf-8')}
      >
        JSON
      </Button>
    </Group>
  );
}

/**
 * Gives the results the whole workspace, or hands it back. Its name says what it will do, so it is not a
 * toggle button; the key is the same one the editor answers to.
 */
function MaximiseButton({ maximised, onToggle }: Readonly<{ maximised: boolean; onToggle: () => void }>) {
  const label = maximised ? 'Restore the editor' : 'Maximise the results';
  const Icon = maximised ? IconArrowsMinimize : IconArrowsMaximize;
  return (
    <Tooltip label={`${label} (⌘/Ctrl-Shift-M)`}>
      <ActionIcon
        variant="default"
        size="sm"
        aria-label={label}
        aria-keyshortcuts="Control+Shift+M Meta+Shift+M"
        onClick={onToggle}
      >
        <Icon size="1rem" aria-hidden />
      </ActionIcon>
    </Tooltip>
  );
}

/** A lost connection is never silently reopened: the query fans out across brokers and is audited. */
function Disconnected({ onRunAgain }: Readonly<{ onRunAgain: () => void }>) {
  return (
    <Notice
      tone="warning"
      title="The connection to this query was lost"
      action={
        <Button size="xs" onClick={onRunAgain}>
          Run it again
        </Button>
      }
    >
      Rahdar does not silently reopen it: this query fans out across brokers and is audited, and reconnecting would run
      it a second time without you asking.
    </Notice>
  );
}

/** A cancelled query keeps what had arrived, and says that it is not the whole answer. */
function Cancelled({ rowCount }: Readonly<{ rowCount: number }>) {
  return (
    <Notice tone="warning" title="Query cancelled">
      The brokers are no longer being read. The {rowWord(rowCount)} that had arrived are kept below, and they are not
      the whole answer.
    </Notice>
  );
}

function failureAdvice(status: number | undefined): string {
  if (status === 429) return 'Wait for one of your running queries to finish, then run this one again.';
  return 'Narrow the query — a header or property predicate is evaluated by the broker and costs nothing.';
}

/**
 * A failure states its cause and what to do next. A refusal carries the estimate and the ceiling it was
 * refused against, because a refusal without its number is not something an operator can act on.
 */
function QueryFailure({ error, onRunAgain }: Readonly<{ error: ApiError; onRunAgain: () => void }>) {
  const { estimate, ceiling, hint } = error.problem;
  const advice = typeof hint === 'string' ? hint : failureAdvice(error.status);
  return (
    <ErrorState
      error={error}
      onRetry={onRunAgain}
      next={
        typeof estimate === 'number' && typeof ceiling === 'number' ? (
          <>
            It would examine about {estimate.toLocaleString()} messages, against a ceiling of {ceiling.toLocaleString()}
            . {advice}
          </>
        ) : (
          advice
        )
      }
    />
  );
}

/**
 * Why there is nothing here. An empty grid means at least four different things, and three of them are
 * not "the subscription is empty": presenting an absence as a fact is the failure this whole screen exists to avoid.
 */
function EmptyResult({ result, tailing }: Readonly<{ result: SqlResultView; tailing: boolean }>) {
  const nodes = result.nodes ?? [];
  const unread = nodes.filter((n) => n.status !== 'ANSWERED');

  if ((result.plan?.targets ?? []).length === 0) {
    return (
      <EmptyState
        kind="empty"
        title="No subscription matched"
        description={
          <>
            The FROM pattern matched no subscription on any node in this cluster. This is an empty target list, not an
            empty subscription — check the name, and remember that <code>*</code> matches one dot-delimited level and{' '}
            <code>#</code> matches many.
          </>
        }
      />
    );
  }
  if (unread.length > 0) {
    return (
      <EmptyState
        kind="unreachable"
        title="Nothing to show, and not because there was nothing"
        description={`${unread.length} of ${nodes.length} targets did not answer, so this is not an empty result — it is an unknown one.`}
        nodes={unread.map((n) => `${n.nodeName} · ${nameOf(n.subscription)}`)}
      />
    );
  }
  if (tailing) {
    return (
      <EmptyState
        kind="empty"
        title="Nothing matched yet"
        description="Every target answered and none of them held a matching message when the tail started. Anything that arrives from now on appears here — but only if it is still on the subscription when Rahdar next reads it."
      />
    );
  }
  return (
    <EmptyState
      kind="empty"
      title="No message matched"
      description="Every target answered and none of them held a message your predicates accept. Widen the query, or check whether a body predicate is being defeated by a truncated body."
    />
  );
}

/** The sentence a screen reader hears for the state of the run. */
function announcementOf(run: SqlRun, verdict: Verdict | null): string {
  const { status, error, rows } = run;
  if (status === 'running') return 'Running query';
  if (status === 'failed' && error) return `Query failed: ${error.message}`;
  if (status === 'cancelled') return `Query cancelled. ${rowWord(rows.length)} had arrived and are kept.`;
  if (status === 'disconnected') return 'The connection to this query was lost. Run it again.';
  if (status === 'tailing') return `Live tail running. ${rows.length} rows so far.`;
  return verdict?.text ?? '';
}

/** Before any row has arrived: the run is under way, or it has not started. */
function NoRows({ status }: Readonly<{ status: SqlRun['status'] }>) {
  if (status === 'running') return <LoadingState label="Running query" blockSize="6rem" />;
  return (
    <EmptyState
      kind="empty"
      title="No results yet"
      description="Run the query to see its rows here. The cost line under the editor says what it will read first."
    />
  );
}

/** The panel for a run that ended badly: failed, lost or cancelled. */
function OutcomeNotice({ run, onRunAgain }: Readonly<{ run: SqlRun; onRunAgain: () => void }>) {
  const { status, error, rows } = run;
  if (status === 'failed' && error) return <QueryFailure error={error} onRunAgain={onRunAgain} />;
  if (status === 'disconnected') return <Disconnected onRunAgain={onRunAgain} />;
  if (status === 'cancelled') return <Cancelled rowCount={rows.length} />;
  return null;
}

/**
 * The verdict on the run and the rows: announced, and every outcome stated (failed, cancelled,
 * disconnected, tailing, empty). It is a named, focusable region because it scrolls when the notices above
 * its rows are tall, and a scroll area that the keyboard cannot reach is a defect.
 */
export function ResultPane({
  clusterId,
  run,
  maximised,
  onToggleMaximise,
  onOpenRow,
  onRunAgain,
  onStopTail,
}: Readonly<{
  clusterId: string;
  run: SqlRun;
  /** The results have the whole workspace; the editor is out of reach. */
  maximised: boolean;
  onToggleMaximise: () => void;
  onOpenRow: (row: SqlRowView) => void;
  onRunAgain: () => void;
  onStopTail: () => void;
}>) {
  const { result, rows, status } = run;
  const tailing = status === 'tailing';
  const verdict = result ? verdictFor(result, rows.length) : null;
  const hasRows = result !== null || rows.length > 0;

  return (
    <section className={classes.region} aria-label="Results" tabIndex={0}>
      {/* Every outcome is announced, not only rendered: an operator on a screen reader otherwise gets no
          signal that a query they ran has finished. */}
      <div className={classes.announcement} role="status" aria-live="polite">
        {announcementOf(run, verdict)}
      </div>

      <div className={classes.resultsBar}>
        {hasRows ? <ExportBar rows={rows} /> : <span />}
        <MaximiseButton maximised={maximised} onToggle={onToggleMaximise} />
      </div>

      <OutcomeNotice run={run} onRunAgain={onRunAgain} />

      {tailing ? (
        <LiveTailBanner
          tail={run.tail}
          shown={rows.length}
          discarding={run.discarding}
          captured={result?.plan?.captured === true}
          paused={run.paused}
          buffered={run.buffered}
          onPause={run.pause}
          onResume={run.resume}
          onStop={onStopTail}
        />
      ) : null}

      {hasRows ? (
        <Stack gap="sm" className={classes.resultStack}>
          {/* One bar, not a stack of alerts: every statement is preserved, behind a disclosure, and the
              rows stay on screen while they are read (7.4). */}
          {result && verdict ? <ResultMetaBar result={result} rowCount={rows.length} verdict={verdict} /> : null}

          {verdict ? (
            <OutcomeSummary verdict={verdict.text} verdictTone={verdict.tone} rows={outcomeRows(result?.nodes)} />
          ) : (
            <OutcomeSummary verdict={`Running — ${rowWord(rows.length)} so far`} rows={outcomeRows(run.progress)} />
          )}

          <ResultGrid
            clusterId={clusterId}
            rows={rows}
            onOpen={onOpenRow}
            freshKeys={run.freshKeys}
            emptyLabel={
              result ? <EmptyResult result={result} tailing={tailing} /> : <LoadingState label="Running query" />
            }
            onAtTopChange={(atTop) => {
              // Scrolling away from the head of a live feed locks it: new rows are prepended, so without
              // this the content moves under the operator exactly while they are trying to read it (7.9).
              if (tailing && !atTop && !run.paused) run.pause();
            }}
          />
        </Stack>
      ) : (
        <NoRows status={status} />
      )}
    </section>
  );
}
