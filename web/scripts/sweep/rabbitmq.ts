/**
 * The RabbitMQ cluster's routes and the states of registering one, for the screenshot sweep (task S2 of 19f-rabbitmq).
 *
 * They run against the RabbitMQ cluster "rabbit" that `scripts/qa-seed.sh` (or demo-seed.sh) registers and seeds
 * with `scripts/lib/rabbitmq-seed.sh`: virtual hosts `/` and `payments`; exchanges orders (topic), audit (fanout),
 * orders.unrouted-test (nothing bound) and payments; the classic queue orders.eu, the quorum queue orders.us (a
 * backlog, no consumer), the stream audit-log (200 records) and a quorum queue with a 200-character name; a policy,
 * a read-only permission and a dynamic shovel. `:rabbit` is that cluster's id.
 *
 * Most states are real. A few a live stack cannot hold, a node with an alarm or a partition, a cluster without the
 * management or the shovel plugin, are the API's own answer put in place of the live one with `page.route`, and the
 * capture says so in its id (`-stub`): the page is the real one, the answer is not.
 */
import type { Page } from '@playwright/test';

import { clickShown, dialogOf, exact, held, holdConfirm, NO_MATCH, press, settle, stubCall, stubGet } from './kafka.ts';
import type { RouteSpec, Scene } from './routes.ts';

const rabbit = (rest: string) => `/clusters/:rabbit/${rest}`;
const emptyPage = { items: [], next: null };

/** A queue or exchange as the shared screens address it: kind, virtual host (`/` is `%2F`) and name. */
const resource = (kind: string, vhost: string, name: string) =>
  encodeURIComponent(`rabbitmq.${kind}/${encodeURIComponent(vhost)}/${name}`);
const LONG_QUEUE = `qa.${Array.from({ length: 11 }, (_, i) => `queue-segment-${String(i + 1).padStart(2, '0')}.`).join('')}terminal-q`;

// ── Registration ──────────────────────────────────────────────────────────────────────────────────────────────────

/** A connection check as the API answers one, for the outcomes a live stack cannot produce. */
function checkOf(over: Record<string, unknown> = {}) {
  return {
    system: 'rabbitmq',
    version: { text: '4.4.0', mode: null, exact: true },
    supported: true,
    clusterId: 'rabbit@rabbitmq',
    alreadyRegistered: null,
    nodes: [{ id: 'rabbit@rabbitmq', address: 'http://rabbitmq:15672', functions: ['BROKER'], outcome: 'OK' }],
    quorum: null,
    access: [
      { capability: 'Read the cluster', status: 'AVAILABLE', reason: 'The account holds the monitoring tag.' },
      { capability: 'Change the cluster', status: 'AVAILABLE', reason: 'The account holds the administrator tag.' },
    ],
    failure: null,
    contributions: {},
    endpoints: [{ plane: 'AMQP', address: 'amqp://rabbitmq:5672', outcome: 'OK' }],
    ...over,
  };
}

const failure = (cause: string, message: string, next: string) =>
  checkOf({ nodes: [], version: null, supported: false, failure: { cause, message, next }, endpoints: [] });

/** Answers the next dry run of a registration with `check`. */
async function stubCheck(page: Page, check: unknown) {
  await page.route(
    (url) => url.pathname === '/api/v1/clusters' && url.searchParams.get('dryRun') === 'true',
    (route) => route.fulfill({ status: 200, contentType: 'application/json', json: check }),
  );
}

async function fillConnection(page: Page, management: string, user = 'rahdar', password = 'rahdar') {
  await exact(page, 'Management URLs').fill(management);
  await exact(page, 'AMQP endpoints').fill('amqp://rabbitmq:5672');
  await exact(page, 'User name').fill(user);
  await exact(page, 'Password').fill(password);
  await exact(page, 'Password').blur();
}

async function runCheck(page: Page) {
  await press(page, 'Check connection');
  await page.waitForTimeout(1500);
}

/** A real check: the form filled as given and checked against the stack's own node. */
const live = (id: string, management: string, user?: string, password?: string): Scene => ({
  id,
  act: async (page) => {
    await fillConnection(page, management, user, password);
    await runCheck(page);
  },
});

