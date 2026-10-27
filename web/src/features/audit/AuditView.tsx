import { useEffect, useMemo, useRef, useState } from 'react';
import { Button, Code, Drawer, Group, Menu, Select, Stack, Text, TextInput } from '@mantine/core';
import { IconDownload, IconLink } from '@tabler/icons-react';
import { useNavigate, useParams, useSearch } from '@tanstack/react-router';
import { useDebouncedValue } from '@mantine/hooks';

import {
  auditExportHref,
  useAudit,
  useAuditEvent,
  type AuditEventView,
  type AuditExportFormat,
  type AuditFilter,
} from './api.ts';
import { useUsers } from '../security/index.ts';
import { useCan } from '../../kernel/auth/useCan.ts';
import { useActionHost } from '../../kernel/actions/hostContext.ts';
import { absoluteHref, clusterHref } from '../../kernel/routing/href.ts';
import { CapabilityGate } from '../../ui/CapabilityGate.tsx';
import { gateFor } from '../../ui/capabilityGate.ts';
import { ActionMenuItem } from '../../ui/ActionMenuItem.tsx';
import { DataTable } from '../../ui/table/index.ts';
import { EmptyState } from '../../ui/EmptyState.tsx';
import { ErrorState } from '../../ui/ErrorState.tsx';
import { DescriptionList, type DescriptionItem } from '../../ui/DescriptionList.tsx';
import { LoadingState } from '../../ui/LoadingState.tsx';
import { Page } from '../../ui/Page.tsx';
import { PageHeader } from '../../ui/PageHeader.tsx';
import { Pager } from '../../ui/Pager.tsx';
import { ReplicaLagNotice } from '../../ui/ReplicaLagNotice.tsx';
import { Section } from '../../ui/Section.tsx';
import { Toolbar } from '../../ui/Toolbar.tsx';
import { at, auditColumns, outcome } from './columns.tsx';
import { StatusBadge } from '../../ui/StatusBadge.tsx';
import { absoluteLabel, serverNow } from '../../kernel/time/time.ts';
import { useDisplayZone } from '../../kernel/time/timezone.ts';
import { useFilterShortcut } from '../../kernel/keyboard/filterShortcut.ts';

const PAGE_SIZE = 100;

function auditKey(e: AuditEventView): string {
  return `${e.ts}-${e.action}-${e.requestId}`;
}

function CopyAuditLink({ clusterId, id }: Readonly<{ clusterId: string; id: number }>) {
  const host = useActionHost();
  return (
    <ActionMenuItem
      label="Copy link"
      icon={<IconLink size="1rem" aria-hidden />}
      onSelect={() =>
        host.copy(absoluteHref(clusterHref(clusterId, 'audit', { event: id })), 'link to the audit event')
      }
    />
  );
}

type AuditSearch = {
  user?: string;
  action?: string;
  outcome?: string;
  /** The time range, as instants: events at or after `from`, and before `to`. */
  from?: string;
  to?: string;
  parentId?: number;
  event?: number;
  page?: number;
};

type SetParam = (patch: Record<string, unknown>) => unknown;

// The filter offers each action and outcome in words; the address and the query keep the recorded value.
const ACTIONS = [
  { value: 'SEND_MESSAGE', label: 'Send message' },
  { value: 'MOVE_MESSAGES', label: 'Move messages' },
  { value: 'RETRY_MESSAGES', label: 'Retry messages' },
  { value: 'DELETE_MESSAGES', label: 'Delete messages' },
  { value: 'EXPIRE_MESSAGES', label: 'Expire messages' },
  { value: 'PURGE_QUEUE', label: 'Purge subscription' },
  { value: 'REGISTER_CLUSTER', label: 'Register cluster' },
  { value: 'REDISCOVER_CLUSTER', label: 'Rediscover cluster' },
  { value: 'DELETE_CLUSTER', label: 'Delete cluster' },
];

const OUTCOMES = [
  { value: 'SUCCESS', label: 'Success' },
  { value: 'FAILURE', label: 'Failure' },
  { value: 'REFUSED', label: 'Refused' },
  { value: 'IN_DOUBT', label: 'In doubt' },
  { value: 'PENDING', label: 'Pending' },
];

const HOUR_MS = 3_600_000;
const RANGES = [
  { value: '1h', label: 'Last hour', ms: HOUR_MS },
  { value: '24h', label: 'Last 24 hours', ms: 24 * HOUR_MS },
  { value: '7d', label: 'Last 7 days', ms: 7 * 24 * HOUR_MS },
  { value: '30d', label: 'Last 30 days', ms: 30 * 24 * HOUR_MS },
];
const CUSTOM = 'custom';

