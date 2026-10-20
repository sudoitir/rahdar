import { useRef, useState } from 'react';
import { Button, Text, TextInput } from '@mantine/core';
import { useNavigate, useSearch } from '@tanstack/react-router';

import { useFilterShortcut } from '../../kernel/keyboard/filterShortcut.ts';
import { CapabilityGate } from '../../ui/CapabilityGate.tsx';
import { EmptyState } from '../../ui/EmptyState.tsx';
import { ErrorState } from '../../ui/ErrorState.tsx';
import { LoadingState } from '../../ui/LoadingState.tsx';
import { Notice } from '../../ui/Notice.tsx';
import { Section } from '../../ui/Section.tsx';
import { DataTable } from '../../ui/table/index.ts';
import { useClusters } from '../clusters/index.ts';
import { useTeams, type TeamSummary } from './api.ts';
import { ownedByCluster, teamColumns, teamSearch } from './teamColumns.tsx';
import { DeleteTeam, TeamNameDialog, type Naming } from './teamDialogs.tsx';
import { useTeamAccess } from './teamAccess.ts';
import { TeamPage } from './TeamPage.tsx';

const rowKey = (t: TeamSummary) => t.id;

type TeamsSearch = { team?: string; teamTab?: string; teamQ?: string; teamSort?: string };

/** How each sortable column orders two teams, ascending. */
const ORDER: Record<string, (a: TeamSummary, b: TeamSummary) => number> = {
  name: (a, b) => a.name.localeCompare(b.name),
  patterns: (a, b) => a.patterns.length - b.patterns.length,
  members: (a, b) => a.memberCount - b.memberCount,
  shares: (a, b) => a.sharesOut + a.sharesIn - (b.sharesOut + b.sharesIn),
};

/**
 * Teams (team-access spec): who owns which subscription and destination name patterns, and who may act on them.
 * The open team, and the list's filter and sort, are in the address, so each can be shared.
 */
export function TeamsPanel() {
  const search = useSearch({ strict: false }) as TeamsSearch;
  const navigate = useNavigate();
  // The team just created: it opens on its patterns with the first field focused, once.
  const [created, setCreated] = useState<string | null>(null);
  const openTeam = (team?: string, replace = false) => void navigate({ to: '.', search: teamSearch(team), replace });

  return search.team ? (
    <TeamPage
      key={search.team}
      teamId={search.team}
      focusNewPattern={created === search.team}
      onFocused={() => setCreated(null)}
      // Gone: the list replaces it in the history, so Back never returns to a team that no longer exists.
      onDeleted={() => openTeam(undefined, true)}
    />
  ) : (
    <TeamList
      search={search}
      onCreated={(id) => {
        setCreated(id);
        openTeam(id);
      }}
    />
  );
}