/** A stubbed outcome: the form filled with the stack's node, the check answered with `check`. */
const stubbed = (id: string, check: unknown): Scene => ({
  id,
  act: async (page) => {
    await stubCheck(page, check);
    await fillConnection(page, 'http://rabbitmq:15672');
    await runCheck(page);
  },
});

const REGISTER_RABBIT_SCENES: Scene[] = [
  {
    id: 'urls-as-entries',
    act: async (page) => {
      await exact(page, 'Management URLs').fill('http://rabbitmq:15672, https://rabbitmq-2 rabbitmq-3:15673');
      await exact(page, 'AMQP endpoints').fill('amqp://rabbitmq:5672 amqps://rabbitmq-2');
      await exact(page, 'AMQP endpoints').blur();
    },
  },
  {
    id: 'plaintext-warning',
    act: async (page) => {
      await exact(page, 'Management URLs').fill('http://rabbitmq:15672');
      await exact(page, 'Management URLs').blur();
    },
  },
  {
    id: 'token-credentials',
    act: async (page) => {
      await page.getByRole('combobox', { name: 'Credentials' }).click();
      await page.getByRole('option').last().click();
    },
  },
  {
    id: 'invalid-url',
    act: async (page) => {
      await exact(page, 'Management URLs').fill('rabbitmq.example.com:15672/api ftp://x');
      await press(page, 'Check connection');
    },
  },
  // Real: the node is on this stack, and "rabbit" is registered already.
  live('already-registered', 'http://rabbitmq:15672'),
  live('wrong-endpoint', 'http://rabbitmq:9'),
  live('no-such-host', 'http://no-such-host.invalid:15672'),
  live('auth-refused', 'http://rabbitmq:15672', 'rahdar', 'not-the-password'),
  live('partly-reached', 'http://rabbitmq:15672 http://no-such-host.invalid:15672'),
  stubbed('checked-stub', checkOf({ clusterId: 'rabbit@elsewhere' })),
  stubbed(
    'management-plugin-missing-stub',
    failure(
      'NOT_RABBITMQ',
      'The address answered, but not as the RabbitMQ management API: /api/overview was not found.',
      'Enable the rabbitmq_management plugin on every node (rabbitmq-plugins enable rabbitmq_management), or give the address of its HTTP listener.',
    ),
  ),
  stubbed(
    'shovel-plugins-missing-stub',
    checkOf({
      clusterId: 'rabbit@elsewhere',
      access: [
        { capability: 'Read the cluster', status: 'AVAILABLE', reason: 'The account holds the monitoring tag.' },
        {
          capability: 'Shovels',
          status: 'UNAVAILABLE',
          reason: 'The rabbitmq_shovel_management plugin is not enabled.',
          remedy: {
            text: 'Enable the plugin on every node',
            snippet: 'rabbitmq-plugins enable rabbitmq_shovel rabbitmq_shovel_management',
            mediaType: 'text/x-shellscript',
          },
        },
      ],
    }),
  ),
];

// ── Changes held for approval ─────────────────────────────────────────────────────────────────────────────────────

/** Deletes the virtual host "payments" through its real preview; the real call answers as a held request. */
async function deleteVhost(page: Page, confirm: boolean) {
  const answer = held('Delete virtual host "payments" on rabbit');
  if (confirm) {
    await stubCall(page, 'DELETE', /\/rabbitmq\/vhosts\/payments$/, answer.status, answer.json, answer.headers);
  }
  await clickShown(page, 'Delete virtual host payments');
  await dialogOf(page).waitFor();
  await page.waitForTimeout(1200);
  if (confirm) await holdConfirm(page, 'Delete virtual host with 1 queue');
}

async function openPolicyPreview(page: Page) {
  await clickShown(page, 'Edit policy orders-eu-limits');
  await dialogOf(page).waitFor();
  await press(page, 'Preview the policy');
  await dialogOf(page).last().waitFor();
  await page.waitForTimeout(1200);
}

// ── The cluster's own screens ─────────────────────────────────────────────────────────────────────────────────────

const node = (over: Record<string, unknown> = {}) => ({
  name: 'rabbit@rabbitmq',
  version: '4.4.0',
  running: true,
  alarms: [],
  partitions: [],
  listeners: [{ protocol: 'amqp', port: 5672 }],
  ...over,
});
const nodes = (...list: Record<string, unknown>[]) => ({ clusterName: 'rabbit@rabbitmq', nodes: list });

