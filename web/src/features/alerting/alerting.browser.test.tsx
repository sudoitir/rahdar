import { describe, expect, it } from 'vitest';
import { screen } from '@testing-library/react';
import { Button } from '@mantine/core';

import { axeViolations, contentWidth, Frame, renderThemed, SCHEMES, settle } from '../../test/browser.tsx';
import { DataTable } from '../../ui/table/index.ts';
import type { AlertDeliveryView, AlertFiringView, AlertRuleView, NotificationChannelView } from './api.ts';
import { channelColumns, deliveryColumns, firingColumns, historyColumns, ruleColumns } from './columns.ts';

/**
 * The alerting tables as Chromium lays them out, with values as long as a real queue or channel name
 * can be. The notification channels section once measured 925 px wide in a 640 px box.
 */

const LONG = 'customer-order-fulfilment-backlog-on-the-eu-west-primary-broker-pair-'.repeat(2);

const rule = (over: Partial<AlertRuleView> = {}): AlertRuleView =>
  ({
    id: 'r1',
    clusterId: 'c1',
    name: LONG,
    kind: 'METRIC_THRESHOLD',
    metric: 'acme-notes:edits-per-note-per-minute',
    comparator: 'GT',
    threshold: 1000,
    stateCondition: null,
    forSeconds: 60,
    severity: 'CRITICAL',
    enabled: true,
    channelIds: ['ch1', 'ch2'],
    sourceAvailable: false,
    ...over,
  }) as AlertRuleView;

const firing = (over: Partial<AlertFiringView> = {}): AlertFiringView =>
  ({
    seq: 1,
    ruleId: 'r1',
    clusterId: null,
    ruleName: LONG,
    subjectKey: `subscription:${LONG}`,
    severity: 'WARNING',
    startedAt: '2026-09-11T10:00:00Z',
    resolvedAt: null,
    value: 12000,
    ...over,
  }) as AlertFiringView;

const channel = (over: Partial<NotificationChannelView> = {}): NotificationChannelView =>
  ({
    id: 'ch1',
    name: LONG,
    kind: 'EMAIL',
    config: JSON.stringify({ host: 'smtp.example.com', port: 587, from: 'a@example.com', to: [`${LONG}@example.com`] }),
    enabled: true,
    hasSecret: false,
    boundRuleCount: 3,
    health: {
      lastState: 'DEAD',
      lastCreatedAt: '2026-09-11T10:00:00Z',
      lastDeliveredAt: null,
      lastError: `Teams responded 404 NOT_FOUND for ${LONG}`,
      pending: 2,
      failedLast24h: 2,
      sentLast24h: 5,
    },
    ...over,
  }) as NotificationChannelView;

const delivery = (over: Partial<AlertDeliveryView> = {}): AlertDeliveryView =>
  ({
    seq: 1,
    ruleId: 'r1',
    summary: `Queue ${LONG} backlog`,
    state: 'DEAD',
    attempts: 5,
    lastError: `HTTP 404 from ${LONG}`,
    createdAt: '2026-09-11T10:00:00Z',
    nextAttemptAt: '2026-09-11T10:05:00Z',
    deliveredAt: null,
    ...over,
  }) as AlertDeliveryView;

const noop = () => {};

const TABLES = {
  rules: (
    <DataTable
      variant="static"
      label="Alert rules"
      columns={ruleColumns({
        channelNames: new Map([
          ['ch1', LONG],
          ['ch2', 'ops'],
        ]),
        controls: { canWrite: true, savingId: undefined, onToggle: noop, onEdit: noop, onDelete: noop },
      })}
      data={[
        rule(),
        rule({ id: 'r2', clusterId: null, name: 'Quota watch', kind: 'STATE', stateCondition: 'STORAGE_QUOTA' }),
      ]}
      rowKey={(r) => r.id}
      empty={null}
    />
  ),
  firing: (
    <DataTable
      variant="static"
      label="Firing alerts"
      columns={firingColumns('auto')}
      data={[firing(), firing({ seq: 2, clusterId: 'c1', value: null })]}
      rowKey={(f) => String(f.seq)}
      empty={null}
    />
  ),
  history: (
    <DataTable
      variant="static"
      label="Alert history"
      columns={historyColumns('auto')}
      data={[firing(), firing({ seq: 2, resolvedAt: '2026-09-11T11:00:00Z' })]}
      rowKey={(f) => String(f.seq)}
      empty={null}
    />
  ),
  channels: (
    <DataTable
      variant="static"
      label="Notification channels"
      columns={channelColumns({
        now: Date.parse('2026-09-11T10:05:00Z'),
        controls: { canWrite: true, testingId: undefined, onTest: noop, onLog: noop, onEdit: noop, onDelete: noop },
      })}
      data={[
        channel(),
        channel({ id: 'ch2', name: 'ops-slack', kind: 'SLACK', config: '{}', hasSecret: true, health: null }),
      ]}
      rowKey={(c) => c.id}
      empty={null}
    />
  ),
  deliveries: (
    <DataTable
      variant="static"
      label="Deliveries"
      columns={deliveryColumns('auto', () => (
        <Button size="compact-xs">Retry</Button>
      ))}
      data={[delivery(), delivery({ seq: 2, state: 'SENT', lastError: null, deliveredAt: '2026-09-11T10:01:00Z' })]}
      rowKey={(d) => String(d.seq)}
      empty={null}
    />
  ),
};

/** The width of the notification channels box in the settings page, and of a drawer, at the smallest window. */
const WIDTHS = [contentWidth(1280), 640];

describe.each(WIDTHS)('alerting tables in a %i px box', (width) => {
  it.each(Object.entries(TABLES))('keeps the %s table inside its box with long values', async (_name, table) => {
    renderThemed(<Frame width={width}>{table}</Frame>, 'light');
    const element = await screen.findByRole('table');
    await settle(() => [...element.querySelectorAll('th')].map((th) => th.getBoundingClientRect().width).join(','));
    const frame = element.parentElement!;
    expect(frame.scrollWidth).toBeLessThanOrEqual(frame.clientWidth);
  });
});

describe.each(SCHEMES)('alerting tables in the %s scheme', (scheme) => {
  it.each(Object.entries(TABLES))('has no accessibility violations in the %s table', async (_name, table) => {
    const { container } = renderThemed(<Frame width={contentWidth(1280)}>{table}</Frame>, scheme);
    await screen.findByRole('table');
    expect(await axeViolations(container)).toEqual([]);
  });
});
