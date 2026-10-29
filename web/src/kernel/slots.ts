import type { ComponentType } from 'react';

import type {
  ActionProps,
  ActionSection,
  ChannelTarget,
  ClientTarget,
  ConnectionTarget,
  ConsumerTarget,
  DestinationTarget,
  LinkProps,
  MessageTarget,
  ProducerTarget,
  ResourceRef,
  RouteTarget,
  SubscriptionTarget,
} from './actions/types.ts';
import type { HeldOperationDetail } from './approvals/api.ts';
import { useFeatures } from './features.ts';
import type { MetricRange } from './time/ranges.ts';

/** Subscriptions picked by id (`kind/path`), or every subscription matching the subscriptions screen's filter (`q`, blank for all). */
export type SubscriptionSelection = { kind: 'ids'; ids: string[] } | { kind: 'filter'; q: string; total: number };

/** Messages picked by id, every message matching the messages screen's selector, or the whole subscription. */
export type MessageSelection = { kind: 'ids'; ids: number[] } | { kind: 'filter'; filter: string } | { kind: 'all' };

/**
 * The kernel-owned slots and what each hands its contributions (ADR-0070). A slot lets a screen
 * show another feature's panel without importing that feature, and without a placeholder when the
 * feature is disabled.
 */
export interface SlotProps {
  /** In the application header, after the product name: status across clusters that needs attention. */
  'shell.header': object;
  /** In the sidebar, above the open cluster's views: how the operator moves between clusters. */
  'shell.navbar': { collapsed: boolean };
  /** The landing page, when no cluster is open. */
  'home.estate': object;
  /** Above every view of a cluster: what identifies it, and what needs saying about its state. */
  'cluster.header': { clusterId: string };
  /** Below a passing registration check: what the enabled features added to it, by feature id. */
  'cluster.registration.afterProbe': { contributions: Record<string, unknown> };
  /** In a subscription's detail drawer, below its per-node breakdown. `onClose` closes the drawer before navigating. */
  'subscription.detail.panels': { clusterId: string; subscription: ResourceRef; onClose: () => void };
  /**
   * Beside the subscriptions screen's selection: what can be done to the selected subscriptions. `count` is how
   * many are selected, and may be zero: an action then stays visible and disabled. `clear` empties
   * the selection.
   */
  'subscriptions.selection': {
    clusterId: string;
    selection: SubscriptionSelection;
    count: number;
    clear: () => void;
  };
  /**
   * Beside the messages screen's selection: what can be done to the selected messages elsewhere.
   * `node` is the Rahdar node being browsed, absent for the live node; `total` is how many messages
   * the selection holds, null when the screen does not know. `clear` empties the selection.
   */
  'messages.selection': {
    clusterId: string;
    subscription: ResourceRef;
    node?: string;
    selection: MessageSelection;
    total: number | null;
    clear: () => void;
  };
  /** At the foot of a cluster's metrics view. */
  'metrics.panels': { clusterId: string };
  /** In the flow view's monitoring pane, when a subscription is selected: its history over `range`, per node. */
  'flow.selection.panels': { clusterId: string; subscription: ResourceRef; range: MetricRange };
  /** Inside a box on the topology graph, after its name. `nodeIds` are the broker endpoints the box stands for. */
  'topology.node.marks': { clusterId: string; nodeIds: string[] };
  /** A section of a cluster's Settings page, under the contribution's title. */
  'settings.sections': { clusterId: string };
  /** A tab of a cluster's Routes page, after Routes, labelled with the contribution's title; its id is the tab's `?tab=`. */
  'routes.tabs': { clusterId: string };
  /**
   * A tab of the Administration page, labelled with the contribution's title and listed under its
   * `group`; its id is the tab's `?tab=`.
   */
  'admin.tabs': object;
  /** A section of the signed-in user's Account page, under the contribution's title. */
  'account.sections': object;
  /**
   * On an approval request's page, above Rahdar's own Approve and Reject: what an installed approval provider
   * shows an approver, such as its policy's checks or its own decision controls. `heldOperation` is the request
   * as the page shows it; `refresh` fetches it again after the contribution changed it.
   */
  'approval.decision': { heldOperation: HeldOperationDetail; refresh: () => void };
  /**
   * A dialog opened from the user menu. The menu lists the contribution's title after Account and hosts
   * the dialog outside the menu, so it outlives the menu closing.
   */
  'shell.userMenu': { opened: boolean; onClose: () => void };

