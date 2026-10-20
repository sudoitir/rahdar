import { IconListDetails } from '@tabler/icons-react';
import { createRoute } from '@tanstack/react-router';

import type { RahdarFeature } from '../../kernel/feature.ts';
import { clusterRoute } from '../../kernel/routing/roots.ts';
import { lazyFeatureView } from '../../kernel/routing/lazy.tsx';
import { validateResourceSearch, type ResourceSearch } from '../../kernel/routing/search.ts';
import { keys } from './api.ts';
import {
  AddressOpenQueues,
  ConsumerOpenQueue,
  ProducerOpenQueues,
  CopyQueueLink,
  CopyQueueName,
  DeleteQueue,
  EditQueue,
  OpenQueue,
  PauseResumeQueue,
  QueueLink,
} from './QueueActions.tsx';
import { QueuePalette } from './QueuePalette.tsx';

/** The listing's own state, plus the queue whose detail is open — a shareable address. */
export interface QueuesSearch extends ResourceSearch {
  queue?: string;
}

function validateQueuesSearch(raw: Record<string, unknown>): QueuesSearch {
  const out: QueuesSearch = validateResourceSearch(raw);
  if (typeof raw.queue === 'string' && raw.queue) out.queue = raw.queue;
  return out;
}

const queuesRoute = createRoute({
  getParentRoute: () => clusterRoute,
  path: 'queues',
  component: lazyFeatureView('resources', () => import('./QueuesView.tsx'), 'QueuesView'),
  validateSearch: validateQueuesSearch,
});

/**
 * What the queues folder adds to the Resources feature: the Queues view and its lifecycle (create, edit,
 * pause, delete), the queue slots and the `queues` stream topic. The backend has one module for queues and
 * the live resource views, so the frontend registers them as one feature (`../resources/feature.ts`).
 */
export const queuesParts = {
  routes: { cluster: [queuesRoute] },
  nav: [
    {
      group: 'messaging',
      order: 10,
      label: 'Queues',
      icon: IconListDetails,
      path: 'queues',
      hotkey: 'q',
      permission: 'cluster:read',
    },
  ],
  palette: QueuePalette,
  slots: {
    'queue.actions': [
      { id: 'queues.open', order: 10, section: 'open', Component: OpenQueue },
      { id: 'queues.copy-name', order: 10, section: 'copy', Component: CopyQueueName },
      { id: 'queues.copy-link', order: 20, section: 'copy', Component: CopyQueueLink },
      { id: 'queues.pause', order: 10, section: 'operate', Component: PauseResumeQueue },
      { id: 'queues.edit', order: 20, section: 'operate', Component: EditQueue },
      { id: 'queues.delete', order: 10, section: 'destroy', Component: DeleteQueue },
    ],
    'queue.link': [{ id: 'queues.link', order: 10, Component: QueueLink }],
    // What a queue means on the rows of the resources that name one.
    'consumer.actions': [{ id: 'queues.consumer.open', order: 10, Component: ConsumerOpenQueue, section: 'open' }],
    'address.actions': [{ id: 'queues.address.open', order: 10, Component: AddressOpenQueues, section: 'open' }],
    'producer.actions': [{ id: 'queues.producer.open', order: 10, Component: ProducerOpenQueues, section: 'open' }],
  },
  streamTopics: {
    queues: ({ clusterId, invalidate }) => invalidate(keys.topic(clusterId, 'queues')),
  },
} satisfies Pick<RahdarFeature, 'routes' | 'nav' | 'palette' | 'slots' | 'streamTopics'>;
