/**
 * Record the README's demo GIFs from a real signed-in session.
 *
 *   ADMIN_PASSWORD=… ADMIN_TOTP_SECRET=… npm --prefix web run demo      (or: just demo-gif)
 *
 * Point it at whatever `just demo` left running. Everything on screen is the
 * product answering for itself against the seeded four-node estate — there is no
 * scripted output and nothing is drawn for the camera.
 *
 * Separate clips, because they answer separate questions and a viewer only
 * watches the first one: `demo.gif` is "what is this", `flow.gif` and
 * `sql-console.gif` are "what can I not do anywhere else", and
 * `plugin-install.gif` is "what does installing a plugin involve". The last one
 * needs `PLUGIN_JAR` (a built, signed plugin-template jar that is not installed
 * yet; when its key is not trusted yet, the clip trusts it from the review) and
 * is skipped without it. `CLIPS=flow,demo` records only the named clips.
 *
 * Playwright records WebM per context; ffmpeg turns each into a GIF through a
 * generated palette (a 256-colour default palette turns a dark UI into mud) and
 * an H.264 MP4 beside it.
 */
import { chromium, type Locator, type Page } from '@playwright/test';
import { execFile } from 'node:child_process';
import { mkdir, mkdtemp, readdir, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { dirname, join, resolve } from 'node:path';
import { promisify } from 'node:util';
import { fileURLToPath } from 'node:url';
import { BASE, password, signIn, streamLive } from './session.ts';

const run = promisify(execFile);
const HERE = dirname(fileURLToPath(import.meta.url));
const OUT = resolve(HERE, '../../docs/img');

// 1280×800 rather than the screenshots' 1440×900: a GIF pays for every pixel in
// bytes, and GitHub renders the README column narrower than either. The GIF is
// then scaled again on encode — 1000px wide at 10fps keeps both clips small
// enough that a README on a phone connection still loads.
const SIZE = { width: 1280, height: 800 };
const GIF_WIDTH = 1000;
const FPS = 10;

password();

const ONLY = process.env.CLIPS?.split(',').map((c) => c.trim());
const PLUGIN_JAR = process.env.PLUGIN_JAR;

/**
 * A visible pointer. Headless Chromium draws no cursor into the recording, so without one a viewer
 * sees views change with no idea what was clicked. It follows real mouse events and pulses on a
 * press; `pointer-events: none` keeps it out of the page's own hit testing. Physical left/top on
 * purpose: mouse events report physical coordinates, whatever the page's direction. A string, because
 * it runs in the page, not in Node, and this file is type-checked without the DOM.
 */
const CURSOR = `(() => {
  const install = () => {
    const dot = document.createElement('div');
    dot.setAttribute('aria-hidden', 'true');
    dot.style.cssText =
      'position:fixed;z-index:2147483647;pointer-events:none;inline-size:18px;block-size:18px;margin:-9px 0 0 -9px;' +
      'border-radius:50%;background:rgba(255,255,255,.35);border:2px solid rgba(255,255,255,.95);' +
      'box-shadow:0 0 0 1px rgba(0,0,0,.45);transition:transform .12s ease-out;left:-40px;top:-40px';
    document.body.appendChild(dot);
    addEventListener('mousemove', (e) => {
      dot.style.left = e.clientX + 'px';
      dot.style.top = e.clientY + 'px';
    }, true);
    addEventListener('mousedown', () => (dot.style.transform = 'scale(.6)'), true);
    addEventListener('mouseup', () => (dot.style.transform = 'scale(1)'), true);
  };
  if (document.body) install();
  else addEventListener('DOMContentLoaded', install);
})();`;

/** Glides the pointer to an element before acting on it, so the viewer's eye arrives first. */
async function point(page: Page, target: Locator) {
  await target.scrollIntoViewIfNeeded();
  const box = await target.boundingBox();
  if (box) await page.mouse.move(box.x + box.width / 2, box.y + box.height / 2, { steps: 14 });
}

async function click(page: Page, target: Locator) {
  await point(page, target);
  await page.waitForTimeout(120);
  await target.click();
}

/**
 * Types into an editor at a speed a viewer can read rather than instantly.
 *
 * <p>Typing is the only part of a clip that is inherently paced: it is also the
 * part that carries motion, so it stays slow enough to read while every static
 * hold around it is cut to the beat it takes to register what changed.
 */
async function type(page: Page, text: string) {
  await page.keyboard.type(text, { delay: 28 });
}

/** Holds the last frame, so a GIF loop does not snap away from the payoff. */
async function hold(page: Page, ms: number) {
  await page.waitForTimeout(ms);
}

/**
 * Moves between views the way an operator does — the sidebar link, not a
 * `goto`. A `goto` is a full document load: several seconds of blank frame and
 * a reconnecting stream between every view, which is most of what made the
 * first cut of this clip feel like a slideshow.
 */
async function navigate(page: Page, name: string | RegExp) {
  await click(page, page.getByRole('link', { name, exact: typeof name === 'string' }).first());
}

async function encode(webm: string, name: string, skip: number, gifWidth: number) {
  // Recording starts when the context does, so the first seconds are the login
  // screen and the navigation to the first view. `mark()` says where the part
  // worth watching begins; everything before it is cut.
  const trim = ['-ss', skip.toFixed(2), '-i', webm];
  // 128 colours rather than 256: a dark UI with a small accent palette does not
  // use them, and the smaller table is a visibly smaller file.
  const palette = join(dirname(webm), 'palette.png');
  const filters = `fps=${FPS},scale=${gifWidth}:-1:flags=lanczos`;
  await run('ffmpeg', ['-y', ...trim, '-vf', `${filters},palettegen=max_colors=128:stats_mode=diff`, palette]);
  await run('ffmpeg', [
    '-y',
    ...trim,
    '-i',
    palette,
    // No dithering: this is a flat, dark UI with large areas of one colour, so
    // dithering buys nothing visible and roughly doubles the file.
    '-lavfi',
    `${filters}[x];[x][1:v]paletteuse=dither=none:diff_mode=rectangle`,
    join(OUT, `${name}.gif`),
  ]);
  console.log(`wrote docs/img/${name}.gif`);
}

/** One clip: its own context, so its own video file. */
async function clip(
  name: string,
  drive: (page: Page, clusterId: string, mark: () => void) => Promise<void>,
  { size = SIZE, gifWidth = GIF_WIDTH } = {},
) {
  if (ONLY && !ONLY.includes(name)) return;
  const dir = await mkdtemp(join(tmpdir(), `rahdar-${name}-`));
  const browser = await chromium.launch();
  const started = Date.now();
  const context = await browser.newContext({
    viewport: size,
    colorScheme: 'dark',
    recordVideo: { dir, size },
  });
  await context.addInitScript({ content: CURSOR });
  const page = await context.newPage();
  let skip = 0;
  try {
    const clusterId = await signIn(page);
    await drive(page, clusterId, () => {
      skip = (Date.now() - started) / 1000;
    });
  } finally {
    await context.close(); // the video is only flushed here
    await browser.close();
  }
  const [video] = (await readdir(dir)).filter((f) => f.endsWith('.webm'));
  if (!video) throw new Error(`${name}: playwright wrote no video into ${dir}`);
  await encode(join(dir, video), name, skip, gifWidth);
  await rm(dir, { recursive: true, force: true });
}

await mkdir(OUT, { recursive: true });

// ── 1. What Rahdar is ────────────────────────────────────────────────
await clip('demo', async (page, clusterId, mark) => {
  await page.goto(`${BASE}/clusters/${clusterId}/topology`);
  await page.locator('.react-flow__node').first().waitFor({ timeout: 30_000 });
  await streamLive(page, 'demo');
  // Only now: everything before this is a login form and a reconnecting header.
  mark();
  // Short: a GIF that opens on four seconds of a still frame reads as a
  // screenshot, and a reader who thinks it is one never waits for the motion.
  await hold(page, 1_200);
  // Choosing a broker opens the panel that says what it is: role, liveness, pair and version, in words.
  await click(page, page.locator('.react-flow__node button').first()).catch(() =>
    console.warn('demo: no topology node to choose'),
  );
  await hold(page, 1_800);

  // Every queue on every node, worst first — the view the bundled console cannot
  // produce at all.
  await navigate(page, 'Queues');
  await page.getByRole('row').nth(1).waitFor({ timeout: 30_000 });
  await hold(page, 600);
  // Sorting by depth is the whole point of the view: the worst thing in the
  // cluster becomes the first row. The header is a button inside the columnheader.
  const depth = page.getByRole('button', { name: /^depth/i }).first();
  await click(page, depth).catch(() => console.warn('demo: no depth column to sort by'));
  await hold(page, 400);
  await depth.click().catch(() => {}); // ascending, then descending
  await hold(page, 1_600);

  // The dead-letter queues the seed really built, by rejecting messages.
  await navigate(page, 'DLQ');
  // The heading, not a row: with nothing dead-lettered yet the grid has no rows, and waiting for one
  // would put a timeout's worth of dead seconds in the middle of the clip.
  await page
    .getByText(/dead-letter queues/i)
    .first()
    .waitFor({ timeout: 20_000 });
  await hold(page, 1_800);

  // Who produces where and who consumes it, moving: the view no other Artemis console has.
  await navigate(page, 'Flow');
  const queue = page.locator('.react-flow__node-queue').first();
  await queue
    .waitFor({ timeout: 60_000 })
    .catch(() => console.warn('demo: flow graph not drawn — let the seed run longer'));
  await hold(page, 2_400);
  // Hovering a queue keeps its whole path bright and fades the rest.
  await point(page, queue).catch(() => {});
  await hold(page, 2_000);

  await navigate(page, 'Metrics');
  await page
    .locator('.recharts-area, .recharts-line')
    .first()
    .waitFor({ timeout: 30_000 })
    .catch(() => console.warn('demo: no plotted series yet — let the seed run longer'));
  await hold(page, 2_000);
});

// ── 2. Flow, on its own ──────────────────────────────────────────────────────
await clip(
  'flow',
  async (page, clusterId, mark) => {
    // Every routing layer the seed builds: diverts, the bridge, cluster hops and Rahdar's capture tap.
    await page.goto(`${BASE}/clusters/${clusterId}/flow?layers=BRIDGES,CAPTURE,CLUSTER,DIVERTS`);
    // Client rates come from the sampler's second sweep; a clip without them is a screenshot. Reload once
    // they exist, so the layout orders every column busiest first. All before `mark()`.
    await page
      .locator('.react-flow__node-client')
      .first()
      .waitFor({ timeout: 90_000 })
      .catch(() => console.warn('flow: no sampled clients — is the seed still driving traffic?'));
    // Collapse the sidebar before the reload, so the graph is fitted to the wider canvas it is filmed in.
    await page.keyboard.press('ControlOrMeta+b');
    await page.waitForTimeout(35_000);
    await page.reload();
    await page.locator('.react-flow__node-client').first().waitFor({ timeout: 60_000 });
    await page
      .locator('animateMotion')
      .first()
      .waitFor({ state: 'attached', timeout: 30_000 })
      .catch(() => {});
    await streamLive(page, 'flow');
    mark();
    await hold(page, 3_000);

    // Hovering a queue keeps its whole path bright and fades the rest. The canvas clips its nodes, so a
    // queue outside the visible part is covered by the pane: pick the first one wholly inside it.
    await page.locator('.react-flow').scrollIntoViewIfNeeded();
    const canvas = (await page.locator('.react-flow').boundingBox())!;
    let target: { x: number; y: number } | null = null;
    for (const box of await Promise.all(
      (await page.locator('.react-flow__node-queue').all()).map((q) => q.boundingBox()),
    )) {
      if (
        box &&
        box.y > canvas.y + 40 &&
        box.y + box.height < canvas.y + canvas.height - 40 &&
        box.x + box.width < canvas.x + canvas.width
      ) {
        target = { x: box.x + box.width / 2, y: box.y + box.height / 2 };
        break;
      }
    }
    if (!target) throw new Error('flow: no queue is wholly on screen');
    await page.mouse.move(target.x, target.y, { steps: 12 });
    await hold(page, 2_400);

    // Selecting it opens the inspector: rates, members and routing for that one node.
    await page.mouse.click(target.x, target.y);
    await page
      .getByRole('complementary', { name: /^Details of / })
      .waitFor({ timeout: 10_000 })
      .catch(() => {});
    await hold(page, 3_000);
    await page.keyboard.press('Escape');
    await page.mouse.move(4, 4);
    await hold(page, 1_600);
    // Wider than the other clips, and without the sidebar: five columns only fit legibly side by side.
  },
  { size: { width: 1600, height: 1000 }, gifWidth: 1200 },
);

// ── 3. The SQL Console, on its own ───────────────────────────────────────────
await clip('sql-console', async (page, _clusterId, mark) => {
  // Through the queues first, the way an operator arrives: one management read
  // settles the console's capability verdict, so the clip is not spent under a
  // banner saying the answer is not known yet. Before `mark()`, so it costs the
  // viewer nothing — and nothing is faked, the read really happens.
  await navigate(page, 'Queues');
  await page.getByRole('row').nth(1).waitFor({ timeout: 30_000 });
  await navigate(page, 'SQL Console');
  const editor = page.getByRole('textbox', { name: /query/i });
  await editor.waitFor({ timeout: 30_000 });
  await streamLive(page, 'sql-console');
  mark();
  await hold(page, 500);

  // A short query needs a short editor: the separator's Home key gives the editor its smallest size,
  // so the clip's window has room for the rows the queries find.
  const separator = page.getByRole('separator', { name: 'Resize the editor and the results' });
  await separator.focus();
  await page.keyboard.press('Home');
  await hold(page, 600);

  // Cheap first: the predicate pushes down into a JMS selector, and the cost
  // line says so before anything is run.
  await editor.click();
  // The console restores the last query; a click alone leaves the cursor in the
  // middle of it.
  await page.keyboard.press('ControlOrMeta+a');
  await type(page, 'SELECT * FROM "ORDERS.*"\nWHERE props.tenant = \'acme\'\nLIMIT 200');
  await hold(page, 1_400); // let the cost line classify it
  await page.getByRole('button', { name: 'Run', exact: true }).click();
  await page
    .getByRole('row')
    .nth(1)
    .waitFor({ timeout: 60_000 })
    .catch(() => {});
  // The results fill the pane under the editor; the page itself does not scroll.
  await hold(page, 2_200);

  // Then the thing a JMS selector cannot do at all: read the body.
  await editor.click();
  await page.keyboard.press('ControlOrMeta+a');
  await type(page, "SELECT * FROM \"ORDERS.*\"\nWHERE body->>'orderId' = '4471'\nLIMIT 50");
  await hold(page, 1_600); // the cost line now says "scan", which is the point
  await page.getByRole('button', { name: 'Run', exact: true }).click();
  await page
    .getByRole('row')
    .nth(1)
    .waitFor({ timeout: 60_000 })
    .catch(() => {});
  await hold(page, 2_800);
});

// ── 4. Installing a plugin ───────────────────────────────────────────────────
// What an administrator sees before anything runs: what the plugin may do, the database changes it
// brings (the SQL itself, one click away), and a held confirmation. Then it is live without a restart.
if (PLUGIN_JAR) {
  await clip(
    'plugin-install',
    async (page, clusterId, mark) => {
      await page.goto(`${BASE}/admin?tab=plugins`);
      const install = page.getByRole('button', { name: 'Install plugin…' });
      await install.waitFor({ timeout: 30_000 });
      mark();
      await hold(page, 1_200);

      // The file chooser is the browser's own; Playwright answers it the moment it opens.
      await point(page, install);
      const chooser = page.waitForEvent('filechooser');
      await install.click();
      await (await chooser).setFiles(PLUGIN_JAR);

      // Inspect, then Review: checked before it is stored, then what it will be able to do.
      const dialog = page.getByRole('dialog');
      await dialog.getByText('What this plugin will be able to do').waitFor({ timeout: 60_000 });
      await hold(page, 2_600);
      const sql = dialog.getByRole('button', { name: 'Show the SQL' });
      if (await sql.count()) {
        await click(page, sql);
        await hold(page, 800);
        // Through the SQL at reading pace: the schema this plugin creates is the part worth reading.
        for (let i = 0; i < 6; i++) {
          await page.mouse.wheel(0, 180);
          await hold(page, 450);
        }
        await hold(page, 1_000);
      }
      // A publisher Rahdar has not met yet: compare the fingerprint, then trust the key from here.
      const trust = dialog.getByRole('button', { name: 'Trust this key…' });
      if (await trust.count()) {
        await click(page, trust);
        const trustDialog = page.getByRole('dialog').last();
        await click(page, trustDialog.getByRole('textbox', { name: 'Key name' }));
        await type(page, 'Acme');
        await click(page, trustDialog.getByRole('checkbox', { name: /I compared this fingerprint/ }));
        await hold(page, 600);
        await click(page, trustDialog.getByRole('button', { name: 'Trust this key' }));
        await dialog.getByText('Verified').first().waitFor({ timeout: 30_000 });
        await hold(page, 1_200);
      }
      await click(page, dialog.getByRole('button', { name: 'Continue' }));

      // The confirmation is held: the button fills while the mouse stays down.
      const confirm = dialog.getByRole('button', { name: /^Install/ }).last();
      await confirm.hover();
      await page.mouse.down();
      await hold(page, 1_800);
      await page.mouse.up();
      await dialog.getByText(/is active/).waitFor({ timeout: 90_000 });
      await hold(page, 1_800);

      // Its screen, loaded into the running Rahdar: a reload picks up the new bundle, then the plugin's
      // page is one sidebar link like any other.
      await click(page, dialog.getByRole('button', { name: 'Reload Rahdar' }));
      await page.waitForLoadState();
      await page.goto(`${BASE}/clusters/${clusterId}/topology`);
      await navigate(page, 'Notes');
      await page.getByRole('heading').first().waitFor({ timeout: 30_000 });
      await hold(page, 2_400);
      // Smaller than the other clips: the story is one dialog, and at this size its text stays legible
      // in the GIF without scaling it down.
    },
    { size: { width: 1024, height: 720 }, gifWidth: 1024 },
  );
} else if (!ONLY || ONLY.includes('plugin-install')) {
  console.warn('plugin-install: skipped — set PLUGIN_JAR to a built plugin-template jar that is not installed yet');
}
