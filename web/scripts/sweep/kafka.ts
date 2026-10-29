/**
 * The Kafka cluster's routes and the states of registering one, for the screenshot sweep (task 6.2 of 19e4-kafka).
 *
 * They run against the Kafka cluster "events" that `scripts/qa-seed.sh` (or demo-seed.sh) registers and seeds
 * with `scripts/lib/kafka-seed.sh`: topics orders, payments, inventory, audit-log and customer-state (compacted,
 * one key a tombstone); groups billing (active), analytics (empty, far behind), reporting (empty, a little
 * behind), fulfilment (a share group) and state-reader (on the compacted topic); ACLs; a transaction left open;
 * and a group and a topic with 200-character names. `:kafka` is that cluster's id.
 *
 * Most states are real. A few a live stack cannot hold, a broker whose certificate is refused or a cluster
 * with no authoriser or a ZooKeeper quorum, are the API's own answer put in place of the live one with
 * `page.route`, and the capture says so in its id (`-stub`): the page is the real one, the answer is not.
 */
import type { Page } from '@playwright/test';

import type { DataCall, RouteSpec, Scene } from './routes.ts';

const NO_MATCH = 'zz-nothing-matches-this';
const kafka = (rest: string) => `/clusters/:kafka/${rest}`;

const exact = (page: Page, label: string) => page.getByLabel(label, { exact: true });
const press = (page: Page, name: string) => page.getByRole('button', { name, exact: true }).click();
const dialogOf = (page: Page) => page.getByRole('dialog');

/** The seeded subscriptions the browse and purge captures open: a group on a topic is addressed by its whole id. */
const subscription = (group: string, topic: string) => encodeURIComponent(`kafka.group-topic/${group}/${topic}`);

/** The estate with a second Kafka cluster nobody answers for, put into the cluster list on the real page. */
export const ESTATE_UNREACHABLE_SCENES: Scene[] = [
  {
    id: 'unreachable-kafka-cluster-stub',
    act: async (page) => {
      await page.route(
        (url) => url.pathname === '/api/v1/clusters',
        async (route) => {
          if (route.request().method() !== 'GET') return route.fallback();
          const response = await route.fetch();
          const body = (await response.json()) as { data: Record<string, unknown>[]; count?: number };
          const kafkaRow = body.data.find((c) => (c.system as { id?: string } | undefined)?.id === 'kafka');
          const down = {
            ...(kafkaRow ?? {}),
            id: '00000000-0000-0000-0000-0000000000d4',
            name: 'events-dr',
            description: 'Kafka disaster-recovery cluster',
            health: 'UNKNOWN',
            nodeCount: 0,
          };
          return route.fulfill({
            response,
            json: { ...body, data: [...body.data, down], count: (body.count ?? body.data.length) + 1 },
          });
        },
      );
      await page.reload();
      await page.waitForTimeout(800);
    },
  },
];

/** The 200-character names `kafka-seed.sh` builds. */
const LONG_GROUP = `qa.${Array.from({ length: 8 }, (_, i) => `consumer-group-segment-${String(i + 1).padStart(2, '0')}.`).join('')}terminal`;

/** Waits for what a stub or an action changed to show. */
const settle = (page: Page) => page.waitForTimeout(500);

/** Answers GETs of a path with a body, then reloads so the page asks again. */
async function stubGet(page: Page, path: RegExp, body: unknown) {
  await page.route(
    (url) => path.test(url.pathname),
    (route) =>
      route.request().method() === 'GET'
        ? route.fulfill({ status: 200, contentType: 'application/json', json: body })
        : route.fallback(),
  );
  await page.reload();
  await settle(page);
}

/** Answers a method on a path with a status and a body, on the real page; the rest of its calls are untouched. */
async function stubCall(
  page: Page,
  method: 'GET' | 'POST',
  path: RegExp,
  status: number,
  json: unknown,
  headers: Record<string, string> = {},
  when: (url: URL) => boolean = () => true,
) {
  await page.route(
    (url) => path.test(url.pathname) && when(url),
    (route) =>
      route.request().method() === method
        ? route.fulfill({
            status,
            contentType: status >= 400 ? 'application/problem+json' : 'application/json',
            headers,
            json,
          })
        : route.fallback(),
  );
}

const isDryRun = (url: URL) => url.searchParams.get('dryRun') === 'true';
const isRun = (url: URL) => !isDryRun(url);

/** What the server says of an operation it held for approval: a 202 whose header names the request. */
const held = (summary: string) => ({
  status: 202,
  headers: { 'X-Rahdar-Held-Operation': '00000000-0000-0000-0000-00000000a11d' },
  json: {
    heldOperation: {
      id: '00000000-0000-0000-0000-00000000a11d',
      summary,
      expiresAt: new Date(Date.now() + 86_400_000).toISOString(),
      link: '/api/v1/held-operations/00000000-0000-0000-0000-00000000a11d',
    },
  },
});

