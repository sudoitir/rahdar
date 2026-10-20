import type { SqlIndexSubscriptionView } from './api.ts';

/** Artemis wildcard matching: words split on '.', '*' is exactly one word, '#' is any number. */
function matches(pattern: string[], address: string[]): boolean {
  if (pattern.length === 0) return address.length === 0;
  const [head, ...rest] = pattern;
  if (head === '#') {
    return Array.from({ length: address.length + 1 }, (_, i) => i).some((i) => matches(rest, address.slice(i)));
  }
  return address.length > 0 && (head === '*' || head === address[0]) && matches(rest, address.slice(1));
}

/** The addresses no enabled capture-everything subscription covers, in the order given. */
export function uncapturedAddresses(subscriptions: SqlIndexSubscriptionView[], addresses: string[]): string[] {
  const patterns = subscriptions
    .filter((s) => s.mode === 'CAPTURE' && s.enabled && s.subscriptionPattern)
    .map((s) => (s.subscriptionPattern as string).split('.'));
  return [...new Set(addresses)].filter((a) => !patterns.some((p) => matches(p, a.split('.'))));
}
