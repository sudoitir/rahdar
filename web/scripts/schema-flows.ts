/**
 * The schema registry's business flows, driven in a real browser against a running stack, the way an
 * operator drives them.
 *
 *   RAHDAR=http://127.0.0.1:18081 \
 *     node --experimental-strip-types web/scripts/schema-flows.ts
 *
 * The screenshot sweep proves a page renders; this proves the flow behind it acts. Every step asserts the
 * outcome an operator would look for (the subject in the list, the version count, the level that changed,
 * the row that went away), never a toast.
 *
 * It signs in with the sessions `just qa-up` saved to `web/.sweep/auth/`, so run that first. It makes its
 * own subject and deletes it at the end.
 */
import { chromium, type Browser, type BrowserContext, type Page } from '@playwright/test';

import { BASE } from './session.ts';

let failures = 0;
const step = (name: string) => console.log(`\n→ ${name}`);
const pass = (what: string) => console.log(`  ✓ ${what}`);
function fail(what: string) {
  failures++;
  console.error(`  ✗ ${what}`);
}

async function check(what: string, act: () => Promise<boolean>) {
  try {
    (await act()) ? pass(what) : fail(what);
  } catch (error) {
    fail(`${what}: ${(error as Error).message.split('\n')[0]}`);
  }
}

const button = (page: Page, name: string) => page.getByRole('button', { name, exact: true });

/**
 * The topmost dialog. Mantine keeps a closing dialog mounted through its exit, so for a moment after one
 * closes to open another there are two; the last in the DOM is the one the operator is looking at.
 */
const dialog = (page: Page) => page.getByRole('dialog').last();

/** Waits for a dialog's exit to finish, so the next step sees exactly one. */
const settled = async (page: Page) => {
  for (let wait = 0; wait < 40; wait++) {
    if ((await page.getByRole('dialog').count()) <= 1) return;
    await page.waitForTimeout(50);
  }
};

/** Presses and holds a confirm until it fires, as an operator's finger does. A click does not arm it. */
async function hold(page: Page, name: string) {
  const target = dialog(page).getByRole('button', { name, exact: true });
  await target.scrollIntoViewIfNeeded();
  const box = await target.boundingBox();
  if (!box) throw new Error(`the hold button "${name}" has no box`);
  await page.mouse.move(box.x + box.width / 2, box.y + box.height / 2);
  await page.mouse.down();
  await page.waitForTimeout(2_000);
  await page.mouse.up();
}

/** An XSRF token for a request made outside the page, taken from the saved session. */
async function xsrf(context: BrowserContext) {
  return (await context.cookies()).find((cookie) => cookie.name === 'XSRF-TOKEN')?.value ?? '';
}

const registry = (cluster: string) => `/api/v1/clusters/${cluster}/kafka/schemas`;

async function subjects(page: Page, cluster: string): Promise<string[]> {
  const body = (await (await page.request.get(`${BASE}${registry(cluster)}`)).json()) as {
    items: { name: string }[];
  };
  return body.items.map((item) => item.name);
}

async function versions(page: Page, cluster: string, subject: string): Promise<number> {
  const body = (await (await page.request.get(`${BASE}${registry(cluster)}/${subject}`)).json()) as {
    versions: unknown[];
  };
  return body.versions.length;
}

/** Registers a version through the API, as another operator's window would. Preview first, then apply its etag. */
async function registerOutside(context: BrowserContext, cluster: string, subject: string, schema: string) {
  const data = { subject, type: 'AVRO', schema };
  const headers = { 'X-XSRF-TOKEN': await xsrf(context) };
  const preview = await context.request.post(`${BASE}${registry(cluster)}:register-preview`, { data, headers });
  const { etag } = (await preview.json()) as { etag: string };
  return context.request.post(`${BASE}${registry(cluster)}:register`, {
    data: { ...data, etag },
    headers,
  });
}

/** The schema text sits behind a disclosure, closed by default; open it, then type. */
async function enterSchema(page: Page, text: string) {
  await button(page, 'Schema text').click();
  await dialog(page).getByRole('textbox', { name: 'Schema' }).fill(text);
}

