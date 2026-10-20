/**
 * Capture the README screenshots against a running Rahdar.
 *
 *   ADMIN_PASSWORD=… ADMIN_TOTP_SECRET=… npm --prefix web run shots      (or: just shots)
 *
 * Point it at whatever `just demo` left running. It signs in as a real operator
 * would (`session.ts`), waits for each view's own evidence of having loaded —
 * never a fixed sleep, which produces a screenshot of a skeleton often enough to
 * matter — and writes over `docs/img/*.png`.
 *
 * The viewport is fixed so the images crop identically in the README table, and
 * `deviceScaleFactor: 2` so they stay legible when GitHub scales them down.
 *
 * The moving demo is `demo.ts`.
 */
import { chromium } from '@playwright/test';
import { mkdir } from 'node:fs/promises';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { BASE, password, signIn, streamLive } from './session.ts';

const HERE = dirname(fileURLToPath(import.meta.url));
const OUT = resolve(HERE, '../../docs/img');

password(); // fail before launching a browser if it is missing

// A query with one pushdown predicate and one target wildcard: the cost line
// then has something to classify, which is the part of this screen worth showing.
// No ORDER BY: sorting reads every message under ORDERS.*, which on a demo that has run for a while
// runs to the console's 30 s bound. Unsorted, the scan stops at the limit.
const SQL = `SELECT * FROM "ORDERS.*"
WHERE props.tenant = 'acme'
LIMIT 200`;

/**
 * The double-submit CSRF token, read back out of the session's own cookie jar.
 * The browser sends it automatically; a request made through `page.request` has
 * to echo it by hand, exactly as `scripts/*.sh` do.
 */
async function xsrf(page: import('@playwright/test').Page): Promise<string> {
  const cookies = await page.context().cookies();
  return cookies.find((c) => c.name === 'XSRF-TOKEN')?.value ?? '';
}

