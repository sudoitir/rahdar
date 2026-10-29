import { useMemo, useState } from 'react';
import { Button, Select, Stack, Text, TextInput } from '@mantine/core';
import { useForm } from '@mantine/form';
import { useDebouncedCallback } from '@mantine/hooks';
import { useNavigate, useParams, useSearch } from '@tanstack/react-router';

import { useCan } from '../../kernel/auth/useCan.ts';
import { CapabilityGate } from '../../ui/CapabilityGate.tsx';
import { gateFor, type GateVerdict } from '../../ui/capabilityGate.ts';
import { ConfirmDialog } from '../../ui/ConfirmDialog.tsx';
import { EmptyState } from '../../ui/EmptyState.tsx';
import { ErrorState } from '../../ui/ErrorState.tsx';
import { focusFirstInvalid } from '../../ui/formErrors.ts';
import { Notice } from '../../ui/Notice.tsx';
import { notify, type ActionVerb } from '../../ui/notify.ts';
import { Page } from '../../ui/Page.tsx';
import { PageHeader } from '../../ui/PageHeader.tsx';
import { StatusBadge } from '../../ui/StatusBadge.tsx';
import { Toolbar } from '../../ui/Toolbar.tsx';
import { DataTable, type Column } from '../../ui/table/index.ts';
import type { AclSearch } from './search.ts';
import { useAcls, useApplyAcls, usePreviewAcls, type Acl, type AclChange, type AclPreview } from './api.ts';

const APPLY: ActionVerb = { verb: 'Apply', past: 'Applied', progressive: 'Applying' };

const OPERATIONS = [
  'all',
  'read',
  'write',
  'create',
  'delete',
  'alter',
  'describe',
  'cluster_action',
  'describe_configs',
  'alter_configs',
  'idempotent_write',
];
const RESOURCE_TYPES = ['topic', 'group', 'cluster', 'transactional_id', 'delegation_token'];

const key = (a: Acl) =>
  [a.principal, a.host, a.operation, a.permission, a.resourceType, a.pattern, a.name].join('\u0000');
const words = (a: Acl) =>
  `${a.permission === 'deny' ? 'Deny' : 'Allow'} ${a.principal} ${a.operation.toUpperCase()} on ${a.resourceType} ${a.name}`;

const EMPTY_ACL: Acl = {
  principal: '',
  host: '*',
  operation: 'read',
  permission: 'allow',
  resourceType: 'topic',
  pattern: 'literal',
  name: '',
};

/** The rows left after one ACL is taken back out of the removals. */
function withoutAcl(acl: Acl) {
  const id = key(acl);
  return (rows: Acl[]) => rows.filter((row) => key(row) !== id);
}

function permissionWord(acl: Acl): 'Deny' | 'Allow' {
  return acl.permission === 'deny' ? 'Deny' : 'Allow';
}

function aclColumns(
  removing: ReadonlySet<string>,
  gate: GateVerdict,
  onKeep: (acl: Acl) => void,
  onRemove: (acl: Acl) => void,
): Column<Acl>[] {
  return [
    {
      id: 'principal',
      header: 'Principal',
      accessor: (acl) => acl.principal,
      kind: 'identifier',
      priority: 'essential',
    },
    {
      id: 'permission',
      header: 'Permission',
      accessor: permissionWord,
      cell: (acl) => (
        <StatusBadge tone={acl.permission === 'deny' ? 'warning' : 'neutral'}>{permissionWord(acl)}</StatusBadge>
      ),
      kind: 'status',
      badge: true,
      priority: 'essential',
    },
    { id: 'operation', header: 'Operation', accessor: (acl) => acl.operation, kind: 'status', priority: 'essential' },
    {
      id: 'type',
      header: 'Resource',
      accessor: (acl) => acl.resourceType.toLowerCase(),
      kind: 'status',
      priority: 'high',
    },
    { id: 'pattern', header: 'Match', accessor: (acl) => acl.pattern.toLowerCase(), kind: 'status', priority: 'low' },
    { id: 'name', header: 'Name', accessor: (acl) => acl.name, kind: 'identifier', priority: 'essential' },
    { id: 'host', header: 'Host', accessor: (acl) => acl.host, kind: 'code', priority: 'low' },
    {
      id: 'remove',
      header: 'Change',
      accessor: (acl) => (removing.has(key(acl)) ? 'Removing' : ''),
      cell: (acl) =>
        removing.has(key(acl)) ? (
          <Button size="compact-xs" variant="subtle" aria-label={`Keep ${words(acl)}`} onClick={() => onKeep(acl)}>
            Keep (removing)
          </Button>
        ) : (
          <CapabilityGate verdict={gate} what="removing an ACL">
            <Button
              size="compact-xs"
              variant="subtle"
              disabled={gate.kind === 'blocked'}
              aria-label={`Remove ${words(acl)}`}
              onClick={() => onRemove(acl)}
            >
              Remove
            </Button>
          </CapabilityGate>
        ),
      kind: 'status',
      priority: 'essential',
    },
  ];
}

