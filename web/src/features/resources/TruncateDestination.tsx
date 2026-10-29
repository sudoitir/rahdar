import { useState } from 'react';
import { Button, Radio, Stack, Text, Textarea, TextInput } from '@mantine/core';
import { IconCut } from '@tabler/icons-react';

import { useResourceGate } from '../../kernel/auth/useResourceGate.ts';
import type { ActionProps, DestinationTarget, HostedDialogProps } from '../../kernel/actions/types.ts';
import { ActionMenuItem } from '../../ui/ActionMenuItem.tsx';
import { ConfirmDialog } from '../../ui/ConfirmDialog.tsx';
import { ErrorState } from '../../ui/ErrorState.tsx';
import { DataTable } from '../../ui/table/index.ts';
import { Notice } from '../../ui/Notice.tsx';
import { notify, type ActionVerb } from '../../ui/notify.ts';
import { useCluster } from '../clusters/index.ts';
import { parseCut, useTruncateDestination, type TruncateEffect } from './truncate.ts';

const TRUNCATE: ActionVerb = { verb: 'Truncate', past: 'Truncated', progressive: 'Truncating' };

const plural = (n: number, noun: string) => `${n.toLocaleString()} ${noun}${n === 1 ? '' : 's'}`;

/**
 * "Truncate…" on a destination whose kind has the verb: delete its records before a cut. The dialog takes
 * the cut, previews what it would remove and which groups lose records they have not read, and only that
 * preview can be confirmed, by pressing and holding a button that names the action and the count.
 */
export function TruncateDestinationDialog({
  clusterId,
  destination,
  opened,
  onClose,
}: Readonly<HostedDialogProps & { clusterId: string; destination: { id: string; name: string } }>) {
  const truncate = useTruncateDestination(clusterId, destination.id);
  const [mode, setMode] = useState<'offsets' | 'time'>('offsets');
  const [text, setText] = useState('');
  const [problem, setProblem] = useState<string | null>(null);
  const [previewed, setPreviewed] = useState<{ cut: string; effect: TruncateEffect } | null>(null);
  const [refusal, setRefusal] = useState<ApiErrorLike | null>(null);
  const [stale, setStale] = useState(false);

  const cut = parseCut(mode, text);
  const signature = `${mode}\u0000${text.trim()}`;
  const current = previewed?.cut === signature ? previewed.effect : null;

  const reset = () => {
    setPreviewed(null);
    setRefusal(null);
    setStale(false);
    setProblem(null);
  };
  const preview = () => {
    reset();
    if ('problem' in cut) return setProblem(cut.problem);
    truncate.mutate(
      { before: cut.before, dryRun: true },
      { onSuccess: (effect) => setPreviewed({ cut: signature, effect }), onError: setRefusal },
    );
  };
  const run = () => {
    if (!('before' in cut) || !current?.etag) return;
    truncate.mutate(
      { before: cut.before, etag: current.etag },
      {
        onSuccess: () => {
          notify.succeeded({ action: TRUNCATE, subject: destination.name });
          dismiss();
        },
        onError: (error) => {
          if (error.status === 412) {
            setStale(true);
            setPreviewed(null);
            return;
          }
          notify.settle(error, {
            action: TRUNCATE,
            subject: destination.name,
            cause: error.message,
            next: 'Check the audit log for what ran, then try again.',
            onHeld: dismiss,
          });
        },
      },
    );
  };
  const dismiss = () => {
    reset();
    truncate.reset();
    setText('');
    onClose();
  };

  let blocked: string | undefined;
  if (stale) blocked = 'The log changed since the preview. Preview the cut again.';
  else if (refusal) blocked = refusal.message;
  else if (!current) blocked = 'Preview the cut first: the button waits for what it would remove.';
  const count = current?.records ?? null;

  return (
    <ConfirmDialog
      opened={opened}
      onClose={dismiss}
      title={`Truncate ${destination.name}`}
      tone="danger"
      align="top"
      confirmLabel={count === null ? 'Truncate' : `Truncate ${plural(count, 'record')}`}
      blocked={blocked}
      pending={truncate.isPending && !truncate.variables?.dryRun}
      onConfirm={run}
      consequence={
        <Stack gap="sm">
          <Text size="sm">
            Deletes the records of {destination.name} before the cut, for every reader. This cannot be undone.
          </Text>
          <Radio.Group
            label="Cut by"
            value={mode}
            onChange={(v) => {
              setMode(v as 'offsets' | 'time');
              reset();
            }}
          >
            <Stack gap={4} mt={4}>
              <Radio value="offsets" label="Offsets, one per partition" />
              <Radio value="time" label="A time" />
            </Stack>
          </Radio.Group>
          {mode === 'offsets' ? (
            <Textarea
              label="Delete records before"
              description="partition:offset, separated by commas, such as 0:120, 1:80"
              autosize
              minRows={2}
              value={text}
              error={problem}
              onChange={(e) => {
                setText(e.currentTarget.value);
                reset();
              }}
            />
          ) : (
            <TextInput
              label="Delete records before"
              description="An ISO-8601 time, such as 2026-10-28T09:30:00Z"
              value={text}
              error={problem}
              onChange={(e) => {
                setText(e.currentTarget.value);
                reset();
              }}
            />
          )}
          <div>
            <PreviewButton pending={truncate.isPending && Boolean(truncate.variables?.dryRun)} onPreview={preview} />
          </div>
          {refusal ? (
            <Notice tone="warning" title="This cut is refused">
              {refusal.message}
            </Notice>
          ) : null}
          {stale ? (
            <Notice tone="warning" title="The log changed since the preview">
              Records were added or removed after the preview, so what it showed is out of date. Preview the cut again
              before confirming.
            </Notice>
          ) : null}
          {truncate.isError && !truncate.variables?.dryRun && !refusal && !stale ? (
            <ErrorState variant="inline" error={truncate.error} />
          ) : null}
          {current ? <EffectSummary effect={current} /> : null}
        </Stack>
      }
    />
  );
}

