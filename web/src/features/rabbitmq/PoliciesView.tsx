import { useCallback, useMemo, useState } from 'react';
import { ActionIcon, Button, Fieldset, Group, NumberInput, Select, Stack, Text, TextInput } from '@mantine/core';
import { useForm } from '@mantine/form';
import { randomId } from '@mantine/hooks';
import { IconPlus, IconTrash } from '@tabler/icons-react';
import { useParams, useSearch } from '@tanstack/react-router';

import { CapabilityGate } from '../../ui/CapabilityGate.tsx';
import type { GateVerdict } from '../../ui/capabilityGate.ts';
import { ConfirmDialog } from '../../ui/ConfirmDialog.tsx';
import { EmptyState } from '../../ui/EmptyState.tsx';
import { ErrorState } from '../../ui/ErrorState.tsx';
import { FieldRow } from '../../ui/FieldRow.tsx';
import { focusFirstInvalid } from '../../ui/formErrors.ts';
import { Notice } from '../../ui/Notice.tsx';
import type { ActionVerb } from '../../ui/notify.ts';
import { Page } from '../../ui/Page.tsx';
import { PageHeader } from '../../ui/PageHeader.tsx';
import { StatusBadge } from '../../ui/StatusBadge.tsx';
import { DataTable, type Column } from '../../ui/table/index.ts';
import { op, seg, useChange, usePolicies, type Policy, type PolicyPreview } from './api.ts';
import { ChangeDialog } from './ChangeDialog.tsx';
import { useWriteGate } from './gate.ts';
import { FilterBar } from './FilterBar.tsx';
import type { FilterSearch } from './search.ts';
import { matchKindWords, POLICY_APPLY_TO } from './words.ts';

const SET: ActionVerb = { verb: 'Set', past: 'Set', progressive: 'Setting' };
const CLEAR: ActionVerb = { verb: 'Clear', past: 'Cleared', progressive: 'Clearing' };

interface DefinitionRow {
  id: string;
  key: string;
  value: string;
}

interface PolicyForm {
  vhost: string;
  name: string;
  pattern: string;
  applyTo: string;
  priority: number | string;
  definition: DefinitionRow[];
}

const EMPTY: PolicyForm = {
  vhost: '',
  name: '',
  pattern: '',
  applyTo: 'all',
  priority: 0,
  definition: [{ id: 'first', key: '', value: '' }],
};

/** What setting a policy sends. */
interface PolicySet {
  vhost: string;
  name: string;
  pattern: string;
  applyTo: string;
  priority: number;
  definition: Record<string, string>;
}

const isJson = (text: string) => {
  try {
    JSON.parse(text);
    return true;
  } catch {
    return false;
  }
};

const definitionWords = (p: Pick<Policy, 'definition'>) =>
  Object.entries(p.definition)
    .map(([k, v]) => `${k}=${v}`)
    .join(', ');

function columns(gate: GateVerdict, onEdit: (p: Policy) => void, onClear: (p: Policy) => void): Column<Policy>[] {
  return [
    { id: 'vhost', header: 'Virtual host', accessor: (p) => p.vhost, kind: 'identifier', priority: 'high' },
    {
      id: 'name',
      header: 'Policy',
      accessor: (p) => p.name,
      cell: (p) => (
        <Stack gap={2} align="flex-start" miw={0}>
          <span>{p.name}</span>
          {p.operator ? <StatusBadge tone="info">Operator policy</StatusBadge> : null}
        </Stack>
      ),
      kind: 'identifier',
      priority: 'essential',
    },
    { id: 'pattern', header: 'Pattern', accessor: (p) => p.pattern, kind: 'code', priority: 'essential' },
    {
      id: 'applyTo',
      header: 'Applies to',
      accessor: (p) => matchKindWords(p.applyTo),
      kind: 'status',
      priority: 'high',
    },
    { id: 'priority', header: 'Priority', accessor: (p) => p.priority, kind: 'number', priority: 'high' },
    {
      id: 'matches',
      header: 'Matches now',
      description: 'The queues and exchanges the pattern matches, as Rahdar matches it',
      accessor: (p) => p.matches,
      kind: 'number',
      priority: 'high',
    },
    { id: 'definition', header: 'Settings', accessor: definitionWords, kind: 'code', wrap: true, priority: 'low' },
    {
      id: 'change',
      header: 'Change',
      accessor: (p) => (p.operator ? 'Not changed here' : 'Edit Clear'),
      cell: (p) =>
        p.operator ? (
          <Text size="xs" c="dimmed">
            Set by an operator; Rahdar lists it and does not change it.
          </Text>
        ) : (
          <Group gap={4} wrap="nowrap">
            <CapabilityGate verdict={gate} what="changing a policy">
              <Button
                size="compact-xs"
                variant="subtle"
                disabled={gate.kind === 'blocked'}
                aria-label={`Edit policy ${p.name}`}
                onClick={() => onEdit(p)}
              >
                Edit
              </Button>
              <Button
                size="compact-xs"
                variant="subtle"
                disabled={gate.kind === 'blocked'}
                aria-label={`Clear policy ${p.name}`}
                onClick={() => onClear(p)}
              >
                Clear
              </Button>
            </CapabilityGate>
          </Group>
        ),
      kind: 'text',
      wrap: true,
      min: 18,
      grow: false,
      priority: 'essential',
    },
  ];
}

