import { queryOptions, useMutation, useQuery, useQueryClient, type UseQueryResult } from '@tanstack/react-query';
import { requestAll } from '../api/paging.ts';
import { ApiError, request } from '../api/request.ts';
import { clearDismissedNotices } from '../useDismissedNotice.ts';
import type { components } from '../api/schema.d.ts';

type Schemas = components['schemas'];

export type GrantView = Schemas['GrantView'];
export type IdentityProviderView = Schemas['IdentityProviderView'];
export type LoginRequest = Schemas['LoginRequest'];
export type MeView = Schemas['MeView'];
export type AccessSummary = Schemas['AccessSummary'];
export type MyResourceAccess = Schemas['MyResourceAccess'];
export type AuthResult = Schemas['AuthResult'];
export type SecondFactorRequest = Schemas['SecondFactorRequest'];
/** How a person can prove a second factor: an authenticator code, a passkey, or a recovery code. */
export type SecondFactorMethod = NonNullable<AuthResult['methods']>[number];

/**
 * What a sign-in mutation was given (a password, a code, a recovery code, a passkey's answer) is kept with it as
 * its variables. The cache would hold them for minutes after the screen is gone; these are forgotten the moment
 * nothing observes the mutation.
 */
const FORGET_AT_ONCE = { gcTime: 0 } as const;

export const keys = {
  authProviders: ['auth', 'providers'] as const,
  me: ['auth', 'me'] as const,
};

export const accessKeys = {
  all: ['access'] as const,
  of: (clusterId?: string) => ['access', clusterId ?? 'global'] as const,
  resource: (clusterId: string, type: string, name: string) => ['access', 'resource', clusterId, type, name] as const,
};

/**
 * What the signed-in user holds, globally or on one cluster, as the server's resolver decides it: through role
 * grants at any scope, and for a cluster the resource permissions held somewhere on it through teams and shares.
 * The console only uses it to offer or withhold controls; the server enforces.
 */
export function accessQuery(clusterId?: string) {
  return queryOptions({
    queryKey: accessKeys.of(clusterId),
    queryFn: () => request<AccessSummary>(clusterId ? `/me/access?clusterId=${clusterId}` : '/me/access'),
    retry: false,
    staleTime: 30_000,
  });
}

/** What the signed-in user may do with one subscription or destination: the actions they hold on it, as the server decides. */
export function resourceAccessQuery(clusterId: string, type: 'SUBSCRIPTION' | 'DESTINATION', name: string) {
  return queryOptions({
    queryKey: accessKeys.resource(clusterId, type, name),
    queryFn: () => request<MyResourceAccess>(`/me/access/resource?${new URLSearchParams({ clusterId, type, name })}`),
    retry: false,
    staleTime: 30_000,
  });
}

/** `retry: false` — a 401 here means "not logged in," which retrying cannot fix. */
export function useMe(): UseQueryResult<MeView, ApiError> {
  return useQuery({
    queryKey: keys.me,
    queryFn: () => request<MeView>('/auth/me'),
    retry: false,
  });
}

/** Public, unauthenticated — the login screen needs this before any session exists. */
export function useAuthProviders(): UseQueryResult<IdentityProviderView[], ApiError> {
  return useQuery({
    queryKey: keys.authProviders,
    queryFn: () => requestAll<IdentityProviderView>('/auth/providers'),
    retry: false,
  });
}

export function useLogin() {
  const qc = useQueryClient();
  return useMutation<AuthResult, ApiError, LoginRequest>({
    ...FORGET_AT_ONCE,
    mutationFn: (body) =>
      request<AuthResult>('/auth/login', {
        method: 'POST',
        body: JSON.stringify(body),
      }),
    onSuccess: (result) => {
      // A correct password for an account with a second factor signs nobody in yet.
      if (result.me) {
        // Whoever signs in next sees every notice again, including on a shared browser.
        clearDismissedNotices();
        qc.removeQueries({ queryKey: accessKeys.all });
        qc.setQueryData(keys.me, result.me);
      }
    },
  });
}

/** The server wants a fresh sign-in before it acts (ADR-0103). */
export function needsReauthentication(error: unknown): boolean {
  return error instanceof ApiError && error.status === 403 && error.type.endsWith('/reauthentication-required');
}

/**
 * Step-up with a password (ADR-0103). An account with a second factor answers `SECOND_FACTOR_REQUIRED` and
 * is not fresh until {@link useSecondFactor} finishes it; an account without one is fresh now, so `/auth/me`,
 * which carries when the session last signed in, is refreshed.
 */
export function useReauthenticate() {
  const qc = useQueryClient();
  return useMutation<AuthResult, ApiError, string>({
    ...FORGET_AT_ONCE,
    mutationFn: (password) =>
      request<AuthResult>('/auth/reauthenticate', {
        method: 'POST',
        body: JSON.stringify({ password }),
      }),
    onSuccess: (result) => {
      if (result.status === 'AUTHENTICATED') void qc.invalidateQueries({ queryKey: keys.me });
    },
  });
}

/**
 * The second factor of a sign-in, when no one is signed in yet, or of a step-up, when someone is: the server
 * tells them apart by the session. Either way `/auth/me` is refreshed, since what it carries has changed.
 */
export function useSecondFactor() {
  const qc = useQueryClient();
  return useMutation<AuthResult, ApiError, SecondFactorRequest>({
    ...FORGET_AT_ONCE,
    mutationFn: (body) =>
      request<AuthResult>('/auth/second-factor', {
        method: 'POST',
        body: JSON.stringify(body),
      }),
    onSuccess: (result) => {
      if (result.me) {
        // Whoever signs in next sees every notice again, including on a shared browser.
        clearDismissedNotices();
        qc.removeQueries({ queryKey: accessKeys.all });
        qc.setQueryData(keys.me, result.me);
      }
      void qc.invalidateQueries({ queryKey: keys.me });
    },
  });
}

/** The options for a passkey answer to the second factor now owed, as `parseRequestOptionsFromJSON` takes them. */
export function fetchPasskeyRequestOptions() {
  return request<PublicKeyCredentialRequestOptionsJSON>('/auth/second-factor/options', { method: 'POST' });
}

export function useLogout() {
  const qc = useQueryClient();
  return useMutation<void, ApiError, void>({
    mutationFn: () => request<void>('/auth/logout', { method: 'POST' }),
    onSuccess: () => {
      clearDismissedNotices();
      qc.removeQueries({ queryKey: accessKeys.all });
      qc.setQueryData(keys.me, undefined);
    },
  });
}