type ApiErrorLike = { message: string };

function PreviewButton({ pending, onPreview }: Readonly<{ pending: boolean; onPreview: () => void }>) {
  return (
    <Button size="xs" variant="default" loading={pending} onClick={onPreview}>
      Preview the cut
    </Button>
  );
}

/** What the cut would remove, and every group that would lose records it has not read. */
function EffectSummary({ effect }: Readonly<{ effect: TruncateEffect }>) {
  return (
    <Stack gap="xs" aria-live="polite">
      <Text size="sm" fw={600}>
        {effect.records == null
          ? 'The number of records this removes could not be counted.'
          : `This removes ${plural(effect.records, 'record')}.`}
      </Text>
      {(effect.groups ?? []).length > 0 ? (
        <DataTable
          variant="static"
          label="Groups that lose unread records"
          storageKey="truncate.groups"
          columns={[
            { id: 'group', header: 'Group', accessor: (g) => g.group ?? '', kind: 'identifier', priority: 'essential' },
            {
              id: 'records',
              header: 'Unread records lost',
              short: 'Lost',
              accessor: (g) => g.records.toLocaleString(),
              kind: 'number',
              priority: 'essential',
            },
          ]}
          data={effect.groups ?? []}
          rowKey={(g) => g.group ?? ''}
          height={{ maxRows: 8 }}
          empty={null}
        />
      ) : (
        <Text size="sm" c="dimmed">
          No group loses a record it has not read.
        </Text>
      )}
    </Stack>
  );
}

/** The destination's "Truncate…" item: shown on every destination, disabled with a reason where the kind lacks the verb. */
export function DestinationTruncate({ clusterId, target, host }: Readonly<ActionProps<DestinationTarget>>) {
  const cluster = useCluster(clusterId);
  const { hidden, verdict } = useResourceGate({
    clusterId,
    noun: 'destination',
    permission: 'destination:truncate',
    label: 'Truncate destinations',
    resource: target.snapshot,
    kind: { id: target.kind, verb: 'truncate' },
    capability: cluster.data?.capabilities.managementWrite,
    pending: cluster.isPending,
  });
  const offered = cluster.data?.kinds?.some((k) => k.verbs.includes('truncate'));
  // A system with no truncate anywhere does not carry the item at all; one that does shows it on every destination.
  if (hidden || (cluster.data && !offered)) return null;
  return (
    <ActionMenuItem
      label="Truncate…"
      icon={<IconCut size="1rem" aria-hidden />}
      tone="danger"
      verdict={verdict}
      onExplain={(v) => host.explain(v, 'truncating this destination')}
      onSelect={() =>
        host.open(TruncateDestinationDialog, { clusterId, destination: { id: target.id, name: target.name } })
      }
    />
  );
}
