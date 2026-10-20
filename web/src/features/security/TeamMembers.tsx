import { useState } from 'react';
import { Button, Select, Stack, Text, TextInput } from '@mantine/core';
import { useForm } from '@mantine/form';
import { useDebouncedValue } from '@mantine/hooks';

import { useAuthProviders } from '../../kernel/auth/api.ts';
import { ConfirmDialog } from '../../ui/ConfirmDialog.tsx';
import { EmptyState } from '../../ui/EmptyState.tsx';
import { ErrorState } from '../../ui/ErrorState.tsx';
import { FieldRow } from '../../ui/FieldRow.tsx';
import { focusFirstInvalid } from '../../ui/formErrors.ts';
import { notify, type ActionVerb } from '../../ui/notify.ts';
import { Section } from '../../ui/Section.tsx';
import { DataTable } from '../../ui/table/index.ts';
import {
  useAddMember,
  useChangeMemberRole,
  useRemoveMember,
  useTeamRoles,
  USER_LOOKUP_MIN_PREFIX,
  useUserLookup,
  type MemberRequest,
  type MemberView,
  type TeamView,
} from './api.ts';
import { KindField } from '../../ui/KindField.tsx';
import { withNotice } from './outcomes.ts';
import classes from './Security.module.css';
import { useTeamAccess } from './teamAccess.ts';
import { memberColumns, memberName } from './teamColumns.tsx';
import { problemSlug, teamProblem } from './teamWords.ts';

const ADD: ActionVerb = { verb: 'Add', past: 'Added', progressive: 'Adding' };
const CHANGE: ActionVerb = { verb: 'Change', past: 'Changed', progressive: 'Changing' };
const REMOVE: ActionVerb = { verb: 'Remove', past: 'Removed', progressive: 'Removing' };

const GROUP_REASON =
  'Adding, changing or removing a directory group needs the user:admin permission, because a group can admit users to Rahdar. Ask a user administrator.';

const rowKey = (m: MemberView) => m.id;

/**
 * A team's members and the team role each holds. A team admin changes user members; directory groups, which can
 * admit users to Rahdar, are left to a user administrator.
 */
export function TeamMembers({ team }: Readonly<{ team: TeamView }>) {
  const roles = useTeamRoles();
  const { userAdmin } = useTeamAccess();
  const change = useChangeMemberRole(team.id);
  const [removing, setRemoving] = useState<MemberView | null>(null);
  const [removeOpen, setRemoveOpen] = useState(false);

  const roleOptions = (roles.data?.roles ?? []).map((r) => ({ value: r.id, label: r.name }));
  const columns = memberColumns({
    roleOptions,
    editable: (m) => m.principalType === 'USER' || userAdmin,
    busyId: change.isPending ? change.variables?.memberId : undefined,
    onChangeRole: (m, roleId) =>
      change.mutate(
        { memberId: m.id, roleId },
        withNotice(CHANGE, `the role of ${memberName(m)}`, 'The member keeps their role. Try again.'),
      ),
    onRemove: (m) => {
      setRemoving(m);
      setRemoveOpen(true);
    },
  });

  return (
    <Stack gap="lg">
      <Section
        title="Members"
        headingLevel={3}
        description="Each member holds one team role, which applies to the subscriptions and destinations this team owns."
      >
        {userAdmin || !team.members.some((m) => m.principalType === 'GROUP') ? null : (
          <Text size="sm" className={classes.reason}>
            {GROUP_REASON}
          </Text>
        )}
        <DataTable
          variant="static"
          label={`Members of ${team.name}`}
          storageKey="security.team-members"
          columns={columns}
          data={team.members}
          rowKey={rowKey}
          empty={
            <EmptyState
              kind="empty"
              title="No members"
              description="Members are users or directory groups, each with a team role on what this team owns. Add one below to give them that access."
            />
          }
        />
      </Section>

      <Section title="Add a member" headingLevel={3}>
        {roles.isError ? (
          <ErrorState
            variant="inline"
            error={roles.error}
            onRetry={() => void roles.refetch()}
            next="Only an administrator of a team can list the team roles. Ask a user administrator."
          />
        ) : null}
        <AddMember team={team} userAdmin={userAdmin} roleOptions={roleOptions} />
      </Section>

      <RemoveMember team={team} member={removing} opened={removeOpen} onClose={() => setRemoveOpen(false)} />
    </Stack>
  );
}

type Values = {
  type: 'USER' | 'GROUP';
  userId: string | null;
  providerId: string | null;
  groupName: string;
  roleId: string | null;
};

