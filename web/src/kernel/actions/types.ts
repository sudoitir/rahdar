import type { ComponentType, ReactNode } from 'react';

import type { ResourceRef } from '../api/resourceId.ts';
import type { components } from '../api/schema.d.ts';
import type { GateVerdict } from '../../ui/capabilityGate.ts';

export type { ResourceRef };

type Schemas = components['schemas'];

/**
 * The sections of a row's action menu, in the order it shows them (ADR-0107). The list is closed,
 * like the navigation groups: an action names one, and adding one is a kernel change.
 */
export const ACTION_SECTIONS = [
  { id: 'open', label: 'Open' },
  { id: 'copy', label: 'Copy' },
  { id: 'operate', label: 'Operate' },
  { id: 'destroy', label: 'Destroy' },
] as const;

export type ActionSection = (typeof ACTION_SECTIONS)[number]['id'];

/**
 * `act` offers everything; `navigate` only the Open and Copy sections, for a view that must never
 * change the broker (Flow).
 */
export type ActionMode = 'act' | 'navigate';

/*
 * What an action is about: the resource's identity, and — when the row it came from carried it —
 * the generated view the row was drawn from. An item that needs a figure the target lacks looks
 * the resource up, and is offered while that loads: unknown is not unavailable. Targets are keyed
 * by the resource's role, never by what one system calls it; each carries the resource's canonical
 * `id`, its `kind` and its `name`.
 */
export interface SubscriptionTarget extends ResourceRef {
  /** The canonical id of the destination it is bound to. */
  destination?: string | null;
  snapshot?: Schemas['SubscriptionView'];
}

export interface DestinationTarget extends ResourceRef {
  snapshot?: Schemas['DestinationView'];
}

/** Connections, channels, consumers and producers are per node; a close needs to know which. */
interface NodeScoped {
  nodeId: string;
  nodeName: string;
}

export interface ConnectionTarget extends ResourceRef, NodeScoped {
  snapshot?: Schemas['ConnectionView'];
}

export interface ChannelTarget extends ResourceRef, NodeScoped {
  /** The name of the connection the channel belongs to. */
  connection?: string | null;
  snapshot?: Schemas['ChannelView'];
}

export interface ConsumerTarget extends ResourceRef, NodeScoped {
  /** The canonical id of the subscription it consumes from. */
  subscription?: string | null;
  /** The name of the channel it belongs to. */
  channel?: string | null;
  snapshot?: Schemas['ConsumerView'];
}

export interface ProducerTarget extends ResourceRef, NodeScoped {
  /** The canonical id of the destination it sends to. */
  destination?: string | null;
  /** The name of the channel it belongs to. */
  channel?: string | null;
  snapshot?: Schemas['ProducerView'];
}

export interface MessageTarget {
  /** The subscription the message sits in. */
  subscription: ResourceRef;
  position: string;
  /** The node the message was browsed on; absent for the live node. */
  node?: string;
}

export interface RouteTarget extends ResourceRef {
  snapshot?: Schemas['RouteView'];
}

/** A client of the flow view: an identity that may stand for many connections. */
export interface ClientTarget {
  label: string;
}

/** Each resource role a row menu or a link can be about, with its target. */
export interface ActionTargets {
  destination: DestinationTarget;
  subscription: SubscriptionTarget;
  route: RouteTarget;
  connection: ConnectionTarget;
  channel: ChannelTarget;
  consumer: ConsumerTarget;
  producer: ProducerTarget;
  message: MessageTarget;
  client: ClientTarget;
}

export type ActionKind = keyof ActionTargets;

/** What the action host gives a dialog it hosts. */
export interface HostedDialogProps {
  opened: boolean;
  onClose: () => void;
}

type Blocked = Extract<GateVerdict, { kind: 'blocked' }>;

/**
 * Where an action's dialog lives (ADR-0107): outside the grid, so it outlives the row that opened
 * it, the menu that offered it, and the refresh that removes the row when the action succeeds.
 */
export interface ActionHost {
  /**
   * Opens `Dialog` with `props`, plus `opened` and `onClose`. On close, focus goes back through
   * `restoreFocus` — unless the dialog navigated away.
   */
  open<P extends HostedDialogProps>(
    Dialog: ComponentType<P>,
    props: NoInfer<Omit<P, keyof HostedDialogProps>>,
    options?: { restoreFocus?: () => void },
  ): void;
  /** Explains why an action is unavailable, in full. */
  explain(verdict: Blocked, what: string, options?: { restoreFocus?: () => void }): void;
  /** Copies `text` and says so; `what` names it ("subscription name"). */
  copy(text: string, what: string): void;
}

/** What a row-action contribution is given. */
export interface ActionProps<T> {
  clusterId: string;
  target: T;
  host: ActionHost;
  mode: ActionMode;
}

/** What a link contribution is given: it wraps `children` (the name) in a link to the resource. */
export interface LinkProps<T> {
  clusterId: string;
  target: T;
  children: ReactNode;
}
