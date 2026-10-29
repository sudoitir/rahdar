import { BASE } from '../e2e-base.ts';
import {
  attachBillingConsumer,
  committed,
  inContainer,
  kafka,
  members,
  offsetsAt,
  openHangingTransaction,
  placeGroup,
  produce,
  produceKeyed,
  readCommittedKeys,
  transactionState,
} from './broker.ts';
import type { Flow, Toolkit } from './types.ts';

const exact = (t: Toolkit, label: string) => t.page.getByLabel(label, { exact: true });
const press = (t: Toolkit, name: string | RegExp) => t.page.getByRole('button', { name }).click();
const dialog = (t: Toolkit) => t.page.getByRole('dialog');

/** Puts the seeded states back before the flows that spend them: lag to skip, an active group, a hanging transaction. */
const prepare: Flow = {
  id: 'prepare',
  title: 'the seeded states the flows act on: records to skip, an active group billing, a hanging transaction',
  run: async (t) => {
    produce('orders', 800);
    produce('payments', 600);
    if (members('billing') === 0) {
      attachBillingConsumer();
      await new Promise((r) => setTimeout(r, 8000));
    }
    t.check(members('billing') > 0, `billing has ${members('billing')} member on the broker`);
    const state = (await t.api(`/clusters/${t.cluster}/kafka/transactions`)) as { items: { hanging: boolean }[] };
    if (!state.items.some((x) => x.hanging)) {
      openHangingTransaction();
      // Hanging is a minute past the transaction's own one minute timeout.
      for (let i = 0; i < 24; i++) {
        await new Promise((r) => setTimeout(r, 10_000));
        const now = (await t.api(`/clusters/${t.cluster}/kafka/transactions`)) as { items: { hanging: boolean }[] };
        if (now.items.some((x) => x.hanging)) break;
      }
    }
    const after = (await t.api(`/clusters/${t.cluster}/kafka/transactions`)) as { items: { hanging: boolean }[] };
    t.check(
      after.items.some((x) => x.hanging),
      'payments-settlement is hanging',
    );
    // reporting is a little behind on payments partition 0, above the log start, below the last stable offset.
    const start = offsetsAt('payments', -2)[0];
    placeGroup('reporting', 'payments', 0, start + 100);
    t.check(
      committed('reporting', 'payments')[0] === start + 100,
      `reporting is placed at offset ${start + 100} of payments-0`,
    );
  },
};

const estate: Flow = {
  id: 'estate',
  title: 'the estate shows both systems, Kafka health and a backlog column with estimates',
  run: async (t) => {
    await t.open('/');
    const clusters = t.page.getByRole('table', { name: /clusters/i }).first();
    await clusters.waitFor();
    const text = (await clusters.innerText()).replace(/\s+/g, ' ');
    t.check(
      /demo/.test(text) && /events/.test(text),
      `the clusters table lists demo and events: "${text.slice(0, 160)}"`,
    );
    t.check(
      /Apache Kafka/.test(text) && /ActiveMQ Artemis/.test(text),
      'the System column names Apache Kafka and Apache ActiveMQ Artemis',
    );
    const api = (await t.api('/clusters?size=100')) as { data: { name: string; health: string }[] };
    t.note(`API health: ${api.data.map((c) => `${c.name}=${c.health}`).join(', ')}`);
    const events = api.data.find((c) => c.name === 'events');
    t.check(events?.health === 'OK', `events health is OK per the API (${events?.health})`);
    await t.page.getByRole('textbox', { name: 'Filter subscriptions' }).fill('billing');
    await t.page.waitForTimeout(1500);
    const body = (await t.page.locator('main').innerText()).replace(/\s+/g, ' ');
    t.check(
      /billing/.test(body) && /events/.test(body),
      `the subscriptions grid lists the Kafka group's subscription beside Artemis ones: "${body.slice(body.indexOf('Backlog'), body.indexOf('Backlog') + 200)}"`,
    );
    t.check(/Backlog/.test(body), 'the subscriptions grid has a Backlog column');
    t.check(
      (await t.page.getByRole('button', { name: /estimate/ }).count()) > 0,
      'a Kafka backlog is marked as an estimate (a button naming it)',
    );
  },
};

async function checkServers(t: Toolkit, servers: string): Promise<string> {
  await t.open('/clusters/new?system=kafka');
  await exact(t, 'Bootstrap servers').fill(servers);
  await exact(t, 'Bootstrap servers').blur();
  const answered = t.page.waitForResponse(
    (r) => r.url().includes('/api/v1/clusters') && r.url().includes('dryRun=true'),
    {
      timeout: 90_000,
    },
  );
  await press(t, 'Check connection');
  await answered;
  await t.page.waitForTimeout(1500);
  const text = (await t.page.locator('main').innerText()).replace(/\s+/g, ' ');
  // What the check said: everything after the form's last field, up to its identity fields.
  return text.slice(text.indexOf('Advanced: client id') + 'Advanced: client id'.length, text.indexOf('Name Optional'));
}

