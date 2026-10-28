import { useEffect, useState, type ComponentType } from 'react';
import { ColorSwatch, Combobox, Skeleton, VisuallyHidden, useCombobox } from '@mantine/core';
import {
  IconAlertOctagon,
  IconAlertTriangle,
  IconCheck,
  IconCircleCheck,
  IconHelpCircle,
  IconLayoutList,
  IconPlus,
  IconSelector,
} from '@tabler/icons-react';
import { useNavigate } from '@tanstack/react-router';

import { sameViewOn, useCurrentView } from '../../kernel/nav/currentView.ts';
import { useClusters, useEnvironments, type ClusterSummary, type EnvironmentView } from './api.ts';
import { HEALTH_WORD } from './clusterHealth.ts';
import styles from './ClusterSwitcher.module.css';

const REGISTER = 'register';
const RETRY = 'retry';
const ALL = 'all';

/** Health in words, with an icon that says it again; colour only emphasises a fault. */
const HEALTH: Record<
  ClusterSummary['health'],
  { word: string; Icon: ComponentType<{ className?: string; 'aria-hidden'?: boolean }>; tone?: 'warning' | 'danger' }
> = {
  OK: { word: HEALTH_WORD.OK, Icon: IconCircleCheck },
  DEGRADED: { word: HEALTH_WORD.DEGRADED, Icon: IconAlertTriangle, tone: 'warning' },
  CRITICAL: { word: HEALTH_WORD.CRITICAL, Icon: IconAlertOctagon, tone: 'danger' },
  UNKNOWN: { word: HEALTH_WORD.UNKNOWN, Icon: IconHelpCircle },
};

interface Entry {
  cluster: ClusterSummary;
  environment: EnvironmentView | null;
}

const nodes = (count: number) => `${count} node${count === 1 ? '' : 's'}`;

/** The environment's colour is the administrator's own mark; the name beside it carries the meaning. */
const colourOf = (environment: EnvironmentView) => environment.colour ?? 'var(--as-border)';

function Health({ level }: Readonly<{ level: ClusterSummary['health'] }>) {
  const { word, Icon, tone } = HEALTH[level];
  return (
    <span className={styles.health} data-tone={tone}>
      <Icon className={styles.icon} aria-hidden />
      {word}
    </span>
  );
}

function Environment({ environment }: Readonly<{ environment: EnvironmentView }>) {
  return (
    <span className={styles.environment}>
      <ColorSwatch component="span" color={colourOf(environment)} size="0.75rem" />
      <span className={styles.ellipsis}>{environment.name}</span>
    </span>
  );
}

/** The environments in their order, each with the entries matching `query`; clusters of no environment last. */
function grouped(clusters: ClusterSummary[], environments: EnvironmentView[], query: string) {
  const byId = new Map(environments.map((environment) => [environment.id, environment]));
  const needle = query.trim().toLowerCase();
  const entries: Entry[] = clusters
    .map((cluster) => ({ cluster, environment: byId.get(cluster.environmentId ?? '') ?? null }))
    .filter(({ cluster, environment }) =>
      `${cluster.name} ${environment?.name ?? ''} ${HEALTH[cluster.health].word}`.toLowerCase().includes(needle),
    )
    .sort((a, b) => a.cluster.name.localeCompare(b.cluster.name, undefined, { numeric: true }));
  const ordered = [...environments].sort((a, b) => a.sortOrder - b.sortOrder);
  return [...ordered, null]
    .map((environment) => ({
      environment,
      entries: entries.filter((entry) => entry.environment?.id === environment?.id),
    }))
    .filter((group) => group.entries.length > 0);
}

/**
 * The face of the switcher: one box of one height whatever it holds, so the navigation under it never
 * moves. The loading state, the chosen cluster and "Choose a cluster" are all this box.
 */
/** The two letters a cluster shows on the collapsed rail. */
function monogramOf(name: string): string {
  return name.slice(0, 2).toUpperCase();
}

function Face({
  collapsed,
  entry,
  loading = false,
}: Readonly<{ collapsed: boolean; entry?: Entry; loading?: boolean }>) {
  if (loading) {
    return (
      <output className={styles.face} data-collapsed={collapsed || undefined}>
        <VisuallyHidden>Loading clusters</VisuallyHidden>
        {collapsed ? (
          <Skeleton className={styles.monogram} />
        ) : (
          <>
            <div className={styles.text}>
              <Skeleton height="1rem" width="60%" />
              <Skeleton height="0.75rem" width="85%" />
            </div>
            <Skeleton className={styles.icon} />
          </>
        )}
      </output>
    );
  }
  if (!entry) {
    return collapsed ? (
      <IconSelector className={styles.icon} aria-hidden />
    ) : (
      <>
        <span className={styles.text}>
          <span className={styles.name}>Choose a cluster</span>
        </span>
        <IconSelector className={styles.icon} aria-hidden />
      </>
    );
  }
  const { cluster, environment } = entry;
  if (collapsed) {
    return (
      <span
        className={styles.monogram}
        style={environment ? { borderColor: colourOf(environment) } : undefined}
        aria-hidden="true"
      >
        {monogramOf(cluster.name)}
      </span>
    );
  }
  return (
    <>
      <span className={styles.text}>
        <span className={styles.line}>
          <span className={`${styles.name} ${styles.ellipsis}`}>{cluster.name}</span>
          <span className={styles.count}>{nodes(cluster.nodeCount)}</span>
        </span>
        <span className={styles.line}>
          {environment ? <Environment environment={environment} /> : null}
          <Health level={cluster.health} />
        </span>
      </span>
      <IconSelector className={styles.icon} aria-hidden />
    </>
  );
}

