import type { AnyRoute } from '@tanstack/react-router';

import { CONTRACT, type RahdarFeature } from '../feature.ts';
import type { ManifestFeatureView } from '../manifest.ts';
import { ACTION_SECTIONS } from '../actions/types.ts';
import { NAV_GROUPS } from '../nav/groups.ts';
import { ADMIN_GROUPS, type SlotContribution, type SlotContributions, type SlotName } from '../slots.ts';
import { guarded } from './guarded.tsx';

/** A loaded plugin module, accepted and made safe to mount, or the reason it was not. */
export type Checked = { ok: true; feature: RahdarFeature } | { ok: false; reason: string };

const NAV_GROUP_IDS = new Set<string>(NAV_GROUPS.map((group) => group.id));
const ACTION_SECTION_IDS = new Set<string>(ACTION_SECTIONS.map((section) => section.id));
const ADMIN_GROUP_IDS = new Set<string>(ADMIN_GROUPS.map((group) => group.id));

/** The first segment a plugin route's path must have: everything it adds lives under `p/<id>/`. */
export function pluginPathPrefix(id: string): string {
  return `p/${id}`;
}

function routePath(route: AnyRoute): string {
  const path = (route.options as { path?: unknown }).path;
  return typeof path === 'string' ? path.replace(/^\//, '') : '';
}

function underPrefix(path: string, id: string): boolean {
  const prefix = pluginPathPrefix(id);
  return path === prefix || path.startsWith(`${prefix}/`);
}

/** Why the exported feature is not the one the manifest names, or null when it is. */
function identityProblem(id: string, feature: RahdarFeature): string | null {
  if (feature.contract !== CONTRACT) {
    return `it was built for extension contract ${String(feature.contract)}, and this Rahdar has ${CONTRACT}`;
  }
  return feature.id === id ? null : `its bundle calls itself ${String(feature.id)}`;
}

/** Why a route or navigation entry leaves the plugin's own `p/<id>/` namespace, or null when none does. */
function namespaceProblem(id: string, feature: RahdarFeature): string | null {
  for (const route of [...(feature.routes?.root ?? []), ...(feature.routes?.cluster ?? [])]) {
    if (!underPrefix(routePath(route), id)) {
      return `its route "${routePath(route)}" is outside ${pluginPathPrefix(id)}/`;
    }
  }
  for (const nav of feature.nav ?? []) {
    if (!NAV_GROUP_IDS.has(nav.group)) return `it names no such navigation group "${nav.group}"`;
    if (!underPrefix(nav.path.replace(/^\//, ''), id)) {
      return `its navigation entry "${nav.label}" leads outside ${pluginPathPrefix(id)}/`;
    }
  }
  return null;
}

/** Why the plugin handles a stream topic it never declared, or null when it does not. */
function topicProblem(entry: ManifestFeatureView, feature: RahdarFeature): string | null {
  const declaredTopics = new Set(entry.topics);
  for (const topic of Object.keys(feature.streamTopics ?? {})) {
    if (!declaredTopics.has(topic)) return `it handles stream topic "${topic}", which it never declared`;
  }
  return null;
}

/** Why a slot contribution is not the plugin's own to make, or null when it is. */
function contributionProblem(
  id: string,
  name: string,
  action: boolean,
  contribution: SlotContribution<never>,
): string | null {
  if (!contribution.id.startsWith(`${id}.`)) return `its ${name} entry "${contribution.id}" is not named ${id}.…`;
  if (action && !ACTION_SECTION_IDS.has(String(contribution.section))) {
    return `its ${name} entry "${contribution.id}" names no menu section (one of ${[...ACTION_SECTION_IDS].join(', ')})`;
  }
  if (name === 'admin.tabs' && !ADMIN_GROUP_IDS.has(String(contribution.group))) {
    return `its ${name} entry "${contribution.id}" names no administration group (one of ${[...ADMIN_GROUP_IDS].join(', ')})`;
  }
  return null;
}

/** The plugin's slot contributions, each made safe to mount; or the reason one is refused. */
function checkSlots(id: string, feature: RahdarFeature): { slots: SlotContributions } | { reason: string } {
  const slots: SlotContributions = {};
  for (const [name, contributions] of Object.entries(feature.slots ?? {}) as [SlotName, SlotContribution<never>[]][]) {
    // Where a resource name links to is Rahdar's own: a plugin that could redirect every subscription
    // link would be a phishing surface inside the console (ADR-0107).
    if (name.endsWith('.link')) {
      return { reason: `it contributes to ${name}, and links to built-in resources are Rahdar's own` };
    }
    const action = name.endsWith('.actions');
    const problem = contributions
      .map((c) => contributionProblem(id, name, action, c))
      .find((reason) => reason !== null);
    if (problem) return { reason: problem };
    // Header, badge-like and menu slots stay silent on failure — a sentence inside a menu is not a
    // menu item; panels and tabs say what happened.
    const quiet = name === 'shell.header' || name === 'topology.node.marks' || action;
    (slots as Record<string, SlotContribution<never>[]>)[name] = contributions.map((contribution) => ({
      ...contribution,
      // A plugin's settings are listed under Plugins, never mixed into Rahdar's own groups.
      group: name === 'settings.sections' ? 'plugins' : contribution.group,
      Component: guarded(id, contribution.Component, quiet),
    }));
  }
  return { slots };
}

/**
 * Accepts what a plugin's bundle exported only when it keeps to its own namespace (ADR-0100):
 * the contract it was built for, its own id, routes and navigation under `p/<id>/`, slot entries
 * named `<id>.…`, only the stream topics its descriptor declared, and navigation and administration
 * groups the shell has. Every component it contributes to a shared screen is wrapped so a throw stays its own.
 */
export function checkPlugin(entry: ManifestFeatureView, exported: unknown): Checked {
  const id = entry.id;
  if (!exported || typeof exported !== 'object') {
    return { ok: false, reason: 'its bundle has no default export describing the plugin' };
  }
  const feature = exported as RahdarFeature;
  const problem = identityProblem(id, feature) ?? namespaceProblem(id, feature) ?? topicProblem(entry, feature);
  if (problem) return { ok: false, reason: problem };

  const checked = checkSlots(id, feature);
  if ('reason' in checked) return { ok: false, reason: checked.reason };

  return {
    ok: true,
    feature: {
      ...feature,
      slots: checked.slots,
      nav: feature.nav?.map((nav) => (nav.Badge ? { ...nav, Badge: guarded(id, nav.Badge, true) } : nav)),
      palette: feature.palette ? guarded(id, feature.palette, true) : undefined,
    },
  };
}
