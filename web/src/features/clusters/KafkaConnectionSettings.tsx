import { useState } from 'react';
import { Button, Stack, Text, TextInput } from '@mantine/core';
import { useForm } from '@mantine/form';

import { useUnsavedSection } from '../../kernel/shell/SectionNav.tsx';
import { ConfirmDialog } from '../../ui/ConfirmDialog.tsx';
import { ErrorState } from '../../ui/ErrorState.tsx';
import { focusFirstInvalid } from '../../ui/formErrors.ts';
import { notify, type ActionVerb } from '../../ui/notify.ts';
import {
  useCheckKafkaConnectionEdit,
  useUpdateConnection,
  type ClusterDetail,
  type KafkaConnection,
  type KafkaUpdateRequest,
} from './api.ts';
import classes from './Clusters.module.css';
import { kafkaConnection, kafkaFieldsOf, kafkaProblems, secretRequired, type KafkaFields } from './kafkaConnection.ts';
import { kafkaCheckPassed } from './kafkaCheck.ts';
import { KafkaCheckPanel } from './KafkaCheckPanel.tsx';
import { KafkaConnectionFields } from './KafkaConnectionFields.tsx';

const SAVE: ActionVerb = { verb: 'Save', past: 'Saved', progressive: 'Saving' };

interface Fields extends KafkaFields {
  name: string;
  description: string;
}

/**
 * The connection of a registered Kafka cluster, edited with the fields registration uses. A change is
 * checked first, broker by broker, with nothing saved; saving is offered once the check of exactly these
 * values passed. A secret left empty keeps the stored one, except where the connection now goes somewhere
 * new or as someone else: then it is asked for again, because a stored secret is never sent there.
 */
export function KafkaConnectionForm({
  cluster,
  stored,
}: Readonly<{ cluster: ClusterDetail; stored: KafkaConnection }>) {
  const check = useCheckKafkaConnectionEdit(cluster.id);
  const update = useUpdateConnection(cluster.id);
  const [checkedFor, setCheckedFor] = useState<string | null>(null);
  const [confirming, setConfirming] = useState(false);

  const form = useForm<Fields>({
    initialValues: { ...kafkaFieldsOf(stored), name: cluster.name, description: cluster.description ?? '' },
    validateInputOnBlur: true,
    validate: (values) => ({
      ...kafkaProblems(values, stored),
      ...(values.name.trim() ? {} : { name: 'Enter a name.' }),
    }),
  });
  useUnsavedSection(form.isDirty());
  const f = form.values;

  const request: KafkaUpdateRequest = {
    name: f.name.trim(),
    description: f.description,
    connection: kafkaConnection(f),
  };
  const signature = JSON.stringify(request);
  // The cluster being edited is not "another registered cluster" to itself.
  const result = check.data && {
    ...check.data,
    alreadyRegistered: check.data.alreadyRegistered?.id === cluster.id ? null : check.data.alreadyRegistered,
  };
  const checkedThis = checkedFor === signature;
  const passed = checkedThis && result !== undefined && kafkaCheckPassed(result);
  const credentials = secretRequired(f, stored) || f.password !== '' || f.token !== '';

  function reason(): string | null {
    if (passed) return null;
    if (check.isPending) return 'Checking the connection…';
    if (result && checkedThis) return 'The check did not pass. Fix what it reports above, then check again.';
    if (result) return 'Check again: the values changed since the last check.';
    return 'Check the connection before saving.';
  }
  const blocked = reason();

  const runCheck = form.onSubmit(() => {
    update.reset();
    setCheckedFor(signature);
    check.mutate(request);
  }, focusFirstInvalid(form.getInputNode));

  function save() {
    update.mutate(request, {
      onSuccess: () => {
        notify.succeeded({ action: SAVE, subject: `the connection of ${cluster.name}` });
        form.setFieldValue('password', '');
        form.setFieldValue('token', '');
        form.resetDirty();
        check.reset();
        setCheckedFor(null);
        setConfirming(false);
      },
    });
  }

  return (
    <form className={classes.settingsForm} noValidate onSubmit={runCheck}>
      <div className={classes.formGroup}>
        <TextInput label="Name" {...form.getInputProps('name')} size="sm" />
        <TextInput label="Description" {...form.getInputProps('description')} size="sm" />
        <KafkaConnectionFields form={form} editing />
      </div>

      <div aria-live="polite" className={classes.form}>
        {check.isError ? <ErrorState variant="inline" error={check.error} /> : null}
        {result && checkedThis ? <KafkaCheckPanel check={result} /> : null}
        {update.isError && !confirming ? <ErrorState variant="inline" error={update.error} /> : null}
      </div>

      <div className={`${classes.actions} ${classes.stickyActions}`}>
        {blocked ? (
          <Text size="xs" c="dimmed" className={classes.reason}>
            {blocked}
          </Text>
        ) : null}
        <Button type="submit" size="sm" variant="default" loading={check.isPending}>
          Check connection
        </Button>
        <Button
          size="sm"
          disabled={!passed}
          loading={update.isPending && !confirming}
          onClick={() => (credentials ? setConfirming(true) : save())}
        >
          Save connection
        </Button>
      </div>

      <ConfirmDialog
        opened={confirming}
        onClose={() => setConfirming(false)}
        title="Save the connection"
        consequence={
          <Stack gap="xs">
            <Text size="sm">
              This changes where or as whom Rahdar connects to <strong>{cluster.name}</strong>. The brokers accepted it
              in the check; if they stop accepting it later, the cluster reads as unreachable until it is corrected.
            </Text>
            {update.isError ? <ErrorState variant="inline" error={update.error} /> : null}
          </Stack>
        }
        confirmLabel="Save connection"
        tone="danger"
        pending={update.isPending}
        onConfirm={save}
      />
    </form>
  );
}
