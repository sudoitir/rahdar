import { IconClipboard, IconPlugConnectedX, IconPlugConnected, IconUsers, IconUsersMinus } from '@tabler/icons-react';
import { Link, useNavigate } from '@tanstack/react-router';

import type {
  ActionProps,
  AddressTarget,
  ConnectionTarget,
  ConsumerTarget,
  LinkProps,
  ProducerTarget,
  SessionTarget,
} from '../../kernel/actions/types.ts';
import { clusterHref } from '../../kernel/routing/href.ts';
import { ActionMenuItem } from '../../ui/ActionMenuItem.tsx';
import linkClasses from '../../ui/InlineLink.module.css';
import { CloseAddressConsumers, CloseDialog } from './CloseConnection.tsx';
import { useCloseAddressGate, useCloseGate } from './closeGates.ts';

/*
 * Row actions and links for the live resource views (ADR-0107). A view links to a live resource
 * by filtering its listing on the identifier; the listings match the identifiers other views link
 * with (cross-node-resource-views), so the filtered listing shows exactly that resource.
 */

type View = 'addresses' | 'connections' | 'sessions' | 'consumers' | 'producers';

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
export function ConnectionOpenSessions({ clusterId, target }: Readonly<ActionProps<ConnectionTarget>>) {
  return (
    <OpenIn
      clusterId={clusterId}
      view="sessions"
      q={target.connectionId}
      label="Open its sessions"
      icon={<IconPlugConnected size={16} aria-hidden />}
    />
  );
}

export function ConnectionCopy({ target, host }: Readonly<ActionProps<ConnectionTarget>>) {
  return (
    <>
      <Copy value={target.snapshot?.remoteAddress} what="remote address" host={host} />
      <Copy value={target.snapshot?.clientId} what="client id" host={host} />
      <Copy value={target.connectionId} what="connection id" host={host} />
    </>
  );
}

export function ConnectionClose({ clusterId, target, host }: Readonly<ActionProps<ConnectionTarget>>) {
  const gate = useCloseGate(clusterId, 'connection', target.connectionId);
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

// ── Sessions ─────────────────────────────────────────────────────────────────
export function SessionOpenRelated({ clusterId, target }: Readonly<ActionProps<SessionTarget>>) {
  return (
    <>
      <OpenIn
        clusterId={clusterId}
        view="connections"
        q={target.connectionId}
        label="Open its connection"
        icon={<IconPlugConnected size={16} aria-hidden />}
      />
      <OpenIn
        clusterId={clusterId}
        view="consumers"
        q={target.sessionId}
        label="Open its consumers"
        icon={<IconUsers size={16} aria-hidden />}
      />
      <OpenIn
        clusterId={clusterId}
        view="producers"
        q={target.sessionId}
        label="Open its producers"
        icon={<IconUsers size={16} aria-hidden />}
      />
    </>
  );
}

export function SessionCopy({ target, host }: Readonly<ActionProps<SessionTarget>>) {
  return <Copy value={target.sessionId} what="session id" host={host} />;
}

export function SessionClose({ clusterId, target, host }: Readonly<ActionProps<SessionTarget>>) {
  const gate = useCloseGate(clusterId, 'session', target.sessionId);
  return (
    <ActionMenuItem
      label="Close session…"
      icon={<IconPlugConnectedX size={16} aria-hidden />}
      tone="danger"
      verdict={gate}
      onExplain={(verdict) => host.explain(verdict, 'closing this session')}
      onSelect={() =>
        host.open(CloseDialog, {
          clusterId,
          kind: 'session',
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
/** Open the session a consumer or producer belongs to, in the sessions view filtered to it. */
function OpenItsSession({ clusterId, target }: Readonly<{ clusterId: string; target: { sessionId?: string | null } }>) {
  return (
    <OpenIn
      clusterId={clusterId}
      view="sessions"
      q={target.sessionId}
      label="Open its session"
      icon={<IconPlugConnected size={16} aria-hidden />}
    />
  );
}

export const ConsumerOpenSession = OpenItsSession;

export function ConsumerCopy({ target, host }: Readonly<ActionProps<ConsumerTarget>>) {
  return <Copy value={target.consumerId} what="consumer id" host={host} />;
}

export function ConsumerClose({ clusterId, target, host }: Readonly<ActionProps<ConsumerTarget>>) {
  const gate = useCloseGate(clusterId, 'consumer', target.consumerId);
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
export const ProducerOpenSession = OpenItsSession;

export function ProducerCopy({ target, host }: Readonly<ActionProps<ProducerTarget>>) {
  return (
    <>
      <Copy value={target.address} what="address" host={host} />
      <Copy value={target.producerId} what="producer id" host={host} />
    </>
  );
}

// ── Addresses ────────────────────────────────────────────────────────────────
export function AddressOpenConsumers({ clusterId, target }: Readonly<ActionProps<AddressTarget>>) {
  // Consumers are listed by queue; on an address whose queues share its name — the common case —
  // this is its consumers, and otherwise the listing states what it matched.
  return (
    <OpenIn
      clusterId={clusterId}
      view="consumers"
      q={target.address}
      label="Open its consumers"
      icon={<IconUsers size={16} aria-hidden />}
    />
  );
}

export function AddressCopy({ target, host }: Readonly<ActionProps<AddressTarget>>) {
  return <Copy value={target.address} what="address name" host={host} />;
}

export function AddressCloseConsumers({ clusterId, target, host }: Readonly<ActionProps<AddressTarget>>) {
  const gate = useCloseAddressGate(clusterId);
  return (
    <ActionMenuItem
      label="Close every consumer…"
      icon={<IconUsersMinus size={16} aria-hidden />}
      tone="danger"
      verdict={gate}
      onExplain={(verdict) => host.explain(verdict, "closing this address's consumers")}
      onSelect={() => host.open(CloseAddressConsumers, { clusterId, address: target.address })}
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
  return target.connectionId ? (
    <FilteredLink clusterId={clusterId} view="connections" q={target.connectionId}>
      {children}
    </FilteredLink>
  ) : (
    <>{children}</>
  );
}

export function SessionLink({ clusterId, target, children }: LinkProps<SessionTarget>) {
  return target.sessionId ? (
    <FilteredLink clusterId={clusterId} view="sessions" q={target.sessionId}>
      {children}
    </FilteredLink>
  ) : (
    <>{children}</>
  );
}

export function AddressLink({ clusterId, target, children }: Readonly<LinkProps<AddressTarget>>) {
  return (
    <FilteredLink clusterId={clusterId} view="addresses" q={target.address}>
      {children}
    </FilteredLink>
  );
}