async function openSubjects(page: Page, cluster: string) {
  await page.goto(`${BASE}/clusters/${cluster}/kafka/schemas`);
  await page.getByRole('heading', { level: 1, name: 'Schemas' }).waitFor();
}

async function openSubject(page: Page, cluster: string, subject: string) {
  await page.goto(`${BASE}/clusters/${cluster}/kafka/schemas/${subject}`);
  await page.getByRole('heading', { level: 1, name: subject }).waitFor();
}

const browser: Browser = await chromium.launch();
const admin = await browser.newContext({ storageState: 'web/.sweep/auth/admin.json', baseURL: BASE });
const page = await admin.newPage();

await page.goto(`${BASE}/clusters`);
if (page.url().includes('/login')) {
  console.error('the saved admin session has expired: regenerate it with web/scripts/sweep/auth.ts');
  await browser.close();
  process.exit(1);
}

// The list answers the system as an object (`{id: "kafka"}`), not a bare id.
const clusters = (await (await page.request.get(`${BASE}/api/v1/clusters?size=500`)).json()) as {
  data: { id: string; name: string; system: { id: string } }[];
};
const kafka = clusters.data.find((cluster) => cluster.system.id === 'kafka');
if (!kafka) {
  console.error('no Kafka cluster is registered on this stack');
  await browser.close();
  process.exit(1);
}
const cluster = kafka.id;
const subject = `flows-${Date.now().toString(36).slice(-5)}-value`;
const schema = '{"type":"record","name":"Flow","fields":[{"name":"id","type":"string"}]}';
const schemaV2 = `{"type":"record","name":"Flow","fields":[{"name":"id","type":"string"},{"name":"at","type":"string","default":""}]}`;

