import { useMemo } from 'react';
import { useDebouncedValue } from '@mantine/hooks';

import { useQueues } from '../queues/index.ts';

const SUGGESTION_LIMIT = 300;

/** A pattern is anything containing `*`; everything else is a literal address. */
function isPattern(entry: string): boolean {
  return entry.includes('*');
}

/**
 * Mirrors the backend's `ReplyDestinationResolver.compile`: `*` matches any run of
 * characters, every other character is literal, anchored at both ends. Kept in step
 * with it deliberately — an operator shown one set of matches whose broker is then
 * browsed for another has been told a lie about what is traced.
 */
function globToRegExp(glob: string): RegExp {
  const parts = glob.split('*').map((part) => part.replaceAll(/[.+?^${}()|[\]\\]/g, String.raw`\$&`));
  return new RegExp(`^${parts.join('.*')}$`, 's');
}

/** What a set of literals and globs currently expands to, given the addresses we know. */
function resolveAgainst(entries: string[], known: string[]): string[] {
  const out = new Set<string>();
  for (const entry of entries) {
    if (!isPattern(entry)) {
      out.add(entry);
      continue;
    }
    const re = globToRegExp(entry);
    for (const address of known) {
      if (re.test(address)) out.add(address);
    }
  }
  return [...out];
}

export interface ReplyDestinationResolution {
  resolved: string[];
  unmatched: string[];
  isError: boolean;
  retry: () => void;
  known: string[];
}

/**
 * What the entered literals and globs expand to against the addresses this cluster
 * currently has.
 *
 * Its own module rather than a second export from `ReplyDestinationsInput`: the control
 * and its help text render in different rows of the form grid and have to agree about
 * what is traced, and a hook exported alongside a component costs fast refresh.
 */
export function useReplyDestinationResolution(clusterId: string, value: string[]): ReplyDestinationResolution {
  // The address list is a background fact, not a per-keystroke query: matches are
  // computed here, so typing costs nothing over the wire.
  const queues = useQueues(clusterId, { size: SUGGESTION_LIMIT });
  const [entries] = useDebouncedValue(value, 200);

  const known = useMemo(() => {
    const addresses = new Set<string>();
    for (const row of queues.data?.data ?? []) addresses.add(row.address);
    return [...addresses].sort((a, b) => a.localeCompare(b));
  }, [queues.data]);

  const resolved = useMemo(() => resolveAgainst(entries, known), [entries, known]);
  const unmatched = useMemo(
    () =>
      entries.filter((entry) => {
        if (!isPattern(entry)) return false;
        const re = globToRegExp(entry);
        return !known.some((address) => re.test(address));
      }),
    [entries, known],
  );

  return {
    resolved,
    unmatched,
    isError: queues.isError,
    retry: () => void queues.refetch(),
    known,
  };
}
