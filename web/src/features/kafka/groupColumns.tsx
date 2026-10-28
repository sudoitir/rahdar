import { Text } from '@mantine/core';
import { Link } from '@tanstack/react-router';

import linkClasses from '../../ui/InlineLink.module.css';
import { BacklogCell } from '../../ui/BacklogCell.tsx';
import { backlogWords } from '../../ui/backlog.ts';
import { StatusBadge } from '../../ui/StatusBadge.tsx';
import type { Column } from '../../ui/table/index.ts';
import type { GroupSummary } from './api.ts';
import { stateWords, typeWords } from './words.ts';

/** The groups list: the name identifies a row and links to the group; the lag says how sure it is. */
export function groupColumns(clusterId: string): Column<GroupSummary>[] {
  return [
    {
      id: 'name',
      header: 'Group',
      accessor: (g) => g.id,
      cell: (g) => (
        <Link to={`/clusters/${clusterId}/kafka/groups/${encodeURIComponent(g.id)}`} className={linkClasses.link}>
          {g.id}
        </Link>
      ),
      kind: 'identifier',
      priority: 'essential',
    },
    { id: 'type', header: 'Type', accessor: (g) => typeWords(g.type), kind: 'status', priority: 'high' },
    {
      id: 'state',
      header: 'State',
      accessor: (g) => stateWords(g.state).text,
      cell: (g) => <StatusBadge tone={stateWords(g.state).tone}>{stateWords(g.state).text}</StatusBadge>,
      kind: 'status',
      badge: true,
      priority: 'essential',
    },
    { id: 'members', header: 'Members', accessor: (g) => g.members, kind: 'number', priority: 'high' },
    {
      id: 'lag',
      header: 'Lag',
      description: 'Records the group has not read yet. ≈ marks an estimate; Unavailable says why it could not be read',
      accessor: (g) => backlogWords(g.lag),
      cell: (g) => <BacklogCell backlog={g.lag} subject={g.id} />,
      kind: 'number',
      priority: 'high',
    },
    {
      id: 'readOnly',
      header: 'Changes',
      accessor: (g) => g.readOnly ?? '',
      cell: (g) =>
        g.readOnly ? (
          <Text size="xs" c="dimmed">
            {g.readOnly}
          </Text>
        ) : (
          'Allowed'
        ),
      kind: 'text',
      priority: 'low',
      wrap: false,
    },
  ];
}
