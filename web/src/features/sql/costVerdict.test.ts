import { describe, expect, it } from 'vitest';

import { ApiError } from '../../kernel/api/request.ts';
import type { SqlPlanView } from './api.ts';
import { costVerdict, type CostInput } from './costVerdict.ts';

const target = (nodeId: string, subscriptionName: string) => ({
  nodeId,
  nodeName: nodeId,
  subscription: `artemis.queue/${subscriptionName}`,
  destination: `artemis.address/${subscriptionName}`,
  messageCount: 10,
});

const plan = (over: Record<string, unknown> = {}): SqlPlanView => ({
  source: 'BROKER',
  targets: [target('n1', 'ORDER.IN')],
  requiresScan: false,
  captured: false,
  effectiveLimit: 100,
  estimatedMessagesExamined: 0,
  pushedDown: [],
  scanned: [],
  notices: [],
  ...over,
});

const input = (over: Partial<CostInput> = {}): CostInput => ({
  text: 'SELECT * FROM "ORDER.IN"',
  debounced: 'SELECT * FROM "ORDER.IN"',
  blockedReason: null,
  plan: plan(),
  planError: null,
  planPending: false,
  ...over,
});

describe('costVerdict', () => {
  it('says the editor is empty, whatever else is true', () => {
    expect(costVerdict(input({ text: '  ', debounced: '', blockedReason: 'You may not.' }))).toEqual({
      badge: 'Unavailable',
      tone: 'neutral',
      sentence: 'Not estimated: the editor is empty.',
    });
  });

  it('says why the console is blocked, once, with a full stop', () => {
    expect(costVerdict(input({ blockedReason: 'Browsing messages needs the message read permission.' }))).toEqual({
      badge: 'Unavailable',
      tone: 'neutral',
      sentence: 'Not estimated: Browsing messages needs the message read permission.',
    });
    expect(costVerdict(input({ blockedReason: 'message access is unavailable' })).sentence).toBe(
      'Not estimated: message access is unavailable.',
    );
  });

  it('says it is estimating while the text is ahead of the plan, and while the plan is fetching', () => {
    const estimating = { badge: 'Estimating', tone: 'neutral', sentence: 'Estimating the edited query…' };
    expect(costVerdict(input({ text: 'SELECT 1', debounced: 'SELECT' }))).toEqual(estimating);
    expect(costVerdict(input({ planPending: true }))).toEqual(estimating);
    // A stale plan error does not outlive an edit.
    const stale = new ApiError(400, { detail: 'No.' });
    expect(costVerdict(input({ text: 'SELECT 1', debounced: 'SELECT', planError: stale }))).toEqual(estimating);
  });

  it('quotes a syntax error, with the server’s suggestion', () => {
    const error = new ApiError(400, { detail: 'JOIN is not part of this dialect.', offending: 'JOIN' });
    expect(costVerdict(input({ planError: error }))).toEqual({
      badge: 'Unavailable',
      tone: 'danger',
      sentence: 'Not estimated: JOIN is not part of this dialect.',
    });
    const suggested = new ApiError(400, { detail: 'Unknown keyword SELCT.', offending: 'SELCT', suggestion: 'SELECT' });
    expect(costVerdict(input({ planError: suggested })).sentence).toBe(
      'Not estimated: Unknown keyword SELCT. Did you mean SELECT?',
    );
    expect(costVerdict(input({ planError: new ApiError(400, { title: 'Bad query' }) })).sentence).toBe(
      'Not estimated: Bad query',
    );
  });

  it('names any other plan failure by the title the error state would show, as a warning', () => {
    expect(costVerdict(input({ planError: new ApiError(403, { permission: 'message:read' }) }))).toEqual({
      badge: 'Unavailable',
      tone: 'warning',
      sentence: 'Not estimated: You are not allowed to do this.',
    });
    expect(costVerdict(input({ planError: new ApiError(500, {}) })).sentence).toBe(
      'Not estimated: Rahdar failed to complete the request.',
    );
  });

  it('says there is no plan when none came, rather than inventing a cost', () => {
    expect(costVerdict(input({ plan: undefined }))).toMatchObject({
      badge: 'Unavailable',
      sentence: 'Not estimated: Rahdar has no plan for this query.',
    });
  });

  it('says a query with no target reads nothing', () => {
    expect(costVerdict(input({ plan: plan({ targets: [] }) }))).toEqual({
      badge: 'No cost',
      tone: 'neutral',
      sentence: 'Reads nothing: no subscription matches the FROM pattern.',
    });
  });

  it('says the index is read, with the row limit and the subscriptions, and that no broker is', () => {
    const indexed = plan({
      source: 'INDEX',
      effectiveLimit: 1000,
      targets: [target('n1', 'A'), target('n2', 'A'), target('n1', 'B')],
    });
    expect(costVerdict(input({ plan: indexed }))).toEqual({
      badge: 'Index',
      tone: 'neutral',
      sentence: "Reads Rahdar's index, up to 1,000 rows from 2 subscriptions. No broker is read.",
    });
  });

  it('warns that a scan reads and examines messages, with subscriptions and nodes', () => {
    const scan = plan({
      requiresScan: true,
      estimatedMessagesExamined: 1_200_000,
      targets: [target('n1', 'A'), target('n2', 'A'), target('n3', 'B')],
    });
    expect(costVerdict(input({ plan: scan }))).toEqual({
      badge: 'Scan',
      tone: 'warning',
      sentence: 'Rahdar reads and examines about 1,200,000 messages on 2 subscriptions across 3 nodes.',
    });
  });

  it('says one subscription on one node in the singular', () => {
    const scan = plan({ requiresScan: true, estimatedMessagesExamined: 1 });
    expect(costVerdict(input({ plan: scan })).sentence).toBe(
      'Rahdar reads and examines about 1 message on 1 subscription across 1 node.',
    );
  });

  it('says the brokers filter, and bounds what Rahdar examines by the row limit', () => {
    expect(costVerdict(input())).toEqual({
      badge: 'Broker-filtered',
      tone: 'neutral',
      sentence: 'The brokers filter; Rahdar examines at most 100 messages from 1 subscription.',
    });
  });

  it('never shows an unknown figure as 0', () => {
    const scan = plan({ requiresScan: true, estimatedMessagesExamined: undefined });
    expect(costVerdict(input({ plan: scan })).sentence).toBe(
      'Rahdar reads and examines about an unknown number of messages on 1 subscription across 1 node.',
    );
    expect(costVerdict(input({ plan: plan({ effectiveLimit: undefined }) })).sentence).toBe(
      'The brokers filter; Rahdar examines at most an unknown number of messages from 1 subscription.',
    );
    expect(costVerdict(input({ plan: plan({ source: 'INDEX', effectiveLimit: undefined }) })).sentence).toContain(
      'up to an unknown number of rows',
    );
  });
});
