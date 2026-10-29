import { useState } from 'react';
import { ActionIcon, Button, Collapse, PasswordInput, Stack, Switch, Text, TextInput } from '@mantine/core';
import { useForm } from '@mantine/form';
import { IconChevronRight, IconX } from '@tabler/icons-react';

import { useUnsavedSection } from '../../kernel/shell/SectionNav.tsx';
import { ConfirmDialog } from '../../ui/ConfirmDialog.tsx';
import { ErrorState } from '../../ui/ErrorState.tsx';
import { FieldRow } from '../../ui/FieldRow.tsx';
import { focusFirstInvalid } from '../../ui/formErrors.ts';
import { notify, type ActionVerb } from '../../ui/notify.ts';
import { useCheckConnectionEdit, useUpdateConnection, type ClusterDetail, type UpdateClusterRequest } from './api.ts';
import classes from './Clusters.module.css';
import { ConnectionCheckPanel } from './ConnectionCheckPanel.tsx';
import { checkPassed, rejectedAccounts } from './connectionCheck.ts';
import { hostPort, isValidPattern } from './pattern.ts';
import { normaliseSeeds, seedRow, type SeedRow } from './normaliseSeeds.ts';

const SAVE: ActionVerb = { verb: 'Save', past: 'Saved', progressive: 'Saving' };

interface Fields {
  name: string;
  description: string;
  seed: string;
  moreSeeds: SeedRow[];
  pattern: string;
  tlsBundle: string;
  username: string;
  password: string;
  separateCore: boolean;
  coreUsername: string;
  corePassword: string;
}

type Connection = NonNullable<ClusterDetail['connection']>;

/** The form's starting values: what the cluster is connected with now, and never a password. */
function initialFields(cluster: ClusterDetail, connection: Connection): Fields {
  const [seed = '', ...moreSeeds] = connection.seedUrls;
  return {
    name: cluster.name,
    description: cluster.description ?? '',
    seed,
    moreSeeds: moreSeeds.map(seedRow),
    pattern: connection.managementUrlPattern ?? '',
    tlsBundle: connection.tlsBundle ?? '',
    username: connection.managementUsername ?? '',
    password: '',
    separateCore: Boolean(connection.coreUsername),
    coreUsername: connection.coreUsername ?? '',
    corePassword: '',
  };
}

/** The seeds typed, normalised, and what is wrong with them. */
function seedsOf(f: Fields): { urls: string[]; problems: Record<string, string> } {
  const urls: string[] = [];
  const problems: Record<string, string> = {};
  [f.seed, ...f.moreSeeds.map((row) => row.url)].forEach((value, i) => {
    const path = i === 0 ? 'seed' : `moreSeeds.${i - 1}.url`;
    const [first] = normaliseSeeds(value);
    if (first?.url) urls.push(first.url);
    else if (first) problems[path] = `Couldn't make sense of: ${first.original}`;
  });
  return { urls, problems };
}

/** The edit these values make, ready to check or save. A password left empty keeps the stored one. */
function requestOf(f: Fields, connection: Connection): UpdateClusterRequest {
  return {
    name: f.name.trim(),
    description: f.description,
    connection: {
      system: 'artemis',
      seedUrls: seedsOf(f).urls,
      managementUrlPattern: f.pattern || undefined,
      // An empty bundle clears the stored one; leaving it out would keep it.
      tlsBundle: f.tlsBundle || (connection.tlsBundle ? '' : undefined),
      management: { username: f.username, password: f.password },
      core: f.separateCore ? { username: f.coreUsername, password: f.corePassword } : null,
    },
  };
}

const AGAIN = (account: string) => `Enter the ${account} password again to check new hosts.`;

/**
 * Which passwords the edit must carry again. A stored password is never sent to an address the cluster has
 * not used: pointing the connection at a new seed, pattern or TLS bundle, or changing who signs in, asks for
 * it, as the server does.
 */
function passwordsRequired(f: Fields, connection: Connection): { management: boolean; core: boolean } {
  const known = new Set(connection.seedUrls.map(hostPort));
  const newHosts =
    seedsOf(f).urls.some((url) => !known.has(hostPort(url))) ||
    f.pattern !== (connection.managementUrlPattern ?? '') ||
    f.tlsBundle !== (connection.tlsBundle ?? '');
  const coreName = connection.coreUsername ?? '';
  return {
    management: newHosts || f.username !== (connection.managementUsername ?? ''),
    core: f.separateCore && (newHosts || f.coreUsername !== coreName),
  };
}

/** Whether saving changes who Rahdar signs in as, which can lock it out of the brokers. */
function changesCredentials(f: Fields, connection: Connection): boolean {
  const core = connection.coreUsername ?? '';
  if (f.username !== (connection.managementUsername ?? '') || f.password !== '') return true;
  if (f.separateCore !== Boolean(core)) return true;
  return f.separateCore && (f.coreUsername !== core || f.corePassword !== '');
}

/**
 * The connection of a registered cluster, edited in one place (ADR-0176): the seeds and the pattern that
 * give every node's management URL, the TLS bundle and both accounts. A change is
 * checked first, node by node, with nothing saved; saving is offered once the check of exactly these
 * values passed. Changing an account asks for the cluster's name, because a wrong one locks Rahdar out.
 */
