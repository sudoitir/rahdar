import { useRef, useState, type ReactNode } from 'react';
import { Button, Group, Select, Stack, Text } from '@mantine/core';
import { useForm } from '@mantine/form';
import { Link, useParams } from '@tanstack/react-router';

import { useCan } from '../../kernel/auth/useCan.ts';
import { useCluster } from '../clusters/index.ts';
import { CapabilityGate } from '../../ui/CapabilityGate.tsx';
import { ConfirmDialog } from '../../ui/ConfirmDialog.tsx';
import { DescriptionList } from '../../ui/DescriptionList.tsx';
import { ErrorState } from '../../ui/ErrorState.tsx';
import { focusFirstInvalid } from '../../ui/formErrors.ts';
import linkClasses from '../../ui/InlineLink.module.css';
import { LoadingState } from '../../ui/LoadingState.tsx';
import { Notice } from '../../ui/Notice.tsx';
import { notify, type ActionVerb } from '../../ui/notify.ts';
import { Page } from '../../ui/Page.tsx';
import { PageHeader } from '../../ui/PageHeader.tsx';
import { SchemaReview } from './SchemaReview.tsx';
import classes from './Schemas.module.css';
import {
  COMPATIBILITY_LEVELS,
  prettySchema,
  registryReason,
  schemaWriteGate,
  useDeleteSubject,
  usePreviewCompatibility,
  usePreviewDelete,
  useSchemaSubject,
  useSetCompatibility,
  type SchemaPreview,
} from './schemasApi.ts';

const SET: ActionVerb = { verb: 'Set', past: 'Set', progressive: 'Setting' };
const DELETE: ActionVerb = { verb: 'Delete', past: 'Deleted', progressive: 'Deleting' };

type Mode = 'level' | 'delete' | null;

/** A level the registry accepts, or empty. Never a stand-in the operator did not choose. */
function knownLevel(level: string | null | undefined): string {
  for (const known of COMPATIBILITY_LEVELS) {
    if (known === level) return known;
  }
  return '';
}

/**
 * The schema text, in a block that scrolls once it is long. A scrolling block with no tab stop cannot
 * be scrolled by keyboard, which fails axe's `scrollable-region-focusable`; so the block takes one.
 * It is read-only text, which is why it is a scrollable region with a name rather than a control.
 */
function SchemaBlock({ name, text }: Readonly<{ name: string; text: string | null | undefined }>) {
  return (
    <section>
      <Text size="sm" fw={600} mb="xs">
        Schema
      </Text>
      <section className={classes.schema} tabIndex={0} aria-label={`Schema of ${name}`}>
        {schemaBody(text)}
      </section>
    </section>
  );
}

function schemaBody(text: string | null | undefined): string {
  return text ? prettySchema(text) : 'This subject has no schema text.';
}

function versionWords(versions: { version: number; deleted: boolean }[] | undefined): string {
  if (!versions || versions.length === 0) return 'None';
  return versions
    .map((version) => (version.deleted ? `${version.version} (deleted)` : String(version.version)))
    .join(', ');
}

function SubjectReviews({
  shown,
  mode,
  name,
  permanent,
  levelTitle,
  deleteTitle,
  pendingLevel,
  pendingDelete,
  stale,
  applyError,
  applied,
  onClose,
  onConfirm,
  onRefreshLevel,
  onRefreshDelete,
}: Readonly<{
  shown: SchemaPreview | null;
  mode: Mode;
  name: string;
  permanent: boolean;
  levelTitle: string;
  deleteTitle: string;
  pendingLevel: boolean;
  pendingDelete: boolean;
  stale: boolean;
  applyError: { status: number } | null;
  applied: string | null;
  onClose: () => void;
  onConfirm: () => void;
  onRefreshLevel: () => void;
  onRefreshDelete: () => void;
}>) {
  const problem = applyError && !stale ? applyError : undefined;
  const result = applied ? <Text size="sm">{applied}</Text> : undefined;
  return (
    <>
      <SchemaReview
        opened={shown !== null && mode === 'level'}
        onClose={onClose}
        title={levelTitle}
        confirmLabel={levelTitle}
        tone="danger"
        preview={shown}
        pending={pendingLevel}
        stale={stale}
        error={problem}
        onConfirm={onConfirm}
        onRefresh={onRefreshLevel}
        result={result}
      />
      <SchemaReview
        opened={shown !== null && mode === 'delete'}
        onClose={onClose}
        title={deleteTitle}
        confirmLabel={deleteTitle}
        tone="danger"
        lead={softDeleteLead(permanent, name)}
        preview={shown}
        pending={pendingDelete}
        stale={stale}
        error={problem}
        onConfirm={onConfirm}
        onRefresh={onRefreshDelete}
        result={result}
      />
    </>
  );
}

