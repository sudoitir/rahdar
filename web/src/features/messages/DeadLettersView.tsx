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
import { useDeadLetters, type DeadLetterSubscription } from './api.ts';
import { BulkActionPreview } from './BulkActionPreview.tsx';
import { deadLetterColumns, type DeadLetterRow } from './columns.ts';
import { useActionGate } from './gates.ts';
import { messageCount } from './outcomes.ts';

const rowKey = (r: DeadLetterRow) => `${r.destination}/${r.subscription.name}`;

/** "Replay all…" on a subscription's row: every message back to the subscription it came from, through the shared preview. */
function ReplayItem({
  clusterId,
  kindId,
  onReplay,
}: Readonly<{ clusterId: string; kindId: string; onReplay: () => void }>) {
  const host = useActionHost();
  const gate = useActionGate(clusterId, 'retry', kindId);
  return (
    <ActionMenuItem
      label="Replay all…"
      icon={<IconArrowBackUp size="1rem" aria-hidden />}
      verdict={gate}
      onExplain={(verdict) => host.explain(verdict, 'replaying this subscription')}
      onSelect={onReplay}
    />
  );
}

/** Why the grid has no rows: the broker's settings could not be read, or nothing is dead-lettered. */
function DeadLettersEmpty({ destinations, available }: Readonly<{ destinations: string[]; available: boolean }>) {
  if (!available) {
    return (
      <EmptyState
        kind="empty"
        title="Dead-letter configuration unavailable"
        description={
          <>
            Rahdar could not read this broker's destination settings, so it will not guess which subscriptions are
            dead-letter subscriptions from their names. Grant the connection management-read access, or check{' '}
            <code>getDestinationSettingsAsJSON</code> is permitted, then reload.
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
          {destinations.length > 0 ? (
            <>
              The broker's dead-letter destination is <code>{destinations.join(', ')}</code>, but no subscription on it
              currently holds messages.
            </>
          ) : (
            <>The broker names no dead-letter or expiry destination, so nothing is dead-lettered.</>
          )}{' '}
          A message lands there when a consumer keeps rejecting it or it expires.
        </>
      }
    />
  );
}

/**
 * Dead-letter / expiry management (ADR-0021, D8). Destinations come from the broker's own settings —
 * when that read fails the view says exactly that and infers nothing. "Replay all" runs a by-selector
 * RETRY through the shared preview + cap gate. The grid is virtualised, so a cluster with hundreds
 * of dead-lettered subscriptions needs no paging.
 */
export function DeadLettersView() {
  const { clusterId } = useParams({ strict: false }) as { clusterId: string };
  const dlq = useDeadLetters(clusterId);
  const [replay, setReplay] = useState<DeadLetterSubscription | null>(null);
  const [breakdown, setBreakdown] = useState<DeadLetterSubscription | null>(null);
  const columns = useMemo(() => deadLetterColumns(clusterId), [clusterId]);

  const destinations = dlq.data?.destinations;
  // Flattened so each row is a subscription rather than a destination: one destination can hold hundreds.
  const rows = useMemo<DeadLetterRow[]>(
    () =>
      (destinations ?? []).flatMap((a) =>
        a.subscriptions.map((subscription) => ({ destination: a.name, kind: a.purpose, subscription })),
      ),
    [destinations],
  );

  return (
    <Page fill>
      <PageHeader
        title="Dead letters"
        description="Subscriptions on the broker's dead-letter and expiry destinations that hold messages, with how many on each node."
      />

      <DataTable
        label="Dead-letter subscriptions"
        storageKey="dead-letters"
        height="fill"
        columns={columns}
        data={rows}
        rowKey={rowKey}
        loading={dlq.isPending}
        error={dlq.isError ? <ErrorState error={dlq.error} onRetry={() => void dlq.refetch()} /> : undefined}
        onRowClick={(r) => setBreakdown(r.subscription)}
        rowMenu={{
          label: (r) => `dead-letter subscription ${r.subscription.name}`,
          render: (r) => (
            <>
              <ActionMenuItem
                label="Per-node breakdown"
                icon={<IconListDetails size="1rem" aria-hidden />}
                onSelect={() => setBreakdown(r.subscription)}
              />
              <ReplayItem
                clusterId={clusterId}
                kindId={r.subscription.kind}
                onReplay={() => setReplay(r.subscription)}
              />
            </>
          ),
        }}
        empty={
          <DeadLettersEmpty
            destinations={(destinations ?? []).map((a) => a.name)}
            available={dlq.data?.settingsAvailable ?? true}
          />
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
            items={breakdown.perNode.map((n) => ({
              term: n.nodeName,
              value: n.depth == null ? 'Unknown' : messageCount(n.depth),
            }))}
          />
        ) : null}
      </Modal>

      {replay ? (
        <BulkActionPreview
          clusterId={clusterId}
          subscriptionName={replay.name}
          action="retry"
          opened={replay !== null}
          onClose={() => setReplay(null)}
          onDone={() => setReplay(null)}
        />
      ) : null}
    </Page>
  );
}