/** Presses and holds the confirmation until it fills, as a person does. */
async function holdConfirm(page: Page, name: string) {
  const button = dialogOf(page).getByRole('button', { name });
  await button.hover();
  await page.mouse.down();
  await page.waitForTimeout(1900);
  await page.mouse.up();
  await page.waitForTimeout(800);
}

// ── Registration ──────────────────────────────────────────────────────────────────────────────────────────────────

/** A connection check as the API answers one, for the outcomes a live stack cannot produce. */
function checkOf(over: Record<string, unknown> = {}) {
  return {
    system: 'kafka',
    version: { text: 'at least 3.5', mode: 'KRaft', exact: false },
    supported: true,
    clusterId: 'Mk3OEYBSD34fcwNTJENDM2Qk',
    alreadyRegistered: null,
    nodes: [
      { id: '1', address: 'kafka-1:9092', functions: ['BROKER', 'CONTROLLER'], outcome: 'OK' },
      { id: '2', address: 'kafka-2:9092', functions: ['BROKER', 'CONTROLLER'], outcome: 'OK' },
      { id: '3', address: 'kafka-3:9092', functions: ['BROKER', 'CONTROLLER'], outcome: 'OK' },
    ],
    quorum: { available: true, leader: '1', voters: 3 },
    access: [
      { capability: 'Describe the cluster', status: 'AVAILABLE', reason: 'The account may describe the cluster.' },
    ],
    failure: null,
    contributions: {},
    ...over,
  };
}

const failure = (cause: string, message: string, next: string) =>
  checkOf({ nodes: [], quorum: null, version: null, supported: false, failure: { cause, message, next } });

/** Answers the next dry run of a registration with `check`. */
async function stubCheck(page: Page, check: unknown) {
  await page.route(
    (url) => url.pathname === '/api/v1/clusters' && url.searchParams.get('dryRun') === 'true',
    (route) => route.fulfill({ status: 200, contentType: 'application/json', json: check }),
  );
}

async function bootstrap(page: Page, servers: string) {
  await exact(page, 'Bootstrap servers').fill(servers);
  await exact(page, 'Bootstrap servers').blur();
}

async function chooseProtocol(page: Page, name: string) {
  await page.getByRole('combobox', { name: 'Security protocol' }).click();
  await page.getByRole('option', { name, exact: true }).click();
}

async function runCheck(page: Page) {
  await press(page, 'Check connection');
  await page.waitForTimeout(800);
}

/** A stubbed outcome: the form filled with a server the stack has, the check answered with `check`. */
const stubbed = (id: string, check: unknown): Scene => ({
  id,
  act: async (page) => {
    await stubCheck(page, check);
    await bootstrap(page, 'kafka-1:9092');
    await runCheck(page);
  },
});

