import { useEffect, useEffectEvent } from 'react';
import { AppShell, Divider, Kbd, ScrollArea, Text, Tooltip, UnstyledButton } from '@mantine/core';
import { spotlight } from '@mantine/spotlight';
import { IconSearch } from '@tabler/icons-react';
import { useDocumentTitle, useHotkeys } from '@mantine/hooks';
import { Link, Outlet, useLocation, useNavigate, useParams } from '@tanstack/react-router';

import styles from './RootLayout.module.css';
import { branding } from '../../branding.ts';
import { useMe } from '../auth/api.ts';
import { usePluginsChanged } from '../plugins/usePluginsChanged.tsx';
import { useSlot } from '../slots.ts';
import { BreakGlassBanner } from '../approvals/BreakGlassBanner.tsx';
import { useGateStatus } from '../approvals/api.ts';
import { InboxBell } from '../inbox/InboxBell.tsx';
import { LoadingState } from '../../ui/LoadingState.tsx';
import { ClusterViewNav } from './ClusterViewNav.tsx';
import { CommandPalette } from './CommandPalette.tsx';
import { FreshnessBar } from './FreshnessBar.tsx';
import { GlobalContext } from './GlobalContext.tsx';
import { NavToggle } from './NavToggle.tsx';
import { UserMenu } from './UserMenu.tsx';
import { useNavCollapsed } from './useNavCollapsed.ts';
import { useCurrentView } from '../nav/currentView.ts';
import { useTitleParts } from './pageTitle.ts';
import { recordLastPlace, useLastPlace } from './lastPlace.ts';
import { recordRecent } from './recents.ts';
import { modShortcut } from '../keyboard/keys.ts';
import { ShortcutsHelp } from '../keyboard/ShortcutsHelp.tsx';
import { useKeySequences } from '../keyboard/useKeySequences.ts';

const NAVBAR_ID = 'as-navbar';
const MAIN_ID = 'as-main';
const PUBLIC_PATHS = new Set(['/login', '/change-password', '/enrol-second-factor']);

/**
 * The desktop workspace chrome: a fixed header, the collapsible sidebar (the features' way between
 * clusters, then the open cluster's view nav, ADR-0034), and the routed detail column.
 * Desktop-first: no phone or tablet layout (ADR-0164).
 *
 * The sidebar collapses to an icon rail rather than disappearing: `AppShell`'s
 * own `collapsed` prop removes the navbar's width entirely, which is the wrong
 * shape for a rail that stays present with icons, so `navbar.width` switches
 * between two theme tokens (`--as-nav-w`, `--as-nav-rail-w`) instead. The switch
 * is instant: `AppShell` animates neither the navbar's width nor anything but the
 * main column's padding, so a transition only reflowed the whole page for its
 * length, and ⌘B is a keyboard action repeated all day. The rail is also what a
 * window narrower than 64rem gets (`useNavCollapsed`).
 */