async function main() {
  await mkdir(OUT, { recursive: true });
  const browser = await chromium.launch();
  const page = await browser.newPage({
    viewport: { width: 1440, height: 900 },
    deviceScaleFactor: 2,
    colorScheme: 'dark',
  });

  const clusterId = await signIn(page);

  const shots: Array<{
    file: string;
    path: string;
    width?: number;
    height?: number;
    /** Drives the view into the state worth photographing, before `ready`. */
    before?: () => Promise<unknown>;
    ready: () => Promise<unknown>;
    /**
     * Say so and move on rather than failing the run. The configuration shots
     * depend on the cluster's state — the first-run offer only exists before
     * anything is declared — and a re-run against an already-declared cluster
     * should still refresh the other eight images.
     */
    optional?: boolean;
  }> = [
    {
      file: 'topology.png',
      path: `/clusters/${clusterId}/topology`,
      // A node box, not the frame: the frame renders before the data arrives. Then one node is chosen,
      // so the picture shows the panel that says what that broker is, in words.
      before: async () => {
        const node = page.locator('.react-flow__node button').first();
        await node.waitFor({ timeout: 30_000 });
        await node.click();
      },
      ready: () => page.getByRole('heading', { name: 'Its pair' }).waitFor({ timeout: 10_000 }),
    },
    {
      file: 'flow.png',
      // The flow view samples clients only while it is open, and rates need two samples: open it,
      // wait for real nodes, then give the sampler two sweeps before photographing.
      // Routing layers on, Rahdar's capture tap included; dead-letter edges stay off (one per queue).
      path: `/clusters/${clusterId}/flow?layers=BRIDGES,CAPTURE,CLUSTER,DIVERTS`,
      // Wide: five columns and their routing hops only fit side by side at a legible zoom on a wide screen.
      width: 1920,
      height: 1300,
      ready: async () => {
        await page.locator('.react-flow__node-queue').first().waitFor({ timeout: 60_000 });
        // Client nodes arrive with the sampler's first sweep and their rates with the second. Reload
        // once both exist, so the layout orders every column busiest first.
        await page
          .locator('.react-flow__node-client')
          .first()
          .waitFor({ timeout: 90_000 })
          .catch(() => console.warn('flow.png: no sampled clients yet — the seed may not be running'));
        await page.waitForTimeout(35_000);
        await page.reload();
        await page.locator('.react-flow__node-client').first().waitFor({ timeout: 60_000 });
        await page.waitForTimeout(2_000);
      },
    },
    {
      file: 'queues.png',
      path: `/clusters/${clusterId}/queues`,
      ready: () => page.getByRole('row').nth(1).waitFor({ timeout: 30_000 }),
    },
    {
      file: 'sql.png',
      path: `/clusters/${clusterId}/sql`,
      height: 1100,
      // The console is an empty form until a query has been run, so this one is
      // driven rather than merely visited: an empty editor is a screenshot of
      // nothing.
      before: async () => {
        await page.getByRole('textbox', { name: /query/i }).click();
        // The console restores the last query, so a click leaves the cursor in
        // the middle of it and typing would splice the two together.
        await page.keyboard.press('ControlOrMeta+a');
        await page.keyboard.type(SQL);
        await page.getByRole('button', { name: 'Run', exact: true }).click();
      },
      // Longer than the console's own 30 s query bound, so a slow broker still yields its rows.
      ready: () => page.getByRole('row').nth(1).waitFor({ timeout: 60_000 }),
    },
    {
      file: 'settings.png',
      path: `/clusters/${clusterId}/settings?tab=settings-operational`,
      ready: () => page.getByRole('heading', { name: 'Operational configuration' }).waitFor({ timeout: 30_000 }),
    },
    // ── declared configuration (ADR-0067) ────────────────────────────────
    // Ordered on purpose: the first-run offer only exists while the cluster has
    // no declaration, so it is photographed before anything declares one.
    {
      file: 'config-first-run.png',
      path: `/clusters/${clusterId}/configuration`,
      optional: true,
      ready: () =>
        page
          .getByText(/would be declared|Adopt what this cluster runs/)
          .first()
          .waitFor({ timeout: 30_000 }),
    },
    {
      file: 'config-recommended.png',
      path: `/clusters/${clusterId}/configuration?tab=recommended`,
      height: 1100,
      ready: () =>
        page
          .getByText(/Rahdar can apply|still need a broker.xml|nothing to recommend/i)
          .first()
          .waitFor({ timeout: 30_000 }),
    },
    {
      file: 'config-plan.png',
      path: `/clusters/${clusterId}/configuration`,
      height: 1200,
      // A declaration the brokers do not yet run, so the plan has something to
      // show. Declaring writes to Rahdar only, and the review drawer only plans:
      // no broker is touched by any of these captures, which is why there is no
      // post-apply shot here.
      before: async () => {
        const current = await page.request.get(`${BASE}/api/v1/clusters/${clusterId}/config`);
        const declaration = (await current.json()) as { revision: number; document: unknown };
        const document = declaration.document as {
          addressSettings: Array<{ match: string; values: Record<string, unknown> }>;
        };
        document.addressSettings = [
          ...document.addressSettings.filter((s) => s.match !== 'ORDERS.#'),
          { match: 'ORDERS.#', values: { addressFullMessagePolicy: 'DROP', maxSizeBytes: 52_428_800 } },
        ];
        await page.request.put(`${BASE}/api/v1/clusters/${clusterId}/config`, {
          headers: { 'X-XSRF-TOKEN': await xsrf(page) },
          data: {
            document: declaration.document,
            expectedRevision: declaration.revision === 0 ? null : declaration.revision,
            note: 'Screenshot fixture',
          },
        });
        await page.goto(`${BASE}/clusters/${clusterId}/configuration`);
        await page.getByRole('button', { name: 'Review & apply' }).click();
      },
      // The drawer plans as it opens; the step count is the plan having arrived.
      ready: () =>
        page
          .getByRole('dialog')
          .getByText(/ steps? · |Nothing to apply/)
          .first()
          .waitFor({ timeout: 30_000 }),
    },
    {
      file: 'config-drift.png',
      path: `/clusters/${clusterId}/configuration`,
      height: 1100,
      // Each node's drift is on the declared tab once the nodes are evaluated.
      before: async () => {
        await page.request
          .post(`${BASE}/api/v1/clusters/${clusterId}/artemis/config/drift/evaluate`, {
            headers: { 'X-XSRF-TOKEN': await xsrf(page) },
          })
          .catch(() => undefined);
        await page.reload();
      },
      ready: () =>
        page
          .getByText(/nodes evaluated/)
          .first()
          .waitFor({ timeout: 30_000 }),
    },
  ];

  for (const shot of shots) {
    await page.setViewportSize({ width: shot.width ?? 1440, height: shot.height ?? 900 });
    await page.goto(`${BASE}${shot.path}`);
    try {
      await shot.before?.();
      await shot.ready();
    } catch (error) {
      if (!shot.optional) throw error;
      console.warn(`skipped ${shot.file}: the view is not in the state it photographs`);
      continue;
    }
    await streamLive(page, shot.file);
    // One more frame, so the entry transition is finished rather than halfway.
    await page.waitForTimeout(750);
    await page.screenshot({ path: resolve(OUT, shot.file) });
    console.log(`wrote docs/img/${shot.file}`);
  }

  await browser.close();
}

main().catch((error) => {
  console.error(error);
  process.exit(1);
});
