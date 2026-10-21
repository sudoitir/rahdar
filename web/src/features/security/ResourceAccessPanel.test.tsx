import { describe, expect, it, vi } from 'vitest';
import { http, HttpResponse } from 'msw';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

import { paged } from '../../kernel/api/paging.ts';
import { renderWithProviders } from '../../test/render.tsx';
import { server } from '../../test/setup.ts';
import { choose } from '../../test/teams.ts';
import type { ResourceAccessView } from './api.ts';

vi.mock('@tanstack/react-router', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@tanstack/react-router')>()),
  Link: ({
    children,
    to,
    search,
    'aria-label': label,
  }: {
    children: React.ReactNode;
    to?: string;
    search?: Record<string, string>;
    'aria-label'?: string;
  }) => (
    <a href={`${to ?? ''}?${new URLSearchParams(search ?? {})}`} aria-label={label}>
      {children}
    </a>
  ),
}));

const { ResourceAccessPanel, DestinationAccessDrawer } = await import('./ResourceAccessPanel.tsx');

const ACCESS: ResourceAccessView = {
  ownerTeam: { id: 't-orders', name: 'Orders' },
  grants: [
    { teamId: 't-orders', teamName: 'Orders', roleName: 'Team Operator', source: 'OWNER', memberCount: 3 },
    {
      teamId: 't-billing',
      teamName: 'Billing',
      roleName: 'Team Viewer',
      source: 'SHARE',
      sharedByTeamName: 'Orders',
      pattern: 'orders.#',
      memberCount: 5,
    },
  ],
};

/** Signs in with the role grants given, and serves the resource's access and the users. */
function serve(permissions: string[], teamAdmin = false) {
  const asked: string[] = [];
  server.use(
    http.get('*/api/v1/auth/me', () =>
      HttpResponse.json({
        id: 'u1',
        username: 'admin',
        mustChangePassword: false,
        grants: [{ scopeType: 'GLOBAL', scopeId: null, permissions }],
      }),
    ),
    ...(teamAdmin
      ? [
          http.get('*/api/v1/me/access', () =>
            HttpResponse.json({
              permissions: [],
              anywhere: [],
              canSeeCluster: null,
              teams: [{ teamId: 't-orders', teamName: 'Orders', roleId: 'r', roleName: 'Team Admin', teamAdmin: true }],
              createPatterns: { subscription: [], destination: [] },
            }),
          ),
        ]
      : []),
    http.get('*/api/v1/clusters/c1/resource-access', ({ request }) => {
      asked.push(new URL(request.url).search);
      return HttpResponse.json(ACCESS);
    }),
    http.get('*/api/v1/users', () =>
      HttpResponse.json(paged([{ id: 'u-1', username: 'alice', providerId: 'local', grants: [] }])),
    ),
    http.get('*/api/v1/environments', () => HttpResponse.json(paged([]))),
    http.get('*/api/v1/clusters', () => HttpResponse.json(paged([{ id: 'c1', name: 'prod' }]))),
    http.get('*/api/v1/users/u-1/access-check', ({ request }) => {
      asked.push(`check${new URL(request.url).search}`);
      return HttpResponse.json(paged([]));
    }),
    http.get('*/api/v1/users/u-1/effective-permissions', () => HttpResponse.json(paged([]))),
  );
  return asked;
}

describe('ResourceAccessPanel', () => {
  it('shows the owner team, and each team and role that may act, with how they got it', async () => {
    const asked = serve(['user:admin']);
    renderWithProviders(<ResourceAccessPanel clusterId="c1" kind="SUBSCRIPTION" name="orders.in" />);

    expect(await screen.findByRole('heading', { level: 3, name: 'Access' })).toBeInTheDocument();
    expect(await screen.findByRole('link', { name: 'Owner: team Orders' })).toBeInTheDocument();
    const table = await screen.findByRole('table', { name: 'Teams and roles with access to orders.in' });
    const rows = within(table).getAllByRole('row');
    expect(rows).toHaveLength(3);
    expect(rows[1]).toHaveTextContent('Orders');
    expect(rows[1]).toHaveTextContent('Owns it');
    expect(rows[2]).toHaveTextContent('Shared by Orders (orders.#)');
    expect(rows[2]).toHaveTextContent('5');
    expect(asked).toContain('?type=SUBSCRIPTION&name=orders.in');
  });

  it('is offered to the admin of a team, who is no user administrator', async () => {
    serve([], true);
    renderWithProviders(<ResourceAccessPanel clusterId="c1" kind="SUBSCRIPTION" name="orders.in" />);

    expect(await screen.findByRole('table', { name: /Teams and roles with access/ })).toBeInTheDocument();
    // Checking one user's access needs the user list, which only a user administrator may read.
    expect(screen.queryByRole('textbox', { name: /Check one user/ })).not.toBeInTheDocument();
  });

  it('is not there for someone who administers nothing, and asks the server nothing', async () => {
    const asked = serve(['subscription:read']);
    renderWithProviders(<ResourceAccessPanel clusterId="c1" kind="SUBSCRIPTION" name="orders.in" />);

    await new Promise((resolve) => setTimeout(resolve, 100));
    expect(screen.queryByRole('heading', { name: 'Access' })).not.toBeInTheDocument();
    expect(asked).toEqual([]);
  });

  it('says no team has access, and what gives a team access, when none does', async () => {
    serve(['user:admin']);
    server.use(
      http.get('*/api/v1/clusters/c1/resource-access', () => HttpResponse.json({ ownerTeam: null, grants: [] })),
    );
    renderWithProviders(<ResourceAccessPanel clusterId="c1" kind="DESTINATION" name="legacy.in" />);

    expect(await screen.findByText('No team has access to this destination')).toBeInTheDocument();
    expect(screen.getByText('No owner')).toBeInTheDocument();
  });

  it("opens one user's access check at this subscription, from the keyboard", async () => {
    const asked = serve(['user:admin']);
    const person = userEvent.setup();
    renderWithProviders(<ResourceAccessPanel clusterId="c1" kind="SUBSCRIPTION" name="orders.in" />);

    await screen.findByRole('table', { name: /Teams and roles with access/ });
    await choose(person, screen, "Check one user's access", 'alice');

    expect(await screen.findByRole('dialog', { name: 'Access check for alice' })).toBeInTheDocument();
    await waitFor(() => expect(asked.at(-1)).toMatch(/^check\?clusterId=c1&type=SUBSCRIPTION&name=orders.in&/));
  });
});

describe('DestinationAccessDrawer', () => {
  it("shows a destination's access in a drawer of its own", async () => {
    serve(['user:admin']);
    renderWithProviders(<DestinationAccessDrawer opened onClose={() => {}} clusterId="c1" destination="orders.addr" />);

    expect(await screen.findByRole('dialog', { name: 'Access to orders.addr' })).toBeInTheDocument();
    expect(
      await screen.findByRole('table', { name: 'Teams and roles with access to orders.addr' }),
    ).toBeInTheDocument();
  });
});
