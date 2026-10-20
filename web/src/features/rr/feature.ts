import { IconArrowsExchange } from '@tabler/icons-react';
import { createRoute } from '@tanstack/react-router';

import { clusterKey } from '../../kernel/api/request.ts';
import { CONTRACT, defineFeature } from '../../kernel/feature.ts';
import { clusterRoute } from '../../kernel/routing/roots.ts';
import { lazyFeatureView, lazySlot } from '../../kernel/routing/lazy.tsx';

function validateRrSearch(raw: Record<string, unknown>): Record<string, unknown> {
  const out: Record<string, unknown> = {};
  for (const k of ['tab', 'state', 'destination'] as const) {
    if (typeof raw[k] === 'string' && raw[k]) out[k] = raw[k];
  }
  const page = Number(raw.page);
  if (Number.isFinite(page) && page > 1) out.page = Math.floor(page);
  return out;
}

const rrRoute = createRoute({
  getParentRoute: () => clusterRoute,
  path: 'rr',
  component: lazyFeatureView('rr', () => import('./FlowsView.tsx'), 'FlowsView'),
  validateSearch: validateRrSearch,
});

/** Request-reply tracing: expectations, flows, and the latency they add up to. */
export const rrFeature = defineFeature({
  contract: CONTRACT,
  id: 'rr',
  routes: { cluster: [rrRoute] },
  nav: [
    {
      group: 'observe',
      order: 40,
      label: 'Requests',
      icon: IconArrowsExchange,
      path: 'rr',
      hotkey: 'r',
      permission: 'cluster:read',
    },
  ],
  slots: {
    'metrics.panels': [
      { id: 'rr-latency', order: 10, Component: lazySlot(() => import('./LatencyCard.tsx'), 'LatencyCard') },
    ],
  },
  streamTopics: {
    rr: ({ clusterId, invalidate }) => invalidate(clusterKey(clusterId, 'rr')),
  },
});
