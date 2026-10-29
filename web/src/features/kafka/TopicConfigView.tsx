import { useMemo, useState } from 'react';
import { Button, Stack, Text, TextInput } from '@mantine/core';
import { Link, useParams } from '@tanstack/react-router';

import { useCan } from '../../kernel/auth/useCan.ts';
import { CapabilityGate } from '../../ui/CapabilityGate.tsx';
import { gateFor, type GateVerdict } from '../../ui/capabilityGate.ts';
import { ConfirmDialog } from '../../ui/ConfirmDialog.tsx';
import { EmptyState } from '../../ui/EmptyState.tsx';
import { ErrorState } from '../../ui/ErrorState.tsx';
import linkClasses from '../../ui/InlineLink.module.css';
import { Notice } from '../../ui/Notice.tsx';
import { notify, type ActionVerb } from '../../ui/notify.ts';
import { Page } from '../../ui/Page.tsx';
import { PageHeader } from '../../ui/PageHeader.tsx';
import { StatusBadge } from '../../ui/StatusBadge.tsx';
import { Toolbar } from '../../ui/Toolbar.tsx';
import { DataTable, type Column } from '../../ui/table/index.ts';
import {
  useApplyTopicConfig,
  usePreviewTopicConfig,
  useTopicConfig,
  type ConfigChange,
  type ConfigEntry,
  type ConfigPreview,
} from './api.ts';

const APPLY: ActionVerb = { verb: 'Apply', past: 'Applied', progressive: 'Applying' };

/** What a pending edit of one entry is: a new value, or going back to the default. */
type Edit = { kind: 'set'; value: string } | { kind: 'remove' };

const SOURCE: Record<ConfigEntry['source'], string> = {
  TOPIC: 'Set on this topic',
  BROKER_DEFAULT: 'Broker default',
  STATIC: 'Broker file',
  DEFAULT: 'Kafka default',
};

const valueWords = (e: ConfigEntry): string => (e.sensitive ? 'Hidden (sensitive)' : (e.value ?? 'Not set'));

function changeOf(edits: Record<string, Edit>): ConfigChange {
  const set: Record<string, string> = {};
  const remove: string[] = [];
  for (const [name, edit] of Object.entries(edits)) {
    if (edit.kind === 'set') set[name] = edit.value;
    else remove.push(name);
  }
  return { set, remove };
}

/** The value column: a pending edit, or the value in force with its unit. */
function pendingValue(entry: ConfigEntry, edit: Edit | undefined): string {
  if (edit?.kind === 'set') return `${edit.value} (pending)`;
  if (edit) return 'Default (pending)';
  const unit = entry.unit ? ` ${entry.unit}` : '';
  return `${valueWords(entry)}${unit}`;
}

/** What the change field starts from: the pending value, or empty when the current one is hidden. */
function draftOf(entry: ConfigEntry, edit: Edit | undefined): string {
  if (edit?.kind === 'set') return edit.value;
  if (entry.sensitive) return '';
  return entry.value ?? '';
}

function topicColumns(
  edits: Record<string, Edit>,
  gate: GateVerdict,
  onEdit: (entry: ConfigEntry, draft: string) => void,
): Column<ConfigEntry>[] {
  return [
    { id: 'name', header: 'Setting', accessor: (entry) => entry.name, kind: 'identifier', priority: 'essential' },
    {
      id: 'value',
      header: 'Value',
      accessor: (entry) => pendingValue(entry, edits[entry.name]),
      kind: 'code',
      priority: 'essential',
    },
    {
      id: 'source',
      header: 'Comes from',
      accessor: (entry) => SOURCE[entry.source],
      cell: (entry) => (
        <StatusBadge tone={entry.source === 'TOPIC' ? 'info' : 'neutral'}>{SOURCE[entry.source]}</StatusBadge>
      ),
      kind: 'status',
      badge: true,
      priority: 'high',
    },
    {
      id: 'default',
      header: 'Default',
      accessor: (entry) => entry.defaultValue ?? '',
      kind: 'code',
      priority: 'low',
    },
    {
      id: 'edit',
      header: 'Change',
      accessor: () => '',
      cell: (entry) =>
        entry.readOnly ? (
          <Text size="xs" c="dimmed">
            Read-only
          </Text>
        ) : (
          <CapabilityGate verdict={gate} what={`changing ${entry.name}`}>
            <Button
              size="compact-xs"
              variant="subtle"
              disabled={gate.kind === 'blocked'}
              aria-label={`Change ${entry.name}`}
              onClick={() => onEdit(entry, draftOf(entry, edits[entry.name]))}
            >
              Change
            </Button>
          </CapabilityGate>
        ),
      kind: 'status',
      priority: 'essential',
    },
  ];
}