/** How many ACL changes are waiting, for the toolbar. */
function reviewWords(pending: number): string {
  if (pending === 0) return 'No changes yet';
  const noun = pending === 1 ? 'change' : 'changes';
  return `${pending} ${noun} to review`;
}

function addsTitle(count: number): string {
  const noun = count === 1 ? 'ACL' : 'ACLs';
  return `${count} ${noun} to add`;
}

/** The empty table: no authoriser, nothing matching the filter, or genuinely no ACLs. */
function aclEmpty(
  first: { available: boolean; reason?: string | null } | undefined,
  filtered: boolean,
  onClear: () => void,
) {
  if (first && !first.available) {
    return (
      <EmptyState
        kind="empty"
        title="ACLs are not available"
        description={first.reason ?? 'The cluster has no authoriser.'}
      />
    );
  }
  if (filtered) {
    return (
      <EmptyState
        kind="filtered"
        title="No ACL matches this filter"
        description="ACLs may still exist; none of them match the filter."
        onClearFilters={onClear}
      />
    );
  }
  return (
    <EmptyState
      kind="empty"
      title="No ACLs"
      description="An ACL grants or denies one operation on one resource to one principal. With none, an authorising cluster refuses everything it does not allow by other means."
    />
  );
}

/** The dialog that adds one ACL to the pending changes. */
function AddAclDialog({
  opened,
  onClose,
  onAdd,
}: Readonly<{ opened: boolean; onClose: () => void; onAdd: (a: Acl) => void }>) {
  const form = useForm<Acl>({
    initialValues: EMPTY_ACL,
    validateInputOnBlur: true,
    validate: {
      principal: (v) => (/^[A-Za-z]+:.+/.test(v.trim()) ? null : 'Write the principal as Type:name, such as User:app.'),
      name: (v) => (v.trim() ? null : 'Enter the name of the resource, or * for all of them.'),
      host: (v) => (v.trim() ? null : 'Enter the host, or * for any.'),
    },
  });
  const submit = form.onSubmit((values) => {
    onAdd({ ...values, principal: values.principal.trim(), name: values.name.trim(), host: values.host.trim() });
    form.reset();
    onClose();
  }, focusFirstInvalid(form.getInputNode));
  return (
    <ConfirmDialog
      opened={opened}
      onClose={onClose}
      title="Add an ACL"
      confirmLabel="Add to the changes"
      onConfirm={() => submit()}
      consequence={
        <Stack gap="sm">
          <Text size="sm">Nothing is changed yet: the ACL joins the changes you review before applying.</Text>
          <TextInput label="Principal" placeholder="User:app" data-autofocus {...form.getInputProps('principal')} />
          <TextInput label="Host" {...form.getInputProps('host')} />
          <Select
            label="Permission"
            data={['allow', 'deny']}
            allowDeselect={false}
            {...form.getInputProps('permission')}
          />
          <Select label="Operation" data={OPERATIONS} allowDeselect={false} {...form.getInputProps('operation')} />
          <Select
            label="Resource type"
            data={RESOURCE_TYPES}
            allowDeselect={false}
            {...form.getInputProps('resourceType')}
          />
          <Select
            label="Name match"
            data={['literal', 'prefixed']}
            allowDeselect={false}
            {...form.getInputProps('pattern')}
          />
          <TextInput label="Resource name" {...form.getInputProps('name')} />
        </Stack>
      }
    />
  );
}

