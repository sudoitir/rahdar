import { useState } from 'react';
import { Select, Stack, Text, Textarea, TextInput, UnstyledButton } from '@mantine/core';
import { IconChevronRight } from '@tabler/icons-react';
import { useForm } from '@mantine/form';

import { ConfirmDialog } from '../../ui/ConfirmDialog.tsx';
import { ErrorState } from '../../ui/ErrorState.tsx';
import { focusFirstInvalid } from '../../ui/formErrors.ts';
import { notify, type ActionVerb } from '../../ui/notify.ts';
import { SchemaReview } from './SchemaReview.tsx';
import classes from './Schemas.module.css';
import {
  SCHEMA_TYPES,
  SCHEMA_TYPE_LABEL,
  usePreviewRegister,
  useRegister,
  type SchemaPreview,
  type SchemaVersion,
} from './schemasApi.ts';

const REGISTER: ActionVerb = { verb: 'Register', past: 'Registered', progressive: 'Registering' };

/**
 * Subject, type and schema. Submitting fetches the preview and shows it. It never applies:
 * registering is a separate confirm on that preview.
 */
export function RegisterSchemaDialog({
  clusterId,
  opened,
  onClose,
}: Readonly<{ clusterId: string; opened: boolean; onClose: () => void }>) {
  const preview = usePreviewRegister(clusterId);
  const register = useRegister(clusterId);
  const [shown, setShown] = useState<SchemaPreview | null>(null);
  const [applied, setApplied] = useState<SchemaVersion | null>(null);
  // Closed by default: the form identifies the subject and the type, and the schema text follows.
  const [expanded, setExpanded] = useState(false);

  const form = useForm({
    initialValues: { subject: '', type: 'AVRO', schema: '' },
    validateInputOnBlur: true,
    validate: {
      subject: (value) => (value.trim() ? null : 'Enter the subject name.'),
      schema: (value) => (value.trim() ? null : 'Enter the schema text.'),
    },
  });

  const close = () => {
    setShown(null);
    setApplied(null);
    preview.reset();
    register.reset();
    form.reset();
    onClose();
  };

  const fetchPreview = form.onSubmit(
    (values) => {
      register.reset();
      preview.mutate(
        { subject: values.subject.trim(), type: values.type, schema: values.schema },
        { onSuccess: setShown },
      );
    },
    // A rejected submit puts the message on its field, so the field has to be showing. The schema text is
    // closed by default, and a message inside a collapsed window is one the operator cannot read.
    (errors) => {
      if (errors.schema) setExpanded(true);
      focusFirstInvalid(form.getInputNode)(errors);
    },
  );

  const run = () => {
    if (!shown?.etag) return;
    register.mutate(
      { subject: form.values.subject.trim(), type: form.values.type, schema: form.values.schema, etag: shown.etag },
      {
        onSuccess: (version) => {
          setApplied(version);
          notify.succeeded({ action: REGISTER, subject: `schema ${form.values.subject.trim()}` });
        },
        onError: (error) => {
          if (error.status === 412) return;
          notify.settle(error, {
            action: REGISTER,
            subject: `schema ${form.values.subject.trim()}`,
            cause: error.message,
            next: 'Preview again, then register.',
            onHeld: close,
          });
        },
      },
    );
  };

  const subject = form.values.subject.trim();
  return (
    <>
      <ConfirmDialog
        opened={opened && !shown}
        onClose={close}
        title="Register a schema"
        confirmLabel="Preview registration"
        pending={preview.isPending}
        onConfirm={() => fetchPreview()}
        consequence={
          <form noValidate onSubmit={fetchPreview}>
            <Stack gap="sm">
              <TextInput label="Subject" {...form.getInputProps('subject')} />
              <Select
                label="Type"
                data={SCHEMA_TYPES.map((type) => ({ value: type, label: SCHEMA_TYPE_LABEL[type] }))}
                allowDeselect={false}
                {...form.getInputProps('type')}
              />
              <Stack gap={4}>
                <UnstyledButton
                  onClick={() => setExpanded((open) => !open)}
                  aria-expanded={expanded}
                  aria-controls="register-schema-text"
                  className={classes.disclosure}
                >
                  <IconChevronRight
                    size={14}
                    className={`${classes.chevron} ${expanded ? classes.chevronOpen : ''}`}
                    aria-hidden
                  />
                  <Text size="sm" fw={500}>
                    Schema text
                  </Text>
                </UnstyledButton>
                {expanded ? (
                  <Textarea
                    id="register-schema-text"
                    label="Schema"
                    description="The schema text. For example, an Avro record."
                    autosize
                    minRows={6}
                    spellCheck={false}
                    {...form.getInputProps('schema')}
                  />
                ) : (
                  <Text size="xs" c={form.errors.schema ? 'var(--as-danger)' : 'dimmed'}>
                    {form.errors.schema
                      ? form.errors.schema
                      : form.values.schema.trim()
                        ? `${form.values.schema.trim().length} characters entered.`
                        : 'Not entered yet.'}
                  </Text>
                )}
              </Stack>
              {preview.isError ? <ErrorState variant="inline" error={preview.error} /> : null}
            </Stack>
          </form>
        }
      />
      <SchemaReview
        opened={shown !== null}
        onClose={close}
        title={`Register schema ${subject}`}
        confirmLabel={`Register schema ${subject}`}
        tone="danger"
        preview={shown}
        pending={register.isPending}
        stale={register.error?.status === 412}
        error={register.isError && register.error.status !== 412 ? register.error : undefined}
        onConfirm={run}
        onRefresh={() => fetchPreview()}
        result={
          applied ? (
            <Text size="sm">
              Registered version {applied.version}
              {applied.id == null ? '' : ` (id ${applied.id})`}.
            </Text>
          ) : undefined
        }
      />
    </>
  );
}
