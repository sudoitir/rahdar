/**
 * Every route the screenshot sweep visits, and what each one's data looks like when it is empty.
 *
 * A route lists the API calls that fill it (`data`). The sweep makes the loading, error and empty
 * states by answering exactly those calls: never answering, a 503 problem document, or `empty`.
 * `empty` is the endpoint's own empty shape, never an empty body: a view that gets `{}` where it
 * expects `{ data: [] }` crashes, and a crash is not an empty state.
 *
 * Paths are relative to `/api/v1`. `:cluster` is the seeded cluster's id; `*` is one path segment.
 * The shell's own calls (who is signed in, the manifest, the event stream) are never answered by the
 * sweep: a route that names one is refused when the sweep starts.
 *
 * `scripts/qa-seed.sh` creates what the cluster routes need.
 */
import type { Page } from '@playwright/test';

import {
  BELL_SCENES,
  CONNECTION_SCENES,
  HELD_QUEUE,
  HELD_TOAST_SCENES,
  REGISTER_AGAIN_SCENES,
  REGISTER_SCENES,
  SETTINGS_DRAFT_SCENES,
  TEAMS_SCENES,
} from './scenes.ts';

export interface DataCall {
  path: string;
  /** What the call answers when the thing it lists is empty; absent where there is no empty shape. */
  empty?: unknown;
}

/**
 * A state reached by acting on the page once it has settled: a connection check run, a dialog opened, a
 * field changed. It is captured in every width and scheme, signed in as the route's account, with every check
 * of a plain capture; layout shift counts from navigation, so an action that moves the page is a finding.
 */
export interface Scene {
  /** The capture's file name and its `--states` value: lower-case words joined by hyphens. */
  id: string;
  act: (page: Page) => Promise<void>;
  /** Statuses the action provokes on purpose, such as a check that a broker refuses. */
  expectedStatus?: number[];
  /** Who acts, when not the route's account. */
  auth?: 'admin' | 'requester';
}

export interface RouteSpec {
  /** The feature or screen the route belongs to, for `--only`. */
  area: string;
  /** The directory the captures go in. */
  id: string;
  /** The address, with `:cluster` for the seeded cluster's id. */
  path: string;
  /**
   * Who is signed in: the administrator unless named. `none` for the login screen, which is only shown to a
   * signed-out browser; `requester` for the held-request screens, as `sweep/approval-provider/install.sh` sets up.
   */
  auth?: 'admin' | 'requester' | 'none';
  /** The calls that fill the route. Without any, the loading, error and empty states are skipped. */
  data?: DataCall[];
  /** A query string that matches nothing the seed made, for the filtered-empty state. */
  filter?: string;
  /** Also capture it as the read-only account, for the permission-denied state. */
  forbidden?: boolean;
  /**
   * Statuses the route answers on purpose: a signed-out page probes the session and is refused, a
   * stale link asks for a run nothing has. The browser logs them, and they are not findings.
   */
  expectedStatus?: number[];
  /** The states reached by acting on the page. */
  scenes?: Scene[];
}

/** Every list answers in this envelope (ADR-0149). */
const page = { data: [], page: 1, pageSize: 50, count: 0, hasNext: false };
const paged = (path: string): DataCall => ({ path, empty: page });

/** The 200-character address qa-seed.sh creates a queue on. */
export const LONG_ADDRESS = `qa.long-address.${Array.from({ length: 16 }, (_, i) => `segment-${String(i + 1).padStart(2, '0')}.`).join('')}terminal`;

const NO_MATCH = 'zz-nothing-matches-this';
const cluster = (rest: string) => `/clusters/:cluster/${rest}`;
const stateless = (area: string, id: string, path: string, extra: Partial<RouteSpec> = {}): RouteSpec => ({
  area,
  id,
  path,
  ...extra,
});

/** A cluster's resource listing: one paged call, filtered by `q`. `api` is the endpoint when it is not the screen's own word. */
const listing = (area: string, id: string, resource: string, api: string = resource): RouteSpec => ({
  area,
  id,
  path: cluster(resource),
  data: [paged(cluster(api))],
  filter: `?q=${NO_MATCH}`,
});

