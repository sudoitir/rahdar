import { useEffect, useState } from 'react';
import { flushSync } from 'react-dom';
import { ActionIcon, Button, Collapse, PasswordInput, Stack, Switch, Text, TextInput } from '@mantine/core';
import { useForm } from '@mantine/form';

import { pairProblems, useRevalidatePairs } from '../../ui/formPairs.ts';
import { IconChevronRight, IconX } from '@tabler/icons-react';
import { useNavigate } from '@tanstack/react-router';

import {
  alreadyRegistered,
  useCheckConnection,
  useRegisterCluster,
  type AdoptionPreviewView,
  type RegisterClusterRequest,
  type TopologyView,
} from './api.ts';
import { useSlot } from '../../kernel/slots.ts';
import { FieldRow } from '../../ui/FieldRow.tsx';
import { focusFirstInvalid } from '../../ui/formErrors.ts';
import { Notice } from '../../ui/Notice.tsx';
import { notify, type ActionVerb } from '../../ui/notify.ts';
import { CapabilityLedger } from './CapabilityLedger.tsx';
import { ClusterIdentityFields } from './ClusterIdentityFields.tsx';
import { RegistrationFailure } from './RegistrationFailure.tsx';
import classes from './Clusters.module.css';
import { adoptionCountsWords, defaultPattern } from './connectionWords.ts';
import { NodeProbeTable } from './NodeProbeTable.tsx';
import { normaliseSeeds, seedRow, type SeedRow } from './normaliseSeeds.ts';
import { isValidPattern } from './pattern.ts';

const EXAMPLE = 'http://broker-1:8161/console/jolokia';

const REGISTER: ActionVerb = { verb: 'Register', past: 'Registered', progressive: 'Registering' };

interface Fields {
  seed: string;
  moreSeeds: SeedRow[];
  name: string;
  description: string;
  username: string;
  password: string;
  coreUsername: string;
  corePassword: string;
  environmentId: string;
  tlsBundle: string;
  /** What the operator typed in the pattern field; empty while the field follows the first seed. */
  pattern: string;
}

const EMPTY: Fields = {
  seed: '',
  moreSeeds: [],
  name: '',
  description: '',
  username: '',
  password: '',
  coreUsername: '',
  corePassword: '',
  environmentId: '',
  tlsBundle: '',
  pattern: '',
};

/** What is wrong with the management URLs typed in one box, or null: the first token that cannot be used. */
function seedProblem(value: string): string | null {
  for (const seed of normaliseSeeds(value)) {
    if (seed.url === null) return `Couldn't make sense of: ${seed.original}`;
    if (new URL(seed.url).username) return 'Put the account in the Management account fields, not in the URL.';
  }
  return null;
}

/** Why registering is not offered yet. */
function blockedReason(
  valid: boolean,
  check: ReturnType<typeof useCheckConnection>,
  checkPassed: boolean,
  stale: boolean,
  registeredAlready: boolean,
): string | null {
  if (!valid) return 'Fill in the fields marked above, then check the connection.';
  if (check.isPending) return 'Checking the connection…';
  if (registeredAlready) return 'These brokers are registered already, so there is nothing to register.';
  if (checkPassed) return null;
  if (stale) return 'Check the connection again — the details changed since the last check.';
  if (check.isError) return 'The check failed. Fix what it reports above, then check again.';
  return 'Check the connection first.';
}

/** What the connection check found, or why it failed, and why registering failed if it did. */
function CheckOutcome({
  check,
  register,
}: Readonly<{
  check: ReturnType<typeof useCheckConnection>;
  register: ReturnType<typeof useRegisterCluster>;
}>) {
  const preview = check.data?.system === 'artemis' ? check.data : null;
  const nodes = preview?.nodes.length ?? 0;
  return (
    <div aria-live="polite" className={classes.form}>
      {preview ? (
        <Text size="sm" c="dimmed">
          {`Connected. Found ${nodes} node${nodes === 1 ? '' : 's'}.`}
        </Text>
      ) : null}
      {preview ? <NodeProbeTable nodes={preview.nodes} label="Nodes found by the check" /> : null}
      {preview ? <UntestedVersions topology={preview.topology} /> : null}
      {check.isError ? <RegistrationFailure error={check.error} /> : null}
      {register.isError ? <RegistrationFailure error={register.error} /> : null}
    </div>
  );
}

