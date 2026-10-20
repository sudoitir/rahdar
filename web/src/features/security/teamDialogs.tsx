import { Button, Modal, Stack, TextInput } from '@mantine/core';
import { useForm } from '@mantine/form';

import type { ApiError } from '../../kernel/api/request.ts';
import { ConfirmDialog } from '../../ui/ConfirmDialog.tsx';
import { DialogActions } from '../../ui/DialogActions.tsx';
import { focusFirstInvalid } from '../../ui/formErrors.ts';
import { notify, type ActionVerb } from '../../ui/notify.ts';
import { useCreateTeam, useDeleteTeam, useRenameTeam, type TeamSummary, type TeamView } from './api.ts';
import { withNotice } from './outcomes.ts';
import { countOf, problemSlug } from './teamWords.ts';

const CREATE: ActionVerb = { verb: 'Create', past: 'Created', progressive: 'Creating' };
const RENAME: ActionVerb = { verb: 'Rename', past: 'Renamed', progressive: 'Renaming' };
const DELETE: ActionVerb = { verb: 'Delete', past: 'Deleted', progressive: 'Deleting' };

const NAME_ERROR = 'Name the team after the group of people who own these subscriptions.';

/** The team a name dialog is about: a new one, or one to rename. */
export type Naming = 'new' | Pick<TeamSummary, 'id' | 'name'>;

/** A new team's name, or a team's new name, in a dialog. `onDone` gets the team as the server now has it. */
export function TeamNameDialog({
  naming,
  onClose,
  onDone,
}: Readonly<{ naming: Naming | null; onClose: () => void; onDone: (team: TeamView) => void }>) {
  return (
    <Modal
      opened={naming !== null}
      onClose={onClose}
      title={naming === 'new' ? 'New team' : `Rename ${naming?.name ?? ''}`}
    >
      {naming === null ? null : (
        <TeamNameForm
          key={naming === 'new' ? 'new' : naming.id}
          team={naming}
          onDone={onDone}
          onHeld={onClose}
          onCancel={onClose}
        />
      )}
    </Modal>
  );
}

/** The name of a new or renamed team. A name already taken is answered beside the field. */
function TeamNameForm({
  team,
  onDone,
  onHeld,
  onCancel,
}: Readonly<{ team: Naming; onDone: (team: TeamView) => void; onHeld: () => void; onCancel: () => void }>) {
  const create = useCreateTeam();
  const rename = useRenameTeam();
  const form = useForm({
    initialValues: { name: team === 'new' ? '' : team.name },
    validateInputOnBlur: true,
    validate: { name: (v) => (v.trim() ? null : NAME_ERROR) },
  });
  const pending = create.isPending || rename.isPending;

  const submit = form.onSubmit(({ name }) => {
    const subject = `team ${name.trim()}`;
    const onError = (action: ActionVerb, next: string) => (error: ApiError) => {
      if (problemSlug(error) === 'duplicate-team-name') {
        form.setErrors({ name: 'A team with that name already exists. Choose another name.' });
        form.getInputNode('name')?.focus();
      } else {
        notify.settle(error, { action, subject, cause: error.message, next, onHeld });
      }
    };
    const onSuccess = (action: ActionVerb) => (saved: TeamView) => {
      notify.succeeded({ action, subject });
      onDone(saved);
    };
    if (team === 'new') {
      create.mutate(name.trim(), {
        onSuccess: onSuccess(CREATE),
        onError: onError(CREATE, 'No team was created. Try again.'),
      });
    } else {
      rename.mutate(
        { teamId: team.id, name: name.trim() },
        { onSuccess: onSuccess(RENAME), onError: onError(RENAME, 'The team keeps its name. Try again.') },
      );
    }
  }, focusFirstInvalid(form.getInputNode));

  return (
    <form noValidate onSubmit={submit}>
      <Stack gap="sm">
        <TextInput
          label="Name"
          description="The group of people who own these subscriptions, such as Payments."
          data-autofocus
          {...form.getInputProps('name')}
          required
        />
        <DialogActions>
          <Button variant="default" disabled={pending} onClick={onCancel}>
            Cancel
          </Button>
          <Button type="submit" loading={pending}>
            {team === 'new' ? 'Create team' : 'Rename team'}
          </Button>
        </DialogActions>
      </Stack>
    </form>
  );
}

/** What a team's deletion takes with it, from the counts a summary or a full team carries. */
function deletedWith(team: TeamSummary | TeamView): string {
  const [members, shares] =
    'memberCount' in team
      ? [team.memberCount, team.sharesOut + team.sharesIn]
      : [team.members.length, team.sharesOut.length + team.sharesIn.length];
  return `This removes the team's ${countOf(team.patterns.length, 'pattern')}, ${countOf(members, 'member')} and ${countOf(shares, 'share')}. Its members lose the access the team gave them on their next request. The subscriptions and destinations themselves are not touched.`;
}

/** States what deleting a team ends, from its own counts, before it can be armed, then asks for its name. */
export function DeleteTeam({
  team,
  opened,
  onClose,
  onDeleted,
}: Readonly<{
  team: TeamSummary | TeamView | null;
  opened: boolean;
  onClose: () => void;
  onDeleted?: () => void;
}>) {
  const remove = useDeleteTeam();
  const confirm = (t: TeamSummary | TeamView) =>
    remove.mutate(
      t.id,
      withNotice(
        DELETE,
        `team ${t.name}`,
        'The team still exists. Try again.',
        () => {
          onClose();
          onDeleted?.();
        },
        onClose,
      ),
    );

  return (
    <ConfirmDialog
      opened={opened}
      onClose={onClose}
      title={team ? `Delete ${team.name}` : 'Delete team'}
      tone="danger"
      pending={remove.isPending}
      confirmLabel="Delete team"
      consequence={team ? deletedWith(team) : ''}
      onConfirm={() => team && confirm(team)}
    />
  );
}
