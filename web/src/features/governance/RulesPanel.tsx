import { useState } from 'react';
import { Button, Group, Modal, Select, Stack, Switch, Text, TextInput } from '@mantine/core';
import { useForm } from '@mantine/form';

import { useCan } from '../../kernel/auth/useCan.ts';
import type { ApiError } from '../../kernel/api/request.ts';
import { ConfirmDialog } from '../../ui/ConfirmDialog.tsx';
import { EmptyState } from '../../ui/EmptyState.tsx';
import { ErrorState } from '../../ui/ErrorState.tsx';
import { focusFirstInvalid } from '../../ui/formErrors.ts';
import { notify, type ActionVerb } from '../../ui/notify.ts';
import { Section } from '../../ui/Section.tsx';
import { DataTable } from '../../ui/table/index.ts';
import {
  useCreateRule,
  useDeleteRule,
  useRemaskProgress,
  useRules,
  useUpdateRule,
  withEnabled,
  type RuleRequest,
  type RuleView,
} from './api.ts';
import { ruleColumns } from './columns.ts';
import classes from './Governance.module.css';
import { TARGETS } from './words.ts';

const SAVE: ActionVerb = { verb: 'Save', past: 'Saved', progressive: 'Saving' };
const ENABLE: ActionVerb = { verb: 'Enable', past: 'Enabled', progressive: 'Enabling' };
const DISABLE: ActionVerb = { verb: 'Disable', past: 'Disabled', progressive: 'Disabling' };
const DELETE: ActionVerb = { verb: 'Delete', past: 'Deleted', progressive: 'Deleting' };

const rowKey = (r: RuleView) => r.id;

/** Whether stored messages have caught up with the policy. Reads always apply the current policy either way. */
function RemaskStatus() {
  const progress = useRemaskProgress();
  let status;
  if (progress.isPending) {
    status = <Text size="sm">Checking whether stored messages are masked under the current policy…</Text>;
  } else if (progress.isError) {
    status = <ErrorState variant="inline" error={progress.error} onRetry={() => void progress.refetch()} />;
  } else {
    const { rowsUnderEarlierVersion: rows, capped, version } = progress.data;
    status =
      rows === 0 ? (
        <Text size="sm">Every stored message is masked under the current policy (version {version}).</Text>
      ) : (
        <Text size="sm" className={classes.figures}>
          {capped ? 'More than ' : ''}
          {rows.toLocaleString()} stored message{rows === 1 ? ' is' : 's are'} still masked under an earlier policy.
          Re-masking runs in the background; reads already apply version {version}.
        </Text>
      );
  }
  return <div className={classes.remask}>{status}</div>;
}

const CLASSES = [
  { value: 'CREDENTIAL', label: 'Credential' },
  { value: 'PAN', label: 'Payment card number' },
  { value: 'IBAN', label: 'IBAN' },
  { value: 'EMAIL', label: 'Email' },
  { value: 'PHONE', label: 'Phone number' },
  { value: 'NATIONAL_ID', label: 'National identifier' },
  { value: 'PERSONAL', label: 'Personal data' },
];

const ACTIONS = [
  { value: 'DEFAULT', label: 'The class default' },
  { value: 'DROP', label: 'Drop — never shown' },
  { value: 'PARTIAL', label: 'Partial — last four kept' },
  { value: 'REDACT', label: 'Redact — whole value' },
];

const WRITE_REASON = 'Changing masking rules needs the governance:write permission.';

const EMPTY: RuleRequest = {
  destinationPattern: null,
  target: 'PROPERTY',
  selector: '',
  dataClass: 'PERSONAL',
  action: null,
  enabled: true,
};

function selectorProblem(selector: string, target: string): string | null {
  if (selector.trim() !== '') return null;
  return target === 'BODY_PATH'
    ? 'Enter the JSON path the rule matches, such as payment.card.'
    : 'Enter the name the rule matches. Use * for any run of characters.';
}

function addressProblem(destinationPattern: string | null | undefined): string | null {
  return destinationPattern && /\s/.test(destinationPattern)
    ? 'A destination pattern has no spaces. Use a pattern such as orders.# or orders.*.'
    : null;
}