/**
 * What registering can also do: save what the brokers run as the cluster's first declared revision
 * (ADR-0176). On when the nodes agree; off, with each disagreement listed, when they do not, because
 * declaring one node's value over another's is a choice for the operator to make.
 */
function AdoptionChoice({
  adoption,
  adopt,
  onChange,
}: Readonly<{ adoption: AdoptionPreviewView; adopt: boolean; onChange: (value: boolean) => void }>) {
  const disagree = adoption.disagreements.length > 0;
  return (
    <Stack gap="xs">
      <Switch
        label="Adopt what the brokers run as the cluster's first configuration"
        description={`Declares ${adoptionCountsWords(adoption.counts)} as revision 1, so the cluster starts in sync and every later change shows as a difference from it. Nothing is written to a broker.`}
        checked={adopt}
        onChange={(event) => onChange(event.currentTarget.checked)}
      />
      {disagree ? (
        <Notice title={`The nodes disagree on ${adoption.disagreements.length} item(s)`} tone="warning">
          <Stack gap="xs">
            <Text size="sm">
              Adopting keeps the first node&apos;s value for each, so it is off until you choose it.
            </Text>
            {adoption.disagreements.map((d) => (
              <Text key={d} size="sm">
                {d}
              </Text>
            ))}
          </Stack>
        </Notice>
      ) : null}
    </Stack>
  );
}

/** Every management URL typed, normalised, and what is wrong with the boxes that hold them. */
function seedsOf(f: Fields): { urls: string[]; problems: Record<string, string> } {
  const urls: string[] = [];
  const problems: Record<string, string> = {};
  [f.seed, ...f.moreSeeds.map((row) => row.url)].forEach((value, i) => {
    const path = i === 0 ? 'seed' : `moreSeeds.${i - 1}.url`;
    const issue = seedProblem(value);
    if (issue) problems[path] = issue;
    for (const seed of normaliseSeeds(value)) {
      if (seed.url && !urls.includes(seed.url)) urls.push(seed.url);
    }
  });
  if (urls.length === 0 && !problems.seed) problems.seed = 'Add at least one management URL.';
  return { urls, problems };
}

const PAIRS: [string, string][] = [
  ['username', 'password'],
  ['coreUsername', 'corePassword'],
];

/** What is wrong with the values as they stand, by field: the form can be sent only when this is empty. */
function problemsOf(values: Fields): Record<string, string> {
  const problems = seedsOf(values).problems;
  if (values.pattern && !isValidPattern(values.pattern)) {
    problems.pattern = 'Use http(s)://{host}[:port][/path], with {host} as the whole host.';
  }
  return {
    ...problems,
    ...pairProblems(
      {
        name: 'username',
        value: values.username,
        missing: 'Enter the username for this password, or clear the password.',
      },
      {
        name: 'password',
        value: values.password,
        missing: 'Enter the password for this username, or clear the username.',
      },
    ),
    ...pairProblems(
      {
        name: 'coreUsername',
        value: values.coreUsername,
        missing: 'Enter the Core username for this password, or clear the password.',
      },
      {
        name: 'corePassword',
        value: values.corePassword,
        missing: 'Enter the Core password for this username, or clear the username.',
      },
    ),
  };
}

/**
 * The Artemis registration form: management URLs, accounts, the connection check and registering. The page
 * around it is the one for every system.
 */
