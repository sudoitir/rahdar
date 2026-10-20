import { useMemo, useState, type ReactNode } from 'react';
import {
  Accordion,
  Button,
  Center,
  Checkbox,
  CloseButton,
  Group,
  Stack,
  Text,
  TextInput,
  VisuallyHidden,
} from '@mantine/core';
import { IconSearch } from '@tabler/icons-react';

import { FieldRow } from '../../ui/FieldRow.tsx';
import { Notice } from '../../ui/Notice.tsx';
import { StatusBadge } from '../../ui/StatusBadge.tsx';
import type { PermissionView } from './api.ts';
import {
  dependentsOf,
  grantedThrough,
  withRequired,
  type AddedPermission,
  type DependentPermission,
} from './permissionRequires.ts';
import classes from './Security.module.css';

const UNCATALOGUED = '__uncatalogued__';
const WILDCARDS = '__wildcards__';

type Entry = { action: string; label: string; scope?: PermissionView['scope']; kinds: string[] };
type PermissionGroup = { id: string; title: string; entries: Entry[] };

const SCOPE_WORDS = { GLOBAL: 'Global', CLUSTER: 'Cluster', RESOURCE: 'Resource' } as const;

/** Whether a team role may hold the permission: it acts on a resource, or it is `team:admin`. */
const teamRoleMay = (p: PermissionView) => p.scope === 'RESOURCE' || p.action === 'team:admin';

/** Where a permission takes effect, in words: its scope, and the kinds of resource a resource permission acts on. */
function scopeBadge(entry: Entry, teamRole: boolean): string | null {
  if (entry.action === 'team:admin' && teamRole) return 'Team';
  if (!entry.scope) return null;
  const word = SCOPE_WORDS[entry.scope];
  return entry.scope === 'RESOURCE' && entry.kinds.length > 0
    ? `${word}: ${entry.kinds.map((k) => k.toLowerCase()).join(', ')}`
    : word;
}

function describe(entry: Entry, teamRole: boolean): string {
  if (entry.scope === 'GLOBAL' && !(teamRole && entry.action === 'team:admin')) {
    return `${entry.label}. Has no effect when granted on an environment or cluster.`;
  }
  return entry.label;
}

/** Groups the catalogue by owning module or plugin, plus the held permissions the catalogue lacks. */
function groupPermissions(catalogue: PermissionView[], selected: string[], teamRole: boolean): PermissionGroup[] {
  const groups = new Map<string, PermissionGroup>();
  for (const p of catalogue) {
    const group = groups.get(p.featureId) ?? { id: p.featureId, title: p.featureTitle, entries: [] };
    group.entries.push({ action: p.action, label: p.label, scope: p.scope, kinds: p.resourceTypes });
    groups.set(p.featureId, group);
  }
  // A team role holds only what the catalogue offers it; what else it holds is listed apart, to be removed.
  if (teamRole) return [...groups.values()];
  const known = new Set(catalogue.map((p) => p.action));
  const isWildcard = (a: string) => a === '*' || a.endsWith(':*');
  const wildcards = selected.filter((a) => isWildcard(a) && !known.has(a));
  const missing = selected.filter((a) => !isWildcard(a) && !known.has(a));
  const result = [...groups.values()];
  if (wildcards.length > 0) {
    result.push({
      id: WILDCARDS,
      title: 'Wildcards',
      entries: wildcards.map((action) => ({
        action,
        label:
          action === '*'
            ? 'Grants every permission, including those of modules and plugins added later.'
            : `Grants every ${action.slice(0, -1)} permission, including ones added later.`,
        kinds: [],
      })),
    });
  }
  if (missing.length > 0) {
    result.push({
      id: UNCATALOGUED,
      title: 'Not in the catalogue',
      entries: missing.map((action) => ({
        action,
        label: 'Its module or plugin is not active. The role keeps it unless you clear it.',
        kinds: [],
      })),
    });
  }
  return result;
}

function matches(entry: Entry, query: string): boolean {
  const q = query.trim().toLowerCase();
  return q === '' || entry.action.toLowerCase().includes(q) || entry.label.toLowerCase().includes(q);
}

