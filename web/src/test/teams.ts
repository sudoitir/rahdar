import { http, HttpResponse } from 'msw';
import userEvent from '@testing-library/user-event';
import { waitFor, within, type screen } from '@testing-library/react';

import { paged } from '../kernel/api/paging.ts';
import { server } from './setup.ts';
import type { PatternPreview, RoleView, TeamSummary, TeamView } from '../features/security/api.ts';

export const CLUSTER = {
  id: 'c-prod',
  name: 'prod',
  health: 'OK',
  nodeCount: 2,
  updatedAt: '2026-10-01T00:00:00Z',
  environmentId: null,
};

export const TEAM_VIEWER: RoleView = {
  id: 'r-tv',
  name: 'Team Viewer',
  builtin: true,
  permissions: ['subscription:read'],
  requiresMfa: false,
  teamAssignable: true,
};
export const TEAM_OPERATOR: RoleView = {
  id: 'r-to',
  name: 'Team Operator',
  builtin: true,
  permissions: ['subscription:read', 'subscription:purge'],
  requiresMfa: false,
  teamAssignable: true,
};
export const OPERATOR: RoleView = {
  id: 'r-op',
  name: 'Operator',
  builtin: true,
  permissions: ['subscription:*'],
  requiresMfa: false,
  teamAssignable: false,
};
export const ROLES = [TEAM_VIEWER, TEAM_OPERATOR, OPERATOR];

export function team(over: Partial<TeamView> = {}): TeamView {
  return {
    id: 't-orders',
    name: 'Orders',
    createdAt: '2026-10-01T00:00:00Z',
    patterns: [{ id: 'p1', clusterId: CLUSTER.id, kind: 'SUBSCRIPTION', pattern: 'orders.#' }],
    members: [
      {
        id: 'm1',
        principalType: 'USER',
        userId: 'u-alice',
        username: 'alice',
        roleId: TEAM_OPERATOR.id,
        roleName: TEAM_OPERATOR.name,
      },
      {
        id: 'm2',
        principalType: 'GROUP',
        providerId: 'okta',
        groupName: 'orders-ops',
        roleId: TEAM_VIEWER.id,
        roleName: TEAM_VIEWER.name,
      },
    ],
    sharesOut: [],
    sharesIn: [],
    ...over,
  };
}

export const summary = (t: TeamView): TeamSummary => ({
  id: t.id,
  name: t.name,
  createdAt: t.createdAt,
  memberCount: t.members.length,
  patterns: t.patterns,
  sharesOut: t.sharesOut.length,
  sharesIn: t.sharesIn.length,
});

export function preview(over: Partial<PatternPreview> = {}): PatternPreview {
  return {
    clusterId: CLUSTER.id,
    kind: 'SUBSCRIPTION',
    pattern: 'orders.#',
    subscriptionMatches: 12,
    destinationMatches: 0,
    subscriptionExamples: ['orders.in', 'orders.out', 'orders.dlq'],
    destinationExamples: [],
    conflicts: [],
    ...over,
  };
}

/** The caller signed in with these global permissions. */
export const signedIn = (permissions: string[]) =>
  http.get('*/api/v1/auth/me', () =>
    HttpResponse.json({
      id: 'u1',
      username: 'ops',
      mustChangePassword: false,
      grants: [{ scopeType: 'GLOBAL', scopeId: null, permissions }],
    }),
  );

export const LOOKUP_USERS = [
  { id: 'u-alice', username: 'alice' },
  { id: 'u-bob', username: 'bob' },
];

/** The lookups every team screen reads, including the ones a team admin uses to find users and team roles. */
export function serveLookups(permissions: string[] = ['user:admin']) {
  server.use(
    http.get('*/api/v1/teams/lookups/roles', () =>
      HttpResponse.json({ roles: ROLES.filter((r) => r.teamAssignable), permissions: [] }),
    ),
    http.get('*/api/v1/teams/lookups/users', ({ request }) => {
      const q = new URL(request.url).searchParams.get('q') ?? '';
      return HttpResponse.json(paged(LOOKUP_USERS.filter((u) => u.username.startsWith(q))));
    }),
    signedIn(permissions),
    http.get('*/api/v1/clusters', () => HttpResponse.json(paged([CLUSTER]))),
    http.get('*/api/v1/environments', () => HttpResponse.json(paged([]))),
    http.get('*/api/v1/alerts/firing', () => HttpResponse.json(paged([]))),
    http.get('*/api/v1/roles', () => HttpResponse.json(paged(ROLES))),
  );
}

/**
 * Opens a Mantine select and picks one option; jsdom keeps the list `display: none`, so options are `hidden`.
 * `typed` is entered first, for a searchable select that asks the server only once something is typed.
 */
export async function choose(
  person: ReturnType<typeof userEvent.setup>,
  scope: Pick<typeof screen, 'getByRole'>,
  select: RegExp | string,
  option: string,
  typed?: string,
) {
  const box = scope.getByRole('combobox', { name: select });
  await person.click(box);
  if (typed) {
    await person.type(box, typed);
  }
  // Every select keeps its own list in the document, so the option is looked up in the list this box controls.
  const list = await waitFor(() => {
    const found = document.getElementById(box.getAttribute('aria-controls') ?? '');
    if (!found) throw new Error('The select has not opened its list');
    return found;
  });
  await person.click(await within(list).findByRole('option', { name: option, hidden: true }));
}
