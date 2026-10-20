import { useEffect, useRef } from 'react';
import { useNavigate, useParams } from '@tanstack/react-router';

import { FEATURE_IDS, type NavContribution } from '../feature.ts';
import { useFeatures } from '../features.ts';
import { useClusterSystem } from '../shell/useClusterSystem.ts';
import { focusFilter } from './filterShortcut.ts';
import { ignoredForShortcuts, latinKey } from './keys.ts';
import { setShortcutsHelpOpen, singleKeyShortcutsEnabled } from './shortcuts.ts';

/** How long after `g` the view letter is waited for. */
const SEQUENCE_MS = 1200;

const BUILT_IN = new Set<string>(FEATURE_IDS);

/**
 * The views that have a `g` letter: built-in views only — a plugin's letter is ignored, so a plugin
 * can never take a letter an operator already relies on (ADR-0109). A system-only view has no letter on a
 * cluster known to run another system; while the system is not known the letter is offered.
 */
export function viewHotkeys(
  features: { id: string; nav?: NavContribution[] }[],
  systemId?: string,
): Map<string, NavContribution> {
  const out = new Map<string, NavContribution>();
  for (const feature of features) {
    if (!BUILT_IN.has(feature.id)) continue;
    for (const item of feature.nav ?? []) {
      if (item.system && systemId && item.system !== systemId) continue;
      if (item.hotkey && !out.has(item.hotkey)) out.set(item.hotkey, item);
    }
  }
  return out;
}

/**
 * The single-key shortcuts (ADR-0109), as one document listener mounted by the shell:
 * `g` then a view's letter goes to that view of the open cluster, `?` opens the list of shortcuts,
 * and `/` focuses the view's filter when it has one. All of them are off when the operator turned
 * single-key shortcuts off, and ignored while typing, with a modifier, and inside dialogs and menus.
 */
export function useKeySequences() {
  const navigate = useNavigate();
  const { clusterId } = useParams({ strict: false }) as { clusterId?: string };
  const features = useFeatures();
  const systemId = useClusterSystem(clusterId);
  const pendingUntil = useRef(0);
  const state = useRef({ navigate, clusterId, hotkeys: viewHotkeys(features, systemId) });
  state.current = { navigate, clusterId, hotkeys: viewHotkeys(features, systemId) };

  useEffect(() => {
    const onKeyDown = (event: KeyboardEvent) => {
      if (!singleKeyShortcutsEnabled() || ignoredForShortcuts(event)) return;
      const key = latinKey(event);
      const { navigate: go, clusterId: cluster, hotkeys } = state.current;

      if (performance.now() < pendingUntil.current) {
        pendingUntil.current = 0;
        const item = hotkeys.get(key);
        if (item && cluster) {
          event.preventDefault();
          void go({ to: `/clusters/${cluster}/${item.path}` });
        }
        return;
      }
      if (key === 'g' && !event.shiftKey) {
        pendingUntil.current = performance.now() + SEQUENCE_MS;
        return;
      }
      if (key === '?') {
        event.preventDefault();
        setShortcutsHelpOpen(true);
        return;
      }
      // Only when the view has a filter: elsewhere `/` stays the browser's (Firefox's quick find).
      if (key === '/' && focusFilter()) {
        event.preventDefault();
      }
    };
    document.addEventListener('keydown', onKeyDown);
    return () => document.removeEventListener('keydown', onKeyDown);
  }, []);
}