const register: Flow = {
  id: 'register',
  title: 'registering a Kafka cluster: each real failure cause, then a successful registration of a fourth broker',
  run: async (t) => {
    const dns = await checkServers(t, 'no-such-host.invalid:9092');
    t.note(`DNS: ${dns.slice(0, 500)}`);
    t.check(/resolve|DNS|unknown host/i.test(dns), 'an unknown host is reported as a DNS cause');
    t.check(
      await t.page.getByRole('button', { name: 'Register cluster' }).isDisabled(),
      'Register stays disabled after a failed check',
    );

    const refused = await checkServers(t, 'kafka-1:9999');
    t.note(`refused: ${refused.slice(0, 500)}`);
    t.check(/refused/i.test(refused), 'a closed port is reported as refused');

    const again = await checkServers(t, 'kafka-1:9092');
    t.note(`already: ${again.slice(0, 700)}`);
    t.check(/already registered/i.test(again), 'the running cluster is reported as already registered');
    t.check(
      await t.page.getByRole('button', { name: 'Register cluster' }).isDisabled(),
      'Register is disabled for an already registered cluster',
    );

    const solo = await checkServers(t, 'kafka-solo:9092');
    t.check(
      /KRaft/.test(solo) && /kafka-solo:9092/.test(solo),
      `the check of the fourth broker passes and names it: ${solo.slice(0, 500)}`,
    );
    await exact(t, 'Name').fill('solo');
    await t.page.getByRole('button', { name: 'Register cluster' }).click();
    await t.page.waitForURL(/\/clusters\/[0-9a-f-]{36}\//, { timeout: 30_000 });
    const list = (await t.api('/clusters?size=100')) as {
      data: { id: string; name: string; system: { id: string }; nodeCount: number; health: string }[];
    };
    const row = list.data.find((c) => c.name === 'solo');
    t.check(
      row?.system.id === 'kafka' && row.nodeCount === 1,
      `Rahdar lists "solo" as Kafka with one node (${JSON.stringify(row && { s: row.system.id, n: row.nodeCount, h: row.health })})`,
    );
    t.check(
      kafkaIds('kafka-solo:9092').includes('1'),
      'the broker itself answers on kafka-solo:9092 (kafka-broker-api-versions)',
    );
    if (row)
      t.check(
        (await t.send('DELETE', `/clusters/${row.id}`)).status === 204,
        'the registration is removed again (204)',
      );

    // A broker whose advertised listener Rahdar cannot reach: it answers the bootstrap, then tells Rahdar to come back at
    // localhost. Started before the run (`startBroker('qa-kafka-badadv', 'kafka-badadv', 'localhost')`): a container that
    // starts or stops while the browser loads aborts its requests (ERR_NETWORK_CHANGED).
    const bad = await checkServers(t, 'kafka-badadv:9092');
    t.note(`unreachable advertised listener: ${bad.slice(0, 500)}`);
    t.check(/localhost/.test(bad), 'the check names the advertised address it could not reach (localhost:9092)');
    t.check(
      (await t.page.getByRole('button', { name: 'Register cluster' }).isDisabled()) ||
        /not reached|Unreachable|Not reached/i.test(bad),
      'the broker that cannot be reached on its advertised address is not shown as reached',
    );
  },
};

function kafkaIds(server: string): string {
  return inContainer('qa-kafka-solo', 'kafka-broker-api-versions.sh', '--bootstrap-server', server).includes('(id: 1')
    ? '1'
    : '';
}

const groups: Flow = {
  id: 'groups',
  title: 'the groups list and a group page agree with the broker',
  run: async (t) => {
    await t.open('/clusters/:kafka/kafka/groups');
    const grid = t.page
      .getByRole('grid', { name: 'Consumer groups' })
      .or(t.page.getByRole('table', { name: 'Consumer groups' }));
    await grid.first().waitFor();
    await t.page.waitForTimeout(1000);
    const text = (await grid.first().innerText()).replace(/\s+/g, ' ');
    const listed = kafka('kafka-consumer-groups.sh', '--list')
      .split('\n')
      .map((l) => l.trim())
      .filter(Boolean);
    t.note(`broker lists ${listed.length} groups; page: ${text.slice(0, 300)}`);
    for (const g of ['analytics', 'billing', 'reporting', 'fulfilment', 'state-reader']) {
      t.check(text.includes(g), `the page lists ${g}`);
    }
    t.check(/share/i.test(text) && /classic/i.test(text), 'the page tells share from classic groups');

    await t.open('/clusters/:kafka/kafka/groups/analytics');
    await t.page.getByRole('table', { name: 'orders partitions' }).waitFor();
    const page = (await t.page.getByRole('table', { name: 'orders partitions' }).innerText()).replace(/\s+/g, ' ');
    const onBroker = committed('analytics', 'orders');
    const ends = offsetsAt('orders', -1);
    const lag = Object.keys(onBroker).reduce((n, p) => n + (ends[Number(p)] - onBroker[Number(p)]), 0);
    t.note(`broker lag of analytics on orders: ${lag}`);
    t.check(
      text.includes(lag.toLocaleString('en')),
      `the list shows the lag ${lag.toLocaleString('en')} the broker computes`,
    );
    for (const [p, o] of Object.entries(onBroker)) {
      t.check(
        new RegExp(`(^|\\s)${p}\\s+[\\d,]+\\s+${o.toLocaleString('en')}\\s+[\\d,]+`).test(page),
        `partition ${p} shows the committed offset ${o} the broker reports`,
      );
    }
  },
};

interface AuditEvent {
  id: number;
  action: string;
  targetName: string | null;
  outcome: string;
  params: string | null;
  error: string | null;
  affectedCount: number | null;
}
/** The newest audit events of the cluster, newest first. */
async function audit(t: Toolkit): Promise<AuditEvent[]> {
  return ((await t.api(`/clusters/${t.cluster}/audit?size=20`)) as { data: AuditEvent[] }).data;
}

const resetOffsets: Flow = {
  id: 'reset',
  title:
    'an offset reset on an empty group: preview, hold, run; the committed offsets move on the broker and the audit row records them',
  run: async (t) => {
    const before = committed('analytics', 'orders');
    await t.open('/clusters/:kafka/kafka/groups/analytics');
    await press(t, 'Reset offsets…');
    await dialog(t).waitFor();
    await t.page.getByRole('combobox', { name: 'Move to' }).click();
    await t.page.getByRole('option', { name: 'The latest offset' }).click();
    await press(t, 'Preview the reset');
    const confirm = dialog(t).getByRole('button', { name: /^Reset \d+ partitions?$/ });
    await confirm.waitFor();
    const preview = (await dialog(t).innerText()).replace(/\s+/g, ' ');
    t.note(`preview: ${preview.slice(preview.indexOf('Moves'), preview.indexOf('Moves') + 300)}`);
    t.check(/skips [\d,]+ records/.test(preview), 'the preview states how many records the group will skip');
    t.check(
      JSON.stringify(committed('analytics', 'orders')) === JSON.stringify(before),
      'nothing moved on the broker before the hold (the preview is a dry run)',
    );
    const armed = await confirm.getAttribute('aria-disabled');
    t.check((await confirm.isEnabled()) || armed !== 'true', 'the confirmation is armed by the preview');
    await t.hold(/^Reset \d+ partitions?$/);
    await t.page.waitForTimeout(2500);
    const after = committed('analytics', 'orders');
    const ends = offsetsAt('orders', -1);
    t.note(`before ${JSON.stringify(before)}; after ${JSON.stringify(after)}; log ends ${JSON.stringify(ends)}`);
    t.check(
      Object.keys(before).every((p) => after[Number(p)] === ends[Number(p)]),
      'the broker now holds every partition at its end offset',
    );
    t.check(
      Object.keys(before).some((p) => after[Number(p)] !== before[Number(p)]),
      'the committed offsets changed on the broker',
    );
    const event = (await audit(t)).find((e) => /OFFSET/i.test(e.action));
    t.note(`audit: ${JSON.stringify(event)}`);
    t.check(event?.outcome === 'SUCCESS', `an audit row records the reset (${event?.action}, ${event?.outcome})`);
    t.check(
      /before|current|from/i.test(event?.params ?? '') && /after|next|to/i.test(event?.params ?? ''),
      'the audit row carries the before and after offsets',
    );
    await t.open('/clusters/:kafka/kafka/groups/analytics');
    const table = (await t.page.getByRole('table', { name: 'orders partitions' }).innerText()).replace(/\s+/g, ' ');
    t.check(
      Object.entries(ends).every(([p, e]) =>
        new RegExp(`(^|\\s)${p}\\s+0\\s+${e.toLocaleString('en')}\\s+${e.toLocaleString('en')}`).test(table),
      ),
      'the group page shows no lag on any partition',
    );
  },
};

const resetRefused: Flow = {
  id: 'reset-refused',
  title: 'an offset reset is refused on an active group, naming its members, and nothing moves',
  expectedStatus: [409, 400],
  run: async (t) => {
    const before = committed('billing', 'orders');
    await t.open('/clusters/:kafka/kafka/groups/billing');
    await press(t, 'Reset offsets…');
    await dialog(t).waitFor();
    await press(t, 'Preview the reset');
    await t.page.waitForTimeout(2500);
    const text = (await dialog(t).innerText()).replace(/\s+/g, ' ');
    t.note(`dialog: ${text.slice(text.indexOf('Preview the reset'), text.indexOf('Preview the reset') + 400)}`);
    t.check(/cannot be reset|members|active/i.test(text), 'the dialog says the group cannot be reset right now');
    t.check(
      await dialog(t)
        .getByRole('button', { name: /^Reset( |$)/ })
        .last()
        .isDisabled(),
      'the reset confirmation stays disabled',
    );
    t.check(JSON.stringify(committed('billing', 'orders')) === JSON.stringify(before), 'nothing moved on the broker');
  },
};

/** The topic's own overrides as the broker lists them. */
const overrides = (topic: string): string =>
  kafka('kafka-configs.sh', '--entity-type', 'topics', '--entity-name', topic, '--describe');

const topicConfig: Flow = {
  id: 'topic-config',
  title:
    "a topic configuration change reaches the broker; an invalid value is refused by the broker's own validation; the override can be removed",
  expectedStatus: [400],
  run: async (t) => {
    t.check(!/retention\.ms/.test(overrides('inventory')), 'inventory has no retention.ms override to begin with');
    await t.open('/clusters/:kafka/kafka/topics/inventory/config');
    await t.page.getByRole('button', { name: 'Change retention.ms' }).click();
    await dialog(t).getByRole('textbox', { name: 'New value' }).fill('86400000');
    await press(t, 'Keep this change');
    await press(t, 'Review changes');
    await dialog(t).waitFor();
    await t.page.getByRole('table', { name: 'Changes to apply' }).waitFor();
    const review = (await dialog(t).innerText()).replace(/\s+/g, ' ');
    t.note(`review: ${review.slice(0, 260)}`);
    t.check(/86400000/.test(review) && /Accepted/.test(review), 'the review shows the new value as accepted');
    t.check(!/retention\.ms/.test(overrides('inventory')), 'nothing changed on the broker at the review');
    await press(t, 'Apply 1 change');
    await t.page.waitForTimeout(2500);
    t.check(
      /retention\.ms=86400000/.test(overrides('inventory')),
      `the broker shows the override: ${overrides('inventory').trim().split('\n').pop()}`,
    );

    // The broker's validation refuses a value it cannot parse; Rahdar says so at the review and applies nothing.
    await t.open('/clusters/:kafka/kafka/topics/inventory/config');
    await t.page.getByRole('button', { name: 'Change cleanup.policy' }).click();
    await dialog(t).getByRole('textbox', { name: 'New value' }).fill('bogus');
    await press(t, 'Keep this change');
    await press(t, 'Review changes');
    await t.page.getByRole('table', { name: 'Changes to apply' }).waitFor();
    await t.page.waitForTimeout(800);
    const bad = (await dialog(t).innerText()).replace(/\s+/g, ' ');
    t.note(`invalid review: ${bad.slice(0, 300)}`);
    t.check(
      !/Accepted/.test(bad) && /rejects|Invalid|must be|at least|not/i.test(bad),
      'the review states the broker refused the value',
    );
    t.check(
      await dialog(t)
        .getByRole('button', { name: /^Apply 1 change$/ })
        .isDisabled(),
      'Apply stays disabled for a refused value',
    );
    t.check(!/cleanup\.policy=bogus/.test(overrides('inventory')), 'the broker did not take the invalid value');
    await t.page.keyboard.press('Escape');

    // Remove the override again.
    await t.open('/clusters/:kafka/kafka/topics/inventory/config');
    await t.page.getByRole('button', { name: 'Change retention.ms' }).click();
    await press(t, 'Remove the override and use the default');
    await press(t, 'Review changes');
    await t.page.getByRole('table', { name: 'Changes to apply' }).waitFor();
    await press(t, 'Apply 1 change');
    await t.page.waitForTimeout(2500);
    t.check(
      !/retention\.ms/.test(overrides('inventory')),
      'the broker no longer lists the override after it is removed',
    );
  },
};

const acls: Flow = {
  id: 'acls',
  title: "an ACL is added and removed through the review; the broker's ACL list changes each time",
  run: async (t) => {
    const list = () => kafka('kafka-acls.sh', '--list', '--principal', 'User:e2e');
    t.check(!/permissionType/.test(list()), 'no ACL for User:e2e to begin with');
    await t.open('/clusters/:kafka/kafka/acls');
    await press(t, 'Add an ACL');
    await dialog(t).getByRole('textbox', { name: 'Principal' }).fill('User:e2e');
    await dialog(t).getByRole('textbox', { name: 'Resource name' }).fill('inventory');
    await press(t, 'Add to the changes');
    await press(t, 'Review changes');
    await dialog(t).waitFor();
    await t.page.waitForTimeout(1500);
    const review = (await dialog(t).innerText()).replace(/\s+/g, ' ');
    t.note(`review: ${review.slice(0, 400)}`);
    t.check(/User:e2e/.test(review), 'the review names the principal and the access it gains');
    t.check(!/permissionType/.test(list()), 'nothing changed on the broker at the review');
    await press(t, /^Apply 1 change$/);
    await t.page.waitForTimeout(2500);
    t.check(
      /User:e2e/.test(list()) && /inventory/.test(list()),
      `the broker lists the new ACL: ${list().replace(/\s+/g, ' ').slice(0, 200)}`,
    );
    await t.open('/clusters/:kafka/kafka/acls');
    await t.page
      .getByRole('button', { name: /^Remove Allow User:e2e/ })
      .first()
      .click();
    await press(t, 'Review changes');
    await dialog(t).waitFor();
    await t.page.waitForTimeout(1500);
    t.note(`removal review: ${(await dialog(t).innerText()).replace(/\s+/g, ' ').slice(0, 300)}`);
    await t.hold(/^Apply 1 change$/);
    await t.page.waitForTimeout(2500);
    t.check(!/permissionType/.test(list()), 'the broker no longer lists the ACL after its removal');
  },
};

const lagOf = (group: string): number => {
  const out = kafka('kafka-consumer-groups.sh', '--describe', '--group', group);
  return out
    .split('\n')
    .map((l) => l.trim().split(/\s+/))
    .filter((c) => c[0] === group && /^\d+$/.test(c[5] ?? ''))
    .reduce((n, c) => n + Number(c[5]), 0);
};

/** Opens the Actions menu of the subscription row of `group` on `topic`, filtered by the group's name. */
async function rowMenu(t: Toolkit, filter: string, rowLabel: RegExp | string) {
  await t.open(`/clusters/:kafka/subscriptions?q=${encodeURIComponent(filter)}`);
  await t.page.getByRole('button', { name: rowLabel }).first().waitFor({ timeout: 30_000 });
  await t.page.getByRole('button', { name: rowLabel }).first().click();
}

const purgeGroup: Flow = {
  id: 'purge',
  title: 'purging an empty group moves its committed offsets to the end on the broker',
  run: async (t) => {
    const lagBefore = lagOf('reporting');
    // The broker's lag counts from the committed offset even where a truncate removed the records below the log start.
    const lost = Object.entries(committed('reporting', 'payments')).reduce(
      (n, [p, o]) => n + Math.max(0, offsetsAt('payments', -2)[Number(p)] - o),
      0,
    );
    t.check(lagBefore > 0, `reporting lags ${lagBefore} records on the broker before`);
    await rowMenu(t, 'reporting', /^Actions for /);
    await t.page.getByRole('menuitem', { name: /Purge messages/ }).click();
    await dialog(t).waitFor();
    await dialog(t).getByText(/No record is deleted/).waitFor({ timeout: 30_000 });
    const text = (await dialog(t).innerText()).replace(/\s+/g, ' ');
    t.note(`purge dialog: ${text.slice(0, 500)}`);
    // The purge moves the group to the last stable offset: records of the open transaction above it stay to read.
    const skipping = Number(/skipping ([\d,]+) records/.exec(text)?.[1]?.replace(/,/g, '') ?? NaN);
    t.check(
      skipping > 0 && skipping <= lagBefore,
      `the dialog states ${skipping} records to skip, of the ${lagBefore} the group lags`,
    );
    t.check(
      !/is unavailable|does not know/.test(text),
      'the dialog does not call the count unavailable or unknown while it states it (it says: "How many messages that destroys is unavailable ... a figure Rahdar does not know")',
    );
    t.check(lagOf('reporting') === lagBefore, 'nothing moved before the hold');
    t.check(
      /No record is deleted/.test(text) && !/destroy|cannot be undone/i.test(text),
      'the dialog says no record is deleted, and never destroy or cannot be undone',
    );
    t.check(
      (await dialog(t).getByRole('checkbox', { name: 'Override the safety cap for this run' }).count()) === 0,
      'no safety cap applies to a purge that deletes nothing',
    );
    await t.hold(/^Skip [\d,]+ records? for 1 group$/);
    await t.page.waitForTimeout(3500);
    t.check(
      lagOf('reporting') === lagBefore - skipping - lost,
      `the broker's lag of reporting fell from ${lagBefore} by the ${skipping} the dialog stated and the ${lost} a truncate had removed, to ${lagOf('reporting')}`,
    );
    const event = (await audit(t)).find((e) => /PURGE/i.test(e.action));
    t.note(`audit: ${JSON.stringify(event)?.slice(0, 400)}`);
    t.check(event?.outcome === 'SUCCESS', `an audit row records the purge (${event?.action}, ${event?.outcome})`);
  },
};

const truncate: Flow = {
  id: 'truncate',
  title: 'truncating a topic moves its log start on the broker and the groups that lose unread records are named',
  run: async (t) => {
    const startBefore = offsetsAt('payments', -2);
    const lagBefore = lagOf('reporting');
    // A little past what the group `reporting` has read on partition 0, so the cut costs it unread records.
    // The cut may not pass the last stable offset, which the open transaction holds back (the page says so).
    const cutAt = committed('reporting', 'payments')[0] + 77;
    const cut = `0:${cutAt}`;
    await t.open('/clusters/:kafka/destinations');
    await t.page.getByRole('button', { name: 'Actions for payments' }).first().click();
    await t.page.getByRole('menuitem', { name: /Truncate/ }).click();
    await dialog(t).waitFor();
    await dialog(t)
      .getByRole('textbox', { name: /Delete records before/ })
      .fill(cut);
    await press(t, 'Preview the cut');
    await t.page.waitForTimeout(2500);
    const text = (await dialog(t).innerText()).replace(/\s+/g, ' ');
    t.note(
      `truncate preview (cut ${cut}): ${text.slice(text.indexOf('This removes') >= 0 ? text.indexOf('This removes') : 0, 400)}`,
    );
    t.check(/This removes [\d,]+ records?/.test(text), 'the preview states how many records go');
    t.check(/reporting/.test(text), 'the preview names the group that would lose unread records (reporting)');
    t.check(
      JSON.stringify(offsetsAt('payments', -2)) === JSON.stringify(startBefore),
      'the log start has not moved before the hold',
    );
    await t.hold(/^Truncate [\d,]+ records?$/);
    await t.page.waitForTimeout(3000);
    const startAfter = offsetsAt('payments', -2);
    t.note(
      `log start before ${JSON.stringify(startBefore)}, after ${JSON.stringify(startAfter)}; reporting lag ${lagBefore} -> ${lagOf('reporting')}`,
    );
    t.check(
      startAfter[0] === cutAt,
      `the broker's log start of partition 0 moved from ${startBefore[0]} to ${startAfter[0]}`,
    );
    // The broker keeps the group's committed offset where it was (it falls below the log start); the preview's figure
    // is what the cut took from the group: the cut minus where the group had read.
    t.check(
      /reporting\s+77\b/.test(text),
      'the preview stated 77 unread records lost for reporting, the cut (1150) minus its committed offset (1073)',
    );
    const event = (await audit(t)).find((e) => /TRUNCATE/i.test(e.action));
    t.check(event?.outcome === 'SUCCESS', `an audit row records the truncate (${event?.action}, ${event?.outcome})`);
  },
};

const bulkPurge: Flow = {
  id: 'bulk-purge',
  title: 'a bulk purge of a mixed selection refuses the active group naming its members and acts on the empty ones',
  expectedStatus: [409],
  run: async (t) => {
    produce('orders', 300);
    produce('payments', 300);
    const lag = { analytics: lagOf('analytics'), reporting: lagOf('reporting') };
    t.check(
      lag.analytics > 0 && lag.reporting > 0,
      `analytics (${lag.analytics}) and reporting (${lag.reporting}) have new records to skip`,
    );
    await t.open('/clusters/:kafka/subscriptions');
    await t.page.getByRole('checkbox', { name: 'Select all on this page' }).waitFor();
    await t.page.getByRole('checkbox', { name: 'Select all on this page' }).check();
    await press(t, 'Purge…');
    await dialog(t).waitFor();
    await dialog(t).getByText(/No record is deleted|refused/).first().waitFor({ timeout: 30_000 });
    const text = (await dialog(t).innerText()).replace(/\s+/g, ' ');
    t.note(`bulk dialog: ${text.slice(0, 900)}`);
    t.check(
      /billing/.test(text) && /console-consumer|member/i.test(text),
      'the preview refuses billing and names its active member',
    );
    t.check(/analytics/.test(text) && /reporting/.test(text), 'the preview lists the empty groups as acting');
    const override = dialog(t).getByRole('checkbox', { name: 'Override the safety cap for this run' });
    if (await override.count()) await override.check();
    await t.hold(/^(Purge|Skip)\b.*(subscriptions?|groups?)$/);
    await t.page.waitForTimeout(5000);
    t.check(
      lagOf('analytics') === 0 && lagOf('reporting') < lag.reporting,
      `the broker shows analytics at ${lagOf('analytics')} and reporting at ${lagOf('reporting')} (from ${lag.reporting}; the records above the open transaction stay to read)`,
    );
    const result = (await t.page.locator('main').innerText()).replace(/\s+/g, ' ');
    t.note(`result page: ${t.page.url()} ${result.slice(0, 700)}`);
  },
};

const backlogDrawer: Flow = {
  id: 'backlog-drawer',
  title: 'the backlog of a group is marked as an estimate, says why, and its partition breakdown matches the broker',
  run: async (t) => {
    inContainer(
      'rahdar-qa-kafka-kafka-1-1',
      'kafka-producer-perf-test.sh',
      '--topic',
      'orders',
      '--num-records',
      '500',
      '--record-size',
      '128',
      '--throughput',
      '1000',
      '--producer-props',
      'bootstrap.servers=kafka-1:9092',
    );
    const lag = lagOf('analytics');
    t.check(lag >= 500, `analytics lags ${lag} records on the broker`);
    await t.open('/clusters/:kafka/subscriptions?q=analytics');
    const cell = t.page.getByRole('button', { name: /estimate/ }).first();
    await cell.waitFor({ timeout: 30_000 });
    t.note(
      `backlog button: ${await cell.getAttribute('aria-label')} / ${(await cell.innerText()).replace(/\s+/g, ' ')}`,
    );
    t.check(
      /[≈~]\s*[\d,]+/.test(await t.page.locator('main').innerText()),
      'the backlog is written with an approximation sign',
    );
    await cell.click();
    await t.page.waitForTimeout(500);
    const reason = (await t.page.getByRole('dialog').first().innerText()).replace(/\s+/g, ' ');
    t.note(`reason: ${reason.slice(0, 300)}`);
    t.check(
      /end offset|compaction|transaction markers|overcount/i.test(reason),
      'the popover says why it is an estimate',
    );
    await t.page.getByRole('button', { name: 'Show partitions' }).click();
    await t.page.waitForTimeout(2000);
    const text = (await t.page.getByRole('dialog').first().innerText()).replace(/\s+/g, ' ');
    t.note(`drawer: ${text.slice(0, 500)}`);
    const onBroker = committed('analytics', 'orders');
    const ends = offsetsAt('orders', -1);
    for (const p of Object.keys(onBroker)) {
      const l = ends[Number(p)] - onBroker[Number(p)];
      t.check(
        text.includes(l.toLocaleString('en')),
        `partition ${p} shows the lag ${l.toLocaleString('en')} the broker computes`,
      );
    }
  },
};

const abortTransaction: Flow = {
  id: 'abort',
  title:
    'aborting the hanging transaction releases the partitions it held: a read-committed consumer sees what was written after it',
  run: async (t) => {
    // The brokers run with a stuck coordinator here (compose.isolated*.yaml): a simulation, since a real coordinator
    // aborts a transaction that outlives its timeout.
    // Several markers: at least one hashes to a partition the transaction holds back.
    const markers = Array.from({ length: 9 }, (_, n) => `e2e-marker-${Date.now()}-${n}`);
    const row = (
      (await t.api(`/clusters/${t.cluster}/kafka/transactions`)) as { items: { id: string; hanging: boolean }[] }
    ).items.find((x) => x.id === 'payments-settlement');
    t.check(row?.hanging === true, `payments-settlement is hanging per the API (${JSON.stringify(row)})`);
    t.note(`broker: ${transactionState('payments-settlement')}`);
    for (const marker of markers) produceKeyed('payments', marker, 'written after the transaction began');
    const unseen = markers.filter((m) => !readCommittedKeys('payments').includes(m));
    t.check(
      unseen.length > 0,
      `a read-committed consumer does not see ${unseen.length} of ${markers.length} records written after the open transaction began`,
    );

    await t.open('/clusters/:kafka/kafka/transactions');
    await t.page.getByRole('button', { name: 'Abort payments-settlement' }).click();
    await dialog(t).waitFor();
    await t.page.waitForTimeout(2000);
    const text = (await dialog(t).innerText()).replace(/\s+/g, ' ');
    t.note(`abort dialog: ${text.slice(0, 500)}`);
    t.check(
      /payments-settlement/.test(text) && /payments/.test(text),
      'the dialog names the transaction and the partitions it holds back',
    );
    t.check(/fenced/i.test(text), 'the dialog says the producer is fenced');
    await t.hold(/^Abort transaction payments-settlement$/);
    await t.page.waitForTimeout(4000);
    t.note(`broker after: ${transactionState('payments-settlement')}`);
    t.check(
      /CompleteAbort|Empty|PrepareAbort/.test(transactionState('payments-settlement')),
      'the broker reports the transaction as aborted',
    );
    const seen = readCommittedKeys('payments');
    t.check(
      markers.every((m) => seen.includes(m)),
      'a read-committed consumer now sees every record written after the transaction began',
    );
    const event = (await audit(t)).find((e) => /ABORT/i.test(e.action));
    t.note(`audit: ${JSON.stringify(event)?.slice(0, 400)}`);
    t.check(event?.outcome === 'SUCCESS', `an audit row records the abort (${event?.action}, ${event?.outcome})`);
  },
};

const heldReset: Flow = {
  id: 'held-reset',
  title:
    'with the approval gate on and two approvers, an offset reset by the requester is held, runs only once another approver approves, and then moves the offsets on the broker',
  run: async (t) => {
    const status = (await t.api('/gate/status')) as { approvers: number; quorate: boolean; enforcing: boolean };
    t.check(
      status.enforcing && status.quorate && status.approvers >= 2,
      `the gate is enforcing with ${status.approvers} approvers`,
    );
    produce('orders', 400);
    const before = committed('analytics', 'orders');
    const requester = await t.as('requester');
    await requester.goto(`${BASE}/clusters/${t.cluster}/kafka/groups/analytics`);
    await requester.getByRole('button', { name: 'Reset offsets…' }).click();
    await requester.getByRole('combobox', { name: 'Move to' }).click();
    await requester.getByRole('option', { name: 'The latest offset' }).click();
    await requester.getByRole('button', { name: 'Preview the reset' }).click();
    const confirm = requester.getByRole('dialog').getByRole('button', { name: /^Reset \d+ partitions?$/ });
    await confirm.waitFor();
    await confirm.hover();
    await requester.mouse.down();
    await requester.waitForTimeout(1900);
    await requester.mouse.up();
    await requester.waitForTimeout(2500);
    const toast = (await requester.locator('body').innerText()).replace(/\s+/g, ' ');
    t.note(
      `requester sees: ${toast.slice(toast.search(/approval|held|waits/i) - 80, toast.search(/approval|held|waits/i) + 260)}`,
    );
    t.check(/approval|held/i.test(toast), 'the requester is told the reset awaits approval');
    t.check(
      JSON.stringify(committed('analytics', 'orders')) === JSON.stringify(before),
      'nothing moved on the broker while the request is held',
    );
    await requester.screenshot({ path: '/tmp/claude-1000/e2e/held-reset-requester.png' });

    const list = (await t.api('/held-operations?scope=DECIDABLE&state=HELD&limit=50')) as {
      items?: { id: string; summary: string; state: string }[];
      data?: { id: string; summary: string; state: string }[];
    };
    const rows = list.items ?? list.data ?? [];
    const held =
      rows.find((r) => /analytics/.test(r.summary) && /HELD|PENDING/i.test(r.state)) ??
      rows.find((r) => /analytics/.test(r.summary));
    t.note(`held request: ${JSON.stringify(held)}`);
    t.check(Boolean(held), 'the held request is listed with the group named');
    if (!held) return;
    // The second approver signs in afresh: an approval asks for it.
    const approver = await t.signedIn('qa-approver');
    await approver.goto(`${BASE}/approvals/${held.id}`);
    await approver.waitForTimeout(1500);
    const text = (await approver.locator('main').innerText()).replace(/\s+/g, ' ');
    t.note(`approval page (qa-approver): ${text.slice(0, 500)}`);
    await approver.screenshot({ path: '/tmp/claude-1000/e2e/held-reset-approver.png' });
    await approver
      .getByRole('button', { name: /^Approve/ })
      .first()
      .click();
    const dlg = approver.getByRole('dialog');
    await dlg.waitFor();
    t.note(`approve dialog: ${(await dlg.innerText()).replace(/\s+/g, ' ').slice(0, 300)}`);
    const go = dlg.getByRole('button', { name: /^Approve$/ }).last();
    await go.hover();
    await approver.mouse.down();
    await approver.waitForTimeout(1900);
    await approver.mouse.up();
    await t.page.waitForTimeout(4000);
    const after = committed('analytics', 'orders');
    t.note(`before ${JSON.stringify(before)} after ${JSON.stringify(after)}`);
    t.check(
      JSON.stringify(after) !== JSON.stringify(before),
      'the committed offsets moved on the broker after the approval',
    );
  },
};

const subscriptionPath = (group: string, topic: string) =>
  `/clusters/:kafka/subscriptions/${encodeURIComponent(`kafka.group-topic/${group}/${topic}`)}/messages`;

/** The offset of the first record at or after a time, as the broker answers it. */
const offsetAtTime = (topic: string, partition: number, ms: number): number => {
  const out = kafka('kafka-get-offsets.sh', '--topic', topic, '--partitions', String(partition), '--time', String(ms));
  return Number(out.trim().split(':')[2]);
};

/** The first record the broker's own consumer reads from a partition at an offset: key, value and timestamp. */
const recordAt = (topic: string, partition: number, offset: number) => {
  const out = inContainer(
    'rahdar-qa-kafka-kafka-1-1',
    'kafka-console-consumer.sh',
    '--bootstrap-server',
    'kafka-1:9092',
    '--topic',
    topic,
    '--partition',
    String(partition),
    '--offset',
    String(offset),
    '--max-messages',
    '1',
    '--timeout-ms',
    '15000',
    '--property',
    'print.timestamp=true',
    '--property',
    'print.key=true',
    '--property',
    'print.offset=true',
  );
  const line = out.split('\n').find((l) => l.startsWith('CreateTime')) ?? '';
  const [ts, off, key, ...value] = line.split('\t');
  return {
    ts: Number(ts.replace('CreateTime:', '')),
    off: Number(off.replace('Offset:', '')),
    key,
    value: value.join('\t'),
  };
};

const browse: Flow = {
  id: 'browse',
  title:
    "browsing a group's records by partition, offset and time shows what the broker holds, with the group's own position",
  run: async (t) => {
    await t.open(subscriptionPath('billing', 'orders'));
    const table = t.page.getByRole('grid').or(t.page.getByRole('table')).first();
    await table.waitFor({ timeout: 30_000 });
    await t.page.getByText(/The group stands at offset/).waitFor({ timeout: 30_000 });
    const body = async () => (await t.page.locator('main').innerText()).replace(/\s+/g, ' ');
    const first = await body();
    t.check(/The group stands at offset/.test(first), 'the page says where the group stands on the partition');
    const stands = /The group stands at offset ([\d,]+) on partition 0/.exec(first);
    t.note(`group position on the page: ${stands?.[1]}`);

    // From an offset: the first row is that offset, and its key and size match the broker's own consumer.
    await t.page.getByRole('combobox', { name: 'Start from' }).click();
    await t.page.getByRole('option', { name: 'An offset' }).click();
    await t.page.getByLabel('Offset', { exact: true }).fill('40');
    await t.page.getByRole('button', { name: 'Browse', exact: true }).click();
    await t.page.waitForTimeout(2000);
    const fromOffset = await body();
    const rec = recordAt('orders', 0, 40);
    t.note(`broker offset 40: ${JSON.stringify({ off: rec.off, ts: rec.ts })}`);
    t.check(rec.off === 40, 'the broker holds a record at offset 40 of partition 0');
    t.check(/\b40\b/.test(fromOffset) && !/\b39\b\s+\d{4}-/.test(fromOffset), 'the browse starts at offset 40');

    // From a time: the first row is the offset the broker resolves that time to.
    const at = new Date(rec.ts + 1).toISOString();
    await t.page.getByRole('combobox', { name: 'Start from' }).click();
    await t.page.getByRole('option', { name: 'A time' }).click();
    await t.page.getByLabel('Time (ISO-8601)', { exact: true }).fill(at);
    await t.page.getByRole('button', { name: 'Browse', exact: true }).click();
    await t.page.waitForTimeout(2000);
    const expected = offsetAtTime('orders', 0, rec.ts + 1);
    const fromTime = await body();
    const firstRow = /Offset Written Key Size Body Actions (\d[\d,]*)/.exec(fromTime)?.[1];
    t.note(`a time of ${at}: broker resolves offset ${expected}; page first row ${firstRow}`);
    t.check(
      Number((firstRow ?? '').replace(/,/g, '')) === expected,
      'the first row is the offset the broker resolves that time to',
    );

    // Past the end: said, not an empty table.
    await t.page.getByRole('combobox', { name: 'Start from' }).click();
    await t.page.getByRole('option', { name: 'An offset' }).click();
    await t.page.getByLabel('Offset', { exact: true }).fill('99999999');
    await t.page.getByRole('button', { name: 'Browse', exact: true }).click();
    await t.page.waitForTimeout(1500);
    t.check(/past the end of the partition/.test(await body()), 'an offset past the end is stated');
  },
};

const tombstone: Flow = {
  id: 'tombstone',
  title:
    "a tombstone on a compacted topic is shown as a tombstone, and every per-record action is disabled with the broker kind's reason",
  run: async (t) => {
    const onBroker = inContainer(
      'rahdar-qa-kafka-kafka-1-1',
      'kafka-console-consumer.sh',
      '--bootstrap-server',
      'kafka-1:9092',
      '--topic',
      'customer-state',
      '--from-beginning',
      '--timeout-ms',
      '12000',
      '--property',
      'print.key=true',
    );
    const tomb = onBroker.split('\n').filter((l) => /^c4\tnull/.test(l.trim()));
    t.check(tomb.length > 0, 'the broker holds a tombstone for key c4 (null value)');
    // Find the partition of the c4 tombstone by browsing each partition of the topic.
    await t.open(subscriptionPath('state-reader', 'customer-state'));
    await t.page.getByRole('grid').or(t.page.getByRole('table')).first().waitFor({ timeout: 30_000 });
    let found = false;
    for (let partition = 0; partition < 3 && !found; partition++) {
      await t.page.getByLabel('Partition', { exact: true }).fill(String(partition));
      await t.page.getByRole('button', { name: 'Browse', exact: true }).click();
      await t.page.waitForTimeout(2500);
      const text = (await t.page.locator('main').innerText()).replace(/\s+/g, ' ');
      if (/tombstone/i.test(text)) {
        found = true;
        t.note(
          `tombstone on partition ${partition}: ${text.slice(text.search(/tombstone/i) - 100, text.search(/tombstone/i) + 80)}`,
        );
      }
    }
    t.check(found, 'a record is marked as a tombstone');
    // Per-record actions: open a row's menu; move, retry, delete and expire are disabled with a reason.
    await t.page
      .getByRole('button', { name: /^Actions for message/ })
      .first()
      .click();
    const menu = t.page.getByRole('menu');
    await menu.waitFor();
    const entries = menu.getByRole('menuitem');
    const items: { text: string; disabled: boolean }[] = [];
    for (let n = 0; n < (await entries.count()); n++) {
      const entry = entries.nth(n);
      items.push({
        text: (await entry.innerText()).replace(/\s+/g, ' '),
        disabled: (await entry.getAttribute('aria-disabled')) === 'true' || (await entry.isDisabled()),
      });
    }
    t.note(`record menu: ${JSON.stringify(items)}`);
    const writes = items.filter((i) => /Move|Retry|Delete|Expire|Dead/i.test(i.text));
    t.check(writes.length > 0 && writes.every((i) => i.disabled), 'every move, retry, delete and expire is disabled');
    t.check(
      writes.every((i) => /cannot|append-only|not/i.test(i.text)),
      "each says why in the system's own words",
    );
  },
};

const heldTruncate: Flow = {
  id: 'held-truncate',
  title: 'a truncate by the requester is held, and cuts the log only once the second approver approves',
  run: async (t) => {
    const before = offsetsAt('inventory', -2);
    const requester = await t.as('requester');
    await requester.goto(`${BASE}/clusters/${t.cluster}/destinations`);
    await requester.getByRole('button', { name: 'Actions for inventory' }).first().click();
    await requester.getByRole('menuitem', { name: /Truncate/ }).click();
    const dlg = requester.getByRole('dialog');
    await dlg.getByRole('textbox', { name: /Delete records before/ }).fill('0:60');
    await dlg.getByRole('button', { name: 'Preview the cut' }).click();
    const confirm = dlg.getByRole('button', { name: /^Truncate [\d,]+ records?$/ });
    await confirm.waitFor();
    await confirm.hover();
    await requester.mouse.down();
    await requester.waitForTimeout(1900);
    await requester.mouse.up();
    await requester.waitForTimeout(2500);
    const seen = (await requester.locator('body').innerText()).replace(/\s+/g, ' ');
    t.check(/approval|second person/i.test(seen), 'the requester is told the truncate awaits approval');
    t.check(
      JSON.stringify(offsetsAt('inventory', -2)) === JSON.stringify(before),
      'the log start has not moved while held',
    );
    const list = (await t.api('/held-operations?scope=DECIDABLE&state=HELD&limit=50')) as {
      items: { id: string; summary: string }[];
    };
    const held = list.items.find((r) => /inventory/.test(r.summary));
    t.note(`held request: ${JSON.stringify(held)}`);
    t.check(Boolean(held), 'the held truncate is listed with the topic named');
    if (!held) return;
    const approver = await t.signedIn('qa-approver');
    await approver.goto(`${BASE}/approvals/${held.id}`);
    await approver
      .getByRole('button', { name: /^Approve/ })
      .first()
      .click();
    const ok = approver.getByRole('dialog');
    await ok.waitFor();
    const go = ok.getByRole('button', { name: /^Approve$/ }).last();
    await go.hover();
    await approver.mouse.down();
    await approver.waitForTimeout(1900);
    await approver.mouse.up();
    await approver.waitForTimeout(4000);
    const after = offsetsAt('inventory', -2);
    t.note(`log start before ${JSON.stringify(before)} after ${JSON.stringify(after)}`);
    t.check(after[0] === 60, 'the broker log start of partition 0 is 60 after the approval');
  },
};

const hangingFind: Flow = {
  id: 'hanging-find',
  title:
    'the hanging transactions section looks on demand, and says what it found: the coordinator knows the open transaction, so none is hanging',
  run: async (t) => {
    await t.open('/clusters/:kafka/kafka/transactions');
    await t.page.getByRole('button', { name: 'Find hanging transactions' }).waitFor();
    t.check(
      (await t.page.getByText('Nothing has been looked for yet').count()) === 1,
      'before Find the section says nothing has been looked for, and has not asked the broker',
    );
    const found = t.page.waitForResponse((r) => r.url().includes('hanging-transactions:find'));
    await t.page.getByRole('button', { name: 'Find hanging transactions' }).click();
    const response = await found;
    t.check(response.status() === 200, `Find answers ${response.status()} on OpenBroker 0.7.0`);
    await t.page.waitForTimeout(1200);
    const text = (await t.page.locator('main').innerText()).replace(/\s+/g, ' ');
    t.check(/No hanging transactions/.test(text), 'it says there are no hanging transactions');
    t.note(
      'A transaction the coordinator does not know (KIP-664) could not be created on this stack: the transaction log cannot be truncated (delete-records on __transaction_state is refused as an invalid topic), so only the empty answer was verified live; the found, abort and stale paths are covered by the MSW tests and the sweep stubs.',
    );
  },
};

export const FLOWS: Flow[] = [
  prepare,
  estate,
  register,
  groups,
  resetOffsets,
  resetRefused,
  topicConfig,
  acls,
  truncate,
  purgeGroup,
  bulkPurge,
  backlogDrawer,
  heldReset,
  heldTruncate,
  browse,
  tombstone,
  hangingFind,
  abortTransaction,
];
