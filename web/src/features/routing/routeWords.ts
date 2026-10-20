import { nameOf } from '../../kernel/api/resourceId.ts';
import type { RouteView } from './api.ts';

/** A route's native attribute as the system reports it; blank when it reports none. */
export const attribute = (r: RouteView, key: string): string => r.nativeAttributes[key] ?? '';

/** The text of a route's direction: what it reads from and where it writes, naming what the system did not record. */
export function routeEnds(r: RouteView): { from: string; to: string } {
  return {
    from: r.source ? nameOf(r.source) : '(unnamed source)',
    to: r.target ? nameOf(r.target) : attribute(r, 'forwardingAddress') || '(the target broker)',
  };
}

/**
 * What a route does, in words. A forward either takes the message or copies it, and a link is started and
 * connected or not: colour alone never carries the difference between traffic being duplicated and taken away.
 */
export function routeState(r: RouteView): string {
  if (r.routeClass === 'FORWARD')
    return attribute(r, 'exclusive') === 'true' ? 'takes the message' : 'copies the message';
  if (r.routeClass === 'LINK') {
    if (attribute(r, 'connected') === 'true') return 'running and connected';
    if (attribute(r, 'started') === 'true') return 'started, not connected to its target';
    return 'not started';
  }
  return '';
}
