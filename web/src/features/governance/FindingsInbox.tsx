import { useState } from 'react';
import { SegmentedControl, Text } from '@mantine/core';

import { useCan } from '../../kernel/auth/useCan.ts';
import { useDisplayZone } from '../../kernel/time/timezone.ts';
import { EmptyState } from '../../ui/EmptyState.tsx';
import { ErrorState } from '../../ui/ErrorState.tsx';
import { notify, type ActionVerb } from '../../ui/notify.ts';
import { Section } from '../../ui/Section.tsx';
import { DataTable } from '../../ui/table/index.ts';
import { useDecideFinding, useFindings, type FindingView } from './api.ts';
import { findingColumns } from './columns.ts';
import { FINDING_STATUSES, fieldOf } from './words.ts';

const CONFIRM: ActionVerb = { verb: 'Confirm', past: 'Confirmed', progressive: 'Confirming' };
const DISMISS: ActionVerb = { verb: 'Dismiss', past: 'Dismissed', progressive: 'Dismissing' };

const WRITE_REASON = 'Confirming or dismissing a finding needs the governance:write permission.';

const rowKey = (f: FindingView) => f.id;

/** Why the table is empty: no finding exists, or the status filter excludes the ones that do. */
function FindingsEmpty({ status, onClear }: Readonly<{ status: string; onClear: () => void }>) {
  if (status === 'OPEN' || status === 'ALL') {
    return (
      <EmptyState
        kind="empty"
        title="No findings"
        description="No personal data has been detected in a field that no rule covers. Findings appear here as Rahdar reads messages."
      />
    );
  }
  return (
    <EmptyState
      kind="filtered"
      title={`No ${status.toLowerCase()} findings`}
      description="Other findings may exist under another status."
      onClearFilters={onClear}
    />
  );
}

/**
 * The classification inbox (data-governance spec): personal data the detectors found in fields no rule covers.
 * Every such value is already masked; a decision here only turns the detection into a rule or an exception.
 */
export function FindingsInbox() {
  const zone = useDisplayZone();
  const [status, setStatus] = useState('OPEN');
  const findings = useFindings(status);
  const decide = useDecideFinding();
  const { can, loading } = useCan();
  // While grants load, offer the controls: the server decides (operator-ui spec).
  const canWrite = loading || can('governance:write');
  const [pending, setPending] = useState<string | null>(null);

  function act(f: FindingView, decision: 'confirm' | 'dismiss') {
    const action = decision === 'confirm' ? CONFIRM : DISMISS;
    const subject = `the finding for ${fieldOf(f)} on ${f.destination} (${f.dataClassLabel})`;
    setPending(f.id);
    decide.mutate(
      { findingId: f.id, decision },
      {
        onSuccess: () => notify.succeeded({ action, subject }),
        onError: (e) =>
          notify.settle(e, { action, subject, cause: e.message, next: 'The finding is unchanged. Try again.' }),
        onSettled: () => setPending(null),
      },
    );
  }

  // Built each render: the cells carry what is gated and busy right now.
  const columns = findingColumns({
    zone,
    controls: {
      canWrite,
      deciding: pending === null ? null : { id: pending, decision: decide.variables?.decision },
      onDecide: act,
    },
  });

  return (
    <Section
      title="Classification inbox"
      description="A finding is personal data the detectors recognised in a field no masking rule names. Those values are already masked. Confirm a finding to make it a rule, or dismiss it as a false positive so that field is no longer masked for that class."
    >
      {canWrite ? null : <Text size="sm">{WRITE_REASON}</Text>}
      <DataTable
        variant="static"
        label="Classification findings"
        storageKey="governance.findings"
        columns={columns}
        data={findings.data ?? []}
        rowKey={rowKey}
        loading={findings.isPending}
        error={
          findings.isError ? <ErrorState error={findings.error} onRetry={() => void findings.refetch()} /> : undefined
        }
        toolbar={{
          start: (
            <SegmentedControl
              size="xs"
              data={FINDING_STATUSES}
              value={status}
              onChange={setStatus}
              aria-label="Show findings by status"
            />
          ),
        }}
        empty={<FindingsEmpty status={status} onClear={() => setStatus('ALL')} />}
      />
    </Section>
  );
}
