/**
 * The schema registry's business flows, driven in a real browser against a running stack, the way an
 * operator drives them.
 *
 *   just qa-flows <name> <port>
 *   RAHDAR=http://127.0.0.1:18081 \
 *     node --experimental-strip-types web/scripts/schema-flows.ts
 *
 * `just qa-up` saves the sessions this signs in with (`web/.sweep/auth/`), so run that first. `just qa-flows`
 * is not part of `just qa-up` or CI; it is the recipe that runs this file against that named stack.
 *
 * Each run makes its own subject and topic. A subject is absent when GET …/schemas/{subject} is 404, not when
 * it is missing from the first page of the list. The delete step checks that API, not the rendered table.
 * The binding step always runs its checks: a missing preview dialog fails them. The controller has no binding
 * delete, so the row stays; the next run cannot pass off it, because its topic is this run's stamp.
 */
import { chromium, type APIRequestContext, type Browser, type BrowserContext, type Page } from '@playwright/test';

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
    if (await act()) pass(what);
    else fail(what);
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

const isTimeout = (error: unknown) => error instanceof Error && error.name === 'TimeoutError';

/** POST …/schemas:register, and not the preview, whose path only continues past `:register`. */
const isRegisterPost = (url: string, method: string) =>
  method === 'POST' && /\/kafka\/schemas:register(?:\?|$)/.test(url);

/**
 * True when a register apply is sent. The waiter is installed before the click, then raced against a short
 * timeout that starts once the click has returned: a resolved timeout is "no request", which is the pass.
 */
async function registerWasSent(page: Page, click: () => Promise<void>) {
  const pending = page
    .waitForRequest((request) => isRegisterPost(request.url(), request.method()), { timeout: 8_000 })
    .then(
      () => true,
      (error: unknown) => {
        if (isTimeout(error)) return false;
        throw error;
      },
    );
  await click();
  return Promise.race([pending, new Promise<boolean>((resolve) => setTimeout(() => resolve(false), 1_500))]);
}

/** The next apply call, so an assertion waits on the response, not a clock. A missing response rejects. */
function applyResponse(page: Page, matches: (url: string, method: string) => boolean) {
  return page.waitForResponse((response) => matches(response.url(), response.request().method()));
}

/** An XSRF token for a request made outside the page, taken from the saved session. */
async function xsrf(context: BrowserContext) {
  return (await context.cookies()).find((cookie) => cookie.name === 'XSRF-TOKEN')?.value ?? '';
}

const registry = (cluster: string) => `/api/v1/clusters/${cluster}/kafka/schemas`;

/** Absent is a 404 for this subject, not a name missing from the first page of the list. */
async function exists(request: APIRequestContext, cluster: string, subject: string): Promise<boolean> {
  const response = await request.get(`${BASE}${registry(cluster)}/${encodeURIComponent(subject)}`);
  if (response.status() === 404) return false;
  if (!response.ok()) throw new Error(`subject lookup answered ${response.status()}`);
  return true;
}

async function versions(request: APIRequestContext, cluster: string, subject: string): Promise<number> {
  const body = (await (await request.get(`${BASE}${registry(cluster)}/${encodeURIComponent(subject)}`)).json()) as {
    versions: unknown[];
  };
  return body.versions.length;
}