/** How many edits are waiting, for the toolbar. */
function pendingWords(pending: number): string {
  if (pending === 0) return 'No changes yet';
  const suffix = pending === 1 ? '' : 's';
  return `${pending} change${suffix} to review`;
}

/** The value in force, for the field that replaces it. A sensitive value is never repeated. */
function editDescription(editing: ConfigEntry | null): string {
  if (editing?.sensitive) return 'A sensitive value is never shown. Enter a new one to replace it.';
  const current = editing?.value ?? 'not set';
  const unit = editing?.unit ? ` ${editing.unit}` : '';
  const fallback = editing?.defaultValue ?? 'none';
  return `Now ${current}${unit}. Default ${fallback}.`;
}

/** Why applying is not offered: the review is stale, or the system rejects a change. */
function applyBlocked(changed: boolean, rejected: boolean): string | undefined {
  if (changed) return 'The configuration changed since this review. Close it and review the changes again.';
  if (rejected) return 'The system rejects at least one change. Fix it, then review again.';
  return undefined;
}

/**
 * A topic's configuration with where each value comes from. Edits are collected, reviewed against what they
 * would change (the value before and after, what a removed entry falls back to, and the driver's own
 * validation), and applied against the state the review read. A sensitive value is never sent here.
 */
export function TopicConfigView() {
  const { clusterId, topic } = useParams({ strict: false }) as { clusterId: string; topic: string };
  const config = useTopicConfig(clusterId, topic);
  const preview = usePreviewTopicConfig(clusterId, topic);
  const apply = useApplyTopicConfig(clusterId, topic);
  const { can, loading } = useCan();
  const readOnlyReason = config.data?.readOnlyReason ?? null;
  const gate = useMemo<GateVerdict>(
    () =>
      readOnlyReason
        ? { kind: 'blocked', reason: readOnlyReason }
        : gateFor(can('destination:update', clusterId), 'Change topic configuration', undefined, loading),
    [readOnlyReason, can, clusterId, loading],
  );
  const [edits, setEdits] = useState<Record<string, Edit>>({});
  const [editing, setEditing] = useState<ConfigEntry | null>(null);
  const [draft, setDraft] = useState('');
  const [reviewed, setReviewed] = useState<ConfigPreview | null>(null);
  const pending = Object.keys(edits).length;

  const columns = useMemo(
    () =>
      topicColumns(edits, gate, (entry, draft) => {
        setEditing(entry);
        setDraft(draft);
      }),
    [edits, gate],
  );

  const review = () => {
    preview.mutate(changeOf(edits), { onSuccess: setReviewed });
  };
  const close = () => {
    setReviewed(null);
    preview.reset();
    apply.reset();
  };
  const hasError = reviewed ? !reviewed.valid || reviewed.changes.some((e) => e.error) : false;
  const run = () => {
    if (!reviewed) return;
    apply.mutate(
      { ...changeOf(edits), etag: reviewed.etag },
      {
        onSuccess: () => {
          notify.succeeded({ action: APPLY, subject: `the configuration of ${topic}` });
          setEdits({});
          close();
        },
        onError: (error) => {
          if (error.status === 412) return;
          notify.settle(error, {
            action: APPLY,
            subject: `the configuration of ${topic}`,
            cause: error.message,
            next: 'Check the audit log for what ran, then try again.',
            onHeld: close,
          });
        },
      },
    );
  };

  return (
    <Page fill>
      <PageHeader
        title={`Configuration of ${topic}`}
        meta={
          <Link to={`/clusters/${clusterId}/destinations`} className={linkClasses.link}>
            All destinations
          </Link>
        }
        description="Each setting with the value in force and where it comes from. Change as many as you need, then review them together."
      />
      {readOnlyReason ? (
        <Notice tone="warning" title="Read-only configuration">
          {readOnlyReason}
        </Notice>
      ) : null}
      {config.data?.internal ? (
        <Notice tone="info" title="An internal topic">
          Kafka manages this topic itself; changing its settings can affect the whole cluster.
        </Notice>
      ) : null}
      <Toolbar
        label="Pending changes"
        end={
          <>
            <Text size="xs" c="dimmed" role="status">
              {pendingWords(pending)}
            </Text>
            <Button size="xs" variant="subtle" disabled={pending === 0} onClick={() => setEdits({})}>
              Discard
            </Button>
            <Button size="xs" disabled={pending === 0} loading={preview.isPending} onClick={review}>
              Review changes
            </Button>
          </>
        }
      />
      <DataTable
        label="Topic configuration"
        storageKey="kafka.topic-config"
        height="fill"
        columns={columns}
        data={config.data?.entries ?? []}
        rowKey={(e) => e.name}
        loading={config.isPending}
        error={config.isError ? <ErrorState error={config.error} onRetry={() => void config.refetch()} /> : undefined}
        empty={
          <EmptyState
            kind="empty"
            title="No settings"
            description="The system reported no configuration for this topic."
          />
        }
      />

      <ConfirmDialog
        opened={editing !== null}
        onClose={() => setEditing(null)}
        title={`Change ${editing?.name ?? ''}`}
        confirmLabel="Keep this change"
        consequence={
          <Stack gap="sm">
            <TextInput
              label="New value"
              description={editDescription(editing)}
              value={draft}
              onChange={(e) => setDraft(e.currentTarget.value)}
              data-autofocus
            />
            {editing?.source === 'TOPIC' ? (
              <Button
                size="xs"
                variant="default"
                onClick={() => {
                  if (editing) setEdits((s) => ({ ...s, [editing.name]: { kind: 'remove' } }));
                  setEditing(null);
                }}
              >
                Remove the override and use the default
              </Button>
            ) : null}
          </Stack>
        }
        blocked={draft.trim() === '' ? 'Enter the new value, or remove the override.' : undefined}
        onConfirm={() => {
          if (editing) setEdits((s) => ({ ...s, [editing.name]: { kind: 'set', value: draft.trim() } }));
          setEditing(null);
        }}
      />

      <ConfirmDialog
        opened={reviewed !== null}
        onClose={close}
        title={`Apply ${pending} change${pending === 1 ? '' : 's'} to ${topic}`}
        confirmLabel={`Apply ${pending} change${pending === 1 ? '' : 's'}`}
        blocked={applyBlocked(apply.error?.status === 412, hasError)}
        pending={apply.isPending}
        onConfirm={run}
        consequence={
          <DataTable
            variant="static"
            label="Changes to apply"
            storageKey="kafka.topic-config.review"
            columns={REVIEW_COLUMNS}
            data={reviewed?.changes ?? []}
            rowKey={(e) => e.name}
            height={{ maxRows: 10 }}
            empty={null}
          />
        }
      />
    </Page>
  );
}

const REVIEW_COLUMNS: Column<ConfigPreview['changes'][number]>[] = [
  { id: 'name', header: 'Setting', accessor: (e) => e.name, kind: 'identifier', priority: 'essential' },
  { id: 'before', header: 'Before', accessor: (e) => e.before ?? 'Not set', kind: 'code', priority: 'essential' },
  {
    id: 'after',
    header: 'After',
    accessor: (e) => e.after ?? (e.fallback ? `${e.fallback} (default)` : 'Not set'),
    kind: 'code',
    priority: 'essential',
  },
  {
    id: 'error',
    header: 'Check',
    accessor: (e) => e.error ?? 'Accepted',
    cell: (e) => (e.error ? <StatusBadge tone="danger">{e.error}</StatusBadge> : 'Accepted'),
    kind: 'text',
    priority: 'essential',
    wrap: true,
  },
];
