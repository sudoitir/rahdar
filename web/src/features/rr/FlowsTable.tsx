import { useMemo, type ReactNode } from 'react';

import type { FlowView } from './api.ts';
import { useServerNow } from '../../kernel/time/time.ts';
import { DataTable, type TableToolbar } from '../../ui/table/index.ts';
import { flowColumns } from './columns.ts';

const rowKey = (f: FlowView) => f.id;

/** One flow's state, destination, correlation id, age, and latency (or none yet); a row opens the flow. */
export function FlowsTable({
  label,
  storageKey,
  flows,
  loading,
  error,
  empty,
  toolbar,
  onSelect,
}: Readonly<{
  /** Names the table: "Flows", "Stuck flows". */
  label: string;
  storageKey: string;
  flows: FlowView[];
  loading: boolean;
  /** An `ErrorState`, shown in place of the rows. */
  error?: ReactNode;
  /** An `EmptyState`: why there are no flows here. */
  empty: ReactNode;
  toolbar?: TableToolbar;
  onSelect: (flowId: string) => void;
}>) {
  // Ticking, and on Rahdar's clock rather than the workstation's — an age is a
  // server timestamp subtracted from now, so the two must be the same clock.
  const now = useServerNow(5_000);
  const columns = useMemo(() => flowColumns(now), [now]);

  return (
    <DataTable
      label={label}
      storageKey={storageKey}
      height={{ maxRows: 20 }}
      columns={columns}
      data={flows}
      rowKey={rowKey}
      loading={loading}
      error={error}
      empty={empty}
      toolbar={toolbar}
      onRowClick={(f) => onSelect(f.id)}
    />
  );
}
