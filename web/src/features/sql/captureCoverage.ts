import type { SqlIndexSubscriptionView } from './api.ts';

/** Artemis wildcard matching: words split on '.', '*' is exactly one word, '#' is any number. */
function matches(pattern: string[], destination: string[]): boolean {
  if (pattern.length === 0) return destination.length === 0;
  const [head, ...rest] = pattern;
  if (head === '#') {
    return Array.from({ length: destination.length + 1 }, (_, i) => i).some((i) => matches(rest, destination.slice(i)));
  }
  return destination.length > 0 && (head === '*' || head === destination[0]) && matches(rest, destination.slice(1));
}

/** The destinations no enabled capture-everything subscription covers, in the order given. */
export function uncapturedDestinations(subscriptions: SqlIndexSubscriptionView[], destinations: string[]): string[] {
  const patterns = subscriptions
    .filter((s) => s.mode === 'CAPTURE' && s.enabled && s.subscriptionPattern)
    .map((s) => (s.subscriptionPattern as string).split('.'));
  return [...new Set(destinations)].filter((a) => !patterns.some((p) => matches(p, a.split('.'))));
}
