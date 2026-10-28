import { useState } from 'react';
import { Button, MultiSelect, Select, Stack, Text, TextInput } from '@mantine/core';

import { ApiError } from '../../kernel/api/request.ts';
import { ConfirmDialog } from '../../ui/ConfirmDialog.tsx';
import { ErrorState } from '../../ui/ErrorState.tsx';
import { Notice } from '../../ui/Notice.tsx';
import { OutcomeSummary } from '../../ui/NodeOutcomeSummary.tsx';
import { notify, type ActionVerb } from '../../ui/notify.ts';
import { DataTable, type Column } from '../../ui/table/index.ts';
import {
  usePreviewOffsetReset,
  useResetOffsets,
  type GroupDetail,
  type ResetOutcome,
  type ResetPartition,
  type ResetPreview,
  type ResetRequest,
  type ResetTarget,
} from './api.ts';

const RESET: ActionVerb = { verb: 'Reset', past: 'Reset', progressive: 'Resetting' };

const TARGETS: { value: ResetTarget; label: string }[] = [
  { value: 'earliest', label: 'The earliest offset' },
  { value: 'latest', label: 'The latest offset' },
  { value: 'time', label: 'A time' },
  { value: 'offset', label: 'An offset' },
];

const plural = (n: number, noun: string) => `${n.toLocaleString()} ${noun}${n === 1 ? '' : 's'}`;

/** What is wrong with the value the target needs, or null. */
function valueProblem(to: ResetTarget, value: string): string | null {
  if (to === 'offset') return /^\d+$/.test(value.trim()) ? null : 'Enter the offset as a whole number, such as 120.';
  if (to === 'time') {
    return value.trim() && !Number.isNaN(Date.parse(value.trim()))
      ? null
      : 'Enter the time as ISO-8601, such as 2026-10-28T09:30:00Z.';
  }
  return null;
}

const COLUMNS: Column<ResetPartition>[] = [
  { id: 'topic', header: 'Topic', accessor: (p) => p.topic, kind: 'identifier', priority: 'essential' },
  { id: 'partition', header: 'Partition', accessor: (p) => p.partition, kind: 'number', priority: 'essential' },
  {
    id: 'current',
    header: 'Now at',
    accessor: (p) => (p.current == null ? 'None' : p.current.toLocaleString()),
    kind: 'number',
    priority: 'high',
  },
  {
    id: 'next',
    header: 'Moves to',
    accessor: (p) => (p.next == null ? 'Refused' : p.next.toLocaleString()),
    kind: 'number',
    priority: 'essential',
  },
  {
    id: 'skipped',
    header: 'Records skipped',
    short: 'Skipped',
    description: 'Records the group would never read, moving forward',
    accessor: (p) => p.skipped.toLocaleString(),
    kind: 'number',
    priority: 'high',
  },
  {
    id: 'reread',
    header: 'Records read again',
    short: 'Re-read',
    description: 'Records the group would read a second time, moving back',
    accessor: (p) => p.reread.toLocaleString(),
    kind: 'number',
    priority: 'high',
  },
  { id: 'note', header: 'Note', accessor: (p) => p.refused ?? p.note ?? '', kind: 'text', priority: 'low', wrap: true },
];

function Totals({ preview }: Readonly<{ preview: ResetPreview }>) {
  const movable = preview.partitions.filter((p) => !p.refused);
  const skipped = movable.reduce((n, p) => n + p.skipped, 0);
  const reread = movable.reduce((n, p) => n + p.reread, 0);
  return (
    <Text size="sm" fw={600}>
      {`Moves ${plural(movable.length, 'partition')}: skips ${plural(skipped, 'record')} and reads ${plural(reread, 'record')} again.`}
    </Text>
  );
}

/**
 * Reset a group's committed offsets (ADR-0216): choose the topics and where to move, preview what each
 * partition would skip or read again and who still holds the group, then confirm by pressing and holding.
 * The preview's etag is what the reset runs against: a group that changed since is refused as stale, and
 * the operator previews again. A group with members or in a rebalance cannot be reset, and says which.
 */
