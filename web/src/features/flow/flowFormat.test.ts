import { describe, expect, it } from 'vitest';

import { edgeText, rateLabel, rateSortValue, rateSourceLabel, totalRateLabel } from './flowFormat.ts';
import { flowQueryString } from './api.ts';
import { layersParam, parseFocus, parseLayers, validateFlowSearch } from './flowSearch.ts';

describe('flow formatting', () => {
  it('never shows an unknown rate as zero', () => {
    expect(rateLabel({ rate: undefined, rateSource: 'SAMPLER', kind: 'CONSUME' })).toBe('measuring…');
    expect(rateLabel({ rate: 0, rateSource: 'SAMPLER', kind: 'CONSUME' })).toBe('0 msg/s');
    expect(rateLabel({ rate: undefined, rateSource: 'NONE', kind: 'ROUTE' })).toBe('not counted by broker');
    expect(totalRateLabel(null)).toBe('measuring…');
  });

  it('names a slow-tier rate as an average', () => {
    expect(rateSourceLabel({ rateSource: 'SUBSCRIPTION_METRIC', averagedOverSeconds: 300 })).toBe(
      'queue metrics, ~5 min average',
    );
  });

  it('sorts unknown rates after known ones in either direction', () => {
    const rates = [3, undefined, 0, 7];
    const desc = [...rates].sort((a, b) => rateSortValue(b, true) - rateSortValue(a, true));
    const asc = [...rates].sort((a, b) => rateSortValue(a, false) - rateSortValue(b, false));
    expect(desc).toEqual([7, 3, 0, undefined]);
    expect(asc).toEqual([0, 3, 7, undefined]);
  });
});

describe('routing edge text', () => {
  it('says what a divert does, where it is missing, and that the broker does not count it', () => {
    expect(
      edgeText({
        kind: 'FORWARD',
        exclusive: true,
        filter: "color='red'",
        rateSource: 'NONE',
        stale: false,
        bypassed: false,
        rahdar: false,
        presentOn: 1,
        presentOf: 2,
        faults: ['PARTIAL_PRESENCE'],
      }),
    ).toBe('reroutes · filtered · not counted by broker · on 1 of 2 nodes');
  });

  it('names a bridge that is down, a bypassed route and a wildcard match in words', () => {
    expect(
      edgeText({
        kind: 'LINK',
        rate: 5,
        rateSource: 'SAMPLER',
        stale: false,
        bypassed: false,
        rahdar: false,
        faults: ['LINK_DOWN'],
      }),
    ).toBe('bridge · 5 msg/s · not connected');
    expect(
      edgeText({
        kind: 'ROUTE',
        delivery: 'SHARED',
        rateSource: 'SUBSCRIPTION_METRIC',
        rate: 2,
        stale: false,
        bypassed: true,
        rahdar: false,
        faults: [],
      }),
    ).toBe('shared · bypassed by an exclusive divert · 2 msg/s');
    expect(
      edgeText({ kind: 'WILDCARD', rateSource: 'NONE', stale: false, bypassed: false, rahdar: false, faults: [] }),
    ).toBe('matches');
  });
});

describe('flow search', () => {
  it('keeps only valid, non-default values', () => {
    expect(
      validateFlowSearch({
        focus: 'subscription:ORDERS.inbound',
        rank: 'IN',
        limit: '100',
        groupBy: 'HOST',
        hops: '9',
        sort: 'bad sort',
      }),
    ).toEqual({ focus: 'subscription:ORDERS.inbound', limit: 100, groupBy: 'HOST' });
    expect(validateFlowSearch({ focus: 'orders' })).toEqual({});
  });

  it('keeps the default layers out of the URL and says NONE for no layers', () => {
    expect(parseLayers(undefined)).toEqual(['CLUSTER', 'ROUTES']);
    expect(layersParam(['ROUTES', 'CLUSTER'])).toBeUndefined();
    expect(layersParam([])).toBe('NONE');
    expect(parseLayers('NONE')).toEqual([]);
    expect(validateFlowSearch({ layers: 'dead_letter,routes,bogus' })).toEqual({ layers: 'DEAD_LETTER,ROUTES' });
  });

  it('parses a focus with a colon in its name', () => {
    expect(parseFocus('destination:jms.topic:prices')).toEqual({ kind: 'destination', name: 'jms.topic:prices' });
  });

  it('always sends the bound and ranking the server should apply', () => {
    expect(flowQueryString({})).toBe('rank=IN&limit=40&groupBy=CLIENT_ID');
  });

  it('asks for the per-node breakdown only in the Split layout', () => {
    expect(flowQueryString({ tab: 'table' })).not.toContain('byNode');
    expect(flowQueryString({ tab: 'split' })).toContain('byNode=true');
  });

  it('keeps the layout, the selection and a non-default range', () => {
    expect(validateFlowSearch({ tab: 'split', node: 'subscription:ORDERS.inbound', range: '6h' })).toEqual({
      tab: 'split',
      node: 'subscription:ORDERS.inbound',
      range: '6h',
    });
    expect(validateFlowSearch({ tab: 'graph', range: '1h', node: '' })).toEqual({});
  });
});