async function compatibility(request: APIRequestContext, cluster: string, subject: string): Promise<string | null> {
  const body = (await (await request.get(`${BASE}${registry(cluster)}/${encodeURIComponent(subject)}`)).json()) as {
    compatibility?: string | null;
  };
  return body.compatibility ?? null;
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

/**
 * Permanent delete, the way the controller requires it: the preview's etag, then DELETE with If-Match.
 * A subject that is already gone is success.
 */
async function purgeSubject(context: BrowserContext, cluster: string, subject: string): Promise<string> {
  if (!(await exists(context.request, cluster, subject))) return 'already gone';
  const headers = { 'X-XSRF-TOKEN': await xsrf(context) };
  const preview = await context.request.post(
    `${BASE}${registry(cluster)}/${encodeURIComponent(subject)}:delete-preview`,
    { data: { permanent: true }, headers },
  );
  if (preview.status() === 404) return 'already gone';
  if (!preview.ok()) return `delete preview answered ${preview.status()}`;
  const { etag } = (await preview.json()) as { etag?: string };
  if (!etag) return 'delete preview had no etag';
  const removed = await context.request.delete(
    `${BASE}${registry(cluster)}/${encodeURIComponent(subject)}?permanent=true`,
    { headers: { ...headers, 'If-Match': etag } },
  );
  if (removed.status() === 404 || removed.ok()) return 'deleted';
  return `delete answered ${removed.status()}`;
}

/**
 * ponytail: KafkaSchemaController has no binding delete (preview and PUT only). A unique topic is the
 * isolation; call a real delete here when the controller grows one.
 */
async function releaseBinding(context: BrowserContext, cluster: string, topic: string): Promise<string> {
  const listed = await context.request.get(`${BASE}${registry(cluster)}/bindings`);
  if (listed.status() === 404) return 'already gone';
  if (!listed.ok()) return `binding list answered ${listed.status()}`;
  const body = (await listed.json()) as { items: { topic: string }[] };
  const mine = body.items.filter((item) => item.topic === topic);
  if (mine.length === 0) return 'no binding for this run';
  return `left in place: the controller has no binding delete (${mine.length} row for ${topic})`;
}

async function cleanup(what: string, act: () => Promise<string>) {
  try {
    console.log(`  cleanup ${what}: ${await act()}`);
  } catch (error) {
    console.error(`  cleanup ${what} failed: ${(error as Error).message.split('\n')[0]}`);
  }
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
const stamp = Date.now().toString(36).slice(-5);
const subject = `flows-${stamp}-value`;
const topic = `flows-${stamp}`;
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
  await check('the preview created nothing', async () => !(await exists(page.request, cluster, subject)));

  await check(
    'a click does not register',
    async () =>
      !(await registerWasSent(page, () =>
        review.getByRole('button', { name: `Register schema ${subject}`, exact: true }).click(),
      )) && !(await exists(page.request, cluster, subject)),
  );

  const applied = applyResponse(page, isRegisterPost);
  await hold(page, `Register schema ${subject}`);
  await check('the register was answered by the server', async () => (await applied).ok());
  await check('the subject now exists', async () => exists(page.request, cluster, subject));
  await check('it has one version', async () => (await versions(page.request, cluster, subject)) === 1);
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
  const again = applyResponse(page, isRegisterPost);
  await hold(page, `Register schema ${subject}`);
  await check('the idempotent apply was accepted and wrote nothing new', async () => {
    if (!(await again).ok()) return false;
    return (await versions(page.request, cluster, subject)) === 1;
  });

  // -------------------------------------------------------------------------------------------------------
  step('change compatibility from the subject page');
  const before = await compatibility(page.request, cluster, subject);
  const next = before === 'FULL' ? 'BACKWARD' : 'FULL';
  await openSubject(page, cluster, subject);
  await button(page, 'Set compatibility').click();
  await dialog(page).getByRole('combobox', { name: 'Compatibility' }).click();
  await page.getByRole('option', { name: next, exact: true }).click();
  await button(page, 'Preview compatibility').click();
  const levelReview = dialog(page);
  await levelReview.getByRole('button', { name: `Set compatibility of ${subject}`, exact: true }).waitFor();
  await check('the review says nothing has been written', async () =>
    ((await levelReview.textContent()) ?? '').includes('Nothing has been written.'),
  );
  const levelled = applyResponse(
    page,
    (url, method) => method === 'POST' && /\/kafka\/schemas\/[^/?]+:compatibility(?:\?|$)/.test(url),
  );
  await hold(page, `Set compatibility of ${subject}`);
  await check('the compatibility apply was accepted', async () => (await levelled).ok());
  await check('the level changed from the one the API had', async () => {
    const now = await compatibility(page.request, cluster, subject);
    return now === next && now !== before;
  });
  await openSubject(page, cluster, subject);
  await check('the subject page shows the new level', async () => {
    const shown = page.getByRole('main').getByText(next, { exact: true });
    try {
      await shown.first().waitFor({ timeout: 5_000 });
      return true;
    } catch (error) {
      if (!isTimeout(error)) throw error;
      const text = ((await page.getByRole('main').textContent()) ?? '').replace(/\s+/g, ' ').slice(0, 180);
      throw new Error(`not on the page yet: ${text}`, { cause: error });
    }
  });

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
  await check('the review is now stale', async () => (await versions(page.request, cluster, subject)) === 2);
  const refused = applyResponse(page, isRegisterPost);
  await hold(page, `Register schema ${subject}`);
  await check('the stale apply was refused as stale', async () => (await refused).status() === 412);
  await check('the stale apply wrote nothing', async () => (await versions(page.request, cluster, subject)) === 2);
  await check('the review says the registry moved', async () =>
    ((await dialog(page).textContent()) ?? '').includes('The registry moved since this preview'),
  );
  await button(page, 'Cancel').click();

  // -------------------------------------------------------------------------------------------------------
  step('bind a serde to a topic');
  await openSubjects(page, cluster);
  await page.getByRole('link', { name: 'Bindings' }).click();
  await page.getByRole('textbox', { name: 'Topic' }).fill(topic);
  await button(page, 'Save binding').click();
  const save = dialog(page).getByRole('button', { name: `Save binding for ${topic} value`, exact: true });
  const previewOpened = await save.waitFor({ timeout: 5_000 }).then(
    () => true,
    (error: unknown) => {
      if (isTimeout(error)) return false;
      throw error;
    },
  );
  await check('saving a binding previews it first', async () => previewOpened);
  await check('the binding is stored', async () => {
    if (!previewOpened) return false;
    const bound = applyResponse(
      page,
      (url, method) => method === 'PUT' && /\/kafka\/schemas\/bindings(?:\?|$)/.test(url),
    );
    await hold(page, `Save binding for ${topic} value`);
    if (!(await bound).ok()) return false;
    const saved = (await (await page.request.get(`${BASE}${registry(cluster)}/bindings`)).json()) as {
      items: { topic: string; side: string; serde: string }[];
    };
    return saved.items.some((item) => item.topic === topic && item.side === 'value' && item.serde === 'avro');
  });
  await cleanup('binding', () => releaseBinding(admin, cluster, topic));

  // -------------------------------------------------------------------------------------------------------
  step('delete the subject permanently');
  await openSubject(page, cluster, subject);
  await button(page, 'Permanently delete').click();
  const deleteReview = dialog(page);
  await deleteReview.getByRole('button', { name: `Permanently delete subject ${subject}`, exact: true }).waitFor();
  await check('the review says the schemas cannot be restored', async () =>
    ((await deleteReview.textContent()) ?? '').includes('cannot be restored'),
  );
  const removed = applyResponse(
    page,
    (url, method) => method === 'DELETE' && url.includes(`/schemas/${encodeURIComponent(subject)}`),
  );
  await hold(page, `Permanently delete subject ${subject}`);
  await check('the delete was answered', async () => (await removed).status() < 300);
  await check('the subject is gone', async () => !(await exists(page.request, cluster, subject)));

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
  try {
    await cleanup('subject', () => purgeSubject(admin, cluster, subject));
    await cleanup('binding', () => releaseBinding(admin, cluster, topic));
    await admin.close();
    await browser.close();
  } catch (error) {
    console.error(`  cleanup failed: ${(error as Error).message.split('\n')[0]}`);
  }
}

console.log(failures ? `\n${failures} flow check(s) failed` : '\nevery flow check passed');
process.exit(failures ? 1 : 0);