export function ConnectionForm({ cluster, connection }: Readonly<{ cluster: ClusterDetail; connection: Connection }>) {
  const check = useCheckConnectionEdit(cluster.id);
  const update = useUpdateConnection(cluster.id);
  const [advancedOpen, setAdvancedOpen] = useState(false);
  const [checkedFor, setCheckedFor] = useState<string | null>(null);
  const [confirming, setConfirming] = useState(false);

  const form = useForm<Fields>({
    initialValues: initialFields(cluster, connection),
    validateInputOnBlur: true,
    validate: (values) => ({
      ...seedsOf(values).problems,
      ...(values.name.trim() ? {} : { name: 'Enter a name.' }),
      ...(values.username.trim() ? {} : { username: 'Enter the management account name.' }),
      ...(values.separateCore && !values.coreUsername.trim() ? { coreUsername: 'Enter the Core account name.' } : {}),
      ...(values.pattern && !isValidPattern(values.pattern)
        ? { pattern: 'Use http(s)://{host}[:port][/path], with {host} as the whole host.' }
        : {}),
      ...(passwordsRequired(values, connection).management && !values.password
        ? { password: AGAIN('management') }
        : {}),
      ...(passwordsRequired(values, connection).core && !values.corePassword ? { corePassword: AGAIN('Core') } : {}),
    }),
  });
  // The settings tabs unmount this form on a switch: while it holds edits, its tab asks before letting go.
  useUnsavedSection(form.isDirty());
  const f = form.values;
  const request = requestOf(f, connection);
  const signature = JSON.stringify(request);
  const credentials = changesCredentials(f, connection);
  const checked = check.isSuccess && checkedFor === signature ? check.data : null;
  const found = checked ? rejectedAccounts(checked) : [];
  // The cluster being edited is not "another cluster" to itself.
  const passed =
    checked !== null &&
    found.length === 0 &&
    checkPassed({
      ...checked,
      alreadyRegistered: checked.alreadyRegistered?.id === cluster.id ? null : checked.alreadyRegistered,
    });

  function reason(): string | null {
    if (passed) return null;
    if (check.isPending) return 'Checking the connection…';
    if (found.length > 0) return 'A broker rejected an account. Correct it, then check again.';
    if (check.isSuccess) return 'Check again: the values changed since the last check.';
    return 'Check the connection before saving.';
  }
  const blocked = reason();

  const runCheck = form.onSubmit(
    () => {
      update.reset();
      setCheckedFor(signature);
      check.mutate(request);
    },
    (errors) => {
      if (errors.pattern) setAdvancedOpen(true);
      focusFirstInvalid(form.getInputNode)(errors);
    },
  );

  function save() {
    update.mutate(request, {
      onSuccess: () => {
        notify.succeeded({ action: SAVE, subject: `the connection of ${cluster.name}` });
        form.setFieldValue('password', '');
        form.setFieldValue('corePassword', '');
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

        <TextInput
          label="Broker management URL"
          description="The addresses you gave. Rahdar derives the rest from the pattern."
          {...form.getInputProps('seed')}
          size="sm"
        />
        {f.moreSeeds.map((row, i) => (
          <FieldRow key={row.key}>
            <TextInput
              label={`Another management URL (${i + 2})`}
              {...form.getInputProps(`moreSeeds.${i}.url`)}
              size="sm"
            />
            <ActionIcon
              variant="subtle"
              aria-label={`Remove management URL ${i + 2}`}
              onClick={() => form.removeListItem('moreSeeds', i)}
            >
              <IconX size={16} aria-hidden />
            </ActionIcon>
          </FieldRow>
        ))}
        <Button
          variant="subtle"
          size="compact-xs"
          className={classes.start}
          onClick={() => form.insertListItem('moreSeeds', seedRow())}
        >
          Add another seed
        </Button>
        <Button
          variant="subtle"
          size="compact-xs"
          className={classes.start}
          onClick={() => setAdvancedOpen((o) => !o)}
          aria-expanded={advancedOpen}
          leftSection={
            <IconChevronRight size={14} aria-hidden className={classes.chevron} data-open={advancedOpen || undefined} />
          }
        >
          Advanced: management URL pattern and TLS
        </Button>
        <Collapse expanded={advancedOpen}>
          <Stack gap="xs">
            <TextInput
              label="Management URL pattern"
              description="Each node's host replaces {host}. A broker answering there is accepted only when it is that node."
              {...form.getInputProps('pattern')}
              size="sm"
            />
            <TextInput
              label="TLS bundle"
              description="Optional. Name of a Spring SSL bundle for an HTTPS broker."
              {...form.getInputProps('tlsBundle')}
              size="sm"
            />
          </Stack>
        </Collapse>
      </div>

      <div className={classes.formGroup}>
        <Text size="sm" fw={600}>
          Management account
        </Text>
        <FieldRow>
          <TextInput label="Username" autoComplete="off" {...form.getInputProps('username')} size="sm" />
          <PasswordInput
            label="Password"
            description="Leave empty to keep the stored password."
            autoComplete="new-password"
            {...form.getInputProps('password')}
            size="sm"
          />
        </FieldRow>

        <Switch
          label="Use a separate Core account"
          description="Off: Core connections use the management account."
          {...form.getInputProps('separateCore', { type: 'checkbox' })}
          size="sm"
        />
        {f.separateCore ? (
          <FieldRow>
            <TextInput label="Core username" autoComplete="off" {...form.getInputProps('coreUsername')} size="sm" />
            <PasswordInput
              label="Core password"
              description="Leave empty to keep the stored password."
              autoComplete="new-password"
              {...form.getInputProps('corePassword')}
              size="sm"
            />
          </FieldRow>
        ) : null}
      </div>

      <div aria-live="polite" className={classes.form}>
        {check.isError ? <ErrorState variant="inline" error={check.error} /> : null}
        {checked ? <ConnectionCheckPanel check={checked} selfId={cluster.id} /> : null}
        {found.map((line) => (
          <Text key={line} size="sm">
            {line}
          </Text>
        ))}
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
              This changes the account Rahdar signs in to <strong>{cluster.name}</strong> with. The brokers accepted it
              in the check; if they stop accepting it later, the cluster reads as rejected until it is corrected.
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
