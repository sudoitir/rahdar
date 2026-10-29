/**
 * The README and site screenshots of the multi-broker story (openspec/changes/19e4-kafka/qa/readme-shots.md): the
 * viewport of a live page in light and dark, from the isolated QA stack, at 1440x900 and scale 2.
 *
 *   RAHDAR=http://127.0.0.1:18080 node --experimental-strip-types web/scripts/readme-shots.ts [--set readme|home] [--only id,id]
 *
 * Each shot is a route of the sweep (`sweep/routes.ts`) with, where it has one, the scene that acts on the page, so
 * the picture is the state the sweep reviews. Writes `openspec/changes/19e4-kafka/qa/captures/<set>/<id>-<scheme>.png`.
 */
import { chromium, type Browser, type Page } from '@playwright/test';
import { mkdir, readFile } from 'node:fs/promises';
import { dirname, resolve } from 'node:path';
import { parseArgs } from 'node:util';
import { fileURLToPath } from 'node:url';

import { BASE } from './session.ts';
import { BUSY, storeScheme } from './sweep/probes.ts';
import { ROUTES } from './sweep/routes.ts';

const WEB = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const OUT = resolve(WEB, '../openspec/changes/19e4-kafka/qa/captures');

interface Shot {
  id: string;
  /** The sweep route this is, by its id. */
  route: string;
  /** The route's scene to act with, when the picture is a state reached by acting on the page. */
  scene?: string;
  /** Replaces the route's path. */
  path?: string;
  /** Acts on the page after it has settled, before the scene. */
  before?: (page: Page) => Promise<void>;
  /** Crop to the whole page rather than the viewport, up to this many CSS pixels tall. */
  fullPage?: number;
}

const SETS: Record<string, Shot[]> = {
  readme: [
    { id: 'estate', route: 'home', path: '/?sort=-backlog' },
    { id: 'subscriptions', route: 'subscriptions', scene: 'backlog-drawer' },
    { id: 'kafka-group', route: 'group-lagging' },
    { id: 'offset-reset', route: 'group-lagging', scene: 'reset-preview-latest' },
    { id: 'transactions', route: 'transactions', scene: 'abort-dialog' },
    { id: 'register-kafka', route: 'register-kafka', scene: 'live-check', fullPage: 1800 },
    {
      id: 'artemis-topology',
      route: 'topology',
      // One node chosen, so the picture shows the panel that says what that broker is, in words.
      before: async (page) => {
        await page.locator('.react-flow__node button').first().click();
        await page.getByRole('heading', { name: 'Its pair' }).waitFor({ timeout: 10_000 });
      },
    },
  ],
  home: [
    { id: 'estate', route: 'home', path: '/?sort=-backlog' },
    { id: 'lag', route: 'subscriptions', scene: 'backlog-drawer' },
    { id: 'transactions', route: 'transactions' },
    { id: 'transactions-abort', route: 'transactions', scene: 'abort-dialog' },
    { id: 'reset-blocked', route: 'group-active', scene: 'reset-blocked-by-members' },
    { id: 'reset-preview', route: 'group-lagging', scene: 'reset-preview-latest' },
    { id: 'reset-held', route: 'group-lagging', scene: 'reset-held-stub' },
    { id: 'brokers', route: 'brokers' },
    { id: 'truncate', route: 'destinations', scene: 'truncate-preview' },
    { id: 'register', route: 'register-kafka', scene: 'checked-stub', fullPage: 1800 },
    {
      id: 'artemis-topology',
      route: 'topology',
      // One node chosen, so the picture shows the panel that says what that broker is, in words.
      before: async (page) => {
        await page.locator('.react-flow__node button').first().click();
        await page.getByRole('heading', { name: 'Its pair' }).waitFor({ timeout: 10_000 });
      },
    },
    { id: 'artemis-queues', route: 'subscriptions', path: '/clusters/:cluster/subscriptions?sort=-backlog' },
  ],
};

