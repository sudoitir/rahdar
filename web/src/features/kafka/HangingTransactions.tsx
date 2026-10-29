import { useMemo, useState } from 'react';
import { Button, Group, Stack, Text, TextInput } from '@mantine/core';

import { useCan } from '../../kernel/auth/useCan.ts';
import { CapabilityGate } from '../../ui/CapabilityGate.tsx';
import { gateFor } from '../../ui/capabilityGate.ts';
import { ConfirmDialog } from '../../ui/ConfirmDialog.tsx';
import { EmptyState } from '../../ui/EmptyState.tsx';
import { ErrorState } from '../../ui/ErrorState.tsx';
import { Notice } from '../../ui/Notice.tsx';
import { notify, type ActionVerb } from '../../ui/notify.ts';
import { Section } from '../../ui/Section.tsx';
import { DataTable, type Column } from '../../ui/table/index.ts';
import {
  useAbortHanging,
  useFindHanging,
  usePreviewHangingAbort,
  type HangingAbortPreview,
  type HangingTransaction,
} from './api.ts';

const ABORT: ActionVerb = { verb: 'Abort', past: 'Aborted', progressive: 'Aborting' };

const plural = (n: number, noun: string) => `${n.toLocaleString()} ${noun}${n === 1 ? '' : 's'}`;

/** A figure the broker did not give is said so: an absent number reads as zero, which is the most misleading answer. */
const orNotReported = (n: number | null | undefined, render: (n: number) => string): string =>
  n == null ? 'Not reported' : render(n);

/** How long ago the producer last wrote, in the largest unit that keeps it exact enough to compare. */
function ageWords(ms: number): string {
  if (ms >= 3_600_000) return `${Math.round(ms / 3_600_000)} h`;
  if (ms >= 60_000) return `${Math.round(ms / 60_000)} min`;
  return `${Math.round(ms / 1000)} s`;
}

const where = (t: Pick<HangingTransaction, 'topic' | 'partition'>) => `${t.topic}-${t.partition}`;

/** The abort of one hanging transaction: previewed and pinned to what was previewed, then a held confirmation. */
function AbortHangingDialog({
  clusterId,
  target,
  onClose,
  onAborted,
}: Readonly<{
  clusterId: string;
  target: HangingTransaction | null;
  onClose: () => void;
  onAborted: () => void;
}>) {
  const preview = usePreviewHangingAbort(clusterId);
  const abort = useAbortHanging(clusterId);
  const [effect, setEffect] = useState<HangingAbortPreview | null>(null);
  const [started, setStarted] = useState<string | null>(null);

  const read = (t: HangingTransaction) => {
    setEffect(null);
    abort.reset();
    preview.mutate({ topic: t.topic, partition: t.partition, producerId: t.producerId }, { onSuccess: setEffect });
  };
  // The effect is what the operator needs to decide, so it is read when the dialog opens.
  if (target && started !== target.id) {
    setStarted(target.id);
    read(target);
  }
  if (!target && started !== null) setStarted(null);

  const dismiss = () => {
    preview.reset();
    abort.reset();
    setEffect(null);
    onClose();
  };
  const run = () => {
    if (!effect || !target) return;
    abort.mutate(
      { topic: effect.topic, partition: effect.partition, producerId: effect.producerId, etag: effect.etag },
      {
        onSuccess: (result) => {
          notify.succeeded({
            action: ABORT,
            subject: `the hanging transaction of producer ${effect.producerId} on ${where(effect)}${result.aborted ? '' : ' (it had closed already)'}`,
          });
          onAborted();
          dismiss();
        },
        onError: (error) => {
          if (error.status === 412) return;
          notify.settle(error, {
            action: ABORT,
            subject: `the hanging transaction on ${where(effect)}`,
            cause: error.message,
            next: 'Check the audit log for what ran, then find it again.',
            onHeld: dismiss,
          });
        },
      },
    );
  };

  const stale = abort.error?.status === 412;
  let blocked: string | undefined;
  if (stale) blocked = 'The transaction changed since the preview. Preview it again.';
  else if (preview.isError) blocked = 'The effect could not be read, so the abort is not offered.';
  else if (!effect) blocked = 'Reading what the abort would do…';

  return (
    <ConfirmDialog
      opened={target !== null}
      onClose={dismiss}
      title={target ? `Abort the hanging transaction on ${where(target)}` : 'Abort'}
      tone="danger"
      confirmLabel={target ? `Abort on ${where(target)}` : 'Abort'}
      blocked={blocked}
      pending={abort.isPending}
      onConfirm={run}
      consequence={
        <Stack gap="sm">
          {preview.isError ? <ErrorState variant="inline" error={preview.error} /> : null}
          {effect ? (
            <>
              <Text size="sm">{effect.effect}</Text>
              <Text size="sm">{`It holds back ${plural(effect.recordsHeldBack, 'record')} from every consumer that reads only committed records.`}</Text>
            </>
          ) : null}
          {stale ? (
            <Notice tone="warning" title="The transaction changed since the preview">
              <Stack gap="xs">
                <Text size="sm">What it holds back may have changed, or it may have closed. Preview it again.</Text>
                <div>
                  <Button size="xs" variant="default" onClick={() => target && read(target)}>
                    Preview again
                  </Button>
                </div>
              </Stack>
            </Notice>
          ) : null}
          {abort.isError && !stale ? <ErrorState variant="inline" error={abort.error} /> : null}
          <Text size="sm">The producer is fenced and its next write fails. This cannot be undone.</Text>
        </Stack>
      }
    />
  );
}