/** A range in the address that no preset chose here, such as a shared link's, in words. */
function customRange(from: string | undefined, to: string | undefined): string {
  if (from && to) return `${absoluteLabel(from)} to ${absoluteLabel(to)}`;
  if (from) return `Since ${absoluteLabel(from)}`;
  return `Before ${absoluteLabel(to)}`;
}

/**
 * The time range: a preset sets `from` to that long before now, by the server's clock, and clears `to`. The
 * instants are what the address holds, so a shared link shows the same events; a range it holds that was not
 * picked here is named as it is.
 */
function RangeFilter({ search, setParam }: Readonly<{ search: AuditSearch; setParam: SetParam }>) {
  const [preset, setPreset] = useState<string | null>(null);
  const ranged = Boolean(search.from || search.to);
  let value: string | null = null;
  if (ranged) value = preset ?? CUSTOM;
  const data = ranged && !preset ? [...RANGES, { value: CUSTOM, label: customRange(search.from, search.to) }] : RANGES;
  return (
    <Select
      label="Time"
      placeholder="Any time"
      size="xs"
      w="13rem"
      clearable
      allowDeselect={false}
      value={value}
      data={data.map(({ value: v, label }) => ({ value: v, label }))}
      onChange={(next) => {
        const range = RANGES.find((r) => r.value === next);
        setPreset(range ? range.value : null);
        if (next === CUSTOM) return;
        void setParam({ from: range ? new Date(serverNow() - range.ms).toISOString() : undefined, to: undefined });
      }}
    />
  );
}