function TeamList({ search, onCreated }: Readonly<{ search: TeamsSearch; onCreated: (teamId: string) => void }>) {
  const teams = useTeams();
  const navigate = useNavigate();
  const { userAdmin, loading: accessLoading, verdict } = useTeamAccess();
  const [naming, setNaming] = useState<Naming | null>(null);
  // The dialog keeps what it was about while it fades out, so its words do not change under the reader.
  const [deleting, setDeleting] = useState<TeamSummary | null>(null);
  const [deleteOpen, setDeleteOpen] = useState(false);
  const filterRef = useRef<HTMLInputElement>(null);
  useFilterShortcut(filterRef);

  const clusters = useClusters();
  const clusterName = (id: string) => clusters.data?.find((c) => c.id === id)?.name ?? 'Unknown cluster';
  const columns = teamColumns({
    clusterName,
    editable: userAdmin,
    onRename: setNaming,
    onDelete: (t) => {
      setDeleting(t);
      setDeleteOpen(true);
    },
  });

  const setSearch = (next: Partial<TeamsSearch>) =>
    void navigate({ to: '.', search: (prev: Record<string, unknown>) => ({ ...prev, ...next }), replace: true });
  const query = search.teamQ ?? '';
  const q = query.trim().toLowerCase();
  const all = teams.data ?? [];
  const matching = all.filter(
    (t) =>
      q === '' ||
      t.name.toLowerCase().includes(q) ||
      ownedByCluster(t.patterns, clusterName).some((line) => line.toLowerCase().includes(q)),
  );
  const field = search.teamSort?.replace(/^-/, '');
  const order = field ? ORDER[field] : undefined;
  const direction = search.teamSort?.startsWith('-') ? -1 : 1;
  const rows = order ? [...matching].sort((a, b) => direction * order(a, b)) : matching;

  const title = 'Teams';
  const description =
    'A team owns subscription and destination name patterns on clusters. Its members see and operate only what those patterns cover. Open a team by its name.';

  // Until access is known the caller counts as an admin, so a non-admin's notice would appear late and push the
  // table down: wait for it, so the notice and the table arrive together.
  if (accessLoading) {
    return (
      <Section title={title} description={description}>
        <LoadingState label="Loading teams" />
      </Section>
    );
  }

  const create = (
    <CapabilityGate verdict={verdict('Creating a team')} what="creating a team">
      <Button disabled={!userAdmin} onClick={() => setNaming('new')}>
        New team
      </Button>
    </CapabilityGate>
  );

  let empty;
  if (q !== '' && all.length > 0) {
    empty = (
      <EmptyState
        kind="filtered"
        title={`No team matches “${query.trim()}”`}
        description="No team has that in its name or its patterns."
        onClearFilters={() => setSearch({ teamQ: undefined })}
      />
    );
  } else if (userAdmin) {
    empty = (
      <EmptyState
        kind="empty"
        title="No teams"
        description="A team owns subscription and destination name patterns on clusters, and its members see and operate only what those patterns cover. Create one to share a cluster between groups of people."
        action={
          <Button size="xs" onClick={() => setNaming('new')}>
            Create team
          </Button>
        }
      />
    );
  } else {
    empty = (
      <EmptyState
        kind="empty"
        title="No teams"
        description="You do not administer a team yet. Ask a user administrator to make you a team admin of one."
      />
    );
  }

  return (
    <Section title={title} description={description}>
      {userAdmin ? null : (
        <Notice title="Team admin">
          You see the teams you administer. Creating, renaming and deleting teams, and changing a team&apos;s patterns
          and shares, need the user:admin permission. You can change who is a member.
        </Notice>
      )}
      <DataTable
        variant="static"
        label="Teams"
        storageKey="security.teams"
        columns={columns}
        data={rows}
        rowKey={rowKey}
        sort={search.teamSort}
        onSortChange={(teamSort) => setSearch({ teamSort })}
        loading={teams.isPending}
        error={teams.isError ? <ErrorState error={teams.error} onRetry={() => void teams.refetch()} /> : undefined}
        toolbar={{
          start: (
            <>
              <TextInput
                ref={filterRef}
                label="Filter teams"
                placeholder="Team name or pattern"
                value={query}
                onChange={(e) => setSearch({ teamQ: e.currentTarget.value || undefined })}
                w="17.5rem"
                size="xs"
              />
              {create}
            </>
          ),
          end:
            teams.data === undefined ? undefined : (
              <Text size="sm" c="dimmed">
                {q === '' ? '' : `${rows.length} of `}
                {all.length} team{all.length === 1 ? '' : 's'}
              </Text>
            ),
        }}
        empty={empty}
      />

      <TeamNameDialog
        naming={naming}
        onClose={() => setNaming(null)}
        onDone={(team) => {
          const isNew = naming === 'new';
          setNaming(null);
          // A team owns nothing until it has a pattern: a new one opens where its first is added.
          if (isNew) onCreated(team.id);
        }}
      />
      <DeleteTeam team={deleting} opened={deleteOpen} onClose={() => setDeleteOpen(false)} />
    </Section>
  );
}
