import { useEffect, useMemo, useState, useRef } from 'react';
import { Button, Select, Text, TextInput } from '@mantine/core';
import { Link, useNavigate, useParams, useSearch } from '@tanstack/react-router';
import { useDebouncedValue } from '@mantine/hooks';

import { CapabilityLedger, useCluster, useRoleIds } from '../clusters/index.ts';
import { kindOf, refOf } from '../../kernel/api/resourceId.ts';
import { useMessages, type MessageSummaryView } from './api.ts';
import { CapabilityGate } from '../../ui/CapabilityGate.tsx';
import { EmptyState } from '../../ui/EmptyState.tsx';
import { ErrorState } from '../../ui/ErrorState.tsx';
import { Notice } from '../../ui/Notice.tsx';
import linkClasses from '../../ui/InlineLink.module.css';
import { Page } from '../../ui/Page.tsx';
import { PageHeader } from '../../ui/PageHeader.tsx';
import { Toolbar } from '../../ui/Toolbar.tsx';
import { DataTable } from '../../ui/table/index.ts';
import { messageColumns } from './columns.ts';
import { browseOf, SELECTOR_REFUSAL, type PartitionBrowse } from './partitionBrowse.ts';
import { PartitionControls, PartitionNotices } from './PartitionControls.tsx';
import { useMessageGate } from './gates.ts';
import { MessageDetailPanel } from './MessageDetailPanel.tsx';
import { MessageActions } from './MessageActions.tsx';
import { PurgeSubscription } from './PurgeSubscription.tsx';
import { SendMessage } from './SendMessage.tsx';
import { useDisplayZone } from '../../kernel/time/timezone.ts';
import { useSlot, type MessageSelection } from '../../kernel/slots.ts';
import { ResourceActions } from '../../kernel/actions/ResourceActions.tsx';
import { useTitlePart } from '../../kernel/shell/pageTitle.ts';
import { useFilterShortcut } from '../../kernel/keyboard/filterShortcut.ts';
import classes from './MessagesView.module.css';

const PAGE_SIZE = 200;

const NO_ROWS: MessageSummaryView[] = [];

const messageKey = (m: MessageSummaryView) => m.position;

/** "12 messages", or why the total is not known. */
function countLabel(page: { count?: number | null; countUnavailable?: string | null }): string {
  if (page.count == null) return `total unavailable — ${page.countUnavailable ?? 'the broker did not report it'}`;
  return `${page.count} message${page.count === 1 ? '' : 's'}`;
}

/** Picked rows win; otherwise the selector in force, otherwise the whole subscription. */
function selectionOf(pickedIds: number[], filter: string | undefined): MessageSelection {
  if (pickedIds.length > 0) return { kind: 'ids', ids: pickedIds };
  return filter ? { kind: 'filter', filter } : { kind: 'all' };
}

function selectionTotalOf(selection: MessageSelection, total: number | null | undefined): number | null {
  if (selection.kind === 'ids') return selection.ids.length;
  return selection.kind === 'all' ? (total ?? null) : null;
}

/** Why the grid is empty: the selector, nodes that did not answer, or genuinely nothing in the subscription. */
function MessagesEmpty({
  filtered,
  unreachable,
  onClearSelector,
  partitioned,
}: Readonly<{ filtered: boolean; unreachable: string[]; onClearSelector: () => void; partitioned: boolean }>) {
  if (partitioned && unreachable.length === 0) {
    return (
      <EmptyState
        kind="empty"
        title="No records from here"
        description="This partition has no committed record at or after the start you chose. Choose the earliest record, another partition or an earlier time."
      />
    );
  }
  if (filtered) {
    return (
      <EmptyState
        kind="filtered"
        title="No message matches this selector"
        description="The selector excludes every message in this subscription. Clear it to browse them all."
        onClearFilters={onClearSelector}
      />
    );
  }
  if (unreachable.length > 0) {
    return <EmptyState kind="unreachable" title="No messages could be listed" nodes={unreachable} />;
  }
  return (
    <EmptyState
      kind="empty"
      title="This subscription has no messages"
      description="Messages wait in a subscription until a consumer takes them. Produce to the subscription, or use Send above to add one. Messages here are read over Jolokia as text — faithful binary bodies need the Core client."
    />
  );
}

/**
 * Browse one subscription's messages (ADR-0021). Reached from a subscription row, not a
 * top-level tab. Node, filter and page are URL-owned (non-negotiable #9);
 * message selection is ephemeral (added in a later slice). The whole view is
 * gated on the `messageIo` capability — when it is not available the ledger
 * explains why and shows the `broker.xml` snippet, with no missing controls
 * (non-negotiable #5).
 */
