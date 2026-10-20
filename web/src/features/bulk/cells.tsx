import { UnstyledButton } from '@mantine/core';
import { Link } from '@tanstack/react-router';

import linkClasses from '../../ui/InlineLink.module.css';
import { resourceName } from '../../kernel/api/resourceId.ts';
import { absoluteLabel } from '../../kernel/time/time.ts';
import { StatusBadge } from '../../ui/StatusBadge.tsx';
import { MiddleTruncate } from '../../ui/table/MiddleTruncate.tsx';
import type { BulkItemView, BulkRunView } from './api.ts';
import { itemStatus, runStatus } from './words.ts';

/** The run's time, as a real link, so each run is reachable from the keyboard. */
export function RunLink({ run, clusterId }: Readonly<{ run: BulkRunView; clusterId: string }>) {
  return (
    <Link to={`/clusters/${clusterId}/bulk/${run.id}`} className={linkClasses.link}>
      {absoluteLabel(run.startedAt ?? run.createdAt)}
    </Link>
  );
}

/** The run's status in words; colour only emphasises a run that went wrong or needs the operator. */
export function RunOutcome({ run }: Readonly<{ run: BulkRunView }>) {
  const { text, tone } = runStatus(run.status);
  return <StatusBadge tone={tone}>{text}</StatusBadge>;
}

/** A queue as a real button, so its node detail is reachable from the keyboard. */
export function QueueButton({
  item,
  onOpen,
}: Readonly<{ item: BulkItemView; onOpen: (subscription: string) => void }>) {
  return (
    <UnstyledButton
      className={linkClasses.link}
      maw="100%"
      aria-label={`Show node detail for ${resourceName(item.subscription)}`}
      onClick={() => onOpen(item.subscription)}
    >
      <MiddleTruncate text={resourceName(item.subscription)} />
    </UnstyledButton>
  );
}

/** The queue's outcome in words; colour only emphasises one that was refused, failed or is unknown. */
export function ItemOutcome({ item }: Readonly<{ item: BulkItemView }>) {
  const { text, tone } = itemStatus(item.status);
  return <StatusBadge tone={tone}>{text}</StatusBadge>;
}