/** The content policy's masking rules (data-governance spec). Built-in credential rules can be disabled, never deleted. */
export function RulesPanel() {
  const rules = useRules();
  const create = useCreateRule();
  const update = useUpdateRule();
  const remove = useDeleteRule();
  const { can, loading } = useCan();
  // While grants load, offer the control: the server decides (operator-ui spec).
  const canWrite = loading || can('governance:write');

  const [editing, setEditing] = useState<RuleView | 'new' | null>(null);
  const form = useForm<RuleRequest>({
    initialValues: EMPTY,
    validateInputOnBlur: true,
    validate: {
      selector: (v, values) => selectorProblem(v, values.target),
      destinationPattern: addressProblem,
    },
  });
  const [saveError, setSaveError] = useState<ApiError | null>(null);
  // The dialog keeps what it was about while it fades out, so its words do not change under the reader.
  const [deleting, setDeleting] = useState<RuleView | null>(null);
  const [deleteOpen, setDeleteOpen] = useState(false);

  function openNew() {
    setEditing('new');
    form.setValues(EMPTY);
    form.clearErrors();
    setSaveError(null);
  }

  function openEdit(rule: RuleView) {
    setEditing(rule);
    form.setValues(withEnabled(rule, rule.enabled));
    form.clearErrors();
    setSaveError(null);
  }

  const save = form.onSubmit((values) => {
    const body: RuleRequest = {
      ...values,
      selector: values.selector.trim(),
      destinationPattern: values.destinationPattern?.trim() ? values.destinationPattern.trim() : null,
    };
    const handlers = {
      onSuccess: (rule: RuleView) => {
        setEditing(null);
        notify.succeeded({ action: SAVE, subject: `the rule for ${rule.selector}` });
      },
      onError: (e: ApiError) => setSaveError(e),
    };
    setSaveError(null);
    if (editing === 'new') create.mutate(body, handlers);
    else if (editing) update.mutate({ ruleId: editing.id, body }, handlers);
  }, focusFirstInvalid(form.getInputNode));

  function toggle(rule: RuleView, enabled: boolean) {
    const action = enabled ? ENABLE : DISABLE;
    const subject = `the rule for ${rule.selector}`;
    update.mutate(
      { ruleId: rule.id, body: withEnabled(rule, enabled) },
      {
        onSuccess: () => notify.succeeded({ action, subject }),
        onError: (e) =>
          notify.settle(e, {
            action,
            subject,
            cause: e.message,
            next: 'The switch shows what is stored; try again.',
          }),
      },
    );
  }

  function confirmDelete(rule: RuleView) {
    const subject = `the rule for ${rule.selector}`;
    remove.mutate(rule.id, {
      onSuccess: () => {
        setDeleteOpen(false);
        notify.succeeded({ action: DELETE, subject });
      },
      onError: (e) =>
        notify.settle(e, {
          action: DELETE,
          subject,
          cause: e.message,
          next: 'It is still listed; try again.',
        }),
    });
  }

  const savingId = update.isPending ? update.variables.ruleId : undefined;
  // Built each render: the cells carry what is gated and busy right now.
  const columns = ruleColumns({
    controls: {
      canWrite,
      savingId,
      onToggle: toggle,
      onEdit: openEdit,
      onDelete: (r) => {
        setDeleting(r);
        setDeleteOpen(true);
      },
    },
  });

  return (
    <Section
      title="Masking rules"
      description={
        <>
          A rule masks a header, a property or a JSON body value wherever message content leaves Rahdar or is stored.
          Values the detectors recognise are masked even without a rule. Credentials are never shown, and users with{' '}
          <code>message:clear</code> see every other value in clear.
        </>
      }
    >
      {canWrite ? null : <Text size="sm">{WRITE_REASON}</Text>}
      <RemaskStatus />

      <DataTable
        variant="static"
        label="Masking rules"
        storageKey="governance.rules"
        columns={columns}
        data={rules.data ?? []}
        rowKey={rowKey}
        loading={rules.isPending}
        error={rules.isError ? <ErrorState error={rules.error} onRetry={() => void rules.refetch()} /> : undefined}
        toolbar={{
          start: (
            <Button size="xs" onClick={openNew} disabled={!canWrite}>
              New rule
            </Button>
          ),
          end: rules.data ? (
            <Text size="sm" c="dimmed">
              {rules.data.length} rule{rules.data.length === 1 ? '' : 's'}
            </Text>
          ) : undefined,
        }}
        empty={
          <EmptyState
            kind="empty"
            title="No masking rules"
            description="A rule names a header, property or JSON body path whose value is masked wherever message content leaves Rahdar. Add one with New rule."
          />
        }
      />

      <Modal
        opened={editing !== null}
        onClose={() => setEditing(null)}
        title={editing === 'new' ? 'New masking rule' : `Edit the rule for ${form.values.selector}`}
        size="md"
      >
        <form noValidate onSubmit={save}>
          <Stack gap="sm">
            <Select label="Matches a" data={TARGETS} {...form.getInputProps('target')} allowDeselect={false} />
            <TextInput
              label={form.values.target === 'BODY_PATH' ? 'JSON path' : 'Name'}
              description={
                form.values.target === 'BODY_PATH'
                  ? 'Dotted, with [*] for array elements: items[*].email'
                  : 'Case-insensitive. * matches any run of characters: *token*'
              }
              {...form.getInputProps('selector')}
              required
            />
            <TextInput
              label="Destinations"
              description="Optional. A destination pattern such as orders.#; empty means every destination."
              {...form.getInputProps('destinationPattern')}
              value={form.values.destinationPattern ?? ''}
            />
            <Select label="Data class" data={CLASSES} {...form.getInputProps('dataClass')} allowDeselect={false} />
            <Select
              label="Action"
              data={ACTIONS}
              value={form.values.action ?? 'DEFAULT'}
              allowDeselect={false}
              onChange={(v) => form.setFieldValue('action', !v || v === 'DEFAULT' ? null : v)}
            />
            <Switch label="Enabled" {...form.getInputProps('enabled', { type: 'checkbox' })} />
            {saveError ? <ErrorState variant="inline" error={saveError} /> : null}
            <Group justify="flex-end">
              <Button
                variant="default"
                onClick={() => setEditing(null)}
                disabled={create.isPending || update.isPending}
              >
                Cancel
              </Button>
              <Button type="submit" loading={create.isPending || update.isPending}>
                Save rule
              </Button>
            </Group>
          </Stack>
        </form>
      </Modal>

      <ConfirmDialog
        opened={deleteOpen}
        onClose={() => setDeleteOpen(false)}
        title={deleting ? `Delete the rule for ${deleting.selector}` : 'Delete the rule'}
        tone="danger"
        pending={remove.isPending}
        confirmLabel="Delete rule"
        consequence={
          deleting ? (
            <>
              Values this rule masks become visible to every user who can read messages on{' '}
              {deleting.destinationPattern ? <code>{deleting.destinationPattern}</code> : 'every destination'}, unless a
              detector still recognises them. Stored messages are re-masked under the new policy.
            </>
          ) : null
        }
        onConfirm={() => deleting && confirmDelete(deleting)}
      />
    </Section>
  );
}