/**
 * The ACLs of a Kafka cluster. A cluster with no authoriser says so, with its reason, instead of an empty list.
 * Additions and removals are collected, reviewed for the access each principal would gain and lose (a DENY
 * overrides an ALLOW and is noted), and applied against the state the review read.
 */
export function AclsView() {
  const { clusterId } = useParams({ strict: false }) as { clusterId: string };
  const search = useSearch({ strict: false }) as AclSearch;
  const navigate = useNavigate();
  const acls = useAcls(clusterId, search);
  const previewAcls = usePreviewAcls(clusterId);
  const applyAcls = useApplyAcls(clusterId);
  const { can, loading } = useCan();
  const gate = gateFor(can('access-rule:write', clusterId), 'Change ACLs', undefined, loading);
  const [principal, setPrincipal] = useState(search.principal ?? '');
  const [resource, setResource] = useState(search.resource ?? '');
  const [adds, setAdds] = useState<Acl[]>([]);
  const [removes, setRemoves] = useState<Acl[]>([]);
  const [adding, setAdding] = useState(false);
  const [reviewed, setReviewed] = useState<AclPreview | null>(null);

  const write = (patch: AclSearch) =>
    void navigate({ to: '.', search: (prev: Record<string, unknown>) => ({ ...prev, ...patch }) });
  const writePrincipal = useDebouncedCallback((v: string) => write({ principal: v || undefined }), 250);
  const writeResource = useDebouncedCallback((v: string) => write({ resource: v || undefined }), 250);

  const first = acls.data?.pages[0];
  const rows = acls.data?.pages.flatMap((p) => p.items) ?? [];
  const removing = useMemo(() => new Set(removes.map(key)), [removes]);
  const pending = adds.length + removes.length;
  const change: AclChange = { add: adds, remove: removes };
  const filtered = Boolean(search.principal || search.resource);

  const columns = useMemo(
    () =>
      aclColumns(
        removing,
        gate,
        (acl) => setRemoves(withoutAcl(acl)),
        (acl) => setRemoves((rows) => [...rows, acl]),
      ),
    [removing, gate],
  );

  const review = () => previewAcls.mutate(change, { onSuccess: setReviewed });
  const close = () => {
    setReviewed(null);
    previewAcls.reset();
    applyAcls.reset();
  };
  const run = () => {
    if (!reviewed) return;
    applyAcls.mutate(
      { ...change, etag: reviewed.etag },
      {
        onSuccess: () => {
          notify.succeeded({ action: APPLY, subject: 'the ACL changes' });
          setAdds([]);
          setRemoves([]);
          close();
        },
        onError: (error) => {
          if (error.status === 412) return;
          notify.settle(error, {
            action: APPLY,
            subject: 'the ACL changes',
            cause: error.message,
            next: 'Check the audit log for what ran, then try again.',
            onHeld: close,
          });
        },
      },
    );
  };
  const destructive = (reviewed?.losses.length ?? 0) > 0 || removes.length > 0;

  return (
    <Page fill>
      <PageHeader
        title="ACLs"
        description="Who may do what to which topic, group or the cluster. Collect additions and removals, then review the access they change."
      />
      {first && !first.available ? (
        <Notice tone="warning" title="This cluster has no authoriser to read">
          {first.reason ?? 'ACLs cannot be listed on this cluster.'}
        </Notice>
      ) : null}
      <Toolbar
        label="ACL filters"
        start={
          <>
            <TextInput
              label="Principal"
              size="xs"
              w="13rem"
              value={principal}
              placeholder="User:app"
              onChange={(e) => {
                setPrincipal(e.currentTarget.value);
                writePrincipal(e.currentTarget.value);
              }}
            />
            <TextInput
              label="Resource name"
              size="xs"
              w="13rem"
              value={resource}
              onChange={(e) => {
                setResource(e.currentTarget.value);
                writeResource(e.currentTarget.value);
              }}
            />
          </>
        }
        end={
          <>
            <Text size="xs" c="dimmed" role="status">
              {reviewWords(pending)}
            </Text>
            <CapabilityGate verdict={gate} what="adding an ACL">
              <Button size="xs" variant="default" disabled={gate.kind === 'blocked'} onClick={() => setAdding(true)}>
                Add an ACL
              </Button>
            </CapabilityGate>
            <Button
              size="xs"
              variant="subtle"
              disabled={pending === 0}
              onClick={() => {
                setAdds([]);
                setRemoves([]);
              }}
            >
              Discard
            </Button>
            <Button size="xs" disabled={pending === 0} loading={previewAcls.isPending} onClick={review}>
              Review changes
            </Button>
          </>
        }
      />
      {adds.length > 0 ? (
        <Notice tone="info" title={addsTitle(adds.length)}>
          <Stack gap={2}>
            {adds.map((a) => (
              <Text key={key(a)} size="sm">
                {words(a)}{' '}
                <Button
                  size="compact-xs"
                  variant="subtle"
                  onClick={() => setAdds((s) => s.filter((x) => key(x) !== key(a)))}
                  aria-label={`Drop ${words(a)}`}
                >
                  Drop
                </Button>
              </Text>
            ))}
          </Stack>
        </Notice>
      ) : null}
      {previewAcls.isError ? <ErrorState variant="inline" error={previewAcls.error} /> : null}
      <DataTable
        label="ACLs"
        storageKey="kafka.acls"
        height="fill"
        columns={columns}
        data={rows}
        rowKey={key}
        loading={acls.isPending}
        error={acls.isError ? <ErrorState error={acls.error} onRetry={() => void acls.refetch()} /> : undefined}
        empty={aclEmpty(first, filtered, () => {
          setPrincipal('');
          setResource('');
          write({ principal: undefined, resource: undefined });
        })}
      />
      {acls.hasNextPage ? (
        <Button size="xs" variant="default" loading={acls.isFetchingNextPage} onClick={() => void acls.fetchNextPage()}>
          Load more ACLs
        </Button>
      ) : null}

      <AddAclDialog
        opened={adding}
        onClose={() => setAdding(false)}
        onAdd={(a) => setAdds((s) => (s.some((x) => key(x) === key(a)) ? s : [...s, a]))}
      />

      <ConfirmDialog
        opened={reviewed !== null}
        onClose={close}
        title={`Apply ${pending} ACL change${pending === 1 ? '' : 's'}`}
        tone={destructive ? 'danger' : 'default'}
        confirmLabel={`Apply ${pending} change${pending === 1 ? '' : 's'}`}
        blocked={
          applyAcls.error?.status === 412
            ? 'The ACLs changed since this review. Close it and review the changes again.'
            : undefined
        }
        pending={applyAcls.isPending}
        onConfirm={run}
        consequence={
          <Stack gap="sm">
            {reviewed && reviewed.losses.length > 0 ? (
              <Notice tone="warning" title="Access that would be lost">
                <Stack gap={2}>
                  {reviewed.losses.map((l) => (
                    <Text key={l} size="sm">
                      {l}
                    </Text>
                  ))}
                </Stack>
              </Notice>
            ) : null}
            {reviewed?.notes.map((n) => (
              <Notice key={n} tone="info" title="Note">
                {n}
              </Notice>
            ))}
            <DataTable
              variant="static"
              label="Effective access before and after"
              storageKey="kafka.acls.review"
              columns={REVIEW_COLUMNS}
              data={reviewed?.access ?? []}
              rowKey={(e) => `${e.principal}/${e.resourceType}/${e.resource}`}
              height={{ maxRows: 10 }}
              empty={null}
            />
          </Stack>
        }
      />
    </Page>
  );
}

const REVIEW_COLUMNS: Column<AclPreview['access'][number]>[] = [
  { id: 'principal', header: 'Principal', accessor: (e) => e.principal, kind: 'identifier', priority: 'essential' },
  {
    id: 'resource',
    header: 'Resource',
    accessor: (e) => `${e.resourceType} ${e.resource}`,
    kind: 'identifier',
    priority: 'essential',
  },
  {
    id: 'before',
    header: 'Before',
    accessor: (e) => e.before.join(', ') || 'None',
    kind: 'text',
    priority: 'essential',
    wrap: true,
  },
  {
    id: 'after',
    header: 'After',
    accessor: (e) => e.after.join(', ') || 'None',
    kind: 'text',
    priority: 'essential',
    wrap: true,
  },
];
