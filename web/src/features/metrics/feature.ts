import { IconChartLine } from '@tabler/icons-react';
import { createRoute } from '@tanstack/react-router';

import { CONTRACT, defineFeature } from '../../kernel/feature.ts';
import { clusterRoute } from '../../kernel/routing/roots.ts';
import { lazyFeatureView, lazySlot } from '../../kernel/routing/lazy.tsx';
import { METRIC_RANGES, type MetricRange } from './ranges.ts';
import { OpenSubscriptionHistory } from './rowActions.tsx';

export interface MetricsSearch {
  range?: MetricRange;
  from?: string;
  to?: string;
  /** Subscription name to scope the series to; absent means cluster-wide. */
  subject?: string;
  /** `node`: a subscription's series also broken down per broker node. Ignored without a subscription. */
  split?: 'node';
}

function validateMetricsSearch(raw: Record<string, unknown>): MetricsSearch {
  const out: MetricsSearch = {};
  // The scope survives a range change and an absolute window alike, so it is read
  // before either branch returns.
  if (typeof raw.subject === 'string' && raw.subject) out.subject = raw.subject;
  if (raw.split === 'node') out.split = 'node';
  if (typeof raw.from === 'string' && raw.from && typeof raw.to === 'string' && raw.to) {
    out.from = raw.from;
    out.to = raw.to;
    return out;
  }
  if (typeof raw.range === 'string' && (METRIC_RANGES as readonly string[]).includes(raw.range)) {
    out.range = raw.range as MetricRange;
  }
  return out;
}

const metricsRoute = createRoute({
  getParentRoute: () => clusterRoute,
  path: 'metrics',
  component: lazyFeatureView('metrics', () => import('./MetricsView.tsx'), 'MetricsView'),
  validateSearch: validateMetricsSearch,
});

/** Time series of depth, throughput and consumers, cluster-wide and per subscription. */
export const metricsFeature = defineFeature({
  contract: CONTRACT,
  id: 'metrics',
  routes: { cluster: [metricsRoute] },
  nav: [
    {
      group: 'observe',
      order: 20,
      label: 'Metrics',
      icon: IconChartLine,
      path: 'metrics',
      hotkey: 'm',
      permission: 'cluster:read',
    },
  ],
  slots: {
    'subscription.actions': [{ id: 'metrics.history', order: 30, section: 'open', Component: OpenSubscriptionHistory }],
    'subscription.detail.panels': [
      {
        id: 'metrics-subscription-history',
        order: 10,
        Component: lazySlot(() => import('./SubscriptionHistoryPanels.tsx'), 'SubscriptionHistoryPanels'),
      },
    ],
    'flow.selection.panels': [
      {
        id: 'metrics.node-split',
        order: 10,
        Component: lazySlot(() => import('./NodeSplitPanels.tsx'), 'NodeSplitPanels'),
      },
    ],
  },
});
