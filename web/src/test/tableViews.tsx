import type { ReactElement } from 'react';

import { DataTable, type Column, type ColumnKind, type ColumnPriority } from '../ui/table/index.ts';
import { AUTO } from '../kernel/time/timezone.ts';
import { auditColumns } from '../features/audit/columns.tsx';
import { nodeFacts, type NodeFacts } from '../features/clusters/nodeFacts.ts';
import { topologyColumns } from '../features/clusters/topologyColumns.tsx';
import { bridgeColumns, divertColumns } from '../features/routing/columns.ts';
import { eventColumns } from '../features/events/columns.ts';
import { healthColumns, storeColumns } from '../features/lifecycle/columns.ts';
import { itemColumns, previewColumns, runColumns } from '../features/bulk/columns.ts';
import { dlqColumns, messageColumns, type DlqRow } from '../features/messages/columns.ts';
import { consumerHealthColumns } from '../features/triage/columns.ts';
import { nodeColumns, pathColumns, type NodeRow, type PathRow } from '../features/flow/columns.ts';
import { pluginColumns } from '../features/plugins/columns.ts';
import { queueColumns } from '../features/queues/columns.ts';
import { resourceColumns } from '../features/resources/columns.tsx';
import { resultColumns } from '../features/sql/columns.ts';
import { transferColumns } from '../features/transfer/columns.ts';
import type { AuditEventView } from '../features/audit/api.ts';
import type { LogicalNodeView, NodeEndpointView } from '../features/clusters/api.ts';
import type { BrokerEventView } from '../features/events/api.ts';
import type { BulkItemView, BulkRunView } from '../features/bulk/api.ts';
import type { ConsumerHealthView } from '../features/triage/api.ts';
import type { FlowEdgeView, FlowNodeView } from '../features/flow/api.ts';
import type { MessageSummaryView } from '../features/messages/api.ts';
import type { PluginUpdateView, PluginView } from '../features/plugins/api.ts';
import { resourceId } from '../kernel/api/resourceId.ts';
import { ADDRESS_KIND, addressId, QUEUE_KIND, queueId } from './wire.ts';
import type { SubscriptionView } from '../features/queues/api.ts';
import type {
  DestinationView,
  ConnectionView,
  ConsumerView,
  ProducerView,
  ChannelView,
} from '../features/resources/api.ts';
import type { BridgeRow, DivertRow } from '../features/routing/api.ts';
import type { SqlRowView } from '../features/sql/api.ts';
import type { StoreView, TableView } from '../features/lifecycle/api.ts';
import type { TransferRunView } from '../features/transfer/api.ts';

/**
 * Every grid's real columns, each with the rows a table browser test draws (ADR-0165). A `normal`
 * fixture is what an installation typically shows; a `long` one stresses the widths: names of 120
 * characters or more, a 200-character address and figures in the billions. In the long fixture the
 * first two rows differ only in the last eight characters of each name, so a test can tell whether
 * shortening an identifier kept the part that tells them apart.
 */
export type Fixture = 'normal' | 'long';

/** What a test needs to know about a column without knowing its row type. */
export interface ViewColumn {
  id: string;
  header: string;
  /** What the header cell draws: the column's short label when it has one, else its header. */
  label: string;
  kind: ColumnKind;
  priority: ColumnPriority;
  hasCell: boolean;
}

export interface TableView_ {
  name: string;
  columns: ViewColumn[];
  /** Whether the grid has a leading checkbox column and a trailing row-menu column, as the real view does. */
  selectable: boolean;
  menu: boolean;
  /** The columns whose values are middle-shortened identifiers, which the long fixture makes differ only at the end. */
  identifiers: string[];
  /** The columns a 1280 px window shows with the normal fixture, in order. At 1920 px every column shows. */
  visible1280: string[];
  table: (fixture: Fixture) => ReactElement;
}

// ── Text the fixtures are made of ──────────────────────────────────────────────────────────────

const WORDS = ['created', 'shipped', 'billed', 'refunded', 'archived', 'retried'];
const SUFFIX = ['a1b2c3d4', 'e5f6a7b8', 'c9d0e1f2', 'a3b4c5d6', 'e7f8a9b0', 'c1d2e3f4'];
const BLOCK = 'tenant-eu-central-1.billing-platform.region-primary.';

/** A name: short and plausible in the normal fixture, 120 characters or more in the long one. */
export function name(f: Fixture, stem: string, i: number): string {
  return f === 'normal'
    ? `${stem}.${WORDS[i % WORDS.length]}`
    : `${stem}.${BLOCK.repeat(2)}${SUFFIX[i % SUFFIX.length]}`;
}

