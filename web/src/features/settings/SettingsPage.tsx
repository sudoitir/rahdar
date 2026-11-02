import { useEffect, useMemo, useRef, useState, type ReactNode } from 'react';
import { Switch, TextInput, VisuallyHidden } from '@mantine/core';
import { IconPencil, IconSearch } from '@tabler/icons-react';
import { useNavigate, useParams, useSearch } from '@tanstack/react-router';

import { useMe } from '../../kernel/auth/api.ts';
import { useCan } from '../../kernel/auth/useCan.ts';
import { useManifest } from '../../kernel/manifest.ts';
import { SectionNav, type SectionTab } from '../../kernel/shell/SectionNav.tsx';
import { useClusterSlot } from '../../kernel/shell/useClusterSlot.ts';
import { SETTINGS_GROUPS } from '../../kernel/slots.ts';
import { EmptyState } from '../../ui/EmptyState.tsx';
import { ErrorState } from '../../ui/ErrorState.tsx';
import { LoadingState } from '../../ui/LoadingState.tsx';
import { Page } from '../../ui/Page.tsx';
import { PageHeader } from '../../ui/PageHeader.tsx';
import { Section } from '../../ui/Section.tsx';
import { Toolbar } from '../../ui/Toolbar.tsx';
import { useSettings } from './api.ts';
import { ApplyBar } from './ApplyBar.tsx';
import { useApplyFlow } from './useApplyFlow.ts';
import { CategoryForm } from './CategoryForm.tsx';
import { SettingsDraftProvider } from './draft.tsx';
import { useSettingsDraft } from './draftContext.ts';
import { categoriesOf, matches, searchTerms } from './model.ts';
import classes from './Settings.module.css';

/** What the address holds about this page: the open tab, the search, and whether only modified settings show. */
export interface SettingsSearch {
  tab?: string;
  q?: string;
  modified?: boolean;
}

/**
 * The page's search from the address. The router reads each value as JSON, so a search for `123` arrives as a
 * number and a repeated `q` as a list; a search is always the text typed, and anything else is no search.
 */
export function settingsSearch(raw: Record<string, unknown>): SettingsSearch {
  const q = typeof raw.q === 'number' ? String(raw.q) : raw.q;
  return {
    ...(typeof raw.tab === 'string' && raw.tab ? { tab: raw.tab } : {}),
    ...(typeof q === 'string' && q ? { q } : {}),
    ...(raw.modified === true || raw.modified === 'true' ? { modified: true } : {}),
  };
}

/**
 * A cluster's Settings page, behind `settings:read`: one tab per section the features contribute and one per
 * settings category, under fixed headings in the order an operator's reach widens — what Rahdar shares, this
 * cluster, then what plugins added (operator-ui spec). Personal display preferences are on the account page,
 * and the installation's encryption keys and health under Administration. One search finds settings across every
 * category. Edits form one draft across categories, applied together from the footer. The open tab and the
 * search are in the address.
 */
export function SettingsPage() {
  const search = settingsSearch(useSearch({ strict: false }));
  const navigate = useNavigate();
  const settings = useSettings();
  const filtering = Boolean(search.q?.trim()) || Boolean(search.modified);

  const setSearch = (next: Partial<SettingsSearch>) =>
    void navigate({
      to: '.',
      search: (prev: Record<string, unknown>) => ({ ...prev, ...next }),
      replace: true,
    });
  const clearFilters = () => setSearch({ q: undefined, modified: undefined });

  let body: ReactNode;
  if (settings.isPending) {
    // The frame holds the height of a tab list and a category, so the page does not jump when it fills.
    body = <LoadingState label="Loading settings" blockSize="32rem" />;
  } else if (settings.isError) {
    body = (
      <SettingsTabs
        search={search}
        categoryTabs={[
          {
            id: 'configuration',
            title: 'Configuration',
            group: 'rahdar',
            panel: (
              <Section title="Configuration">
                <ErrorState error={settings.error} onRetry={() => void settings.refetch()} />
              </Section>
            ),
          },
        ]}
        onClearFilters={clearFilters}
      />
    );
  } else {
    body = (
      <SettingsDraftProvider settings={settings.data.settings}>
        <DraftedSettings search={search} onClearFilters={clearFilters} />
      </SettingsDraftProvider>
    );
  }

  return (
    <Page>
      <PageHeader
        title="Settings"
        description="What Rahdar shares across clusters, and this cluster's configuration."
      />
      <Toolbar
        label="Find settings"
        start={
          <>
            <SearchBox value={search.q ?? ''} onChange={(q) => setSearch({ q: q || undefined })} />
            <Switch
              label="Modified only"
              checked={Boolean(search.modified)}
              onChange={(event) => setSearch({ modified: event.currentTarget.checked || undefined })}
              size="xs"
              className={classes.modifiedOnly}
            />
          </>
        }
        end={
          filtering ? (
            <VisuallyHidden role="status">
              {search.q?.trim() ? 'Showing settings that match the search.' : 'Showing modified settings only.'}
            </VisuallyHidden>
          ) : undefined
        }
      />
      {body}
    </Page>
  );
}

/**
 * The search field. It holds what is typed itself, so a keystroke never waits for the address to change, and
 * takes the address's value when that changes from elsewhere ("Clear filters", back and forward).
 */
function SearchBox({ value, onChange }: Readonly<{ value: string; onChange: (value: string) => void }>) {
  const [typed, setTyped] = useState(value);
  useEffect(() => setTyped((current) => (current.trim() === value.trim() ? current : value)), [value]);
  return (
    <TextInput
      label="Search settings"
      placeholder="Name, key or description"
      leftSection={<IconSearch size={14} aria-hidden />}
      value={typed}
      onChange={(event) => {
        setTyped(event.currentTarget.value);
        onChange(event.currentTarget.value);
      }}
      w="20rem"
      size="xs"
      type="search"
    />
  );
}

