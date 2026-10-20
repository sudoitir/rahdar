import { describe, expect, it } from 'vitest';
import { http, HttpResponse } from 'msw';
import { screen, waitFor } from '@testing-library/react';

import { renderWithProviders } from '../../test/render.tsx';
import { server } from '../../test/setup.ts';
import { useCan, type ResourceWhere } from './useCan.ts';

type Summary = {
  permissions: string[];
  anywhere?: string[];
  canSeeCluster?: boolean | null;
  teams?: { teamId: string; teamName: string; roleId: string; roleName: string; teamAdmin: boolean }[];
  createPatterns?: { subscription: string[]; destination: string[] };
};

/** Serves `/me/access`: the installation summary, and each cluster's by id. */
function serveAccess(installation: Summary, byCluster: Record<string, Summary> = {}) {
  server.use(
    http.get('*/api/v1/me/access', ({ request }) => {
      const clusterId = new URL(request.url).searchParams.get('clusterId');
      const summary = clusterId ? (byCluster[clusterId] ?? { permissions: [] }) : installation;
      return HttpResponse.json({
        anywhere: [],
        canSeeCluster: clusterId ? false : null,
        teams: [],
        createPatterns: { subscription: [], destination: [] },
        ...summary,
      });
    }),
  );
}

function Probe({ permission, clusterId }: Readonly<{ permission: string; clusterId?: string }>) {
  const { can, loading } = useCan();
  return (
    <p>
      {permission} {clusterId ?? 'installation'}: {can(permission, clusterId) ? 'yes' : 'no'}
      {loading ? ' (loading)' : ''}
    </p>
  );
}

function ResourceProbe({ permission, where }: Readonly<{ permission: string; where: ResourceWhere }>) {
  const { can } = useCan();
  return (
    <p>
      {permission} on {where.name}: {can(permission, where) ? 'yes' : 'no'}
    </p>
  );
}

function AnywhereProbe({ permission, clusterId }: Readonly<{ permission: string; clusterId: string }>) {
  const { canAnywhere } = useCan();
  return (
    <p>
      {permission} anywhere on {clusterId}: {canAnywhere(permission, clusterId) ? 'yes' : 'no'}
    </p>
  );
}