/**
 * The transactions the coordinator does not know about: open on a partition and holding the last stable offset
 * back for ever, so no timeout will end them. They are found on demand, because finding them reads every partition
 * in scope, and aborted one partition at a time.
 */
export function HangingTransactions({ clusterId }: Readonly<{ clusterId: string }>) {
  const find = useFindHanging(clusterId);
  const { can, loading } = useCan();
  const readGate = gateFor(can('transaction:read', clusterId), 'Find hanging transactions', undefined, loading);
  const abortGate = gateFor(can('transaction:abort', clusterId), 'Abort transactions', undefined, loading);
  const [topic, setTopic] = useState('');
  const [searched, setSearched] = useState<{ topic: string } | null>(null);
  const [items, setItems] = useState<HangingTransaction[]>([]);
  const [next, setNext] = useState<string | null>(null);
  const [aborting, setAborting] = useState<HangingTransaction | null>(null);

  const run = (cursor?: string, rescan = false) => {
    const scope = cursor || rescan ? (searched?.topic ?? '') : topic.trim();
    find.mutate(
      { topic: scope || undefined, cursor },
      {
        onSuccess: (page) => {
          setSearched({ topic: scope });
          setItems((previous) => (cursor ? [...previous, ...page.items] : page.items));
          setNext(page.next ?? null);
        },
      },
    );
  };

  const columns = useMemo<Column<HangingTransaction>[]>(
    () => [
      { id: 'topic', header: 'Topic', accessor: (t) => t.topic, kind: 'identifier', priority: 'essential' },
      { id: 'partition', header: 'Partition', accessor: (t) => t.partition, kind: 'number', priority: 'essential' },
      {
        id: 'producer',
        header: 'Producer',
        accessor: (t) => (t.producerEpoch == null ? `${t.producerId}` : `${t.producerId}, epoch ${t.producerEpoch}`),
        kind: 'code',
        priority: 'high',
      },
      {
        id: 'held',
        header: 'Records held back',
        short: 'Held back',
        description: 'Records after its start that a read-committed consumer cannot reach',
        accessor: (t) => orNotReported(t.recordsHeldBack, (n) => n.toLocaleString()),
        kind: 'number',
        priority: 'high',
      },
      {
        id: 'age',
        header: 'Last write',
        description: 'How long ago the producer last wrote',
        accessor: (t) => orNotReported(t.ageMs, (n) => `${ageWords(n)} ago`),
        kind: 'number',
        priority: 'high',
      },
      {
        id: 'abort',
        header: 'Change',
        accessor: () => '',
        cell: (t) => (
          <CapabilityGate verdict={abortGate} what={`aborting the transaction on ${where(t)}`}>
            <Button
              size="compact-xs"
              variant="subtle"
              color="red"
              disabled={abortGate.kind === 'blocked'}
              aria-label={`Abort on ${where(t)}`}
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
    [abortGate],
  );

  let body;
  if (find.isError && !searched) {
    body = (
      <Notice tone="warning" title="Hanging transactions cannot be looked for here">
        <Stack gap="xs">
          <Text size="sm">
            The search did not run, so nothing is said about whether any exists. This is not an empty answer.
          </Text>
          <ErrorState variant="inline" error={find.error} />
        </Stack>
      </Notice>
    );
  } else if (!searched) {
    body = (
      <EmptyState
        kind="empty"
        title="Nothing has been looked for yet"
        description="A hanging transaction is open on a partition but unknown to its coordinator, so no timeout ends it and it holds back every consumer that reads committed records. Finding them reads every partition in scope, so it runs when you ask, for the cluster or for one topic."
      />
    );
  } else {
    body = (
      <>
        {find.isError ? (
          <Notice tone="warning" title="The search for more failed">
            <ErrorState variant="inline" error={find.error} />
          </Notice>
        ) : null}
        <DataTable
          label="Hanging transactions"
          storageKey="kafka.hanging"
          variant="static"
          columns={columns}
          data={items}
          rowKey={(t) => t.id}
          height={{ maxRows: 12 }}
          empty={
            <EmptyState
              kind="empty"
              title="No hanging transactions"
              description={
                searched.topic
                  ? `No open transaction on ${searched.topic} is unknown to its coordinator.`
                  : 'Every open transaction is known to its coordinator, which aborts it after its timeout.'
              }
            />
          }
        />
        {next ? (
          <div>
            <Button size="xs" variant="default" loading={find.isPending} onClick={() => run(next)}>
              Find more
            </Button>
          </div>
        ) : null}
      </>
    );
  }

  return (
    <Section
      title="Hanging transactions"
      description="The ones no timeout will end: found on demand, and aborted one partition at a time."
    >
      <Stack gap="sm">
        <Group align="flex-end" gap="sm">
          <TextInput
            size="xs"
            label="Topic"
            description="Leave empty to look at every topic."
            value={topic}
            onChange={(e) => setTopic(e.currentTarget.value)}
            w="18rem"
          />
          <CapabilityGate verdict={readGate} what="looking for hanging transactions">
            <Button
              size="xs"
              variant="default"
              disabled={readGate.kind === 'blocked'}
              loading={find.isPending && !next}
              onClick={() => run()}
            >
              Find hanging transactions
            </Button>
          </CapabilityGate>
        </Group>
        <div aria-live="polite">{body}</div>
      </Stack>
      <AbortHangingDialog
        clusterId={clusterId}
        target={aborting}
        onClose={() => setAborting(null)}
        onAborted={() => run(undefined, true)}
      />
    </Section>
  );
}
