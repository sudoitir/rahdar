import { useMutation, useQuery, useQueryClient, type UseQueryResult } from '@tanstack/react-query';
import { requestAll, type PagedView } from '../../kernel/api/paging.ts';
import { ApiError, request } from '../../kernel/api/request.ts';
import { accessKeys } from '../../kernel/auth/api.ts';
import type { components } from '../../kernel/api/schema.d.ts';

type Schemas = components['schemas'];

export type AccessCheckView = Schemas['AccessCheckView'];
export type AccessSource = Schemas['AccessSource'];
export type AccountSessionView = Schemas['AccountSessionView'];
export type CreateUserRequest = Schemas['CreateUserRequest'];
export type DefaultRoleRequest = Schemas['DefaultRoleRequest'];
export type EffectivePermissionView = Schemas['EffectivePermissionView'];
export type EndedSessionsView = Schemas['EndedSessionsView'];
export type GrantRequest = Schemas['GrantRequest'];
export type GroupMappingRequest = Schemas['GroupMappingRequest'];
export type GroupMappingView = Schemas['GroupMappingView'];
export type GroupMappingsView = Schemas['GroupMappingsView'];
export type MemberRequest = Schemas['MemberRequest'];
export type MemberView = Schemas['MemberView'];
export type ResourceAccessView = Schemas['ResourceAccessView'];
export type ResourceTeamGrant = Schemas['ResourceTeamGrant'];
export type PatternConflict = Schemas['PatternConflict'];
export type PatternPreview = Schemas['PatternPreview'];
export type PatternRequest = Schemas['PatternRequest'];
export type PatternView = Schemas['PatternView'];
export type PermissionView = Schemas['PermissionView'];
export type RoleRequest = Schemas['RoleRequest'];
export type RoleView = Schemas['RoleView'];
export type SetDisabledRequest = Schemas['SetDisabledRequest'];
export type ShareRequest = Schemas['ShareRequest'];
export type ShareView = Schemas['ShareView'];
export type TeamRoleLookup = Schemas['TeamRoleLookup'];
export type TeamSummary = Schemas['TeamSummary'];
export type TeamView = Schemas['TeamView'];
export type UnownedView = Schemas['UnownedView'];
export type UserLookup = Schemas['UserLookup'];
export type UserView = Schemas['UserView'];
export type PatternKind = PatternRequest['kind'];

export const keys = {
  groupMappings: (providerId: string) => ['identity', 'providers', providerId, 'group-mappings'] as const,
  permissions: ['permissions'] as const,
  roles: ['roles'] as const,
  users: ['users'] as const,
  /** The caller's own sessions, or a user's when an administrator looks at theirs. */
  sessions: (userId?: string) => (userId ? (['users', userId, 'sessions'] as const) : (['auth', 'sessions'] as const)),
  effectivePermissions: (userId: string) => ['users', userId, 'effective-permissions'] as const,
  accessCheck: (userId: string, clusterId: string | null, kind: string, name: string) =>
    ['users', userId, 'access-check', clusterId, kind, name] as const,
  teams: ['teams'] as const,
  userLookup: (q: string) => ['teams', 'lookups', 'users', q] as const,
  teamRoles: ['teams', 'lookups', 'roles'] as const,
  team: (teamId: string) => ['teams', teamId] as const,
  preview: (teamId: string, clusterId: string, kind: PatternKind, pattern: string) =>
    ['teams', teamId, 'preview', clusterId, kind, pattern] as const,
  resourceAccess: (clusterId: string, kind: string, name: string) =>
    ['resource-access', clusterId, kind, name] as const,
  unowned: (clusterId: string, kind: string, page: number) => ['unowned', clusterId, kind, page] as const,
};

export function useUsers(enabled = true): UseQueryResult<UserView[], ApiError> {
  return useQuery({
    queryKey: keys.users,
    queryFn: () => requestAll<UserView>('/users'),
    enabled,
  });
}

export function useEffectivePermissions(userId: string | null): UseQueryResult<EffectivePermissionView[], ApiError> {
  return useQuery({
    queryKey: keys.effectivePermissions(userId ?? ''),
    queryFn: () => requestAll<EffectivePermissionView>(`/users/${userId}/effective-permissions`),
    enabled: userId !== null,
  });
}

