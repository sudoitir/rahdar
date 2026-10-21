import { useState } from 'react';
import { Button } from '@mantine/core';

import { useCluster } from '../clusters/index.ts';
import { useCan } from '../../kernel/auth/useCan.ts';
import type { SlotProps } from '../../kernel/slots.ts';
import { CapabilityGate } from '../../ui/CapabilityGate.tsx';
import { gateFor, type GateVerdict } from '../../ui/capabilityGate.ts';
import { endpointsOf, serving } from './nodes.ts';
import { TransferDialog } from './TransferDialog.tsx';

/** An empty selection is one more reason a control cannot act, explained like the others. */
function withSelection(gate: GateVerdict, total: number | null): GateVerdict {
  return gate.kind === 'allowed' && total === 0
    ? { kind: 'blocked', reason: 'There are no messages to transfer: the subscription is empty.' }
    : gate;
}

/**
 * "Transfer…" and "Redistribute to node…" beside the messages screen's selection
 * (`messages.selection`). Each opens its preview; nothing acts from here. The dialogs stay mounted,
 * so a closed modal hands focus back to the button that opened it.
 */
export function TransferActions({
  clusterId,
  subscription,
  node,
  selection,
  total,
  clear,
}: Readonly<SlotProps['messages.selection']>) {
  const { canAnywhere, loading } = useCan();
  const cluster = useCluster(clusterId);
  const [opened, setOpened] = useState<'transfer' | 'redistribute' | null>(null);
  const pending = loading || cluster.isPending;

  // Either mode will do here; the dialog gates each one on its own permission.
  const transferGate = withSelection(
    gateFor(
      canAnywhere('message:move', clusterId) || canAnywhere('message:read', clusterId),
      'Browse messages',
      undefined,
      pending,
    ),
    total,
  );
  const live = endpointsOf(cluster.data?.topology).filter(serving).length;
  const moveGate = gateFor(
    canAnywhere('message:move', clusterId),
    'Move or retry messages',
    cluster.data?.capabilities.managementWrite,
    pending,
  );
  const redistributeGate = withSelection(
    moveGate.kind === 'allowed' && cluster.data && live < 2
      ? {
          kind: 'blocked',
          reason: `This cluster has ${live === 1 ? 'one live node' : 'no live node'}, so there is no other node to redistribute to.`,
        }
      : moveGate,
    total,
  );

  const dialog = (redistribute: boolean) => (
    <TransferDialog
      clusterId={clusterId}
      subscriptionName={subscription.name}
      node={node}
      selection={selection}
      total={total}
      redistribute={redistribute}
      opened={opened === (redistribute ? 'redistribute' : 'transfer')}
      onClose={() => setOpened(null)}
      onStarted={clear}
    />
  );

  return (
    <>
      <CapabilityGate verdict={transferGate} what="transferring these messages">
        <Button
          size="xs"
          variant="light"
          disabled={transferGate.kind === 'blocked'}
          onClick={() => setOpened('transfer')}
        >
          Transfer…
        </Button>
      </CapabilityGate>
      <CapabilityGate verdict={redistributeGate} what="redistributing these messages">
        <Button
          size="xs"
          variant="light"
          disabled={redistributeGate.kind === 'blocked'}
          onClick={() => setOpened('redistribute')}
        >
          Redistribute to node…
        </Button>
      </CapabilityGate>
      {dialog(false)}
      {dialog(true)}
    </>
  );
}