const formOf = (p: Policy | null, vhost?: string): PolicyForm =>
  p
    ? {
        vhost: p.vhost,
        name: p.name,
        pattern: p.pattern,
        applyTo: p.applyTo,
        priority: p.priority,
        definition: Object.entries(p.definition).map(([key, value]) => ({ id: randomId(), key, value })),
      }
    : { ...EMPTY, vhost: vhost ?? '' };

/** The dialog that takes a policy to its preview. */
function PolicyDialog({
  initial,
  existing,
  onClose,
  onSubmit,
  pending,
}: Readonly<{
  initial: PolicyForm | null;
  existing: boolean;
  onClose: () => void;
  onSubmit: (p: PolicySet) => void;
  pending: boolean;
}>) {
  const form = useForm<PolicyForm>({
    initialValues: initial ?? EMPTY,
    validateInputOnBlur: true,
    validate: {
      vhost: (v) => (v.trim() ? null : 'Enter the virtual host.'),
      name: (v) => (v.trim() ? null : 'Enter the name of the policy.'),
      pattern: (v) => (v ? null : 'Enter a regular expression over the names, such as ^orders\\.'),
      definition: {
        key: (v) => (v.trim() ? null : 'Name the setting, or remove the row.'),
        value: (v) => (isJson(v) ? null : 'Write the value as JSON, such as 1000 or "reject-publish".'),
      },
    },
  });
  const submit = form.onSubmit(
    (v) =>
      onSubmit({
        vhost: v.vhost.trim(),
        name: v.name.trim(),
        pattern: v.pattern,
        applyTo: v.applyTo,
        priority: Number(v.priority) || 0,
        definition: Object.fromEntries(v.definition.map((d) => [d.key.trim(), d.value.trim()])),
      }),
    focusFirstInvalid(form.getInputNode),
  );
  return (
    <ConfirmDialog
      opened={initial !== null}
      onClose={onClose}
      title={existing ? `Edit policy ${initial?.name}` : 'Set a policy'}
      confirmLabel="Preview the policy"
      size="lg"
      pending={pending}
      onConfirm={() => submit()}
      consequence={
        <Stack gap="sm">
          <Text size="sm">A policy sets arguments on every queue and exchange whose name matches its pattern.</Text>
          <FieldRow>
            <TextInput
              label="Virtual host"
              disabled={existing}
              data-autofocus={!existing}
              {...form.getInputProps('vhost')}
            />
            <TextInput label="Name" disabled={existing} {...form.getInputProps('name')} />
          </FieldRow>
          <TextInput
            label="Pattern"
            description="A regular expression over the names."
            {...form.getInputProps('pattern')}
          />
          <FieldRow>
            <Select
              label="Applies to"
              data={POLICY_APPLY_TO.map((a) => ({ value: a, label: matchKindWords(a) }))}
              allowDeselect={false}
              {...form.getInputProps('applyTo')}
            />
            <NumberInput
              label="Priority"
              description="The highest wins where two match."
              allowDecimal={false}
              {...form.getInputProps('priority')}
            />
          </FieldRow>
          <Fieldset legend="Settings">
            <Stack gap="xs">
              {form.values.definition.map((d, i) => (
                <FieldRow key={d.id}>
                  <TextInput
                    label="Setting"
                    size="xs"
                    placeholder="max-length"
                    {...form.getInputProps(`definition.${i}.key`)}
                  />
                  <TextInput
                    label="Value (JSON)"
                    size="xs"
                    placeholder="1000"
                    {...form.getInputProps(`definition.${i}.value`)}
                  />
                  <ActionIcon
                    variant="subtle"
                    aria-label={`Remove setting ${i + 1}`}
                    onClick={() => form.removeListItem('definition', i)}
                  >
                    <IconTrash size="1rem" aria-hidden />
                  </ActionIcon>
                </FieldRow>
              ))}
              <div>
                <Button
                  size="xs"
                  variant="default"
                  leftSection={<IconPlus size="1rem" aria-hidden />}
                  onClick={() => form.insertListItem('definition', { id: randomId(), key: '', value: '' })}
                >
                  Add setting
                </Button>
              </div>
            </Stack>
          </Fieldset>
        </Stack>
      }
    />
  );
}

const MATCH_COLUMNS: Column<PolicyPreview['matches'][number]>[] = [
  { id: 'name', header: 'Name', accessor: (m) => m.name, kind: 'identifier', priority: 'essential' },
  { id: 'kind', header: 'Kind', accessor: (m) => matchKindWords(m.kind), kind: 'status', priority: 'essential' },
  {
    id: 'result',
    header: 'Result',
    accessor: (m) => (m.shadowedBy ? `Won by ${m.shadowedBy}` : 'This policy applies'),
    kind: 'text',
    wrap: true,
    priority: 'essential',
  },
];

