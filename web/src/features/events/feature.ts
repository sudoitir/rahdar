import { IconBellRinging } from '@tabler/icons-react';
import { createRoute } from '@tanstack/react-router';

import { CONTRACT, defineFeature } from '../../kernel/feature.ts';
import { clusterRoute } from '../../kernel/routing/roots.ts';
import { lazyFeatureView } from '../../kernel/routing/lazy.tsx';

function validateEventsSearch(raw: Record<string, unknown>): Record<string, unknown> {
  const out: Record<string, unknown> = {};
  for (const k of ['type', 'destination'] as const) {
    if (typeof raw[k] === 'string' && raw[k]) out[k] = raw[k];
  }
  const event = Number(raw.event);
  if (Number.isInteger(event) && event > 0) out.event = event;
  const page = Number(raw.page);
  if (Number.isFinite(page) && page > 1) out.page = Math.floor(page);
  return out;
}

const eventsRoute = createRoute({
  getParentRoute: () => clusterRoute,
  path: 'events',
  component: lazyFeatureView('events', () => import('./EventsView.tsx'), 'EventsView'),
  validateSearch: validateEventsSearch,
});

/**
 * The cluster's broker notifications, recorded and live. The live feed mounts its own stream: its
 * frames carry the events themselves, with no resource behind them for a topic handler to refetch.
 */
export const eventsFeature = defineFeature({
  contract: CONTRACT,
  id: 'events',
  routes: { cluster: [eventsRoute] },
  nav: [
    {
      group: 'activity',
      order: 10,
      label: 'Events',
      icon: IconBellRinging,
      path: 'events',
      hotkey: 'e',
      permission: 'cluster:read',
    },
  ],
});
