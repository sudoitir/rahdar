import { useState } from 'react';
import { Button, Select, Stack, Text } from '@mantine/core';
import { useForm } from '@mantine/form';

import { useCan } from '../../kernel/auth/useCan.ts';
import { useCluster } from '../clusters/index.ts';
import { CapabilityGate } from '../../ui/CapabilityGate.tsx';
import { DescriptionList } from '../../ui/DescriptionList.tsx';
import { ErrorState } from '../../ui/ErrorState.tsx';
import { FieldRow } from '../../ui/FieldRow.tsx';
import { focusFirstInvalid } from '../../ui/formErrors.ts';
import { notify, type ActionVerb } from '../../ui/notify.ts';
import { Section } from '../../ui/Section.tsx';
import { SchemaReview } from './SchemaReview.tsx';
import {
  COMPATIBILITY_LEVELS,
  registryReason,
  schemaWriteGate,
  usePreviewDefaultCompatibility,
  useSetDefaultCompatibility,
  type SchemaPreview,
} from './schemasApi.ts';

const SET: ActionVerb = { verb: 'Set', past: 'Set', progressive: 'Setting' };

/**
 * The registry default, which a subject without its own level follows. There is no read of the current
 * level on this page: the review's Before line is that level, and nothing is written until it is confirmed.
 */
export function RegistryDefault({ clusterId }: Readonly<{ clusterId: string }>) {
  const cluster = useCluster(clusterId);
  const preview = usePreviewDefaultCompatibility(clusterId);
  const apply = useSetDefaultCompatibility(clusterId);
  const { can, loading } = useCan();
  const [shown, setShown] = useState<SchemaPreview | null>(null);
  const [applied, setApplied] = useState<string | null>(null);

  const unavailable = registryReason(cluster.data?.capabilities.findings, preview.error);
  const gate = schemaWriteGate(can('schema:write', clusterId), loading, unavailable);

  const form = useForm({
    initialValues: { level: 'BACKWARD' },
    validateInputOnBlur: true,
    validate: { level: (value) => (value ? null : 'Choose a compatibility level.') },
  });

  const close = () => {
    setShown(null);
    preview.reset();
    apply.reset();
  };

  const fetchPreview = form.onSubmit((values) => {
    apply.reset();
    preview.mutate({ level: values.level }, { onSuccess: setShown });
  }, focusFirstInvalid(form.getInputNode));

  const run = () => {
    if (!shown?.etag) return;
    apply.mutate(
      { level: form.values.level, etag: shown.etag },
      {
        onSuccess: (level) => {
          setApplied(level);
          notify.succeeded({ action: SET, subject: 'the registry default compatibility' });
        },
        onError: (error) => {
          if (error.status === 412) return;
          notify.settle(error, {
            action: SET,
            subject: 'the registry default compatibility',
            cause: error.message,
            next: 'Preview again. Nothing was written.',
            onHeld: close,
          });
        },
      },
    );
  };

  const stale = apply.error?.status === 412;
  return (
    <Section
      title="Registry default compatibility"
      description="Subjects without their own level follow this. The review shows the current level before anything is written."
    >
      <Stack gap="sm">
        {applied ? (
          <DescriptionList label="Registry default compatibility" items={[{ term: 'Current', value: applied }]} />
        ) : null}
        <form noValidate onSubmit={fetchPreview}>
          <FieldRow>
            <Select
              label="New level"
              data={[...COMPATIBILITY_LEVELS]}
              allowDeselect={false}
              {...form.getInputProps('level')}
            />
            <CapabilityGate verdict={gate} what="setting the registry default">
              <Button
                type="submit"
                variant="default"
                size="xs"
                disabled={gate.kind === 'blocked'}
                loading={preview.isPending}
              >
                Review registry default
              </Button>
            </CapabilityGate>
          </FieldRow>
        </form>
        {preview.isError && !unavailable ? <ErrorState variant="inline" error={preview.error} /> : null}
      </Stack>
      <SchemaReview
        opened={shown !== null}
        onClose={close}
        title="Set registry default compatibility"
        confirmLabel="Set registry default compatibility"
        tone="danger"
        preview={shown}
        pending={apply.isPending}
        stale={stale}
        error={apply.isError && !stale ? apply.error : undefined}
        onConfirm={run}
        onRefresh={() => fetchPreview()}
        result={applied ? <Text size="sm">Registry default is {applied}.</Text> : undefined}
      />
    </Section>
  );
}
