/**
 * Photograph and check every route of a running, seeded Rahdar, in every colour scheme, width and state.
 *
 *   RAHDAR=http://127.0.0.1:18080 npm --prefix web run sweep -- --label before
 *   … run sweep -- --label after --only subscriptions,admin --states default,error --widths 1280,zoom
 *
 * Needs the sessions `just qa-up` saved to `web/.sweep/auth` and the content `scripts/qa-seed.sh` made. Writes
 * `web/.sweep/<label>/<area>/<id>/<width>-<scheme>-<state>.png` and `web/.sweep/<label>/report.json`, one entry
 * per capture with the result of every check, and prints a summary. Exits 1 when any capture failed a check, or
 * when a signal stopped it (SIGINT or SIGTERM; the captures taken are still reported).
 *
 * The matrix (a full run is a few thousand captures: narrow it with the flags, and run one sweep at a time):
 *   widths    1920x1080, 1440x900, 1280x800, each light and dark; `system` once at 1440; `zoom` (below), light.
 *   states    default, loading, error, empty, filtered-empty, forbidden, where the route has them (routes.ts),
 *             and its scenes: states reached by acting on the page (a check run, a dialog opened). A scene is
 *             captured when `--states` is absent, or names it or `scenes`.
 *   flags     --label NAME  --only AREA,…  --states STATE,…  --widths 1920,1440,1280,zoom  --workers N
 *
 * Checks on every capture: axe (WCAG 2.2 AA), sideways overflow of the page and of each grid or table,
 * layout shift from navigation start, policy violations, console errors, failed requests, requests to
 * another origin, and a redirect to the login screen.
 */
import AxeBuilder from '@axe-core/playwright';
import { chromium, type Browser, type BrowserContext, type Page } from '@playwright/test';
import { existsSync } from 'node:fs';
import { mkdir, readFile, writeFile } from 'node:fs/promises';
import { dirname, relative, resolve } from 'node:path';
import { parseArgs } from 'node:util';
import { fileURLToPath } from 'node:url';
import { BASE } from './session.ts';
import { BUSY, INIT_PROBES, MEASURE, storeScheme, type Measure } from './sweep/probes.ts';
import { ROUTES, type DataCall, type RouteSpec, type Scene } from './sweep/routes.ts';

const WEB = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const AUTH = resolve(WEB, '.sweep/auth');

/**
 * `zoom` is 200% browser zoom at 1280x800. Zooming a page shrinks the layout viewport (1280 CSS px become
 * 640, 800 become 400) and doubles the device pixel ratio, so media queries, container widths and wrapping
 * all see 640x400. That is what a 640x400 viewport with `deviceScaleFactor: 2` reproduces. `deviceScaleFactor`
 * alone would not: it keeps the 1280 px layout and only sharpens it. CSS `zoom` on the body would not either,
 * because the viewport the queries read stays 1280 px.
 */
const VIEWPORTS = {
  '1920': { width: 1920, height: 1080, deviceScaleFactor: 1, schemes: ['light', 'dark'] },
  '1440': { width: 1440, height: 900, deviceScaleFactor: 1, schemes: ['light', 'dark', 'system'] },
  '1280': { width: 1280, height: 800, deviceScaleFactor: 1, schemes: ['light', 'dark'] },
  zoom: { width: 640, height: 400, deviceScaleFactor: 2, schemes: ['light', 'dark'] },
} as const;
type Width = keyof typeof VIEWPORTS;
type Scheme = 'light' | 'dark' | 'system';

const STATES = ['default', 'loading', 'error', 'empty', 'filtered-empty', 'forbidden'] as const;
type State = (typeof STATES)[number];

