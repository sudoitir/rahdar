/**
 * `@rahdar/plugin-sdk` — everything a plugin's UI may use from Rahdar (ADR-0100). The host
 * shares this module as a singleton, so a plugin's bundle uses the running Rahdar's own copy:
 * the same router roots, the same query cache, the same permission checks. Nothing outside this
 * file is part of the contract; a plugin that reaches past it breaks on the next Rahdar release.
 */
import type { ComponentProps, ComponentType, ReactElement } from 'react';

import type { PluginId, RahdarFeature } from '../kernel/feature.ts';
import { featureView as kernelFeatureView } from '../kernel/routing/roots.ts';
import { DescriptionList } from '../ui/DescriptionList.tsx';
import { ErrorState } from '../ui/ErrorState.tsx';
import { FieldRow } from '../ui/FieldRow.tsx';
import { LoadingState } from '../ui/LoadingState.tsx';
import { Notice } from '../ui/Notice.tsx';
import { Page } from '../ui/Page.tsx';
import { PageHeader } from '../ui/PageHeader.tsx';
import { Section } from '../ui/Section.tsx';
import { Stat } from '../ui/Stat.tsx';
import { StatusBadge } from '../ui/StatusBadge.tsx';
import { Toolbar } from '../ui/Toolbar.tsx';

export { CONTRACT } from '../kernel/feature.ts';
export type {
  NavContribution,
  PaletteSource,
  PluginId,
  RouteContributions,
  RahdarFeature,
  TopicHandler,
} from '../kernel/feature.ts';
export {
  ADMIN_GROUPS,
  SETTINGS_GROUPS,
  type AdminGroupId,
  type MessageSelection,
  type SubscriptionSelection,
  type SettingsGroupId,
  type SlotContribution,
  type SlotContributions,
  type SlotEntry,
  type SlotName,
  type SlotProps,
} from '../kernel/slots.ts';
export { NAV_GROUPS, type NavGroupId } from '../kernel/nav/groups.ts';
export { clusterRoute, rootRoute } from '../kernel/routing/roots.ts';
export {
  ApiError,
  clusterKey,
  heldOperationsKey,
  OperationHeldError,
  request,
  type HeldOperation,
} from '../kernel/api/request.ts';
export type {
  HeldDisplayRow,
  HeldEvent,
  HeldOperationDetail,
  HeldOperationSummary,
  HeldState,
} from '../kernel/approvals/api.ts';
export type { InboxItem, InboxSeverity } from '../kernel/inbox/api.ts';
export { useCan, type AllowedActions, type ResourceWhere } from '../kernel/auth/useCan.ts';
/**
 * The step-up an action shows when the server refused it for want of a fresh sign-in, such as an approver's
 * vote: render it under the control with the mutation's error. It renders nothing for any other error.
 */
export { StepUpPrompt } from '../kernel/auth/StepUp.tsx';
/**
 * The signed-in user's own stream, which the shell already holds open so their inbox and held operations stay
 * fresh on every page. A view shares that connection and reads its status, to poll while it is not `live`.
 */
