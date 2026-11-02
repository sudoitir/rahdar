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
  useApplyBrokerConfig,
  useBrokerConfig,
  usePreviewBrokerConfig,
  type BrokerConfigEntry,
  type ConfigChange,
  type ConfigPreview,
} from './api.ts';

const APPLY: ActionVerb = { verb: 'Apply', past: 'Applied', progressive: 'Applying' };
const CLUSTER_DEFAULT = 'default';
const RESTART_REASON = 'Set it in server.properties and restart the broker.';

/** What a pending edit of one entry is: a new value, or going back to what the level above gives. */
type Edit = { kind: 'set'; value: string } | { kind: 'remove' };

const SOURCE: Record<BrokerConfigEntry['source'], string> = {
  NODE: 'Set on this broker',
  CLUSTER: 'Cluster default',
  STATIC: 'Broker file',
  DEFAULT: 'Kafka default',
};

function changeOf(edits: Record<string, Edit>): ConfigChange {
  const set: Record<string, string> = {};
  const remove: string[] = [];
  for (const [name, edit] of Object.entries(edits)) {
    if (edit.kind === 'set') set[name] = edit.value;
    else remove.push(name);
  }
  return { set, remove };
}

function pendingValue(entry: BrokerConfigEntry, edit: Edit | undefined): string {
  if (edit?.kind === 'set') return `${edit.value} (pending)`;
  if (edit) return 'Override removed (pending)';
  const unit = entry.unit && entry.value ? ` ${entry.unit}` : '';
  return `${entry.value || 'Not set'}${unit}`;
}

function currentValueWords(entry: BrokerConfigEntry | null): string {
  const value = entry?.value || 'not set';
  const unit = entry?.unit ? ` ${entry.unit}` : '';
  return `Now ${value}${unit}.`;
}

function pendingWords(pending: number): string {
  if (pending === 0) return 'No changes yet';
  return `${pending} change${pending === 1 ? '' : 's'} to review`;
}

/** Whether an entry holds an override at this page's level, which can be removed. */
const overridden = (entry: BrokerConfigEntry, clusterDefault: boolean): boolean =>
  entry.source === (clusterDefault ? 'CLUSTER' : 'NODE');

function brokerColumns(
  edits: Record<string, Edit>,
  gate: GateVerdict,
  onEdit: (entry: BrokerConfigEntry) => void,
): Column<BrokerConfigEntry>[] {
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
        <StatusBadge tone={entry.source === 'NODE' || entry.source === 'CLUSTER' ? 'info' : 'neutral'}>
          {SOURCE[entry.source]}
        </StatusBadge>
      ),
      kind: 'status',
      badge: true,
      priority: 'high',
    },
    {
      id: 'edit',
      header: 'Change',
      accessor: () => '',
      cell: (entry) =>
        entry.readOnly ? (
          <Text size="xs" c="dimmed">
            Needs a restart. {RESTART_REASON}
          </Text>
        ) : (
          <CapabilityGate verdict={gate} what={`changing ${entry.name}`}>
            <Button
              size="compact-xs"
              variant="subtle"
              disabled={gate.kind === 'blocked'}
              aria-label={`Change ${entry.name}`}
              onClick={() => onEdit(entry)}
            >
              Change
            </Button>
          </CapabilityGate>
        ),
      kind: 'text',
      wrap: true,
      priority: 'essential',
    },
  ];
}

/** Why applying is not offered: the review is stale. */
const staleWords = (stale: boolean): string | undefined =>
  stale ? 'The configuration changed since this review. Close it and review the changes again.' : undefined;

/**
 * A broker's configuration, or the cluster-wide default, with where each value comes from. Changes are collected,
 * reviewed against what they would replace, and applied against the state the review read. A setting that only a
 * restart changes is shown as a fact with its reason, and one that holds a secret is never listed.
 */
