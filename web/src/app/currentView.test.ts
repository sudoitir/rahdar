import { describe, expect, it } from 'vitest';

import { FEATURES } from './features.ts';
import { matchView, sameViewOn } from '../kernel/nav/currentView.ts';

describe('matchView (ADR-0109)', () => {
  it('names the view an address is under, by the longest navigation path', () => {
    const view = matchView('/clusters/c1/subscriptions/orders/messages', FEATURES);
    expect(view?.clusterId).toBe('c1');
    expect(view?.item?.label).toBe('Subscriptions');
    expect(view?.groupLabel).toBe('Messaging');
  });

  it('distinguishes a view from another that starts with the same letters', () => {
    expect(matchView('/clusters/c1/artemis/config-diff', FEATURES)?.item?.label).toBe('Config diff');
    expect(matchView('/clusters/c1/artemis/configuration', FEATURES)?.item?.label).toBe('Configuration');
  });

  it('is nothing outside a cluster, and no view on a cluster address no view claims', () => {
    expect(matchView('/admin', FEATURES)).toBeNull();
    expect(matchView('/clusters/c1', FEATURES)?.item).toBeUndefined();
  });
});

describe('sameViewOn', () => {
  it('opens the same view on another cluster, without its search', () => {
    expect(sameViewOn('c2', matchView('/clusters/c1/subscriptions', FEATURES))).toBe('/clusters/c2/subscriptions');
  });

  it("opens the cluster's landing page when there is no view to keep", () => {
    expect(sameViewOn('c2', null)).toBe('/clusters/c2');
  });
});