/** Axe's WCAG tags; `wcag22aa` brings in the target-size rule. */
const AXE_TAGS = ['wcag2a', 'wcag2aa', 'wcag21aa', 'wcag22aa'];
/** The layout shift one route may add; the loading-to-content swap counts. */
const CLS_BUDGET = 0.01;
/** The calls the sweep never answers: the session, the manifest and the event stream. */
const NEVER_STUBBED = /^\/api\/v1\/(auth\/me|me|manifest|stream)$/;
const SETTLE_MS = 12_000;
/** After a 503 the app retries with backoff before it gives up and shows the error. */
const SETTLE_ERROR_MS = 30_000;

const { values: flags } = parseArgs({
  options: {
    label: { type: 'string', default: new Date().toISOString().replaceAll(/[:.]/g, '-') },
    only: { type: 'string' },
    states: { type: 'string' },
    widths: { type: 'string' },
    workers: { type: 'string', default: '2' },
  },
});
const list = (value: string | undefined) => (value ? value.split(',').map((v) => v.trim()) : undefined);
const only = list(flags.only);
const askedStates = list(flags.states);
const wantedStates = (askedStates?.filter((s) => s !== 'scenes' && STATES.includes(s as State)) as
  State[] | undefined) ?? [...STATES];
const wantsScene = ({ id }: Scene) => !askedStates || askedStates.includes('scenes') || askedStates.includes(id);
const wantedWidths = (list(flags.widths) as Width[] | undefined) ?? (Object.keys(VIEWPORTS) as Width[]);
const OUT = resolve(WEB, '.sweep', flags.label);

for (const route of ROUTES) {
  for (const call of route.data ?? []) {
    if (NEVER_STUBBED.test(`/api/v1${call.path}`)) throw new Error(`${route.id} would answer ${call.path}`);
  }
}
const sceneIds = new Set(ROUTES.flatMap((route) => (route.scenes ?? []).map((scene) => scene.id)));
for (const wanted of askedStates ?? []) {
  if (wanted !== 'scenes' && !STATES.includes(wanted as State) && !sceneIds.has(wanted)) {
    throw new Error(`unknown state: ${wanted}`);
  }
}
for (const wanted of wantedWidths) if (!(wanted in VIEWPORTS)) throw new Error(`unknown width: ${wanted}`);

type Auth = 'admin' | 'reader' | 'requester' | 'none';
interface Job {
  route: RouteSpec;
  width: Width;
  scheme: Scheme;
  /** A scene's id when `scene` is set. */
  state: string;
  scene?: Scene;
  auth: Auth;
}

/** The captures a route has: its states, in the widths and schemes asked for. */
function jobsFor(route: RouteSpec): Job[] {
  const anonymous = route.auth === 'none';
  const has: Record<State, boolean> = {
    default: true,
    loading: !anonymous && !!route.data?.length,
    error: !anonymous && !!route.data?.length,
    empty: !anonymous && !!route.data?.some((call) => call.empty !== undefined),
    'filtered-empty': !anonymous && !!route.filter,
    forbidden: !anonymous && !!route.forbidden,
  };
  const jobs: Job[] = [];
  for (const state of wantedStates.filter((s) => has[s])) {
    for (const width of wantedWidths) {
      for (const scheme of VIEWPORTS[width].schemes) {
        jobs.push({
          route,
          width,
          scheme,
          state,
          auth: anonymous ? 'none' : state === 'forbidden' ? 'reader' : (route.auth ?? 'admin'),
        });
      }
    }
  }
  for (const scene of (route.scenes ?? []).filter(wantsScene)) {
    for (const width of wantedWidths) {
      for (const scheme of VIEWPORTS[width].schemes) {
        jobs.push({ route, width, scheme, state: scene.id, scene, auth: scene.auth ?? route.auth ?? 'admin' });
      }
    }
  }
  return jobs;
}

/** The saved session without its local storage, so the colour scheme in a capture is only ever the one set for it. */
async function sessionOf(auth: Auth) {
  if (auth === 'none') return { cookies: [], origins: [] };
  const file = resolve(AUTH, `${auth}.json`);
  if (!existsSync(file)) throw new Error(`no saved session ${relative(WEB, file)}: run just qa-up first`);
  return { ...JSON.parse(await readFile(file, 'utf8')), origins: [] };
}

