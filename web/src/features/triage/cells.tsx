import { nameOf, refOf } from '../../kernel/api/resourceId.ts';
import { ResourceLink } from '../../kernel/actions/ResourceLink.tsx';
import { MiddleTruncate } from '../../ui/table/index.ts';
import type { ConsumerHealthView } from './api.ts';

/** The subscription's name, shortened in the middle, as a link to it (ADR-0107) where a feature offers one. */
export function SubscriptionLink({ row }: Readonly<{ row: ConsumerHealthView }>) {
  return (
    <ResourceLink kind="subscription" target={{ ...refOf(row.subscription), destination: row.destination }}>
      <MiddleTruncate text={nameOf(row.subscription)} />
    </ResourceLink>
  );
}
