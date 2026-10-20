import { useEffect, useRef, useState, type Ref } from 'react';
import { Button, Fieldset, Select, Stack, Text, TextInput } from '@mantine/core';
import { useForm } from '@mantine/form';
import { useDebouncedValue } from '@mantine/hooks';

import { ConfirmDialog } from '../../ui/ConfirmDialog.tsx';
import { EmptyState } from '../../ui/EmptyState.tsx';
import { ErrorState } from '../../ui/ErrorState.tsx';
import { FieldRow } from '../../ui/FieldRow.tsx';
import { focusFirstInvalid, serverFieldErrors } from '../../ui/formErrors.ts';
import { Notice } from '../../ui/Notice.tsx';
import { notify, type ActionVerb } from '../../ui/notify.ts';
import { Section } from '../../ui/Section.tsx';
import { DataTable } from '../../ui/table/index.ts';
import { useClusters } from '../clusters/index.ts';
import {
  useAddPattern,
  usePatternPreview,
  useRemovePattern,
  type PatternKind,
  type PatternPreview,
  type PatternView,
  type TeamView,
} from './api.ts';
import { KindField } from '../../ui/KindField.tsx';
import { withNotice } from './outcomes.ts';
import classes from './Security.module.css';
import { useTeamAccess } from './teamAccess.ts';
import { patternColumns } from './teamColumns.tsx';
import { conflictText, countOf, KIND_OPTIONS, patternFault, problemSlug } from './teamWords.ts';

const ADD: ActionVerb = { verb: 'Add', past: 'Added', progressive: 'Adding' };
const REMOVE: ActionVerb = { verb: 'Remove', past: 'Removed', progressive: 'Removing' };

const SHOWN_EXAMPLES = 5;

/** A pattern to pre-fill the form with: the exact name picked from the unowned list. */
export interface PatternDraft {
  clusterId: string;
  kind: PatternKind;
  pattern: string;
  /** Changes with every pick, so the form reseeds even when the same name is picked twice. */
  nonce: number;
}

const rowKey = (p: PatternView) => p.id;

/**
 * A team's patterns, each with the way to remove it, and the form that adds one with a live preview. `focusFirst`
 * puts focus in the form's first field once, for a team just created; `onFocused` says it was done.
 */
export function TeamPatterns({
  team,
  draft,
  focusFirst = false,
  onFocused,
}: Readonly<{ team: TeamView; draft?: PatternDraft; focusFirst?: boolean; onFocused?: () => void }>) {
  const clusters = useClusters();
  const { userAdmin } = useTeamAccess();
  const [removing, setRemoving] = useState<PatternView | null>(null);
  const [removeOpen, setRemoveOpen] = useState(false);
  const firstField = useRef<HTMLInputElement>(null);

  useEffect(() => {
    if (!focusFirst) return;
    firstField.current?.focus();
    onFocused?.();
  }, [focusFirst, onFocused]);

  const clusterName = (id: string) => clusters.data?.find((c) => c.id === id)?.name ?? 'Unknown cluster';
  const columns = patternColumns({
    clusterName,
    editable: userAdmin,
    onRemove: (p) => {
      setRemoving(p);
      setRemoveOpen(true);
    },
  });

  return (
    <Stack gap="lg">
      <Section
        title="Owned patterns"
        headingLevel={3}
        description="The subscription and destination names this team owns on each cluster. Two teams cannot own the same name on one cluster."
      >
        <DataTable
          variant="static"
          label={`Patterns of ${team.name}`}
          storageKey="security.team-patterns"
          columns={columns}
          data={team.patterns}
          rowKey={rowKey}
          empty={
            <EmptyState
              kind="empty"
              title="No patterns"
              description="A pattern gives this team the subscriptions and destinations whose names it matches on one cluster. Until one is added, the team owns nothing and its members see no subscription or destination."
              action={
                userAdmin ? (
                  <Button size="xs" variant="default" onClick={() => firstField.current?.focus()}>
                    Add a pattern
                  </Button>
                ) : undefined
              }
            />
          }
        />
      </Section>

      <Section title="Add a pattern" headingLevel={3}>
        {userAdmin ? null : (
          <Notice title="Needs user:admin">
            Adding and removing a team&apos;s patterns needs the user:admin permission. Ask a user administrator.
          </Notice>
        )}
        <Fieldset legend="New pattern" disabled={!userAdmin}>
          <AddPattern key={draft?.nonce ?? 'blank'} team={team} initial={draft} firstField={firstField} />
        </Fieldset>
      </Section>

      <RemovePattern
        team={team}
        pattern={removing}
        opened={removeOpen}
        onClose={() => setRemoveOpen(false)}
        clusterName={clusterName}
      />
    </Stack>
  );
}