const ADMIN_TABS: { tab: string; data: DataCall[] }[] = [
  { tab: 'users', data: [paged('/users'), paged('/roles')] },
  { tab: 'roles', data: [paged('/roles'), paged('/permissions')] },
  { tab: 'api-keys', data: [paged('/admin/tokens')] },
  { tab: 'environments', data: [paged('/environments')] },
  { tab: 'group-mappings', data: [paged('/identity/providers/*/group-mappings')] },
  { tab: 'data', data: [{ path: '/data/stores', empty: { stores: [] } }, { path: '/data/health' }] },
  { tab: 'governance-rules', data: [paged('/governance/rules')] },
  { tab: 'governance-findings', data: [paged('/governance/findings')] },
  { tab: 'plugins', data: [{ path: '/admin/plugins' }] },
  { tab: 'encryption-keys', data: [{ path: '/settings' }] },
  { tab: 'diagnostics', data: [{ path: '/diagnostics/summary' }] },
  { tab: 'rahdar-health', data: [{ path: '/settings' }] },
];

/** The settings page's tabs: its sections (`settings.sections` contributions) and one per settings category. */
const SETTINGS_TABS = [
  'clusters-register',
  'clusters-connection',
  'clusters-capabilities',
  'clusters-remove',
  'sql-index',
  'alerting-channels',
  ...['alerting', 'apitokens', 'approvals', 'brokerconfig', 'broker', 'events', 'governance', 'lifecycle'],
  ...['stream', 'flow', 'inbox', 'mcp', 'transfer', 'identity-local', 'rr', 'scrape', 'security', 'setupreview'],
];

/** One held request's page in each state the requester's requests reached, as `approval-provider/install.sh` leaves them. */
const HELD_STATES = ['HELD', 'SUCCEEDED', 'REJECTED', 'CANCELLED', 'EXPIRED', 'REFUSED'];
const heldList = (path: string): DataCall => ({ path, empty: { items: [], next: null } });

