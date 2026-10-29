import type { ReactNode } from 'react';
import { Stack, Text } from '@mantine/core';

import { StatusBadge } from '../../ui/StatusBadge.tsx';
import type { Column } from '../../ui/table/index.ts';
import type { CheckNode } from './api.ts';
import classes from './Clusters.module.css';
import { accountResultWords, urlProblemWords, urlSourceWords, type AccountResult } from './connectionWords.ts';

/** The cause of a failure, in words. */
export const CAUSE_WORDS: Record<string, string> = {
  DNS: 'The name does not resolve',
  REFUSED: 'The connection was refused',
  TIMEOUT: 'The connection timed out',
  TLS: 'The certificate is not trusted, or does not match the host name',
  AUTHENTICATION: 'Authentication failed',
  AUTHORIZATION: 'The account may not describe the cluster',
  UNSUPPORTED_VERSION: 'This version is not supported',
  ADVERTISED_UNREACHABLE: 'The brokers advertise addresses Rahdar cannot reach',
};

const ROLE: Record<string, string> = { PRIMARY: 'Primary', BACKUP: 'Backup', NONE: 'Standalone' };

/** A node's name: an Artemis node by its own name, a Kafka broker by its id. */
export const nodeName = (n: CheckNode): string => (n.artemis ? n.id : `Broker ${n.id}`);

/** Who the node is, in one line under its name. */
function nodeFacts(n: CheckNode): string {
  if (!n.artemis) return n.functions.map((f) => f.toLowerCase()).join(', ');
  const role = ROLE[n.artemis.haRole] ?? n.artemis.haRole;
  return [role, n.version?.text ?? 'version unknown', `NodeID ${n.artemis.nativeNodeId ?? 'not reported'}`].join(' · ');
}

/** A cell with its value and, under it, a dimmed line that explains it. */
function twoLines(first: ReactNode, second: string | null) {
  return (
    <Stack gap={2} miw={0} align="flex-start">
      {first}
      {second ? (
        <Text size="xs" c="dimmed" className={classes.probeDetail}>
          {second}
        </Text>
      ) : null}
    </Stack>
  );
}

/** Where Rahdar reaches the node, and for an Artemis node where the address came from or why there is none. */
function addressNote(n: CheckNode): string | null {
  if (!n.artemis) return null;
  if (n.address) return urlSourceWords(n.artemis.urlSource);
  return `No management URL: ${urlProblemWords(n.artemis.urlProblem)}`;
}

const accountBadge = (result: AccountResult) => (
  <StatusBadge tone={result === 'REJECTED' || result === 'UNREACHABLE' ? 'warning' : 'neutral'}>
    {accountResultWords(result)}
  </StatusBadge>
);

/** What to do about an account's result, when there is something to do. */
function accountHint(result: AccountResult, account: 'management' | 'Core'): string | null {
  if (result === 'REJECTED') return `Check the ${account} account's username and password.`;
  if (result === 'UNREACHABLE') return 'Nothing answered; check the address and the network.';
  return null;
}

function reach(n: CheckNode): string {
  if (n.artemis) return `Management ${n.artemis.management} Core ${n.artemis.core}`;
  return n.outcome === 'OK' ? 'Reached' : `Not reached ${n.cause ?? ''} ${n.message ?? ''}`;
}

/**
 * What a connection check found, one row per node, for registering and for editing a connection, whichever
 * system the cluster runs. A Kafka broker is reached or not, with the cause; an Artemis node says what each
 * of its two accounts did there.
 */
export const CHECK_NODE_COLUMNS: Column<CheckNode>[] = [
  {
    id: 'node',
    header: 'Node',
    accessor: (n) => `${nodeName(n)} ${nodeFacts(n)}`,
    cell: (n) =>
      twoLines(
        <Text size="sm" fw={500}>
          {nodeName(n)}
        </Text>,
        nodeFacts(n),
      ),
    kind: 'text',
    priority: 'essential',
    wrap: true,
  },
  {
    id: 'address',
    header: 'Address',
    description: 'Where Rahdar reaches the node: its advertised address, or for Artemis its management URL',
    accessor: (n) => `${n.address ?? 'Not learned'} ${addressNote(n) ?? ''}`,
    cell: (n) =>
      twoLines(
        <Text size="sm" className={classes.mono}>
          {n.address ?? 'Not learned'}
        </Text>,
        addressNote(n),
      ),
    kind: 'code',
    priority: 'essential',
    wrap: true,
  },
  {
    id: 'reach',
    header: 'Reach',
    accessor: reach,
    cell: (n) => {
      if (n.artemis) {
        return (
          <Stack gap={4} miw={0} align="flex-start">
            {twoLines(
              <Text size="xs" component="span">
                Management account {accountBadge(n.artemis.management as AccountResult)}
              </Text>,
              accountHint(n.artemis.management as AccountResult, 'management'),
            )}
            {twoLines(
              <Text size="xs" component="span">
                Core account {accountBadge(n.artemis.core as AccountResult)}
              </Text>,
              n.artemis.core === 'NOT_TRIED' && n.artemis.haRole === 'BACKUP'
                ? 'Not tried: a passive backup takes no Core connection'
                : accountHint(n.artemis.core as AccountResult, 'Core'),
            )}
          </Stack>
        );
      }
      return twoLines(
        <StatusBadge tone={n.outcome === 'OK' ? 'neutral' : 'warning'}>
          {n.outcome === 'OK' ? 'Reached' : 'Not reached'}
        </StatusBadge>,
        n.outcome === 'UNREACHABLE'
          ? [n.cause ? CAUSE_WORDS[n.cause] : null, n.message].filter(Boolean).join(': ')
          : null,
      );
    },
    kind: 'text',
    priority: 'essential',
    wrap: true,
  },
];
