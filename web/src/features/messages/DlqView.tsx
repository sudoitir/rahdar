import { useMemo, useState } from 'react';
import { Modal } from '@mantine/core';
import { IconArrowBackUp, IconListDetails } from '@tabler/icons-react';
import { useParams } from '@tanstack/react-router';

import { useActionHost } from '../../kernel/actions/hostContext.ts';
import { ActionMenuItem } from '../../ui/ActionMenuItem.tsx';
import { DescriptionList } from '../../ui/DescriptionList.tsx';
import { EmptyState } from '../../ui/EmptyState.tsx';
import { ErrorState } from '../../ui/ErrorState.tsx';
import { Page } from '../../ui/Page.tsx';
import { PageHeader } from '../../ui/PageHeader.tsx';
import { DataTable } from '../../ui/table/index.ts';
import { useDlq, type DeadLetterSubscription } from './api.ts';
import { BulkActionPreview } from './BulkActionPreview.tsx';
import { dlqColumns, type DlqRow } from './columns.ts';
import { useActionGate } from './gates.ts';
import { messageCount } from './outcomes.ts';

const rowKey = (r: DlqRow) => `${r.address}/${r.queue.name}`;

/** "Replay all…" on a queue's row: every message back to the queue it came from, through the shared preview. */
function ReplayItem({ clusterId, onReplay }: Readonly<{ clusterId: string; onReplay: () => void }>) {
  const host = useActionHost();
  const gate = useActionGate(clusterId, 'retry');
  return (
    <ActionMenuItem
      label="Replay all…"
      icon={<IconArrowBackUp size="1rem" aria-hidden />}
      verdict={gate}
      onExplain={(verdict) => host.explain(verdict, 'replaying this queue')}
      onSelect={onReplay}
    />
  );
}

/** Why the grid has no rows: the broker's settings could not be read, or nothing is dead-lettered. */
function DlqEmpty({ addresses, available }: Readonly<{ addresses: string[]; available: boolean }>) {
  if (!available) {
    return (
      <EmptyState
        kind="empty"
        title="Dead-letter configuration unavailable"
        description={
          <>
            Rahdar could not read this broker's address settings, so it will not guess which queues are dead-letter
            queues from their names. Grant the connection management-read access, or check{' '}
            <code>getAddressSettingsAsJSON</code> is permitted, then reload.
          </>
        }
      />
    );
  }
  return (
    <EmptyState
      kind="empty"
      title="No dead-lettered messages"
      description={
        <>
          The broker's dead-letter address is <code>{addresses.join(', ') || '—'}</code>, but no queue on it currently
          holds messages. A message lands there when a consumer keeps rejecting it or it expires.
        </>
      }
    />
  );
}

/**
 * Dead-letter / expiry management (ADR-0021, D8). Addresses come from the broker's own settings —
 * when that read fails the view says exactly that and infers nothing. "Replay all" runs a by-selector
 * RETRY through the shared preview + cap gate. The grid is virtualised, so a cluster with hundreds
 * of dead-lettered queues needs no paging.
 */
export function DlqView() {
  const { clusterId } = useParams({ strict: false }) as { clusterId: string };
  const dlq = useDlq(clusterId);
  const [replay, setReplay] = useState<DeadLetterSubscription | null>(null);
  const [breakdown, setBreakdown] = useState<DeadLetterSubscription | null>(null);
  const columns = useMemo(() => dlqColumns(clusterId), [clusterId]);

  const addresses = dlq.data?.destinations;
  // Flattened so each row is a queue rather than an address: one address can hold hundreds.
  const rows = useMemo<DlqRow[]>(
    () =>
      (addresses ?? []).flatMap((a) => a.subscriptions.map((queue) => ({ address: a.name, kind: a.purpose, queue }))),
    [addresses],
  );

  return (
    <Page fill>
      <PageHeader
        title="Dead-letter queues"
        description="Queues on the broker's dead-letter and expiry addresses that hold messages, with how many on each node."
      />

      <DataTable
        label="Dead-letter queues"
        storageKey="dlq"
        height="fill"
        columns={columns}
        data={rows}
        rowKey={rowKey}
        loading={dlq.isPending}
        error={dlq.isError ? <ErrorState error={dlq.error} onRetry={() => void dlq.refetch()} /> : undefined}
        onRowClick={(r) => setBreakdown(r.queue)}
        rowMenu={{
          label: (r) => `dead-letter queue ${r.queue.name}`,
          render: (r) => (
            <>
              <ActionMenuItem
                label="Per-node breakdown"
                icon={<IconListDetails size="1rem" aria-hidden />}
                onSelect={() => setBreakdown(r.queue)}
              />
              <ReplayItem clusterId={clusterId} onReplay={() => setReplay(r.queue)} />
            </>
          ),
        }}
        empty={
          <DlqEmpty addresses={(addresses ?? []).map((a) => a.name)} available={dlq.data?.settingsAvailable ?? true} />
        }
      />

      <Modal
        opened={breakdown !== null}
        onClose={() => setBreakdown(null)}
        title={breakdown ? `${breakdown.name} by node` : ''}
      >
        {breakdown ? (
          <DescriptionList
            label="Messages on each node"
            items={breakdown.perNode.map((n) => ({ term: n.nodeName, value: messageCount(n.depth) }))}
          />
        ) : null}
      </Modal>

      {replay ? (
        <BulkActionPreview
          clusterId={clusterId}
          queueName={replay.name}
          action="retry"
          opened={replay !== null}
          onClose={() => setReplay(null)}
          onDone={() => setReplay(null)}
        />
      ) : null}
    </Page>
  );
}