/** Every catalogue permission for a user here, with each source that allows it. `name` needs a cluster. */
export function useAccessCheck(
  userId: string | null,
  where: { clusterId: string | null; kind: 'SUBSCRIPTION' | 'DESTINATION'; name: string },
): UseQueryResult<AccessCheckView[], ApiError> {
  const name = where.clusterId ? where.name.trim() : '';
  return useQuery({
    queryKey: keys.accessCheck(userId ?? '', where.clusterId, where.kind, name),
    queryFn: () => {
      const qs = new URLSearchParams();
      if (where.clusterId) qs.set('clusterId', where.clusterId);
      if (name) {
        qs.set('type', where.kind);
        qs.set('name', name);
      }
      const query = qs.size > 0 ? `?${qs}` : '';
      return requestAll<AccessCheckView>(`/users/${userId}/access-check${query}`);
    },
    enabled: userId !== null,
  });
}

export function useCreateUser() {
  const qc = useQueryClient();
  return useMutation<UserView, ApiError, CreateUserRequest>({
    mutationFn: (body) =>
      request<UserView>('/users', {
        method: 'POST',
        body: JSON.stringify(body),
      }),
    onSuccess: () => qc.invalidateQueries({ queryKey: keys.users }),
  });
}

export function useSetUserDisabled() {
  const qc = useQueryClient();
  return useMutation<UserView, ApiError, { userId: string; disabled: boolean }>({
    mutationFn: ({ userId, disabled }) =>
      request<UserView>(`/users/${userId}/disabled`, {
        method: 'PUT',
        body: JSON.stringify({ disabled } satisfies SetDisabledRequest),
      }),
    onSuccess: () => qc.invalidateQueries({ queryKey: keys.users }),
  });
}

/** Lift an account's lock (repeated failed sign-ins) so its user can sign in again at once. */
export function useUnlockUser() {
  const qc = useQueryClient();
  return useMutation<UserView, ApiError, string>({
    mutationFn: (userId) => request<UserView>(`/users/${userId}/unlock`, { method: 'PUT' }),
    onSuccess: () => qc.invalidateQueries({ queryKey: keys.users }),
  });
}

/**
 * Remove a user's second factors: the authenticator app, passkeys, recovery codes and trusted devices go, their API
 * keys are revoked, and they are signed out everywhere. Needs `user:admin` and a fresh sign-in; refused for oneself.
 */
export function useResetSecondFactors() {
  const qc = useQueryClient();
  return useMutation<UserView, ApiError, string>({
    mutationFn: (userId) => request<UserView>(`/users/${userId}/second-factors`, { method: 'DELETE' }),
    onSuccess: () => qc.invalidateQueries({ queryKey: keys.users }),
  });
}

export function useAddGrant() {
  const qc = useQueryClient();
  return useMutation<void, ApiError, { userId: string; body: GrantRequest }>({
    mutationFn: ({ userId, body }) =>
      request<void>(`/users/${userId}/grants`, {
        method: 'POST',
        body: JSON.stringify(body),
      }),
    onSuccess: () => qc.invalidateQueries({ queryKey: keys.users }),
  });
}

export function useRemoveGrant() {
  const qc = useQueryClient();
  return useMutation<void, ApiError, { userId: string; roleId: string; scopeType: string; scopeId?: string }>({
    mutationFn: ({ userId, roleId, scopeType, scopeId }) => {
      const qs = new URLSearchParams({
        scopeType,
        ...(scopeId ? { scopeId } : {}),
      });
      return request<void>(`/users/${userId}/grants/${roleId}?${qs}`, {
        method: 'DELETE',
      });
    },
    onSuccess: () => qc.invalidateQueries({ queryKey: keys.users }),
  });
}

export function useRoles(): UseQueryResult<RoleView[], ApiError> {
  return useQuery({
    queryKey: keys.roles,
    queryFn: () => requestAll<RoleView>('/roles'),
  });
}

