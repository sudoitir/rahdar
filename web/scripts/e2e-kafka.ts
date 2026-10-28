/**
 * The Kafka end-to-end run (task 6.2c of 19e4-kafka): every flow driven in a real browser against the isolated QA
 * stack (`just qa-up kafka 18080 demo=1`), and its effect read back from the broker itself with the Kafka image's
 * own tools, never from Rahdar's answer alone.
 *
 *   RAHDAR=http://127.0.0.1:18080 node --experimental-strip-types web/scripts/e2e-kafka.ts [--only id,id]
 *
 * Each flow is independent enough to run alone but the order is the order of the seeded states: the group a
 * flow resets is not the group another purges. A flow passes when every `check` held; the console errors and the
 * failed requests of the whole run are listed at the end, and a refusal a flow expects (a 409, a 400) is named
 * by the flow so it does not count.
 */
import { chromium } from '@playwright/test';
import { mkdirSync, writeFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

import type { Check, Flow, Toolkit } from './e2e-kafka/types.ts';

const HERE = dirname(fileURLToPath(import.meta.url));
const BASE = process.env.RAHDAR ?? 'http://127.0.0.1:18080';
const AUTH = resolve(HERE, '../.sweep/auth/admin.json');
const OUT = resolve(HERE, '../../openspec/changes/19e4-kafka/qa/e2e-results.md');

interface Result {
  flow: Flow;
  checks: Check[];
  notes: string[];
  error?: string;
}
interface Problem {
  flow: string;
  kind: string;
  detail: string;
}

async function main() {
  const only = process.argv.includes('--only') ? process.argv[process.argv.indexOf('--only') + 1].split(',') : null;
  const { FLOWS } = await import('./e2e-kafka/flows.ts');
  const browser = await chromium.launch();
  const context = await browser.newContext({ storageState: AUTH, viewport: { width: 1440, height: 900 } });
  const api = async (path: string) => {
    const response = await context.request.get(`${BASE}/api/v1${path}`);
    return response.json();
  };
  const clusters = (await api('/clusters?size=100')) as { data: { id: string; name: string }[] };
  const cluster = clusters.data.find((c) => c.name === 'events')?.id;
  if (!cluster) throw new Error('the Kafka cluster "events" is not registered: run `just qa-up kafka 18080 demo=1`');

  const problems: Problem[] = [];
  const results: Result[] = [];
  let current: Flow | null = null;
  const page = await context.newPage();
  page.on('console', (m) => {
    if (m.type() === 'error')
      problems.push({ flow: current?.id ?? '-', kind: 'console error', detail: `${m.text()} (${m.location().url})` });
  });
  page.on('pageerror', (e) => problems.push({ flow: current?.id ?? '-', kind: 'page error', detail: e.message }));
  page.on('requestfailed', (r) => {
    // A navigation that replaced a pending request, and an SSE stream that was closed on purpose.
    if (/ERR_ABORTED/.test(r.failure()?.errorText ?? '')) return;
    problems.push({
      flow: current?.id ?? '-',
      kind: 'request failed',
      detail: `${r.method()} ${r.url()} ${r.failure()?.errorText}`,
    });
  });
  page.on('response', (r) => {
    if (r.status() < 400 || !r.url().includes('/api/')) return;
    if (current?.expectedStatus?.includes(r.status())) return;
    problems.push({
      flow: current?.id ?? '-',
      kind: `HTTP ${r.status()}`,
      detail: `${r.request().method()} ${r.url()}`,
    });
  });

  for (const flow of FLOWS as Flow[]) {
    if (only && !only.includes(flow.id)) continue;
    current = flow;
    const result: Result = { flow, checks: [], notes: [] };
    const t: Toolkit = {
      page,
      context,
      cluster,
      api,
      send: async (method, path, body) => {
        const token = (await context.cookies()).find((c) => /xsrf/i.test(c.name))?.value ?? '';
        const response = await context.request.fetch(`${BASE}/api/v1${path}`, {
          method,
          headers: { 'X-XSRF-TOKEN': token },
          data: body,
        });
        return { status: response.status(), body: await response.json().catch(() => null) };
      },
      check: (ok, what) => {
        result.checks.push({ ok, what });
        console.log(`  ${ok ? 'ok  ' : 'FAIL'} ${what}`);
      },
      note: (text) => {
        result.notes.push(text);
        console.log(`  note ${text}`);
      },
      open: async (path) => {
        await page.goto(`${BASE}${path.replace(':kafka', cluster)}`);
        await page.waitForLoadState('networkidle').catch(() => undefined);
      },
      hold: async (name) => {
        const button = page.getByRole('dialog').getByRole('button', { name });
        await button.hover();
        await page.mouse.down();
        await page.waitForTimeout(1900);
        await page.mouse.up();
        await page.waitForTimeout(800);
      },
    };
    console.log(`\n${flow.id}: ${flow.title}`);
    try {
      await flow.run(t);
    } catch (e) {
      result.error = e instanceof Error ? e.message.split('\n')[0] : String(e);
      console.log(`  ERROR ${result.error}`);
      await page.screenshot({ path: `/tmp/claude-1000/e2e/${flow.id}-error.png` }).catch(() => undefined);
    }
    results.push(result);
  }
  current = null;
  await browser.close();

  const passed = (r: Result) => !r.error && r.checks.every((c) => c.ok) && r.checks.length > 0;
  const lines = [
    '# 6.2c: the Kafka end-to-end run',
    '',
    `Run ${new Date().toISOString()} against ${BASE} (the isolated QA stack, three Kafka brokers, six Artemis nodes).`,
    "Each effect was read back from the broker with the Kafka image's own tools.",
    '',
    '| Flow | Result |',
    '| --- | --- |',
    ...results.map((r) => `| ${r.flow.id}: ${r.flow.title} | ${passed(r) ? 'pass' : 'FAIL'} |`),
    '',
  ];
  for (const r of results) {
    lines.push(`## ${r.flow.id}: ${r.flow.title}`, '');
    for (const c of r.checks) lines.push(`- ${c.ok ? 'pass' : '**FAIL**'}: ${c.what}`);
    for (const n of r.notes) lines.push(`- note: ${n}`);
    if (r.error) lines.push(`- **ERROR**: ${r.error}`);
    lines.push('');
  }
  lines.push('## Console errors and failed requests', '');
  if (problems.length === 0) lines.push('None.');
  for (const p of problems) lines.push(`- ${p.flow}: ${p.kind}: ${p.detail}`);
  if (!only) {
    mkdirSync(dirname(OUT), { recursive: true });
    writeFileSync(OUT, `${lines.join('\n')}\n`);
  }
  console.log(`\n${results.filter(passed).length}/${results.length} flows passed, ${problems.length} problems`);
  for (const p of problems) console.log(`  ${p.flow}: ${p.kind}: ${p.detail}`);
}

await main();
