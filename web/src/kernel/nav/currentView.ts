import { useMemo } from 'react';
import { useLocation } from '@tanstack/react-router';

import type { NavContribution, RahdarFeature } from '../feature.ts';
import { useFeatures } from '../features.ts';
import { NAV_GROUPS } from './groups.ts';

export interface CurrentView {
  clusterId: string;
  /** The navigation entry the address is under, when there is one. */
  item?: NavContribution;
  /** Its group's label. */
  groupLabel?: string;
}

const CLUSTER_PATH = /^\/clusters\/([^/]+)(?:\/(.*))?$/;

function trimTrailingSlashes(path: string): string {
  let end = path.length;
  while (end > 0 && path[end - 1] === '/') end -= 1;
  return path.slice(0, end);
}

/**
 * Which cluster view an address is under (ADR-0109): the navigation entry whose path is the longest
 * prefix of the path after `/clusters/<id>/`, so a subscription's message browser is under Subscriptions and a
 * bulk run under Bulk runs. Null outside any cluster.
 */
export function matchView(pathname: string, features: RahdarFeature[]): CurrentView | null {
  const match = CLUSTER_PATH.exec(pathname);
  if (!match) return null;
  const clusterId = decodeURIComponent(match[1]);
  const rest = trimTrailingSlashes(match[2] ?? '');
  const item = features
    .flatMap((f) => f.nav ?? [])
    .filter((n) => rest === n.path || rest.startsWith(`${n.path}/`))
    .sort((a, b) => b.path.length - a.path.length)[0];
  const groupLabel = item ? NAV_GROUPS.find((g) => g.id === item.group)?.label : undefined;
  return { clusterId, item, groupLabel };
}

export function useCurrentView(): CurrentView | null {
  const { pathname } = useLocation();
  const features = useFeatures();
  return useMemo(() => matchView(pathname, features), [pathname, features]);
}

/**
 * The address of the same view on another cluster (ADR-0109): an operator comparing two clusters
 * stays on Subscriptions. The view's search is not carried — its filter and its open resource name things
 * that may not exist there. Without a current view, or when the view is for one system and the cluster runs
 * another (`system`), the cluster's landing page.
 */
export function sameViewOn(clusterId: string, view: CurrentView | null, system?: string): string {
  const item = view?.item;
  if (!item || (item.system && item.system !== system)) return `/clusters/${clusterId}`;
  return `/clusters/${clusterId}/${item.path}`;
}
