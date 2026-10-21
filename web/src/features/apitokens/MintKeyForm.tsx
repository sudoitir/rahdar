import { Button, MultiSelect, Select, Stack, Text, TextInput } from '@mantine/core';
import { useForm } from '@mantine/form';

import { DialogActions } from '../../ui/DialogActions.tsx';
import { ErrorState } from '../../ui/ErrorState.tsx';
import { focusFirstInvalid } from '../../ui/formErrors.ts';
import { notify, type ActionVerb } from '../../ui/notify.ts';
import { useClusters } from '../clusters/index.ts';
import { PermissionPicker, usePermissionsCatalogue, type PermissionView } from '../security/index.ts';
import { useCan } from '../../kernel/auth/useCan.ts';
import { serverNow } from '../../kernel/time/time.ts';
import { useCreateToken, useMcpTools, useTokenPolicy, type CreatedTokenView, type TokenGrantRequest } from './api.ts';
import classes from './TokenParts.module.css';

const CREATE: ActionVerb = { verb: 'Create', past: 'Created', progressive: 'Creating' };

const GLOBAL = 'GLOBAL';
const DAY_MS = 86_400_000;
const LIFETIMES = [7, 30, 90, 180, 365];

const NAME_ERROR = 'Name the key after where it will be used.';
const PATTERN_ERROR = 'Give the pattern the names must match, such as orders.#';
const NO_LIMIT = 'NONE';
const GRANTS_ERROR = 'Choose at least one permission; a key without any could sign in and do nothing.';

/** The lifetimes, in days, the installation's maximum allows, with the maximum itself as the last choice. */
function lifetimeOptionsFor(maxDays: number | null): string[] {
  if (maxDays === null) return [];
  const options = LIFETIMES.filter((d) => d <= maxDays).map(String);
  if (maxDays >= 1 && !options.includes(String(maxDays))) options.push(String(maxDays));
  return options;
}

/** 30 days when offered, otherwise the longest lifetime there is. */
function defaultLifetime(options: string[]): string | null {
  if (options.includes('30')) return '30';
  return options.at(-1) ?? null;
}

type Limit = { kind: 'SUBSCRIPTION' | 'DESTINATION'; pattern: string };

/**
 * One grant per permission. A limit applies to the permissions that act on subscriptions or destinations of its kind,
 * and the others, which have no names to match, stay on the whole scope.
 */
function grantsFor(
  scope: string,
  chosen: string[],
  limit: Limit | null,
  catalogue: PermissionView[],
): TokenGrantRequest[] {
  const global = scope === GLOBAL;
  return chosen.map((action) => {
    const limited =
      limit !== null &&
      catalogue.some((p) => p.action === action && p.scope === 'RESOURCE' && p.resourceTypes.includes(limit.kind));
    return {
      action,
      scopeType: global ? GLOBAL : 'CLUSTER',
      scopeId: global ? null : scope,
      ...(limited ? { resourceType: limit.kind, resourcePattern: limit.pattern } : {}),
    };
  });
}

/**
 * Mints a key: a name, an expiry within the installation's maximum lifetime, grants from the
 * permissions the user holds at the chosen scope (the server intersects them anyway, so offering
 * more would only mint keys that silently lose what was ticked), and optionally the MCP tools the
 * key may call.
 */
