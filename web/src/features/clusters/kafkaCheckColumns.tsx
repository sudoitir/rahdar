import { Stack, Text } from '@mantine/core';

import { StatusBadge } from '../../ui/StatusBadge.tsx';
import type { Column } from '../../ui/table/index.ts';
import type { CauseKind, KafkaNodeCheck } from './api.ts';
import classes from './Clusters.module.css';

/** The cause of a failure, in words. */
export const CAUSE_WORDS: Record<CauseKind | 'AUTHORIZATION' | 'UNSUPPORTED_VERSION', string> = {
  DNS: 'The name does not resolve',
  REFUSED: 'The connection was refused',
  TIMEOUT: 'The connection timed out',
  TLS: 'The certificate is not trusted, or does not match the host name',
  AUTHENTICATION: 'Authentication failed',
  AUTHORIZATION: 'The account may not describe the cluster',
  UNSUPPORTED_VERSION: 'This Kafka version is not supported',
};

/** One broker of a check: which it is, where it advertises itself, and whether Rahdar reached it there. */
export const KAFKA_NODE_COLUMNS: Column<KafkaNodeCheck>[] = [
  {
    id: 'node',
    header: 'Broker',
    accessor: (n) => `${n.id} ${n.functions.join(' ')}`,
    cell: (n) => (
      <Stack gap={2} miw={0} align="flex-start">
        <Text size="sm" fw={500}>{`Broker ${n.id}`}</Text>
        {n.functions.length > 0 ? (
          <Text size="xs" c="dimmed">
            {n.functions.map((f) => f.toLowerCase()).join(', ')}
          </Text>
        ) : null}
      </Stack>
    ),
    kind: 'text',
    priority: 'essential',
    wrap: true,
  },
  {
    id: 'address',
    header: 'Advertised address',
    short: 'Address',
    accessor: (n) => n.address ?? 'Not learned',
    cell: (n) => (
      <Text size="sm" className={classes.mono}>
        {n.address ?? 'Not learned'}
      </Text>
    ),
    kind: 'code',
    priority: 'essential',
    wrap: true,
  },
  {
    id: 'reach',
    header: 'Reach',
    accessor: (n) => (n.outcome === 'OK' ? 'Reached' : `Not reached ${n.cause ?? ''} ${n.message ?? ''}`),
    cell: (n) => (
      <Stack gap={2} miw={0} align="flex-start">
        <StatusBadge tone={n.outcome === 'OK' ? 'neutral' : 'warning'}>
          {n.outcome === 'OK' ? 'Reached' : 'Not reached'}
        </StatusBadge>
        {n.outcome === 'UNREACHABLE' ? (
          <Text size="xs" c="dimmed">
            {[n.cause ? CAUSE_WORDS[n.cause] : null, n.message].filter(Boolean).join(': ')}
          </Text>
        ) : null}
      </Stack>
    ),
    kind: 'text',
    priority: 'essential',
    wrap: true,
  },
];
