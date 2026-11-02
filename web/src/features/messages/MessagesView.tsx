import { useEffect, useMemo, useState, useRef, type Dispatch, type SetStateAction } from 'react';
import { Button, Select, Text, TextInput } from '@mantine/core';
import { Link, useNavigate, useParams, useSearch } from '@tanstack/react-router';
import { useDebouncedValue } from '@mantine/hooks';

import { CapabilityLedger, useCluster, useRoleIds } from '../clusters/index.ts';
import { kindOf, refOf, subscriptionRefWords } from '../../kernel/api/resourceId.ts';
import { verbRefusal } from '../../kernel/auth/kindVerbs.ts';
import { useMessages, type MessagePageView, type MessageSummaryView } from './api.ts';
import { CapabilityGate } from '../../ui/CapabilityGate.tsx';
import type { GateVerdict } from '../../ui/capabilityGate.ts';
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
import { useClusterSlot } from '../../kernel/shell/useClusterSlot.ts';
import type { MessageSelection } from '../../kernel/slots.ts';
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

/** The last page of a total, or none while the total is not known. */
function lastPageOf(total: number | null | undefined): number | null {
  return total == null ? null : Math.max(1, Math.ceil(total / PAGE_SIZE));
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
  singleLog,
}: Readonly<{
  filtered: boolean;
  unreachable: string[];
  onClearSelector: () => void;
  partitioned: boolean;
  singleLog: boolean;
}>) {
  if (singleLog && unreachable.length === 0) {
    return (
      <EmptyState
        kind="empty"
        title="No records from here"
        description="This stream has no record at or after the start you chose. Choose the earliest record or an earlier time."
      />
    );
  }
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
 * Whether this subscription is read as a partitioned log. The cluster's kind list is the authority
 * once it has loaded. Before that, a Kafka group on a topic is already known to be one, so the browse
 * controls are in the first paint instead of replacing the queue toolbar when the cluster arrives.
 */
export function isPartitionedLog(
  kind: string | undefined,
  kinds: readonly { id: string; partitioned?: boolean }[] | undefined,
): boolean {
  const listed = kind ? kinds?.find((item) => item.id === kind) : undefined;
  if (listed) return listed.partitioned === true;
  return kind === 'kafka.group-topic' || kind === 'rabbitmq.stream';
}

/** The kind a subscription is, from the cluster's kind list, when both are known. */
function kindOfSubscription(
  kind: string | undefined,
  kinds:
    | readonly { id: string; resourceClass: string; verbs: string[]; label: { singular: string; plural: string } }[]
    | undefined,
) {
  return kind ? kinds?.find((item) => item.id === kind) : undefined;
}

/**
 * Whether the subscription's log is one stream rather than the partitions of a topic: a partitioned kind of the
 * subscription class that is not a consumer group's position on a topic. It has no partition to choose.
 */
function isSingleLog(kind: { id: string; resourceClass: string } | undefined, partitioned: boolean): boolean {
  return partitioned && kind?.resourceClass === 'SUBSCRIPTION' && kind.id !== 'kafka.group-topic';
}

/** What kind of log a subscription is read as, from its kind and the cluster's kind list. */
function logShapeOf(
  kind: string,
  kinds: Parameters<typeof kindOfSubscription>[1],
): { partitioned: boolean; kindInfo: ReturnType<typeof kindOfSubscription>; singleLog: boolean } {
  const partitioned = isPartitionedLog(kind, kinds);
  const kindInfo = kindOfSubscription(kind, kinds);
  const singleLog = kindInfo ? isSingleLog(kindInfo, partitioned) : partitioned && kind !== 'kafka.group-topic';
  return { partitioned, kindInfo, singleLog };
}

/** Why a kind the system cannot browse is refused before anything is read: the driver's reason when it gave one. */
function browseRefusalOf(
  cluster: ReturnType<typeof useCluster>['data'],
  kindInfo: ReturnType<typeof kindOfSubscription>,
): string | undefined {
  if (!cluster?.system || !kindInfo || kindInfo.verbs.includes('browse')) return undefined;
  return (
    cluster.capabilities.findings.find((f) => f.capability === 'messages.browse')?.reason ??
    verbRefusal(cluster.system.name, kindInfo.label, 'browse')
  );
}

/** The set of picked rows, which the view empties when the node, the filter or the page changes (D10). */
function useRowSelection(): {
  selected: Set<string>;
  setSelected: Dispatch<SetStateAction<Set<string>>>;
  toggleRow: (key: string) => void;
  toggleAll: (keys: string[], allSelected: boolean) => void;
} {
  const [selected, setSelected] = useState<Set<string>>(new Set());
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
  return { selected, setSelected, toggleRow, toggleAll };
}

/** The page the header describes: a partition of a log, or a count read from one node. */
function messagesHeading(
  page: MessagePageView | undefined,
  partitioned: boolean,
  partition: number,
  endpoints: readonly { id: string; name: string }[],
  singleLog?: string,
): string {
  if (!page) return 'Reading the subscription…';
  if (singleLog) return singleLog;
  if (!partitioned) {
    const node = endpoints.find((endpoint) => endpoint.id === page.node)?.name ?? 'the live node';
    return `${countLabel(page)} · read from ${node}`;
  }
  const hidden =
    page.log?.uncommittedHidden === false
      ? ''
      : '. Only committed records are shown: those of a transaction that is still open or was aborted are not.';
  return `Partition ${partition}${hidden}`;
}

/** What the browse asks for: one partition and a start, or a node, a selector and a page. */
function messageQuery(
  partitioned: boolean,
  browse: PartitionBrowse,
  search: { node?: string; filter?: string },
  page: number,
) {
  if (partitioned) return { ...browse, size: PAGE_SIZE };
  return { node: search.node, filter: search.filter, page, size: PAGE_SIZE };
}

/** The selector field of a queue, or the explanation that a log has no selector. */
function selectorField(partitioned: boolean, singleLog: boolean, filter: string) {
  if (singleLog) {
    return {
      placeholder: undefined,
      description: 'A stream has no selector. Choose where to start reading, an offset or a time.',
      value: '',
      width: '22rem',
    };
  }
  if (partitioned) return { placeholder: undefined, description: SELECTOR_REFUSAL, value: '', width: '22rem' };
  return { placeholder: "Selector, e.g. region = 'eu'", description: undefined, value: filter, width: '17.5rem' };
}

/** Where the browse stands: the group's offset on a log, or the page of a queue. */
function browsePosition(
  partitioned: boolean,
  singleLog: boolean,
  log: MessagePageView['log'],
  page: number,
  lastPage: number | null,
) {
  // A stream is read by offset; no consumer group stands anywhere on it.
  if (singleLog) return null;
  if (partitioned) {
    // Said in the toolbar, where its arrival moves nothing: the controls keep their place.
    if (log?.subscriptionPosition == null) {
      return <Text size="xs">The group's position on this partition is unavailable.</Text>;
    }
    return (
      <Text size="xs">
        {`The group stands at offset ${log.subscriptionPosition.toLocaleString()} on partition ${log.partition}: records from there on it has not read yet.`}
      </Text>
    );
  }
  const pages = lastPage == null ? `page ${page} · total unavailable` : `page ${page} of ${lastPage}`;
  return (
    <Text size="xs" c="dimmed">
      {pages}
    </Text>
  );
}

/** The node to browse, offered when more than one is manageable and the subscription is not a log. */
function NodePicker({
  endpoints,
  partitioned,
  value,
  onChange,
}: Readonly<{
  endpoints: readonly { id: string; name: string }[];
  partitioned: boolean;
  value: string | undefined;
  onChange: (node: string | null) => void;
}>) {
  if (endpoints.length <= 1 || partitioned) return null;
  return (
    <Select
      size="xs"
      w="11.25rem"
      label="Node to browse"
      placeholder="Live node"
      clearable
      value={value ?? null}
      onChange={onChange}
      data={endpoints.map((e) => ({ value: e.id, label: e.name }))}
    />
  );
}

/** What other features add beside the selection, once the subscription's reference is known. */
function SelectionSlots({
  clusterId,
  subscription,
  node,
  selection,
  total,
  onClear,
}: Readonly<{
  clusterId: string;
  subscription: ReturnType<typeof refOf> | undefined;
  node: string | undefined;
  selection: MessageSelection;
  total: number | null;
  onClear: () => void;
}>) {
  const slots = useClusterSlot('messages.selection', clusterId);
  if (!subscription) return null;
  return slots.map(({ id, Component }) => (
    <Component
      key={id}
      clusterId={clusterId}
      subscription={subscription}
      node={node}
      selection={selection}
      total={total}
      clear={onClear}
    />
  ));
}

/** What the header says of the page: the refusal, or the partition or node the page was read from. */
interface HeaderDescription {
  /** Whether the kind cannot be browsed. */
  refused: boolean;
  page: MessagePageView | undefined;
  partition: number;
  endpoints: readonly { id: string; name: string }[];
  logNoun: string | undefined;
  /** Whether the line keeps its height while the page loads. */
  stable: boolean;
}

function MessagesHeader({
  clusterId,
  subscriptionName,
  node,
  partitioned,
  description,
  sendGate,
  onSend,
}: Readonly<{
  clusterId: string;
  subscriptionName: string;
  node: string | undefined;
  partitioned: boolean;
  description: HeaderDescription;
  sendGate: GateVerdict;
  onSend: () => void;
}>) {
  const text = description.refused
    ? 'This kind of queue cannot be read, only sent to and purged.'
    : messagesHeading(description.page, partitioned, description.partition, description.endpoints, description.logNoun);
  return (
    <PageHeader
      title="Messages"
      meta={
        <>
          <Link to={`/clusters/${clusterId}/subscriptions`} className={linkClasses.link}>
            All subscriptions
          </Link>
          <span className={classes.subscription}>{subscriptionRefWords(subscriptionName)}</span>
        </>
      }
      description={<span className={description.stable ? classes.stableHeading : undefined}>{text}</span>}
      actions={
        <>
          <CapabilityGate verdict={sendGate} what="sending a message">
            <Button disabled={sendGate.kind === 'blocked'} onClick={onSend}>
              Send
            </Button>
          </CapabilityGate>
          <PurgeSubscription
            clusterId={clusterId}
            subscriptionName={subscriptionName}
            node={node}
            moves={partitioned}
          />
        </>
      }
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
  const { partitioned, kindInfo, singleLog } = logShapeOf(
    subscriptionKind ?? kindOf(subscriptionName),
    cluster.data?.kinds,
  );
  // A kind the system cannot browse is refused before anything is read.
  const browseRefusal = browseRefusalOf(cluster.data, kindInfo);
  const browse = browseOf(search);
  const columns = useMemo(() => messageColumns(zone, partitioned), [zone, partitioned]);
  const [filter, setFilter] = useState(search.filter ?? '');
  const [debounced] = useDebouncedValue(filter, 250);
  // The open message is in the destination, so a link to one message opens it (non-negotiable #9).
  const openId = search.message ?? null;
  useTitlePart(
    'resource',
    openId ? `${subscriptionRefWords(subscriptionName)} › message ${openId}` : subscriptionRefWords(subscriptionName),
  );
  const setOpenId = (id: string | null) =>
    navigate({ to: '.', search: (prev: Record<string, unknown>) => ({ ...prev, message: id ?? undefined }) });
  const { selected, setSelected, toggleRow, toggleAll } = useRowSelection();
  const [sendOpen, setSendOpen] = useState(false);
  const page = search.page ?? 1;

  // Selection is ephemeral (D10) — reset on any navigation of node / filter / page.
  useEffect(() => setSelected(new Set()), [search.node, search.filter, page, setSelected]);

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
    messageQuery(partitioned, browse, search, page),
    !browseRefusal,
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
  // Reading a log needs no write, so the notice about writes is not said over one, and its late arrival moves nothing.
  const unproven = messageIo?.status === 'UNKNOWN' && !partitioned;

  const header = (
    <MessagesHeader
      clusterId={clusterId}
      subscriptionName={subscriptionName}
      node={search.node}
      partitioned={partitioned}
      description={{
        refused: browseRefusal !== undefined,
        page: messages.data,
        partition: browse.partition,
        endpoints,
        logNoun: singleLog ? kindInfo?.label.singular : undefined,
        stable: partitioned && !singleLog,
      }}
      sendGate={sendGate}
      onSend={() => setSendOpen(true)}
    />
  );

  const uncertainty = unproven ? (
    <Notice tone="info" title="Not yet established for this connection">
      No management write has been attempted here yet, so Rahdar cannot say for certain that message operations will
      work. They are offered anyway — the first one settles it.
    </Notice>
  ) : null;

  if (browseRefusal) {
    return (
      <Page>
        {header}
        <EmptyState kind="empty" title="These messages cannot be browsed" description={browseRefusal} />
      </Page>
    );
  }

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
  const lastPage = lastPageOf(total);

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
  const selector = selectorField(partitioned, singleLog, filter);

  const setNode = (node: string | null) =>
    navigate({
      to: '.',
      search: (prev: Record<string, unknown>) => ({ ...prev, node: node || undefined, page: undefined }),
    });

  return (
    <Page fill>
      {header}
      {uncertainty}
      <Toolbar
        label="Message filters"
        start={
          <>
            {partitioned ? (
              <PartitionControls
                browse={browse}
                partitions={singleLog ? 1 : undefined}
                onBrowse={setBrowse}
                next={messages.data?.log?.next ?? null}
              />
            ) : null}
            <TextInput
              ref={filterRef}
              label="Message selector"
              placeholder={selector.placeholder}
              description={selector.description}
              disabled={partitioned}
              value={selector.value}
              onChange={(e) => setFilter(e.currentTarget.value)}
              w={selector.width}
              size="xs"
            />
            <NodePicker endpoints={endpoints} partitioned={partitioned} value={search.node} onChange={setNode} />
          </>
        }
        end={
          <>
            {browsePosition(partitioned, singleLog, messages.data?.log, page, lastPage)}
            <SelectionSlots
              clusterId={clusterId}
              subscription={subscription}
              node={search.node}
              selection={selection}
              total={selectionTotal}
              onClear={() => setSelected(new Set())}
            />
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

      {/* Below the controls, so what the read has to say arrives under them and never pushes them down. */}
      {partitioned ? <PartitionNotices page={messages.data} browse={browse} /> : null}

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
            singleLog={singleLog}
          />
        }
      />

      <MessageDetailPanel
        clusterId={clusterId}
        subscriptionName={subscriptionName}
        messageId={openId}
        node={search.node}
        filter={search.filter}
        partition={partitioned ? browse.partition : undefined}
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