async function contextFor(browser: Browser, { width, scheme, auth }: Job): Promise<BrowserContext> {
  const viewport = VIEWPORTS[width];
  const context = await browser.newContext({
    viewport: { width: viewport.width, height: viewport.height },
    deviceScaleFactor: viewport.deviceScaleFactor,
    // `system` is the browser's own preference with nothing stored: the app has to follow it, or say it does not.
    colorScheme: scheme === 'system' ? 'light' : scheme,
    storageState: await sessionOf(auth),
  });
  await context.addInitScript({ content: INIT_PROBES });
  if (scheme !== 'system') await context.addInitScript({ content: storeScheme(scheme) });
  return context;
}

/** The pattern of one `DataCall`, as a test on a request's path. */
const matcher = (call: DataCall, clusterId: string) =>
  new RegExp(`^/api/v1${ids(call.path, clusterId).replaceAll('*', '[^/]+')}$`);

/** The Kafka cluster's id (`events`), for the routes that name it as `:kafka`; empty on a stack with none. */
let kafkaCluster = '';

/** A path with the seeded clusters' ids in it: `:cluster` is the Artemis one, `:kafka` the Kafka one. */
const ids = (path: string, clusterId: string) =>
  path.replaceAll(':cluster', clusterId).replaceAll(':kafka', kafkaCluster);

type Answer = 'hang' | 'error' | 'empty';

/** Answers the route's own data calls: never, with a 503 problem document, or with their empty shape. */
async function answer(page: Page, route: RouteSpec, mode: Answer, clusterId: string, answered: Set<string>) {
  const calls = (route.data ?? []).filter((call) => mode !== 'empty' || call.empty !== undefined);
  const tests = calls.map((call) => ({ call, test: matcher(call, clusterId) }));
  await page.route(
    (url) => url.pathname.startsWith('/api/v1/'),
    async (request) => {
      const url = new URL(request.request().url());
      const hit = tests.find(({ test }) => test.test(url.pathname));
      if (!hit || request.request().method() !== 'GET') return request.fallback();
      answered.add(request.request().url());
      if (mode === 'hang') return; // never fulfilled: the view stays in its loading state
      if (mode === 'error') {
        return request.fulfill({
          status: 503,
          contentType: 'application/problem+json',
          body: JSON.stringify({
            type: 'about:blank',
            title: 'Service Unavailable',
            status: 503,
            detail: 'Simulated by the sweep.',
          }),
        });
      }
      return request.fulfill({
        status: 200,
        contentType: 'application/json',
        body: ids(JSON.stringify(hit.call.empty), clusterId),
      });
    },
  );
}

/**
 * Whether nothing on the page says it is loading within `timeout`. The check runs through
 * `page.evaluate`, which the DevTools protocol runs outside the page's Content-Security-Policy.
 * `page.waitForFunction` with a string predicate evaluates it with `eval` inside the page whenever
 * the page is still busy at the first look, which the policy blocks, so it was never settled and
 * the sweep recorded a script-src violation the console does not have.
 */
async function idle(page: Page, timeout: number): Promise<boolean> {
  const deadline = Date.now() + timeout;
  while (Date.now() < deadline) {
    if (!(await page.evaluate(BUSY))) return true;
    await page.waitForTimeout(100);
  }
  return false;
}

async function settle(page: Page, state: State) {
  await page.waitForLoadState('load');
  if (state === 'loading') {
    await page.waitForTimeout(1500);
    return true;
  }
  const settled = await idle(page, state === 'error' ? SETTLE_ERROR_MS : SETTLE_MS);
  await page.evaluate('document.fonts.ready.then(() => true)');
  await page.waitForTimeout(500);
  return settled;
}

