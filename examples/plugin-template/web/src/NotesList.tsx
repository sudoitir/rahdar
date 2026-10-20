import { useMemo, useState } from 'react';
import { Button, Stack, Textarea } from '@mantine/core';
import { DataTable, EmptyState, ErrorState, Section, useCan, type Column } from '@rahdar/plugin-sdk';

import { ID, useAddNote, useDeleteNote, useSubscriptionNotes, type Note } from './api.ts';

/** The notes on one subscription, and — for someone allowed to — a way to add one. */
export function NotesList({ clusterId, subscription }: { clusterId: string; subscription: string }) {
  const notes = useSubscriptionNotes(clusterId, subscription);
  const add = useAddNote(clusterId, subscription);
  const remove = useDeleteNote(clusterId);
  const { can } = useCan();
  const [body, setBody] = useState('');
  const canWrite = can(`${ID}:write`, { clusterId, type: 'subscription', name: subscription });

  const columns = useMemo<Column<Note>[]>(() => {
    const cols: Column<Note>[] = [
      { id: 'note', header: 'Note', accessor: (n) => n.body, kind: 'text', priority: 'essential', wrap: true },
      { id: 'author', header: 'By', accessor: (n) => n.author, kind: 'text', priority: 'high' },
      {
        id: 'createdAt',
        header: 'Added',
        accessor: (n) => new Date(n.createdAt).toLocaleString(),
        kind: 'time',
        priority: 'high',
      },
    ];
    if (canWrite) {
      cols.push({
        id: 'actions',
        header: 'Actions',
        accessor: () => 'Delete',
        kind: 'status',
        priority: 'essential',
        cell: (n) => (
          <Button
            size="compact-xs"
            variant="subtle"
            aria-label={`Delete the note by ${n.author}`}
            loading={remove.isPending && remove.variables === n.id}
            onClick={() => remove.mutate(n.id)}
          >
            Delete
          </Button>
        ),
      });
    }
    return cols;
  }, [canWrite, remove]);

  return (
    <Stack gap="md">
      <DataTable
        variant="static"
        label={`Notes on ${subscription}`}
        columns={columns}
        data={notes.data ?? []}
        rowKey={(n) => n.id}
        loading={notes.isPending}
        height={{ maxRows: 8 }}
        empty={
          <EmptyState
            kind="empty"
            title={`No notes on ${subscription}`}
            description="A note is for the next operator: why this subscription is odd, who owns it."
          />
        }
        error={notes.isError ? <ErrorState error={notes.error} onRetry={() => void notes.refetch()} /> : undefined}
      />
      <Section title="Add a note" headingLevel={3}>
        <form
          onSubmit={(e) => {
            e.preventDefault();
            add.mutate(body, { onSuccess: () => setBody('') });
          }}
        >
          <Stack gap="xs">
            <Textarea
              aria-label="Note"
              value={body}
              onChange={(e) => setBody(e.currentTarget.value)}
              autosize
              minRows={2}
              disabled={!canWrite}
              description={canWrite ? undefined : `Adding notes needs the ${ID}:write permission on this cluster.`}
              error={add.error?.message}
            />
            <Button type="submit" size="xs" w="fit-content" loading={add.isPending} disabled={!canWrite}>
              Add note
            </Button>
          </Stack>
        </form>
      </Section>
    </Stack>
  );
}