export function MintKeyForm({
  onMinted,
  onCancel,
}: Readonly<{ onMinted: (created: CreatedTokenView) => void; onCancel: () => void }>) {
  const create = useCreateToken();
  const policy = useTokenPolicy();
  const tools = useMcpTools();
  const catalogue = usePermissionsCatalogue();
  const clusters = useClusters();
  const { can } = useCan();

  const form = useForm({
    initialValues: {
      name: '',
      scope: GLOBAL,
      chosen: [] as string[],
      lifetime: null as string | null,
      mcpTools: [] as string[],
      limitKind: NO_LIMIT,
      pattern: '',
    },
    validateInputOnBlur: true,
    validate: {
      name: (v) => (v.trim() ? null : NAME_ERROR),
      chosen: (v) => (v.length > 0 ? null : GRANTS_ERROR),
      pattern: (v, values) => (values.limitKind === NO_LIMIT || v.trim() ? null : PATTERN_ERROR),
    },
  });
  const { name, scope, chosen, lifetime, mcpTools, limitKind, pattern } = form.values;
  const chosenProps = form.getInputProps('chosen');

  const clusterId = scope === GLOBAL ? undefined : scope;
  // What the user can actually delegate at the selected scope. A wildcard grant makes every
  // catalogued permission available; `can` resolves that. Computed while rendering, so it follows the
  // user's grants as they load or change, never a stale answer.
  const available = (catalogue.data ?? []).filter(
    (p) => can(p.action, clusterId) && (scope === GLOBAL || p.scope !== 'GLOBAL'),
  );

  const latest = policy.data ? Date.parse(policy.data.latestExpiry) : null;
  const maxDays = latest === null ? null : Math.round((latest - serverNow()) / DAY_MS);
  const lifetimeOptions = lifetimeOptionsFor(maxDays);
  const selectedLifetime = lifetime ?? defaultLifetime(lifetimeOptions);

  const submit = form.onSubmit(() => {
    if (!selectedLifetime) return;
    const limit: Limit | null =
      limitKind === NO_LIMIT ? null : { kind: limitKind as Limit['kind'], pattern: pattern.trim() };
    const grants = grantsFor(scope, chosen, limit, catalogue.data ?? []);
    // Never past the cap the server stated, however long the form stayed open.
    const expiresAt = new Date(
      Math.min(serverNow() + Number(selectedLifetime) * DAY_MS, latest ?? Infinity),
    ).toISOString();
    const subject = `key "${name.trim()}"`;
    create.mutate(
      { name: name.trim(), expiresAt, grants, mcpTools },
      {
        onSuccess: (created) => {
          onMinted(created);
          notify.succeeded({ action: CREATE, subject });
        },
      },
    );
  }, focusFirstInvalid(form.getInputNode));

  return (
    <form noValidate onSubmit={submit}>
      <Stack gap="sm">
        <TextInput label="Name" {...form.getInputProps('name')} required />
        {policy.isError ? (
          <ErrorState variant="inline" error={policy.error} onRetry={() => void policy.refetch()} />
        ) : (
          <Select
            label="Expires in"
            description={
              policy.data
                ? `Keys on this installation live at most ${maxDays} days.`
                : 'Loading the installation’s maximum lifetime…'
            }
            value={selectedLifetime}
            onChange={(v) => form.setFieldValue('lifetime', v)}
            data={lifetimeOptions.map((d) => ({ value: d, label: `${d} days` }))}
            allowDeselect={false}
            required
          />
        )}
        <Select
          label="Scope"
          description="Where the key's permissions apply."
          {...form.getInputProps('scope')}
          onChange={(v) => {
            form.setFieldValue('scope', v ?? GLOBAL);
            form.setFieldValue('chosen', []);
          }}
          allowDeselect={false}
          data={[
            { value: GLOBAL, label: 'Global — every cluster' },
            ...(clusters.data ?? []).map((c) => ({ value: c.id, label: c.name })),
          ]}
        />
        {/* The marker `form.getInputNode` looks for, so a rejected submit can focus the grants. */}
        <div data-path="chosen" tabIndex={-1} className={classes.group}>
          <Text size="sm" fw={500}>
            Permissions
          </Text>
          <Text size="xs" c="dimmed" mb="xs">
            Only what you hold at this scope is offered.
          </Text>
          {available.length === 0 ? (
            <Text size="xs" c="dimmed">
              You hold nothing at this scope, so a key made here could do nothing.
            </Text>
          ) : (
            <PermissionPicker catalogue={available} value={chosen} onChange={chosenProps.onChange} />
          )}
          {form.errors.chosen ? (
            <Text size="xs" role="alert" mt="xs">
              {form.errors.chosen}
            </Text>
          ) : null}
        </div>
        <Select
          label="Limit to"
          description="Names a key may act on. Permissions that act on subscriptions or destinations follow it; the others do not."
          {...form.getInputProps('limitKind')}
          allowDeselect={false}
          data={[
            { value: NO_LIMIT, label: 'Everything in the scope' },
            { value: 'SUBSCRIPTION', label: 'Subscriptions whose names match a pattern' },
            { value: 'DESTINATION', label: 'Destinations whose names match a pattern' },
          ]}
        />
        {limitKind === NO_LIMIT ? null : (
          <TextInput
            label="Name pattern"
            description="Artemis wildcards: * stands for one word and # for any number of words, words are separated by a dot."
            placeholder="orders.#"
            {...form.getInputProps('pattern')}
            required
          />
        )}
        {tools.data ? (
          <MultiSelect
            label="MCP tools"
            description="Leave empty to let the key call every tool its permissions allow. Other tools stay hidden from it."
            placeholder={mcpTools.length === 0 ? 'Every tool' : undefined}
            searchable
            {...form.getInputProps('mcpTools')}
            data={tools.data.map((t) => ({
              value: t.name,
              label: `${t.name} (${t.posture === 'READ' ? 'read' : 'changes state'})`,
            }))}
          />
        ) : null}
        {create.isError ? (
          <ErrorState variant="inline" error={create.error} next={createFailure(create.error)} />
        ) : null}
        <DialogActions>
          <Button variant="default" disabled={create.isPending} onClick={onCancel}>
            Cancel
          </Button>
          <Button type="submit" loading={create.isPending}>
            Create key
          </Button>
        </DialogActions>
      </Stack>
    </form>
  );
}

/** Why a key could not be made, and what to do about it, in one line. */
function createFailure(error: { type: string }): string {
  if (error.type.endsWith('/session-required')) {
    return 'A key can only be created from a signed-in console session, not with another key. Sign in to the console and create it there.';
  }
  if (error.type.endsWith('/mfa-required')) {
    return 'Your role requires two-step verification, and this session has not completed it. Sign out, sign in with your second factor, then create the key.';
  }
  return 'No key was created. Try again.';
}
