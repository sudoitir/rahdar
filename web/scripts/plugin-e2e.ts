/**
 * A plugin's whole life against a running Rahdar, through its public API and its UI (task 10.7):
 * install the template plugin, use its API and its UI bundle, update it with no restart, roll it
 * back, then disable, uninstall and purge it. Rahdar's own CI runs this against a fresh build.
 *
 *   RAHDAR=http://localhost:8080 ADMIN_PASSWORD=… NEW_PASSWORD=… \
 *   JAR_V1=…/acme-notes-1.0.0.jar JAR_V2=…/acme-notes-1.0.1.jar \
 *   node --experimental-strip-types scripts/plugin-e2e.ts
 *
 * ADMIN_PASSWORD is the one Rahdar printed at first start; when the account must still change it,
 * NEW_PASSWORD becomes the password. The ADMIN role requires two-step verification (ADR-0143), so the run
 * then sets up an authenticator app and gives its code whenever Rahdar asks it to confirm it is the admin.
 */
import { chromium, request, type APIRequestContext } from '@playwright/test';
import { readFile } from 'node:fs/promises';
import { randomUUID } from 'node:crypto';

import { nextTotp } from './totp.ts';

const BASE = process.env.RAHDAR ?? 'http://localhost:8080';
const USER = process.env.ADMIN_USER ?? 'admin';
const ID = 'acme-notes';

function need(name: string): string {
  const value = process.env[name];
  if (!value) throw new Error(`set ${name}`);
  return value;
}

function step(message: string) {
  console.log(`→ ${message}`);
}

async function csrf(api: APIRequestContext): Promise<string> {
  const cookies = (await api.storageState()).cookies;
  return cookies.find((c) => c.name === 'XSRF-TOKEN')?.value ?? '';
}

async function call(api: APIRequestContext, method: string, path: string, body?: unknown, raw?: Buffer) {
  const headers: Record<string, string> = { 'X-XSRF-TOKEN': await csrf(api) };
  const response = await api.fetch(`/api/v1${path}`, {
    method,
    headers: raw ? { ...headers, 'content-type': 'application/octet-stream' } : headers,
    data: raw ?? (body === undefined ? undefined : body),
  });
  const text = await response.text();
  return { status: response.status(), body: text ? JSON.parse(text) : undefined };
}

/** Every row of one of Rahdar's lists: the envelope's `data`, at the largest page, which these few rows fit. */
async function list<T>(api: APIRequestContext, path: string): Promise<T[]> {
  const page = (await expectStatus(await call(api, 'GET', `${path}?size=500`), 200, `list ${path}`)) as {
    data: T[];
    hasNext: boolean;
  };
  if (page.hasNext) throw new Error(`${path} has more than one page`);
  return page.data;
}

async function expectStatus(result: { status: number; body: unknown }, want: number, what: string) {
  if (result.status !== want)
    throw new Error(`${what}: expected ${want}, got ${result.status} ${JSON.stringify(result.body)}`);
  return result.body as never;
}

async function awaitPlugin(api: APIRequestContext, want: { status: string; version?: string }) {
  const deadline = Date.now() + 90_000;
  let last: { status?: string; version?: string; failure?: string } = {};
  while (Date.now() < deadline) {
    last = (await call(api, 'GET', `/admin/plugins/${ID}`)).body ?? {};
    if (last.status === 'failed') throw new Error(`${ID} failed: ${last.failure}`);
    if (last.status === want.status && (!want.version || last.version === want.version)) return;
    await new Promise((r) => setTimeout(r, 500));
  }
  throw new Error(`${ID} never reached ${JSON.stringify(want)}; last ${JSON.stringify(last)}`);
}

/** The secret of the authenticator app the run sets up for the admin. */
let totpSecret = '';

async function enrolAuthenticator(api: APIRequestContext) {
  const started = (await expectStatus(await call(api, 'POST', '/auth/mfa/totp'), 200, 'start two-step set-up')) as {
    secret: string;
  };
  totpSecret = started.secret;
  await expectStatus(
    await call(api, 'POST', '/auth/mfa/totp/confirm', { code: await nextTotp(totpSecret) }),
    200,
    'confirm two-step set-up',
  );
}