/** The user, action, outcome and time filters; each commits to the URL, the single source of truth. */
function AuditFilters({ search, setParam }: Readonly<{ search: AuditSearch; setParam: SetParam }>) {
  // `/` focuses this view's filter (ADR-0109).
  const filterRef = useRef<HTMLInputElement>(null);
  useFilterShortcut(filterRef);
  const { can } = useCan();
  const canListUsers = can('user:admin');
  const users = useUsers(canListUsers);

  const [user, setUser] = useState(search.user ?? '');
  const [debouncedUser] = useDebouncedValue(user, 250);
  // The free-text field applies once typing pauses, not only when it loses focus.
  const committed = search.user ?? '';
  useEffect(() => {
    if (debouncedUser.trim() !== committed) void setParam({ user: debouncedUser.trim() || undefined });
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [debouncedUser]);

  // The Select commits to the URL immediately (a discrete choice needs no debounce); the
  // free-text fallback commits its debounced value — either way, the URL's `search.user` is
  // the single source of truth for the query.
  return (
    <>
      {canListUsers ? (
        <Select
          label="User"
          placeholder="Any user"
          size="xs"
          w="11.25rem"
          clearable
          searchable
          value={search.user ?? null}
          onChange={(v) => setParam({ user: v || undefined })}
          data={(users.data ?? []).map((u) => u.username)}
        />
      ) : (
        <TextInput
          ref={filterRef}
          label="Filter by user"
          placeholder="Filter by user"
          value={user}
          onChange={(e) => setUser(e.currentTarget.value)}
          size="xs"
          w="11.25rem"
        />
      )}
      <Select
        label="Action"
        placeholder="Any action"
        size="xs"
        w="11.875rem"
        clearable
        value={search.action ?? null}
        onChange={(v) => setParam({ action: v || undefined })}
        data={ACTIONS}
      />
      <Select
        label="Outcome"
        placeholder="Any outcome"
        size="xs"
        w="9.375rem"
        clearable
        value={search.outcome ?? null}
        onChange={(v) => setParam({ outcome: v || undefined })}
        data={OUTCOMES}
      />
      <RangeFilter search={search} setParam={setParam} />
    </>
  );
}

/** Why the grid is empty: a filter excludes every event, or nothing has been recorded yet. */
function AuditEmpty({ filtered, onClearFilters }: Readonly<{ filtered: boolean; onClearFilters: () => void }>) {
  if (filtered) {
    return (
      <EmptyState
        kind="filtered"
        title="No audit event matches these filters"
        description="Events are recorded on this cluster, but none has this user, action or outcome, falls in this time range, or belongs to the run you are looking at. Clear the filters to see them all."
        onClearFilters={onClearFilters}
      />
    );
  }
  return (
    <EmptyState
      kind="empty"
      title="No audit events yet"
      description="The audit log records every message operation, purge and cluster change the moment it runs, with who did it and how it ended. Nothing has been recorded on this cluster yet; run an operation and it appears here."
    />
  );
}

/** The open event's detail, or the reason it cannot be shown (loading, gone, failed to load). */
function AuditEventDetail({
  selected,
  offPage,
  setParam,
}: Readonly<{
  selected: AuditEventView | null;
  offPage: ReturnType<typeof useAuditEvent>;
  setParam: SetParam;
}>) {
  if (!selected) {
    if (offPage.isPending) return <LoadingState label="Loading the audit event" blockSize="12rem" />;
    if (offPage.error?.status === 404) {
      return (
        <EmptyState
          kind="empty"
          title="This audit event no longer exists"
          description="No audit event with this id exists on this cluster. Check the link, or ask whoever shared it to copy it again."
        />
      );
    }
    if (offPage.isError) return <ErrorState error={offPage.error} onRetry={() => void offPage.refetch()} />;
    return null;
  }
  const { word, tone } = outcome(selected.outcome);
  const items: DescriptionItem[] = [
    { term: 'Time', value: at(selected) },
    { term: 'User', value: selected.username ?? 'anonymous' },
    {
      term: 'Target',
      value: selected.targetName ?? 'no target',
      hint: selected.dryRun ? 'Dry run: nothing was changed.' : undefined,
    },
    { term: 'Outcome', value: <StatusBadge tone={tone}>{word}</StatusBadge> },
    ...(selected.error ? [{ term: 'Error', value: selected.error }] : []),
    { term: 'Request', value: selected.requestId ?? '—' },
    { term: 'From', value: selected.sourceIp ?? '—' },
  ];
  return (
    <Stack gap="lg">
      <DescriptionList label="Audit event" columns={2} items={items} />
      {selected.params ? (
        <Section headingLevel={3} title="Parameters">
          <Code block tabIndex={0} role="region" aria-label="Audit event parameters">
            {selected.params}
          </Code>
        </Section>
      ) : null}
      {selected.parentId != null || selected.action.startsWith('bulk.') ? (
        <Group gap="xs">
          {selected.parentId != null ? (
            <Button
              size="xs"
              variant="default"
              onClick={() => {
                void setParam({ parentId: selected.parentId, event: undefined });
              }}
            >
              Show the operation this belongs to, with all its parts
            </Button>
          ) : null}
          {selected.action.startsWith('bulk.') ? (
            <Button
              size="xs"
              variant="default"
              onClick={() => {
                void setParam({ parentId: selected.id, event: undefined });
              }}
            >
              Show the event for each subscription in this run
            </Button>
          ) : null}
        </Group>
      ) : null}
    </Stack>
  );
}

/** The audit-log screen (non-negotiable #3): every mutating call, filterable, newest first. */
const EXPORT_FORMATS: readonly { format: AuditExportFormat; label: string }[] = [
  { format: 'csv', label: 'CSV' },
  { format: 'json', label: 'JSON' },
  { format: 'ndjson', label: 'NDJSON' },
];

function exportLabel(total: number | undefined): string {
  if (total === undefined) {
    return 'The matching events, oldest first';
  }
  return `${total.toLocaleString()} matching ${total === 1 ? 'event' : 'events'}, oldest first`;
}

/**
 * Downloads what the filters show, oldest first. Each format is a plain link the browser follows, so the file
 * streams to disk and a long export can be cancelled there.
 */
function ExportMenu({
  clusterId,
  filter,
  total,
}: Readonly<{ clusterId: string; filter: AuditFilter; total: number | undefined }>) {
  const { can, loading } = useCan();
  const gate = gateFor(can('audit:export', clusterId), 'Export the audit trail', undefined, loading);
  return (
    <CapabilityGate verdict={gate} what="exporting the audit log">
      <Menu shadow="md" position="bottom-end">
        <Menu.Target>
          <Button
            variant="default"
            leftSection={<IconDownload size="1rem" aria-hidden />}
            disabled={gate.kind === 'blocked'}
          >
            Export
          </Button>
        </Menu.Target>
        <Menu.Dropdown>
          <Menu.Label>{exportLabel(total)}</Menu.Label>
          {EXPORT_FORMATS.map(({ format, label }) => (
            <Menu.Item key={format} component="a" href={auditExportHref(clusterId, filter, format)} download>
              {label}
            </Menu.Item>
          ))}
        </Menu.Dropdown>
      </Menu>
    </CapabilityGate>
  );
}

export function AuditView() {
  // Absolute timestamps here read the display zone from module state, so this
  // subscribes the view to a zone change (`app/timezone.ts`).
  const zone = useDisplayZone();
  const columns = useMemo(() => auditColumns(zone), [zone]);
  const { clusterId } = useParams({ strict: false }) as { clusterId: string };
  const search = useSearch({ strict: false }) as AuditSearch;
  const navigate = useNavigate();
  const page = search.page ?? 1;

  const setParam = (patch: Record<string, unknown>) =>
    navigate({
      to: '.',
      search: (prev: Record<string, unknown>) => ({ ...prev, ...patch, page: undefined }),
    });

  const filter: AuditFilter = {
    user: search.user,
    action: search.action,
    outcome: search.outcome,
    parentId: search.parentId,
    from: search.from,
    to: search.to,
  };
  const query = useAudit(clusterId, {
    ...filter,
    page,
    size: PAGE_SIZE,
  });

  const rows = query.data?.data ?? [];
  // The open event is held in the URL as its id and looked up in the rows each render. A shared
  // link can name an event that is not on the loaded page; it is fetched by id then, rather than
  // the link silently opening nothing.
  const setOpen = (e: AuditEventView | null) =>
    navigate({
      to: '.',
      search: (prev: Record<string, unknown>) => ({ ...prev, event: e?.id }),
    });
  const onPage = rows.find((e) => e.id === search.event);
  const offPage = useAuditEvent(clusterId, !onPage ? search.event : undefined);
  const selected = onPage ?? offPage.data ?? null;
  const total = query.data?.count ?? 0;

  // The user field keeps what was typed in it, so clearing the filters starts it afresh.
  const [filterEpoch, setFilterEpoch] = useState(0);
  const filtered = Boolean(
    search.user || search.action || search.outcome || search.from || search.to || search.parentId != null,
  );
  const clearFilters = () => {
    setFilterEpoch((n) => n + 1);
    void setParam({
      user: undefined,
      action: undefined,
      outcome: undefined,
      from: undefined,
      to: undefined,
      parentId: undefined,
    });
  };

  return (
    <Page fill>
      <PageHeader
        title="Audit log"
        description="Every operation that changed something, newest first, with who ran it and how it ended."
        actions={<ExportMenu clusterId={clusterId} filter={filter} total={query.data?.count ?? undefined} />}
      />

      <Toolbar label="Audit filters" start={<AuditFilters key={filterEpoch} search={search} setParam={setParam} />} />

      {search.parentId != null ? (
        <Group gap="xs">
          <Text size="sm">
            Showing the events that belong to audit event {search.parentId}, such as each subscription of a bulk run.
          </Text>
          <Button size="xs" variant="subtle" onClick={() => setParam({ parentId: undefined })}>
            Show every event
          </Button>
        </Group>
      ) : null}

      <ReplicaLagNotice mayLag={query.data?.mayLag} />

      <DataTable
        label="Audit events"
        storageKey="audit"
        height="fill"
        columns={columns}
        data={rows}
        rowKey={auditKey}
        loading={query.isPending}
        error={query.isError ? <ErrorState error={query.error} onRetry={() => void query.refetch()} /> : undefined}
        onRowClick={setOpen}
        toolbar={{
          // The count and position live in the pager, stated once.
          end: (
            <Pager
              page={page}
              pageSize={PAGE_SIZE}
              total={total}
              onChange={(next) =>
                navigate({
                  to: '.',
                  search: (p: Record<string, unknown>) => ({ ...p, page: next > 1 ? next : undefined }),
                })
              }
              label="audit events"
            />
          ),
        }}
        rowMenu={{
          label: (e) => `${e.action} at ${at(e)}`,
          render: (e) => <CopyAuditLink clusterId={clusterId} id={e.id} />,
        }}
        empty={<AuditEmpty filtered={filtered} onClearFilters={clearFilters} />}
      />

      <Drawer
        opened={search.event !== undefined}
        onClose={() => setOpen(null)}
        position="right"
        size="lg"
        title={selected ? selected.action : 'Audit event'}
      >
        {search.event === undefined ? null : (
          <AuditEventDetail selected={selected} offPage={offPage} setParam={setParam} />
        )}
      </Drawer>
    </Page>
  );
}
