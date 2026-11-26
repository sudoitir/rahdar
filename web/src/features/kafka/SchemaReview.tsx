import type { ReactNode } from 'react';
import { Button, Stack, Text } from '@mantine/core';

import { ConfirmDialog } from '../../ui/ConfirmDialog.tsx';
import { DescriptionList, type DescriptionItem } from '../../ui/DescriptionList.tsx';
import { ErrorState } from '../../ui/ErrorState.tsx';
import type { SchemaPreview } from './schemasApi.ts';
import classes from './Schemas.module.css';

/** Before and after, the server's "nothing was written" sentence, and the compatibility facts it sent. */
export function ReviewSummary({ preview }: Readonly<{ preview: SchemaPreview }>) {
  const items: DescriptionItem[] = [
    { term: 'Subject', value: preview.subject ?? 'Registry default' },
    { term: 'Action', value: preview.action ?? '' },
  ];
  if (preview.compatibilityOutcome) items.push({ term: 'Compatibility', value: preview.compatibilityOutcome });
  if (preview.alreadyRegistered) {
    items.push({
      term: 'Already registered',
      value: preview.existingVersionId == null ? 'Yes' : `Id ${preview.existingVersionId}`,
    });
  }
  return (
    <Stack gap="sm">
      <DescriptionList items={items} label="What this would change" />
      <Text size="sm" fw={600}>
        Before
      </Text>
      <Lines lines={preview.before ?? []} />
      <Text size="sm" fw={600}>
        After
      </Text>
      <Lines lines={preview.after ?? []} />
      {preview.statement ? <Text size="sm">{preview.statement}</Text> : null}
      {preview.checkOnApply && preview.note ? <Text size="sm">{preview.note}</Text> : null}
      {preview.note && preview.note !== preview.statement && !preview.checkOnApply ? (
        <Text size="sm">{preview.note}</Text>
      ) : null}
    </Stack>
  );
}

function Lines({ lines }: Readonly<{ lines: string[] }>) {
  if (lines.length === 0) return <Text size="sm">None</Text>;
  return (
    <ul className={classes.lines}>
      {lines.map((line) => (
        <li key={line}>
          <Text size="sm">{line}</Text>
        </li>
      ))}
    </ul>
  );
}

/**
 * The same summary for a preview and its result. Register, compatibility and delete confirm by holding:
 * a click does nothing. A 412 says the registry moved and offers a fresh preview.
 */
export function SchemaReview({
  opened,
  onClose,
  title,
  confirmLabel,
  tone = 'default',
  preview,
  lead,
  pending = false,
  stale = false,
  error,
  onConfirm,
  onRefresh,
  result,
}: Readonly<{
  opened: boolean;
  onClose: () => void;
  title: string;
  confirmLabel: string;
  tone?: 'default' | 'danger';
  preview: SchemaPreview | null;
  /** Blast radius the preview's note does not already state. */
  lead?: ReactNode;
  pending?: boolean;
  stale?: boolean;
  error?: unknown;
  onConfirm: () => void;
  onRefresh?: () => void;
  result?: ReactNode;
}>) {
  return (
    <ConfirmDialog
      opened={opened}
      onClose={onClose}
      title={title}
      confirmLabel={confirmLabel}
      tone={tone}
      pending={pending}
      blocked={stale ? 'The registry moved since this preview. Preview again. Nothing was written.' : undefined}
      result={result}
      onConfirm={onConfirm}
      consequence={
        <Stack gap="sm">
          {lead}
          {preview ? <ReviewSummary preview={preview} /> : null}
          {stale ? (
            <Button variant="default" onClick={onRefresh}>
              Preview again
            </Button>
          ) : null}
          {error && !stale ? <ErrorState variant="inline" error={error} /> : null}
        </Stack>
      }
    />
  );
}
