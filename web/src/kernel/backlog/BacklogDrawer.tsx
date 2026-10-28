import { useMemo } from 'react';
import { Drawer, Stack, Text } from '@mantine/core';

import { EmptyState } from '../../ui/EmptyState.tsx';
import { ErrorState } from '../../ui/ErrorState.tsx';
import { LoadingState } from '../../ui/LoadingState.tsx';
import { DataTable } from '../../ui/table/index.ts';
import { Ago } from '../time/Ago.tsx';
import { useServerNow } from '../time/time.ts';
import { useBacklogBreakdown } from './api.ts';
import { partitionColumns } from './partitionColumns.tsx';

/** What names a subscription whose backlog is open: its cluster, its canonical id and the name to show. */
export interface BacklogTarget {
  clusterId: string;
  id: string;
  name: string;
}

/**
 * The backlog of one subscription partition by partition, slowest first, with how old the sample is. The
 * total on the row is an estimate made from these; this is what it is made of.
 */
export function BacklogDrawer({ target, onClose }: Readonly<{ target: BacklogTarget | null; onClose: () => void }>) {
  const breakdown = useBacklogBreakdown(target?.clusterId ?? '', target?.id ?? null);
  const now = useServerNow(5_000);
  const columns = useMemo(partitionColumns, []);

  let body;
  if (breakdown.isError) {
    body = <ErrorState error={breakdown.error} onRetry={() => void breakdown.refetch()} />;
  } else if (!breakdown.data) {
    body = <LoadingState label="Loading the partitions" blockSize="10rem" />;
  } else {
    body = (
      <Stack gap="sm">
        <Text size="sm" c="dimmed">
          Sampled <Ago at={breakdown.data.sampledAt} now={now} />. The slowest partitions come first.
        </Text>
        <DataTable
          variant="static"
          label={`${target?.name ?? 'Subscription'} backlog by partition`}
          storageKey="backlog.partitions"
          columns={columns}
          data={breakdown.data.partitions}
          rowKey={(p) => String(p.partition)}
          height={{ maxRows: 12 }}
          empty={
            <EmptyState
              kind="empty"
              title="No partitions"
              description="The system reported no partition for this subscription."
            />
          }
        />
      </Stack>
    );
  }

  return (
    <Drawer
      opened={target !== null}
      onClose={onClose}
      position="right"
      size="lg"
      title={target ? `Backlog of ${target.name}` : ''}
    >
      {body}
    </Drawer>
  );
}
