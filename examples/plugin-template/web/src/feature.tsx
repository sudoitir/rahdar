import { createRoute } from '@tanstack/react-router';
import { IconNotes } from '@tabler/icons-react';
import { CONTRACT, clusterRoute, definePlugin, pluginPath, pluginView } from '@rahdar/plugin-sdk';

import { ID, notesKey } from './api.ts';
import { NotesList } from './NotesList.tsx';
import { NotesView } from './NotesView.tsx';

/** A page of the cluster, under the plugin's own path: /clusters/<id>/p/acme-notes. */
const notesRoute = createRoute({
  getParentRoute: () => clusterRoute,
  path: pluginPath(ID),
  component: pluginView(ID, NotesView),
});

/**
 * Everything the plugin adds to Rahdar's UI. Rahdar accepts it only when it stays in its own
 * namespace: routes under p/acme-notes, slot entries named acme-notes.…, the stream topics its
 * plugin.json declares.
 */
export default definePlugin({
  contract: CONTRACT,
  id: ID,
  routes: { cluster: [notesRoute] },
  nav: [{ group: 'messaging', order: 90, label: 'Notes', icon: IconNotes, path: pluginPath(ID), permission: `${ID}:read` }],
  slots: {
    // In every subscription's detail drawer.
    'subscription.detail.panels': [
      {
        id: `${ID}.subscription-notes`,
        order: 90,
        title: 'Notes',
        Component: ({ clusterId, subscription }) => <NotesList clusterId={clusterId} subscription={subscription.name} />,
      },
    ],
  },
  streamTopics: {
    // The backend publishes this topic on every change; open views refresh.
    [ID]: ({ clusterId, invalidate }) => invalidate(notesKey(clusterId)),
  },
});
