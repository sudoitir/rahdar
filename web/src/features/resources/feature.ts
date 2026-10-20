import { createElement } from 'react';
import { IconAt, IconListDetails, IconNetwork, IconPlugConnected, IconSend, IconUsers } from '@tabler/icons-react';
import { createRoute } from '@tanstack/react-router';

import { CONTRACT, defineFeature } from '../../kernel/feature.ts';
import { clusterRoute } from '../../kernel/routing/roots.ts';
import type { SlotContributions } from '../../kernel/slots.ts';
import { lazyFeatureView } from '../../kernel/routing/lazy.tsx';
import { validateResourceSearch, type ResourceSearch } from '../../kernel/routing/search.ts';
import { keys } from './api.ts';
import {
  ChannelClose,
  ChannelCopy,
  ChannelLink,
  ChannelOpenRelated,
  ConnectionClose,
  ConnectionCopy,
  ConnectionLink,
  ConnectionOpenChannels,
  ConsumerClose,
  ConsumerCopy,
  ConsumerOpenChannel,
  DestinationCloseConsumers,
  DestinationCopy,
  DestinationLink,
  DestinationOpenConsumers,
  ProducerCopy,
  ProducerOpenChannel,
} from './rowActions.tsx';
import {
  ConsumerOpenSubscription,
  CopySubscriptionLink,
  CopySubscriptionName,
  DeleteSubscription,
  DestinationOpenSubscriptions,
  EditSubscription,
  OpenSubscription,
  PauseResumeSubscription,
  ProducerOpenSubscriptions,
  SubscriptionLink,
} from './SubscriptionActions.tsx';
import { ResourcesPalette } from './ResourcesPalette.tsx';

/** The subscriptions' own state, plus the one whose detail is open — a shareable address. */
export interface SubscriptionsSearch extends ResourceSearch {
  subscription?: string;
}

function validateSubscriptionsSearch(raw: Record<string, unknown>): SubscriptionsSearch {
  const out: SubscriptionsSearch = validateResourceSearch(raw);
  if (typeof raw.subscription === 'string' && raw.subscription) out.subscription = raw.subscription;
  return out;
}

const subscriptionsRoute = createRoute({
  getParentRoute: () => clusterRoute,
  path: 'subscriptions',
  component: lazyFeatureView('resources', () => import('./SubscriptionsView.tsx'), 'SubscriptionsView'),
  validateSearch: validateSubscriptionsSearch,
});

const KINDS = [
  { kind: 'destinations', label: 'Destinations', icon: IconAt, hotkey: 'a' },
  { kind: 'consumers', label: 'Consumers', icon: IconUsers, hotkey: 'c' },
  { kind: 'channels', label: 'Channels', icon: IconPlugConnected, hotkey: 'i' },
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

const resourceSlots: SlotContributions = {
  'subscription.actions': [
    { id: 'resources.subscription.open', order: 10, section: 'open', Component: OpenSubscription },
    { id: 'resources.subscription.copy-name', order: 10, section: 'copy', Component: CopySubscriptionName },
    { id: 'resources.subscription.copy-link', order: 20, section: 'copy', Component: CopySubscriptionLink },
    { id: 'resources.subscription.pause', order: 10, section: 'operate', Component: PauseResumeSubscription },
    { id: 'resources.subscription.edit', order: 20, section: 'operate', Component: EditSubscription },
    { id: 'resources.subscription.delete', order: 10, section: 'destroy', Component: DeleteSubscription },
  ],
  'subscription.link': [{ id: 'resources.subscription.link', order: 10, Component: SubscriptionLink }],
  'connection.actions': [
    { id: 'resources.connection.open', order: 20, section: 'open', Component: ConnectionOpenChannels },
    { id: 'resources.connection.copy', order: 10, section: 'copy', Component: ConnectionCopy },
    { id: 'resources.connection.close', order: 10, section: 'operate', Component: ConnectionClose },
  ],
  'channel.actions': [
    { id: 'resources.channel.open', order: 20, section: 'open', Component: ChannelOpenRelated },
    { id: 'resources.channel.copy', order: 10, section: 'copy', Component: ChannelCopy },
    { id: 'resources.channel.close', order: 10, section: 'operate', Component: ChannelClose },
  ],
  'consumer.actions': [
    { id: 'resources.consumer.open-subscription', order: 10, section: 'open', Component: ConsumerOpenSubscription },
    { id: 'resources.consumer.open', order: 20, section: 'open', Component: ConsumerOpenChannel },
    { id: 'resources.consumer.copy', order: 10, section: 'copy', Component: ConsumerCopy },
    { id: 'resources.consumer.close', order: 10, section: 'operate', Component: ConsumerClose },
  ],
  'producer.actions': [
    { id: 'resources.producer.open-subscriptions', order: 10, section: 'open', Component: ProducerOpenSubscriptions },
    { id: 'resources.producer.open', order: 20, section: 'open', Component: ProducerOpenChannel },
    { id: 'resources.producer.copy', order: 10, section: 'copy', Component: ProducerCopy },
  ],
  'destination.actions': [
    {
      id: 'resources.destination.open-subscriptions',
      order: 10,
      section: 'open',
      Component: DestinationOpenSubscriptions,
    },
    { id: 'resources.destination.open', order: 20, section: 'open', Component: DestinationOpenConsumers },
    { id: 'resources.destination.copy', order: 10, section: 'copy', Component: DestinationCopy },
    { id: 'resources.destination.close', order: 10, section: 'operate', Component: DestinationCloseConsumers },
  ],
  'connection.link': [{ id: 'resources.connection.link', order: 10, Component: ConnectionLink }],
  'channel.link': [{ id: 'resources.channel.link', order: 10, Component: ChannelLink }],
  'destination.link': [{ id: 'resources.destination.link', order: 10, Component: DestinationLink }],
};

/**
 * Subscriptions and destinations with their lifecycle, and the live cross-node views of destinations,
 * consumers, channels, connections and producers with closing connections: one feature, as on the backend.
 */
export const resourcesFeature = defineFeature({
  contract: CONTRACT,
  id: 'resources',
  routes: { cluster: [subscriptionsRoute, ...resourceRoutes] },
  nav: [
    {
      group: 'messaging',
      order: 10,
      label: 'Subscriptions',
      icon: IconListDetails,
      path: 'subscriptions',
      hotkey: 'q',
      permission: 'cluster:read',
    },
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
  slots: resourceSlots,
  // The stream's topics keep the server's names; each invalidates the views of the role it concerns.
  streamTopics: {
    queues: ({ clusterId, invalidate }) => invalidate(keys.topic(clusterId, 'subscriptions')),
    consumers: ({ clusterId, invalidate }) => invalidate(keys.topic(clusterId, 'consumers')),
    sessions: ({ clusterId, invalidate }) => invalidate(keys.topic(clusterId, 'channels')),
    connections: ({ clusterId, invalidate }) => invalidate(keys.topic(clusterId, 'connections')),
  },
});