const { values } = parseArgs({ options: { set: { type: 'string', default: 'readme' }, only: { type: 'string' } } });
const shots = (SETS[values.set ?? 'readme'] ?? []).filter((s) => !values.only || values.only.split(',').includes(s.id));

async function settle(page: Page) {
  await page.waitForLoadState('load');
  for (let i = 0; i < 100 && (await page.evaluate(BUSY)); i++) await page.waitForTimeout(100);
  // The header says Live once the event stream is connected; a picture of "Polling" is of a page still starting.
  await page
    .getByText('Live', { exact: true })
    .first()
    .waitFor({ timeout: 15_000 })
    .catch(() => undefined);
  await page.evaluate('document.fonts.ready.then(() => true)');
  await page.waitForTimeout(800);
}

/** The live check of a registration that passes: the stack's own brokers, registered as `events` and removed for the picture. */
async function liveCheck(page: Page) {
  await page.getByLabel('Bootstrap servers', { exact: true }).fill('kafka-1:9092');
  await page.getByLabel('Bootstrap servers', { exact: true }).blur();
  await page.getByRole('button', { name: 'Check connection', exact: true }).click();
  await page.getByText('Controller quorum').first().waitFor({ timeout: 60_000 });
  await page.waitForTimeout(800);
}

async function main() {
  const browser: Browser = await chromium.launch();
  const admin = { ...JSON.parse(await readFile(resolve(WEB, '.sweep/auth/admin.json'), 'utf8')), origins: [] };
  const probe = await browser.newContext({ storageState: admin });
  const clusters = (
    (await (await probe.request.get(`${BASE}/api/v1/clusters?size=100`)).json()) as {
      data: { id: string; name: string }[];
    }
  ).data;
  await probe.close();
  const demo = clusters.find((c) => c.name === 'demo')?.id ?? '';
  const events = clusters.find((c) => c.name === 'events')?.id ?? '';
  const ids = (path: string) => path.replaceAll(':cluster', demo).replaceAll(':kafka', events);

  for (const shot of shots) {
    const route = ROUTES.find((r) => r.id === shot.route);
    if (!route) throw new Error(`no sweep route ${shot.route}`);
    const scene = shot.scene === 'live-check' ? { act: liveCheck } : route.scenes?.find((s) => s.id === shot.scene);
    if (shot.scene && !scene) throw new Error(`no scene ${shot.scene} on ${shot.route}`);
    for (const scheme of ['light', 'dark'] as const) {
      const context = await browser.newContext({
        viewport: { width: 1440, height: 900 },
        deviceScaleFactor: 2,
        colorScheme: scheme,
        storageState: admin,
      });
      await context.addInitScript({ content: storeScheme(scheme) });
      const page = await context.newPage();
      const errors: string[] = [];
      page.on('console', (m) => m.type() === 'error' && errors.push(m.text()));
      try {
        await page.goto(`${BASE}${ids(shot.path ?? route.path)}`);
        await settle(page);
        await shot.before?.(page);
        if (scene) {
          await scene.act(page);
          await settle(page);
        }
        await page.evaluate('window.scrollTo(0, 0)');
        await page.mouse.move(0, 0);
        await page.evaluate('document.activeElement instanceof HTMLElement && document.activeElement.blur()');
        const file = resolve(OUT, values.set ?? 'readme', `${shot.id}-${scheme}.png`);
        await mkdir(dirname(file), { recursive: true });
        if (shot.fullPage) {
          const height = Math.min(shot.fullPage, await page.evaluate('document.documentElement.scrollHeight'));
          await page.setViewportSize({ width: 1440, height });
          await page.waitForTimeout(500);
        }
        await page.screenshot({ path: file });
        console.log(
          `${errors.length ? 'ERRORS ' : 'ok     '} ${shot.id}-${scheme}${errors.length ? ` ${errors[0]}` : ''}`,
        );
      } catch (e) {
        console.log(`FAILED  ${shot.id}-${scheme}: ${e instanceof Error ? e.message.split('\n')[0] : e}`);
      } finally {
        await context.close();
      }
    }
  }
  await browser.close();
}

await main();
