import { Link } from '@tanstack/react-router';

import { type Column } from '../../ui/table/index.ts';
import linkClasses from '../../ui/InlineLink.module.css';
import type { SchemaSubjectRow } from './schemasApi.ts';

/**
 * Subject, versions and compatibility. The list row has no schema type, so there is no latest-type
 * column; the subject page shows the type. A type on the row is what would put the column here.
 */
export function schemaColumns(clusterId: string): Column<SchemaSubjectRow>[] {
  return [
    {
      id: 'subject',
      header: 'Subject',
      accessor: (row) => row.name ?? '',
      cell: (row) => (
        <Link
          to={`/clusters/${clusterId}/kafka/schemas/${encodeURIComponent(row.name ?? '')}`}
          className={linkClasses.link}
        >
          {row.name}
        </Link>
      ),
      kind: 'identifier',
      priority: 'essential',
    },
    {
      id: 'versions',
      header: 'Versions',
      accessor: (row) => row.versions,
      kind: 'number',
      priority: 'high',
    },
    {
      id: 'compatibility',
      header: 'Compatibility',
      accessor: (row) => row.compatibility ?? row.compatibilityNote ?? 'Registry default',
      kind: 'status',
      priority: 'high',
    },
  ];
}