/** What stands in for the groups when no permission is declared, or none matches the search. */
function pickerNotice(groupCount: number, visibleCount: number, query: string, clear: () => void): ReactNode {
  if (groupCount === 0) {
    return <Text size="sm">No module or plugin declares a permission, so there is nothing to grant.</Text>;
  }
  if (visibleCount > 0) return null;
  return (
    <Stack gap={4} align="flex-start">
      <Text size="sm">No permission matches “{query}”.</Text>
      <Button size="xs" variant="subtle" onClick={clear}>
        Clear search
      </Button>
    </Stack>
  );
}

/**
 * The role editor's permission picker (operator-ui spec): grouped by module or plugin, searchable,
 * with each permission's description and the scope it acts at, and select-all or clear per group,
 * announced. A permission a held wildcard already gives shows as granted through it. Choosing a
 * permission adds the ones it requires, with a note; removing one that others require removes them
 * too, says which, and offers Undo. `teamRole` offers only what a team role may hold.
 */
export function PermissionPicker({
  catalogue,
  value,
  onChange,
  teamRole = false,
}: Readonly<{
  catalogue: PermissionView[];
  value: string[];
  onChange: (next: string[]) => void;
  teamRole?: boolean;
}>) {
  const [query, setQuery] = useState('');
  const [announcement, setAnnouncement] = useState('');
  const [added, setAdded] = useState<AddedPermission[]>([]);
  const [removed, setRemoved] = useState<{
    actions: string[];
    dependents: DependentPermission[];
    before: string[];
  } | null>(null);

  const offered = useMemo(() => (teamRole ? catalogue.filter(teamRoleMay) : catalogue), [catalogue, teamRole]);
  const groups = useMemo(() => groupPermissions(offered, value, teamRole), [offered, value, teamRole]);
  const visible = groups
    .map((g) => ({ ...g, entries: g.entries.filter((e) => matches(e, query)) }))
    .filter((g) => g.entries.length > 0);
  const selected = new Set(value);
  // A permission held only through a wildcard: shown granted, and not something to tick or clear here.
  const through = (action: string) => (selected.has(action) ? null : grantedThrough(selected, action));
  const heldIn = (entries: Entry[]) => entries.filter((e) => selected.has(e.action) || through(e.action)).length;
  // A group that holds something starts open, so what the role grants is in view.
  const [open, setOpen] = useState<string[]>(() => groups.filter((g) => heldIn(g.entries) > 0).map((g) => g.id));
  const barred = teamRole ? value.filter((a) => !offered.some((p) => p.action === a)) : [];

  function add(actions: string[]) {
    const result = withRequired(catalogue, value, actions);
    setAdded(result.added);
    setRemoved(null);
    onChange(result.next);
  }

  /** Removes them, and the held permissions that would stop working without them, saying which. */
  function remove(actions: string[]) {
    const dependents = dependentsOf(catalogue, value, actions);
    const gone = new Set([...actions, ...dependents.map((d) => d.permission)]);
    setAdded([]);
    setRemoved(dependents.length > 0 ? { actions, dependents, before: value } : null);
    onChange(value.filter((a) => !gone.has(a)));
  }

  function toggle(action: string) {
    if (selected.has(action)) remove([action]);
    else add([action]);
  }

  function setGroup(group: PermissionGroup, on: boolean) {
    const actions = group.entries.map((e) => e.action);
    const total = actions.length;
    if (on) {
      add(actions.filter((a) => !selected.has(a) && !through(a)));
      setAnnouncement(`${total} of ${total} permissions selected in ${group.title}`);
    } else {
      remove(actions.filter((a) => selected.has(a)));
      setAnnouncement(
        `${heldIn(group.entries.filter((e) => !selected.has(e.action)))} of ${total} permissions selected in ${group.title}`,
      );
    }
  }

  function undo() {
    if (!removed) return;
    onChange(removed.before);
    setRemoved(null);
  }

  return (
    <Stack gap="xs">
      <FieldRow>
        <TextInput
          label="Search permissions"
          leftSection={<IconSearch size="1rem" aria-hidden />}
          value={query}
          onChange={(e) => setQuery(e.currentTarget.value)}
          rightSection={query ? <CloseButton aria-label="Clear search" onClick={() => setQuery('')} size="sm" /> : null}
        />
        <Text size="sm" c="dimmed" className={classes.figure}>
          {value.length} selected
        </Text>
      </FieldRow>

      <VisuallyHidden role="status" aria-live="polite">
        {announcement}
      </VisuallyHidden>

      {barred.length > 0 ? (
        <Notice
          title="Not allowed in a team role"
          tone="warning"
          action={
            <Button size="xs" variant="default" onClick={() => onChange(value.filter((a) => !barred.includes(a)))}>
              Remove them
            </Button>
          }
        >
          A team role holds only permissions that act on a subscription or destination, and team:admin. Saving is refused while it
          holds {barred.join(', ')}.
        </Notice>
      ) : null}

      {added.length > 0 ? (
        <Stack gap={2} role="status" aria-label="Permissions added">
          {added.map((a) => (
            <Text key={a.permission} size="sm">
              Added {a.permission}, required by {a.requiredBy}
            </Text>
          ))}
        </Stack>
      ) : null}

      {removed ? (
        <Notice
          title="Removed with it"
          tone="warning"
          action={
            <Button size="xs" variant="default" onClick={undo}>
              Undo
            </Button>
          }
        >
          {removalConsequence(removed.actions, removed.dependents)}
        </Notice>
      ) : null}

      {pickerNotice(groups.length, visible.length, query, () => setQuery('')) ?? (
        <Accordion
          multiple
          chevronPosition="left"
          value={query.trim() ? visible.map((g) => g.id) : open}
          onChange={setOpen}
        >
          {visible.map((group) => {
            const all = groups.find((g) => g.id === group.id)!.entries;
            const held = heldIn(all);
            // Every permission here comes through a wildcard: there is nothing to clear in this group.
            const onlyThrough = held === all.length && all.every((e) => !selected.has(e.action));
            return (
              <Accordion.Item key={group.id} value={group.id}>
                <Center>
                  <Checkbox
                    aria-label={`Select all in ${group.title}`}
                    checked={held === all.length}
                    indeterminate={held > 0 && held < all.length}
                    disabled={onlyThrough}
                    onChange={() => setGroup({ ...group, entries: all }, held < all.length)}
                    size="md"
                    ms="sm"
                  />
                  <Accordion.Control aria-label={`${group.title}, ${held} of ${all.length} selected`}>
                    <Group justify="space-between" wrap="nowrap">
                      <Text size="sm" fw={500}>
                        {group.title}
                      </Text>
                      <Text size="sm" c="dimmed" className={classes.figure}>
                        {held}/{all.length}
                      </Text>
                    </Group>
                  </Accordion.Control>
                </Center>
                <Accordion.Panel>
                  <Stack gap="xs">
                    {group.entries.map((e) => {
                      const badge = scopeBadge(e, teamRole);
                      const wildcard = through(e.action);
                      return (
                        <Checkbox
                          key={e.action}
                          label={
                            <Group gap={6} wrap="nowrap">
                              <Text size="sm" ff="monospace">
                                {e.action}
                              </Text>
                              {badge ? <StatusBadge>{badge}</StatusBadge> : null}
                            </Group>
                          }
                          description={
                            wildcard ? `${describe(e, teamRole)} Granted through ${wildcard}.` : describe(e, teamRole)
                          }
                          checked={selected.has(e.action) || wildcard !== null}
                          disabled={wildcard !== null}
                          onChange={() => toggle(e.action)}
                        />
                      );
                    })}
                  </Stack>
                </Accordion.Panel>
              </Accordion.Item>
            );
          })}
        </Accordion>
      )}
    </Stack>
  );
}

/** What removing permissions takes with it: the ones that need them, by name. */
function removalConsequence(actions: string[], dependents: DependentPermission[]): string {
  const needing = dependents.map((d) => `${d.permission} (needs ${d.needs})`).join(', ');
  const were = dependents.length === 1 ? 'it was' : 'they were';
  return `${needing} would stop working without ${actions.join(', ')}, so ${were} removed too.`;
}