export function RootLayout() {
  const { collapsed, forced, toggle } = useNavCollapsed();
  const { clusterId } = useParams({ strict: false }) as { clusterId?: string };
  const location = useLocation();
  const navigate = useNavigate();
  const isPublicRoute = PUBLIC_PATHS.has(location.pathname);
  const me = useMe();
  // Asked beside the session, not after it: the break-glass banner above every page is then in the first paint
  // instead of pushing the page down when it arrives.
  const gate = useGateStatus({ enabled: !isPublicRoute });
  const header = useSlot('shell.header');
  const navbar = useSlot('shell.navbar');

  useEffect(() => {
    if (isPublicRoute) return;
    if (me.isError && me.error.status === 401) {
      void navigate({ to: '/login' });
    } else if (me.data?.mustChangePassword && location.pathname !== '/change-password') {
      void navigate({ to: '/change-password' });
    } else if (me.data?.secondFactorEnrolmentRequired && !me.data.mustChangePassword) {
      // The session may enrol a second factor and do nothing else until it has (identity-and-sessions spec).
      void navigate({ to: '/enrol-second-factor' });
    }
  }, [isPublicRoute, me.isError, me.error, me.data, location.pathname, navigate]);

  // Every tab says what it holds (ADR-0109): an operator with six Rahdar tabs open finds the one
  // on prod's Subscriptions without opening each.
  const view = useCurrentView();
  const titleParts = useTitleParts();
  useDocumentTitle(
    [titleParts.resource, view?.item?.label, view ? titleParts.cluster : undefined, branding.productName]
      .filter(Boolean)
      .join(' · '),
  );

  // Every place visited feeds the palette's Recent group: a view, or the resource open in it.
  const recentLabel = view?.item ? titleParts.resource || view.item.label : undefined;
  // The search is part of the place (the open subscription), but a filter typed into it is not a new place:
  // the effect event reads it without making it a trigger.
  const record = useEffectEvent(() => {
    if (!view?.item || !recentLabel) return;
    recordRecent(view.clusterId, {
      label: recentLabel,
      kind: titleParts.resource ? `In ${view.item.label}` : `View · ${view.groupLabel ?? ''}`.replace(/ · $/, ''),
      to: location.pathname,
      search: (location.search ?? {}) as Record<string, unknown>,
    });
  });
  const recentCluster = view?.clusterId;
  const recentItem = view?.item;
  useEffect(() => {
    record();
  }, [recentCluster, recentItem, recentLabel, location.pathname]);

  // The last place in a cluster is what a page outside any cluster leads back to.
  const lastPlace = useLastPlace();
  const placeCluster = view?.clusterId;
  const placeName = titleParts.cluster;
  const placeLabel = view?.item?.label ?? 'Topology';
  useEffect(() => {
    if (!placeCluster || !placeName) return;
    recordLastPlace({
      clusterId: placeCluster,
      clusterName: placeName,
      label: placeLabel,
      to: location.pathname,
      search: (location.search ?? {}) as Record<string, unknown>,
    });
    // The search is part of the place, but a filter typed into it is not worth a write per keystroke.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [placeCluster, placeName, placeLabel, location.pathname]);

  useHotkeys([['mod+B', toggle]]);
  useKeySequences();
  usePluginsChanged();

  // Every hook above runs unconditionally on every render; only the JSX branches.
  if (isPublicRoute) {
    return <Outlet />;
  }

  if (me.isLoading || gate.isLoading) {
    return <LoadingState label="Loading Rahdar" blockSize="100dvh" />;
  }

  if (me.isError || me.data?.mustChangePassword || me.data?.secondFactorEnrolmentRequired) {
    // The effect above is already navigating away; render nothing in the meantime.
    return null;
  }

  return (
    <AppShell
      header={{ height: 56 }}
      navbar={
        // A page outside any cluster has no sidebar: it would hold only a cluster picker, and the space
        // is the page's. The way back to a cluster is the line above the page (`GlobalContext`).
        clusterId ? { width: collapsed ? 'var(--as-nav-rail-w)' : 'var(--as-nav-w)', breakpoint: 0 } : undefined
      }
      padding="lg"
      transitionDuration={0}
    >
      <a href={`#${MAIN_ID}`} className={styles.skipLink}>
        Skip to content
      </a>
      <AppShell.Header>
        <div className={styles.header}>
          <div className={styles.headerStart}>
            <Link to={lastPlace?.to ?? '/'} search={lastPlace?.search as never} className={styles.brand}>
              <img src="/favicon.svg" alt="" width={24} height={24} />
              <Text component="span" fw={600} truncate>
                {branding.productName}
              </Text>
            </Link>
            {header.map(({ id, Component }) => (
              <Component key={id} />
            ))}
          </div>
          <Tooltip.Group openDelay={500} closeDelay={80}>
            <div className={styles.headerEnd}>
              {/* A visible way into the palette: a shortcut nobody can see is one nobody finds. */}
              <UnstyledButton
                className={styles.search}
                onClick={() => spotlight.open()}
                aria-keyshortcuts="Meta+K Control+K"
              >
                <IconSearch size={16} stroke={1.5} aria-hidden />
                <span className={styles.searchText}>Search or jump to…</span>
                <Kbd size="xs" className={styles.kbd}>
                  {modShortcut('K')}
                </Kbd>
              </UnstyledButton>
              <FreshnessBar />
              {/* The data's state on the left of the rule, the console's own controls on the right. */}
              <Divider orientation="vertical" />
              <InboxBell />
              <ShortcutsHelp />
              <UserMenu me={me.data} />
            </div>
          </Tooltip.Group>
        </div>
      </AppShell.Header>

      {clusterId ? (
        <AppShell.Navbar id={NAVBAR_ID} p="sm">
          <Tooltip.Group openDelay={350} closeDelay={80}>
            <AppShell.Section grow component={ScrollArea}>
              {navbar.map(({ id, Component }) => (
                <Component key={id} collapsed={collapsed} />
              ))}
              {clusterId ? <ClusterViewNav clusterId={clusterId} collapsed={collapsed} /> : null}
            </AppShell.Section>
            <AppShell.Section className={styles.navFooter} data-collapsed={collapsed || undefined}>
              <NavToggle collapsed={collapsed} forced={forced} onToggle={toggle} controls={NAVBAR_ID} />
            </AppShell.Section>
          </Tooltip.Group>
        </AppShell.Navbar>
      ) : null}

      <AppShell.Main id={MAIN_ID} className={styles.main}>
        <BreakGlassBanner />
        {clusterId || location.pathname === '/' ? null : (
          <div className={styles.global}>
            <GlobalContext />
          </div>
        )}
        <Outlet />
      </AppShell.Main>

      <CommandPalette />
    </AppShell>
  );
}
