import { describe, expect, it } from 'vitest';
import { createRoute } from '@tanstack/react-router';

import { pluginEntry } from '../../test/manifest.ts';
import { CONTRACT } from '../feature.ts';
import { clusterRoute, rootRoute } from '../routing/roots.ts';
import { checkPlugin } from './validate.ts';

const Panel = () => null;
const entry = pluginEntry('acme-notes', { topics: ['acme-notes'] });

function plugin(overrides: Record<string, unknown> = {}) {
  return { contract: CONTRACT, id: 'acme-notes', ...overrides };
}

describe('checkPlugin', () => {
  it('accepts a plugin that keeps to its namespace', () => {
    const route = createRoute({ getParentRoute: () => clusterRoute, path: 'p/acme-notes/notes', component: Panel });
    const checked = checkPlugin(
      entry,
      plugin({
        routes: { cluster: [route] },
        nav: [{ group: 'observe', order: 1, label: 'Notes', icon: Panel, path: 'p/acme-notes/notes' }],
        streamTopics: { 'acme-notes': () => {} },
        slots: {
          'settings.sections': [
            { id: 'acme-notes.settings', order: 1, title: 'Notes', group: 'cluster', Component: Panel },
          ],
          'subscription.actions': [{ id: 'acme-notes.annotate', order: 1, section: 'operate', Component: Panel }],
          'admin.tabs': [
            { id: 'acme-notes.policy', order: 1, title: 'Note policy', group: 'governance', Component: Panel },
          ],
        },
      }),
    );
    expect(checked.ok).toBe(true);
    if (!checked.ok) return;
    // A row action is kept, in its section, and guarded like any other contribution.
    const action = checked.feature.slots?.['subscription.actions']?.[0];
    expect(action?.section).toBe('operate');
    expect(action?.Component).not.toBe(Panel);
    const section = checked.feature.slots?.['settings.sections']?.[0];
    expect(section?.group).toBe('plugins');
    expect(section?.Component).not.toBe(Panel);
    // An administration tab keeps the group it names: Governance is where its operators look.
    expect(checked.feature.slots?.['admin.tabs']?.[0]?.group).toBe('governance');
  });

  it.each([
    ['a newer contract', plugin({ contract: CONTRACT + 1 }), /extension contract/],
    ['an older contract', plugin({ contract: CONTRACT - 1 }), /extension contract/],
    ['another id', plugin({ id: 'acme-other' }), /calls itself/],
    ['nothing exported', undefined, /no default export/],
    [
      'a route outside its namespace',
      plugin({
        routes: { root: [createRoute({ getParentRoute: () => rootRoute, path: 'admin/evil', component: Panel })] },
      }),
      /outside p\/acme-notes/,
    ],
    ['an undeclared topic', plugin({ streamTopics: { subscriptions: () => {} } }), /never declared/],
    [
      'a slot id outside its namespace',
      plugin({ slots: { 'shell.header': [{ id: 'banner', order: 0, Component: Panel }] } }),
      /not named acme-notes/,
    ],
    [
      'a contribution to a built-in link',
      plugin({ slots: { 'subscription.link': [{ id: 'acme-notes.link', order: 0, Component: Panel }] } }),
      /links to built-in resources are Rahdar's own/,
    ],
    [
      'a row action with no section',
      plugin({ slots: { 'subscription.actions': [{ id: 'acme-notes.note', order: 0, Component: Panel }] } }),
      /names no menu section/,
    ],
    [
      'an administration tab with no group',
      plugin({ slots: { 'admin.tabs': [{ id: 'acme-notes.policy', order: 0, title: 'Policy', Component: Panel }] } }),
      /names no administration group/,
    ],
    [
      'an administration tab in an unknown group',
      plugin({
        slots: {
          'admin.tabs': [{ id: 'acme-notes.policy', order: 0, title: 'Policy', group: 'plugins', Component: Panel }],
        },
      }),
      /names no administration group/,
    ],
    [
      'an unknown navigation group',
      plugin({ nav: [{ group: 'nope', order: 1, label: 'X', icon: Panel, path: 'p/acme-notes' }] }),
      /navigation group/,
    ],
  ])('refuses %s', (_label, exported, reason) => {
    const checked = checkPlugin(entry, exported);
    expect(checked.ok).toBe(false);
    if (!checked.ok) expect(checked.reason).toMatch(reason);
  });
});
