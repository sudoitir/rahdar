import { Stack, Text } from '@mantine/core';

import type { Column } from '../../ui/table/index.ts';
import type { PartitionBacklog } from './api.ts';

const figure = (n: number) => n.toLocaleString();

/** The partitions of a backlog, slowest first as the server sends them. A lag that could not be read says so, never 0. */
export function partitionColumns(): Column<PartitionBacklog>[] {
  return [
    {
      id: 'partition',
      header: 'Partition',
      accessor: (p) => p.partition,
      kind: 'number',
      priority: 'essential',
    },
    {
      id: 'lag',
      header: 'Lag',
      description: 'Records between the group’s position and the end of the partition',
      accessor: (p) => (p.lag == null ? 'Unavailable' : figure(p.lag)),
      cell: (p) =>
        p.lag == null ? (
          <Stack gap={2} miw={0} align="flex-start">
            <Text size="sm">Unavailable</Text>
            {p.reason ? (
              <Text size="xs" c="dimmed">
                {p.reason}
              </Text>
            ) : null}
          </Stack>
        ) : (
          figure(p.lag)
        ),
      kind: 'number',
      priority: 'essential',
      wrap: true,
    },
    {
      id: 'committed',
      header: 'Committed offset',
      short: 'Committed',
      accessor: (p) => (p.committed == null ? 'None' : figure(p.committed)),
      kind: 'number',
      priority: 'high',
    },
    {
      id: 'end',
      header: 'End offset',
      short: 'End',
      accessor: (p) => figure(p.end),
      kind: 'number',
      priority: 'high',
    },
  ];
}
