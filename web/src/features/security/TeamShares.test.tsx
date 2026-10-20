import { afterEach, describe, expect, it } from 'vitest';
import { http, HttpResponse } from 'msw';
import { act, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { Notifications, notifications } from '@mantine/notifications';

import { paged } from '../../kernel/api/paging.ts';
import { renderWithProviders } from '../../test/render.tsx';
import { server } from '../../test/setup.ts';
import type { ShareView } from './api.ts';
import { TeamShares } from './TeamShares.tsx';
import { choose, CLUSTER, serveLookups, summary, team, TEAM_VIEWER } from '../../test/teams.ts';

afterEach(() => act(() => notifications.clean()));

const share = (over: Partial<ShareView> = {}): ShareView => ({
  id: 's1',
  ownerTeamId: 't-orders',
  ownerTeamName: 'Orders',
  targetTeamId: 't-billing',
  targetTeamName: 'Billing',
  clusterId: CLUSTER.id,
  kind: 'SUBSCRIPTION',
  pattern: 'orders.events.#',
  roleId: TEAM_VIEWER.id,
  roleName: TEAM_VIEWER.name,
  covered: true,
  ...over,
});

function serve(permissions = ['user:admin']) {
  serveLookups(permissions);
  const billing = team({ id: 't-billing', name: 'Billing', patterns: [], members: [] });
  server.use(http.get('*/api/v1/teams', () => HttpResponse.json(paged([summary(team()), summary(billing)]))));
}

function renderShares(over: Parameters<typeof team>[0] = {}) {
  return renderWithProviders(
    <>
      <Notifications />
      <TeamShares team={team(over)} />
    </>,
  );
}

describe('TeamShares', () => {
  it('lists what the team shares and what is shared with it', async () => {
    serve();
    renderShares({
      sharesOut: [share()],
      sharesIn: [share({ id: 's2', ownerTeamName: 'Billing', pattern: 'billing.in' })],
    });

    const out = await screen.findByRole('row', { name: /orders\.events\.#/ });
    expect(out).toHaveTextContent('Billing');
    await waitFor(() => expect(out).toHaveTextContent('prod'));
    expect(out).toHaveTextContent('Team Viewer');
    expect(out).toHaveTextContent('Active');
    const incoming = screen.getByRole('row', { name: /billing\.in/ });
    expect(within(incoming).queryByRole('button')).not.toBeInTheDocument();
  });

  it('marks a share whose pattern the owner no longer covers, in words', async () => {
    serve();
    renderShares({ sharesOut: [share({ covered: false })] });

    expect(await screen.findByRole('row', { name: /orders\.events\.#/ })).toHaveTextContent('Not covered');
    expect(
      screen.getByText(/grants nothing because this team's patterns no longer contain its pattern/),
    ).toBeInTheDocument();
  });

  it('shares a pattern with another team at a team role', async () => {
    serve();
    let body: unknown;
    server.use(
      http.post('*/api/v1/teams/t-orders/shares', async ({ request }) => {
        body = await request.json();
        return HttpResponse.json(share(), { status: 201 });
      }),
    );
    const person = userEvent.setup();
    renderShares();

    await choose(person, screen, /Share with/, 'Billing');
    await choose(person, screen, /As team role/, 'Team Viewer');
    await choose(person, screen, /Cluster/, 'prod');
    await person.type(screen.getByRole('textbox', { name: /^Pattern/ }), 'orders.events.#');
    await person.click(screen.getByRole('button', { name: 'Share pattern' }));

    await waitFor(() =>
      expect(body).toEqual({
        targetTeamId: 't-billing',
        clusterId: 'c-prod',
        kind: 'SUBSCRIPTION',
        pattern: 'orders.events.#',
        roleId: TEAM_VIEWER.id,
      }),
    );
  });

  it('puts the refusal of a pattern outside the owner beside the pattern', async () => {
    serve();
    server.use(
      http.post('*/api/v1/teams/t-orders/shares', () =>
        HttpResponse.json(
          { type: 'https://rahdar/problems/share-outside-owner', title: 'Refused', detail: 'outside' },
          { status: 422 },
        ),
      ),
    );
    const person = userEvent.setup();
    renderShares();

    await choose(person, screen, /Share with/, 'Billing');
    await choose(person, screen, /As team role/, 'Team Viewer');
    await choose(person, screen, /Cluster/, 'prod');
    await person.type(screen.getByRole('textbox', { name: /^Pattern/ }), 'billing.#');
    await person.click(screen.getByRole('button', { name: 'Share pattern' }));

    expect(await screen.findByText(/not inside what this team owns/, { selector: 'p' })).toBeInTheDocument();
  });

  it('is read-only for a team admin, with the reason', async () => {
    serve(['team:admin']);
    renderShares({ sharesOut: [share()] });

    await waitFor(() =>
      expect(screen.getByRole('button', { name: 'Remove share of orders.events.# with Billing' })).toBeDisabled(),
    );
    expect(screen.getByRole('button', { name: 'Share pattern' })).toBeDisabled();
    expect(screen.getByText(/removing a share, needs the user:admin permission/)).toBeInTheDocument();
  });
});