/** What a pattern matches on its cluster now, in words. */
function Matches({
  preview,
  kind,
  cluster,
}: Readonly<{ preview: PatternPreview; kind: PatternKind; cluster: string }>) {
  const parts = [
    kind === 'DESTINATION'
      ? null
      : {
          word: 'subscription',
          plural: 'subscriptions',
          count: preview.subscriptionMatches,
          examples: preview.subscriptionExamples,
        },
    kind === 'SUBSCRIPTION'
      ? null
      : {
          word: 'destination',
          plural: 'destinations',
          count: preview.destinationMatches,
          examples: preview.destinationExamples,
        },
  ].filter((p) => p !== null);
  const total = parts.reduce((sum, p) => sum + p.count, 0);
  if (total === 0) {
    return (
      <Text size="sm">Matches nothing on {cluster} today. The pattern still owns any name created under it later.</Text>
    );
  }
  return (
    <Stack gap={4}>
      <Text size="sm">
        Matches {parts.map((p) => countOf(p.count, p.word, p.plural)).join(' and ')} on {cluster} now.
      </Text>
      {parts.map((p) =>
        p.examples.length === 0 ? null : (
          <ul key={p.word} className={classes.examples} aria-label={`Matching ${p.word}s`}>
            {p.examples.slice(0, SHOWN_EXAMPLES).map((name) => (
              <li key={name}>{name}</li>
            ))}
            {p.count > SHOWN_EXAMPLES ? <li>and {p.count - SHOWN_EXAMPLES} more</li> : null}
          </ul>
        ),
      )}
    </Stack>
  );
}

function AddPattern({
  team,
  initial,
  firstField,
}: Readonly<{ team: TeamView; initial?: PatternDraft; firstField: Ref<HTMLInputElement> }>) {
  const clusters = useClusters();
  const add = useAddPattern(team.id);
  const form = useForm<{ clusterId: string | null; kind: PatternKind; pattern: string }>({
    initialValues: {
      clusterId: initial?.clusterId ?? null,
      kind: initial?.kind ?? 'SUBSCRIPTION',
      pattern: initial?.pattern ?? '',
    },
    validateInputOnBlur: true,
    validate: {
      clusterId: (v) => (v ? null : 'Choose the cluster the pattern applies to.'),
      pattern: patternFault,
    },
  });
  const { clusterId, kind, pattern } = form.values;

  // The preview follows the typing, a moment behind it, and only for a pattern that is well formed.
  const [settled] = useDebouncedValue(pattern, 300);
  const wellFormed = patternFault(settled) === null;
  const preview = usePatternPreview(team.id, clusterId, kind, settled, wellFormed);
  const current = settled === pattern && wellFormed && preview.data !== undefined;
  const conflict = current ? preview.data?.conflicts[0] : undefined;
  const clusterLabel = clusters.data?.find((c) => c.id === clusterId)?.name ?? 'the cluster';

  const submit = form.onSubmit(({ clusterId: cluster, kind: patternKind, pattern: text }) => {
    if (!cluster) return;
    if (conflict) {
      form.setErrors({ pattern: conflictText(conflict) });
      form.getInputNode('pattern')?.focus();
      return;
    }
    const subject = `pattern ${text} to ${team.name}`;
    const pendingId = notify.pending({ action: ADD, subject });
    add.mutate(
      { clusterId: cluster, kind: patternKind, pattern: text },
      {
        onSuccess: () => {
          notify.succeeded({ action: ADD, subject, pendingId });
          form.reset();
        },
        onError: (error) => {
          const fields = serverFieldErrors(error, ['clusterId', 'kind', 'pattern']);
          const slug = problemSlug(error);
          if (slug === 'team-pattern-overlap' || slug === 'duplicate-team-pattern') fields.pattern = error.message;
          if (Object.keys(fields).length > 0) {
            form.setErrors(fields);
            form.getInputNode('pattern')?.focus();
          }
          notify.settle(error, {
            action: ADD,
            subject,
            pendingId,
            cause: error.message,
            next: 'No pattern was added. Fix the pattern and try again.',
            onHeld: () => form.reset(),
          });
        },
      },
    );
  }, focusFirstInvalid(form.getInputNode));

  return (
    <form noValidate onSubmit={submit}>
      <Stack gap="sm">
        <KindField
          label="Kind"
          data={KIND_OPTIONS}
          value={kind}
          onChange={(next) => form.setFieldValue('kind', next)}
        />
        <FieldRow>
          <Select
            ref={firstField}
            label="Cluster"
            data={(clusters.data ?? []).map((c) => ({ value: c.id, label: c.name }))}
            searchable
            placeholder={clusters.isPending ? 'Loading' : 'Select a cluster'}
            nothingFoundMessage="No clusters"
            {...form.getInputProps('clusterId')}
            required
          />
          <TextInput
            label="Pattern"
            description="Words separated by dots. * is one word, # is any number of words."
            {...form.getInputProps('pattern')}
            error={form.errors.pattern ?? (conflict ? conflictText(conflict) : undefined)}
            required
          />
        </FieldRow>
        <PreviewLine
          cluster={clusterLabel}
          chosen={clusterId !== null}
          wellFormed={wellFormed}
          waiting={settled !== pattern || preview.isFetching}
          error={preview.isError ? preview.error : null}
          onRetry={() => void preview.refetch()}
          preview={current ? preview.data : undefined}
          kind={kind}
        />
        <div>
          <Button type="submit" loading={add.isPending}>
            Add pattern
          </Button>
        </div>
      </Stack>
    </form>
  );
}