/** An address: 200 characters in the long fixture. */
function address(f: Fixture, i: number): string {
  return f === 'normal'
    ? `orders.${WORDS[i % WORDS.length]}.v1`
    : `${BLOCK.repeat(4).slice(0, 192)}${SUFFIX[i % SUFFIX.length]}`;
}

/** A short identifier of a thing the broker numbers; long in the long fixture. */
function ident(f: Fixture, stem: string, i: number): string {
  return f === 'normal' ? `${stem}-${i + 1}` : name(f, stem, i);
}

/** A figure: ordinary in the normal fixture, in the billions in the long one. */
function num(f: Fixture, value: number): number {
  return f === 'normal' ? value : value * 1_000_003;
}

/** Six nodes in the states the table words: serving, in step, stopped and not caught up, unreachable, and not polled. */
const topologyNode = (f: Fixture, i: number): NodeFacts => {
  const id = ident(f, 'node-id', i);
  const endpoint: NodeEndpointView = {
    id: `endpoint-${i}`,
    name: name(f, 'broker', i),
    artemisNodeId: id,
    jolokiaUrl: `http://${name(f, 'broker', i)}:8161/jolokia`,
    coreUrl: `${name(f, 'broker', i)}:61616`,
    haRole: i % 2 ? 'BACKUP' : 'PRIMARY',
    state: i === 3 ? 'STOPPED' : 'STARTED',
    active: i % 2 === 0,
    replicaSync: i % 2 ? i !== 3 : null,
    version: '2.44.0',
    versionSupport: i === 4 ? 'NEWER_THAN_TESTED' : 'SUPPORTED',
    lastError: i === 4 ? 'Connection refused: no further information' : null,
    lastSeenAt: at(i),
    urlSource: null,
    urlProblem: null,
    coreUrlManual: false,
    manageable: i !== 5,
  };
  const logical: LogicalNodeView = {
    artemisNodeId: id,
    splitBrain: 'NONE',
    replicationBehind: i === 3,
    endpoints: [endpoint],
  };
  return nodeFacts(endpoint, logical);
};

const ROWS = [0, 1, 2, 3, 4, 5];
const NODE = (f: Fixture, i: number) => ({
  nodeId: `node-${(i % 2) + 1}`,
  nodeName: f === 'normal' ? `broker-${(i % 2) + 1}` : name(f, 'broker', i % 2),
});
const at = (i: number) => `2026-09-30T14:0${i}:12Z`;
const CLUSTER = 'cluster-1';

// ── Building a view ────────────────────────────────────────────────────────────────────────────

interface Spec<T> {
  name: string;
  label: string;
  columns: Column<T>[];
  rows: (f: Fixture) => T[];
  rowKey: (row: T) => string;
  selectable?: boolean;
  menu?: boolean;
  /** `{ maxRows }` where the real view is as tall as its rows. */
  height?: { maxRows: number };
  identifiers: string[];
  visible1280: string[];
}

function view<T>(spec: Spec<T>): TableView_ {
  const { columns } = spec;
  return {
    name: spec.name,
    columns: columns.map((c) => ({
      id: c.id,
      header: c.header,
      label: c.short ?? c.header,
      kind: c.kind,
      priority: c.priority,
      hasCell: c.cell !== undefined,
    })),
    selectable: spec.selectable ?? false,
    menu: spec.menu ?? false,
    identifiers: spec.identifiers,
    visible1280: spec.visible1280,
    table: (f) => {
      const noop = () => {};
      const selection = spec.selectable
        ? { selectable: true as const, selected: new Set<string>(), onToggleRow: noop, onToggleAll: noop }
        : {};
      const menu = spec.menu ? { rowMenu: { label: () => 'row', render: () => null } } : {};
      return (
        <DataTable
          label={spec.label}
          columns={columns}
          data={spec.rows(f)}
          rowKey={spec.rowKey}
          empty={null}
          height={spec.height ?? 'fill'}
          // Every real view sorts through the URL, which gives its sortable headers a button and a mark.
          onSortChange={noop}
          {...selection}
          {...menu}
        />
      );
    },
  };
}

// ── Fixtures, by row type ──────────────────────────────────────────────────────────────────────

/** The identity fields of a queue's subscription row. */
const subscriptionIdentity = (name: string) => ({
  id: queueId(name),
  kind: QUEUE_KIND,
  kindLabel: 'Queue',
  name,
});

const queue = (f: Fixture, i: number): SubscriptionView => ({
  ...subscriptionIdentity(name(f, 'orders', i)),
  destination: addressId(address(f, i)),
  nativeAttributes: { routingType: i % 2 ? 'ANYCAST' : 'MULTICAST' },
  durable: i % 3 !== 0,
  totalMessageCount: num(f, 12_840 + i),
  totalConsumerCount: num(f, 4 + i),
  totalDeliveringCount: num(f, 31 + i),
  totalScheduledCount: num(f, 2 + i),
  nodesPresent: 2,
  nodesTotal: 2,
  paused: i === 2,
  perNode: [],
  allowedActions: [],
});