export function BrokerConfigView() {
  const { clusterId, broker } = useParams({ strict: false }) as { clusterId: string; broker: string };
  const config = useBrokerConfig(clusterId, broker);
  const preview = usePreviewBrokerConfig(clusterId, broker);
  const apply = useApplyBrokerConfig(clusterId, broker);
  const { can, loading } = useCan();
  const clusterDefault = broker === CLUSTER_DEFAULT;
  const subject = clusterDefault ? 'the cluster default' : `broker ${broker}`;
  const gate = useMemo<GateVerdict>(
    () => gateFor(can('destination:update', clusterId), 'Change broker configuration', undefined, loading),
    [can, clusterId, loading],
  );
  const [edits, setEdits] = useState<Record<string, Edit>>({});
  const [editing, setEditing] = useState<BrokerConfigEntry | null>(null);
  const [adding, setAdding] = useState(false);
  const [draft, setDraft] = useState('');
  const [draftName, setDraftName] = useState('');
  const [reviewed, setReviewed] = useState<ConfigPreview | null>(null);
  const pending = Object.keys(edits).length;

  // A setting named by hand is not listed until it is set, so it joins the rows while it is pending.
  const rows = useMemo<BrokerConfigEntry[]>(() => {
    const listed = config.data?.entries ?? [];
    const extra = Object.keys(edits)
      .filter((name) => !listed.some((e) => e.name === name))
      .map((name) => ({ name, value: '', source: 'DEFAULT' as const, readOnly: false }));
    return [...listed, ...extra];
  }, [config.data, edits]);

  const columns = useMemo(
    () =>
      brokerColumns(edits, gate, (entry) => {
        setEditing(entry);
        setDraft(edits[entry.name]?.kind === 'set' ? (edits[entry.name] as { value: string }).value : entry.value);
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
  const run = () => {
    if (!reviewed) return;
    apply.mutate(
      { ...changeOf(edits), etag: reviewed.etag },
      {
        onSuccess: () => {
          notify.succeeded({ action: APPLY, subject: `the configuration of ${subject}` });
          setEdits({});
          close();
        },
        onError: (error) => {
          if (error.status === 412) return;
          notify.settle(error, {
            action: APPLY,
            subject: `the configuration of ${subject}`,
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
        title={clusterDefault ? 'Cluster default configuration' : `Configuration of broker ${broker}`}
        meta={
          <Link to={`/clusters/${clusterId}/kafka/brokers`} className={linkClasses.link}>
            All brokers
          </Link>
        }
        description="Each setting with the value in force and where it comes from. Change as many as you need, then review them together."
      />
      {config.data?.note ? (
        <Notice tone="info" title="Only what is set is listed">
          {config.data.note}
        </Notice>
      ) : null}
      {preview.isError ? (
        <Notice tone="danger" title="This change was refused, so nothing was applied">
          {preview.error.message} Change or discard the setting, then review again.
        </Notice>
      ) : null}
      <Toolbar
        label="Pending changes"
        end={
          <>
            <Text size="xs" c="dimmed" role="status">
              {pendingWords(pending)}
            </Text>
            <CapabilityGate verdict={gate} what="adding a setting">
              <Button
                size="xs"
                variant="default"
                disabled={gate.kind === 'blocked'}
                onClick={() => {
                  setDraft('');
                  setDraftName('');
                  setAdding(true);
                }}
              >
                Add a setting
              </Button>
            </CapabilityGate>
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
        label="Broker configuration"
        storageKey="kafka.broker-config"
        height="fill"
        columns={columns}
        data={rows}
        rowKey={(e) => e.name}
        loading={config.isPending}
        error={config.isError ? <ErrorState error={config.error} onRetry={() => void config.refetch()} /> : undefined}
        empty={
          <EmptyState
            kind="empty"
            title="No settings"
            description="Nothing is set here. Add a setting to give every broker without its own value a new one."
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
              description={currentValueWords(editing)}
              value={draft}
              onChange={(e) => setDraft(e.currentTarget.value)}
              data-autofocus
            />
            {editing && overridden(editing, clusterDefault) ? (
              <Button
                size="xs"
                variant="default"
                onClick={() => {
                  setEdits((s) => ({ ...s, [editing.name]: { kind: 'remove' } }));
                  setEditing(null);
                }}
              >
                Remove the override
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
        opened={adding}
        onClose={() => setAdding(false)}
        title="Add a setting"
        confirmLabel="Keep this change"
        consequence={
          <Stack gap="sm">
            <TextInput
              label="Setting"
              description="A dynamic broker setting, such as message.max.bytes."
              value={draftName}
              onChange={(e) => setDraftName(e.currentTarget.value)}
              data-autofocus
            />
            <TextInput label="Value" value={draft} onChange={(e) => setDraft(e.currentTarget.value)} />
          </Stack>
        }
        blocked={draftName.trim() === '' || draft.trim() === '' ? 'Enter the setting and its value.' : undefined}
        onConfirm={() => {
          setEdits((s) => ({ ...s, [draftName.trim()]: { kind: 'set', value: draft.trim() } }));
          setAdding(false);
        }}
      />

      <ConfirmDialog
        opened={reviewed !== null}
        onClose={close}
        title={`Apply ${pending} change${pending === 1 ? '' : 's'} to ${subject}`}
        confirmLabel={`Apply ${pending} change${pending === 1 ? '' : 's'}`}
        blocked={staleWords(apply.error?.status === 412)}
        pending={apply.isPending}
        onConfirm={run}
        consequence={
          <DataTable
            variant="static"
            label="Changes to apply"
            storageKey="kafka.broker-config.review"
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
    accessor: (e) => e.after ?? 'Override removed',
    kind: 'code',
    priority: 'essential',
  },
];
