import type { ReactNode } from 'react';
import { Stack, Text } from '@mantine/core';

import type { Column } from '../../ui/table/index.ts';
import { StatusBadge } from '../../ui/StatusBadge.tsx';
import type { NodeProbeView } from './api.ts';
import classes from './Clusters.module.css';
import { accountResultWords, managementUrlWords } from './connectionWords.ts';

/** One account's result as a badge, in words; a refusal is the one that draws the eye. */
function accountBadge(result: NodeProbeView['management']) {
  const tone = result === 'REJECTED' || result === 'UNREACHABLE' ? 'warning' : 'neutral';
  return <StatusBadge tone={tone}>{accountResultWords(result)}</StatusBadge>;
}

/** Why a passive backup is not asked for its Core account. */
function coreWords(node: NodeProbeView): string {
  if (node.core === 'NOT_TRIED' && node.haRole === 'BACKUP')
    return 'Not tried: a passive backup takes no Core connection';
  return accountResultWords(node.core);
}

const ROLE: Record<string, string> = { PRIMARY: 'Primary', BACKUP: 'Backup', NONE: 'Standalone' };

/** Who the node is, in one line under its name: role, version and NodeID. */
function nodeFacts(n: NodeProbeView): string {
  const role = n.haRole ? (ROLE[n.haRole] ?? n.haRole) : 'Role unknown';
  return [role, n.version ?? 'version unknown', `NodeID ${n.artemisNodeId ?? 'not reported'}`].join(' · ');
}

/** What to do about an account's result, when there is something to do. */
function accountHint(result: NodeProbeView['management'], account: 'management' | 'Core'): string | null {
  if (result === 'REJECTED') return `Check the ${account} account's username and password.`;
  if (result === 'UNREACHABLE') return 'Nothing answered; check the address and the network.';
  return null;
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

/**
 * What a connection check found, one row per node: who it is, where Rahdar manages it, and what each of
 * the two accounts did there. Three columns, each with a line of detail, so the check reads whole in the
 * width of a form instead of hiding the facts it is there to show.
 */
export const NODE_PROBE_COLUMNS: Column<NodeProbeView>[] = [
  {
    id: 'node',
    header: 'Node',
    accessor: (n) => `${n.name} ${nodeFacts(n)}`,
    cell: (n) =>
      twoLines(
        <Text size="sm" fw={500}>
          {n.name}
        </Text>,
        nodeFacts(n),
      ),
    kind: 'text',
    priority: 'essential',
    wrap: true,
  },
  {
    id: 'management',
    header: 'Management account',
    short: 'Management',
    accessor: (n) => `${accountResultWords(n.management)} ${managementUrlWords(n)}`,
    cell: (n) => twoLines(accountBadge(n.management), accountHint(n.management, 'management') ?? managementUrlWords(n)),
    kind: 'text',
    priority: 'essential',
    wrap: true,
  },
  {
    id: 'core',
    header: 'Core account',
    short: 'Core',
    accessor: coreWords,
    cell: (n) => twoLines(accountBadge(n.core), accountHint(n.core, 'Core')),
    kind: 'text',
    priority: 'essential',
    wrap: true,
  },
];