/** Plugin changes need a sign-in within five minutes (ADR-0103); a slow run confirms again, password then code. */
async function fresh(api: APIRequestContext, password: string) {
  const step = (await expectStatus(
    await call(api, 'POST', '/auth/reauthenticate', { password }),
    200,
    'reauthenticate',
  )) as {
    status: string;
  };
  if (step.status === 'SECOND_FACTOR_REQUIRED') {
    await expectStatus(
      await call(api, 'POST', '/auth/second-factor', { totpCode: await nextTotp(totpSecret) }),
      200,
      'second factor',
    );
  }
}

async function install(api: APIRequestContext, jar: string, expectedClass: string) {
  const upload = (await expectStatus(
    await call(api, 'PUT', '/admin/plugins/upload', undefined, await readFile(jar)),
    201,
    `upload ${jar}`,
  )) as {
    sha256: string;
    plan: { activationClass: string; toVersion: string };
  };
  if (upload.plan.activationClass !== expectedClass) {
    throw new Error(`expected ${expectedClass}, planned ${upload.plan.activationClass}`);
  }
  await expectStatus(await call(api, 'POST', `/admin/plugins/uploads/${upload.sha256}/activate`), 202, 'activate');
  await awaitPlugin(api, { status: 'active', version: upload.plan.toVersion });
  return upload.plan.toVersion;
}