interface Capture {
  area: string;
  /** How many times the capture was taken again because the machine's network changed under it. */
  retries?: number;
  id: string;
  path: string;
  width: Width;
  scheme: Scheme;
  state: string;
  auth: Auth;
  png: string;
  ok: boolean;
  /** The names of the checks this capture failed. */
  failures: string[];
  settled?: boolean;
  finalPath?: string;
  appliedScheme?: string | null;
  cls?: number;
  layoutShifts?: number;
  shiftSources?: { value: number; at: number; node: string; moved: number[] }[];
  pageOverflow?: Measure['pageOverflow'];
  scrollerOverflow?: Measure['scrollers'];
  csp?: Measure['csp'];
  consoleErrors?: string[];
  failedRequests?: { url: string; status?: number; error?: string }[];
  foreignRequests?: string[];
  axe?: { id: string; impact: string | null | undefined; help: string; nodes: number; targets: string[] }[];
  error?: string;
}

async function capture(context: BrowserContext, job: Job, clusterId: string): Promise<Capture> {
  const { route, width, scheme, state, auth, scene } = job;
  const file = `${width === 'zoom' ? 'zoom200' : width}-${scheme}-${state}.png`;
  const png = resolve(OUT, route.area, route.id, file);
  const path =
    ids(route.path, clusterId).replaceAll(/:held:([A-Z]+)/g, (_, held: string) => heldIds.get(held) ?? held) +
    (state === 'filtered-empty' ? (route.filter ?? '') : '');
  const result: Capture = {
    area: route.area,
    id: route.id,
    path,
    width,
    scheme,
    state,
    auth,
    png: relative(WEB, png),
    ok: false,
    failures: [],
  };

  const page = await context.newPage();
  const consoleErrors: { text: string; url: string }[] = [];
  const failed: NonNullable<Capture['failedRequests']> = [];
  const foreign = new Set<string>();
  const answered = new Set<string>();
  const origin = new URL(BASE).origin;
  page.on('console', (message) => {
    if (message.type() === 'error') consoleErrors.push({ text: message.text(), url: message.location().url });
  });
  page.on('pageerror', (error) => consoleErrors.push({ text: `uncaught: ${error.message}`, url: '' }));
  page.on('requestfailed', (request) => {
    const error = request.failure()?.errorText ?? 'failed';
    if (error !== 'net::ERR_ABORTED') failed.push({ url: request.url(), error });
  });
  page.on('response', (response) => {
    if (response.status() >= 400) failed.push({ url: response.url(), status: response.status() });
  });
  page.on('request', (request) => {
    const url = new URL(request.url());
    if (/^https?:$/.test(url.protocol) && url.origin !== origin) foreign.add(request.url());
  });

  try {
    if (state === 'loading') await answer(page, route, 'hang', clusterId, answered);
    if (state === 'error') await answer(page, route, 'error', clusterId, answered);
    if (state === 'empty') await answer(page, route, 'empty', clusterId, answered);

    await page.goto(`${BASE}${path}`);
    result.settled = await settle(page, scene ? 'default' : (state as State));
    if (scene) {
      await scene.act(page);
      result.settled = (await idle(page, SETTLE_MS)) && result.settled;
      await page.evaluate('document.fonts.ready.then(() => true)');
      // Pressing a control scrolls the window to it, and a full-page capture of a scrolled window draws the
      // sticky header in the middle of the page.
      await page.evaluate('window.scrollTo(0, 0)');
      await page.waitForTimeout(500);
    }
    const measured = JSON.parse(JSON.stringify(await page.evaluate(MEASURE))) as Measure;
    result.finalPath = new URL(page.url()).pathname;
    result.appliedScheme = measured.scheme;
    result.cls = Number(measured.cls.toFixed(4));
    result.layoutShifts = measured.shifts;
    result.shiftSources = measured.shiftSources;
    result.pageOverflow = measured.pageOverflow;
    result.scrollerOverflow = measured.scrollers;
    result.csp = measured.csp;

    // The answers the sweep gave, and the 403s a read-only account is meant to get, are not findings.
    const expected = (r: { url: string; status?: number }) =>
      answered.has(r.url) ||
      (state === 'forbidden' && r.status === 403) ||
      (r.status !== undefined &&
        [...(route.expectedStatus ?? []), ...(scene?.expectedStatus ?? [])].includes(r.status));
    result.failedRequests = failed.filter((r) => !expected(r));
    const expectedUrls = new Set(failed.filter(expected).map((r) => r.url));
    result.consoleErrors = consoleErrors
      .filter((e) => !expectedUrls.has(e.url) && !answered.has(e.url))
      .map((e) => e.text.slice(0, 300));
    result.foreignRequests = [...foreign];

    const axe = await new AxeBuilder({ page }).withTags(AXE_TAGS).analyze();
    result.axe = axe.violations.map((v) => ({
      id: v.id,
      impact: v.impact,
      help: v.help,
      nodes: v.nodes.length,
      targets: v.nodes.slice(0, 3).map((n) => n.target.join(' ')),
    }));

    await mkdir(dirname(png), { recursive: true });
    await page.screenshot({ path: png, fullPage: true, animations: 'disabled' });

    const fail = (name: string, when: boolean) => when && result.failures.push(name);
    fail('redirected-to-login', result.finalPath === '/login' && route.path !== '/login' && auth !== 'none');
    fail('scheme-not-applied', scheme !== 'system' && measured.scheme !== scheme);
    fail('page-overflow', !!measured.pageOverflow);
    // At 200% zoom a grid, a canvas or code may scroll in two dimensions (WCAG 1.4.10; ADR-0164).
    fail('grid-overflow', width !== 'zoom' && measured.scrollers.length > 0);
    fail('layout-shift', measured.cls > CLS_BUDGET);
    fail('csp-violation', measured.csp.length > 0);
    fail('console-error', result.consoleErrors.length > 0);
    fail('failed-request', result.failedRequests.length > 0);
    fail('foreign-origin', result.foreignRequests.length > 0);
    fail('axe', result.axe.length > 0);
    fail('not-settled', result.settled === false);
  } catch (error) {
    result.error = (error as Error).message.split('\n')[0];
    result.failures.push('exception');
  } finally {
    await page.close();
  }
  result.ok = result.failures.length === 0;
  return result;
}