/** The preview's place under the form: always one live region, so its change is announced and nothing jumps. */
function PreviewLine({
  cluster,
  chosen,
  wellFormed,
  waiting,
  error,
  onRetry,
  preview,
  kind,
}: Readonly<{
  cluster: string;
  chosen: boolean;
  wellFormed: boolean;
  waiting: boolean;
  error: unknown;
  onRetry: () => void;
  preview: PatternPreview | undefined;
  kind: PatternKind;
}>) {
  let content;
  if (!chosen || !wellFormed) {
    content = (
      <Text size="sm" c="dimmed">
        Choose a cluster and type a pattern to see what it matches.
      </Text>
    );
  } else if (error) {
    content = <ErrorState variant="inline" error={error} onRetry={onRetry} />;
  } else if (preview && !waiting) {
    content = <Matches preview={preview} kind={kind} cluster={cluster} />;
  } else {
    content = (
      <Text size="sm" c="dimmed">
        Checking what it matches…
      </Text>
    );
  }
  return (
    <output aria-live="polite" aria-label="Pattern preview" style={{ display: 'block' }}>
      {content}
    </output>
  );
}

/** States what removing a pattern ends, from a count of what it matches now, then asks for the pattern. */
function RemovePattern({
  team,
  pattern,
  opened,
  onClose,
  clusterName,
}: Readonly<{
  team: TeamView;
  pattern: PatternView | null;
  opened: boolean;
  onClose: () => void;
  clusterName: (clusterId: string) => string;
}>) {
  const remove = useRemovePattern(team.id);
  const preview = usePatternPreview(
    team.id,
    pattern?.clusterId ?? null,
    pattern?.kind ?? 'SUBSCRIPTION',
    pattern?.pattern ?? '',
    pattern !== null,
  );

  let covers: string;
  if (preview.isPending) covers = 'Counting what it matches now…';
  else if (preview.isError) covers = 'How many subscriptions and destinations it matches is unavailable right now.';
  else
    covers = `It matches ${preview.data.subscriptionMatches} subscription${preview.data.subscriptionMatches === 1 ? '' : 's'} and ${preview.data.destinationMatches} destination${preview.data.destinationMatches === 1 ? '' : 's'} on ${clusterName(preview.data.clusterId)} now.`;

  return (
    <ConfirmDialog
      opened={opened}
      onClose={onClose}
      title={pattern ? `Remove ${pattern.pattern} from ${team.name}` : 'Remove pattern'}
      tone="danger"
      pending={remove.isPending}
      confirmLabel="Remove pattern"
      consequence={
        pattern
          ? `${covers} Members of ${team.name} lose the access it gave them on their next request, unless a role grant, another pattern or a share still gives it. You can add it again.`
          : ''
      }
      onConfirm={() =>
        pattern &&
        remove.mutate(
          pattern.id,
          withNotice(
            REMOVE,
            `pattern ${pattern.pattern} from ${team.name}`,
            'The team still owns the pattern. Try again.',
            onClose,
          ),
        )
      }
    />
  );
}
