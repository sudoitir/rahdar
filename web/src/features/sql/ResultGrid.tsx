import { useMemo } from 'react';

import { useDisplayZone } from '../../kernel/time/timezone.ts';
import { DataTable } from '../../ui/table/index.ts';
import type { SqlRowView } from './api.ts';
import { resultColumns } from './columns.ts';
import { rowKey } from './useSqlTail.ts';
import classes from './ResultGrid.module.css';

/**
 * The result set, in the console's data table: the same sizing, sorting and node attribution as every
 * other tabular view.
 *
 * <p>Which columns show, and in what order, is the table's own Columns menu, remembered per browser.
 * The message identifies a row, so it leads; where the row came from follows the subscription and the node, because
 * a live row and an indexed row mean different things and which one an operator is looking at has to be
 * answerable without opening it.
 */
export function ResultGrid({
  clusterId,
  rows,
  onOpen,
  emptyLabel,
  freshKeys,
  onAtTopChange,
}: Readonly<{
  clusterId: string;
  rows: SqlRowView[];
  onOpen: (row: SqlRowView) => void;
  /** Says why there are no rows, and what to do next: the grid shows it in their place. */
  emptyLabel: React.ReactNode;
  /** Keys the live tail delivered in the last few seconds. */
  freshKeys?: ReadonlySet<string>;
  onAtTopChange?: (atTop: boolean) => void;
}>) {
  // The Enqueued column is an absolute timestamp, so this view follows the display zone.
  const zone = useDisplayZone();
  const columns = useMemo(() => resultColumns(clusterId, zone), [clusterId, zone]);
  return (
    <DataTable
      label="Query results"
      storageKey="sql.results"
      height="fill"
      columns={columns}
      data={rows}
      rowKey={rowKey}
      onRowClick={onOpen}
      empty={emptyLabel}
      rowClassName={(r) => (freshKeys?.has(rowKey(r)) ? classes.fresh : undefined)}
      onAtTopChange={onAtTopChange}
    />
  );
}
