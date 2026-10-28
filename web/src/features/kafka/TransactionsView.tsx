import { useMemo, useState } from 'react';
import { Button, Stack, Text } from '@mantine/core';
import { useParams } from '@tanstack/react-router';

import { useCan } from '../../kernel/auth/useCan.ts';
import { Ago } from '../../kernel/time/Ago.tsx';
import { useServerNow } from '../../kernel/time/time.ts';
import { CapabilityGate } from '../../ui/CapabilityGate.tsx';
import { gateFor } from '../../ui/capabilityGate.ts';
import { ConfirmDialog } from '../../ui/ConfirmDialog.tsx';
import { EmptyState } from '../../ui/EmptyState.tsx';
import { ErrorState } from '../../ui/ErrorState.tsx';
import { Notice } from '../../ui/Notice.tsx';
import { notify, type ActionVerb } from '../../ui/notify.ts';
import { Page } from '../../ui/Page.tsx';
import { PageHeader } from '../../ui/PageHeader.tsx';
import { StatusBadge } from '../../ui/StatusBadge.tsx';
import { DataTable, type Column } from '../../ui/table/index.ts';
import { useAbortTransaction, usePreviewAbort, useTransactions, type AbortPreview, type Transaction } from './api.ts';

const ABORT: ActionVerb = { verb: 'Abort', past: 'Aborted', progressive: 'Aborting' };

const blockedWords = (t: Transaction): string =>
  t.blocked.length === 0 ? 'None' : t.blocked.map((b) => `${b.topic}-${b.partition}`).join(', ');

const seconds = (ms: number): string =>
  ms >= 60_000 ? `${Math.round(ms / 60_000)} min` : `${Math.round(ms / 1000)} s`;

/** The abort of one transaction: the effect first, named, then a held confirmation. */
function AbortDialog({
  clusterId,
  transaction,
  onClose,
}: Readonly<{ clusterId: string; transaction: Transaction | null; onClose: () => void }>) {
  const id = transaction?.id ?? '';
  const preview = usePreviewAbort(clusterId, id);
  const abort = useAbortTransaction(clusterId, id);
  const [effect, setEffect] = useState<AbortPreview | null>(null);
  const [started, setStarted] = useState<string | null>(null);

  // The effect is what the operator needs to decide, so it is read when the dialog opens.
  if (transaction && started !== transaction.id) {
    setStarted(transaction.id);
    setEffect(null);
    preview.mutate(undefined, { onSuccess: setEffect });
  }
  if (!transaction && started !== null) setStarted(null);

  const dismiss = () => {
    preview.reset();
    abort.reset();
    setEffect(null);
    onClose();
  };
  const run = () => {
    if (!effect) return;
    abort.mutate(
      { etag: effect.etag },
      {
        onSuccess: () => {
          notify.succeeded({ action: ABORT, subject: `transaction ${id}` });
          dismiss();
        },
        onError: (error) => {
          if (error.status === 412 || error.status === 409) return;
          notify.settle(error, {
            action: ABORT,
            subject: `transaction ${id}`,
            cause: error.message,
            next: 'Check the audit log for what ran, then try again.',
            onHeld: dismiss,
          });
        },
      },
    );
  };
  let blocked: string | undefined;
  if (abort.error?.status === 412) blocked = 'The transaction changed since the preview. Close this and open it again.';
  else if (abort.error?.status === 409) blocked = abort.error.message;
  else if (effect && !effect.abortable)
    blocked =
      'Only an open transaction past its timeout can be aborted. This one is not, and aborting it would fence a producer that is working.';
  else if (preview.isError) blocked = 'The effect could not be read, so the abort is not offered.';
  else if (!effect) blocked = 'Reading what the abort would do…';

  return (
    <ConfirmDialog
      opened={transaction !== null}
      onClose={dismiss}
      title={`Abort transaction ${id}`}
      tone="danger"
      confirmLabel={`Abort transaction ${id}`}
      blocked={blocked}
      pending={abort.isPending}
      onConfirm={run}
      consequence={
        <Stack gap="sm">
          {preview.isError ? <ErrorState variant="inline" error={preview.error} /> : null}
          {effect ? (
            <>
              <Text size="sm">{effect.effect}</Text>
              {effect.released.length > 0 ? (
                <Text size="sm">{`Unblocks: ${effect.released.map((b) => `${b.topic}-${b.partition}`).join(', ')}.`}</Text>
              ) : null}
            </>
          ) : null}
          <Text size="sm">The producer is fenced and its next write fails. This cannot be undone.</Text>
        </Stack>
      }
    />
  );
}