/** A permanent delete's consequence is the preview's own note; a soft delete has none, so say its effect. */
function softDeleteLead(permanent: boolean, name: string): ReactNode {
  if (permanent) return undefined;
  return (
    <Text size="sm">
      This hides subject {name} from the default list. It stays readable as a deleted subject, and it cannot be undone
      from here.
    </Text>
  );
}

/** The driver's reason, with the link to where the connection is set. */
function UnavailableNotice({ clusterId, reason }: Readonly<{ clusterId: string; reason: string }>) {
  return (
    <Notice
      tone="warning"
      title="The schema registry is unavailable"
      action={
        <Link to={`/clusters/${clusterId}/settings?tab=clusters-connection`} className={linkClasses.link}>
          Connection settings
        </Link>
      }
    >
      {reason}
    </Notice>
  );
}

/**
 * One subject: its type, compatibility and versions, the schema text, and the actions that change it.
 * A soft delete hides the subject from the default list; it cannot be undone from here.
 * Permanently delete soft-deletes and then deletes the schema.
 */
export function SubjectView() {
  const { clusterId, subject } = useParams({ strict: false }) as { clusterId: string; subject: string };
  const cluster = useCluster(clusterId);
  const detail = useSchemaSubject(clusterId, subject);
  const { can, loading } = useCan();
  const previewLevel = usePreviewCompatibility(clusterId, subject);
  const setLevel = useSetCompatibility(clusterId, subject);
  const previewDelete = usePreviewDelete(clusterId, subject);
  const remove = useDeleteSubject(clusterId, subject);
  const [mode, setMode] = useState<Mode>(null);
  const started = useRef<Mode>(null);
  const [permanent, setPermanent] = useState(false);
  const [shown, setShown] = useState<SchemaPreview | null>(null);
  const [applied, setApplied] = useState<string | null>(null);

  const unavailable = registryReason(cluster.data?.capabilities.findings, detail.error);
  const gate = schemaWriteGate(can('schema:write', clusterId), loading, unavailable);
  const data = detail.data;
  const name = data?.name ?? subject;

  const form = useForm({
    initialValues: { level: '' },
    validateInputOnBlur: true,
    validate: { level: (value) => (value ? null : 'Choose a compatibility level.') },
  });

  const close = () => {
    started.current = null;
    setMode(null);
    setShown(null);
    setApplied(null);
    previewLevel.reset();
    setLevel.reset();
    previewDelete.reset();
    remove.reset();
  };

  const fetchLevel = form.onSubmit((values) => {
    started.current = 'level';
    setShown(null);
    previewDelete.reset();
    remove.reset();
    setLevel.reset();
    previewLevel.mutate(
      { level: values.level },
      {
        onSuccess: (preview) => {
          if (started.current === 'level') setShown(preview);
        },
      },
    );
  }, focusFirstInvalid(form.getInputNode));

  const fetchDelete = (next: boolean) => {
    started.current = 'delete';
    setShown(null);
    setPermanent(next);
    setMode('delete');
    previewLevel.reset();
    setLevel.reset();
    remove.reset();
    previewDelete.mutate(
      { permanent: next },
      {
        onSuccess: (preview) => {
          if (started.current === 'delete') setShown(preview);
        },
      },
    );
  };

  const openLevel = () => {
    form.setFieldValue('level', knownLevel(data?.compatibility));
    setMode('level');
  };

  const applyError = mode === 'delete' ? remove.error : setLevel.error;
  const stale = applyError?.status === 412;

  const fail = (error: { status: number; message: string }, action: ActionVerb, what: string) => {
    if (error.status === 412) return;
    notify.settle(error, {
      action,
      subject: what,
      cause: error.message,
      next: 'Preview again. Nothing was written.',
      onHeld: close,
    });
  };

  const run = () => {
    if (!shown?.etag) return;
    if (mode === 'level') {
      setLevel.mutate(
        { level: form.values.level, etag: shown.etag },
        {
          onSuccess: (level) => {
            setApplied(`Compatibility is ${level}.`);
            notify.succeeded({ action: SET, subject: `compatibility of ${name}` });
          },
          onError: (error) => fail(error, SET, `compatibility of ${name}`),
        },
      );
      return;
    }
    remove.mutate(
      { permanent, etag: shown.etag },
      {
        onSuccess: () => {
          setApplied(permanent ? `Permanently deleted subject ${name}.` : `Deleted subject ${name}.`);
          notify.succeeded({ action: DELETE, subject: `subject ${name}` });
        },
        onError: (error) => fail(error, DELETE, `subject ${name}`),
      },
    );
  };

  const deleteTitle = `${permanent ? 'Permanently delete' : 'Delete'} subject ${name}`;
  const levelTitle = `Set compatibility of ${name}`;

  return (
    <Page>
      <PageHeader
        title={name}
        description="A subject holds one schema type, a compatibility level and a version for each registration."
        actions={
          <Link to={`/clusters/${clusterId}/kafka/schemas`} className={linkClasses.link}>
            Schemas
          </Link>
        }
      />
      {unavailable ? <UnavailableNotice clusterId={clusterId} reason={unavailable} /> : null}
      {detail.isPending ? <LoadingState label="Loading the subject" blockSize="16rem" /> : null}
      {detail.isError && !unavailable ? (
        <ErrorState error={detail.error} onRetry={() => void detail.refetch()} />
      ) : null}
      {data ? (
        <Stack gap="md">
          <DescriptionList
            label="Subject"
            items={[
              { term: 'Type', value: data.type ?? 'Unknown' },
              { term: 'Compatibility', value: data.compatibility ?? data.compatibilityNote ?? 'Registry default' },
              { term: 'Versions', value: versionWords(data.versions) },
            ]}
          />
          <SchemaBlock name={name} text={data.schemaText} />
          <Group>
            <CapabilityGate verdict={gate} what="setting compatibility">
              <Button
                size="xs"
                variant="default"
                disabled={gate.kind === 'blocked' || previewDelete.isPending}
                onClick={openLevel}
              >
                Set compatibility
              </Button>
            </CapabilityGate>
            <CapabilityGate verdict={gate} what="deleting this subject">
              <Button
                size="xs"
                variant="default"
                disabled={gate.kind === 'blocked' || previewLevel.isPending || (previewDelete.isPending && permanent)}
                loading={previewDelete.isPending && !permanent}
                onClick={() => fetchDelete(false)}
              >
                Delete subject
              </Button>
            </CapabilityGate>
            <CapabilityGate verdict={gate} what="permanently deleting this subject">
              <Button
                size="xs"
                color="signal"
                disabled={gate.kind === 'blocked' || previewLevel.isPending || (previewDelete.isPending && !permanent)}
                loading={previewDelete.isPending && permanent}
                onClick={() => fetchDelete(true)}
              >
                Permanently delete
              </Button>
            </CapabilityGate>
          </Group>
          {previewDelete.isError ? <ErrorState variant="inline" error={previewDelete.error} /> : null}
        </Stack>
      ) : null}

      <ConfirmDialog
        opened={mode === 'level' && !shown}
        onClose={close}
        title={levelTitle}
        confirmLabel="Preview compatibility"
        pending={previewLevel.isPending}
        onConfirm={() => fetchLevel()}
        consequence={
          <form noValidate onSubmit={fetchLevel}>
            <Stack gap="sm">
              <Select
                label="Compatibility"
                data={[...COMPATIBILITY_LEVELS]}
                allowDeselect={false}
                {...form.getInputProps('level')}
              />
              <Text size="sm">Nothing is written until you confirm the preview.</Text>
              {previewLevel.isError ? <ErrorState variant="inline" error={previewLevel.error} /> : null}
            </Stack>
          </form>
        }
      />
      <SubjectReviews
        shown={shown}
        mode={mode}
        name={name}
        permanent={permanent}
        levelTitle={levelTitle}
        deleteTitle={deleteTitle}
        pendingLevel={setLevel.isPending}
        pendingDelete={remove.isPending}
        stale={stale}
        applyError={applyError}
        applied={applied}
        onClose={close}
        onConfirm={run}
        onRefreshLevel={() => fetchLevel()}
        onRefreshDelete={() => fetchDelete(permanent)}
      />
    </Page>
  );
}
