import { nameOf } from './resourceId.ts';
import type { components } from './schema.d.ts';

export type RouteView = components['schemas']['RouteView'];

/**
 * The routes of a cluster are one list, a divert being a `FORWARD` route and a bridge a `LINK` one. The two
 * tables of this screen read the rows of their own class, in the shape each was drawn in.
 */
export interface RouteNode {
  nodeId: string;
  nodeName: string;
}

export interface DivertRow {
  name: string;
  routingName?: string | null;
  address: string;
  forwardingAddress: string;
  filter?: string | null;
  routingType?: string | null;
  transformerClassName?: string | null;
  exclusive: boolean;
  retroactiveResource: boolean;
  owner?: string | null;
  captureSubscriptionId?: string | null;
  brokerXml?: string | null;
  nodesPresent: number;
  nodesTotal: number;
  perNode: RouteNode[];
}

export interface BridgeRow {
  name: string;
  queueName?: string | null;
  forwardingAddress?: string | null;
  filterString?: string | null;
  discoveryGroupName?: string | null;
  transformerClassName?: string | null;
  staticConnectors: string[];
  messagesAcknowledged: number;
  messagesPendingAcknowledgement: number;
  started: boolean;
  connected: boolean;
  useDuplicateDetection: boolean;
  highlyAvailable: boolean;
  nodesPresent: number;
  nodesTotal: number;
  perNode: (RouteNode & {
    started?: boolean | null;
    connected?: boolean | null;
    messagesAcknowledged?: number | null;
    messagesPendingAcknowledgement?: number | null;
  })[];
}

export function divertRow(r: RouteView): DivertRow {
  const a = r.nativeAttributes;
  return {
    name: r.name,
    routingName: a.routingName ?? null,
    address: nameOf(r.source),
    forwardingAddress: nameOf(r.target),
    filter: a.filter ?? null,
    routingType: a.routingType ?? null,
    transformerClassName: a.transformerClassName ?? null,
    exclusive: a.exclusive === 'true',
    retroactiveResource: a.retroactiveResource === 'true',
    owner: r.owner ?? null,
    captureSubscriptionId: a.captureSubscriptionId ?? null,
    brokerXml: r.drift ?? null,
    nodesPresent: r.nodesPresent,
    nodesTotal: r.nodesTotal,
    perNode: r.perNode.map(({ nodeId, nodeName }) => ({ nodeId, nodeName })),
  };
}

export function bridgeRow(r: RouteView): BridgeRow {
  const a = r.nativeAttributes;
  return {
    name: r.name,
    queueName: r.source ? nameOf(r.source) : null,
    forwardingAddress: a.forwardingAddress ?? null,
    filterString: a.filter ?? null,
    discoveryGroupName: a.discoveryGroupName ?? null,
    transformerClassName: a.transformerClassName ?? null,
    staticConnectors: a.staticConnectors ? a.staticConnectors.split(',') : [],
    messagesAcknowledged: Number(a.messagesAcknowledged ?? 0),
    messagesPendingAcknowledgement: Number(a.messagesPendingAcknowledgement ?? 0),
    started: a.started === 'true',
    connected: a.connected === 'true',
    useDuplicateDetection: a.useDuplicateDetection === 'true',
    highlyAvailable: a.highlyAvailable === 'true',
    nodesPresent: r.nodesPresent,
    nodesTotal: r.nodesTotal,
    perNode: r.perNode,
  };
}
