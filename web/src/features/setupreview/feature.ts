import { IconStethoscope } from '@tabler/icons-react';
import { createRoute } from '@tanstack/react-router';

import { CONTRACT, defineFeature } from '../../kernel/feature.ts';
import { clusterRoute } from '../../kernel/routing/roots.ts';
import { lazyFeatureView } from '../../kernel/routing/lazy.tsx';
import { keys } from './api.ts';
import { SEVERITY_FILTERS, type SeverityFilter } from './words.ts';

export interface SetupReviewSearch {
  severity?: SeverityFilter;
  accepted?: 'hide';
}

const setupReviewRoute = createRoute({
  getParentRoute: () => clusterRoute,
  path: 'artemis/setup-review',
  component: lazyFeatureView('setupreview', () => import('./SetupReviewView.tsx'), 'SetupReviewView', 'artemis'),
  validateSearch: (raw: Record<string, unknown>): SetupReviewSearch => ({
    severity: (SEVERITY_FILTERS as readonly string[]).includes(String(raw.severity))
      ? (raw.severity as SeverityFilter)
      : undefined,
    accepted: raw.accepted === 'hide' ? 'hide' : undefined,
  }),
});

/**
 * Setup review (ADR-0106): the cluster's HA, clustering, durability and message-safety
 * configuration against a catalogue of known mistakes, each with the evidence and the fix.
 */
export const setupreviewFeature = defineFeature({
  contract: CONTRACT,
  id: 'setupreview',
  routes: { cluster: [setupReviewRoute] },
  nav: [
    {
      group: 'configuration',
      // Before Configuration (10): what is wrong comes before declaring what should be.
      order: 5,
      label: 'Setup review',
      icon: IconStethoscope,
      system: 'artemis',
      path: 'artemis/setup-review',
      permission: 'cluster:read',
    },
  ],
  streamTopics: {
    'setup-review': ({ clusterId, invalidate }) => invalidate(keys.review(clusterId)),
  },
});