export function usePermissionsCatalogue(): UseQueryResult<PermissionView[], ApiError> {
  return useQuery({
    queryKey: keys.permissions,
    queryFn: () => requestAll<PermissionView>('/permissions'),
  });
}

export function useCreateRole() {
  const qc = useQueryClient();
  return useMutation<RoleView, ApiError, RoleRequest>({
    mutationFn: (body) =>
      request<RoleView>('/roles', {
        method: 'POST',
        body: JSON.stringify(body),
      }),
    onSuccess: () => qc.invalidateQueries({ queryKey: keys.roles }),
  });
}

export function useUpdateRole() {
  const qc = useQueryClient();
  return useMutation<RoleView, ApiError, { roleId: string; body: RoleRequest }>({
    mutationFn: ({ roleId, body }) =>
      request<RoleView>(`/roles/${roleId}`, {
        method: 'PUT',
        body: JSON.stringify(body),
      }),
    onSuccess: () => qc.invalidateQueries({ queryKey: keys.roles }),
  });
}

export function useDeleteRole() {
  const qc = useQueryClient();
  return useMutation<void, ApiError, string>({
    mutationFn: (roleId) => request<void>(`/roles/${roleId}`, { method: 'DELETE' }),
    onSuccess: () => qc.invalidateQueries({ queryKey: keys.roles }),
  });
}

const groupMappingsPath = (providerId: string) =>
  `/identity/providers/${encodeURIComponent(providerId)}/group-mappings`;

export function useGroupMappings(providerId: string): UseQueryResult<GroupMappingsView, ApiError> {
  return useQuery({
    queryKey: keys.groupMappings(providerId),
    queryFn: () => request<GroupMappingsView>(groupMappingsPath(providerId)),
  });
}

export function useCreateGroupMapping(providerId: string) {
  const qc = useQueryClient();
  return useMutation<GroupMappingView, ApiError, GroupMappingRequest>({
    mutationFn: (body) =>
      request<GroupMappingView>(groupMappingsPath(providerId), {
        method: 'POST',
        body: JSON.stringify(body),
      }),
    onSuccess: () => qc.invalidateQueries({ queryKey: keys.groupMappings(providerId) }),
  });
}

export function useDeleteGroupMapping(providerId: string) {
  const qc = useQueryClient();
  return useMutation<void, ApiError, string>({
    mutationFn: (mappingId) =>
      request<void>(`${groupMappingsPath(providerId)}/${mappingId}`, {
        method: 'DELETE',
      }),
    onSuccess: () => qc.invalidateQueries({ queryKey: keys.groupMappings(providerId) }),
  });
}

export function useSetDefaultRole(providerId: string) {
  const qc = useQueryClient();
  return useMutation<GroupMappingsView, ApiError, DefaultRoleRequest>({
    mutationFn: (body) =>
      request<GroupMappingsView>(`${groupMappingsPath(providerId)}/default-role`, {
        method: 'PUT',
        body: JSON.stringify(body),
      }),
    onSuccess: () => qc.invalidateQueries({ queryKey: keys.groupMappings(providerId) }),
  });
}

const sessionsPath = (userId?: string) => (userId ? `/users/${userId}/sessions` : '/auth/sessions');

/** Signed-in sessions: the caller's own, or the user's when `userId` is given (needs `user:admin`). */
export function useSessions(userId?: string, enabled = true): UseQueryResult<AccountSessionView[], ApiError> {
  return useQuery({
    queryKey: keys.sessions(userId),
    queryFn: () => requestAll<AccountSessionView>(sessionsPath(userId)),
    enabled,
  });
}

/** End one session by its handle. Refreshes the list either way: a session already gone should not stay listed. */
export function useEndSession(userId?: string) {
  const qc = useQueryClient();
  return useMutation<void, ApiError, string>({
    mutationFn: (handle) => request<void>(`${sessionsPath(userId)}/${handle}`, { method: 'DELETE' }),
    onSettled: () => qc.invalidateQueries({ queryKey: keys.sessions(userId) }),
  });
}

