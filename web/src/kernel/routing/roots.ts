import { createElement, type ComponentType, type ReactElement } from 'react';
import { createRootRoute, createRoute, lazyRouteComponent } from '@tanstack/react-router';

import { LoginView } from '../auth/LoginView.tsx';
import type { ModuleId } from '../feature.ts';
import { PluginUnavailable } from '../plugins/PluginUnavailable.tsx';
import { ClusterLayout } from '../shell/ClusterLayout.tsx';
import { FeatureGate } from '../shell/FeatureGate.tsx';
import { HomeView } from '../shell/HomeView.tsx';
import { validateResourceSearch, type ResourceSearch } from './search.ts';
import { RootLayout } from '../shell/RootLayout.tsx';

/** The root every page renders inside: the shell. A feature's `routes.root` are its children. */
export const rootRoute = createRootRoute({ component: RootLayout });

/** One cluster's layout. A feature's `routes.cluster` are its children. */
export const clusterRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: 'clusters/$clusterId',
  component: ClusterLayout,
});

/** The estate's subscriptions filter on a name and a cluster, sort and page; the URL owns them. */
const indexRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: '/',
  component: HomeView,
  validateSearch: (raw: Record<string, unknown>): ResourceSearch & { cluster?: string } => ({
    ...validateResourceSearch(raw),
    ...(typeof raw.cluster === 'string' && raw.cluster ? { cluster: raw.cluster } : {}),
  }),
});

const loginRoute = createRoute({ getParentRoute: () => rootRoute, path: 'login', component: LoginView });

/**
 * The open tab is an `admin.tabs` contribution's id; the page falls back to the first tab for any
 * other. `plugin` is the plugin whose details are open on the Plugins tab; `upload` reopens an
 * inspected upload's review, which is where a single-sign-on step-up returns to. `view` is the Data
 * tab's open view. `team` is the team open on the Teams tab, and `teamTab` the section of it; `teamQ` and
 * `teamSort` filter and sort the list of teams.
 */
const adminRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: 'admin',
  component: lazyRouteComponent(() => import('../shell/AdminView.tsx'), 'AdminView'),
  validateSearch: (
    raw: Record<string, unknown>,
  ): {
    tab?: string;
    plugin?: string;
    upload?: string;
    view?: 'retention' | 'health';
    team?: string;
    teamTab?: string;
    teamQ?: string;
    teamSort?: string;
  } => ({
    ...(typeof raw.tab === 'string' && raw.tab ? { tab: raw.tab } : {}),
    ...(typeof raw.plugin === 'string' && raw.plugin ? { plugin: raw.plugin } : {}),
    ...(typeof raw.upload === 'string' && /^[0-9a-f]{64}$/.test(raw.upload) ? { upload: raw.upload } : {}),
    ...(raw.view === 'retention' || raw.view === 'health' ? { view: raw.view } : {}),
    ...(typeof raw.team === 'string' && raw.team ? { team: raw.team } : {}),
    ...(typeof raw.teamTab === 'string' && raw.teamTab ? { teamTab: raw.teamTab } : {}),
    ...(typeof raw.teamQ === 'string' && raw.teamQ ? { teamQ: raw.teamQ } : {}),
    ...(typeof raw.teamSort === 'string' && raw.teamSort ? { teamSort: raw.teamSort } : {}),
  }),
});

const accountRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: 'account',
  component: lazyRouteComponent(() => import('../shell/AccountView.tsx'), 'AccountView'),
});

/** The signed-in user's notices; `filter=unread` narrows them to the unread ones. */
const inboxRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: 'inbox',
  component: lazyRouteComponent(() => import('../inbox/InboxView.tsx'), 'InboxView'),
  validateSearch: (raw: Record<string, unknown>): { filter?: 'unread' } =>
    raw.filter === 'unread' ? { filter: 'unread' } : {},
});

/** Approval requests: those waiting for the user's decision, or with `tab=mine` the user's own. */
const approvalsRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: 'approvals',
  component: lazyRouteComponent(() => import('../approvals/ApprovalsView.tsx'), 'ApprovalsView'),
  validateSearch: (raw: Record<string, unknown>): { tab?: 'mine' } => (raw.tab === 'mine' ? { tab: 'mine' } : {}),
});

/** One approval request's page, which every held outcome and approval notice links to (`ui/held.ts`). */
const approvalRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: 'approvals/$id',
  component: lazyRouteComponent(() => import('../approvals/ApprovalView.tsx'), 'ApprovalView'),
});

/**
 * Every address under a plugin's `p/<id>/` that none of its own routes answer — including every
 * address of a plugin that is not running — explains why (ADR-0100). A plugin's own routes are
 * more specific, so they always win.
 */
const pluginRootFallback = createRoute({
  getParentRoute: () => rootRoute,
  path: 'p/$pluginId/$',
  component: PluginUnavailable,
});

export const pluginClusterFallback = createRoute({
  getParentRoute: () => clusterRoute,
  path: 'p/$pluginId/$',
  component: PluginUnavailable,
});

/** The shell's own pages, beside the cluster layout. */
export const shellRoutes = [
  indexRoute,
  loginRoute,
  adminRoute,
  accountRoute,
  inboxRoute,
  approvalsRoute,
  approvalRoute,
  pluginRootFallback,
];

/** A feature's view, or the page explaining that the feature is disabled on this installation (feature-modules spec). */
export function featureView(feature: ModuleId, View: ComponentType): () => ReactElement {
  return function FeatureView() {
    return createElement(FeatureGate, { feature, children: createElement(View) });
  };
}
