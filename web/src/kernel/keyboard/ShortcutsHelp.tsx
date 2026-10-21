import { Fragment } from 'react';
import { ActionIcon, Kbd, Popover, ScrollArea, Stack, Switch, Text, Tooltip } from '@mantine/core';
import { IconKeyboard } from '@tabler/icons-react';

import { Section } from '../../ui/Section.tsx';
import { useFeatures } from '../features.ts';
import { NAV_GROUPS } from '../nav/groups.ts';
import { setShortcutsHelpOpen, useShortcutsHelpOpen, useSingleKeyShortcuts } from './shortcuts.ts';
import classes from './ShortcutsHelp.module.css';
import { viewHotkeys } from './useKeySequences.ts';

interface Row {
  keys: string[][];
  what: string;
}

function Keys({ keys }: Readonly<{ keys: string[][] }>) {
  return (
    <dd className={classes.keys}>
      {keys.map((combo, i) => (
        <Fragment key={combo.join('+')}>
          {i > 0 ? (
            <Text span size="xs" c="dimmed">
              or
            </Text>
          ) : null}
          <span className={classes.combo}>
            {combo.map((k) => (
              <Kbd key={k} size="xs">
                {k}
              </Kbd>
            ))}
          </span>
        </Fragment>
      ))}
    </dd>
  );
}

/**
 * One group of shortcuts: what each does, in a line read at a glance, then the keys that do it. `note` holds
 * what a line leaves out.
 */
function Shortcuts({ title, rows, note }: Readonly<{ title: string; rows: Row[]; note?: string }>) {
  if (rows.length === 0) return null;
  return (
    <Section title={title} headingLevel={3}>
      <dl className={classes.list}>
        {rows.map((row) => (
          <Fragment key={row.what}>
            <dt className={classes.what}>{row.what}</dt>
            <Keys keys={row.keys} />
          </Fragment>
        ))}
      </dl>
      {note ? <Text className={classes.note}>{note}</Text> : null}
    </Section>
  );
}

/**
 * Every keyboard shortcut, in one place (ADR-0109), with the switch that turns the single-key ones
 * off: a header button and the popover it opens, like the refresh control beside it. `?` opens the
 * same popover; the button keeps it reachable when the single-key shortcuts are off.
 */