/** End every session but the one making the request. */
export function useEndOtherSessions(userId?: string) {
  const qc = useQueryClient();
  return useMutation<EndedSessionsView, ApiError, void>({
    mutationFn: () => request<EndedSessionsView>(sessionsPath(userId), { method: 'DELETE' }),
    onSettled: () => qc.invalidateQueries({ queryKey: keys.sessions(userId) }),
  });
}

/** Every team the caller may see: all of them for a user administrator, their own for a team admin. */
export function useTeams(): UseQueryResult<TeamSummary[], ApiError> {
  return useQuery({ queryKey: keys.teams, queryFn: () => requestAll<TeamSummary>('/teams') });
}

export function useTeam(teamId: string): UseQueryResult<TeamView, ApiError> {
  return useQuery({ queryKey: keys.team(teamId), queryFn: () => request<TeamView>(`/teams/${teamId}`) });
}

const json = (body: unknown) => JSON.stringify(body);

/** The server answers a lookup only for a prefix this long, so a team admin cannot page through every account. */
export const USER_LOOKUP_MIN_PREFIX = 2;

/**
 * Enabled users whose name starts with `q`, at most twenty, for choosing a member. Open to a team admin, who may
 * not list every user. Sent only once `q` holds {@link USER_LOOKUP_MIN_PREFIX} characters.
 */
export function useUserLookup(q: string): UseQueryResult<UserLookup[], ApiError> {
  return useQuery({
    queryKey: keys.userLookup(q),
    enabled: q.trim().length >= USER_LOOKUP_MIN_PREFIX,
    queryFn: async () =>
      (await request<PagedView<UserLookup>>(`/teams/lookups/users?${new URLSearchParams({ q })}`)).data,
    placeholderData: (previous) => previous,
  });
}

/** The roles a member or share may hold, with their permissions: the team roles only. Open to a team admin. */
export function useTeamRoles(): UseQueryResult<TeamRoleLookup, ApiError> {
  return useQuery({ queryKey: keys.teamRoles, queryFn: () => request<TeamRoleLookup>('/teams/lookups/roles') });
}

export function useCreateTeam() {
  const qc = useQueryClient();
  return useMutation<TeamView, ApiError, string>({
    mutationFn: (name) =>
      request<TeamView>('/teams', { method: 'POST', body: json({ name } satisfies Schemas['TeamRequest']) }),
    onSuccess: () => qc.invalidateQueries({ queryKey: keys.teams }),
  });
}

export function useRenameTeam() {
  const qc = useQueryClient();
  return useMutation<TeamView, ApiError, { teamId: string; name: string }>({
    mutationFn: ({ teamId, name }) =>
      request<TeamView>(`/teams/${teamId}`, { method: 'PUT', body: json({ name } satisfies Schemas['TeamRequest']) }),
    onSuccess: () => qc.invalidateQueries({ queryKey: keys.teams }),
  });
}

export function useDeleteTeam() {
  const qc = useQueryClient();
  return useMutation<void, ApiError, string>({
    mutationFn: (teamId) => request<void>(`/teams/${teamId}`, { method: 'DELETE' }),
    // Every team query but the deleted team's own: asking for it again only answers that it is gone, while its
    // page is still on screen for the moment before it leaves.
    onSuccess: (_, teamId) =>
      qc.invalidateQueries({ queryKey: keys.teams, predicate: (q) => q.queryKey[1] !== teamId }),
  });
}

/** What a pattern matches on a cluster now, and the other teams' patterns it would overlap. Needs a well-formed pattern. */
export function usePatternPreview(
  teamId: string,
  clusterId: string | null,
  kind: PatternKind,
  pattern: string,
  enabled: boolean,
): UseQueryResult<PatternPreview, ApiError> {
  return useQuery({
    queryKey: keys.preview(teamId, clusterId ?? '', kind, pattern),
    queryFn: () => {
      const qs = new URLSearchParams({ clusterId: clusterId ?? '', kind, pattern });
      return request<PatternPreview>(`/teams/${teamId}/patterns/preview?${qs}`);
    },
    enabled: enabled && clusterId !== null,
  });
}

