import { IconClipboard, IconPlugConnectedX, IconPlugConnected, IconUsers, IconUsersMinus } from '@tabler/icons-react';
import { Link, useNavigate } from '@tanstack/react-router';

import type {
  ActionProps,
  ChannelTarget,
  ConnectionTarget,
  ConsumerTarget,
  DestinationTarget,
  LinkProps,
  ProducerTarget,
} from '../../kernel/actions/types.ts';
import { nameOf } from '../../kernel/api/resourceId.ts';
import { clusterHref } from '../../kernel/routing/href.ts';
import { ActionMenuItem } from '../../ui/ActionMenuItem.tsx';
import linkClasses from '../../ui/InlineLink.module.css';
import { CloseDestinationConsumers, CloseDialog } from './CloseConnection.tsx';
import { useCloseDestinationGate, useCloseGate } from './closeGates.ts';

/*
 * Row actions and links for the live resource views (ADR-0107). A view links to a live resource
 * by filtering its listing on the identifier; the listings match the identifiers other views link
 * with (cross-node-resource-views), so the filtered listing shows exactly that resource.
 */

type View = 'destinations' | 'connections' | 'channels' | 'consumers' | 'producers';

/** An "Open …" item: a link to a live listing filtered to one identifier. */
function OpenIn({
  clusterId,
  view,
  q,
  label,
  icon,
}: Readonly<{
  clusterId: string;
  view: View;
  q: string | null | undefined;
  label: string;
  icon: React.ReactNode;
}>) {
  const navigate = useNavigate();
  if (!q) {
    return (
      <ActionMenuItem
        label={label}
        icon={icon}
        verdict={{ kind: 'blocked', reason: 'The broker reported no identifier to look it up by.' }}
        onSelect={() => {}}
      />
    );
  }
  return (
    <ActionMenuItem
      label={label}
      icon={icon}
      href={clusterHref(clusterId, view, { q })}
      onSelect={() => navigate({ to: `/clusters/${clusterId}/${view}`, search: { q } })}
    />
  );
}

function Copy({
  value,
  what,
  host,
}: Readonly<{
  value: string | null | undefined;
  what: string;
  host: ActionProps<unknown>['host'];
}>) {
  return (
    <ActionMenuItem
      label={`Copy ${what}`}
      icon={<IconClipboard size={16} aria-hidden />}
      verdict={value ? undefined : { kind: 'blocked', reason: `The broker reported no ${what}.` }}
      onSelect={() => value && host.copy(value, what)}
    />
  );
}

// ── Connections ──────────────────────────────────────────────────────────────
export function ConnectionOpenChannels({ clusterId, target }: Readonly<ActionProps<ConnectionTarget>>) {
  return (
    <OpenIn
      clusterId={clusterId}
      view="channels"
      q={target.name}
      label="Open its channels"
      icon={<IconPlugConnected size={16} aria-hidden />}
    />
  );
}

export function ConnectionCopy({ target, host }: Readonly<ActionProps<ConnectionTarget>>) {
  return (
    <>
      <Copy value={target.snapshot?.remoteAddress} what="remote address" host={host} />
      <Copy value={target.snapshot?.clientId} what="client id" host={host} />
      <Copy value={target.name} what="connection id" host={host} />
    </>
  );
}

export function ConnectionClose({ clusterId, target, host }: Readonly<ActionProps<ConnectionTarget>>) {
  const gate = useCloseGate(clusterId, 'connection', target.id);
  return (
    <ActionMenuItem
      label="Close connection…"
      icon={<IconPlugConnectedX size={16} aria-hidden />}
      tone="danger"
      verdict={gate}
      onExplain={(verdict) => host.explain(verdict, 'closing this connection')}
      onSelect={() =>
        host.open(CloseDialog, {
          clusterId,
          kind: 'connection',
          nodeId: target.nodeId,
          nodeName: target.nodeName,
          targetId: target.id,
          fetchedAt: null,
        })
      }
    />
  );
}

// ── Channels ─────────────────────────────────────────────────────────────────
export function ChannelOpenRelated({ clusterId, target }: Readonly<ActionProps<ChannelTarget>>) {
  return (
    <>
      <OpenIn
        clusterId={clusterId}
        view="connections"
        q={target.connection}
        label="Open its connection"
        icon={<IconPlugConnected size={16} aria-hidden />}
      />
      <OpenIn
        clusterId={clusterId}
        view="consumers"
        q={target.name}
        label="Open its consumers"
        icon={<IconUsers size={16} aria-hidden />}
      />
      <OpenIn
        clusterId={clusterId}
        view="producers"
        q={target.name}
        label="Open its producers"
        icon={<IconUsers size={16} aria-hidden />}
      />
    </>
  );
}

export function ChannelCopy({ target, host }: Readonly<ActionProps<ChannelTarget>>) {
  return <Copy value={target.name} what="channel id" host={host} />;
}

