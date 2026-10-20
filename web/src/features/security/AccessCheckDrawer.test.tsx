import { describe, expect, it, vi } from 'vitest';
import { http, HttpResponse } from 'msw';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

import { paged } from '../../kernel/api/paging.ts';
import { renderWithProviders } from '../../test/render.tsx';
import { server } from '../../test/setup.ts';
import { CLUSTER, choose, serveLookups } from '../../test/teams.ts';
import type { AccessCheckView } from './api.ts';
import { AccessCheckDrawer } from './AccessCheckDrawer.tsx';

const USER = { id: 'u-1', username: 'alice' };

const check = (over: Partial<AccessCheckView>): AccessCheckView => ({
  action: 'subscription:read',
  description: 'See queues',
  scope: 'RESOURCE',
  allowed: false,
  sources: [],
  ...over,
});

const GLOBAL_RESULT: AccessCheckView[] = [
  check({
    action: 'subscription:read',
    allowed: true,
    sources: [{ type: 'ROLE_GRANT', roleName: 'VIEWER', scopeType: 'GLOBAL' }],
  }),
  check({ action: 'subscription:purge' }),
];

const TEAM_RESULT: AccessCheckView[] = [
  check({
    action: 'subscription:read',
    allowed: true,
    sources: [
      { type: 'TEAM', roleName: 'TEAM_OPERATOR', teamId: 't1', teamName: 'Orders' },
      { type: 'SHARE', roleName: 'TEAM_VIEWER', teamId: 't2', teamName: 'Billing', ownerTeamName: 'Orders' },
    ],
  }),
  check({ action: 'subscription:purge' }),
];

/** Serves the access check, recording the query each call carried. */
function serve(answer: (params: URLSearchParams) => AccessCheckView[] | Response) {
  const asked: Record<string, string | null>[] = [];
  server.use(
    http.get('*/api/v1/users/u-1/access-check', ({ request }) => {
      const url = new URL(request.url);
      asked.push(
        Object.fromEntries(
          ['clusterId', 'type', 'name'].flatMap((k) => (url.searchParams.has(k) ? [[k, url.searchParams.get(k)]] : [])),
        ),
      );
      const result = answer(url.searchParams);
      return result instanceof Response ? result : HttpResponse.json(paged(result));
    }),
    http.get('*/api/v1/users/u-1/effective-permissions', () => HttpResponse.json(paged([]))),
  );
  return asked;
}

const renderDrawer = (user: typeof USER | null = USER, onClose = vi.fn()) =>
  renderWithProviders(<AccessCheckDrawer user={user} onClose={onClose} />);

describe('AccessCheckDrawer', () => {
  it('is closed without a user', () => {
    renderDrawer(null);
    expect(screen.queryByRole('dialog')).toBeNull();
  });

  it('checks Rahdar itself at first, showing what is allowed and the role that allows it', async () => {
    serveLookups();
    const asked = serve(() => GLOBAL_RESULT);
    renderDrawer();

    expect(await screen.findByRole('dialog', { name: 'Access check for alice' })).toBeInTheDocument();
    const read = await screen.findByRole('row', { name: /subscription:read/ });
    expect(read).toHaveTextContent('Allowed');
    expect(read).toHaveTextContent('VIEWER, granted global');
    expect(screen.getByRole('row', { name: /subscription:purge/ })).toHaveTextContent('Not allowed');
    expect(asked).toEqual([{}]);
    expect(screen.getByText(/1 of 2 permissions are allowed on Rahdar itself/)).toBeInTheDocument();
  });

  it('asks about a queue on a cluster and names the team and the share that allow it', async () => {
    serveLookups();
    const asked = serve((params) => (params.get('name') ? TEAM_RESULT : GLOBAL_RESULT));
    const person = userEvent.setup();
    renderDrawer();

    await screen.findByRole('row', { name: /subscription:read/ });
    await choose(person, screen, 'Cluster', CLUSTER.name);
    await person.type(screen.getByRole('textbox', { name: 'Subscription' }), 'orders.in');
    await person.click(screen.getByRole('button', { name: 'Check access' }));

    await waitFor(() => expect(asked.at(-1)).toEqual({ clusterId: CLUSTER.id, type: 'SUBSCRIPTION', name: 'orders.in' }));
    const read = await screen.findByRole('row', { name: /subscription:read/ });
    await waitFor(() => expect(read).toHaveTextContent('TEAM_OPERATOR in team Orders'));
    expect(read).toHaveTextContent('TEAM_VIEWER shared by team Orders with team Billing');
    expect(screen.getByText(/allowed on subscription orders.in on prod/)).toBeInTheDocument();
  });

  it('asks about an address when the kind is chosen', async () => {
    serveLookups();
    const asked = serve(() => GLOBAL_RESULT);
    const person = userEvent.setup();
    renderDrawer();

    await screen.findByRole('row', { name: /subscription:read/ });
    await choose(person, screen, 'Cluster', CLUSTER.name);
    await person.click(screen.getByRole('radio', { name: 'Destination' }));
    await person.type(screen.getByRole('textbox', { name: 'Destination' }), 'orders');
    await person.click(screen.getByRole('button', { name: 'Check access' }));

    await waitFor(() => expect(asked.at(-1)).toEqual({ clusterId: CLUSTER.id, type: 'DESTINATION', name: 'orders' }));
  });

  it('keeps the name unavailable, with the reason, until a cluster is chosen', async () => {
    serveLookups();
    serve(() => GLOBAL_RESULT);
    renderDrawer();

    const name = await screen.findByRole('textbox', { name: 'Subscription' });
    expect(name).toBeDisabled();
    expect(screen.getByText('Choose a cluster first.')).toBeInTheDocument();
  });

  it('filters the permissions by name', async () => {
    serveLookups();
    serve(() => GLOBAL_RESULT);
    const person = userEvent.setup();
    renderDrawer();

    await screen.findByRole('row', { name: /subscription:purge/ });
    await person.type(screen.getByRole('textbox', { name: 'Filter by permission' }), 'purge');

    expect(screen.queryByRole('row', { name: /subscription:read/ })).toBeNull();
    expect(screen.getByRole('row', { name: /subscription:purge/ })).toBeInTheDocument();
  });

  it('shows only the allowed permissions when asked', async () => {
    serveLookups();
    serve(() => GLOBAL_RESULT);
    const person = userEvent.setup();
    renderDrawer();

    await screen.findByRole('row', { name: /subscription:purge/ });
    await person.click(screen.getByRole('switch', { name: 'Allowed only' }));

    expect(screen.queryByRole('row', { name: /subscription:purge/ })).toBeNull();
    expect(screen.getByRole('row', { name: /subscription:read/ })).toBeInTheDocument();
  });

  it('states why a check failed and retries it', async () => {
    serveLookups();
    serve(() => HttpResponse.json({ title: 'Down', detail: 'checks unavailable' }, { status: 503 }));
    renderDrawer();

    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent('checks unavailable');
    expect(within(alert).getByRole('button', { name: 'Retry' })).toBeInTheDocument();
  });

  it('closes on escape', async () => {
    serveLookups();
    serve(() => GLOBAL_RESULT);
    const onClose = vi.fn();
    const person = userEvent.setup();
    renderDrawer(USER, onClose);

    await screen.findByRole('row', { name: /subscription:read/ });
    await person.keyboard('{Escape}');
    expect(onClose).toHaveBeenCalled();
  });
});