const address_ = (f: Fixture, i: number): DestinationView => ({
  ...NODE(f, i),
  id: addressId(address(f, i)),
  kind: ADDRESS_KIND,
  kindLabel: 'Address',
  name: address(f, i),
  delivery: ['POINT_TO_POINT', 'PUBLISH_SUBSCRIBE'],
  subscriptionCount: num(f, 3 + i),
  messageCount: num(f, 9_200 + i),
  allowedActions: [],
});

const consumer = (f: Fixture, i: number): ConsumerView => ({
  ...NODE(f, i),
  id: resourceId('artemis.consumer', ident(f, 'session', i), ident(f, 'consumer', i)),
  kind: 'artemis.consumer',
  kindLabel: 'Consumer',
  channel: resourceId('artemis.session', ident(f, 'session', i)),
  subscription: queueId(name(f, 'orders', i)),
  destination: addressId(address(f, i)),
  protocol: 'AMQP',
  messagesDelivered: num(f, 8_431 + i),
  messagesAcknowledged: num(f, 8_400 + i),
  messagesInFlight: num(f, 12 + i),
  allowedActions: [],
});

const session = (f: Fixture, i: number): ChannelView => ({
  ...NODE(f, i),
  id: resourceId('artemis.session', ident(f, 'session', i)),
  kind: 'artemis.session',
  kindLabel: 'Session',
  user: f === 'normal' ? 'svc-orders' : name(f, 'svc', i),
  connection: resourceId('artemis.connection', ident(f, 'connection', i)),
  consumerCount: num(f, 2 + i),
  producerCount: num(f, 1 + i),
  creationTime: at(i),
  allowedActions: [],
});

const connection = (f: Fixture, i: number): ConnectionView => ({
  ...NODE(f, i),
  id: resourceId('artemis.connection', ident(f, 'connection', i)),
  kind: 'artemis.connection',
  kindLabel: 'Connection',
  remoteAddress: f === 'normal' ? `10.4.${i}.17:5672` : address(f, i),
  protocol: 'AMQP',
  clientId: ident(f, 'client', i),
  channelCount: num(f, 3 + i),
  creationTime: at(i),
  allowedActions: [],
});

const producer = (f: Fixture, i: number): ProducerView => ({
  ...NODE(f, i),
  id: resourceId('artemis.producer', ident(f, 'session', i), ident(f, 'producer', i)),
  kind: 'artemis.producer',
  kindLabel: 'Producer',
  clientId: f === 'normal' ? 'order-writer' : name(f, 'writer', i),
  channel: resourceId('artemis.session', ident(f, 'session', i)),
  destination: addressId(address(f, i)),
  protocol: 'CORE',
  messagesSent: num(f, 55_120 + i),
  allowedActions: [],
});

const divert = (f: Fixture, i: number): DivertRow => ({
  name: name(f, 'divert', i),
  routingName: name(f, 'divert', i),
  address: address(f, i),
  forwardingAddress: address(f, i + 1),
  filter: f === 'normal' ? "region = 'eu'" : `${BLOCK.repeat(3)}region = 'eu'`,
  routingType: 'MULTICAST',
  transformerClassName: null,
  exclusive: i % 2 === 0,
  retroactiveResource: false,
  owner: ['MESSAGE_CAPTURE', 'OPERATOR', null][i % 3],
  captureSubscriptionId: null,
  brokerXml: null,
  nodesPresent: 2,
  nodesTotal: 2,
  perNode: [],
});

const bridge = (f: Fixture, i: number): BridgeRow => ({
  name: name(f, 'bridge', i),
  queueName: name(f, 'orders', i),
  forwardingAddress: address(f, i),
  filterString: null,
  discoveryGroupName: null,
  transformerClassName: null,
  staticConnectors: [],
  messagesAcknowledged: num(f, 1_000 + i),
  messagesPendingAcknowledgement: num(f, 12 + i),
  started: true,
  connected: i !== 2,
  useDuplicateDetection: true,
  highlyAvailable: false,
  nodesPresent: 2,
  nodesTotal: 2,
  perNode: [],
});

const message = (f: Fixture, i: number): MessageSummaryView => ({
  position: String(num(f, 5_000_000 + i)),
  type: 4,
  durable: i % 2 === 0,
  priority: 4,
  timestamp: Date.parse(at(i)),
  expiration: 0,
  size: num(f, 1_024 + i),
  groupId: null,
  correlationId: null,
  bodyPreview: f === 'normal' ? '{"order":1042,"status":"created"}' : `{"order":"${BLOCK.repeat(4)}"}`,
  bodyTruncated: i === 1,
  propertyCount: 3,
  redactions: [],
});

