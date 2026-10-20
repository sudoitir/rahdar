import { useMemo } from 'react';
import { Link, useParams } from '@tanstack/react-router';

import { useDisplayZone } from '../../kernel/time/timezone.ts';
import { EmptyState } from '../../ui/EmptyState.tsx';
import { ErrorState } from '../../ui/ErrorState.tsx';
import linkClasses from '../../ui/InlineLink.module.css';
import { Page } from '../../ui/Page.tsx';
import { PageHeader } from '../../ui/PageHeader.tsx';
import { DataTable } from '../../ui/table/index.ts';
import { useBulkRuns, type BulkRunView } from './api.ts';
import { runColumns } from './columns.ts';

const rowKey = (r: BulkRunView) => r.id;

/** Past and running bulk runs on a cluster, newest first. */
export function BulkRunsView() {
  const zone = useDisplayZone();
  const { clusterId } = useParams({ strict: false }) as { clusterId: string };
  const query = useBulkRuns(clusterId);
  const columns = useMemo(() => runColumns({ clusterId, zone }), [clusterId, zone]);

  return (
    <Page fill>
      <PageHeader
        title="Bulk runs"
        description="Pauses, resumes, purges and deletes applied to many subscriptions at once, newest first, each with its outcome."
      />
      <DataTable
        label="Bulk runs"
        storageKey="bulk.runs"
        height="fill"
        columns={columns}
        data={query.data ?? []}
        rowKey={rowKey}
        loading={query.isPending}
        error={query.isError ? <ErrorState error={query.error} onRetry={() => void query.refetch()} /> : undefined}
        empty={
          <EmptyState
            kind="empty"
            title="No bulk runs yet"
            description="A bulk run pauses, resumes, purges or deletes many subscriptions at once, one subscription at a time, after a preview you confirm. Nothing has been run on this cluster yet. Select subscriptions on the Subscriptions screen to start one, and it is listed here with its outcome."
            action={
              <Link to={`/clusters/${clusterId}/subscriptions`} className={linkClasses.link}>
                Go to Subscriptions
              </Link>
            }
          />
        }
      />
    </Page>
  );
}
