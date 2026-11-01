import { useMemo } from 'react';
import { Menu, Text } from '@mantine/core';

import type { ComponentType } from 'react';

import { useClusterSlot } from '../shell/useClusterSlot.ts';
import type { SlotName } from '../slots.ts';
import { useActionHost } from './hostContext.ts';
import classes from './ResourceActions.module.css';
import { ACTION_SECTIONS, type ActionHost, type ActionKind, type ActionMode, type ActionTargets } from './types.ts';

type ActionSlot<K extends ActionKind> = `${K}.actions` & SlotName;

/**
 * The items every enabled feature contributes to a resource's row menu (ADR-0107), grouped under
 * the kernel's sections in their order. Rendered inside an open menu only, so the contributions'
 * own hooks (grants, capabilities) run for the one row whose menu is open.
 *
 * <p>`restoreFocus` is what the dialogs these items open hand focus back through when they close.
 */
export function ResourceActions<K extends ActionKind>({
  kind,
  clusterId,
  target,
  mode = 'act',
  restoreFocus,
}: Readonly<{
  kind: K;
  clusterId: string;
  target: ActionTargets[K];
  mode?: ActionMode;
  restoreFocus?: () => void;
}>) {
  const contributions = useClusterSlot(`${kind}.actions` as ActionSlot<K>, clusterId);
  const base = useActionHost();
  const host = useMemo<ActionHost>(
    () => ({
      ...base,
      open: (Dialog, props, options) =>
        base.open(Dialog, props, { restoreFocus: options?.restoreFocus ?? restoreFocus }),
      explain: (verdict, what, options) =>
        base.explain(verdict, what, { restoreFocus: options?.restoreFocus ?? restoreFocus }),
    }),
    [base, restoreFocus],
  );

  const sections = ACTION_SECTIONS.filter((section) => mode === 'act' || section.id === 'open' || section.id === 'copy')
    .map((section) => ({
      ...section,
      items: contributions.filter((c) => (c.section ?? 'open') === section.id),
    }))
    .filter((section) => section.items.length > 0);

  if (sections.length === 0) {
    return (
      <Text size="sm" c="dimmed" px="sm" py="xs">
        Nothing can be done to this from here.
      </Text>
    );
  }

  const props: object = { clusterId, target, host, mode };
  return (
    <>
      {sections.map((section) => (
        <div key={section.id} role="group" aria-label={section.label} className={classes.section}>
          <Menu.Label>{section.label}</Menu.Label>
          {section.items.map(({ id, Component }) => {
            const Item = Component as ComponentType<object>;
            return <Item key={id} {...props} />;
          })}
        </div>
      ))}
      {mode === 'navigate' ? (
        <>
          <Menu.Divider />
          <Text size="xs" c="dimmed" px="sm" py="xs" maw="36ch">
            This view never changes the broker. Open the resource to act on it, with its confirmation.
          </Text>
        </>
      ) : null}
    </>
  );
}
