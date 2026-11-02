import { useState } from 'react';
import { Text } from '@mantine/core';

import { useCan } from '../../kernel/auth/useCan.ts';
import { useClusterSystem } from '../../kernel/shell/useClusterSystem.ts';
import { ConfirmDialog } from '../../ui/ConfirmDialog.tsx';
import { EmptyState } from '../../ui/EmptyState.tsx';
import { ErrorState } from '../../ui/ErrorState.tsx';
import { notify, type ActionVerb } from '../../ui/notify.ts';
import { Page } from '../../ui/Page.tsx';
import { Section } from '../../ui/Section.tsx';
import { DataTable } from '../../ui/table/index.ts';
import {
  useAlertRules,
  useCreateAlertRule,
  useDeleteAlertRule,
  useNotificationChannels,
  usePluginMetrics,
  useUpdateAlertRule,
  type AlertRuleRequest,
  type AlertRuleView,
} from './api.ts';
import { ruleColumns } from './columns.ts';
import { RuleForm } from './RuleForm.tsx';

const ADD: ActionVerb = { verb: 'Add', past: 'Added', progressive: 'Adding' };
const SAVE: ActionVerb = { verb: 'Save', past: 'Saved', progressive: 'Saving' };
const ENABLE: ActionVerb = { verb: 'Enable', past: 'Enabled', progressive: 'Enabling' };
const DISABLE: ActionVerb = { verb: 'Disable', past: 'Disabled', progressive: 'Disabling' };
const DELETE: ActionVerb = { verb: 'Delete', past: 'Deleted', progressive: 'Deleting' };

const rowKey = (r: AlertRuleView) => r.id;

/** The request that keeps a rule as it is, except for `enabled`. */
function withEnabled(r: AlertRuleView, enabled: boolean): AlertRuleRequest {
  return {
    name: r.name,
    kind: r.kind,
    metric: r.metric ?? undefined,
    comparator: r.comparator ?? undefined,
    threshold: r.threshold ?? undefined,
    stateCondition: r.stateCondition ?? undefined,
    forSeconds: r.forSeconds,
    severity: r.severity,
    enabled,
    channelIds: r.channelIds,
  };
}

/** The channels a deleted rule would have notified, in words. */
function channelsPhrase(count: number): string {
  if (count === 0) return 'any channel';
  return count === 1 ? 'its channel' : `its ${count} channels`;
}

/** Rule CRUD — thresholds and cluster-state conditions share one form and table (alerting spec). */
export function RulesPanel({ clusterId }: Readonly<{ clusterId: string }>) {
  const rules = useAlertRules(clusterId);
  const system = useClusterSystem(clusterId);
  const channels = useNotificationChannels();
  const pluginMetrics = usePluginMetrics(clusterId);
  const create = useCreateAlertRule(clusterId);
  const update = useUpdateAlertRule(clusterId);
  const remove = useDeleteAlertRule(clusterId);
  const { can, loading: grantsLoading } = useCan();
  // While grants load, offer the controls; the server is the enforcement point.
  const canWrite = grantsLoading || can('alert:write', clusterId);

  const [editing, setEditing] = useState<AlertRuleView | null>(null);
  // The dialog keeps what it was about while it fades out, so its words do not change under the reader.
  const [deleting, setDeleting] = useState<AlertRuleView | null>(null);
  const [deleteOpen, setDeleteOpen] = useState(false);
  const channelNames = new Map((channels.data ?? []).map((c) => [c.id, c.name]));

  const submit = (body: AlertRuleRequest) => {
    const subject = `rule "${body.name}"`;
    if (editing) {
      update.mutate(
        { ruleId: editing.id, body },
        {
          onSuccess: () => {
            setEditing(null);
            notify.succeeded({ action: SAVE, subject });
          },
          onError: (error) =>
            notify.settle(error, {
              action: SAVE,
              subject,
              cause: error.message,
              next: 'The rule is unchanged. Check the fields and save again.',
            }),
        },
      );
    } else {
      create.mutate(body, {
        onSuccess: () => notify.succeeded({ action: ADD, subject }),
        onError: (error) =>
          notify.settle(error, {
            action: ADD,
            subject,
            cause: error.message,
            next: 'Nothing was added. Check the fields and try again.',
          }),
      });
    }
  };

  const toggle = (rule: AlertRuleView) => {
    const action = rule.enabled ? DISABLE : ENABLE;
    const subject = `rule "${rule.name}"`;
    update.mutate(
      { ruleId: rule.id, body: withEnabled(rule, !rule.enabled) },
      {
        onSuccess: () => notify.succeeded({ action, subject }),
        onError: (error) =>
          notify.settle(error, {
            action,
            subject,
            cause: error.message,
            next: 'The switch shows what is stored; try again.',
          }),
      },
    );
  };

  const confirmDelete = (rule: AlertRuleView) => {
    const subject = `rule "${rule.name}"`;
    remove.mutate(rule.id, {
      onSuccess: () => {
        setDeleteOpen(false);
        notify.succeeded({ action: DELETE, subject });
      },
      onError: (error) =>
        notify.settle(error, {
          action: DELETE,
          subject,
          cause: error.message,
          next: 'It is still listed; try again.',
        }),
    });
  };

  const savingId = update.isPending ? update.variables.ruleId : undefined;
  // Built each render: the cells carry what is gated and busy right now.
  const columns = ruleColumns({
    channelNames,
    controls: {
      canWrite,
      savingId,
      onToggle: toggle,
      onEdit: setEditing,
      onDelete: (r) => {
        setDeleting(r);
        setDeleteOpen(true);
      },
    },
  });

  return (
    <Page>
      {canWrite ? null : (
        <Text size="sm">
          You cannot change rules: that needs the <code>alert:write</code> permission on this cluster.
        </Text>
      )}

      <Section title={editing ? `Edit "${editing.name}"` : 'New rule'}>
        <RuleForm
          key={editing?.id ?? 'new'}
          channels={channels.data ?? []}
          pluginMetrics={pluginMetrics.data ?? []}
          initial={editing ?? undefined}
          submitting={create.isPending || update.isPending}
          disabled={!canWrite}
          system={system}
          onCancel={editing ? () => setEditing(null) : undefined}
          onSubmit={submit}
        />
      </Section>

      <Section title="Rules">
        <DataTable
          variant="static"
          label="Alert rules"
          storageKey="alerting.rules"
          columns={columns}
          data={rules.data ?? []}
          rowKey={rowKey}
          loading={rules.isPending}
          error={rules.isError ? <ErrorState error={rules.error} onRetry={() => void rules.refetch()} /> : undefined}
          empty={
            <EmptyState
              kind="empty"
              title="No rules yet"
              description="A rule watches a metric or a cluster state and fires an alert when it holds. Add one with the form above, or edit the built-in split-brain, node-down and replication-behind rules seeded when this cluster was registered."
            />
          }
        />
      </Section>

      <ConfirmDialog
        opened={deleteOpen}
        onClose={() => setDeleteOpen(false)}
        title="Delete rule"
        tone="danger"
        pending={remove.isPending}
        confirmLabel="Delete rule"
        consequence={
          deleting ? (
            <>
              <strong>{deleting.name}</strong> stops being evaluated, so it no longer fires or notifies{' '}
              {channelsPhrase(deleting.channelIds.length)}. This cannot be undone.
            </>
          ) : null
        }
        onConfirm={() => deleting && confirmDelete(deleting)}
      />
    </Page>
  );
}
