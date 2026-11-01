import type { NavContribution, RahdarFeature } from './feature.ts';
import { NAV_GROUPS, type NavGroupId } from './nav/groups.ts';

export interface NavGroup {
  id: NavGroupId;
  label: string;
  items: NavContribution[];
}

/**
 * Whether a cluster is offered a view: a system-only view only on a cluster that runs that system
 * (`systemId`), and a view of a resource class only where the system has a kind of that class (`classes`).
 */
export function offeredOn(item: NavContribution, systemId?: string, classes?: readonly string[]): boolean {
  if (item.system && item.system !== systemId) return false;
  return !item.resourceClass || (classes?.includes(item.resourceClass) ?? false);
}

/**
 * The features' views under the kernel's groups (ADR-0070): groups in their fixed order, views in
 * their declared order, and a group none of the features contributes to left out. A view is listed only for a
 * cluster it is offered on ({@link offeredOn}).
 */
export function navGroups(features: RahdarFeature[], systemId?: string, classes?: readonly string[]): NavGroup[] {
  const items = features.flatMap((feature) => feature.nav ?? []).filter((item) => offeredOn(item, systemId, classes));
  return NAV_GROUPS.map((group) => ({
    id: group.id,
    label: group.label,
    items: items.filter((item) => item.group === group.id).sort((a, b) => a.order - b.order),
  })).filter((group) => group.items.length > 0);
}