const RABBIT_OWN: RouteSpec[] = [
  {
    area: 'rabbitmq',
    id: 'nodes',
    path: rabbit('rabbitmq/nodes'),
    data: [{ path: rabbit('rabbitmq/nodes'), empty: nodes() }],
    forbidden: true,
    scenes: [
      {
        id: 'alarm-stub',
        act: (page) =>
          stubGet(
            page,
            /\/api\/v1\/clusters\/[^/]+\/rabbitmq\/nodes$/,
            nodes(node({ alarms: ['memory'] }), node({ name: 'rabbit@rabbitmq-2' })),
          ),
      },
      {
        id: 'partition-stub',
        act: (page) =>
          stubGet(
            page,
            /\/api\/v1\/clusters\/[^/]+\/rabbitmq\/nodes$/,
            nodes(
              node({ partitions: ['rabbit@rabbitmq-3'] }),
              node({ name: 'rabbit@rabbitmq-2', partitions: ['rabbit@rabbitmq-3'] }),
              node({ name: 'rabbit@rabbitmq-3', running: false, alarms: ['disk'] }),
            ),
          ),
      },
    ],
  },
  {
    area: 'rabbitmq',
    id: 'vhosts',
    path: rabbit('rabbitmq/vhosts'),
    data: [{ path: rabbit('rabbitmq/vhosts'), empty: { items: [], countsComplete: true, next: null } }],
    forbidden: true,
    scenes: [
      { id: 'create-dialog', act: (page) => press(page, 'Create a virtual host') },
      { id: 'delete-preview', act: (page) => deleteVhost(page, false) },
      { id: 'delete-held-stub', act: (page) => deleteVhost(page, true) },
      {
        id: 'counts-incomplete-stub',
        act: (page) =>
          stubGet(page, /\/api\/v1\/clusters\/[^/]+\/rabbitmq\/vhosts$/, {
            items: [{ name: '/', description: 'Default virtual host', tags: [], queues: 5, messages: 360 }],
            countsComplete: false,
            next: null,
          }),
      },
    ],
  },
  {
    area: 'rabbitmq',
    id: 'bindings',
    path: rabbit('rabbitmq/bindings'),
    data: [{ path: rabbit('rabbitmq/bindings'), empty: emptyPage }],
    filter: '?source=' + NO_MATCH,
    forbidden: true,
    scenes: [{ id: 'create-dialog', act: (page) => press(page, 'Create a binding') }],
  },
  { area: 'rabbitmq', id: 'bindings-of-exchange', path: rabbit('rabbitmq/bindings?source=orders') },
  {
    area: 'rabbitmq',
    id: 'policies',
    path: rabbit('rabbitmq/policies'),
    data: [{ path: rabbit('rabbitmq/policies'), empty: emptyPage }],
    filter: '?vhost=' + NO_MATCH,
    // The broker answers 404 for a virtual host it does not know; the page shows that as an empty list.
    expectedStatus: [404],
    forbidden: true,
    scenes: [
      { id: 'set-dialog', act: (page) => press(page, 'Set a policy') },
      { id: 'preview', act: openPolicyPreview },
      {
        id: 'clear-preview',
        act: async (page) => {
          await clickShown(page, 'Clear policy orders-eu-limits');
          await dialogOf(page).waitFor();
          await page.waitForTimeout(1200);
        },
      },
    ],
  },
  {
    area: 'rabbitmq',
    id: 'permissions',
    path: rabbit('rabbitmq/permissions'),
    data: [{ path: rabbit('rabbitmq/permissions'), empty: emptyPage }],
    filter: '?user=' + NO_MATCH,
    forbidden: true,
  },
  {
    area: 'rabbitmq',
    id: 'links',
    path: rabbit('rabbitmq/links'),
    data: [
      { path: rabbit('rabbitmq/shovels'), empty: { available: true, remedy: null, ...emptyPage } },
      { path: rabbit('rabbitmq/federation-links'), empty: { available: true, remedy: null, ...emptyPage } },
    ],
    filter: '?vhost=' + NO_MATCH,
    forbidden: true,
    scenes: [
      {
        id: 'plugins-missing-stub',
        act: async (page) => {
          const missing = (what: string) => ({
            available: false,
            remedy: `Enable the ${what} plugin on every node: rabbitmq-plugins enable ${what}`,
            items: [],
            next: null,
          });
          await page.route(
            (url) => /\/api\/v1\/.*\/rabbitmq\/shovels$/.test(url.pathname),
            (route) => route.fulfill({ status: 200, json: missing('rabbitmq_shovel_management') }),
          );
          await page.route(
            (url) => /\/api\/v1\/.*\/rabbitmq\/federation-links$/.test(url.pathname),
            (route) => route.fulfill({ status: 200, json: missing('rabbitmq_federation_management') }),
          );
          await page.reload();
          await settle(page);
        },
      },
      {
        id: 'static-shovel-stub',
        act: (page) =>
          stubGet(page, /\/api\/v1\/clusters\/[^/]+\/rabbitmq\/shovels$/, {
            available: true,
            remedy: null,
            items: [
              {
                id: 'rabbitmq.shovel/%2F/static-copy',
                vhost: '/',
                name: 'static-copy',
                state: 'running',
                source: 'amqp:// orders.eu',
                destination: 'amqp://dr.example.com:5672 orders.eu',
                managedBy: 'CONFIGURATION',
              },
            ],
            next: null,
          }),
      },
    ],
  },
];