function AddMember({
  team,
  userAdmin,
  roleOptions,
}: Readonly<{ team: TeamView; userAdmin: boolean; roleOptions: { value: string; label: string }[] }>) {
  const [search, setSearch] = useState('');
  const [typed] = useDebouncedValue(search, 200);
  const users = useUserLookup(typed);
  const [chosen, setChosen] = useState<{ value: string; label: string } | null>(null);
  const providers = useAuthProviders();
  const add = useAddMember(team.id);
  const form = useForm<Values>({
    initialValues: { type: 'USER', userId: null, providerId: null, groupName: '', roleId: null },
    validateInputOnBlur: true,
    validate: {
      userId: (v, values) => (values.type === 'USER' && !v ? 'Choose the user to add.' : null),
      providerId: (v, values) =>
        values.type === 'GROUP' && !v ? 'Choose the identity provider that sends the group.' : null,
      groupName: (v, values) =>
        values.type === 'GROUP' && !v.trim() ? 'Enter the group name exactly as the provider sends it.' : null,
      roleId: (v) => (v ? null : 'Choose the team role the member holds.'),
    },
  });
  const group = form.values.type === 'GROUP';
  const memberIds = new Set(team.members.map((m) => m.userId));
  const found = (users.data ?? []).filter((u) => !memberIds.has(u.id)).map((u) => ({ value: u.id, label: u.username }));
  // The chosen user stays in the list while a different name is being typed.
  const userOptions = chosen && !found.some((o) => o.value === chosen.value) ? [chosen, ...found] : found;
  const external = (providers.data ?? []).filter((p) => p.id !== 'local');

  const submit = form.onSubmit((values) => {
    if (!values.roleId) return;
    const request: MemberRequest =
      values.type === 'USER'
        ? { principalType: 'USER', userId: values.userId, roleId: values.roleId }
        : {
            principalType: 'GROUP',
            providerId: values.providerId,
            groupName: values.groupName.trim(),
            roleId: values.roleId,
          };
    const who =
      values.type === 'USER'
        ? (userOptions.find((u) => u.value === values.userId)?.label ?? 'the user')
        : values.groupName.trim();
    const subject = `${who} to ${team.name}`;
    const pendingId = notify.pending({ action: ADD, subject });
    add.mutate(request, {
      onSuccess: () => {
        notify.succeeded({ action: ADD, subject, pendingId });
        form.reset();
      },
      onError: (error) =>
        notify.settle(error, {
          action: ADD,
          subject,
          pendingId,
          cause: teamProblem(error) ?? error.message,
          next:
            problemSlug(error) === 'team-member-exists'
              ? 'Change their role in the list instead.'
              : 'No member was added. Try again.',
          onHeld: () => form.reset(),
        }),
    });
  }, focusFirstInvalid(form.getInputNode));

  const userField = users.isError ? (
    <ErrorState
      variant="inline"
      error={users.error}
      onRetry={() => void users.refetch()}
      next="Only an administrator of a team can look users up. Ask a user administrator to add the member."
    />
  ) : (
    <Select
      label="User"
      description="Type the start of a username."
      data={userOptions}
      searchable
      searchValue={search}
      onSearchChange={setSearch}
      filter={({ options }) => options}
      onChange={(id, option) => {
        form.setFieldValue('userId', id);
        setChosen(option ? { value: option.value, label: option.label } : null);
      }}
      value={form.values.userId}
      error={form.errors.userId}
      onBlur={() => form.validateField('userId')}
      data-path="userId"
      placeholder="Type two letters of a username"
      nothingFoundMessage={lookupMessage(typed, users.isFetching)}
      required
    />
  );

  return (
    <form noValidate onSubmit={submit}>
      <Stack gap="sm">
        <KindField
          label="Member type"
          data={[
            { value: 'USER', label: 'User' },
            { value: 'GROUP', label: 'Directory group', disabled: !userAdmin },
          ]}
          value={form.values.type}
          onChange={(next) => form.setFieldValue('type', next)}
        />
        {userAdmin ? null : (
          <Text size="sm" className={classes.reason}>
            {GROUP_REASON}
          </Text>
        )}
        {group ? (
          <FieldRow>
            <Select
              label="Identity provider"
              data={external.map((p) => ({ value: p.id, label: p.label }))}
              placeholder="Select a provider"
              nothingFoundMessage="No external identity provider"
              {...form.getInputProps('providerId')}
              required
            />
            <TextInput label="Group" {...form.getInputProps('groupName')} required />
          </FieldRow>
        ) : (
          userField
        )}
        <Select
          label="Team role"
          data={roleOptions}
          placeholder="Select a role"
          nothingFoundMessage="No team roles"
          {...form.getInputProps('roleId')}
          required
        />
        <div>
          <Button type="submit" loading={add.isPending}>
            Add member
          </Button>
        </div>
      </Stack>
    </form>
  );
}

/** States what removing a member ends, then asks for their name. */
function RemoveMember({
  team,
  member,
  opened,
  onClose,
}: Readonly<{ team: TeamView; member: MemberView | null; opened: boolean; onClose: () => void }>) {
  const remove = useRemoveMember(team.id);
  const name = member ? memberName(member) : '';
  const theirs = member?.principalType === 'GROUP' ? 'its users' : 'them';

  return (
    <ConfirmDialog
      opened={opened}
      onClose={onClose}
      title={member ? `Remove ${name} from ${team.name}` : 'Remove member'}
      tone="danger"
      pending={remove.isPending}
      confirmLabel="Remove member"
      consequence={
        member
          ? `${name} loses the ${member.roleName} access that ${team.name} gave ${theirs} on the next request. You can add them again.`
          : ''
      }
      onConfirm={() =>
        member &&
        remove.mutate(
          member.id,
          withNotice(REMOVE, `${name} from ${team.name}`, 'They are still a member. Try again.', onClose),
        )
      }
    />
  );
}

/** What the user picker says when it lists nobody. */
function lookupMessage(typed: string, searching: boolean): string {
  if (typed.trim().length < USER_LOOKUP_MIN_PREFIX) {
    return 'Type at least two letters';
  }
  return searching ? 'Searching' : 'No enabled user starts with that';
}