/**
 * The way between clusters (`shell.navbar`): one row of constant height showing the cluster the address
 * is under, opening a searchable list of every cluster, grouped by environment, with registration at its
 * end. Outside a cluster it says "Choose a cluster". Choosing one keeps the view (ADR-0109): comparing
 * Subscriptions on two clusters is a choice and a click.
 *
 * Collapsed to the rail it is the cluster's monogram, bordered in its environment's colour.
 */
export function ClusterSwitcher({ collapsed }: Readonly<{ collapsed: boolean }>) {
  const clusters = useClusters();
  const environments = useEnvironments();
  const view = useCurrentView();
  const navigate = useNavigate();
  const [search, setSearch] = useState('');
  const combobox = useCombobox({
    onDropdownOpen: () => {
      combobox.focusSearchInput();
      combobox.selectFirstOption();
    },
    onDropdownClose: (source) => {
      combobox.resetSelectedOption();
      setSearch('');
      // Escape and Tab leave the list where the operator was; a click elsewhere leaves focus there.
      if (source === 'keyboard') combobox.focusTarget();
    },
  });

  // Enter chooses what the keyboard's cursor is on, so after the list narrows the cursor goes to its first match.
  const { selectFirstOption } = combobox;
  useEffect(() => {
    selectFirstOption();
  }, [search, selectFirstOption]);

  if (clusters.isPending) return <Face collapsed={collapsed} loading />;

  const all = clusters.data ?? [];
  const groups = grouped(all, environments.data ?? [], search);
  const current = all.find((cluster) => cluster.id === view?.clusterId);
  const entry = current && {
    cluster: current,
    environment: environments.data?.find((environment) => environment.id === current.environmentId) ?? null,
  };
  let label = 'Choose a cluster';
  if (entry) {
    const environment = entry.environment ? `${entry.environment.name}, ` : '';
    label = `Switch cluster, now ${entry.cluster.name}, ${environment}${HEALTH[entry.cluster.health].word}`;
    // The name starts with the monogram the rail shows, so it can be said as it is read (WCAG 2.5.3).
    if (collapsed) label = `${monogramOf(entry.cluster.name)}, ${label}`;
  }

  function choose(value: string) {
    combobox.closeDropdown();
    // Back on the trigger first: a hidden list cannot hold focus.
    combobox.targetRef.current?.focus();
    if (value === REGISTER) void navigate({ to: '/clusters/new' });
    else if (value === RETRY) void clusters.refetch();
    else if (value === ALL) void navigate({ to: '/' });
    else void navigate({ to: sameViewOn(value, view, all.find((c) => c.id === value)?.system.id) });
  }

  let empty: string | null = null;
  if (clusters.isError) empty = `Clusters could not be loaded. ${clusters.error.message}`;
  else if (all.length === 0) empty = 'No clusters are registered yet.';
  else if (groups.length === 0) empty = 'No cluster matches this search.';

  return (
    <>
      <Combobox
        store={combobox}
        onOptionSubmit={choose}
        width="max-content"
        position={collapsed ? 'right-start' : 'bottom-start'}
        classNames={{ dropdown: styles.dropdown, search: styles.search, option: styles.row }}
      >
        {/* The target's own props would clear `aria-expanded`: it is given here, where they are merged last. */}
        <Combobox.Target targetType="button" {...{ 'aria-expanded': combobox.dropdownOpened }}>
          <button
            type="button"
            className={styles.face}
            data-collapsed={collapsed || undefined}
            aria-label={collapsed ? label : undefined}
            onClick={() => combobox.toggleDropdown()}
          >
            <Face collapsed={collapsed} entry={entry} />
          </button>
        </Combobox.Target>
        <Combobox.Dropdown>
          <Combobox.Search
            value={search}
            onChange={(event) => setSearch(event.currentTarget.value)}
            placeholder="Search clusters"
            aria-label="Search clusters"
            onKeyDown={(event) => {
              if (event.key === 'Tab') {
                event.preventDefault();
                combobox.closeDropdown('keyboard');
              }
            }}
          />
          <Combobox.Options aria-label="Clusters">
            {groups.map(({ environment, entries }) => (
              <Combobox.Group
                key={environment?.id ?? 'none'}
                label={
                  <span className={styles.groupLabel}>
                    {environment ? <Environment environment={environment} /> : 'No environment'}
                  </span>
                }
              >
                {entries.map(({ cluster }) => (
                  <Combobox.Option
                    key={cluster.id}
                    value={cluster.id}
                    active={cluster.id === current?.id}
                    aria-selected={cluster.id === current?.id}
                  >
                    <span className={styles.option}>
                      <span className={styles.line}>
                        <span className={`${styles.name} ${styles.ellipsis}`}>{cluster.name}</span>
                        {cluster.id === current?.id ? <IconCheck className={styles.icon} aria-hidden /> : null}
                        <span className={styles.count}>{nodes(cluster.nodeCount)}</span>
                      </span>
                      <span className={styles.line}>
                        {environment ? <span className={styles.ellipsis}>{environment.name}</span> : null}
                        <Health level={cluster.health} />
                      </span>
                    </span>
                  </Combobox.Option>
                ))}
              </Combobox.Group>
            ))}
            {search.trim() === '' ? (
              <Combobox.Option value={ALL} className={styles.all}>
                <IconLayoutList className={styles.icon} aria-hidden />
                All clusters
              </Combobox.Option>
            ) : null}
            {empty ? <Combobox.Empty>{empty}</Combobox.Empty> : null}
            {clusters.isError ? <Combobox.Option value={RETRY}>Try again</Combobox.Option> : null}
            <Combobox.Option value={REGISTER} className={styles.register}>
              <IconPlus className={styles.icon} aria-hidden />
              Register cluster
            </Combobox.Option>
          </Combobox.Options>
        </Combobox.Dropdown>
      </Combobox>
    </>
  );
}
