import { describe, expect, it } from 'vitest';
import { page } from 'vitest/browser';

import { EmptyState } from '../../ui/EmptyState.tsx';
import { Notice } from '../../ui/Notice.tsx';
import { Page } from '../../ui/Page.tsx';
import { PageHeader } from '../../ui/PageHeader.tsx';
import { DataTable } from '../../ui/table/index.ts';
import { Frame, renderThemed, SCHEMES, settle } from '../../test/browser.tsx';
import { partitionColumns } from './partitionColumns.tsx';
import type { PartitionRow } from './placement.ts';

function row(over: Partial<PartitionRow> = {}): PartitionRow {
  return {
    topic: 'orders',
    partition: 0,
    leader: 1,
    replicas: [2, 1],
    inSyncReplicas: [1],
    offlineReplicas: [2],
    logStartOffset: 10,
    logEndOffset: 40,
    sizeBytes: 4096,
    replicasOnBroker: [
      { broker: 2, bytes: 4096, offsetLag: 40, future: false },
      { broker: 1, bytes: 3800, offsetLag: 0, future: false },
    ],
    preferredLeader: false,
    eligibleLeaderReplicas: null,
    localLogStartOffset: 10,
    tieredOffset: null,
    tieredOffsetReason: 'not tiered',
    logDirsUnavailable: null,
    ...over,
  };
}

function Partitions({ scheme }: { scheme: (typeof SCHEMES)[number] }) {
  return (
    <Page fill>
      <PageHeader
        title="Partitions of orders"
        description="Where each partition's replicas sit, in the cluster's order, and how far each one is behind."
      />
      <Notice tone="warning" title="Broker 3 did not answer the log-directory read." />
      <DataTable
        label="Partitions of orders"
        storageKey={`shot.partitions.${scheme}`}
        height="fill"
        columns={partitionColumns({ clusterId: 'k1', showTopic: false, showEligible: false })}
        data={[row(), row({ partition: 1, leader: 2, replicas: [2, 1], preferredLeader: true, offlineReplicas: [] })]}
        rowKey={(item) => String(item.partition)}
        empty={null}
      />
    </Page>
  );
}

function InSync() {
  return (
    <Page>
      <PageHeader
        title="Replication"
        description="Partitions that are under-replicated, have an offline replica, or are not led by their first replica."
      />
      <EmptyState
        kind="empty"
        title="In sync"
        description="Every partition is in sync and on its preferred leader."
      />
    </Page>
  );
}

async function shot(name: string) {
  await settle(() => document.body.innerText);
  expect(document.documentElement.scrollWidth).toBeLessThanOrEqual(document.documentElement.clientWidth + 1);
  await page.screenshot({ path: `__screenshots__/placement-${name}.png` });
}

describe('partition placement on a desktop', () => {
  it.each(SCHEMES)('shows an under-replicated partition in %s', async (scheme) => {
    renderThemed(
      <Frame width={1280} height={720}>
        <Partitions scheme={scheme} />
      </Frame>,
      scheme,
    );
    await shot(`partitions-${scheme}`);
  });

  it.each(SCHEMES)('says a healthy cluster is in sync in %s', async (scheme) => {
    renderThemed(
      <Frame width={1280} height={480}>
        <InSync />
      </Frame>,
      scheme,
    );
    await shot(`replication-empty-${scheme}`);
  });
});