export function ArtemisRegistrationForm() {
  const [advancedOpen, setAdvancedOpen] = useState(false);
  const [checkedInputs, setCheckedInputs] = useState<string | null>(null);
  const [adoptChoice, setAdoptChoice] = useState<boolean | null>(null);

  const check = useCheckConnection();
  const register = useRegisterCluster();
  const navigate = useNavigate();

  const form = useForm<Fields>({
    initialValues: EMPTY,
    validateInputOnBlur: true,
    validate: problemsOf,
  });
  const f = form.values;
  useRevalidatePairs(form, PAIRS);

  const { urls: seedList } = seedsOf(f);
  const rewritten = [f.seed, ...f.moreSeeds.map((row) => row.url)]
    .flatMap((value) => normaliseSeeds(value))
    .filter((s) => s.url && s.url !== s.original);
  const pattern = f.pattern || defaultPattern(seedList[0]);

  // What is wrong, whether or not the field was touched: that decides if the form can be sent. A message
  // shows beside its field once the field was left, or the form was pressed while invalid.
  const valid = Object.keys(problemsOf(f)).length === 0;

  // Everything the check's verdict depends on, credentials included — a check that
  // stayed valid across a password edit would vouch for credentials it never saw,
  // which is the exact failure this gate exists to prevent.
  const inputSignature = JSON.stringify([
    seedList,
    pattern,
    f.username,
    f.password,
    f.coreUsername,
    f.corePassword,
    f.tlsBundle,
  ]);
  const checkedThis = checkedInputs === inputSignature;
  const stale = check.isSuccess && checkedInputs !== null && !checkedThis;

  // Registering is gated on a passing check of these exact inputs. The check opens a real Core
  // session per node, so it is the only thing that can catch a Core account the broker refuses —
  // the failure that otherwise surfaces after registration, where it reads as a broken cluster.
  const checkPassed = check.isSuccess && checkedThis && check.data.system === 'artemis';
  const afterProbe = useSlot('cluster.registration.afterProbe');
  // Brokers a registered cluster holds stay refused whichever call found it: the check, or a registration
  // that lost a race with another one. Only a new check of changed details can clear it.
  const registeredAlready = checkedThis && Boolean(alreadyRegistered(check.error) ?? alreadyRegistered(register.error));
  const registerBlockedReason = blockedReason(valid, check, checkPassed, stale, registeredAlready);

  const preview = check.data?.system === 'artemis' ? check.data : null;
  const adoption = checkPassed ? (preview?.adoption ?? null) : null;
  const adopt = adoption ? (adoptChoice ?? adoption.disagreements.length === 0) : false;

  // Open the advanced fields on their own once a check reveals they'd matter —
  // the operator never has to know they exist until the ledger says so.
  useEffect(() => {
    if (check.data?.system !== 'artemis') return;
    // A refusal, not an unproven capability: at check time no write has been
    // attempted, so messageIo is legitimately UNKNOWN and opening the advanced
    // fields for it would fire on every single registration.
    const gap =
      check.data.capabilities.messageIo.status === 'UNAVAILABLE' ||
      check.data.capabilities.notifications.status === 'UNAVAILABLE';
    if (gap) setAdvancedOpen(true);
  }, [check.data]);

  // A rejected press shows every field's message and takes the first invalid one into focus.
  const checkConnection = form.onSubmit(
    () => {
      setCheckedInputs(inputSignature);
      setAdoptChoice(null);
      register.reset();
      check.mutate(payload());
    },
    (errors) => {
      if (errors.pattern) flushSync(() => setAdvancedOpen(true));
      focusFirstInvalid(form.getInputNode)(errors);
    },
  );

  function payload(): RegisterClusterRequest {
    return {
      system: 'artemis',
      name: f.name || undefined,
      description: f.description || undefined,
      environmentId: f.environmentId || undefined,
      adopt,
      connection: {
        system: 'artemis',
        seedUrls: seedList,
        credentials: f.username ? { username: f.username, password: f.password } : undefined,
        coreCredentials: f.coreUsername ? { username: f.coreUsername, password: f.corePassword } : undefined,
        tlsBundle: f.tlsBundle || undefined,
        managementUrlPattern: pattern || undefined,
      },
    };
  }

  return (
    <div className={classes.register}>
      <form className={classes.form} noValidate onSubmit={checkConnection}>
        <TextInput
          label="Broker management URL"
          description={`One is enough: Rahdar finds the rest of the cluster from it. For example: ${EXAMPLE}`}
          {...form.getInputProps('seed')}
        />
        {f.moreSeeds.map((row, i) => (
          <FieldRow key={row.key}>
            <TextInput
              label={`Another management URL (${i + 2})`}
              autoComplete="off"
              {...form.getInputProps(`moreSeeds.${i}.url`)}
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
          size="compact-sm"
          className={classes.start}
          onClick={() => form.insertListItem('moreSeeds', seedRow())}
        >
          Add another seed
        </Button>
        {rewritten.length > 0 ? (
          <Text size="xs" c="dimmed">
            Normalised to:{' '}
            {rewritten.map((s, i) => (
              <Text key={s.original} span size="xs" className={classes.mono}>
                {i > 0 ? ', ' : ''}
                {s.url}
              </Text>
            ))}
          </Text>
        ) : null}
        <Text size="sm" fw={600}>
          Management account
        </Text>
        <FieldRow>
          <TextInput label="Username" autoComplete="off" {...form.getInputProps('username')} />
          <PasswordInput label="Password" autoComplete="off" {...form.getInputProps('password')} />
        </FieldRow>
        <Text size="sm" fw={600}>
          Core account
        </Text>
        <FieldRow>
          <TextInput
            label="Core username"
            description="Optional. Defaults to the management account."
            autoComplete="off"
            {...form.getInputProps('coreUsername')}
          />
          <PasswordInput label="Core password" autoComplete="off" {...form.getInputProps('corePassword')} />
        </FieldRow>

        <Button
          variant="subtle"
          size="compact-sm"
          className={classes.start}
          onClick={() => setAdvancedOpen((o) => !o)}
          aria-expanded={advancedOpen}
          leftSection={
            <IconChevronRight size={16} aria-hidden className={classes.chevron} data-open={advancedOpen || undefined} />
          }
        >
          Advanced: management URL pattern and TLS
        </Button>
        <Collapse expanded={advancedOpen}>
          <div className={classes.form}>
            <TextInput
              label="Management URL pattern"
              description="How Rahdar finds each other node's management URL: its host replaces {host}. A broker answering there is accepted only when it is that node."
              value={pattern}
              onChange={(event) => form.setFieldValue('pattern', event.currentTarget.value)}
              error={form.errors.pattern}
            />
            <TextInput
              label="TLS bundle"
              description="Optional. Name of a Spring SSL bundle for an HTTPS broker."
              {...form.getInputProps('tlsBundle')}
            />
          </div>
        </Collapse>

        <CheckOutcome check={check} register={register} />

        {preview ? <CapabilityLedger capabilities={preview.capabilities} /> : null}

        {adoption ? <AdoptionChoice adoption={adoption} adopt={adopt} onChange={setAdoptChoice} /> : null}

        {/* What the enabled features make of the check, such as the configuration it
            recommends; each reads its own part of the check's contributions. */}
        {preview
          ? afterProbe.map(({ id, Component }) => <Component key={id} contributions={preview.contributions} />)
          : null}

        <ClusterIdentityFields
          input={(path) => form.getInputProps(path)}
          nameHint="Optional. Defaults to the first broker's host."
        />

        <div className={`${classes.actions} ${classes.stickyActions}`}>
          {registerBlockedReason ? (
            <Text size="xs" c="dimmed" className={classes.reason}>
              {registerBlockedReason}
            </Text>
          ) : null}
          <Button type="submit" variant="default" loading={check.isPending}>
            Check connection
          </Button>
          <Button
            loading={register.isPending}
            disabled={!valid || !checkPassed || registeredAlready}
            onClick={() =>
              register.mutate(payload(), {
                onSuccess: (detail) => {
                  notify.succeeded({ action: REGISTER, subject: `cluster ${detail.name}` });
                  form.reset();
                  void navigate({ to: `/clusters/${detail.id}/topology` });
                },
              })
            }
          >
            Register cluster
          </Button>
        </div>
      </form>
    </div>
  );
}

/**
 * Nodes on a release newer than Rahdar has been tested against (ADR-0142). Registration
 * goes ahead; the operator is told before they rely on it rather than after.
 */
function UntestedVersions({ topology }: Readonly<{ topology: TopologyView }>) {
  const untested = topology.nodes.flatMap((n) => n.endpoints).filter((e) => e.versionSupport === 'NEWER_THAN_TESTED');
  if (untested.length === 0) return null;
  const which = untested.map((e) => `${e.name} runs Artemis ${e.version}`).join('; ');
  return (
    <Notice title="Newer Artemis than Rahdar has tested" tone="warning">
      {`${which}. Registration will go ahead, but this release is outside the range Rahdar's tests cover, so a management call may behave differently. The supported versions page lists the tested range.`}
    </Notice>
  );
}
