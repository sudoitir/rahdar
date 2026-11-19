import { useMemo } from 'react';
import { Button } from '@mantine/core';
import { Link, useParams } from '@tanstack/react-router';

import { useCan } from '../../kernel/auth/useCan.ts';
import { ApiError } from '../../kernel/api/request.ts';
import { EmptyState } from '../../ui/EmptyState.tsx';
import { ErrorState } from '../../ui/ErrorState.tsx';
import linkClasses from '../../ui/InlineLink.module.css';
import { LoadingState } from '../../ui/LoadingState.tsx';
import { Notice } from '../../ui/Notice.tsx';
import { Page } from '../../ui/Page.tsx';
import { PageHeader } from '../../ui/PageHeader.tsx';
import { DataTable } from '../../ui/table/index.ts';
import { logDirMisses, partitionColumns } from './partitionColumns.tsx';
import { usePartitions } from './placement.ts';

/**
 * One topic's partitions. Replicas stay in the cluster's order, bytes are the bytes on that broker,
 * and a log-directory miss is named once above the table.
 */
export function PartitionsView() {
  const { clusterId, topic } = useParams({ strict: false }) as { clusterId: string; topic: string };
  const { reach, loading: grantsLoading } = useCan();
  const read = reach('destination:read', clusterId);
  const partitions = usePartitions(clusterId, topic, read !== 'none');
  const rows = partitions.data?.pages.flatMap((page) => page.items) ?? [];
  const showEligible = rows.some((row) => row.eligibleLeaderReplicas != null);
  const columns = useMemo(
    () => partitionColumns({ clusterId, showTopic: false, showEligible }),
    [clusterId, showEligible],
  );

  if (!grantsLoading && read === 'none') {
    return (
      <Page>
        <PageHeader title={`Partitions of ${topic}`} />
        <ErrorState error={new ApiError(403, { title: 'Forbidden', permission: 'destination:read' })} />
      </Page>
    );
  }
  if (partitions.isError) {
    return (
      <Page>
        <PageHeader title={`Partitions of ${topic}`} />
        <ErrorState error={partitions.error} onRetry={() => void partitions.refetch()} />
      </Page>
    );
  }
  if (!partitions.data) {
    return (
      <Page>
        <PageHeader title={`Partitions of ${topic}`} />
        <LoadingState label={`Loading partitions of ${topic}`} blockSize="12rem" />
      </Page>
    );
  }
  const misses = logDirMisses(rows);
  return (
    <Page fill>
      <PageHeader
        title={`Partitions of ${topic}`}
        meta={
          <Link to={`/clusters/${clusterId}/destinations`} className={linkClasses.link}>
            All destinations
          </Link>
        }
        description="Where each partition's replicas sit, in the cluster's order, and how far each one is behind."
      />
      {misses.map((miss) => (
        <Notice key={miss} tone="warning" title={miss} />
      ))}
      <DataTable
        label={`Partitions of ${topic}`}
        storageKey="kafka.partitions"
        height="fill"
        columns={columns}
        data={rows}
        rowKey={(row) => String(row.partition)}
        loading={partitions.isFetching && !partitions.isFetchingNextPage}
        toolbar={{
          end: partitions.hasNextPage ? (
            <Button
              size="xs"
              variant="default"
              loading={partitions.isFetchingNextPage}
              onClick={() => void partitions.fetchNextPage()}
            >
              Load more partitions
            </Button>
          ) : undefined,
        }}
        empty={
          <EmptyState
            kind="empty"
            title="No partitions"
            description="The cluster reported no partition for this topic."
          />
        }
      />
    </Page>
  );
}
