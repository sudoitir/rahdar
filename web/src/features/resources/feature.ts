import { createElement } from 'react';
import { IconAt, IconNetwork, IconPlugConnected, IconSend, IconUsers } from '@tabler/icons-react';
import { createRoute } from '@tanstack/react-router';

import { CONTRACT, defineFeature } from '../../kernel/feature.ts';
import { clusterRoute } from '../../kernel/routing/roots.ts';
import type { SlotContributions } from '../../kernel/slots.ts';
import { lazyFeatureView } from '../../kernel/routing/lazy.tsx';
import { validateResourceSearch } from '../../kernel/routing/search.ts';
import { queuesParts } from '../queues/index.ts';
import { keys } from './api.ts';
import { ResourcesPalette } from './ResourcesPalette.tsx';
import {
  AddressCloseConsumers,
  AddressCopy,
  AddressLink,
  AddressOpenConsumers,
  ConnectionClose,
  ConnectionCopy,
  ConnectionLink,
  ConnectionOpenSessions,
  ConsumerClose,
  ConsumerCopy,
  ConsumerOpenSession,
  ProducerCopy,
  ProducerOpenSession,
  SessionClose,
  SessionCopy,
  SessionLink,
  SessionOpenRelated,
} from './rowActions.tsx';

const KINDS = [
  { kind: 'addresses', label: 'Addresses', icon: IconAt, hotkey: 'a' },
  { kind: 'consumers', label: 'Consumers', icon: IconUsers, hotkey: 'c' },
  { kind: 'sessions', label: 'Sessions', icon: IconPlugConnected, hotkey: 'i' },
  { kind: 'connections', label: 'Connections', icon: IconNetwork, hotkey: 'n' },
  { kind: 'producers', label: 'Producers', icon: IconSend, hotkey: 'p' },
] as const;

/** One kind's view: the shared `ResourceView`, loaded on first visit and bound to the kind. */
async function loadKindView(kind: (typeof KINDS)[number]['kind']) {
  const { ResourceView } = await import('./ResourceView.tsx');
  return { KindView: () => createElement(ResourceView, { kind }) };
}

const resourceRoutes = KINDS.map(({ kind }) =>
  createRoute({
    getParentRoute: () => clusterRoute,
    path: kind,
    component: lazyFeatureView('resources', () => loadKindView(kind), 'KindView'),
    validateSearch: validateResourceSearch,
  }),
);

/** The slots of both halves of this feature, with each slot's entries from both. */
function mergeSlots(...parts: SlotContributions[]): SlotContributions {
  const merged: Record<string, unknown[]> = {};
  for (const part of parts) {
    for (const [name, entries] of Object.entries(part)) merged[name] = [...(merged[name] ?? []), ...entries];
  }
  return merged as SlotContributions;
}

const resourceSlots: SlotContributions = {
  'connection.actions': [
    { id: 'resources.connection.open', order: 20, section: 'open', Component: ConnectionOpenSessions },
    { id: 'resources.connection.copy', order: 10, section: 'copy', Component: ConnectionCopy },
    { id: 'resources.connection.close', order: 10, section: 'operate', Component: ConnectionClose },
  ],
  'session.actions': [
    { id: 'resources.session.open', order: 20, section: 'open', Component: SessionOpenRelated },
    { id: 'resources.session.copy', order: 10, section: 'copy', Component: SessionCopy },
    { id: 'resources.session.close', order: 10, section: 'operate', Component: SessionClose },
  ],
  'consumer.actions': [
    { id: 'resources.consumer.open', order: 20, section: 'open', Component: ConsumerOpenSession },
    { id: 'resources.consumer.copy', order: 10, section: 'copy', Component: ConsumerCopy },
    { id: 'resources.consumer.close', order: 10, section: 'operate', Component: ConsumerClose },
  ],
  'producer.actions': [
    { id: 'resources.producer.open', order: 20, section: 'open', Component: ProducerOpenSession },
    { id: 'resources.producer.copy', order: 10, section: 'copy', Component: ProducerCopy },
  ],
  'address.actions': [
    { id: 'resources.address.open', order: 20, section: 'open', Component: AddressOpenConsumers },
    { id: 'resources.address.copy', order: 10, section: 'copy', Component: AddressCopy },
    { id: 'resources.address.close', order: 10, section: 'operate', Component: AddressCloseConsumers },
  ],
  'connection.link': [{ id: 'resources.connection.link', order: 10, Component: ConnectionLink }],
  'session.link': [{ id: 'resources.session.link', order: 10, Component: SessionLink }],
  'address.link': [{ id: 'resources.address.link', order: 10, Component: AddressLink }],
};

/**
 * Queues and addresses with their lifecycle, and the live cross-node views of addresses, consumers, sessions,
 * connections and producers with closing connections: one feature, as on the backend.
 */
export const resourcesFeature = defineFeature({
  contract: CONTRACT,
  id: 'resources',
  routes: { cluster: [...queuesParts.routes.cluster, ...resourceRoutes] },
  nav: [
    ...queuesParts.nav,
    ...KINDS.map(({ kind, label, icon, hotkey }, i) => ({
      group: 'resources' as const,
      order: (i + 1) * 10,
      label,
      icon,
      path: kind,
      hotkey,
      permission: 'cluster:read',
    })),
  ],
  palette: ResourcesPalette,
  slots: mergeSlots(queuesParts.slots, resourceSlots),
  streamTopics: {
    ...queuesParts.streamTopics,
    consumers: ({ clusterId, invalidate }) => invalidate(keys.topic(clusterId, 'consumers')),
    sessions: ({ clusterId, invalidate }) => invalidate(keys.topic(clusterId, 'sessions')),
    connections: ({ clusterId, invalidate }) => invalidate(keys.topic(clusterId, 'connections')),
  },
});