/** What a policy change reaches: the names its pattern matches, and those another policy of higher priority wins. */
function Matches({ preview }: Readonly<{ preview: PolicyPreview }>) {
  const won = preview.matches.filter((m) => m.shadowedBy).length;
  return (
    <Stack gap="sm">
      <Text size="sm">
        Matched as Rahdar matches names: a Java regular expression over the names, which can differ from the broker's
        own matching at the edges.
      </Text>
      {won > 0 ? (
        <Notice tone="warning" title={`${won} won by a policy of higher priority`}>
          The policy does not apply to these; the one named in the table wins.
        </Notice>
      ) : null}
      {preview.partial ? (
        <Notice tone="warning" title="The matches are not complete">
          The list was cut short or not every name was read, so more may match.
        </Notice>
      ) : null}
      <DataTable
        variant="static"
        label="Names the pattern matches"
        storageKey="rabbitmq.policy.matches"
        columns={MATCH_COLUMNS}
        data={preview.matches}
        rowKey={(m) => `${m.kind}/${m.name}`}
        height={{ maxRows: 10 }}
        empty={<Text size="sm">Nothing matches the pattern now.</Text>}
      />
    </Stack>
  );
}

/**
 * The policies of a RabbitMQ cluster. Setting or clearing one is previewed with the names it reaches, as Rahdar
 * matches them and with those a policy of higher priority wins; clearing is held. Operator policies are listed only.
 */
export function PoliciesView() {
  const { clusterId } = useParams({ strict: false }) as { clusterId: string };
  const search = useSearch({ strict: false }) as FilterSearch;
  const policies = usePolicies(clusterId, search);
  const gate = useWriteGate(clusterId, 'policy:write', 'Change policies');
  const [editing, setEditing] = useState<PolicyForm | null>(null);
  const [existing, setExisting] = useState(false);
  const set = useChange<PolicySet, PolicyPreview>(clusterId, (p) =>
    op(clusterId, 'PUT', `policies/${seg(p.vhost)}/${seg(p.name)}`, {
      pattern: p.pattern,
      applyTo: p.applyTo,
      priority: p.priority,
      definition: p.definition,
    }),
  );
  const clear = useChange<Policy, PolicyPreview>(clusterId, (p) =>
    op(clusterId, 'DELETE', `policies/${seg(p.vhost)}/${seg(p.name)}`),
  );
  const rows = policies.data?.pages.flatMap((p) => p.items) ?? [];
  const { start: startClear } = clear;
  const defaultVhost = search.vhost;
  const open = useCallback(
    (p: Policy | null) => {
      setExisting(p !== null);
      setEditing(formOf(p, defaultVhost));
    },
    [defaultVhost],
  );
  const cols = useMemo(() => columns(gate, open, startClear), [gate, open, startClear]);
  const chosen = set.reviewed?.vars;
  const cleared = clear.reviewed?.vars;

  return (
    <Page fill>
      <PageHeader
        title="Policies"
        description="Settings applied to every queue and exchange whose name matches a pattern."
      />
      <FilterBar
        label="Policy filters"
        fields={[{ name: 'vhost', label: 'Virtual host' }]}
        end={
          <CapabilityGate verdict={gate} what="setting a policy">
            <Button size="xs" disabled={gate.kind === 'blocked'} onClick={() => open(null)}>
              Set a policy
            </Button>
          </CapabilityGate>
        }
      />
      <DataTable
        label="Policies"
        storageKey="rabbitmq.policies"
        height="fill"
        columns={cols}
        data={rows}
        rowKey={(p) => p.id}
        loading={policies.isPending}
        error={
          policies.isError ? <ErrorState error={policies.error} onRetry={() => void policies.refetch()} /> : undefined
        }
        empty={
          <EmptyState
            kind="empty"
            title="No policies"
            description="A policy sets queue arguments, such as a length limit or a dead-letter exchange, for every name that matches."
          />
        }
      />
      {policies.hasNextPage ? (
        <Button
          size="xs"
          variant="default"
          loading={policies.isFetchingNextPage}
          onClick={() => void policies.fetchNextPage()}
        >
          Load more policies
        </Button>
      ) : null}
      <PolicyDialog
        key={`${existing}/${editing?.name ?? ''}`}
        initial={editing}
        existing={existing}
        onClose={() => setEditing(null)}
        pending={set.previewing}
        onSubmit={(p) => set.start(p, () => setEditing(null))}
      />
      <ChangeDialog
        change={set}
        title={`Set policy ${chosen?.name ?? ''}`}
        action={SET}
        confirmLabel="Set the policy"
        subject={`policy "${chosen?.name ?? ''}"`}
        extra={(preview) => <Matches preview={preview} />}
      />
      <ChangeDialog
        change={clear}
        title={`Clear policy ${cleared?.name ?? ''}`}
        action={CLEAR}
        confirmLabel="Clear 1 policy"
        destructive
        subject={`policy "${cleared?.name ?? ''}"`}
        extra={(preview) => <Matches preview={preview} />}
      />
    </Page>
  );
}
