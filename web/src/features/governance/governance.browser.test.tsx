import { describe, expect, it } from 'vitest';
import { screen } from '@testing-library/react';

import { axeViolations, contentWidth, Frame, renderThemed, SCHEMES, settle } from '../../test/browser.tsx';
import { DataTable } from '../../ui/table/index.ts';
import type { FindingView, RuleView } from './api.ts';
import { findingColumns, ruleColumns } from './columns.ts';

/**
 * The governance tables as Chromium lays them out. The rules page once measured 4435 px wide in a
 * 1280 px window: long selectors and address patterns sat in a table that grew with them instead of
 * shortening or wrapping inside the content box.
 */

const LONG_SELECTOR = 'payment.customer.billing.address.line1.customerIdentificationNumber.verified'.repeat(2);

const rule = (over: Partial<RuleView> = {}): RuleView =>
  ({
    id: 'r1',
    destinationPattern: null,
    target: 'BODY_PATH',
    selector: 'authorization',
    dataClass: 'CREDENTIAL',
    dataClassLabel: 'credential',
    action: null,
    defaultAction: 'DROP',
    builtin: false,
    enabled: true,
    exception: false,
    updatedAt: '2026-09-14T00:00:00Z',
    ...over,
  }) as RuleView;

const finding = (over: Partial<FindingView> = {}): FindingView =>
  ({
    id: 'f1',
    destination: 'orders.eu.customers.registration.verification.requests',
    location: 'BODY',
    fieldPath: LONG_SELECTOR,
    dataClass: 'EMAIL',
    dataClassLabel: 'email',
    status: 'OPEN',
    hitCount: 1200,
    firstSeenAt: '2026-09-14T00:00:00Z',
    lastSeenAt: '2026-09-14T01:00:00Z',
    ...over,
  }) as FindingView;

const rules = [
  rule({
    selector: LONG_SELECTOR,
    destinationPattern: 'orders.eu.customers.registration.verification.#',
    exception: true,
  }),
  rule({ id: 'r2', builtin: true, target: 'HEADER' }),
  rule({ id: 'r3', selector: 'customerEmail', target: 'PROPERTY' }),
];

const noop = () => {};

const rulesTable = (width: number) => (
  <Frame width={width}>
    <DataTable
      variant="static"
      label="Masking rules"
      columns={ruleColumns({
        controls: { canWrite: true, savingId: undefined, onToggle: noop, onEdit: noop, onDelete: noop },
      })}
      data={rules}
      rowKey={(r) => r.id}
      empty={null}
    />
  </Frame>
);

const findingsTable = (width: number) => (
  <Frame width={width}>
    <DataTable
      variant="static"
      label="Classification findings"
      columns={findingColumns({
        zone: 'auto',
        controls: { canWrite: true, deciding: null, onDecide: noop },
      })}
      data={[finding(), finding({ id: 'f2', status: 'DISMISSED', location: 'HEADER', fieldPath: 'x-email' })]}
      rowKey={(f) => f.id}
      empty={null}
    />
  </Frame>
);

describe.each([1280, 1920])('governance tables in a %i px window', (window) => {
  const width = contentWidth(window);

  it.each([
    ['rules', rulesTable],
    ['findings', findingsTable],
  ])('keeps the %s table inside its box with long values', async (_name, table) => {
    renderThemed(table(width), 'light');
    const element = await screen.findByRole('table');
    await settle(() => [...element.querySelectorAll('th')].map((th) => th.getBoundingClientRect().width).join(','));
    const frame = element.parentElement!;
    expect(frame.scrollWidth).toBeLessThanOrEqual(frame.clientWidth);
    expect(document.documentElement.scrollWidth).toBeLessThanOrEqual(document.documentElement.clientWidth);
  });
});

describe.each(SCHEMES)('governance tables in the %s scheme', (scheme) => {
  it.each([
    ['rules', rulesTable],
    ['findings', findingsTable],
  ])('has no accessibility violations in the %s table', async (_name, table) => {
    const { container } = renderThemed(table(contentWidth(1280)), scheme);
    await screen.findByRole('table');
    expect(await axeViolations(container)).toEqual([]);
  });
});
