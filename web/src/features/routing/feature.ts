import { IconRoute } from '@tabler/icons-react';
import { createRoute } from '@tanstack/react-router';

import { CONTRACT, defineFeature } from '../../kernel/feature.ts';
import { clusterRoute } from '../../kernel/routing/roots.ts';
import { lazyFeatureView } from '../../kernel/routing/lazy.tsx';
import { validateResourceSearch, type ResourceSearch } from '../../kernel/routing/search.ts';
import { CopyRouteName, DeleteDivert } from './rowActions.tsx';

/**
 * The routes view's navigable state: the listing's filter, sort and page; which tab is open — the
 * routes or a `routes.tabs` contribution's id; and the string keys a contributed tab keeps in the
 * URL (the builder's open editor, its region anchor and its selection). A contribution narrows
 * those to what it accepts; this route only carries them, because it cannot know them.
 */
export interface RoutesSearch extends ResourceSearch {
  tab?: string;
  section?: string;
  item?: string;
  anchor?: string;
  selected?: string;
}

const PASSED_THROUGH = ['tab', 'section', 'item', 'anchor', 'selected'] as const;

export function validateRoutesSearch(raw: Record<string, unknown>): RoutesSearch {
  const out: RoutesSearch = validateResourceSearch(raw);
  for (const key of PASSED_THROUGH) {
    const value = raw[key];
    if (typeof value === 'string' && value) out[key] = value;
  }
  return out;
}

const routesRoute = createRoute({
  getParentRoute: () => clusterRoute,
  path: 'routes',
  component: lazyFeatureView('routing', () => import('./RoutesView.tsx'), 'RoutesView'),
  validateSearch: validateRoutesSearch,
});

/** The routes across the cluster, and whatever other features contribute as a tab. */
export const routingFeature = defineFeature({
  contract: CONTRACT,
  id: 'routing',
  routes: { cluster: [routesRoute] },
  nav: [
    {
      group: 'resources',
      order: 60,
      label: 'Routes',
      icon: IconRoute,
      path: 'routes',
      hotkey: 'o',
      permission: 'cluster:read',
    },
  ],
  slots: {
    'route.actions': [
      { id: 'routing.route.copy', order: 10, section: 'copy', Component: CopyRouteName },
      { id: 'routing.divert.delete', order: 10, section: 'destroy', Component: DeleteDivert },
    ],
  },
});