try {
  // -------------------------------------------------------------------------------------------------------
  step('register a schema by holding the confirm');
  await openSubjects(page, cluster);
  await button(page, 'Register a schema').click();
  await dialog(page).getByRole('textbox', { name: 'Subject' }).fill(subject);
  await enterSchema(page, schema);
  await button(page, 'Preview registration').click();
  const review = dialog(page);
  await review.getByRole('button', { name: `Register schema ${subject}`, exact: true }).waitFor();

  await check('the review says nothing has been written', async () =>
    (await review.textContent())!.includes('Nothing has been written.'),
  );
  await check('the preview created nothing', async () => !(await subjects(page, cluster)).includes(subject));

  await review.getByRole('button', { name: `Register schema ${subject}`, exact: true }).click();
  await page.waitForTimeout(400);
  await check('a click does not register', async () => !(await subjects(page, cluster)).includes(subject));

  await hold(page, `Register schema ${subject}`);
  await page.waitForTimeout(1_500);
  await settled(page);
  await check('the subject now exists', async () => (await subjects(page, cluster)).includes(subject));
  await check('it has one version', async () => (await versions(page, cluster, subject)) === 1);
  await check(
    'the list shows it',
    async () => (await page.getByRole('row', { name: new RegExp(`^${subject}\\b`) }).count()) > 0,
  );

  // -------------------------------------------------------------------------------------------------------
  step('registering the same schema again writes no second version');
  await openSubjects(page, cluster);
  await button(page, 'Register a schema').click();
  await dialog(page).getByRole('textbox', { name: 'Subject' }).fill(subject);
  await enterSchema(page, schema);
  await button(page, 'Preview registration').click();
  await dialog(page)
    .getByRole('button', { name: `Register schema ${subject}`, exact: true })
    .waitFor();
  await check('the review says it is already registered', async () =>
    /already registered/i.test((await dialog(page).textContent()) ?? ''),
  );
  await hold(page, `Register schema ${subject}`);
  await page.waitForTimeout(1_500);
  await check('no second version was written', async () => (await versions(page, cluster, subject)) === 1);

  // -------------------------------------------------------------------------------------------------------
  step('change compatibility from the subject page');
  await openSubject(page, cluster, subject);
  await button(page, 'Set compatibility').click();
  await dialog(page).getByRole('combobox', { name: 'Compatibility' }).click();
  await page.getByRole('option', { name: 'FULL', exact: true }).click();
  await button(page, 'Preview compatibility').click();
  await page.waitForTimeout(1_000);
  await check('the review names the new level', async () =>
    ((await dialog(page).textContent()) ?? '').includes('FULL'),
  );
  await hold(page, `Set compatibility of ${subject}`);
  await page.waitForTimeout(1_500);
  await openSubject(page, cluster, subject);
  await check('the subject now says FULL', async () =>
    ((await page.getByRole('main').textContent()) ?? '').includes('FULL'),
  );

  // -------------------------------------------------------------------------------------------------------
  step('a stale preview writes nothing');
  await openSubjects(page, cluster);
  await button(page, 'Register a schema').click();
  await dialog(page).getByRole('textbox', { name: 'Subject' }).fill(subject);
  await enterSchema(page, schemaV2);
  await button(page, 'Preview registration').click();
  await dialog(page)
    .getByRole('button', { name: `Register schema ${subject}`, exact: true })
    .waitFor();
  // Another window registers a version while this review is open.
  const raced = await registerOutside(admin, cluster, subject, schemaV2);
  await check('the other window wrote the second version', async () => raced.status() < 300);
  await check('the review is now stale', async () => (await versions(page, cluster, subject)) === 2);
  await hold(page, `Register schema ${subject}`);
  await page.waitForTimeout(1_500);
  await check('the stale apply wrote nothing', async () => (await versions(page, cluster, subject)) === 2);
  await check('the review says the registry moved', async () =>
    ((await dialog(page).textContent()) ?? '').includes('The registry moved since this preview'),
  );
  await button(page, 'Cancel').click();

  // -------------------------------------------------------------------------------------------------------
  step('bind a serde to a topic');
  await openSubjects(page, cluster);
  await page.getByRole('link', { name: 'Bindings' }).click();
  await page.getByRole('textbox', { name: 'Topic' }).fill('orders');
  await button(page, 'Save binding').click();
  await page.waitForTimeout(1_000);
  await check('saving a binding previews it first', async () => (await page.getByRole('dialog').count()) > 0);
  if (await page.getByRole('dialog').count()) {
    await hold(page, 'Save binding for orders value');
    await page.waitForTimeout(1_500);
    const saved = (await (await page.request.get(`${BASE}${registry(cluster)}/bindings`)).json()) as {
      items: { topic: string; side: string; serde: string }[];
    };
    await check('the binding is stored', async () =>
      saved.items.some((item) => item.topic === 'orders' && item.side === 'value' && item.serde === 'avro'),
    );
  }

  // -------------------------------------------------------------------------------------------------------
  step('delete the subject permanently');
  await openSubject(page, cluster, subject);
  await button(page, 'Permanently delete').click();
  await page.waitForTimeout(1_000);
  await check('the review says the schemas cannot be restored', async () =>
    ((await dialog(page).textContent()) ?? '').includes('cannot be restored'),
  );
  await hold(page, `Permanently delete subject ${subject}`);
  await page.waitForTimeout(1_500);
  await check('the subject is gone', async () => !(await subjects(page, cluster)).includes(subject));

  // -------------------------------------------------------------------------------------------------------
  step('a read-only account sees the actions disabled, naming the permission');
  const reader = await browser.newContext({ storageState: 'web/.sweep/auth/reader.json', baseURL: BASE });
  const readerPage = await reader.newPage();
  await openSubjects(readerPage, cluster);
  await check(
    'the register button is there, not missing',
    async () => (await button(readerPage, 'Register a schema').count()) === 1,
  );
  await check('and it is disabled', async () => button(readerPage, 'Register a schema').isDisabled());
  // The reason hangs off a focusable "Why?" beside the disabled control, not on the page as text.
  await button(readerPage, 'Why registering a schema is unavailable').click();
  // The explanation is a popover, which is portalled outside the page's `main`.
  await check('the reason names schema:write', async () =>
    ((await readerPage.getByRole('dialog').last().textContent()) ?? '').includes('schema:write'),
  );
  await reader.close();
} finally {
  await page.request.delete(`${BASE}${registry(cluster)}/${subject}`, {
    headers: { 'X-XSRF-TOKEN': await xsrf(admin) },
  });
  await admin.close();
  await browser.close();
}

console.log(failures ? `\n${failures} flow check(s) failed` : '\nevery flow check passed');
process.exit(failures ? 1 : 0);