const dlqRow = (f: Fixture, i: number): DlqRow => ({
  address: f === 'normal' ? ['DLQ', 'ExpiryQueue'][i % 2] : address(f, i),
  kind: i % 2 ? 'expiry' : 'dead-letter',
  queue: {
    ...subscriptionIdentity(name(f, 'DLQ.orders', i)),
    destination: addressId(f === 'normal' ? ['DLQ', 'ExpiryQueue'][i % 2] : address(f, i)),
    totalDepth: num(f, 1_204 + i),
    perNode: [
      { nodeId: 'node-1', nodeName: 'broker-1', depth: num(f, 600 + i) },
      { nodeId: 'node-2', nodeName: 'broker-2', depth: num(f, 604) },
    ],
  },
});

const brokerEvent = (f: Fixture, i: number): BrokerEventView => ({
  seq: i + 1,
  occurredAt: at(i),
  receivedAt: at(i),
  type: [
    'openbroker.consumer.attached',
    'openbroker.channel.closed',
    'openbroker.client.disconnected',
    'openbroker.resource.created',
    'openbroker.resource.deleted',
    'openbroker.message.expired',
  ][i],
  destination: address(f, i),
  routingName: name(f, 'orders', i),
  consumerName: name(f, 'consumer', i),
  sessionName: null,
  connectionName: null,
  remoteAddress: f === 'normal' ? `10.4.${i}.17:5672` : address(f, i),
  username: 'svc-orders',
  nodeId: 'node-1',
  props: null,
});

const auditEvent = (f: Fixture, i: number): AuditEventView => ({
  id: i + 1,
  parentId: null,
  ts: at(i),
  username: f === 'normal' ? 'a.rahimi' : name(f, 'operator', i),
  sourceIp: '10.0.0.4',
  requestId: null,
  action: f === 'normal' ? 'subscription.purge' : `${BLOCK.repeat(2).slice(0, 80)}subscription.purge`,
  targetType: 'queue',
  targetName: name(f, 'orders', i),
  affectedCount: num(f, 120 + i),
  outcome: ['SUCCESS', 'FAILURE', 'PENDING'][i % 3],
  dryRun: i === 3,
  params: null,
  error: null,
  clusterName: 'production',
  nodeId: 'node-1',
});

const transfer = (f: Fixture, i: number): TransferRunView => ({
  id: `run-${i + 1}`,
  mode: i % 2 ? 'COPY' : 'MOVE',
  state: (['SUCCEEDED', 'RUNNING', 'PARTIAL', 'FAILED', 'STOPPED', 'RETURNED'] as const)[i],
  source: {
    clusterId: CLUSTER,
    nodeId: 'node-1',
    nodeName: f === 'normal' ? 'broker-1' : name(f, 'broker', i),
    subscription: resourceId('artemis.queue', name(f, 'orders', i)),
    destination: resourceId('artemis.address', address(f, i)),
  },
  target: {
    clusterId: i === 3 ? 'cluster-2' : CLUSTER,
    nodeId: 'node-2',
    nodeName: f === 'normal' ? 'broker-2' : name(f, 'broker', i + 1),
    subscription: resourceId('artemis.queue', name(f, 'archive', i)),
    destination: resourceId('artemis.address', address(f, i + 1)),
  },
  sameNode: false,
  selection: { kind: 'ALL' },
  t0: at(i),
  planHash: 'abc123',
  username: f === 'normal' ? 'a.rahimi' : name(f, 'operator', i),
  createdAt: at(i),
  expiresAt: at(i),
  startedAt: at(i),
  updatedAt: at(i),
  finishedAt: at(i),
  estimate: num(f, 500),
  estimateBytes: null,
  staged: 0,
  held: 0,
  delivered: num(f, 480 + i),
  notTransferred: 0,
  expired: 0,
  returned: 0,
  bytes: num(f, 51_200),
  messagesPerSecond: null,
  stagingQueue: null,
  findings: [],
  notes: [],
  cap: 100_000,
  overCap: false,
  overrideCap: false,
  resumable: false,
  returnable: false,
  auditEventId: null,
  targetAuditEventId: null,
  lastError: null,
  errorSnippet: null,
});