export function ChannelClose({ clusterId, target, host }: Readonly<ActionProps<ChannelTarget>>) {
  const gate = useCloseGate(clusterId, 'channel', target.id);
  return (
    <ActionMenuItem
      label="Close channel…"
      icon={<IconPlugConnectedX size={16} aria-hidden />}
      tone="danger"
      verdict={gate}
      onExplain={(verdict) => host.explain(verdict, 'closing this channel')}
      onSelect={() =>
        host.open(CloseDialog, {
          clusterId,
          kind: 'channel',
          nodeId: target.nodeId,
          nodeName: target.nodeName,
          targetId: target.id,
          fetchedAt: null,
        })
      }
    />
  );
}

// ── Consumers ────────────────────────────────────────────────────────────────
/** Open the channel a consumer or producer belongs to, in the channels view filtered to it. */
function OpenItsChannel({ clusterId, target }: Readonly<{ clusterId: string; target: { channel?: string | null } }>) {
  return (
    <OpenIn
      clusterId={clusterId}
      view="channels"
      q={target.channel}
      label="Open its channel"
      icon={<IconPlugConnected size={16} aria-hidden />}
    />
  );
}

export const ConsumerOpenChannel = OpenItsChannel;

export function ConsumerCopy({ target, host }: Readonly<ActionProps<ConsumerTarget>>) {
  return <Copy value={target.name} what="consumer id" host={host} />;
}

export function ConsumerClose({ clusterId, target, host }: Readonly<ActionProps<ConsumerTarget>>) {
  const gate = useCloseGate(clusterId, 'consumer', target.id);
  return (
    <ActionMenuItem
      label="Close its connection…"
      icon={<IconPlugConnectedX size={16} aria-hidden />}
      tone="danger"
      verdict={gate}
      onExplain={(verdict) => host.explain(verdict, "closing this consumer's connection")}
      onSelect={() =>
        host.open(CloseDialog, {
          clusterId,
          kind: 'consumer',
          nodeId: target.nodeId,
          nodeName: target.nodeName,
          targetId: target.id,
          fetchedAt: null,
        })
      }
    />
  );
}

// ── Producers ────────────────────────────────────────────────────────────────
export const ProducerOpenChannel = OpenItsChannel;

export function ProducerCopy({ target, host }: Readonly<ActionProps<ProducerTarget>>) {
  return (
    <>
      <Copy value={nameOf(target.destination)} what="destination" host={host} />
      <Copy value={target.name} what="producer id" host={host} />
    </>
  );
}

// ── Destinations ────────────────────────────────────────────────────────────────
export function DestinationOpenConsumers({ clusterId, target }: Readonly<ActionProps<DestinationTarget>>) {
  // Consumers are listed by subscription; on an destination whose subscriptions share its name — the common case —
  // this is its consumers, and otherwise the listing states what it matched.
  return (
    <OpenIn
      clusterId={clusterId}
      view="consumers"
      q={target.name}
      label="Open its consumers"
      icon={<IconUsers size={16} aria-hidden />}
    />
  );
}

export function DestinationCopy({ target, host }: Readonly<ActionProps<DestinationTarget>>) {
  return <Copy value={target.name} what="destination name" host={host} />;
}

export function DestinationCloseConsumers({ clusterId, target, host }: Readonly<ActionProps<DestinationTarget>>) {
  const gate = useCloseDestinationGate(clusterId, target.kind);
  return (
    <ActionMenuItem
      label="Close every consumer…"
      icon={<IconUsersMinus size={16} aria-hidden />}
      tone="danger"
      verdict={gate}
      onExplain={(verdict) => host.explain(verdict, "closing this destination's consumers")}
      onSelect={() => host.open(CloseDestinationConsumers, { clusterId, destination: target.name })}
    />
  );
}

// ── Links ────────────────────────────────────────────────────────────────────
function FilteredLink({
  clusterId,
  view,
  q,
  children,
}: Readonly<{
  clusterId: string;
  view: View;
  q: string;
  children: React.ReactNode;
}>) {
  return (
    <Link to={`/clusters/${clusterId}/${view}`} search={{ q }} className={linkClasses.link}>
      {children}
    </Link>
  );
}

export function ConnectionLink({ clusterId, target, children }: LinkProps<ConnectionTarget>) {
  return target.name ? (
    <FilteredLink clusterId={clusterId} view="connections" q={target.name}>
      {children}
    </FilteredLink>
  ) : (
    <>{children}</>
  );
}

export function ChannelLink({ clusterId, target, children }: LinkProps<ChannelTarget>) {
  return target.name ? (
    <FilteredLink clusterId={clusterId} view="channels" q={target.name}>
      {children}
    </FilteredLink>
  ) : (
    <>{children}</>
  );
}

export function DestinationLink({ clusterId, target, children }: Readonly<LinkProps<DestinationTarget>>) {
  return (
    <FilteredLink clusterId={clusterId} view="destinations" q={target.name}>
      {children}
    </FilteredLink>
  );
}
