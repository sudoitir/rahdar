import { nameOf } from '../../kernel/api/resourceId.ts';
import { ResourceLink } from '../../kernel/actions/ResourceLink.tsx';
import { MiddleTruncate } from '../../ui/table/index.ts';
import type { ConsumerHealthView } from './api.ts';

/** The queue's name, shortened in the middle, as a link to it (ADR-0107) where a feature offers one. */
export function QueueLink({ row }: Readonly<{ row: ConsumerHealthView }>) {
  return (
    <ResourceLink kind="queue" target={{ queueName: nameOf(row.subscription), address: nameOf(row.destination) }}>
      <MiddleTruncate text={nameOf(row.subscription)} />
    </ResourceLink>
  );
}
