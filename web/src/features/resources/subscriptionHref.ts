import { clusterHref } from '../../kernel/routing/href.ts';

/** Where a subscription's detail opens: the Subscriptions view with its drawer, whatever page it is on. */
export function subscriptionHref(clusterId: string, subscriptionId: string): string {
  return clusterHref(clusterId, 'subscriptions', { subscription: subscriptionId });
}