/** A capture spoiled by the capture machine's network changing under it, not by the console. */
function networkChanged(result: Capture): boolean {
  const text = JSON.stringify([result.consoleErrors, result.failedRequests]);
  return text.includes('ERR_NETWORK_CHANGED');
}

/** The seeded cluster's id; empty on a stack with none, which only the routes without `:cluster` can use. */
async function clusterId(browser: Browser): Promise<string> {
  const context = await browser.newContext({ storageState: await sessionOf('admin') });
  const response = await context.request.get(`${BASE}/api/v1/clusters?size=500`);
  const body = (await response.json()) as { data?: { id: string; name: string }[] };
  await context.close();
  const found = body.data?.find((c) => c.name === 'demo') ?? body.data?.[0];
  if (!response.ok()) throw new Error(`no cluster list at ${BASE}: run just qa-up first (${response.status()})`);
  return found?.id ?? '';
}

/** The Kafka cluster's id: the one named `events`, or empty. */
async function kafkaId(browser: Browser): Promise<string> {
  const context = await browser.newContext({ storageState: await sessionOf('admin') });
  const response = await context.request.get(`${BASE}/api/v1/clusters?size=500`);
  const body = (await response.json()) as { data?: { id: string; name: string }[] };
  await context.close();
  return body.data?.find((c) => c.name === 'events')?.id ?? '';
}

/** The requester's newest request in each state, for the routes that name one as `:held:<STATE>`. */
const heldIds = new Map<string, string>();

async function loadHeldIds(browser: Browser) {
  const context = await browser.newContext({ storageState: await sessionOf('requester') });
  const response = await context.request.get(`${BASE}/api/v1/held-operations?scope=MINE&limit=100`);
  const body = (await response.json()) as { items?: { id: string; state: string }[] };
  await context.close();
  if (!response.ok()) throw new Error(`no held requests at ${BASE} (${response.status()})`);
  for (const { id, state } of body.items ?? []) if (!heldIds.has(state)) heldIds.set(state, id);
}