/**
 * The transactional ids of a Kafka cluster: state, timeout and the partitions a hanging transaction holds back.
 * A hanging transaction is the one that stops consumers reading past it; aborting it is gated and previewed.
 */
export function TransactionsView() {
  const { clusterId } = useParams({ strict: false }) as { clusterId: string };
  const transactions = useTransactions(clusterId);
  const now = useServerNow(5_000);
  const { can, loading } = useCan();
  const gate = gateFor(can('transaction:abort', clusterId), 'Abort transactions', undefined, loading);
  const [aborting, setAborting] = useState<Transaction | null>(null);

  const columns = useMemo<Column<Transaction>[]>(
    () => [
      { id: 'id', header: 'Transactional id', accessor: (t) => t.id, kind: 'identifier', priority: 'essential' },
      {
        id: 'state',
        header: 'State',
        accessor: (t) => (t.hanging ? `${t.state} (hanging)` : t.state),
        cell: (t) => (
          <StatusBadge tone={t.hanging ? 'warning' : 'neutral'}>
            {t.hanging ? `${t.state}, hanging` : t.state}
          </StatusBadge>
        ),
        kind: 'status',
        badge: true,
        priority: 'essential',
      },
      {
        id: 'started',
        header: 'Started',
        accessor: (t) => t.startedAt ?? 'Not running',
        cell: (t) => (t.startedAt ? <Ago at={t.startedAt} now={now} /> : 'Not running'),
        kind: 'time',
        priority: 'high',
      },
      { id: 'timeout', header: 'Timeout', accessor: (t) => seconds(t.timeoutMs), kind: 'number', priority: 'low' },
      { id: 'blocked', header: 'Holds back', accessor: blockedWords, kind: 'text', priority: 'high', wrap: true },
      {
        id: 'abort',
        header: 'Change',
        accessor: () => '',
        cell: (t) => (
          <CapabilityGate verdict={gate} what={`aborting ${t.id}`}>
            <Button
              size="compact-xs"
              variant="subtle"
              color="red"
              disabled={gate.kind === 'blocked'}
              aria-label={`Abort ${t.id}`}
              onClick={() => setAborting(t)}
            >
              Abort…
            </Button>
          </CapabilityGate>
        ),
        kind: 'status',
        priority: 'essential',
      },
    ],
    [gate, now],
  );

  const hanging = transactions.data?.items.filter((t) => t.hanging) ?? [];
  return (
    <Page fill>
      <PageHeader
        title="Transactions"
        description="Producers that write atomically. A transaction that never finishes holds back every consumer that reads committed records past it."
      />
      {hanging.length > 0 ? (
        <Notice tone="warning" title={`${hanging.length} hanging transaction${hanging.length === 1 ? '' : 's'}`}>
          A hanging transaction is past its timeout or its producer is gone, and it blocks the partitions listed beside
          it.
        </Notice>
      ) : null}
      <DataTable
        label="Transactions"
        storageKey="kafka.transactions"
        height="fill"
        columns={columns}
        data={transactions.data?.items ?? []}
        rowKey={(t) => t.id}
        loading={transactions.isPending}
        error={
          transactions.isError ? (
            <ErrorState error={transactions.error} onRetry={() => void transactions.refetch()} />
          ) : undefined
        }
        empty={
          <EmptyState
            kind="empty"
            title="No transactional producers"
            description="A producer with a transactional id appears here once it has begun a transaction. None has, or the cluster keeps none."
          />
        }
      />
      <AbortDialog clusterId={clusterId} transaction={aborting} onClose={() => setAborting(null)} />
    </Page>
  );
}