const health = (f: Fixture, i: number): ConsumerHealthView => ({
  destination: resourceId('artemis.address', address(f, i)),
  subscription: resourceId('artemis.queue', name(f, 'orders', i)),
  verdict: ['NO_CONSUMERS', 'STALLED', 'FALLING_BEHIND', 'DRAINING', 'HEALTHY', 'INSUFFICIENT_DATA'][i],
  severity: 6 - i,
  cause: 'cause',
  source: 'SAMPLED',
  brokerConsumerName: null,
  depth: num(f, 12_840 + i),
  consumers: num(f, 4 + i),
  delivering: num(f, 31 + i),
  scheduled: 0,
  paused: false,
  depthSlopePerSecond: i === 5 ? null : 0.4 * (i - 2),
  addRate: 1.5,
  ackRate: i === 5 ? null : 1.2,
  netRate: 0.3,
  ackRatePerConsumer: 0.3,
  drainEtaSeconds: null,
  asOf: at(i),
  sampleSpanSeconds: 300,
  stale: i === 4,
  nodesPresent: 2,
  nodesTotal: 2,
});

const sqlRow = (f: Fixture, i: number): SqlRowView => ({
  nodeId: `node-${(i % 2) + 1}`,
  nodeName: f === 'normal' ? `broker-${(i % 2) + 1}` : name(f, 'broker', i % 2),
  subscription: resourceId('artemis.queue', name(f, 'orders', i)),
  destination: resourceId('artemis.address', address(f, i)),
  messageId: num(f, 5_000_000 + i),
  messageType: 4,
  durable: true,
  priority: 4,
  timestamp: Date.parse(at(i)),
  expiration: 0,
  size: num(f, 1_024 + i),
  body: f === 'normal' ? '{"order":1042,"status":"created"}' : `{"order":"${BLOCK.repeat(4)}"}`,
  bodyTruncated: false,
  source: i % 2 ? 'INDEX' : 'LIVE',
  origin: 'SAMPLED',
});

const runRow = (f: Fixture, i: number): BulkRunView => ({
  id: `run-${i + 1}`,
  clusterId: CLUSTER,
  operation: (['PAUSE', 'RESUME', 'PURGE', 'DELETE', 'PURGE', 'PAUSE'] as const)[i],
  status: (['SUCCEEDED', 'RUNNING', 'PARTIAL', 'FAILED', 'STOPPED', 'INTERRUPTED'] as const)[i],
  username: f === 'normal' ? 'a.rahimi' : name(f, 'operator', i),
  createdAt: at(i),
  expiresAt: at(i),
  startedAt: at(i),
  finishedAt: at(i),
  total: num(f, 40 + i),
  succeeded: 0,
  failed: 0,
  skipped: 0,
  estimate: null,
  estimateComplete: true,
  cap: 1_000,
  overCap: false,
  overrideCap: false,
  continueOnFailure: false,
  disconnectConsumers: false,
  selection: {},
  planHash: 'abc123',
  auditEventId: null,
  error: null,
});

const bulkItem = (f: Fixture, i: number): BulkItemView => ({
  ordinal: i,
  subscription: resourceId('artemis.queue', name(f, 'orders', i)),
  status: (['SUCCEEDED', 'FAILED', 'REFUSED', 'PENDING', 'RUNNING', 'SKIPPED'] as const)[i],
  error:
    i === 1 || i === 2
      ? f === 'normal'
        ? 'The queue has consumers'
        : `${BLOCK.repeat(3)}the queue has consumers`
      : null,
  warning: null,
  affected: i === 3 ? null : num(f, 1_200 + i),
  nodes: [
    { nodeId: 'node-1', nodeName: 'broker-1', messageCount: 10, consumerCount: 1, paused: false },
    { nodeId: 'node-2', nodeName: 'broker-2', messageCount: 10, consumerCount: 1, paused: false },
  ],
  outcome: null,
  startedAt: null,
  finishedAt: null,
});

const tableRow = (f: Fixture, i: number): TableView => ({
  schema: i === 0 ? 'public' : f === 'normal' ? 'audit' : name(f, 'schema', i),
  name:
    f === 'normal'
      ? ['audit_event', 'queue_snapshot', 'metric_sample', 'message_index', 'job_run', 'setting'][i]
      : name(f, 'table', i),
  rows: num(f, 120_000 + i),
  deadRows: num(f, 1_200 + i),
  deadPercent: 1,
  lastVacuum: i === 4 ? undefined : at(i),
  bytes: num(f, 58_000_000 + i),
  growthBytes: i === 3 ? undefined : num(f, 1_200_000),
  partitioned: i % 2 === 0,
  missingPartitions: i === 2 ? ['2026_10'] : [],
  problems: i === 1 ? ['autovacuum is behind'] : [],
});

const store = (f: Fixture, i: number): StoreView => ({
  id: `store-${i + 1}`,
  label:
    f === 'normal'
      ? ['Audit log', 'Queue samples', 'Message index', 'Metric samples', 'Broker events', 'Job runs'][i]
      : name(f, 'store', i),
  source: i === 3 ? 'plugin-workflow' : 'core',
  tables: [],
  retention: i === 2 ? 'forever' : `${7 * (i + 1)}d`,
  defaultRetention: '7d',
  minRetention: '1d',
  maxRetention: '90d',
  quotaUnit: i % 2 ? 'BYTES' : 'ROWS',
  quota: i === 5 ? 0 : 512,
  quotaWarnPercent: 80,
  rows: num(f, 120_000 + i),
  bytes: num(f, 58_000_000 + i),
  quotaUsedPercent: 40 + i,
  overWarning: i === 1,
  lastPurgeAt: i === 4 ? undefined : at(i),
  lastPurged: num(f, 3_100 + i),
  lastPurgeError: i === 1 ? 'the purge was interrupted' : undefined,
});