async function main() {
  const api = await request.newContext({ baseURL: BASE });
  let password = need('ADMIN_PASSWORD');

  step('sign in');
  await api.get('/api/v1/auth/providers');
  const signedIn = (await expectStatus(
    await call(api, 'POST', '/auth/login', { username: USER, password }),
    200,
    'login',
  )) as { me: { mustChangePassword: boolean } };
  if (signedIn.me.mustChangePassword) {
    const next = need('NEW_PASSWORD');
    await expectStatus(
      await call(api, 'POST', '/auth/password', { currentPassword: password, newPassword: next }),
      204,
      'change password',
    );
    password = next;
  }
  // The password change keeps the session; the administrator's role requires a second factor before it may do more.
  await enrolAuthenticator(api);

  step("an unknown publisher's jar is refused until an installer trusts its key");
  const untrusted = (await expectStatus(
    await call(api, 'PUT', '/admin/plugins/upload', undefined, await readFile(need('JAR_V1'))),
    201,
    'upload before trusting',
  )) as { sha256: string; plan: { trust: { status: string } } };
  if (untrusted.plan.trust.status !== 'UNTRUSTED') {
    throw new Error(`expected an untrusted signer, planned ${untrusted.plan.trust.status}`);
  }
  await expectStatus(
    await call(api, 'POST', `/admin/plugins/uploads/${untrusted.sha256}/activate`),
    422,
    'activate before trusting',
  );
  await expectStatus(
    await call(api, 'POST', '/admin/plugins/keys', {
      name: 'Template CI',
      pem: await readFile(need('PUBLISHER_CERT'), 'utf8'),
    }),
    201,
    'trust the publisher key',
  );

  step('install 1.0.0 — a new schema, so Brief maintenance');
  await install(api, need('JAR_V1'), 'BRIEF_MAINTENANCE');

  step("use the plugin's own API");
  const cluster = randomUUID();
  await expectStatus(
    await call(api, 'POST', `/clusters/${cluster}/p/${ID}/queues/orders/notes`, { body: 'owned by payments' }),
    201,
    'add a note',
  );
  const notes = (await expectStatus(
    await call(api, 'GET', `/clusters/${cluster}/p/${ID}/queues/orders/notes`),
    200,
    'list notes',
  )) as unknown[];
  if (notes.length !== 1) throw new Error(`expected 1 note, got ${notes.length}`);

  step('its UI bundle is served where the manifest says');
  const manifest = (await call(api, 'GET', '/manifest')).body as { features: { id: string; ui?: { entry: string } }[] };
  const entry = manifest.features.find((f) => f.id === ID)?.ui?.entry;
  if (!entry) throw new Error('the manifest names no UI entry');
  const bundle = await api.get(entry);
  if (bundle.status() !== 200 || !bundle.headers()['content-type']?.startsWith('text/javascript')) {
    throw new Error(`${entry}: ${bundle.status()} ${bundle.headers()['content-type']}`);
  }

  step('the page starts with the plugin loaded');
  const browser = await chromium.launch();
  const page = await browser.newPage({ storageState: await api.storageState(), baseURL: BASE });
  const errors: string[] = [];
  page.on('pageerror', (e) => errors.push(e.message));
  // Rahdar's Content-Security-Policy must not refuse anything Rahdar or the plugin itself loads.
  page.on('console', (m) => {
    if (m.text().includes('Content Security Policy')) errors.push(m.text());
  });
  await page.goto('/admin?tab=plugins');
  await page.getByRole('row', { name: /Notes/ }).getByText('Active').waitFor({ timeout: 30_000 });
  if (await page.getByText(/could not show (its|their) screens/).count()) throw new Error('the plugin UI did not load');
  if (errors.length) throw new Error(`page errors: ${errors.join('; ')}`);

  step("the role editor offers the plugin's permissions, and a role saves them");
  const roleName = `e2e-notes-${randomUUID().slice(0, 8)}`;
  await page.goto('/admin?tab=roles');
  await page.getByRole('button', { name: 'New role' }).click();
  const editor = page.getByRole('dialog');
  await editor.getByRole('textbox', { name: /^Name/ }).fill(roleName);
  await editor.getByRole('textbox', { name: 'Search permissions' }).fill(ID);
  await editor.getByRole('checkbox', { name: 'Select all in Notes' }).check();
  await editor.getByRole('button', { name: 'Create role' }).click();
  await editor.waitFor({ state: 'hidden' });
  const roles = await list<{ id: string; name: string; permissions: string[] }>(api, '/roles');
  const role = roles.find((r) => r.name === roleName);
  // The editor adds what the plugin's permissions require (`subscription:read`), so a role is never saved half-usable.
  if (!role || [...role.permissions].sort().join() !== `${ID}:read,${ID}:write,subscription:read`) {
    throw new Error(`role saved with ${JSON.stringify(role?.permissions)}`);
  }
  await browser.close();

  step('update to 1.0.1 — no database change, so no downtime');
  await fresh(api, password);
  await install(api, need('JAR_V2'), 'INSTANT');

  step('roll back to 1.0.0; the data is still there');
  await expectStatus(await call(api, 'POST', `/admin/plugins/${ID}/rollback`), 202, 'rollback');
  await awaitPlugin(api, { status: 'active', version: '1.0.0' });
  const kept = (await expectStatus(
    await call(api, 'GET', `/clusters/${cluster}/p/${ID}/queues/orders/notes`),
    200,
    'notes after rollback',
  )) as unknown[];
  if (kept.length !== 1) throw new Error('rollback lost data');

  step('disable: its API is gone');
  await fresh(api, password);
  await expectStatus(await call(api, 'POST', `/admin/plugins/${ID}/disable`), 204, 'disable');
  const gone = await call(api, 'GET', `/clusters/${cluster}/p/${ID}/queues/orders/notes`);
  if (gone.status !== 404) throw new Error(`disabled plugin answered ${gone.status}`);

  step('disabled: its permissions leave the catalogue and the role keeps them');
  const catalogue = await list<{ action: string }>(api, '/permissions');
  if (catalogue.some((p) => p.action.startsWith(`${ID}:`)))
    throw new Error('a disabled plugin is still in the catalogue');
  const kept2 = (await list<{ id: string; permissions: string[] }>(api, '/roles')).find((r) => r.id === role.id);
  if (kept2?.permissions.length !== 3) throw new Error(`the role lost its permissions: ${JSON.stringify(kept2)}`);
  await expectStatus(await call(api, 'DELETE', `/roles/${role.id}`), 204, 'delete role');

  step('uninstall, then purge after a dry run');
  await expectStatus(await call(api, 'POST', `/admin/plugins/${ID}/uninstall`), 204, 'uninstall');
  const estimate = (await expectStatus(
    await call(api, 'POST', `/admin/plugins/${ID}/purge?dryRun=true`),
    200,
    'purge dry run',
  )) as {
    tables: { name: string }[];
  };
  if (!estimate.tables.some((t) => t.name === 'note')) throw new Error('the dry run does not name the note table');
  await expectStatus(await call(api, 'POST', `/admin/plugins/${ID}/purge?dryRun=false`), 200, 'purge');
  await expectStatus(await call(api, 'GET', `/admin/plugins/${ID}`), 404, 'purged plugin');

  console.log('✓ plugin lifecycle passed');
}

main().catch((e) => {
  console.error(`✗ ${e instanceof Error ? e.message : String(e)}`);
  process.exit(1);
});