export function OffsetResetDialog({
  clusterId,
  group,
  opened,
  onClose,
}: Readonly<{ clusterId: string; group: GroupDetail; opened: boolean; onClose: () => void }>) {
  const topics = group.topics.filter((t) => !t.deleted).map((t) => t.topic);
  const preview = usePreviewOffsetReset(clusterId, group.id);
  const reset = useResetOffsets(clusterId, group.id);
  const [chosen, setChosen] = useState<string[]>(topics);
  const [to, setTo] = useState<ResetTarget>('earliest');
  const [value, setValue] = useState('');
  const [problem, setProblem] = useState<string | null>(null);
  const [shown, setShown] = useState<{ key: string; data: ResetPreview } | null>(null);
  const [stale, setStale] = useState(false);
  const [partial, setPartial] = useState<ResetOutcome | null>(null);

  const request = (): ResetRequest => ({
    topics: chosen.length === topics.length ? undefined : chosen,
    to,
    value: to === 'time' || to === 'offset' ? value.trim() : undefined,
  });
  const key = JSON.stringify(request());
  const current = shown?.key === key ? shown.data : null;

  const forget = () => {
    setShown(null);
    setStale(false);
    setPartial(null);
    setProblem(null);
    preview.reset();
    reset.reset();
  };
  const runPreview = () => {
    forget();
    const issue = valueProblem(to, value);
    if (issue) return setProblem(issue);
    if (chosen.length === 0) return setProblem('Choose at least one topic.');
    preview.mutate(request(), { onSuccess: (data) => setShown({ key, data }) });
  };
  const dismiss = () => {
    forget();
    onClose();
  };
  const run = () => {
    if (!current) return;
    reset.mutate(
      { ...request(), etag: current.etag },
      {
        onSuccess: (outcome) => {
          if (outcome.failure) {
            // Part way: the topics that moved stay moved, so the dialog stays to say which did not, and why.
            notify.partial({
              action: RESET,
              subject: `the offsets of ${group.id}`,
              reached: `${outcome.movedTopics.length} of ${outcome.movedTopics.length + outcome.notMovedTopics.length} topics`,
              next: outcome.failure,
            });
            setPartial(outcome);
            return;
          }
          notify.succeeded({ action: RESET, subject: `the offsets of ${group.id}` });
          dismiss();
        },
        onError: (error) => {
          if (error.status === 412) {
            setStale(true);
            setShown(null);
            return;
          }
          if (error.status === 409 || error.status === 400) return;
          notify.settle(error, {
            action: RESET,
            subject: `the offsets of ${group.id}`,
            cause: error.message,
            next: 'Check the audit log for what ran, then try again.',
            onHeld: dismiss,
          });
        },
      },
    );
  };

  let blocked: string | undefined;
  if (partial) blocked = 'The reset stopped part way. Close this and preview the topics that did not move.';
  else if (stale) blocked = 'The group changed since the preview. Preview it again.';
  else if (reset.error?.status === 409) blocked = reset.error.message;
  else if (!current) blocked = 'Preview the reset first: the button waits for what it would move.';
  else if (current.blocked) blocked = current.blocked;
  else if (current.partitions.some((p) => p.refused)) {
    const n = current.partitions.filter((p) => p.refused).length;
    blocked = `${plural(n, 'partition')} cannot be moved to that position, as the table says. Choose another position or other topics.`;
  }
  const refused = reset.error && (reset.error.status === 409 || reset.error.status === 400) ? reset.error : null;

  return (
    <ConfirmDialog
      opened={opened}
      onClose={dismiss}
      title={`Reset the offsets of ${group.id}`}
      tone="danger"
      size="lg"
      confirmLabel={
        current ? `Reset ${plural(current.partitions.filter((p) => !p.refused).length, 'partition')}` : 'Reset offsets'
      }
      blocked={blocked}
      pending={reset.isPending}
      onConfirm={run}
      consequence={
        <Stack gap="sm">
          <Text size="sm">
            Moves where {group.id} will read next. Records it skips are not read by this group; records it reads again
            are processed twice. Only an empty group can be reset.
          </Text>
          <MultiSelect
            label="Topics"
            data={topics}
            value={chosen}
            onChange={(v) => {
              setChosen(v);
              forget();
            }}
            clearable={false}
          />
          <Select
            label="Move to"
            data={TARGETS}
            allowDeselect={false}
            value={to}
            onChange={(v) => {
              setTo((v ?? 'earliest') as ResetTarget);
              forget();
            }}
          />
          {to === 'offset' || to === 'time' ? (
            <TextInput
              label={to === 'offset' ? 'Offset' : 'Time (ISO-8601)'}
              value={value}
              error={problem}
              onChange={(e) => {
                setValue(e.currentTarget.value);
                forget();
              }}
            />
          ) : problem ? (
            <Text size="sm" c="red" role="alert">
              {problem}
            </Text>
          ) : null}
          <div>
            <Button size="xs" variant="default" loading={preview.isPending} onClick={runPreview}>
              Preview the reset
            </Button>
          </div>
          {preview.isError ? <ErrorState variant="inline" error={preview.error} /> : null}
          {stale ? (
            <Notice tone="warning" title="The group changed since the preview">
              Its members or offsets are not what the preview showed. Preview the reset again before confirming.
            </Notice>
          ) : null}
          {refused ? <RefusalNotice error={refused} /> : null}
          {partial ? <PartialReset outcome={partial} /> : null}
          {current && !partial ? (
            <Stack gap="xs" aria-live="polite">
              {current.blocked ? (
                <Notice tone="warning" title="This group cannot be reset right now">
                  <Stack gap={4}>
                    <Text size="sm">{current.blocked}</Text>
                    {current.members.length > 0 ? (
                      <Text size="sm">{`Members: ${current.members.join(', ')}.`}</Text>
                    ) : null}
                  </Stack>
                </Notice>
              ) : null}
              <Totals preview={current} />
              <DataTable
                variant="static"
                label="Partitions the reset would move"
                storageKey="kafka.reset"
                columns={COLUMNS}
                data={current.partitions}
                rowKey={(p) => `${p.topic}/${p.partition}`}
                height={{ maxRows: 10 }}
                empty={null}
              />
            </Stack>
          ) : null}
        </Stack>
      }
    />
  );
}