export function MessagesView() {
  // `/` focuses this view's filter (ADR-0109).
  const filterRef = useRef<HTMLInputElement>(null);
  useFilterShortcut(filterRef);
  // Absolute timestamps here read the display zone from module state, so this
  // subscribes the view to a zone change (`app/timezone.ts`).
  const zone = useDisplayZone();
  const { clusterId, subscriptionName } = useParams({ strict: false }) as {
    clusterId: string;
    subscriptionName: string;
  };
  const search = useSearch({ strict: false }) as {
    node?: string;
    filter?: string;
    page?: number;
    message?: string;
    partition?: number;
    from?: string;
    value?: string;
  };
  const navigate = useNavigate();

  const cluster = useCluster(clusterId);
  const roleIds = useRoleIds(clusterId);
  // The subscription's reference needs its kind, which the cluster view gives; until then nothing offers it.
  const subscription = useMemo(
    () => (roleIds.loaded ? refOf(roleIds.subscription(subscriptionName)) : undefined),
    [roleIds, subscriptionName],
  );
  const sendGate = useMessageGate(clusterId, 'message:send', 'Send messages', 'messageIo');
  // A partitioned log is read from one partition and a start, not by selector, node and page.
  const subscriptionKind = roleIds.loaded ? kindOf(roleIds.subscription(subscriptionName)) : undefined;
  const partitioned = cluster.data?.kinds?.find((k) => k.id === subscriptionKind)?.partitioned === true;
  const browse = browseOf(search);
  const columns = useMemo(() => messageColumns(zone, partitioned), [zone, partitioned]);
  const [filter, setFilter] = useState(search.filter ?? '');
  const [debounced] = useDebouncedValue(filter, 250);
  // The open message is in the destination, so a link to one message opens it (non-negotiable #9).
  const openId = search.message ?? null;
  useTitlePart('resource', openId ? `${subscriptionName} › message ${openId}` : subscriptionName);
  const setOpenId = (id: string | null) =>
    navigate({ to: '.', search: (prev: Record<string, unknown>) => ({ ...prev, message: id ?? undefined }) });
  const [selected, setSelected] = useState<Set<string>>(new Set());
  const [sendOpen, setSendOpen] = useState(false);
  const page = search.page ?? 1;
  const selectionSlot = useSlot('messages.selection');

  // Selection is ephemeral (D10) — reset on any navigation of node / filter / page.
  useEffect(() => setSelected(new Set()), [search.node, search.filter, page]);

  const toggleRow = (key: string) =>
    setSelected((s) => {
      const next = new Set(s);
      if (next.has(key)) next.delete(key);
      else next.add(key);
      return next;
    });
  const toggleAll = (keys: string[], allSelected: boolean) =>
    setSelected((s) => {
      const next = new Set(s);
      keys.forEach((k) => (allSelected ? next.delete(k) : next.add(k)));
      return next;
    });

  useEffect(() => {
    if ((search.filter ?? '') === debounced) return;
    void navigate({
      to: '.',
      search: (prev: Record<string, unknown>) => ({
        ...prev,
        filter: debounced || undefined,
        page: undefined,
      }),
    });
  }, [debounced, navigate, search.filter]);

  const endpoints = useMemo(
    () => (cluster.data?.topology.nodes ?? []).flatMap((n) => n.endpoints).filter((e) => e.manageable),
    [cluster.data],
  );

  const messages = useMessages(
    clusterId,
    subscriptionName,
    partitioned ? { ...browse, size: PAGE_SIZE } : { node: search.node, filter: search.filter, page, size: PAGE_SIZE },
  );
  const setBrowse = (next: PartitionBrowse) =>
    navigate({
      to: '.',
      search: (prev: Record<string, unknown>) => ({
        ...prev,
        partition: next.partition,
        from: next.from === 'earliest' ? undefined : next.from,
        value: next.value,
        message: undefined,
      }),
    });

  const messageIo = cluster.data?.capabilities.messageIo;
  // Block only on a known refusal. Since ADR-0049 D5 this capability is UNKNOWN
  // until a management write has actually been attempted, and blocking on absence
  // of evidence would lock every operator out of a working broker until something
  // else happened to write to it first.
  const gated = messageIo?.status === 'UNAVAILABLE';
  const unproven = messageIo?.status === 'UNKNOWN';

  const header = (
    <PageHeader
      title="Messages"
      meta={
        <>
          <Link to={`/clusters/${clusterId}/subscriptions`} className={linkClasses.link}>
            All subscriptions
          </Link>
          <span className={classes.subscription}>{subscriptionName}</span>
        </>
      }
      description={
        messages.data
          ? `${countLabel(messages.data)} · read from ${
              endpoints.find((e) => e.id === messages.data.node)?.name ?? 'the live node'
            }`
          : 'Reading the subscription…'
      }
      actions={
        <>
          <CapabilityGate verdict={sendGate} what="sending a message">
            <Button disabled={sendGate.kind === 'blocked'} onClick={() => setSendOpen(true)}>
              Send
            </Button>
          </CapabilityGate>
          <PurgeSubscription clusterId={clusterId} subscriptionName={subscriptionName} node={search.node} />
        </>
      }
    />
  );

  const uncertainty = unproven ? (
    <Notice tone="info" title="Not yet established for this connection">
      No management write has been attempted here yet, so Rahdar cannot say for certain that message operations will
      work. They are offered anyway — the first one settles it.
    </Notice>
  ) : null;

  if (cluster.data && gated) {
    return (
      <Page>
        {header}
        <EmptyState
          kind="empty"
          title="Message operations are not available here"
          description={
            <>
              This connection cannot browse messages. The reason and the exact <code>broker.xml</code> change are below.
            </>
          }
        />
        <CapabilityLedger capabilities={cluster.data.capabilities} clusterId={clusterId} />
      </Page>
    );
  }

  const rows = messages.data?.data ?? NO_ROWS;
  // An unavailable total is stated, never read as zero: the page count is then unknown too.
  const total = messages.data?.count;
  const lastPage = total == null ? null : Math.max(1, Math.ceil(total / PAGE_SIZE));

  // Picked rows win; otherwise the selector in force, otherwise the whole subscription.
  const pickedIds = [...selected].map(Number).filter((n) => Number.isFinite(n));
  const selection = selectionOf(pickedIds, search.filter);
  // A selector's match count is the preview's to establish; the page's total is the whole subscription.
  const selectionTotal = selectionTotalOf(selection, total);

  // A node Rahdar could not reach contributes no rows, which looks exactly like a subscription with none.
  const unreachable = (cluster.data?.topology.nodes ?? [])
    .flatMap((n) => n.endpoints)
    .filter((e) => e.lastError)
    .map((e) => e.name);
  // The destination follows the field once typing pauses, so clearing the field clears the selector.
  const clearSelector = () => setFilter('');

  const setNode = (node: string | null) =>
    navigate({
      to: '.',
      search: (prev: Record<string, unknown>) => ({ ...prev, node: node || undefined, page: undefined }),
    });

  return (
    <Page fill>
      {header}
      {uncertainty}
      {partitioned ? <PartitionNotices page={messages.data} /> : null}

      <Toolbar
        label="Message filters"
        start={
          <>
            {partitioned ? <PartitionControls browse={browse} onBrowse={setBrowse} /> : null}
            <TextInput
              ref={filterRef}
              label="Message selector"
              placeholder={partitioned ? undefined : "Selector, e.g. region = 'eu'"}
              description={partitioned ? SELECTOR_REFUSAL : undefined}
              disabled={partitioned}
              value={partitioned ? '' : filter}
              onChange={(e) => setFilter(e.currentTarget.value)}
              w={partitioned ? '22rem' : '17.5rem'}
              size="xs"
            />
            {endpoints.length > 1 && !partitioned ? (
              <Select
                size="xs"
                w="11.25rem"
                label="Node to browse"
                placeholder="Live node"
                clearable
                value={search.node ?? null}
                onChange={setNode}
                data={endpoints.map((e) => ({ value: e.id, label: e.name }))}
              />
            ) : null}
          </>
        }
        end={
          <>
            {partitioned ? null : (
              <Text size="xs" c="dimmed">
                {lastPage == null ? `page ${page} · total unavailable` : `page ${page} of ${lastPage}`}
              </Text>
            )}
            {subscription
              ? selectionSlot.map(({ id, Component }) => (
                  <Component
                    key={id}
                    clusterId={clusterId}
                    subscription={subscription}
                    node={search.node}
                    selection={selection}
                    total={selectionTotal}
                    clear={() => setSelected(new Set())}
                  />
                ))
              : null}
          </>
        }
      />

      <MessageActions
        clusterId={clusterId}
        subscriptionName={subscriptionName}
        node={search.node}
        selected={selected}
        onCleared={() => setSelected(new Set())}
      />

      <DataTable
        label="Messages"
        storageKey="messages"
        height="fill"
        columns={columns}
        data={rows}
        rowKey={messageKey}
        loading={messages.isPending}
        error={
          messages.isError ? <ErrorState error={messages.error} onRetry={() => void messages.refetch()} /> : undefined
        }
        onRowClick={(m) => setOpenId(m.position)}
        selectable
        selected={selected}
        onToggleRow={toggleRow}
        onToggleAll={toggleAll}
        rowMenu={{
          label: (m) => `message ${m.position}`,
          render: (m, menu) =>
            subscription ? (
              <ResourceActions
                kind="message"
                clusterId={clusterId}
                target={{ subscription, position: m.position, node: search.node }}
                restoreFocus={menu.restoreFocus}
              />
            ) : null,
        }}
        empty={
          <MessagesEmpty
            filtered={Boolean(search.filter)}
            unreachable={unreachable}
            onClearSelector={clearSelector}
            partitioned={partitioned}
          />
        }
      />

      <MessageDetailPanel
        clusterId={clusterId}
        subscriptionName={subscriptionName}
        messageId={openId}
        node={search.node}
        filter={search.filter}
        onClose={() => setOpenId(null)}
      />

      <SendMessage
        clusterId={clusterId}
        subscriptionName={subscriptionName}
        node={search.node}
        opened={sendOpen}
        onClose={() => setSendOpen(false)}
      />
    </Page>
  );
}