describe('useCan', () => {
  it('answers from the server summary, not from the grants of /auth/me', async () => {
    serveAccess({ permissions: ['settings:read'] });
    server.use(
      http.get('*/api/v1/auth/me', () =>
        HttpResponse.json({
          id: 'u1',
          username: 'u',
          mustChangePassword: false,
          grants: [{ scopeType: 'GLOBAL', scopeId: null, permissions: ['*'] }],
        }),
      ),
    );
    renderWithProviders(
      <>
        <Probe permission="settings:read" />
        <Probe permission="user:admin" />
      </>,
    );

    expect(await screen.findByText('settings:read installation: yes')).toBeInTheDocument();
    expect(screen.getByText('user:admin installation: no')).toBeInTheDocument();
  });

  it('counts a grant on the environment of a cluster, which the grants of /auth/me cannot show', async () => {
    serveAccess({ permissions: [] }, { 'c-live': { permissions: ['subscription:purge'] } });
    renderWithProviders(
      <>
        <Probe permission="subscription:purge" clusterId="c-live" />
        <Probe permission="subscription:purge" clusterId="c-other" />
      </>,
    );

    expect(await screen.findByText('subscription:purge c-live: yes')).toBeInTheDocument();
    await waitFor(() => expect(screen.getByText('subscription:purge c-other: no')).toBeInTheDocument());
  });

  it('offers the control until the cluster it was asked about has answered, then follows the answer', async () => {
    let release: () => void = () => {};
    const gate = new Promise<void>((resolve) => (release = resolve));
    server.use(
      http.get('*/api/v1/me/access', async ({ request }) => {
        const clusterId = new URL(request.url).searchParams.get('clusterId');
        if (clusterId) await gate;
        return HttpResponse.json({
          permissions: [],
          anywhere: [],
          canSeeCluster: clusterId ? false : null,
          teams: [],
          createPatterns: { subscription: [], destination: [] },
        });
      }),
    );
    renderWithProviders(<Probe permission="subscription:purge" clusterId="c-live" />);

    expect(await screen.findByText('subscription:purge c-live: yes')).toBeInTheDocument();
    release();
    await waitFor(() => expect(screen.getByText('subscription:purge c-live: no')).toBeInTheDocument());
  });

  it('counts the admin of a team as holding team:admin, though no role grants it globally', async () => {
    serveAccess({
      permissions: [],
      teams: [{ teamId: 't1', teamName: 'Orders', roleId: 'r1', roleName: 'TEAM_ADMIN', teamAdmin: true }],
    });
    renderWithProviders(
      <>
        <Probe permission="team:admin" />
        <Probe permission="user:admin" />
      </>,
    );

    expect(await screen.findByText('team:admin installation: yes')).toBeInTheDocument();
    expect(screen.getByText('user:admin installation: no')).toBeInTheDocument();
  });

  it('does not count a team member who is not a team admin', async () => {
    serveAccess({
      permissions: [],
      teams: [{ teamId: 't1', teamName: 'Orders', roleId: 'r1', roleName: 'TEAM_VIEWER', teamAdmin: false }],
    });
    renderWithProviders(<Probe permission="team:admin" />);

    await waitFor(() => expect(screen.getByText('team:admin installation: no')).toBeInTheDocument());
  });

  describe('on a cluster where the caller has a role only through a team', () => {
    function Reach({ clusterId }: Readonly<{ clusterId: string }>) {
      const { can, canAnywhere, canOn, reach, createPatterns } = useCan();
      return (
        <ul>
          <li>grant: {can('subscription:purge', clusterId) ? 'yes' : 'no'}</li>
          <li>anywhere: {canAnywhere('subscription:purge', clusterId) ? 'yes' : 'no'}</li>
          <li>anywhere other: {canAnywhere('subscription:delete', clusterId) ? 'yes' : 'no'}</li>
          <li>on mine: {canOn('subscription:purge', { allowedActions: ['subscription:purge'] }) ? 'yes' : 'no'}</li>
          <li>on theirs: {canOn('subscription:purge', { allowedActions: ['subscription:read'] }) ? 'yes' : 'no'}</li>
          <li>reach: {reach('subscription:purge', clusterId)}</li>
          <li>patterns: {createPatterns(clusterId, 'subscription')?.join(',') ?? 'unknown'}</li>
        </ul>
      );
    }

    it('separates the page-level right from the right on one row', async () => {
      serveAccess(
        { permissions: [] },
        {
          c1: {
            permissions: [],
            anywhere: ['subscription:purge'],
            createPatterns: { subscription: ['orders.#'], destination: [] },
          },
        },
      );
      renderWithProviders(<Reach clusterId="c1" />);

      await waitFor(() => expect(screen.getByText('reach: teams')).toBeInTheDocument());
      expect(screen.getByText('grant: no')).toBeInTheDocument();
      expect(screen.getByText('anywhere: yes')).toBeInTheDocument();
      expect(screen.getByText('anywhere other: no')).toBeInTheDocument();
      expect(screen.getByText('on mine: yes')).toBeInTheDocument();
      expect(screen.getByText('on theirs: no')).toBeInTheDocument();
      expect(screen.getByText('patterns: orders.#')).toBeInTheDocument();
    });

    it('says the caller reaches nothing on a cluster where they hold nothing', async () => {
      serveAccess({ permissions: [] }, { c1: { permissions: [] } });
      renderWithProviders(<Reach clusterId="c1" />);

      await waitFor(() => expect(screen.getByText('reach: none')).toBeInTheDocument());
      expect(screen.getByText('anywhere: no')).toBeInTheDocument();
    });
  });

  it('asks the server about one queue, and follows what a team gives there', async () => {
    serveAccess({ permissions: [] });
    const asked: string[] = [];
    server.use(
      http.get('*/api/v1/me/access/resource', ({ request }) => {
        const url = new URL(request.url);
        asked.push(`${url.searchParams.get('type')} ${url.searchParams.get('name')}`);
        const own = url.searchParams.get('name') === 'orders.in';
        return HttpResponse.json({ actions: own ? ['subscription:read', 'acme:peek'] : [] });
      }),
    );
    renderWithProviders(
      <>
        <ResourceProbe permission="acme:peek" where={{ clusterId: 'c1', type: 'subscription', name: 'orders.in' }} />
        <ResourceProbe permission="acme:peek" where={{ clusterId: 'c1', type: 'subscription', name: 'billing.in' }} />
      </>,
    );

    await waitFor(() => expect(screen.getByText('acme:peek on billing.in: no')).toBeInTheDocument());
    expect(screen.getByText('acme:peek on orders.in: yes')).toBeInTheDocument();
    expect(asked.sort()).toEqual(['SUBSCRIPTION billing.in', 'SUBSCRIPTION orders.in']);
  });

  it('answers from the allowedActions a row carries, with no request', async () => {
    serveAccess({ permissions: [] });
    server.use(http.get('*/api/v1/me/access/resource', () => HttpResponse.error()));
    renderWithProviders(
      <>
        <ResourceProbe
          permission="acme:write"
          where={{ clusterId: 'c1', type: 'subscription', name: 'a', allowedActions: ['acme:write'] }}
        />
        <ResourceProbe
          permission="acme:write"
          where={{ clusterId: 'c1', type: 'subscription', name: 'b', allowedActions: ['acme:read'] }}
        />
      </>,
    );

    expect(await screen.findByText('acme:write on a: yes')).toBeInTheDocument();
    expect(screen.getByText('acme:write on b: no')).toBeInTheDocument();
  });

  it('offers a page for a resource permission held on some queue of a cluster, through a team', async () => {
    serveAccess({ permissions: [] }, { c1: { permissions: [], anywhere: ['acme:read'] }, c2: { permissions: [] } });
    renderWithProviders(
      <>
        <AnywhereProbe permission="acme:read" clusterId="c1" />
        <AnywhereProbe permission="acme:read" clusterId="c2" />
        <Probe permission="acme:read" clusterId="c1" />
      </>,
    );

    await waitFor(() => expect(screen.getByText('acme:read anywhere on c2: no')).toBeInTheDocument());
    expect(screen.getByText('acme:read anywhere on c1: yes')).toBeInTheDocument();
    // But holding it somewhere is not holding it on the cluster.
    expect(screen.getByText('acme:read c1: no')).toBeInTheDocument();
  });
});
