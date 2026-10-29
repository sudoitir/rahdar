/**
 * The real approval screens of a Kafka offset reset and a truncate, from the isolated QA stack with the QA approval
 * provider installed (`sweep/approval-provider/install.sh`): the requester's screen after it asks, and the second
 * approver's page for the request. Light and dark at 1440x900. Nothing is approved: the requests are left held.
 *
 *   RAHDAR=http://127.0.0.1:18080 node --experimental-strip-types web/scripts/held-shots.ts
 *
 * Writes `openspec/changes/19e4-kafka/qa/captures/held/<request>-<requester|approver>-<scheme>.png`.
 */
import { chromium, type Page } from '@playwright/test';
import { mkdir, readFile } from 'node:fs/promises';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

import { offsetsAt } from './e2e-kafka/broker.ts';
import { BASE, login } from './session.ts';
import { storeScheme } from './sweep/probes.ts';

const WEB = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const OUT = resolve(WEB, '../openspec/changes/19e4-kafka/qa/captures/held');

async function hold(page: Page, name: RegExp) {
  const button = page.getByRole('dialog').getByRole('button', { name }).last();
  await button.hover();
  await page.mouse.down();
  await page.waitForTimeout(1900);
  await page.mouse.up();
  await page.waitForTimeout(1500);
}

async function main() {
  const browser = await chromium.launch();
  const creds = Object.fromEntries(
    (await readFile(resolve(WEB, '.sweep/auth/credentials.env'), 'utf8'))
      .split('\n')
      .filter((l) => l.includes('='))
      .map((l) => [l.slice(0, l.indexOf('=')), l.slice(l.indexOf('=') + 1)]),
  );
  const requesterState = {
    ...JSON.parse(await readFile(resolve(WEB, '.sweep/auth/requester.json'), 'utf8')),
    origins: [],
  };
  const admin = { ...JSON.parse(await readFile(resolve(WEB, '.sweep/auth/admin.json'), 'utf8')), origins: [] };
  const probe = await browser.newContext({ storageState: admin });
  const clusters = (
    (await (await probe.request.get(`${BASE}/api/v1/clusters?size=100`)).json()) as {
      data: { id: string; name: string }[];
    }
  ).data;
  const events = clusters.find((c) => c.name === 'events')?.id ?? '';
  await mkdir(OUT, { recursive: true });

  for (const scheme of ['light', 'dark'] as const) {
    const options = { viewport: { width: 1440, height: 900 }, colorScheme: scheme } as const;
    // One request each: a reset of analytics to the latest offset, a truncate of inventory five records past its log start.
    const requests = [
      {
        id: 'reset',
        open: async (page: Page) => {
          await page.goto(`${BASE}/clusters/${events}/kafka/groups/analytics`);
          await page.getByRole('button', { name: 'Reset offsets…' }).click();
          await page.getByRole('combobox', { name: 'Move to' }).click();
          await page.getByRole('option', { name: 'The latest offset' }).click();
          await page.getByRole('button', { name: 'Preview the reset' }).click();
          await page
            .getByRole('dialog')
            .getByRole('button', { name: /^Reset \d+ partitions?$/ })
            .waitFor();
          await hold(page, /^Reset \d+ partitions?$/);
        },
        summary: /analytics/,
      },
      {
        id: 'truncate',
        open: async (page: Page) => {
          await page.goto(`${BASE}/clusters/${events}/destinations`);
          await page.getByRole('button', { name: 'Actions for inventory' }).first().click();
          await page.getByRole('menuitem', { name: /Truncate/ }).click();
          await page
            .getByRole('dialog')
            .getByRole('textbox', { name: /Delete records before/ })
            .fill(`0:${offsetsAt('inventory', -2)[0] + 5}`);
          await page.getByRole('button', { name: 'Preview the cut' }).click();
          await page
            .getByRole('dialog')
            .getByRole('button', { name: /^Truncate [\d,]+ records?$/ })
            .waitFor();
          await hold(page, /^Truncate [\d,]+ records?$/);
        },
        summary: /inventory/,
      },
    ];
    for (const request of requests) {
      const requester = await browser.newContext({ ...options, storageState: requesterState });
      await requester.addInitScript({ content: storeScheme(scheme) });
      const page = await requester.newPage();
      await request.open(page);
      await page.mouse.move(0, 0);
      await page.screenshot({ path: resolve(OUT, `${request.id}-requester-${scheme}.png`) });
      const held = (await (
        await requester.request.get(`${BASE}/api/v1/held-operations?scope=MINE&state=HELD&limit=50`)
      ).json()) as {
        items: { id: string; summary: string }[];
      };
      const mine = held.items.find((h) => request.summary.test(h.summary));
      await requester.close();
      if (!mine) {
        console.log(`FAILED  ${request.id}-${scheme}: no held request`);
        continue;
      }
      const approver = await browser.newContext(options);
      await approver.addInitScript({ content: storeScheme(scheme) });
      const approverPage = await approver.newPage();
      await login(approverPage, 'qa-approver', creds.QA_USER_PASSWORD);
      await approverPage.goto(`${BASE}/approvals/${mine.id}`);
      await approverPage
        .getByRole('button', { name: /^Approve/ })
        .first()
        .waitFor({ timeout: 30_000 });
      await approverPage.waitForTimeout(800);
      await approverPage.screenshot({ path: resolve(OUT, `${request.id}-approver-${scheme}.png`) });
      await approver.close();
      // Leave nothing held behind: the requester cancels it.
      const cleanup = await browser.newContext({ storageState: requesterState });
      const token = (await cleanup.cookies()).find((c) => /xsrf/i.test(c.name))?.value ?? '';
      await cleanup.request.post(`${BASE}/api/v1/held-operations/${mine.id}/cancel`, {
        headers: { 'X-XSRF-TOKEN': token },
      });
      await cleanup.close();
      console.log(`ok      ${request.id}-${scheme}`);
    }
  }
  await browser.close();
}

await main();