/** Registering a Kafka cluster: the form, each outcome of the check, and the refusals before it. */
const REGISTER_KAFKA_SCENES: Scene[] = [
  { id: 'servers-as-entries', act: (page) => bootstrap(page, 'kafka-1:9092, kafka-2 kafka-3:9093') },
  {
    id: 'server-with-scheme',
    act: async (page) => {
      await bootstrap(page, 'PLAINTEXT://kafka-1:9092');
      await press(page, 'Check connection');
    },
  },
  {
    id: 'sasl-fields',
    act: async (page) => {
      await chooseProtocol(page, 'SASL over TLS');
      await page.getByRole('combobox', { name: 'SASL mechanism' }).click();
      await page.getByRole('option', { name: 'SCRAM-SHA-512' }).click();
    },
  },
  { id: 'sasl-plaintext-warning', act: (page) => chooseProtocol(page, 'SASL over plaintext') },
  {
    id: 'password-without-username',
    act: async (page) => {
      await chooseProtocol(page, 'SASL over TLS');
      await page.getByRole('combobox', { name: 'SASL mechanism' }).click();
      await page.getByRole('option', { name: 'PLAIN' }).click();
      await exact(page, 'Password').fill('secret');
      await exact(page, 'Password').blur();
      await press(page, 'Check connection');
    },
  },
  { id: 'advanced-client-id', act: (page) => page.getByRole('button', { name: /Advanced: client id/ }).click() },
  // Real: the brokers are on this stack.
  {
    id: 'already-registered',
    act: async (page) => {
      await bootstrap(page, 'kafka-1:9092');
      await runCheck(page);
    },
  },
  {
    id: 'cause-dns',
    act: async (page) => {
      await bootstrap(page, 'no-such-host.invalid:9092');
      await runCheck(page);
    },
  },
  {
    id: 'cause-refused',
    act: async (page) => {
      await bootstrap(page, 'kafka-1:9999');
      await runCheck(page);
    },
  },
  stubbed('checked-stub', checkOf()),
  stubbed(
    'partial-reach-stub',
    checkOf({
      nodes: [
        { id: '1', address: 'kafka-1:9092', functions: ['BROKER'], outcome: 'OK' },
        {
          id: '2',
          address: 'internal-kafka-2.cluster.local:9092',
          functions: ['BROKER'],
          outcome: 'UNREACHABLE',
          cause: 'DNS',
          message: 'unknown host',
        },
      ],
    }),
  ),
  stubbed(
    'zookeeper-stub',
    checkOf({
      version: { text: 'at least 3.5', mode: 'ZooKeeper', exact: false },
      quorum: { available: false, reason: 'The cluster runs ZooKeeper.' },
      access: [
        { capability: 'Describe the cluster', status: 'AVAILABLE', reason: 'ok' },
        {
          capability: 'Read consumer group offsets',
          status: 'UNAVAILABLE',
          reason: 'The account may not describe groups.',
          remedy: {
            text: 'Grant Describe on groups',
            snippet: 'kafka-acls.sh --add --operation Describe --group "*"',
            mediaType: 'text/x-shellscript',
          },
        },
      ],
    }),
  ),
  stubbed(
    'cause-timeout-stub',
    failure(
      'TIMEOUT',
      'kafka-1:9092 did not answer within 30 seconds.',
      'Check the network path from Rahdar, and any firewall in between.',
    ),
  ),
  stubbed(
    'cause-tls-stub',
    failure(
      'TLS',
      'The broker presented a certificate for kafka.internal, not kafka-1.',
      'Give a TLS bundle that trusts it and check the name it carries.',
    ),
  ),
  stubbed(
    'cause-authentication-stub',
    failure(
      'AUTHENTICATION',
      'The broker rejected the SCRAM credentials.',
      'Check the mechanism, the username and the password.',
    ),
  ),
  stubbed(
    'cause-authorization-stub',
    failure(
      'AUTHORIZATION',
      'The account was accepted but may not describe the cluster.',
      'Grant Describe on the cluster to the account.',
    ),
  ),
  stubbed(
    'cause-unsupported-version-stub',
    failure('UNSUPPORTED_VERSION', 'The cluster runs Kafka 2.8.', 'Rahdar supports Kafka 3.0 and later.'),
  ),
];

/** Registration, outside any cluster: the system choice, each system's form and the outcomes. */
export const REGISTRATION_ROUTES: RouteSpec[] = [
  {
    area: 'onboarding',
    id: 'register-choose',
    path: '/clusters/new',
    data: [{ path: '/systems', empty: { data: [], page: 1, pageSize: 50, count: 0, hasNext: false } }],
  },
  { area: 'onboarding', id: 'register-kafka', path: '/clusters/new?system=kafka', scenes: REGISTER_KAFKA_SCENES },
  { area: 'onboarding', id: 'register-unknown-system', path: '/clusters/new?system=rabbitmq' },
];

// ── Kafka screens ─────────────────────────────────────────────────────────────────────────────────────────────────

const GROUPS_EMPTY: DataCall = { path: kafka('kafka/groups'), empty: { items: [], next: null } };

async function openReset(page: Page) {
  await press(page, 'Reset offsets…');
  await dialogOf(page).waitFor();
}

async function previewReset(page: Page) {
  await openReset(page);
  await press(page, 'Preview the reset');
  await page.waitForTimeout(1200);
}

const HANGING_FOUND = {
  items: [
    {
      id: 'kafka.hanging-transaction/orders/0/4711',
      topic: 'orders',
      partition: 0,
      producerId: 4711,
      producerEpoch: 3,
      startOffset: 120,
      recordsHeldBack: 842,
      ageMs: 540_000,
    },
    {
      id: 'kafka.hanging-transaction/payments/2/9',
      topic: 'payments',
      partition: 2,
      producerId: 9,
      producerEpoch: null,
      startOffset: null,
      recordsHeldBack: null,
      ageMs: null,
    },
  ],
  next: null,
};

const RESET_PREVIEW = {
  partitions: [
    { topic: 'orders', partition: 0, current: 120, next: 0, skipped: 0, reread: 120 },
    { topic: 'orders', partition: 1, current: 90, next: 0, skipped: 0, reread: 90 },
  ],
  members: [],
  blocked: null,
  etag: 'qa-etag',
};

