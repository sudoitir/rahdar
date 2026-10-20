import { useMemo, useState } from 'react';
import { Button, Group, Modal, Stack, Text } from '@mantine/core';
import { useForm } from '@mantine/form';
import { Link, useParams } from '@tanstack/react-router';

import { useClusters } from '../clusters/index.ts';
import { DestinationPicker } from '../resources/index.ts';
import { useCan } from '../../kernel/auth/useCan.ts';
import { useRoleIds } from '../clusters/index.ts';
import { useDisplayZone } from '../../kernel/time/timezone.ts';
import { CapabilityGate } from '../../ui/CapabilityGate.tsx';
import { gateFor } from '../../ui/capabilityGate.ts';
import { HoldToConfirm } from '../../ui/HoldToConfirm.tsx';
import { EmptyState } from '../../ui/EmptyState.tsx';
import { ErrorState } from '../../ui/ErrorState.tsx';
import { focusFirstInvalid } from '../../ui/formErrors.ts';
import linkClasses from '../../ui/InlineLink.module.css';
import { Page } from '../../ui/Page.tsx';
import { PageHeader } from '../../ui/PageHeader.tsx';
import { Section } from '../../ui/Section.tsx';
import { DataTable } from '../../ui/table/index.ts';
import { useOrphans, useReturnOrphan, useTransferRuns, type OrphanView, type TransferRunView } from './api.ts';
import { transferColumns } from './columns.ts';
import classes from './TransfersView.module.css';
import { plural } from './words.ts';

/**
 * Staging left on a broker by a run Rahdar no longer has: the one way messages could strand.
 * It is never cleaned up behind the operator's back — each one is listed with its depth and a
 * return action that names the subscription the messages go back to.
 */
function Orphans({ clusterId }: Readonly<{ clusterId: string }>) {
  const query = useOrphans(clusterId);
  const returnOrphan = useReturnOrphan(clusterId);
  const ids = useRoleIds(clusterId);
  const { canAnywhere, loading } = useCan();
  const [chosen, setChosen] = useState<OrphanView | null>(null);
  const form = useForm({
    initialValues: { subscription: '' },
    validateInputOnBlur: true,
    validate: { subscription: (v) => (v.trim() ? null : 'Name the subscription the messages go back to.') },
  });
  const gate = gateFor(canAnywhere('message:move', clusterId), 'Move or retry messages', undefined, loading);

  const returnMessages = form.onSubmit(({ subscription }) => {
    if (!chosen) return;
    returnOrphan.mutate(
      {
        nodeId: chosen.nodeId,
        stagingQueue: chosen.stagingQueue,
        target: ids.subscription(subscription.trim()),
      },
      { onSuccess: () => setChosen(null) },
    );
  }, focusFirstInvalid(form.getInputNode));

  // Not being able to look is not the same as nothing being there, and is said rather than hidden.
  if (query.isError) {
    return (
      <Section
        title="Staging subscriptions with no run"
        description="Rahdar could not check for them. If a transfer was interrupted, its messages may still be held on a broker."
      >
        <ErrorState variant="inline" error={query.error} onRetry={() => void query.refetch()} />
      </Section>
    );
  }
  // Nothing stranded is the normal case, and says nothing.
  if (!query.data || query.data.length === 0) return null;

  return (
    <Section
      title="Staging subscriptions with no run"
      description="These hold messages a transfer took off a subscription before Rahdar lost the run. Nothing is lost: return each one to the subscription the messages came from, and Rahdar removes the staging subscription afterwards."
    >
      {query.data.map((orphan) => (
        <Group key={`${orphan.nodeId}:${orphan.stagingQueue}`} gap="sm">
          <Text size="sm" className={classes.figures}>
            {orphan.stagingQueue} on {orphan.nodeName} —{' '}
            {orphan.depth == null ? 'depth unknown' : plural(orphan.depth, 'message')}
          </Text>
          <CapabilityGate verdict={gate} what="returning these messages">
            <Button
              size="xs"
              variant="default"
              disabled={gate.kind === 'blocked'}
              onClick={() => {
                returnOrphan.reset();
                form.reset();
                setChosen(orphan);
              }}
            >
              Return to…
            </Button>
          </CapabilityGate>
        </Group>
      ))}

      <div aria-live="polite">
        {returnOrphan.data ? (
          <Text size="sm">
            {plural(returnOrphan.data.returned, 'message')} returned from {returnOrphan.data.stagingQueue}.{' '}
            {returnOrphan.data.removed
              ? 'The staging subscription is gone.'
              : `${plural(returnOrphan.data.remaining, 'message')} could not be taken and the staging subscription is still there.`}
          </Text>
        ) : null}
      </div>

      <Modal
        opened={chosen !== null}
        onClose={() => setChosen(null)}
        title={chosen ? `Return the messages held in ${chosen.stagingQueue}` : ''}
      >
        {chosen ? (
          <Stack gap="sm">
            <Text size="sm">
              Every message in {chosen.stagingQueue} on {chosen.nodeName} moves to the subscription you name, on that
              same node, and the staging subscription is then removed.
            </Text>
            <DestinationPicker
              clusterId={clusterId}
              label="Return them to"
              permission="message:send"
              description="The subscription the transfer took them from."
              {...form.getInputProps('subscription')}
              value={form.values.subscription}
              unknownHint="No subscription by that name on this cluster. Check it before returning messages to it."
            />
            {returnOrphan.isError ? (
              <ErrorState
                error={returnOrphan.error}
                next="Nothing was returned. Check the subscription, then try again."
              />
            ) : null}
            <HoldToConfirm
              label="Return the messages"
              loading={returnOrphan.isPending}
              disabled={returnOrphan.isPending}
              onConfirm={() => returnMessages()}
            />
          </Stack>
        ) : null}
      </Modal>
    </Section>
  );
}

const rowKey = (r: TransferRunView) => r.id;

/** Transfers where this cluster is the source or the target, newest first, and any stranded staging. */
export function TransfersView() {
  const zone = useDisplayZone();
  const { clusterId } = useParams({ strict: false }) as { clusterId: string };
  const query = useTransferRuns(clusterId);
  const clusters = useClusters();
  const clusterList = clusters.data;
  const columns = useMemo(
    () =>
      transferColumns({
        clusterId,
        clusterName: (id) => clusterList?.find((c) => c.id === id)?.name ?? 'another cluster',
        zone,
      }),
    [clusterId, clusterList, zone],
  );

  return (
    <Page fill>
      <PageHeader
        title="Message transfers"
        description="Messages moved or copied between subscriptions on other nodes or clusters, newest first, each with its outcome."
      />
      <Orphans clusterId={clusterId} />
      <DataTable
        label="Transfers"
        storageKey="transfers"
        height="fill"
        columns={columns}
        data={query.data ?? []}
        rowKey={rowKey}
        loading={query.isPending}
        error={query.isError ? <ErrorState error={query.error} onRetry={() => void query.refetch()} /> : undefined}
        empty={
          <EmptyState
            kind="empty"
            title="No transfers yet"
            description="A transfer moves or copies messages from a subscription to a subscription on another node or another cluster, after a preview that checks the target can accept them. None has been run from or to this cluster yet. Select messages on the Messages screen to start one."
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
