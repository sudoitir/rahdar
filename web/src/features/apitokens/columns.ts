import { createElement, type ReactNode } from 'react';

import type { Column } from '../../ui/table/index.ts';
import { absoluteLabel } from '../../kernel/time/time.ts';
import type { TokenView, UsageView } from './api.ts';
import { AdminKeyActions, Expiry, KeyActions, KeyName, TokenStatus, type KeyControls } from './cells.tsx';
import { instantLabel, tokenState, toolsLabel } from './format.ts';

type DayUsage = UsageView['perDay'][number];

const grantsLabel = (t: TokenView) =>
  t.grants.length === 0
    ? 'None'
    : t.grants
        .map((g) => {
          const where = g.scopeType === 'GLOBAL' ? g.action : `${g.action} (cluster)`;
          return g.resourcePattern ? `${where} on ${g.resourceType?.toLowerCase()}s ${g.resourcePattern}` : where;
        })
        .join(', ');

const nameColumn: Column<TokenView> = {
  id: 'name',
  header: 'Name',
  accessor: (t) => `${t.name} ${t.prefix}`,
  cell: (t) => createElement(KeyName, { token: t }),
  kind: 'text',
  wrap: true,
  priority: 'essential',
};

const statusColumn: Column<TokenView> = {
  id: 'status',
  header: 'Status',
  accessor: tokenState,
  cell: (t) => createElement(TokenStatus, { token: t, stale: false }),
  kind: 'status',
  badge: true,
  priority: 'high',
};

const toolsColumn: Column<TokenView> = {
  id: 'tools',
  header: 'MCP tools',
  accessor: toolsLabel,
  kind: 'text',
  wrap: true,
  priority: 'low',
};

const expiresColumn: Column<TokenView> = {
  id: 'expires',
  header: 'Expires',
  accessor: (t) => absoluteLabel(t.expiresAt),
  cell: (t) => createElement(Expiry, { token: t, label: absoluteLabel(t.expiresAt) }),
  kind: 'text',
  wrap: true,
  priority: 'high',
};

const lastUsedColumn: Column<TokenView> = {
  id: 'lastUsed',
  header: 'Last used',
  accessor: (t) => instantLabel(t.lastUsedAt),
  kind: 'time',
  priority: 'low',
};

const actionsColumn = (actions: (t: TokenView) => ReactNode, accessible: string): Column<TokenView> => ({
  id: 'actions',
  header: 'Actions',
  accessor: () => accessible,
  cell: actions,
  kind: 'status',
  wrap: true,
  priority: 'essential',
});

/**
 * The signed-in user's keys. The name identifies a key and the actions are never hidden; the status
 * and the expiry come next, and the last use and the tool restriction go first when the table is narrow.
 */
export function keyColumns({ controls }: Readonly<{ controls: KeyControls }>): Column<TokenView>[] {
  return [
    nameColumn,
    statusColumn,
    expiresColumn,
    lastUsedColumn,
    toolsColumn,
    actionsColumn((t) => createElement(KeyActions, { token: t, controls }), 'Usage Rotate Revoke'),
  ];
}

/**
 * Every user's keys, for an administrator. The owner and the name identify a key; the permissions and
 * the tool restriction, which are the longest, go first when the table is narrow. `stale` is part of
 * the status in words.
 */
export function adminKeyColumns({
  controls,
}: Readonly<{ controls: Pick<KeyControls, 'onUsage' | 'onRevoke'> }>): Column<TokenView>[] {
  return [
    { id: 'owner', header: 'Owner', accessor: (t) => t.owner, kind: 'text', priority: 'essential' },
    nameColumn,
    {
      ...statusColumn,
      accessor: (t) => `${tokenState(t)}${t.stale ? ' stale' : ''}`,
      cell: (t) => createElement(TokenStatus, { token: t, stale: t.stale }),
    },
    {
      id: 'permissions',
      header: 'Permissions',
      accessor: grantsLabel,
      kind: 'text',
      wrap: true,
      priority: 'low',
    },
    toolsColumn,
    { id: 'expires', header: 'Expires', accessor: (t) => absoluteLabel(t.expiresAt), kind: 'time', priority: 'high' },
    lastUsedColumn,
    actionsColumn((t) => createElement(AdminKeyActions, { token: t, controls }), 'Usage Revoke'),
  ];
}

/** The per-day counts of one key's usage. */
export function usageColumns(): Column<DayUsage>[] {
  return [
    { id: 'day', header: 'Day (UTC)', accessor: (d) => d.day, kind: 'time', priority: 'essential' },
    { id: 'requests', header: 'Requests', accessor: (d) => d.requests, kind: 'number', priority: 'essential' },
    { id: 'denied', header: 'Denied', accessor: (d) => d.denied, kind: 'number', priority: 'high' },
    { id: 'limited', header: 'Rate limited', accessor: (d) => d.limited, kind: 'number', priority: 'high' },
    { id: 'errors', header: 'Errors', accessor: (d) => d.errors, kind: 'number', priority: 'high' },
  ];
}
