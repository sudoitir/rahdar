import { useState } from 'react';
import { Button, Menu, Stack, Text } from '@mantine/core';
import { useForm } from '@mantine/form';

import { useActionHost } from '../../kernel/actions/hostContext.ts';
import { ActionMenuItem } from '../../ui/ActionMenuItem.tsx';
import { CapabilityGate } from '../../ui/CapabilityGate.tsx';
import { kindOf } from '../../kernel/api/resourceId.ts';
import { useRoleIds } from '../clusters/index.ts';
import { ConfirmDialog } from '../../ui/ConfirmDialog.tsx';
import { focusFirstInvalid } from '../../ui/formErrors.ts';
import { Toolbar } from '../../ui/Toolbar.tsx';
import { useMessageAction, type MessageActionKind } from './api.ts';
import { BulkActionPreview } from './BulkActionPreview.tsx';
import { useActionGate } from './gates.ts';
import { announceFailure, announceResult, destroys, messageCount, VERBS } from './outcomes.ts';
import { DestinationPicker } from '../resources/index.ts';

const ACTIONS = ['move', 'retry', 'delete', 'expire'] as const;

/** What an action does to the selected messages, stated before it can be confirmed. */
function consequenceOf(action: MessageActionKind, count: number, subscriptionName: string): string {
  const selected = messageCount(count);
  switch (action) {
    case 'move':
      return `Moves the ${selected} selected from ${subscriptionName} to the subscription you name, on the node they were read from.`;
    case 'retry':
      return `Sends the ${selected} selected, dead-lettered, back to the subscription each came from.`;
    case 'delete':
      return `Removes the ${selected} selected from ${subscriptionName}, by id. This cannot be undone.`;
    case 'expire':
      return `Expires the ${selected} selected in ${subscriptionName}, by id. This cannot be undone.`;
  }
}

/** One by-selector entry of the menu: visible whatever the operator may do, with the reason when they may not. */
function SelectorItem({
  clusterId,
  action,
  kindId,
  onSelect,
}: Readonly<{ clusterId: string; action: MessageActionKind; kindId?: string; onSelect: () => void }>) {
  const host = useActionHost();
  const gate = useActionGate(clusterId, action, kindId);
  return (
    <ActionMenuItem
      label={`${VERBS[action].verb} by selector`}
      verdict={gate}
      tone={destroys(action) ? 'danger' : undefined}
      onExplain={(verdict) => host.explain(verdict, `${VERBS[action].verb.toLowerCase()} by selector`)}
      onSelect={onSelect}
    />
  );
}

/** One action on the selected rows, gated on the operator's permission. */
function SelectionAction({
  clusterId,
  action,
  kindId,
  onSelect,
}: Readonly<{ clusterId: string; action: MessageActionKind; kindId?: string; onSelect: () => void }>) {
  const gate = useActionGate(clusterId, action, kindId);
  return (
    <CapabilityGate verdict={gate} what={`${VERBS[action].progressive.toLowerCase()} the selected messages`}>
      <Button size="xs" variant="default" disabled={gate.kind === 'blocked'} onClick={onSelect}>
        {VERBS[action].verb}
      </Button>
    </CapabilityGate>
  );
}

/**
 * The by-selector entry point into {@link BulkActionPreview}, and, when the selection is non-empty,
 * the by-id actions on it. Each by-id action confirms with the number it acts on; a delete or expire
 * is armed by typing the subscription's name. The outcome is announced: done, failed with its cause, or
 * stopped part-way with the ids left undone.
 */
export function MessageActions({
  clusterId,
  subscriptionName,
  node,
  selected,
  onCleared,
}: Readonly<{
  clusterId: string;
  subscriptionName: string;
  node?: string;
  selected: ReadonlySet<string>;
  onCleared: () => void;
}>) {
  const run = useMessageAction(clusterId, subscriptionName);
  const roleIds = useRoleIds(clusterId);
  const kindId = roleIds.loaded ? kindOf(roleIds.subscription(subscriptionName)) : undefined;
  // The action stays set while the dialog closes, so its text does not change during the exit.
  const [action, setAction] = useState<MessageActionKind>('move');
  const [opened, setOpened] = useState(false);
  const form = useForm({
    initialValues: { target: '' },
    validateInputOnBlur: true,
    // Checked on activation, so the confirm button is never disabled with no reason given.
    validate: {
      target: (value) => (action === 'move' && !value.trim() ? 'Name the subscription to move the messages to.' : null),
    },
  });
  const [bulk, setBulk] = useState<MessageActionKind | null>(null);

  const ids = [...selected];

  const open = (next: MessageActionKind) => {
    setAction(next);
    form.reset();
    setOpened(true);
  };

  const submit = form.onSubmit(({ target }) => {
    run.mutate(
      {
        action,
        body: { positions: ids, target: action === 'move' ? roleIds.subscription(target) : undefined },
        node,
      },
      {
        onSuccess: (r) => {
          announceResult(action, subscriptionName, r, ids.length);
          setOpened(false);
          onCleared();
        },
        onError: (e) =>
          announceFailure(action, `${messageCount(ids.length)} in subscription "${subscriptionName}"`, e, () =>
            setOpened(false),
          ),
      },
    );
  }, focusFirstInvalid(form.getInputNode));

  const verb = VERBS[action].verb;
  return (
    <>
      <Toolbar
        label="Message operations"
        start={
          // Every item is unavailable on a system that offers none of these, and axe reports Mantine's focus
          // placeholder as a child a menu may not have; without it the first item takes the focus.
          <Menu withInitialFocusPlaceholder={false}>
            <Menu.Target>
              <Button size="xs" variant="default">
                By selector…
              </Button>
            </Menu.Target>
            <Menu.Dropdown>
              {ACTIONS.map((a) => (
                <SelectorItem key={a} clusterId={clusterId} action={a} kindId={kindId} onSelect={() => setBulk(a)} />
              ))}
            </Menu.Dropdown>
          </Menu>
        }
        end={
          selected.size > 0 ? (
            <>
              <Text size="xs" fw={600} role="status">
                {selected.size} selected
              </Text>
              {ACTIONS.map((a) => (
                <SelectionAction key={a} clusterId={clusterId} action={a} kindId={kindId} onSelect={() => open(a)} />
              ))}
              <Button size="xs" variant="subtle" onClick={onCleared}>
                Clear
              </Button>
            </>
          ) : undefined
        }
      />

      <ConfirmDialog
        opened={opened}
        onClose={() => setOpened(false)}
        title={`${verb} ${messageCount(ids.length)}`}
        consequence={
          <Stack gap="sm">
            <Text size="sm">{consequenceOf(action, ids.length, subscriptionName)}</Text>
            {action === 'move' ? (
              <DestinationPicker
                clusterId={clusterId}
                label="Target subscription"
                description="The subscription receives the messages on the same node."
                permission="message:send"
                {...form.getInputProps('target')}
                value={form.values.target}
              />
            ) : null}
          </Stack>
        }
        confirmLabel={`${verb} ${messageCount(ids.length)}`}
        tone={destroys(action) ? 'danger' : 'default'}
        pending={run.isPending}
        onConfirm={() => submit()}
      />

      {bulk ? (
        <BulkActionPreview
          clusterId={clusterId}
          subscriptionName={subscriptionName}
          action={bulk}
          node={node}
          opened={bulk !== null}
          onClose={() => setBulk(null)}
          onDone={() => {
            setBulk(null);
            onCleared();
          }}
        />
      ) : null}
    </>
  );
}
