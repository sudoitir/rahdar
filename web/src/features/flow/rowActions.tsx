import { IconChartSankey, IconPlugConnected } from '@tabler/icons-react';
import { useNavigate } from '@tanstack/react-router';

import type {
  ActionProps,
  DestinationTarget,
  ClientTarget,
  ConnectionTarget,
  RouteTarget,
  SubscriptionTarget,
} from '../../kernel/actions/types.ts';
import { ResourceActions } from '../../kernel/actions/ResourceActions.tsx';
import { nameOf, refOf } from '../../kernel/api/resourceId.ts';
import { clusterHref } from '../../kernel/routing/href.ts';
import { ActionMenuItem } from '../../ui/ActionMenuItem.tsx';
import { useRoleIds } from '../clusters/index.ts';
import type { FlowNodeView } from './api.ts';

/** "Show in Flow": the flow view focused on one resource, its upstream and downstream. */
function ShowInFlow({
  clusterId,
  focus,
  reason,
}: Readonly<{ clusterId: string; focus: string | null; reason?: string }>) {
  const navigate = useNavigate();
  return (
    <ActionMenuItem
      label="Show in Flow"
      icon={<IconChartSankey size="1rem" aria-hidden />}
      verdict={focus ? undefined : { kind: 'blocked', reason: reason ?? 'There is nothing to focus the flow on.' }}
      href={focus ? clusterHref(clusterId, 'flow', { focus }) : undefined}
      onSelect={() => focus && navigate({ to: `/clusters/${clusterId}/flow`, search: { focus } as never })}
    />
  );
}

export function SubscriptionInFlow({ clusterId, target }: Readonly<ActionProps<SubscriptionTarget>>) {
  return <ShowInFlow clusterId={clusterId} focus={`subscription:${target.name}`} />;
}

export function DestinationInFlow({ clusterId, target }: Readonly<ActionProps<DestinationTarget>>) {
  return <ShowInFlow clusterId={clusterId} focus={`destination:${target.name}`} />;
}

export function ConnectionInFlow({ clusterId, target }: Readonly<ActionProps<ConnectionTarget>>) {
  // Flow names a client by its client id by default; a connection without one is found by host.
  const clientId = target.snapshot?.clientId;
  return (
    <ShowInFlow
      clusterId={clusterId}
      focus={clientId ? `client:${clientId}` : null}
      reason="This connection has no client id, which is what Flow groups clients by. Group Flow by host to find it."
    />
  );
}

export function RouteInFlow({ clusterId, target }: Readonly<ActionProps<RouteTarget>>) {
  // A link reads from a subscription; every other route reads from a destination.
  const source = target.snapshot?.source;
  const role = target.snapshot?.routeClass === 'LINK' ? 'subscription' : 'destination';
  return <ShowInFlow clusterId={clusterId} focus={source ? `${role}:${nameOf(source)}` : null} />;
}

/** A flow client: focus the view on it. */
export function FocusClient({ clusterId, target }: Readonly<ActionProps<ClientTarget>>) {
  const navigate = useNavigate();
  const focus = `client:${target.label}`;
  return (
    <ActionMenuItem
      label="Focus the view on this"
      icon={<IconChartSankey size="1rem" aria-hidden />}
      href={clusterHref(clusterId, 'flow', { focus })}
      onSelect={() => navigate({ to: `/clusters/${clusterId}/flow`, search: { focus } as never })}
    />
  );
}

/** A flow client's connections, found by what Flow names it by: its client id, user or host. */
export function ClientConnections({ clusterId, target }: Readonly<ActionProps<ClientTarget>>) {
  const navigate = useNavigate();
  return (
    <ActionMenuItem
      label="Open its connections"
      icon={<IconPlugConnected size="1rem" aria-hidden />}
      href={clusterHref(clusterId, 'connections', { q: target.label })}
      onSelect={() => navigate({ to: `/clusters/${clusterId}/connections`, search: { q: target.label } as never })}
    />
  );
}

/**
 * A flow node's menu: its resource's Open and Copy items only (flow-visualization spec: menus in
 * the flow view navigate and never act).
 */
export function FlowNodeActions({
  clusterId,
  node,
  restoreFocus,
}: Readonly<{
  clusterId: string;
  node: FlowNodeView;
  restoreFocus?: () => void;
}>) {
  const ids = useRoleIds(clusterId);
  const label = node.label ?? '';
  if (!ids.loaded && (node.kind === 'SUBSCRIPTION' || node.kind === 'DESTINATION')) return null;
  switch (node.kind) {
    case 'SUBSCRIPTION':
      return (
        <ResourceActions
          kind="subscription"
          clusterId={clusterId}
          target={refOf(ids.subscription(label))}
          mode="navigate"
          restoreFocus={restoreFocus}
        />
      );
    case 'DESTINATION':
      return (
        <ResourceActions
          kind="destination"
          clusterId={clusterId}
          target={refOf(ids.destination(label))}
          mode="navigate"
          restoreFocus={restoreFocus}
        />
      );
    default:
      return (
        <ResourceActions
          kind="client"
          clusterId={clusterId}
          target={{ label }}
          mode="navigate"
          restoreFocus={restoreFocus}
        />
      );
  }
}