const RESET_SCENES: Scene[] = [
  {
    id: 'reset-held-stub',
    act: async (page) => {
      const answer = held('Reset the offsets of analytics on events');
      await stubCall(page, 'POST', /\/offsets:preview$/, 200, RESET_PREVIEW);
      await stubCall(page, 'POST', /\/offsets:reset$/, answer.status, answer.json, answer.headers);
      await previewReset(page);
      await holdConfirm(page, 'Reset 2 partitions');
    },
  },
  {
    id: 'reset-stale-stub',
    act: async (page) => {
      await stubCall(page, 'POST', /\/offsets:preview$/, 200, RESET_PREVIEW);
      await stubCall(page, 'POST', /\/offsets:reset$/, 412, {
        title: 'Precondition failed',
        status: 412,
        detail: 'The group changed.',
      });
      await previewReset(page);
      await holdConfirm(page, 'Reset 2 partitions');
    },
    expectedStatus: [412],
  },
  {
    // The reset moved one topic and stopped at the next: which moved, which did not, and why.
    id: 'reset-partial-stub',
    act: async (page) => {
      await stubCall(page, 'POST', /\/offsets:preview$/, 200, RESET_PREVIEW);
      await stubCall(page, 'POST', /\/offsets:reset$/, 200, {
        partitions: RESET_PREVIEW.partitions,
        moved: 2,
        movedTopics: ['orders'],
        notMovedTopics: ['payments'],
        failure: 'The group coordinator moved while the offsets of payments were being written.',
      });
      await previewReset(page);
      await holdConfirm(page, 'Reset 2 partitions');
    },
  },
  {
    // The reset to the end of the log: what the group would skip, the case a lagging group is reset for.
    id: 'reset-preview-latest',
    act: async (page) => {
      await openReset(page);
      await page.getByRole('combobox', { name: 'Move to' }).click();
      await page.getByRole('option', { name: 'The latest offset' }).click();
      await press(page, 'Preview the reset');
      await page.waitForTimeout(1200);
    },
  },
  { id: 'reset-dialog', act: openReset },
  { id: 'reset-preview', act: previewReset },
  {
    id: 'reset-offset-needs-value',
    act: async (page) => {
      await openReset(page);
      await page.getByRole('combobox', { name: 'Move to' }).click();
      await page.getByRole('option', { name: 'An offset' }).click();
      await press(page, 'Preview the reset');
    },
  },
  {
    id: 'reset-offset-past-end',
    act: async (page) => {
      await openReset(page);
      await page.getByRole('combobox', { name: 'Move to' }).click();
      await page.getByRole('option', { name: 'An offset' }).click();
      await exact(page, 'Offset').fill('99999999');
      await press(page, 'Preview the reset');
      await page.waitForTimeout(1200);
    },
    expectedStatus: [400],
  },
];