/** A tab for a settings category, with the heading it sits under. */
type CategoryTab = SectionTab & { group: 'rahdar' | 'plugins' };

/** The categories as forms, the draft's footer and the guard against leaving with unsaved edits. */
function DraftedSettings({ search, onClearFilters }: Readonly<{ search: SettingsSearch; onClearFilters: () => void }>) {
  const draft = useSettingsDraft();
  const manifest = useManifest().data;
  const me = useMe().data;
  const { can, loading } = useCan();
  // While grants load, offer the controls; the server is the enforcement point.
  const canWrite = loading || can('settings:write');
  const tabsRef = useRef<HTMLDivElement>(null);

  const plugins = useMemo(
    () => new Map(manifest?.features.filter((f) => f.origin === 'PLUGIN').map((f) => [f.id, f.title])),
    [manifest],
  );
  const categories = useMemo(() => categoriesOf(draft.settings, plugins), [draft.settings, plugins]);
  const terms = searchTerms(search.q);
  const changedKeys = new Set(draft.changes.map((c) => c.key));
  // After the draft is applied or sent, focus returns to the open section rather than to the page's start.
  const flow = useApplyFlow(categories, () =>
    requestAnimationFrame(() =>
      tabsRef.current?.querySelector<HTMLElement>('[data-section-panel]')?.focus({ preventScroll: true }),
    ),
  );

  const tabs: CategoryTab[] = [];
  for (const category of categories) {
    const shown = new Set(
      category.keys.filter((key) => {
        const setting = draft.settings[key];
        if (!setting || !matches(key, setting, terms)) return false;
        return !search.modified || setting.overridden || changedKeys.has(key);
      }),
    );
    if (shown.size === 0) continue;
    const edits = draft.changedIn.get(category.id) ?? 0;
    tabs.push({
      id: category.id,
      title: category.title,
      group: category.group,
      aside: (
        <TabAside
          matches={terms.length > 0 || search.modified ? shown.size : undefined}
          edits={edits}
          title={category.title}
        />
      ),
      panel: (
        <Section title={category.title}>
          <CategoryForm
            category={category}
            shown={shown}
            terms={terms}
            canWrite={canWrite}
            username={me?.username}
            onSubmit={flow.reviewFromField}
          />
        </Section>
      ),
    });
  }

  return (
    <>
      <div ref={tabsRef}>
        <SettingsTabs search={search} categoryTabs={tabs} onClearFilters={onClearFilters} />
      </div>
      <ApplyBar state={flow} />
    </>
  );
}

/** A category's match count while filtering, and a marker when it holds unsaved edits, both read in words. */
function TabAside({ matches, edits, title }: Readonly<{ matches: number | undefined; edits: number; title: string }>) {
  if (matches === undefined && edits === 0) return null;
  const noun = edits === 1 ? 'change' : 'changes';
  const words = [
    matches === undefined ? null : `${matches} matching`,
    edits === 0 ? null : `${edits} unsaved ${noun}`,
  ].filter(Boolean);
  return (
    <span className={classes.tabAside} title={`${title}: ${words.join(', ')}`}>
      {matches === undefined ? null : (
        <span className={classes.matchCount} aria-hidden>
          {matches}
        </span>
      )}
      {edits === 0 ? null : <IconPencil size={14} aria-hidden className={classes.editMark} />}
      <VisuallyHidden>, {words.join(', ')}</VisuallyHidden>
    </span>
  );
}

/**
 * The page's tabs: the features' sections and the given categories, under their headings. While a search
 * or the modified filter is on, a feature's section shows only when its title matches the search, and a
 * search that matches nothing says so and offers to clear it.
 */
function SettingsTabs({
  search,
  categoryTabs,
  onClearFilters,
}: Readonly<{ search: SettingsSearch; categoryTabs: CategoryTab[]; onClearFilters: () => void }>) {
  const { clusterId } = useParams({ strict: false }) as { clusterId: string };
  const sections = useClusterSlot('settings.sections', clusterId);
  const terms = searchTerms(search.q);
  const filtering = terms.length > 0 || Boolean(search.modified);

  const groups = SETTINGS_GROUPS.map((group) => {
    const contributed = sections
      .filter((section) => (section.group ?? 'plugins') === group.id)
      .filter((section) => {
        if (search.modified) return false;
        const title = (section.title ?? section.id).toLowerCase();
        return terms.every((term) => title.includes(term));
      })
      .map(({ id, title, Component }) => ({
        id,
        title: title ?? id,
        panel: (
          <Section title={title ?? id}>
            <Component clusterId={clusterId} />
          </Section>
        ),
        preload: (Component as { preload?: () => void }).preload,
      }));
    // A group's settings categories come first: they are what most visits here are for.
    const categories = categoryTabs.filter((tab) => tab.group === group.id);
    return { ...group, tabs: [...categories, ...contributed] };
  });

  if (filtering && groups.every((group) => group.tabs.length === 0)) {
    return (
      <EmptyState
        kind="filtered"
        title={search.q?.trim() ? `No settings match “${search.q.trim()}”` : 'No settings are modified'}
        description={
          search.modified
            ? 'Every setting that matches still has its packaged default. Clear the filters to see them all.'
            : 'Nothing in a setting’s name, key, description or category contains every word of the search.'
        }
        onClearFilters={onClearFilters}
      />
    );
  }

  return <SectionNav label="Settings sections" groups={groups} keep={['q', 'modified']} />;
}