// ── The shared screens on the RabbitMQ cluster ────────────────────────────────────────────────────────────────────

const SHARED_PAGE = { data: [], page: 1, pageSize: 50, count: 0, hasNext: false };

/** Narrows a list to one name, so its row is on screen however small the window: the grids draw only the rows in view. */
async function narrowTo(page: Page, label: string, name: string) {
  await exact(page, label).fill(name);
  await page.waitForTimeout(1200);
}

const openActions = async (page: Page, name: string, filter: string) => {
  await narrowTo(page, filter, name);
  await clickShown(page, `Actions for ${name}`);
};

const RABBIT_SHARED: RouteSpec[] = [
  {
    area: 'rabbitmq-shared',
    id: 'subscriptions',
    path: rabbit('subscriptions'),
    data: [{ path: rabbit('subscriptions'), empty: SHARED_PAGE }],
    filter: '?q=' + NO_MATCH,
    forbidden: true,
    scenes: [
      {
        id: 'backlog-reason',
        // A narrow window hides the backlog column, and with it the figure to explain.
        act: async (page) => {
          const estimate = page.getByRole('button', { name: /estimate/ }).first();
          if (await estimate.isVisible()) await estimate.click();
        },
      },
      {
        id: 'queue-panel',
        act: async (page) => {
          await narrowTo(page, 'Filter subscriptions', 'orders.us');
          await page.getByText('orders.us', { exact: true }).first().click();
          await dialogOf(page).first().waitFor();
          await page.waitForTimeout(1200);
        },
      },
      { id: 'create-queue', act: (page) => press(page, 'New subscription') },
      {
        id: 'delete-confirmation',
        act: async (page) => {
          await openActions(page, 'orders.eu', 'Filter subscriptions');
          await page.getByRole('menuitem', { name: /Delete subscription/ }).click();
          await dialogOf(page).waitFor();
          await page.waitForTimeout(1200);
        },
      },
    ],
  },
  {
    area: 'rabbitmq-shared',
    id: 'queue-classic',
    path: rabbit(`subscriptions/${resource('classic-queue', '/', 'orders.eu')}`),
    data: [{ path: rabbit(`subscriptions/${resource('classic-queue', '/', 'orders.eu')}`) }],
  },
  {
    area: 'rabbitmq-shared',
    id: 'queue-quorum',
    path: rabbit(`subscriptions/${resource('quorum-queue', '/', 'orders.us')}`),
    data: [{ path: rabbit(`subscriptions/${resource('quorum-queue', '/', 'orders.us')}`) }],
  },
  {
    area: 'rabbitmq-shared',
    id: 'queue-stream',
    path: rabbit(`subscriptions/${resource('stream', '/', 'audit-log')}`),
  },
  {
    area: 'rabbitmq-shared',
    id: 'messages-classic',
    path: rabbit(`subscriptions/${resource('classic-queue', '/', 'orders.eu')}/messages`),
    data: [{ path: rabbit('resources/rabbitmq.classic-queue/*/messages') }],
    forbidden: true,
    expectedStatus: [409, 422],
  },
  {
    area: 'rabbitmq-shared',
    id: 'messages-quorum',
    path: rabbit(`subscriptions/${resource('quorum-queue', '/', 'orders.us')}/messages`),
    expectedStatus: [409, 422],
  },
  {
    area: 'rabbitmq-shared',
    id: 'messages-stream',
    path: rabbit(`subscriptions/${resource('stream', '/', 'audit-log')}/messages`),
    data: [{ path: rabbit('resources/rabbitmq.stream/*/messages') }],
    forbidden: true,
  },
  {
    area: 'rabbitmq-shared',
    id: 'queue-long-name',
    path: rabbit(`subscriptions/${resource('quorum-queue', '/', LONG_QUEUE)}/messages`),
    expectedStatus: [409, 422],
  },
  {
    area: 'rabbitmq-shared',
    id: 'destinations',
    path: rabbit('destinations'),
    data: [{ path: rabbit('destinations'), empty: SHARED_PAGE }],
    filter: '?q=' + NO_MATCH,
    forbidden: true,
    scenes: [
      { id: 'new-destination', act: (page) => press(page, 'New destination') },
      {
        id: 'publish-routed',
        act: async (page) => {
          await openActions(page, 'orders', 'Filter destinations');
          await page.getByRole('menuitem', { name: /Publish/ }).click();
          await dialogOf(page).getByRole('textbox', { name: 'Routing key' }).fill('eu.new');
          await dialogOf(page).getByRole('textbox', { name: 'Body' }).fill('{"order":1}');
          await dialogOf(page).getByRole('button', { name: 'Publish', exact: true }).click();
          await page.waitForTimeout(1200);
        },
      },
      {
        // The exchange nothing is bound to: the broker accepts the message and no queue receives it (409 not-routed).
        id: 'publish-not-routed',
        act: async (page) => {
          await openActions(page, 'orders.unrouted-test', 'Filter destinations');
          await page.getByRole('menuitem', { name: /Publish/ }).click();
          await dialogOf(page).getByRole('textbox', { name: 'Routing key' }).fill('nobody.listens');
          await dialogOf(page).getByRole('textbox', { name: 'Body' }).fill('{"order":2}');
          await dialogOf(page).getByRole('button', { name: 'Publish', exact: true }).click();
          await page.waitForTimeout(1500);
        },
        expectedStatus: [409],
      },
    ],
  },
  ...(['connections', 'channels', 'consumers'] as const).map((kind): RouteSpec => ({
    area: 'rabbitmq-shared',
    id: kind,
    path: rabbit(kind),
    data: [{ path: rabbit(kind), empty: SHARED_PAGE }],
    filter: '?q=' + NO_MATCH,
    forbidden: true,
  })),
  // The system-neutral screens the navigation also lists on this cluster.
  { area: 'rabbitmq-shared', id: 'consumer-health', path: rabbit('consumer-health') },
  { area: 'rabbitmq-shared', id: 'metrics', path: rabbit('metrics') },
  { area: 'rabbitmq-shared', id: 'alerts', path: rabbit('alerts?tab=rules') },
  { area: 'rabbitmq-shared', id: 'landing', path: '/clusters/:rabbit' },
  // The other systems' landings, to show the RabbitMQ work changed neither.
  { area: 'rabbitmq-regression', id: 'landing-kafka', path: '/clusters/:kafka' },
  { area: 'rabbitmq-regression', id: 'landing-artemis', path: '/clusters/:cluster' },
];

export const RABBITMQ_ROUTES: RouteSpec[] = [...RABBIT_OWN, ...RABBIT_SHARED];

/** Registering a RabbitMQ cluster, outside any cluster: the form and each outcome of the check. */
export const REGISTER_RABBITMQ_ROUTES: RouteSpec[] = [
  {
    area: 'onboarding',
    id: 'register-rabbitmq',
    path: '/clusters/new?system=rabbitmq',
    scenes: REGISTER_RABBIT_SCENES,
  },
];
