import { StatusBadge } from '../../ui/StatusBadge.tsx';

/** A node's figures are older than the last scrape that should have refreshed them. */
export function StaleBadge() {
  return <StatusBadge tone="warning">stale</StatusBadge>;
}