export const KAFKA_ROUTES: RouteSpec[] = [
  {
    area: 'kafka',
    id: 'groups',
    path: kafka('kafka/groups'),
    data: [GROUPS_EMPTY],
    filter: '?q=' + NO_MATCH,
    forbidden: true,
  },
  { area: 'kafka', id: 'groups-share-filter', path: kafka('kafka/groups?type=share') },
  { area: 'kafka', id: 'groups-empty-state-filter', path: kafka('kafka/groups?state=empty') },
  // An active group: members, a lag near zero. Its reset is refused at the preview, naming the members.
  {
    area: 'kafka',
    id: 'group-active',
    path: kafka('kafka/groups/billing'),
    data: [{ path: kafka('kafka/groups/billing') }],
    forbidden: true,
    scenes: [{ id: 'reset-blocked-by-members', act: previewReset }],
  },
  // Empty and far behind: the group to reset.
  {
    area: 'kafka',
    id: 'group-lagging',
    path: kafka('kafka/groups/analytics'),
    data: [{ path: kafka('kafka/groups/analytics') }],
    scenes: RESET_SCENES,
  },
  {
    area: 'kafka',
    id: 'group-empty',
    path: kafka('kafka/groups/reporting'),
    data: [{ path: kafka('kafka/groups/reporting') }],
  },
  // A share group has no offsets to reset: the button is there, disabled, with the reason.
  {
    area: 'kafka',
    id: 'group-share',
    path: kafka('kafka/groups/fulfilment'),
    data: [{ path: kafka('kafka/groups/fulfilment') }],
  },
  { area: 'kafka', id: 'group-long-name', path: kafka(`kafka/groups/${encodeURIComponent(LONG_GROUP)}`) },
  { area: 'kafka', id: 'group-unknown', path: kafka('kafka/groups/zz-no-such-group'), expectedStatus: [404] },
  {
    area: 'kafka',
    id: 'topic-config',
    path: kafka('kafka/topics/orders/config'),
    data: [{ path: kafka('kafka/topics/orders/config'), empty: { internal: false, entries: [] } }],
    forbidden: true,
    scenes: [
      { id: 'change-dialog', act: (page) => page.getByRole('button', { name: 'Change retention.ms' }).click() },
      {
        id: 'review',
        act: async (page) => {
          await page.getByRole('button', { name: 'Change retention.ms' }).click();
          await dialogOf(page).getByRole('textbox', { name: 'New value' }).fill('86400000');
          await press(page, 'Keep this change');
          await press(page, 'Review changes');
          await dialogOf(page).waitFor();
        },
      },
      {
        id: 'review-rejected',
        act: async (page) => {
          await page.getByRole('button', { name: 'Change retention.ms' }).click();
          await dialogOf(page).getByRole('textbox', { name: 'New value' }).fill('soon');
          await press(page, 'Keep this change');
          await press(page, 'Review changes');
          await dialogOf(page).waitFor();
          await page.waitForTimeout(800);
        },
        expectedStatus: [400],
      },
    ],
  },
  { area: 'kafka', id: 'topic-config-compacted', path: kafka('kafka/topics/customer-state/config') },
  // An internal topic: its configuration is read-only, with the reason, and every Change is disabled.
  { area: 'kafka', id: 'topic-config-internal', path: kafka('kafka/topics/__consumer_offsets/config') },
  {
    area: 'kafka',
    id: 'acls',
    path: kafka('kafka/acls'),
    data: [{ path: kafka('kafka/acls'), empty: { available: true, items: [], next: null } }],
    filter: '?principal=' + NO_MATCH,
    forbidden: true,
    scenes: [
      { id: 'add-dialog', act: (page) => press(page, 'Add an ACL') },
      {
        id: 'add-invalid',
        act: async (page) => {
          await press(page, 'Add an ACL');
          await dialogOf(page).getByRole('textbox', { name: 'Principal' }).fill('app');
          await press(page, 'Add to the changes');
        },
      },
      {
        id: 'review-removal',
        act: async (page) => {
          await page
            .getByRole('button', { name: /^Remove Allow User:billing-service/ })
            .first()
            .click();
          await press(page, 'Review changes');
          await dialogOf(page).waitFor();
          await page.waitForTimeout(800);
        },
      },
      {
        id: 'no-authorizer-stub',
        act: (page) =>
          stubGet(page, /\/api\/v1\/clusters\/[^/]+\/kafka\/acls$/, {
            available: false,
            reason: 'The cluster runs without an authorizer.',
            items: [],
            next: null,
          }),
      },
    ],
  },
  {
    area: 'kafka',
    id: 'transactions',
    path: kafka('kafka/transactions'),
    data: [{ path: kafka('kafka/transactions'), empty: { items: [] } }],
    forbidden: true,
    scenes: [
      {
        id: 'abort-dialog',
        act: async (page) => {
          await page
            .getByRole('button', { name: /^Abort / })
            .first()
            .click();
          await dialogOf(page).waitFor();
          await page.waitForTimeout(1000);
        },
      },
      {
        // The search for transactions the coordinator does not know, with two found (the live stack needs OpenBroker 0.7.0).
        id: 'hanging-found-stub',
        act: async (page) => {
          await stubCall(page, 'POST', /hanging-transactions:find$/, 200, HANGING_FOUND);
          await press(page, 'Find hanging transactions');
          await page.waitForTimeout(1200);
        },
      },
      {
        id: 'hanging-none-stub',
        act: async (page) => {
          await stubCall(page, 'POST', /hanging-transactions:find$/, 200, { items: [], next: null });
          await press(page, 'Find hanging transactions');
          await page.waitForTimeout(1200);
        },
      },
      {
        id: 'hanging-abort-dialog-stub',
        act: async (page) => {
          await stubCall(page, 'POST', /hanging-transactions:find$/, 200, HANGING_FOUND);
          await stubCall(page, 'POST', /hanging-transactions:abort-preview$/, 200, {
            topic: 'orders',
            partition: 0,
            producerId: 4711,
            effect: 'Aborts the open transaction of producer 4711 on orders-0 and fences it: its next write fails.',
            recordsHeldBack: 842,
            etag: 'qa-etag',
          });
          await press(page, 'Find hanging transactions');
          await page.getByRole('button', { name: 'Abort on orders-0' }).click();
          await dialogOf(page).waitFor();
          await page.waitForTimeout(1000);
        },
      },
      {
        // A transaction that is still within its timeout: aborting it would fence a working producer, so the button is disabled with the system's reason.
        id: 'abort-not-hanging-stub',
        act: async (page) => {
          await stubCall(page, 'POST', /:abort-preview$/, 200, {
            id: 'payments-settlement',
            state: 'ongoing',
            released: [{ topic: 'payments', partition: 0 }],
            effect:
              'payments-settlement is open and within its timeout, so it is not hanging: aborting it would fence a producer that is working.',
            abortable: false,
            etag: 'qa-etag',
          });
          await page
            .getByRole('button', { name: /^Abort / })
            .first()
            .click();
          await dialogOf(page).waitFor();
          await page.waitForTimeout(1000);
        },
      },
    ],
  },
  {
    area: 'kafka',
    id: 'brokers',
    path: kafka('kafka/brokers'),
    data: [
      {
        path: kafka('kafka/brokers'),
        empty: {
          mode: 'KRaft',
          brokers: [],
          quorum: { available: false, reason: 'No broker answered.', voters: [], majority: false },
        },
      },
    ],
    forbidden: true,
    scenes: [
      {
        id: 'zookeeper-stub',
        act: (page) =>
          stubGet(page, /\/api\/v1\/clusters\/[^/]+\/kafka\/brokers$/, {
            mode: 'ZooKeeper',
            brokers: [
              { id: '1', address: 'kafka-1:9092', reachable: true, leaders: 12, underReplicated: 0, offline: 0 },
              { id: '2', address: 'kafka-2:9092', reachable: true, leaders: 11, underReplicated: 0, offline: 0 },
            ],
            quorum: { available: false, reason: 'The cluster runs ZooKeeper.', voters: [], majority: false },
          }),
      },
      {
        id: 'broker-down-stub',
        act: (page) =>
          stubGet(page, /\/api\/v1\/clusters\/[^/]+\/kafka\/brokers$/, {
            mode: 'KRaft',
            brokers: [
              {
                id: '1',
                rack: 'a',
                address: 'kafka-1:9092',
                reachable: true,
                leaders: 12,
                underReplicated: 4,
                offline: 0,
              },
              {
                id: '2',
                rack: 'b',
                address: 'kafka-2:9092',
                reachable: true,
                leaders: 11,
                underReplicated: 4,
                offline: 0,
              },
              {
                id: '3',
                rack: 'c',
                address: 'kafka-3:9092',
                reachable: false,
                reason: 'Connection refused.',
                leaders: 0,
                underReplicated: 0,
                offline: 3,
              },
            ],
            quorum: {
              available: true,
              leader: '1',
              majority: true,
              voters: [
                { id: '1', lag: 0, caughtUp: true },
                { id: '2', lag: 0, caughtUp: true },
                { id: '3', lag: 4800, caughtUp: false },
              ],
            },
          }),
      },
      {
        // Two of the three voters are behind: the caught-up voters are not a majority, the critical state.
        id: 'no-majority-stub',
        act: (page) =>
          stubGet(page, /\/api\/v1\/clusters\/[^/]+\/kafka\/brokers$/, {
            mode: 'KRaft',
            brokers: [
              { id: '1', address: 'kafka-1:9092', reachable: true, leaders: 20, underReplicated: 0, offline: 0 },
              { id: '2', address: 'kafka-2:9092', reachable: true, leaders: 20, underReplicated: 0, offline: 0 },
              { id: '3', address: 'kafka-3:9092', reachable: true, leaders: 20, underReplicated: 0, offline: 0 },
            ],
            quorum: {
              available: true,
              leader: '1',
              majority: false,
              voters: [
                { id: '1', lag: 0, caughtUp: true },
                { id: '2', lag: 12000, caughtUp: false },
                { id: '3', caughtUp: false },
              ],
            },
          }),
      },
    ],
  },

  // The shared screens on a Kafka cluster.
  {
    area: 'kafka-shared',
    id: 'subscriptions',
    path: kafka('subscriptions'),
    data: [{ path: kafka('subscriptions'), empty: { data: [], page: 1, pageSize: 50, count: 0, hasNext: false } }],
    filter: '?q=' + NO_MATCH,
    forbidden: true,
    scenes: [
      {
        id: 'backlog-reason',
        act: (page) =>
          page
            .getByRole('button', { name: /estimate/ })
            .first()
            .click(),
      },
      {
        id: 'backlog-drawer',
        act: async (page) => {
          await page
            .getByRole('button', { name: /estimate/ })
            .first()
            .click();
          await press(page, 'Show partitions');
          await dialogOf(page).first().waitFor();
          await page.waitForTimeout(1000);
        },
      },
      {
        // An active group among the rows: the preview refuses it, naming its members, and acts on the rest.
        id: 'bulk-purge-preview',
        act: async (page) => {
          await page.getByRole('checkbox', { name: 'Select all on this page' }).check();
          await press(page, 'Purge…');
          await dialogOf(page).waitFor();
          await page.waitForTimeout(1500);
        },
      },
    ],
  },
  {
    area: 'kafka-shared',
    id: 'destinations',
    path: kafka('destinations'),
    data: [{ path: kafka('destinations'), empty: { data: [], page: 1, pageSize: 50, count: 0, hasNext: false } }],
    scenes: [
      {
        id: 'truncate-dialog',
        act: async (page) => {
          await page.getByRole('button', { name: 'Actions for orders' }).first().click();
          await page.getByRole('menuitem', { name: /Truncate/ }).click();
          await dialogOf(page).waitFor();
        },
      },
      {
        id: 'truncate-preview',
        act: async (page) => {
          await page.getByRole('button', { name: 'Actions for orders' }).first().click();
          await page.getByRole('menuitem', { name: /Truncate/ }).click();
          await dialogOf(page)
            .getByRole('textbox', { name: /Delete records before/ })
            .fill('0:100');
          await press(page, 'Preview the cut');
          await page.waitForTimeout(1200);
        },
      },
      {
        id: 'truncate-held-stub',
        act: async (page) => {
          const answer = held('Truncate topic "orders" on events');
          await stubCall(
            page,
            'POST',
            /:truncate$/,
            200,
            {
              records: 1250,
              groups: [
                { group: 'billing', records: 900 },
                { group: 'analytics', records: 350 },
              ],
            },
            {},
            isDryRun,
          );
          await stubCall(page, 'POST', /:truncate$/, answer.status, answer.json, answer.headers, isRun);
          await page.getByRole('button', { name: 'Actions for orders' }).first().click();
          await page.getByRole('menuitem', { name: /Truncate/ }).click();
          await dialogOf(page)
            .getByRole('textbox', { name: /Delete records before/ })
            .fill('0:100');
          await press(page, 'Preview the cut');
          await page.waitForTimeout(1200);
          await holdConfirm(page, 'Truncate 1,250 records');
        },
      },
      {
        id: 'truncate-compacted-refused',
        act: async (page) => {
          await page.getByRole('button', { name: 'Actions for customer-state' }).first().click();
          await page.getByRole('menuitem', { name: /Truncate/ }).click();
          await dialogOf(page)
            .getByRole('textbox', { name: /Delete records before/ })
            .fill('0:1');
          await press(page, 'Preview the cut');
          await page.waitForTimeout(1200);
        },
        expectedStatus: [409],
      },
    ],
  },
  {
    area: 'kafka-shared',
    id: 'messages-orders',
    path: kafka(`subscriptions/${subscription('billing', 'orders')}/messages`),
    data: [{ path: kafka('resources/kafka.group-topic/*/messages') }],
    forbidden: true,
    scenes: [
      {
        id: 'from-offset',
        act: async (page) => {
          await page.getByRole('combobox', { name: 'Start from' }).click();
          await page.getByRole('option', { name: 'An offset' }).click();
          await exact(page, 'Offset').fill('5');
          await press(page, 'Browse');
          await settle(page);
        },
      },
      {
        id: 'from-time',
        act: async (page) => {
          await page.getByRole('combobox', { name: 'Start from' }).click();
          await page.getByRole('option', { name: 'A time' }).click();
          await exact(page, 'Time (ISO-8601)').fill(new Date(Date.now() - 3_600_000).toISOString());
          await press(page, 'Browse');
          await settle(page);
        },
      },
      {
        id: 'offset-past-end',
        act: async (page) => {
          await page.getByRole('combobox', { name: 'Start from' }).click();
          await page.getByRole('option', { name: 'An offset' }).click();
          await exact(page, 'Offset').fill('99999999');
          await press(page, 'Browse');
          await settle(page);
        },
      },
      { id: 'selector-disabled-actions', act: (page) => press(page, 'By selector…') },
      {
        id: 'purge-refused-members',
        act: async (page) => {
          await press(page, 'Purge subscription');
          await dialogOf(page).waitFor();
          await page.waitForTimeout(1000);
        },
        expectedStatus: [409],
      },
      {
        // The estimate is taken, then the group began rebalancing: the run is refused and its reason stays in the dialog.
        id: 'purge-refused-rebalancing-stub',
        act: async (page) => {
          await stubCall(
            page,
            'POST',
            /:purge$/,
            200,
            { affectedCount: 0, skippedCount: 1200, cap: 100000, overCap: false, dryRun: true },
            {},
            isDryRun,
          );
          await stubCall(
            page,
            'POST',
            /:purge$/,
            409,
            {
              title: 'Failed precondition',
              status: 409,
              detail: 'The group began rebalancing. Try again when it is stable.',
            },
            {},
            isRun,
          );
          await press(page, 'Purge subscription');
          await dialogOf(page).waitFor();
          await page.waitForTimeout(1000);
          await holdConfirm(page, 'Skip 1,200 records');
        },
        expectedStatus: [409],
      },
      {
        id: 'purge-held-stub',
        act: async (page) => {
          const answer = held('Purge subscription "billing" on events');
          await stubCall(
            page,
            'POST',
            /:purge$/,
            200,
            { affectedCount: 0, skippedCount: 1200, cap: 100000, overCap: false, dryRun: true },
            {},
            isDryRun,
          );
          await stubCall(page, 'POST', /:purge$/, answer.status, answer.json, answer.headers, isRun);
          await press(page, 'Purge subscription');
          await dialogOf(page).waitFor();
          await page.waitForTimeout(1000);
          await holdConfirm(page, 'Skip 1,200 records');
        },
      },
      {
        id: 'record-open',
        act: async (page) => {
          await page.getByRole('row').nth(1).click();
          await dialogOf(page).waitFor();
        },
      },
    ],
  },
  {
    // A bulk purge that ran: a run of Kafka subscriptions, one done, one failed because its group began
    // rebalancing, one refused at the preview because it has members. The run is the API's answer put in place.
    area: 'kafka-shared',
    id: 'bulk-run-stub',
    path: kafka('bulk/00000000-0000-4000-8000-00000000b001'),
    scenes: [
      {
        id: 'run-with-rebalancing-failure-stub',
        act: async (page) => {
          const cluster = /\/clusters\/([^/]+)/.exec(new URL(page.url()).pathname)?.[1] ?? '';
          const item = (
            ordinal: number,
            group: string,
            status: string,
            error: string | null,
            outcome: unknown[] | null,
          ) => ({
            ordinal,
            subscription: `kafka.group-topic/${group}/orders`,
            kind: 'kafka.group-topic',
            kindLabel: { singular: 'Group on topic', plural: 'Groups on topics' },
            status,
            error,
            warning: null,
            affected: status === 'REFUSED' ? null : 1200,
            nodes: [],
            outcome,
            startedAt: '2026-10-28T10:00:00Z',
            finishedAt: '2026-10-28T10:00:02Z',
          });
          const rebalancing = 'The group began rebalancing. Try again when it is stable.';
          await stubCall(page, 'GET', /\/bulk\/runs\/00000000-0000-4000-8000-00000000b001$/, 200, {
            run: {
              id: '00000000-0000-4000-8000-00000000b001',
              clusterId: cluster,
              operation: 'PURGE',
              status: 'PARTIAL',
              username: 'admin',
              createdAt: '2026-10-28T10:00:00Z',
              expiresAt: '2026-10-28T10:10:00Z',
              startedAt: '2026-10-28T10:00:00Z',
              finishedAt: '2026-10-28T10:00:03Z',
              total: 3,
              succeeded: 1,
              failed: 1,
              skipped: 1,
              estimate: 3600,
              estimateComplete: true,
              cap: 100000,
              overCap: false,
              overrideCap: false,
              continueOnFailure: false,
              disconnectConsumers: false,
              selection: {
                ids: [
                  'kafka.group-topic/analytics/orders',
                  'kafka.group-topic/reporting/orders',
                  'kafka.group-topic/billing/orders',
                ],
                q: null,
              },
              planHash: 'qa',
              auditEventId: null,
              error: null,
            },
            items: [
              item(0, 'analytics', 'SUCCEEDED', null, [
                { nodeId: 'cluster', nodeName: 'The whole cluster', status: 'APPLIED', affected: 1200 },
              ]),
              item(1, 'reporting', 'FAILED', rebalancing, [
                { nodeId: 'cluster', nodeName: 'The whole cluster', status: 'FAILED', error: rebalancing },
              ]),
              item(
                2,
                'billing',
                'REFUSED',
                'The group has 2 members (consumer-1, consumer-2). Stop them before purging.',
                null,
              ),
            ],
          });
          await page.goto(
            `${new URL(page.url()).origin}/clusters/${cluster}/bulk/00000000-0000-4000-8000-00000000b001`,
          );
          await page.waitForTimeout(800);
        },
      },
    ],
  },
  {
    area: 'kafka-shared',
    id: 'messages-tombstone',
    path: kafka(`subscriptions/${subscription('state-reader', 'customer-state')}/messages`),
  },
  {
    area: 'kafka-shared',
    id: 'messages-long-name',
    path: kafka(
      `subscriptions/${encodeURIComponent(`kafka.group-topic/${LONG_GROUP}/${LONG_GROUP.replaceAll('consumer-group', 'topic')}`)}/messages`,
    ),
  },
];
