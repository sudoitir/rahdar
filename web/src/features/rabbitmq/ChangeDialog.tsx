import type { ReactNode } from 'react';
import { Stack, Text } from '@mantine/core';

import { ConfirmDialog } from '../../ui/ConfirmDialog.tsx';
import { ErrorState } from '../../ui/ErrorState.tsx';
import { IdentityBasisNote } from '../../ui/IdentityBasisNote.tsx';
import type { ActionVerb } from '../../ui/notify.ts';
import { DataTable, type Column } from '../../ui/table/index.ts';
import type { Change, PolicyPreview, RabbitMqDiff, RabbitMqPreview } from './api.ts';

const DIFF_COLUMNS: Column<RabbitMqDiff>[] = [
  { id: 'key', header: 'Setting', accessor: (d) => d.key, kind: 'identifier', priority: 'essential' },
  { id: 'before', header: 'Now', accessor: (d) => d.before ?? 'None', kind: 'text', priority: 'essential', wrap: true },
  {
    id: 'after',
    header: 'After',
    accessor: (d) => d.after ?? 'Removed',
    kind: 'text',
    priority: 'essential',
    wrap: true,
  },
];

/**
 * The dialog every RabbitMQ change goes through: what the preview says the change does, the note that RabbitMQ does
 * not tell a recreated resource apart, and the confirmation (held for a destructive change, ADR-0186). A stale
 * preview (412) is said in the dialog; a preview that fails is shown where the control sits.
 */
export function ChangeDialog<V, P extends RabbitMqPreview | PolicyPreview>({
  change,
  title,
  action,
  confirmLabel,
  subject,
  destructive = false,
  extra,
}: Readonly<{
  change: Change<V, P>;
  title: string;
  action: ActionVerb;
  confirmLabel: string;
  subject: string;
  destructive?: boolean;
  /** What this change adds to the effect, such as the resources a policy matches. */
  extra?: (preview: P) => ReactNode;
}>) {
  const preview = change.reviewed?.preview;
  return (
    <>
      {change.previewError ? <ErrorState variant="inline" error={change.previewError} /> : null}
      <ConfirmDialog
        opened={preview !== undefined}
        onClose={change.close}
        title={title}
        tone={destructive ? 'danger' : 'default'}
        confirmLabel={confirmLabel}
        blocked={
          change.applyError?.status === 412
            ? 'This changed since the preview. Close it and review the change again.'
            : undefined
        }
        pending={change.applying}
        onConfirm={() => change.apply(action, subject)}
        consequence={
          preview ? (
            <Stack gap="sm">
              <Text size="sm">{preview.effect.detail}</Text>
              {preview.effect.count > 0 ? (
                <Text size="sm" fw={500}>
                  {preview.effect.count.toLocaleString()} {preview.effect.unit} affected
                </Text>
              ) : null}
              {preview.effect.lines.length > 0 ? (
                <Stack gap={2} component="ul" m={0} pl="md" aria-label="What the change reaches">
                  {preview.effect.lines.map((line) => (
                    <Text key={line} component="li" size="sm">
                      {line}
                    </Text>
                  ))}
                </Stack>
              ) : null}
              {preview.effect.changes.length > 0 ? (
                <DataTable
                  variant="static"
                  label="Settings before and after"
                  storageKey="rabbitmq.change"
                  columns={DIFF_COLUMNS}
                  data={preview.effect.changes}
                  rowKey={(d) => d.key}
                  height={{ maxRows: 8 }}
                  empty={null}
                />
              ) : null}
              {extra?.(preview)}
              <IdentityBasisNote basis={preview.identityBasis} />
            </Stack>
          ) : null
        }
      />
    </>
  );
}