const plugin = (f: Fixture, i: number): PluginView => ({
  id: f === 'normal' ? ['workflow', 'notes', 'billing', 'mailer', 'sso', 'tracing'][i] : name(f, 'plugin', i),
  version: `1.${i}.0`,
  status: (['active', 'failed', 'incompatible', 'active', 'needs_restart', 'disabled'] as const)[i],
  failure: null,
  progress: null,
  stepStartedAt: null,
  installedAt: at(i),
  activatedAt: at(i),
  installedBy: 'a.rahimi',
  sha256: 'a'.repeat(64),
  rollbackAvailable: false,
  stuck: false,
  iconUrl: null,
  dependants: [],
  signerFingerprint: null,
  signerSubject: null,
  verified: i % 2 === 0,
  info: {
    name: `plugin-${i}`,
    title:
      f === 'normal'
        ? ['Workflow', 'Notes', 'Billing', 'Mailer', 'Single sign-on', 'Tracing'][i]
        : name(f, 'plugin', i),
    description: null,
    vendor: { name: f === 'normal' ? 'Acme' : name(f, 'vendor', 0), url: null, email: null },
    license: null,
    changeNotes: null,
    since: '2026.09.0',
    until: null,
    restartToActivate: false,
    updateUrl: null,
    requires: [],
    requiresLicense: false,
    contributions: {
      ui: true,
      permissions: [],
      settingKeys: ['a', 'b'],
      streamTopics: [],
      mcpTools: [],
      identityProviders: [],
      auditReceive: false,
      auditRetentionHold: false,
    },
  },
});

/** The first plugin has a newer version available, whichever fixture names it. */
const updates: ReadonlyMap<string, PluginUpdateView> = new Map(
  (['normal', 'long'] as const).map((f) => {
    const first = plugin(f, 0);
    return [
      first.id,
      { id: first.id, currentVersion: first.version, availableVersion: '1.9.0', changeNotes: null, error: null },
    ];
  }),
);

const flowNode = (f: Fixture, kind: 'SUBSCRIPTION' | 'DESTINATION' | 'PRODUCER', i: number): FlowNodeView => ({
  id: `${kind}:${i}`,
  kind,
  label: kind === 'DESTINATION' ? address(f, i) : name(f, 'orders', i),
  members: kind === 'PRODUCER' ? 3 : null,
});

const flowEdge = (i: number): FlowEdgeView => ({
  id: `edge-${i}`,
  kind: (['PRODUCE', 'ROUTE', 'CONSUME', 'FORWARD', 'LINK', 'DEAD_LETTER'] as const)[i],
  source: 'a',
  target: 'b',
  rate: 12.5 * (i + 1),
  rateSource: i === 4 ? 'NONE' : 'SAMPLER',
  asOf: at(i),
  averagedOverSeconds: null,
  stale: i === 3,
  delivery: 'COPY',
  members: i + 1,
  exclusive: null,
  filter: null,
  transformer: null,
  bypassed: false,
  presentOn: null,
  presentOf: null,
  rahdar: false,
  faults: i === 2 ? ['NO_CONSUMER'] : [],
  byNode: null,
});

const pathRow = (f: Fixture, i: number): PathRow => ({
  id: `path-${i}`,
  edge: flowEdge(i),
  from: flowNode(f, 'PRODUCER', i),
  to: flowNode(f, 'SUBSCRIPTION', i),
  faults: i === 2 ? ['NO_CONSUMER'] : [],
});

const nodeRow = (f: Fixture, i: number): NodeRow => ({
  nodeId: `node-${i + 1}`,
  node: f === 'normal' ? `broker-${i + 1}` : name(f, 'broker', i),
  messageCount: num(f, 4_200 + i),
  consumerCount: num(f, 3 + i),
  inRate: 12.5 + i,
  outRate: 11.9 + i,
  stale: i === 2,
});

// ── The views ──────────────────────────────────────────────────────────────────────────────────

const deps = { clusterId: CLUSTER, fetchedAt: null };
const clusterName = (id: string) => `Cluster ${id}`;

