import { IconAlertTriangle } from '@tabler/icons-react';
import { createRoute } from '@tanstack/react-router';

import { CONTRACT, defineFeature } from '../../kernel/feature.ts';
import { clusterRoute } from '../../kernel/routing/roots.ts';
import { lazyFeatureView } from '../../kernel/routing/lazy.tsx';
import {
  BrowseSubscriptionMessages,
  CopyMessage,
  DeleteMessage,
  MoveMessage,
  OpenMessage,
  RetryMessage,
} from './rowActions.tsx';

/** Message-browse navigable state (ADR-0021). Selection stays ephemeral (D10), not in the URL. */
export interface MessagesSearch {
  node?: string;
  filter?: string;
  page?: number;
  /** The message whose detail is open — so a link to one message opens it. */
  message?: string;
}

function validateMessagesSearch(raw: Record<string, unknown>): MessagesSearch {
  const out: MessagesSearch = {};
  if (typeof raw.node === 'string' && raw.node) out.node = raw.node;
  if (typeof raw.filter === 'string' && raw.filter) out.filter = raw.filter;
  if ((typeof raw.message === 'string' || typeof raw.message === 'number') && /^\d+$/.test(String(raw.message))) {
    out.message = String(raw.message);
  }
  const page = Number(raw.page);
  if (Number.isFinite(page) && page > 1) out.page = Math.floor(page);
  return out;
}

const messagesRoute = createRoute({
  getParentRoute: () => clusterRoute,
  path: 'subscriptions/$subscriptionName/messages',
  component: lazyFeatureView('messages', () => import('./MessagesView.tsx'), 'MessagesView'),
  validateSearch: validateMessagesSearch,
});

const deadLettersRoute = createRoute({
  getParentRoute: () => clusterRoute,
  path: 'dead-letters',
  component: lazyFeatureView('messages', () => import('./DeadLettersView.tsx'), 'DeadLettersView'),
});

/** Browsing, sending, moving and deleting messages, and the dead-letter view. */
export const messagesFeature = defineFeature({
  contract: CONTRACT,
  id: 'messages',
  routes: { cluster: [messagesRoute, deadLettersRoute] },
  nav: [
    {
      group: 'messaging',
      order: 20,
      label: 'Dead letters',
      icon: IconAlertTriangle,
      path: 'dead-letters',
      hotkey: 'd',
      permission: 'message:read',
    },
  ],
  slots: {
    'subscription.actions': [
      { id: 'messages.browse', order: 20, section: 'open', Component: BrowseSubscriptionMessages },
    ],
    'message.actions': [
      { id: 'messages.open', order: 10, section: 'open', Component: OpenMessage },
      { id: 'messages.copy', order: 10, section: 'copy', Component: CopyMessage },
      { id: 'messages.move', order: 10, section: 'operate', Component: MoveMessage },
      { id: 'messages.retry', order: 20, section: 'operate', Component: RetryMessage },
      { id: 'messages.delete', order: 10, section: 'destroy', Component: DeleteMessage },
    ],
  },
});
