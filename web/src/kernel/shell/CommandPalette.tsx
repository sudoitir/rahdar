import { useCallback, useEffect, useMemo, useState } from 'react';
import { Spotlight, type SpotlightActionData, type SpotlightActionGroupData } from '@mantine/spotlight';
import { useDebouncedValue } from '@mantine/hooks';
import { IconSearch } from '@tabler/icons-react';
import { useNavigate, useParams } from '@tanstack/react-router';

import { isPollingPaused, setPollingPaused, usePollingPaused } from '../api/polling.ts';
import { useCan } from '../auth/useCan.ts';
import type { ModuleId, PaletteSource } from '../feature.ts';
import { useFeatures } from '../features.ts';
import { useClusterSystem } from './useClusterSystem.ts';
import { navGroups } from '../registry.ts';
import { densityToggleLabel, useDensity } from '../../ui/table/density.ts';
import { useColorSchemeToggle } from './useColorSchemeToggle.ts';
import { readRecents } from './recents.ts';

type Report = (feature: ModuleId, groups: SpotlightActionGroupData[]) => void;

/** Mounts one feature's palette source, with a `report` that stays the same across renders. */
function Source({
  feature,
  Palette,
  clusterId,
  query,
  opened,
  onReport,
}: Readonly<{
  feature: ModuleId;
  Palette: PaletteSource;
  clusterId?: string;
  query: string;
  opened: boolean;
  onReport: Report;
}>) {
  const report = useCallback((groups: SpotlightActionGroupData[]) => onReport(feature, groups), [feature, onReport]);
  return <Palette clusterId={clusterId} query={query} opened={opened} report={report} />;
}

/**
 * ⌘K navigation across the console: jump to a view, a recent place, a cluster, or a subscription by name, or
 * search a live view (ADR-0109). Mounted once in the root layout; the shortcut is registered by
 * {@link Spotlight}, and the header's Search button opens it too.
 *
 * The typed query reaches the features' sources, debounced, together with whether the palette is
 * open, so a source searches only while someone is looking — and only what Rahdar already holds.
 * A view the operator may not open is still listed, disabled, with the reason: the rail shows it the
 * same way.
 *
 * Pause is here too, so every header control is reachable from the keyboard without a hotkey of its
 * own (ADR-0052, ADR-0118), and so are the user menu's colour scheme and table density (ADR-0159,
 * ADR-0162).
 */
export function CommandPalette() {
  const navigate = useNavigate();
  const params = useParams({ strict: false }) as { clusterId?: string };
  const clusterId = params.clusterId;
  const paused = usePollingPaused();
  const scheme = useColorSchemeToggle();
  const [density, setDensity] = useDensity();
  const features = useFeatures();
  const systemId = useClusterSystem(clusterId);
  const { canAnywhere, loading: grantsLoading } = useCan();
  const [query, setQuery] = useState('');
  const [debounced] = useDebouncedValue(query.trim(), 200);
  const [opened, setOpened] = useState(false);
  const [contributed, setContributed] = useState<Partial<Record<ModuleId, SpotlightActionGroupData[]>>>({});
  const onReport = useCallback<Report>(
    (feature, groups) => setContributed((prev) => ({ ...prev, [feature]: groups })),
    [],
  );

  // Read when the palette opens: a recent is recorded by every navigation, and re-reading storage on
  // each render would be work for a list nobody is looking at.
  const [recents, setRecents] = useState<ReturnType<typeof readRecents>>([]);
  useEffect(() => {
    if (opened && clusterId) setRecents(readRecents(clusterId));
  }, [opened, clusterId]);

  const groups = useMemo<SpotlightActionGroupData[]>(() => {
    const out: SpotlightActionGroupData[] = [];

    if (clusterId && recents.length > 0) {
      out.push({
        group: 'Recent',
        actions: recents.map((r) => ({
          id: `recent-${r.label}`,
          label: r.label,
          description: r.kind,
          onClick: () => navigate({ to: r.to, search: r.search as never }),
        })),
      });
    }

    out.push({
      group: 'Console',
      actions: [
        {
          id: 'toggle-auto-refresh',
          label: paused ? 'Resume auto-refresh' : 'Pause auto-refresh',
          description: paused
            ? 'Start refetching on the usual interval again'
            : 'Stop refetching until you resume; does not survive a reload',
          onClick: () => setPollingPaused(!isPollingPaused()),
        },
        { id: 'toggle-color-scheme', label: scheme.label, onClick: scheme.toggle },
        {
          id: 'toggle-table-density',
          label: densityToggleLabel(density),
          onClick: () => setDensity(density === 'compact' ? 'comfortable' : 'compact'),
        },
      ],
    });

    // The views under the rail's own groups, so a view is found where the rail shows it.
    if (clusterId) {
      for (const navGroup of navGroups(features, systemId)) {
        out.push({
          group: navGroup.label,
          actions: navGroup.items.map((item): SpotlightActionData => {
            // Offered while grants load: a claim about permission is made only once it is known.
            const blocked = !grantsLoading && item.permission !== undefined && !canAnywhere(item.permission, clusterId);
            return {
              id: `view-${item.path}`,
              label: item.label,
              description: blocked ? `Unavailable: needs the ${item.permission} permission on this cluster` : undefined,
              disabled: blocked,
              onClick: () => navigate({ to: `/clusters/${clusterId}/${item.path}` }),
            };
          }),
        });
      }
    }

    // In composition order, and only for features still enabled.
    for (const feature of features) out.push(...(contributed[feature.id] ?? []));
    return out;
  }, [
    clusterId,
    navigate,
    paused,
    scheme,
    density,
    setDensity,
    features,
    systemId,
    contributed,
    recents,
    canAnywhere,
    grantsLoading,
  ]);

  return (
    <>
      {features.map((feature) =>
        feature.palette ? (
          <Source
            key={feature.id}
            feature={feature.id}
            Palette={feature.palette}
            clusterId={clusterId}
            query={debounced}
            opened={opened}
            onReport={onReport}
          />
        ) : null,
      )}
      <Spotlight
        actions={groups}
        shortcut={['mod + K']}
        query={query}
        onQueryChange={setQuery}
        onSpotlightOpen={() => setOpened(true)}
        onSpotlightClose={() => setOpened(false)}
        nothingFound={
          debounced.length > 0 && debounced.length < 2
            ? 'Type one more character to search subscriptions'
            : 'Nothing matches. Subscriptions are searched by name; live views are offered as searches.'
        }
        highlightQuery
        // Opened by ⌘K all day: it is there at once, not animated in.
        transitionProps={{ duration: 0 }}
        scrollable
        maxHeight={480}
        searchProps={{
          placeholder: 'Jump to a cluster, view, or subscription…',
          'aria-label': 'Search views, clusters and subscriptions',
          leftSection: <IconSearch size={16} aria-hidden />,
        }}
      />
    </>
  );
}