export const ROUTES: RouteSpec[] = [
  // The shell
  {
    area: 'shell',
    id: 'home',
    path: '/',
    data: [paged('/clusters'), paged('/subscriptions')],
    filter: `?q=${NO_MATCH}`,
  },
  stateless('shell', 'login', '/login', { auth: 'none', expectedStatus: [401] }),
  {
    area: 'shell',
    id: 'account',
    path: '/account',
    data: [paged('/auth/sessions'), paged('/tokens'), { path: '/auth/mfa' }],
  },
  stateless('identity-local', 'change-password', '/change-password'),
  stateless('identity-local', 'enrol-second-factor', '/enrol-second-factor'),
  stateless('plugins', 'plugin-unavailable-root', '/p/qa-missing/anything'),
  stateless('plugins', 'plugin-unavailable-cluster', cluster('p/qa-missing/anything')),
  ...ADMIN_TABS.map(({ tab, data }): RouteSpec => ({
    area: 'admin',
    id: `admin-${tab}`,
    path: `/admin?tab=${tab}`,
    data,
    forbidden: true,
  })),
  {
    area: 'admin',
    id: 'admin-teams',
    path: '/admin?tab=teams',
    data: [paged('/teams')],
    filter: `&teamQ=${NO_MATCH}`,
    forbidden: true,
    scenes: TEAMS_SCENES,
  },

  // Registering a cluster. `register` needs a stack with no cluster, so that the check can succeed:
  // `just qa-up`'s seed registers one, which has to be removed first.
  { area: 'onboarding', id: 'register', path: '/', scenes: REGISTER_SCENES },
  {
    area: 'onboarding',
    id: 'register-again',
    path: cluster('settings?tab=clusters-register'),
    scenes: REGISTER_AGAIN_SCENES,
  },

  // Cluster views
  {
    area: 'clusters',
    id: 'topology',
    path: cluster('topology'),
    data: [{ path: cluster('topology'), empty: { clusterId: ':cluster', nodes: [] } }, { path: cluster('health') }],
  },
  listing('subscriptions', 'subscriptions', 'subscriptions'),
  listing('triage', 'consumer-health', 'consumer-health'),
  {
    area: 'messages',
    id: 'messages-dlq-queue',
    path: cluster('subscriptions/DLQ/messages'),
    data: [
      {
        path: cluster('resources/artemis.queue/*/messages'),
        empty: { ...page, node: 'primary', transport: 'jolokia' },
      },
    ],
  },
  {
    area: 'messages',
    id: 'messages-long-name',
    path: cluster(`subscriptions/${LONG_ADDRESS}/messages`),
    data: [
      {
        path: cluster('resources/artemis.queue/*/messages'),
        empty: { ...page, node: 'primary', transport: 'jolokia' },
      },
    ],
  },
  {
    area: 'messages',
    id: 'dead-letters',
    path: cluster('dead-letters'),
    data: [{ path: cluster('dead-letters'), empty: { destinations: [], settingsAvailable: true } }],
  },
  {
    area: 'metrics',
    id: 'metrics',
    path: cluster('metrics'),
    data: [
      {
        path: cluster('metrics'),
        empty: {
          from: '2026-01-01T00:00:00Z',
          to: '2026-01-01T01:00:00Z',
          step: '15s',
          truncated: false,
          series: [],
        },
      },
    ],
  },
  ...(['firing', 'history', 'rules'] as const).map((tab): RouteSpec => ({
    area: 'alerting',
    id: `alerts-${tab}`,
    path: cluster(`alerts?tab=${tab}`),
    data: [paged(cluster(`alerts/${tab}`)), ...(tab === 'rules' ? [paged('/channels')] : [])],
  })),
  {
    area: 'flow',
    id: 'flow',
    path: cluster('flow'),
    data: [
      {
        path: cluster('flow'),
        empty: { nodes: [], edges: [], measuring: false, sampleIntervalSeconds: 15, layers: [], assumptions: [] },
      },
    ],
  },
  stateless('flow', 'flow-table', cluster('flow?tab=table')),
  listing('triage', 'consumer-health', 'consumer-health'),
  ...(['flows', 'stuck', 'latency', 'expectations'] as const).map((tab): RouteSpec => ({
    area: 'rr',
    id: `rr-${tab}`,
    path: cluster(`rr?tab=${tab}`),
    data: [
      paged(cluster('rr/flows')),
      paged(cluster('rr/expectations')),
      { path: cluster('rr/stats'), empty: { addresses: [] } },
    ],
  })),
  { area: 'bulk', id: 'bulk', path: cluster('bulk'), data: [paged(cluster('bulk/runs'))] },
  // A run id nothing has: what a stale link shows.
  {
    area: 'bulk',
    id: 'bulk-run-unknown',
    path: cluster('bulk/00000000-0000-0000-0000-000000000000'),
    data: [{ path: cluster('bulk/runs/*') }],
    expectedStatus: [404],
  },
  { area: 'transfer', id: 'transfers', path: cluster('transfers'), data: [paged(cluster('transfers/runs'))] },
  {
    area: 'transfer',
    id: 'transfer-run-unknown',
    path: cluster('transfers/00000000-0000-0000-0000-000000000000'),
    data: [{ path: cluster('transfers/runs/*') }],
    expectedStatus: [404],
  },
  { area: 'sql', id: 'sql', path: cluster('sql'), data: [paged(cluster('sql/index'))] },
  listing('resources', 'destinations', 'destinations'),
  listing('resources', 'consumers', 'consumers'),
  listing('resources', 'channels', 'channels'),
  listing('resources', 'connections', 'connections'),
  listing('resources', 'producers', 'producers'),
  {
    area: 'routes',
    id: 'routes',
    path: cluster('routes'),
    data: [paged(cluster('routes')), { path: cluster('artemis/config') }],
  },
  {
    area: 'setupreview',
    id: 'setup-review',
    path: cluster('artemis/setup-review'),
    data: [{ path: cluster('artemis/setup-review') }],
  },
  ...(
    [
      'addresses',
      'addressSettings',
      'securitySettings',
      'diverts',
      'bridges',
      'nodes',
      'history',
      'recommended',
    ] as const
  ).map((tab): RouteSpec => ({
    area: 'brokerconfig',
    id: `configuration-${tab}`,
    path: cluster(`artemis/configuration?tab=${tab}`),
    data: [{ path: cluster('artemis/config') }, paged(cluster('artemis/config/revisions'))],
  })),
  {
    area: 'brokerconfig',
    id: 'config-diff',
    path: cluster('artemis/config-diff'),
    data: [{ path: cluster('artemis/config-diff') }],
  },
  ...SETTINGS_TABS.map((tab): RouteSpec => ({
    area: 'settings',
    id: `settings-${tab.replace(/^settings-/, '')}`,
    path: cluster(`settings?tab=${tab}`),
    data: [{ path: '/settings' }],
    forbidden: tab === 'approvals',
    scenes: { 'clusters-connection': CONNECTION_SCENES, approvals: SETTINGS_DRAFT_SCENES }[tab],
  })),
  {
    area: 'settings',
    id: 'settings-search',
    path: cluster('settings?q=timeout'),
    data: [{ path: '/settings' }],
  },
  { area: 'settings', id: 'settings-search-none', path: cluster(`settings?q=${NO_MATCH}`) },
  { area: 'settings', id: 'settings-modified', path: cluster('settings?modified=true'), data: [{ path: '/settings' }] },
  { area: 'settings', id: 'settings-pending', path: cluster('settings?tab=approvals'), auth: 'requester' },

  // Approval requests, the inbox and the bell
  {
    area: 'approvals',
    id: 'approvals',
    path: '/approvals',
    data: [heldList('/held-operations')],
    scenes: BELL_SCENES,
  },
  {
    area: 'approvals',
    id: 'approvals-mine',
    path: '/approvals?tab=mine',
    auth: 'requester',
    data: [heldList('/held-operations')],
  },
  ...HELD_STATES.flatMap((state): RouteSpec[] =>
    (['admin', 'requester'] as const).map((auth) => ({
      area: 'approvals',
      id: `approval-${state.toLowerCase()}-${auth}`,
      path: `/approvals/:held:${state}`,
      auth,
      data: [{ path: '/held-operations/*' }],
    })),
  ),
  stateless('approvals', 'approval-missing', '/approvals/00000000-0000-4000-8000-000000000000', {
    expectedStatus: [404],
  }),
  {
    area: 'approvals',
    id: 'inbox',
    path: '/inbox',
    data: [{ path: '/inbox', empty: { items: [], next: null } }],
    filter: '?filter=unread',
  },
  {
    area: 'approvals',
    id: 'held-toast',
    path: cluster(`subscriptions/${HELD_QUEUE}/messages`),
    auth: 'requester',
    scenes: HELD_TOAST_SCENES,
  },
  { area: 'approvals', id: 'inbox-requester', path: '/inbox', auth: 'requester', scenes: BELL_SCENES },
  // Run with `rahdar.gate.break-glass` set in Rahdar's environment: every page shows the banner.
  { area: 'breakglass', id: 'break-glass-admin', path: cluster('subscriptions') },
  { area: 'breakglass', id: 'break-glass-requester', path: '/approvals?tab=mine', auth: 'requester' },
  {
    area: 'approvals',
    id: 'account-requests',
    path: '/account',
    auth: 'requester',
    data: [heldList('/held-operations')],
  },
  {
    area: 'events',
    id: 'events',
    path: cluster('events'),
    data: [{ path: cluster('events'), empty: { ...page, count: 0, dropped: 0 } }],
    filter: `?address=${NO_MATCH}`,
  },
  {
    area: 'audit',
    id: 'audit',
    path: cluster('audit'),
    data: [paged(cluster('audit'))],
    filter: `?user=${NO_MATCH}`,
    forbidden: true,
  },
];