export const VIEWS: TableView_[] = [
  view({
    name: 'queues',
    label: 'Queues',
    columns: queueColumns(),
    rows: (f) => ROWS.map((i) => queue(f, i)),
    rowKey: (r) => r.name,
    selectable: true,
    menu: true,
    identifiers: ['address', 'queueName'],
    visible1280: [
      'address',
      'queueName',
      'owner',
      'routingType',
      'depth',
      'consumers',
      'delivering',
      'scheduled',
      'durable',
      'paused',
      'nodes',
    ],
  }),
  view({
    name: 'addresses',
    label: 'Address',
    columns: resourceColumns.addresses(deps),
    rows: (f) => ROWS.map((i) => address_(f, i)),
    rowKey: (r) => `${r.nodeId}/${r.name}`,
    menu: true,
    identifiers: ['name'],
    visible1280: ['name', 'owner', 'delivery', 'queues', 'depth', 'node', 'action'],
  }),
  view({
    name: 'consumers',
    label: 'Consumers',
    columns: resourceColumns.consumers(deps),
    rows: (f) => ROWS.map((i) => consumer(f, i)),
    rowKey: (r) => `${r.nodeId}/${r.id}`,
    menu: true,
    identifiers: ['queue', 'address'],
    visible1280: ['queue', 'address', 'protocol', 'delivered', 'acked', 'inFlight', 'node', 'action'],
  }),
  view({
    name: 'sessions',
    label: 'Sessions',
    columns: resourceColumns.sessions(deps),
    rows: (f) => ROWS.map((i) => session(f, i)),
    rowKey: (r) => `${r.nodeId}/${r.id}`,
    menu: true,
    identifiers: ['session', 'conn'],
    visible1280: ['session', 'user', 'conn', 'consumers', 'producers', 'node', 'action'],
  }),
  view({
    name: 'connections',
    label: 'Connections',
    columns: resourceColumns.connections(deps),
    rows: (f) => ROWS.map((i) => connection(f, i)),
    rowKey: (r) => `${r.nodeId}/${r.id}`,
    menu: true,
    identifiers: ['remote'],
    visible1280: ['remote', 'protocol', 'client', 'sessions', 'node', 'action'],
  }),
  view({
    name: 'producers',
    label: 'Producers',
    columns: resourceColumns.producers(),
    rows: (f) => ROWS.map((i) => producer(f, i)),
    rowKey: (r) => `${r.nodeId}/${r.id}`,
    menu: true,
    identifiers: ['address'],
    visible1280: ['address', 'client', 'protocol', 'sent', 'node'],
  }),
  view({
    name: 'diverts',
    label: 'Diverts',
    columns: divertColumns(CLUSTER),
    rows: (f) => ROWS.map((i) => divert(f, i)),
    rowKey: (r) => r.name,
    menu: true,
    identifiers: ['name'],
    visible1280: ['name', 'direction', 'exclusive', 'filter', 'owner', 'nodes', 'action'],
  }),
  view({
    name: 'bridges',
    label: 'Bridges',
    columns: bridgeColumns(),
    rows: (f) => ROWS.map((i) => bridge(f, i)),
    rowKey: (r) => r.name,
    identifiers: ['name'],
    visible1280: ['name', 'direction', 'state', 'pending', 'acked', 'nodes'],
  }),
  view({
    name: 'messages',
    label: 'Messages',
    columns: messageColumns(AUTO),
    rows: (f) => ROWS.map((i) => message(f, i)),
    rowKey: (r) => r.position,
    selectable: true,
    menu: true,
    identifiers: [],
    visible1280: ['position', 'timestamp', 'priority', 'durable', 'size', 'props', 'body'],
  }),
  view({
    name: 'dead-letter queues',
    label: 'Dead-letter queues',
    columns: dlqColumns(CLUSTER),
    rows: (f) => ROWS.map((i) => dlqRow(f, i)),
    rowKey: (r) => r.queue.name,
    menu: true,
    identifiers: ['queue', 'address'],
    visible1280: ['queue', 'address', 'kind', 'depth', 'nodes'],
  }),
  view({
    name: 'events',
    label: 'Events',
    columns: eventColumns(AUTO),
    rows: (f) => ROWS.map((i) => brokerEvent(f, i)),
    rowKey: (r) => String(r.seq),
    identifiers: ['address', 'subject'],
    visible1280: ['time', 'type', 'family', 'address', 'subject', 'remote'],
  }),
  view({
    name: 'audit',
    label: 'Audit',
    columns: auditColumns(AUTO),
    rows: (f) => ROWS.map((i) => auditEvent(f, i)),
    rowKey: (r) => String(r.id),
    identifiers: ['target'],
    visible1280: ['time', 'user', 'action', 'target', 'count', 'outcome'],
  }),
  view({
    name: 'transfers',
    label: 'Transfers',
    columns: transferColumns({ clusterId: CLUSTER, clusterName, zone: AUTO }),
    rows: (f) => ROWS.map((i) => transfer(f, i)),
    rowKey: (r) => r.id,
    identifiers: ['from', 'to'],
    visible1280: ['when', 'mode', 'from', 'to', 'delivered', 'state', 'user'],
  }),
  view({
    name: 'consumer health',
    label: 'Consumer health',
    columns: consumerHealthColumns(),
    rows: (f) => ROWS.map((i) => health(f, i)),
    rowKey: (r) => r.subscription,
    identifiers: ['subscription', 'destination'],
    visible1280: ['verdict', 'subscription', 'destination', 'depth', 'consumers', 'delivering', 'ackRate', 'trend'],
  }),
  view({
    name: 'sql results',
    label: 'Results',
    columns: resultColumns(CLUSTER, AUTO),
    rows: (f) => ROWS.map((i) => sqlRow(f, i)),
    rowKey: (r) => `${r.nodeId}/${r.messageId}`,
    identifiers: ['queue'],
    visible1280: ['messageId', 'queue', 'node', 'source', 'timestamp', 'body', 'priority', 'size'],
  }),
  view({
    name: 'bulk runs',
    label: 'Runs',
    columns: runColumns({ clusterId: CLUSTER, zone: AUTO }),
    rows: (f) => ROWS.map((i) => runRow(f, i)),
    rowKey: (r) => r.id,
    identifiers: [],
    visible1280: ['when', 'operation', 'queues', 'user', 'outcome'],
  }),
  view({
    name: 'bulk run queues',
    label: 'Queues in this run',
    columns: itemColumns(true, () => {}),
    rows: (f) => ROWS.map((i) => bulkItem(f, i)),
    rowKey: (r) => r.subscription,
    height: { maxRows: 12 },
    identifiers: [],
    visible1280: ['queue', 'status', 'affected', 'note'],
  }),
  view({
    name: 'bulk preview',
    label: 'Queues in this plan',
    columns: previewColumns(true),
    rows: (f) => ROWS.map((i) => bulkItem(f, i)),
    rowKey: (r) => r.subscription,
    height: { maxRows: 8 },
    identifiers: ['queue'],
    visible1280: ['queue', 'messages', 'nodes', 'plan', 'note'],
  }),
  view({
    name: 'storage health',
    label: 'Storage health',
    columns: healthColumns(AUTO),
    rows: (f) => ROWS.map((i) => tableRow(f, i)),
    rowKey: (r) => `${r.schema}.${r.name}`,
    identifiers: ['table'],
    visible1280: ['table', 'state', 'size', 'growth', 'dead', 'vacuum', 'partitions'],
  }),
  view({
    name: 'retention',
    label: 'Retention',
    columns: storeColumns(AUTO),
    rows: (f) => ROWS.map((i) => store(f, i)),
    rowKey: (r) => r.id,
    identifiers: [],
    visible1280: ['store', 'retention', 'rows', 'size', 'quota', 'purge'],
  }),
  view({
    name: 'plugins',
    label: 'Plugins',
    columns: pluginColumns({ updates, onUpdate: () => {}, onOpen: () => {}, showLicense: false }),
    rows: (f) => ROWS.map((i) => plugin(f, i)),
    rowKey: (r) => r.id,
    identifiers: [],
    visible1280: ['plugin', 'version', 'status', 'adds', 'fix'],
  }),
  view({
    name: 'flow paths',
    label: 'Paths',
    columns: pathColumns(Date.parse('2026-09-30T14:10:00Z')),
    rows: (f) => ROWS.map((i) => pathRow(f, i)),
    rowKey: (r) => r.id,
    identifiers: ['from', 'to'],
    visible1280: ['from', 'relation', 'to', 'rate', 'source', 'clients', 'faults'],
  }),
  view({
    name: 'topology',
    label: 'Nodes',
    columns: topologyColumns(Date.parse('2026-09-30T14:10:00Z')),
    rows: (f) => ROWS.map((i) => topologyNode(f, i)),
    rowKey: (r) => r.id,
    identifiers: ['node', 'nodeId'],
    visible1280: ['node', 'role', 'pair', 'liveness', 'version', 'address', 'seen', 'nodeId'],
  }),
  ...(
    [
      ['resource', ['node', 'backlog', 'consumers', 'in', 'out']],
      ['producer', ['node', 'in']],
      ['consumer', ['node', 'out']],
    ] as const
  ).map(([shape, visible1280]) =>
    view({
      name: `flow nodes (${shape})`,
      label: 'Nodes',
      columns: nodeColumns(shape),
      rows: (f) => ROWS.map((i) => nodeRow(f, i)),
      rowKey: (r) => r.nodeId,
      height: { maxRows: 8 },
      identifiers: ['node'],
      visible1280: [...visible1280],
    }),
  ),
];
