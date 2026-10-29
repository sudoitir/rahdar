import { IconSitemap } from '@tabler/icons-react';
import { createRoute, redirect } from '@tanstack/react-router';

import { CONTRACT, defineFeature } from '../../kernel/feature.ts';
import { clusterRoute, rootRoute } from '../../kernel/routing/roots.ts';
import { lazyFeatureView, lazySlot } from '../../kernel/routing/lazy.tsx';
import { keys } from './api.ts';
import { ClusterHeader } from './ClusterHeader.tsx';
import { ClusterPalette } from './ClusterPalette.tsx';
import { RemoveClusterSection } from './RemoveClusterSection.tsx';
import { ClusterSwitcher } from './ClusterSwitcher.tsx';
import { CapabilitiesSection, ConnectionSection, RegisterSection } from './ClusterSettings.tsx';
import { EnvironmentsPanel } from './EnvironmentsPanel.tsx';
import { validateTopologySearch } from './topologySearch.ts';

/** A cluster's own address opens its topology. */
const clusterIndexRoute = createRoute({
  getParentRoute: () => clusterRoute,
  path: '/',
  beforeLoad: ({ params }) => {
    throw redirect({ to: `/clusters/${params.clusterId}/topology` });
  },
});

const topologyRoute = createRoute({
  getParentRoute: () => clusterRoute,
  path: 'topology',
  validateSearch: validateTopologySearch,
  component: lazyFeatureView('clusters', () => import('./TopologyView.tsx'), 'TopologyView'),
});

/** Registering a cluster: a page of its own outside any cluster, with the chosen system in its address. */
const registerRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: 'clusters/new',
  validateSearch: (raw: Record<string, unknown>): { system?: string } =>
    typeof raw.system === 'string' && raw.system ? { system: raw.system } : {},
  component: lazyFeatureView('clusters', () => import('./RegisterClusterPage.tsx'), 'RegisterClusterPage'),
});

/** Registered clusters, their topology and environments: the platform every other feature works on. */
export const clustersFeature = defineFeature({
  contract: CONTRACT,
  id: 'clusters',
  routes: { root: [registerRoute], cluster: [clusterIndexRoute, topologyRoute] },
  nav: [
    {
      group: 'observe',
      order: 10,
      label: 'Topology',
      icon: IconSitemap,
      path: 'topology',
      hotkey: 't',
      permission: 'cluster:read',
    },
  ],
  palette: ClusterPalette,
  slots: {
    'shell.navbar': [{ id: 'clusters-switcher', order: 10, Component: ClusterSwitcher }],
    'home.estate': [
      { id: 'clusters-estate', order: 10, Component: lazySlot(() => import('./EstateView.tsx'), 'EstateView') },
    ],
    'cluster.header': [{ id: 'clusters-header', order: 10, Component: ClusterHeader }],
    'settings.sections': [
      { id: 'clusters-register', order: 30, group: 'rahdar', title: 'Clusters', Component: RegisterSection },
      {
        id: 'clusters-connection',
        order: 40,
        group: 'cluster',
        title: 'Connection',
        Component: ConnectionSection,
      },
      {
        id: 'clusters-capabilities',
        order: 50,
        group: 'cluster',
        title: 'Connection capabilities',
        Component: CapabilitiesSection,
      },
      // Last in the cluster's group, whatever other features contribute to it.
      {
        id: 'clusters-remove',
        order: 1000,
        group: 'cluster',
        title: 'Remove cluster',
        Component: RemoveClusterSection,
      },
    ],
    'admin.tabs': [
      { id: 'environments', order: 30, title: 'Environments', group: 'installation', Component: EnvironmentsPanel },
    ],
  },
  streamTopics: {
    topology: ({ clusterId, invalidate }) => invalidate(keys.topology(clusterId)),
    health: ({ clusterId, invalidate }) => invalidate(keys.health(clusterId)),
  },
});