  /*
   * Row actions (ADR-0107): the items of a resource's row menu, wherever a grid lists it. Each
   * contribution names its `section`, renders `ActionMenuItem`s, and opens its dialogs through
   * `host`, never in the row. In `navigate` mode it offers nothing that changes the broker.
   */
  'subscription.actions': ActionProps<SubscriptionTarget>;
  'destination.actions': ActionProps<DestinationTarget>;
  'connection.actions': ActionProps<ConnectionTarget>;
  'channel.actions': ActionProps<ChannelTarget>;
  'consumer.actions': ActionProps<ConsumerTarget>;
  'producer.actions': ActionProps<ProducerTarget>;
  'message.actions': ActionProps<MessageTarget>;
  'route.actions': ActionProps<RouteTarget>;
  'client.actions': ActionProps<ClientTarget>;

  /*
   * Links (ADR-0107): the owning feature's link to one resource, wrapping its name. Built-in only —
   * a plugin may not decide where Rahdar's own resource links lead.
   */
  'subscription.link': LinkProps<SubscriptionTarget>;
  'destination.link': LinkProps<DestinationTarget>;
  'connection.link': LinkProps<ConnectionTarget>;
  'channel.link': LinkProps<ChannelTarget>;
}

export type SlotName = keyof SlotProps;

/**
 * The closed, ordered headings a Settings page groups its tabs under, in the order an operator's
 * reach widens: their own preferences, then what is shared across Rahdar, then this cluster, then
 * what plugins added. Adding one is a kernel change, like a navigation group (ADR-0070).
 */
export const SETTINGS_GROUPS = [
  { id: 'personal', label: 'Yours' },
  { id: 'rahdar', label: 'Rahdar' },
  { id: 'cluster', label: 'This cluster' },
  { id: 'plugins', label: 'Plugins' },
] as const;

export type SettingsGroupId = (typeof SETTINGS_GROUPS)[number]['id'];

/**
 * The closed, ordered headings the Administration page groups its tabs under: who may do what, what
 * is installed and where, what rules data and changes follow, then what helps when something is
 * wrong. Every `admin.tabs` contribution names one. Adding one is a kernel change, like a navigation
 * group (ADR-0070).
 */
export const ADMIN_GROUPS = [
  { id: 'access', label: 'Access' },
  { id: 'installation', label: 'Installation' },
  { id: 'governance', label: 'Governance' },
  { id: 'support', label: 'Support' },
] as const;

export type AdminGroupId = (typeof ADMIN_GROUPS)[number]['id'];

export interface SlotContribution<P> {
  /** Unique within the slot. */
  id: string;
  /** Position within the slot; lower comes first. */
  order: number;
  /** The heading or tab label, in the slots that show one. */
  title?: string;
  /**
   * The heading its tab sits under, in the grouped slots: a `SettingsGroupId` for `settings.sections`
   * (listed under Plugins without one), and an `AdminGroupId`, required, for `admin.tabs`.
   */
  group?: string;
  /**
   * `routes.tabs` only: the id of the broker system the tab is for (`artemis`). The tab is listed only on a
   * cluster that runs that system.
   */
  system?: string;
  /** `*.actions` only: the menu section the item is listed under. */
  section?: ActionSection;
  Component: ComponentType<P>;
}

/** What a contribution to a grouped slot names as its heading. */
type SlotGroup<K extends SlotName> = K extends 'admin.tabs'
  ? { group: AdminGroupId }
  : K extends 'settings.sections'
    ? { group?: SettingsGroupId }
    : object;

/** One contribution to the named slot, with the heading that slot groups it under. */
export type SlotEntry<K extends SlotName> = SlotContribution<SlotProps[K]> & SlotGroup<K>;

export type SlotContributions = { [K in SlotName]?: SlotEntry<K>[] };

/** The enabled features' contributions to one slot, in order. */
export function useSlot<K extends SlotName>(name: K): SlotEntry<K>[] {
  return useFeatures()
    .flatMap((feature): SlotEntry<K>[] => feature.slots?.[name] ?? [])
    .sort((a, b) => a.order - b.order);
}
