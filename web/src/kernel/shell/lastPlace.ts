import { useSyncExternalStore } from 'react';

/**
 * Where the operator last was inside a cluster, so a page outside any cluster (Administration, the inbox,
 * an account) can lead straight back to it. Browser-local and per viewer, like the palette's recents: a
 * convenience that is fine to lose with site data, never state anything depends on.
 */
export interface LastPlace {
  clusterId: string;
  clusterName: string;
  /** The view the operator was in, such as "Subscriptions". */
  label: string;
  /** The address, and the search it was opened with. */
  to: string;
  search: Record<string, unknown>;
}

const KEY = 'as:last-place';
const listeners = new Set<() => void>();
let cached: { raw: string | null; value: LastPlace | null } | undefined;

function parse(raw: string | null): LastPlace | null {
  try {
    const value: unknown = raw ? JSON.parse(raw) : null;
    const place = value as Partial<LastPlace> | null;
    return place &&
      typeof place.to === 'string' &&
      typeof place.clusterId === 'string' &&
      typeof place.label === 'string'
      ? ({ clusterName: place.clusterId, search: {}, ...place } as LastPlace)
      : null;
  } catch {
    return null;
  }
}

export function readLastPlace(): LastPlace | null {
  let raw: string | null = null;
  try {
    raw = globalThis.localStorage.getItem(KEY);
  } catch {
    // Storage refused: there is no last place.
  }
  if (cached?.raw !== raw) cached = { raw, value: parse(raw) };
  return cached.value;
}

export function recordLastPlace(place: LastPlace) {
  const raw = JSON.stringify(place);
  if (readLastPlace() && cached?.raw === raw) return;
  try {
    globalThis.localStorage.setItem(KEY, raw);
  } catch {
    // Storage refused (private window, quota): the way back is simply absent.
    return;
  }
  listeners.forEach((listener) => listener());
}

export function useLastPlace(): LastPlace | null {
  return useSyncExternalStore(
    (listener) => {
      listeners.add(listener);
      globalThis.addEventListener?.('storage', listener);
      return () => {
        listeners.delete(listener);
        globalThis.removeEventListener?.('storage', listener);
      };
    },
    readLastPlace,
    () => null,
  );
}
