import { useState } from 'react';
import { Button } from '@mantine/core';

import { useCluster } from '../clusters/index.ts';
import { useCan } from '../../kernel/auth/useCan.ts';
import type { SlotProps } from '../../kernel/slots.ts';
import { CapabilityGate } from '../../ui/CapabilityGate.tsx';
import { gateFor, type GateVerdict } from '../../ui/capabilityGate.ts';
import type { BulkOperation } from './api.ts';
import { BulkPreviewDialog } from './BulkPreviewDialog.tsx';
import { OPERATIONS } from './words.ts';

const ORDER: BulkOperation[] = ['PAUSE', 'RESUME', 'PURGE', 'DELETE'];

/**
 * The bulk actions over the subscriptions screen's selection (`subscriptions.selection`). Each is gated on the
 * permission of the single-subscription command it applies, and opens its preview; nothing acts from here.
 */
export function BulkActionBar({ clusterId, selection, count, clear }: Readonly<SlotProps['subscriptions.selection']>) {
  const { canAnywhere, loading } = useCan();
  const cluster = useCluster(clusterId);
  const write = cluster.data?.capabilities.managementWrite;
  // The dialog stays mounted and is opened and closed, so the modal hands focus back to the button
  // that opened it.
  const [operation, setOperation] = useState<BulkOperation>('PAUSE');
  const [opened, setOpened] = useState(false);

  return (
    <>
      {ORDER.map((each) => {
        const op = OPERATIONS[each];
        const permitted = gateFor(
          canAnywhere(op.permission, clusterId),
          op.permissionLabel,
          write,
          loading || cluster.isPending,
        );
        // An empty selection is one more reason the control cannot act, explained the same way.
        const gate: GateVerdict =
          permitted.kind === 'allowed' && count === 0
            ? {
                kind: 'blocked',
                reason:
                  'No subscriptions are selected. Select subscriptions in the grid, or select all matching the filter.',
              }
            : permitted;
        return (
          <CapabilityGate key={each} verdict={gate} what={`${op.gerund} these subscriptions`}>
            <Button
              size="xs"
              variant="default"
              disabled={gate.kind === 'blocked'}
              onClick={() => {
                setOperation(each);
                setOpened(true);
              }}
            >
              {op.destructive ? `${op.verb}…` : op.verb}
            </Button>
          </CapabilityGate>
        );
      })}
      <BulkPreviewDialog
        clusterId={clusterId}
        operation={operation}
        selection={selection}
        opened={opened}
        onClose={() => setOpened(false)}
        onStarted={clear}
      />
    </>
  );
}