export function ShortcutsHelp() {
  const opened = useShortcutsHelpOpen();
  const [enabled, setEnabled] = useSingleKeyShortcuts();
  const hotkeys = viewHotkeys(useFeatures());
  const byGroup = NAV_GROUPS.map((group) => ({
    group,
    rows: [...hotkeys.entries()]
      .filter(([, item]) => item.group === group.id)
      .sort((a, b) => a[1].order - b[1].order)
      .map(([key, item]) => ({
        keys: [['g', key]],
        what: `Go to ${item.label}`,
      })),
  })).filter((g) => g.rows.length > 0);

  return (
    <Popover
      opened={opened}
      onChange={setShortcutsHelpOpen}
      position="bottom-end"
      // Never wider than the window, so at 200% zoom it still fits beside nothing.
      width="min(30rem, calc(100vw - 2 * var(--mantine-spacing-md)))"
      // Opened from the keyboard (?) as often as by pointer: it appears at once rather than animating in.
      transitionProps={{ duration: 0 }}
      trapFocus
      returnFocus
      // Its trigger sits in the fixed header and is never scrolled away; hiding a "detached" popover only
      // raced the position measurement and could leave it open but invisible.
      hideDetached={false}
    >
      <Popover.Target>
        <Tooltip label="Keyboard shortcuts (?)" position="bottom" disabled={opened}>
          <ActionIcon
            variant="subtle"
            color="graphite"
            aria-label="Keyboard shortcuts"
            aria-keyshortcuts="Shift+Slash"
            aria-expanded={opened}
            aria-haspopup="dialog"
            onClick={() => setShortcutsHelpOpen(!opened)}
          >
            <IconKeyboard size={18} stroke={1.5} aria-hidden />
          </ActionIcon>
        </Tooltip>
      </Popover.Target>
      <Popover.Dropdown aria-label="Keyboard shortcuts" p={0}>
        <ScrollArea.Autosize mah="min(70vh, 40rem)" type="auto" scrollbars="y">
          <Stack p="md">
            <Section title="Keyboard shortcuts">
              <Switch
                checked={enabled}
                onChange={(e) => setEnabled(e.currentTarget.checked)}
                label="Single-key shortcuts"
                data-autofocus
                description="Keys without ⌘ or Ctrl. Turn them off for speech input or if they get in your way. Kept in this browser."
              />

              <Shortcuts
                title="Everywhere"
                rows={[
                  {
                    keys: [
                      ['⌘', 'K'],
                      ['Ctrl', 'K'],
                    ],
                    what: 'Search views, clusters and subscriptions',
                  },
                  {
                    keys: [
                      ['⌘', 'B'],
                      ['Ctrl', 'B'],
                    ],
                    what: 'Collapse or expand the sidebar',
                  },
                  ...(enabled
                    ? [
                        { keys: [['?']], what: 'Show this list' },
                        { keys: [['/']], what: "Focus the view's filter" },
                      ]
                    : []),
                ]}
              />

              {enabled ? (
                byGroup.map(({ group, rows }) => (
                  <Shortcuts key={group.id} title={`Go to — ${group.label}`} rows={rows} />
                ))
              ) : (
                <Text size="sm" c="dimmed">
                  Single-key shortcuts are off. Turn them on above to go to views with <Kbd size="xs">g</Kbd> and a
                  letter.
                </Text>
              )}

              <Shortcuts
                title="In a grid"
                rows={[
                  {
                    keys: [['↑'], ['↓'], ['←'], ['→']],
                    what: 'Move between cells',
                  },
                  {
                    keys: [['Home'], ['End']],
                    what: 'First or last cell of the row',
                  },
                  {
                    keys: [
                      ['Ctrl', 'Home'],
                      ['Ctrl', 'End'],
                    ],
                    what: 'First or last row',
                  },
                  { keys: [['Enter']], what: 'Open the row' },
                  {
                    keys: [['Space']],
                    what: 'Select the row',
                  },
                  {
                    keys: [['Shift', 'F10'], ['Menu']],
                    what: "Open the row's actions",
                  },
                  { keys: [['Ctrl', 'C']], what: "Copy the cell's full value" },
                ]}
                note="Space selects only where a list has checkboxes."
              />

              <Shortcuts
                title="In the SQL console"
                rows={[
                  {
                    keys: [
                      ['⌘', 'Enter'],
                      ['Ctrl', 'Enter'],
                    ],
                    what: 'Run the query',
                  },
                  {
                    keys: [
                      ['⌘', '.'],
                      ['Ctrl', '.'],
                    ],
                    what: 'Cancel the running query',
                  },
                  { keys: [['Ctrl', 'Space']], what: 'Complete a name' },
                  {
                    keys: [['Escape']],
                    what: 'Close completion or leave the editor',
                  },
                  { keys: [['F8']], what: 'Go to the next problem' },
                  {
                    keys: [
                      ['⌘', 'Shift', 'M'],
                      ['Ctrl', 'Shift', 'M'],
                    ],
                    what: 'Maximise or restore the results',
                  },
                  {
                    keys: [['↑'], ['↓']],
                    what: 'Resize the editor',
                  },
                  { keys: [['Home'], ['End']], what: 'Smallest or largest editor' },
                ]}
                note="⌘ or Ctrl + . cancels from the editor or the page; Escape never cancels. Resize keys work on the separator, Shift for bigger steps."
              />

              <Shortcuts
                title="In the topology"
                rows={[
                  { keys: [['←'], ['→']], what: 'Move between columns of nodes' },
                  { keys: [['↑'], ['↓']], what: 'Move within a column' },
                  { keys: [['Home'], ['End']], what: 'First or last node' },
                  { keys: [['Enter'], ['Space']], what: 'Choose the node and announce it' },
                  { keys: [['Escape']], what: 'Clear the choice, keeping focus on the node' },
                ]}
              />
            </Section>
          </Stack>
        </ScrollArea.Autosize>
      </Popover.Dropdown>
    </Popover>
  );
}
