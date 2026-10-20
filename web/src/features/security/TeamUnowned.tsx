import { useState } from 'react';
import { Pagination, SegmentedControl, Select, Stack, Text } from '@mantine/core';

import type { PagedView } from '../../kernel/api/paging.ts';
import { EmptyState } from '../../ui/EmptyState.tsx';
import { ErrorState } from '../../ui/ErrorState.tsx';
import { FieldRow } from '../../ui/FieldRow.tsx';
import { Notice } from '../../ui/Notice.tsx';
import { Section } from '../../ui/Section.tsx';
import { DataTable } from '../../ui/table/index.ts';
import { useClusters } from '../clusters/index.ts';
import { UNOWNED_PAGE_SIZE, useUnowned, type PatternKind, type UnownedView } from './api.ts';
import { useTeamAccess } from './teamAccess.ts';
import { unownedColumns } from './teamColumns.tsx';

type Kind = 'SUBSCRIPTION' | 'DESTINATION';

const rowKey = (u: UnownedView) => u.name;

/** How many pages the list has: from the count when the server gave one, else one more while there is a next. */
function pageCount(data: PagedView<UnownedView> | undefined, page: number): number {
  if (data?.count) return Math.ceil(data.count / UNOWNED_PAGE_SIZE);
  return data?.hasNext ? page + 1 : 1;
}

/**
 * The queues or addresses of a cluster that no team's pattern covers, reachable only through role grants. Each can
 * be assigned to this team as a queue, address or both pattern, which pre-fills the pattern form with its exact name.
 */
export function TeamUnowned({
  onAssign,
}: Readonly<{ onAssign: (clusterId: string, kind: PatternKind, name: string) => void }>) {
  const clusters = useClusters();
  const { userAdmin } = useTeamAccess();
  const [clusterId, setClusterId] = useState<string | null>(null);
  const [kind, setKind] = useState<Kind>('SUBSCRIPTION');
  const [page, setPage] = useState(1);
  const unowned = useUnowned(clusterId, kind, page, userAdmin);
  const cluster = clusters.data?.find((c) => c.id === clusterId)?.name;
  const pages = pageCount(unowned.data, page);
  const [one, many] = kind === 'SUBSCRIPTION' ? ['subscription', 'subscriptions'] : ['destination', 'destinations'];
  const count = unowned.data?.count;
  const error = unowned.isError ? (
    <ErrorState error={unowned.error} onRetry={() => void unowned.refetch()} />
  ) : undefined;
  const toolbar =
    count == null
      ? undefined
      : {
          end: (
            <Text size="sm" c="dimmed">
              {count} unowned
            </Text>
          ),
        };

  const columns = unownedColumns({
    editable: userAdmin,
    onAssign: (name, patternKind) => clusterId && onAssign(clusterId, patternKind, name),
  });

  return (
    <Section
      title="Unowned resources"
      headingLevel={3}
      description="Queues and addresses that no team's pattern covers. Only role grants reach them, so a team member sees none of them."
    >
      {userAdmin ? null : (
        <Notice title="Needs user:admin">
          Listing what no team owns, and assigning it, needs the user:admin permission. Ask a user administrator.
        </Notice>
      )}
      <Stack gap="sm">
        <FieldRow>
          <Select
            label="Cluster"
            data={(clusters.data ?? []).map((c) => ({ value: c.id, label: c.name }))}
            value={clusterId}
            onChange={(next) => {
              setClusterId(next);
              setPage(1);
            }}
            searchable
            placeholder={clusters.isPending ? 'Loading' : 'Select a cluster'}
            nothingFoundMessage="No clusters"
            disabled={!userAdmin}
          />
        </FieldRow>
        <SegmentedControl
          aria-label="Unowned kind"
          data={[
            { value: 'SUBSCRIPTION', label: 'Subscriptions' },
            { value: 'DESTINATION', label: 'Destinations' },
          ]}
          value={kind}
          onChange={(next) => {
            setKind(next as Kind);
            setPage(1);
          }}
          disabled={!userAdmin}
        />
        {clusterId === null ? (
          <Text size="sm" c="dimmed">
            Choose a cluster to list the {many} no team owns.
          </Text>
        ) : (
          <>
            <DataTable
              variant="static"
              label={`Unowned ${many} on ${cluster ?? 'the cluster'}`}
              storageKey="security.team-unowned"
              columns={columns}
              data={unowned.data?.data ?? []}
              rowKey={rowKey}
              loading={unowned.isPending && userAdmin}
              error={error}
              toolbar={toolbar}
              empty={
                <EmptyState
                  kind="empty"
                  title={`Every ${one} on ${cluster ?? 'this cluster'} is owned`}
                  description="A team's pattern covers every name of this kind on the cluster, so nothing needs assigning."
                />
              }
            />
            {pages > 1 ? <Pagination total={pages} value={page} onChange={setPage} aria-label="Unowned pages" /> : null}
          </>
        )}
      </Stack>
    </Section>
  );
}