export { useUserStream } from '../kernel/stream/useUserStream.ts';
export type { StreamStatus } from '../kernel/stream/useClusterStream.ts';
export {
  ACTION_SECTIONS,
  type ActionHost,
  type ActionMode,
  type ActionProps,
  type ActionSection,
  type ActionTargets,
  type ChannelTarget,
  type ClientTarget,
  type ConnectionTarget,
  type ConsumerTarget,
  type DestinationTarget,
  type HostedDialogProps,
  type MessageTarget,
  type ProducerTarget,
  type ResourceRef,
  type RouteTarget,
  type SubscriptionTarget,
} from '../kernel/actions/types.ts';
export { ActionMenuItem } from '../ui/ActionMenuItem.tsx';
export { CapabilityGate } from '../ui/CapabilityGate.tsx';
export { gateFor, type GateScope, type GateVerdict } from '../ui/capabilityGate.ts';
export { useMe } from '../kernel/auth/api.ts';
export { CodeEditor, type CodeDiagnostic, type CodeEditorProps } from '../ui/CodeEditor.tsx';
export { HoldToConfirm } from '../ui/HoldToConfirm.tsx';
export { ConfirmDialog, type ConfirmDialogProps } from '../ui/ConfirmDialog.tsx';
export {
  DiagramView,
  type DiagramAction,
  type DiagramChoice,
  type DiagramEdge,
  type DiagramNode,
  type DiagramViewProps,
} from '../ui/DiagramView.tsx';
export { NodeOutcomeSummary, OutcomeSummary, type OutcomeRow } from '../ui/NodeOutcomeSummary.tsx';
export { Pager } from '../ui/Pager.tsx';
export {
  DataTable,
  type Column,
  type ColumnKind,
  type ColumnPriority,
  type DataTableProps,
  type RowMenu,
} from '../ui/table/index.ts';
export { type DescriptionItem } from '../ui/DescriptionList.tsx';
export { EmptyState, type EmptyStateProps } from '../ui/EmptyState.tsx';
export { MetricChart } from '../kernel/metrics/MetricChart.tsx';
export { usePluginSeries } from '../kernel/metrics/pluginSeries.ts';
export { METRIC_RANGES, type MetricRange } from '../kernel/time/ranges.ts';
/**
 * Shows a toast in Rahdar's own notification area, announced through `aria-live`. Import this, never
 * `@mantine/notifications` directly: that is not shared, so a plugin's own copy would show nothing.
 */
export { notify, type ActionVerb } from '../ui/notify.ts';

export {
  DescriptionList,
  ErrorState,
  FieldRow,
  LoadingState,
  Notice,
  Page,
  PageHeader,
  Section,
  Stat,
  StatusBadge,
  Toolbar,
};

export type PageProps = ComponentProps<typeof Page>;
export type PageHeaderProps = ComponentProps<typeof PageHeader>;
export type SectionProps = ComponentProps<typeof Section>;
export type ToolbarProps = ComponentProps<typeof Toolbar>;
export type ErrorStateProps = ComponentProps<typeof ErrorState>;
export type FieldRowProps = ComponentProps<typeof FieldRow>;
export type LoadingStateProps = ComponentProps<typeof LoadingState>;
export type NoticeProps = ComponentProps<typeof Notice>;
export type StatusBadgeProps = ComponentProps<typeof StatusBadge>;
export type StatProps = ComponentProps<typeof Stat>;
export type DescriptionListProps = ComponentProps<typeof DescriptionList>;

/** A plugin's description of itself: a {@link RahdarFeature} whose id is the plugin's own. */
export interface RahdarPlugin extends RahdarFeature {
  id: PluginId;
}

/** Declares a plugin's UI; its bundle exposes the result as `./feature`'s default export. */
export function definePlugin<T extends RahdarPlugin>(plugin: T): T {
  return plugin;
}

/**
 * A route path under the plugin's own namespace, the only place its routes may live:
 * `pluginPath('acme-notes', 'notes/$noteId')` is `p/acme-notes/notes/$noteId`. Use it with
 * `rootRoute` for a page outside any cluster, or `clusterRoute` for a view of one cluster.
 */
export function pluginPath(id: PluginId, path = ''): string {
  const rest = path.replace(/^\/+/, '');
  return rest ? `p/${id}/${rest}` : `p/${id}`;
}

/**
 * The API path of a plugin's own endpoints, for {@link request}: `/p/<id>/…`, or
 * `/clusters/<clusterId>/p/<id>/…` for a cluster-scoped call.
 */
export function pluginApi(id: PluginId, path: string, clusterId?: string): string {
  const rest = path.replace(/^\/+/, '');
  return clusterId ? `/clusters/${clusterId}/p/${id}/${rest}` : `/p/${id}/${rest}`;
}

/** The plugin's page, or the page explaining it is unavailable when the plugin is not running. */
export function pluginView(id: PluginId, View: ComponentType): () => ReactElement {
  return kernelFeatureView(id, View);
}