/** A team's access changes with its patterns, members and shares, and so does what is unowned. */
function useRefreshTeams() {
  const qc = useQueryClient();
  return () =>
    Promise.all([
      qc.invalidateQueries({ queryKey: keys.teams }),
      qc.invalidateQueries({ queryKey: ['unowned'] }),
      // A change to a team may be to the caller's own access.
      qc.invalidateQueries({ queryKey: accessKeys.all }),
    ]);
}

export function useAddPattern(teamId: string) {
  const refresh = useRefreshTeams();
  return useMutation<PatternView, ApiError, PatternRequest>({
    mutationFn: (body) => request<PatternView>(`/teams/${teamId}/patterns`, { method: 'POST', body: json(body) }),
    onSuccess: refresh,
  });
}

export function useRemovePattern(teamId: string) {
  const refresh = useRefreshTeams();
  return useMutation<void, ApiError, string>({
    mutationFn: (patternId) => request<void>(`/teams/${teamId}/patterns/${patternId}`, { method: 'DELETE' }),
    onSuccess: refresh,
  });
}

export function useAddMember(teamId: string) {
  const refresh = useRefreshTeams();
  return useMutation<MemberView, ApiError, MemberRequest>({
    mutationFn: (body) => request<MemberView>(`/teams/${teamId}/members`, { method: 'POST', body: json(body) }),
    onSuccess: refresh,
  });
}

export function useChangeMemberRole(teamId: string) {
  const refresh = useRefreshTeams();
  return useMutation<MemberView, ApiError, { memberId: string; roleId: string }>({
    mutationFn: ({ memberId, roleId }) =>
      request<MemberView>(`/teams/${teamId}/members/${memberId}`, {
        method: 'PUT',
        body: json({ roleId } satisfies Schemas['MemberRoleRequest']),
      }),
    onSuccess: refresh,
  });
}

export function useRemoveMember(teamId: string) {
  const refresh = useRefreshTeams();
  return useMutation<void, ApiError, string>({
    mutationFn: (memberId) => request<void>(`/teams/${teamId}/members/${memberId}`, { method: 'DELETE' }),
    onSuccess: refresh,
  });
}

export function useAddShare(teamId: string) {
  const refresh = useRefreshTeams();
  return useMutation<ShareView, ApiError, ShareRequest>({
    mutationFn: (body) => request<ShareView>(`/teams/${teamId}/shares`, { method: 'POST', body: json(body) }),
    onSuccess: refresh,
  });
}

export function useRemoveShare(teamId: string) {
  const refresh = useRefreshTeams();
  return useMutation<void, ApiError, string>({
    mutationFn: (shareId) => request<void>(`/teams/${teamId}/shares/${shareId}`, { method: 'DELETE' }),
    onSuccess: refresh,
  });
}

export const UNOWNED_PAGE_SIZE = 100;

/** The subscription or destination names of a cluster that no team's pattern covers, one page at a time. */
export function useUnowned(
  clusterId: string | null,
  kind: 'SUBSCRIPTION' | 'DESTINATION',
  page: number,
  enabled: boolean,
): UseQueryResult<PagedView<UnownedView>, ApiError> {
  return useQuery({
    queryKey: keys.unowned(clusterId ?? '', kind, page),
    queryFn: () =>
      request<PagedView<UnownedView>>(
        `/clusters/${clusterId}/unowned?${new URLSearchParams({ type: kind, page: String(page), size: String(UNOWNED_PAGE_SIZE) })}`,
      ),
    enabled: enabled && clusterId !== null,
  });
}

/** The owner and every share of one subscription or destination: which teams and roles may act on it. */
export function useResourceAccess(
  clusterId: string,
  kind: 'SUBSCRIPTION' | 'DESTINATION',
  name: string,
  enabled: boolean,
): UseQueryResult<ResourceAccessView, ApiError> {
  return useQuery({
    queryKey: keys.resourceAccess(clusterId, kind, name),
    queryFn: () =>
      request<ResourceAccessView>(
        `/clusters/${clusterId}/resource-access?${new URLSearchParams({ type: kind, name })}`,
      ),
    enabled,
    retry: false,
  });
}