/** A reset the system refused: the reason, and for an offset out of range, which partitions. */
function RefusalNotice({ error }: Readonly<{ error: ApiError }>) {
  return (
    <Notice
      tone="warning"
      title={error.status === 409 ? 'The system refuses the reset' : 'The offsets are out of range'}
    >
      <Stack gap={4}>
        <Text size="sm">{error.message}</Text>
        {error.fieldErrors.map((f) => (
          <Text key={`${f.field}${f.message}`} size="sm">{`${f.field}: ${f.message}`}</Text>
        ))}
      </Stack>
    </Notice>
  );
}

/** A reset that stopped part way: which topics moved and how far, which stayed where they were, and why it stopped. */
function PartialReset({ outcome }: Readonly<{ outcome: ResetOutcome }>) {
  const movedIn = (topic: string) => outcome.partitions.filter((p) => p.topic === topic && p.next != null).length;
  return (
    <OutcomeSummary
      verdict={`Partly reset: ${outcome.failure}`}
      verdictTone="danger"
      total={`${outcome.movedTopics.length} of ${outcome.movedTopics.length + outcome.notMovedTopics.length} topics moved`}
      rows={[
        ...outcome.movedTopics.map((topic) => ({
          key: topic,
          name: topic,
          count: plural(movedIn(topic), 'partition'),
          status: 'Moved',
        })),
        ...outcome.notMovedTopics.map((topic) => ({
          key: topic,
          name: topic,
          status: 'Not moved',
          tone: 'danger' as const,
          detail: outcome.failure,
        })),
      ]}
    />
  );
}