async function main() {
  const jobs = ROUTES.filter((route) => !only || only.includes(route.area) || only.includes(route.id)).flatMap(jobsFor);
  if (jobs.length === 0) throw new Error('nothing to capture: check --only, --states and --widths');
  console.log(`sweep "${flags.label}": ${jobs.length} captures against ${BASE}`);

  // Playwright would close the browser on a signal and leave the capture in flight to fail with "browser has
  // been closed", recorded as an `exception` of a route that did nothing wrong. A signal instead stops the
  // sweep taking new captures, lets the one in flight finish and writes the report of the rest.
  const browser = await chromium.launch({ handleSIGINT: false, handleSIGTERM: false, handleSIGHUP: false });
  let stopped = false;
  for (const signal of ['SIGINT', 'SIGTERM', 'SIGHUP'] as const) {
    process.once(signal, () => {
      stopped = true;
      console.log(`\n${signal}: finishing the captures in flight, then reporting what was captured`);
    });
  }
  const cluster = await clusterId(browser);
  kafkaCluster = await kafkaId(browser);
  const needing = jobs.find(
    (job) => (!cluster && job.route.path.includes(':cluster')) || (!kafkaCluster && job.route.path.includes(':kafka')),
  );
  if (needing) throw new Error(`${needing.route.id} needs a registered cluster at ${BASE}`);
  if (jobs.some((job) => job.route.path.includes(':held:'))) await loadHeldIds(browser);
  const contexts = new Map<string, Promise<BrowserContext>>();
  const contextOf = (job: Job) => {
    const key = `${job.auth}|${job.width}|${job.scheme}`;
    if (!contexts.has(key)) contexts.set(key, contextFor(browser, job));
    return contexts.get(key)!;
  };

  const captures: Capture[] = [];
  const queue = [...jobs];
  const worker = async () => {
    for (let job = queue.shift(); job && !stopped; job = queue.shift()) {
      let result = await capture(await contextOf(job), job, cluster);
      // The capture machine's own network changing (Docker adding an interface) aborts the page's
      // requests with ERR_NETWORK_CHANGED. That says nothing about the console, so such a capture is
      // taken again, up to twice, and the retry is recorded.
      for (let retry = 1; retry <= 2 && networkChanged(result); retry++) {
        result = { ...(await capture(await contextOf(job), job, cluster)), retries: retry };
      }
      captures.push(result);
      console.log(`${result.ok ? 'ok  ' : 'FAIL'} ${result.png}${result.ok ? '' : `  ${result.failures.join(', ')}`}`);
    }
  };
  await Promise.all(Array.from({ length: Math.max(1, Number(flags.workers)) }, worker));
  await browser.close();

  captures.sort((a, b) => a.png.localeCompare(b.png));
  await mkdir(OUT, { recursive: true });
  await writeFile(
    resolve(OUT, 'report.json'),
    JSON.stringify({ label: flags.label, rahdar: BASE, cluster, captures }, null, 2),
  );

  const byCheck = new Map<string, number>();
  for (const c of captures) for (const f of c.failures) byCheck.set(f, (byCheck.get(f) ?? 0) + 1);
  const failedCount = captures.filter((c) => !c.ok).length;
  console.log(`\n${captures.length} captures${stopped ? ` of ${jobs.length} (stopped)` : ''}, ${failedCount} failed`);
  for (const [check, count] of [...byCheck].sort((a, b) => b[1] - a[1]))
    console.log(`  ${String(count).padStart(5)}  ${check}`);
  console.log(`report: ${relative(process.cwd(), resolve(OUT, 'report.json'))}`);
  process.exit(failedCount > 0 || stopped ? 1 : 0);
}

main().catch((error) => {
  console.error(error);
  process.exit(1);
});
