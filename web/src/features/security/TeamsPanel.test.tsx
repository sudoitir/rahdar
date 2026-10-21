import { afterEach, describe, expect, it, vi } from 'vitest';
import { http, HttpResponse } from 'msw';
import { act, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { notifications } from '@mantine/notifications';

import { paged } from '../../kernel/api/paging.ts';
import { notify } from '../../ui/notify.ts';
import { renderAppAt } from '../../test/render.tsx';
import { server } from '../../test/setup.ts';
import { preview, serveLookups, summary, team } from '../../test/teams.ts';
import { holdButton } from '../../test/hold.ts';

afterEach(() => {
  vi.restoreAllMocks();
  act(() => notifications.clean());
});

const orders = team();

function serveTeams(permissions = ['user:admin']) {
  serveLookups(permissions);
  server.use(
    http.get('*/api/v1/teams', () => HttpResponse.json(paged([summary(orders)]))),
    http.get('*/api/v1/teams/t-orders', () => HttpResponse.json(orders)),
    http.get('*/api/v1/auth/providers', () => HttpResponse.json(paged([]))),
  );
}

describe('the Teams tab', () => {
  it('lists each team with what it owns per cluster, and opens one from its name, a real link', async () => {
    serveTeams();
    const person = userEvent.setup();
    const { router } = renderAppAt('/admin?tab=teams');

    const row = await screen.findByRole('row', { name: /Orders/ });
    // What it owns is named per cluster, with each pattern's kind.
    await waitFor(() => expect(row).toHaveTextContent('prod: orders.# (subscriptions)'));
    expect(screen.getByText('1 team')).toBeInTheDocument();

    // A link, so it opens in a new tab and with a middle click like any other.
    const link = within(row).getByRole('link', { name: 'Orders' });
    expect(link).toHaveAttribute('href', expect.stringContaining('team=t-orders'));
    await person.click(link);

    expect(await screen.findByRole('heading', { level: 2, name: 'Orders' })).toBeInTheDocument();
    expect(router.state.location.search).toMatchObject({ tab: 'teams', team: 't-orders' });
    expect(screen.getByRole('tab', { name: 'Patterns (1)' })).toHaveAttribute('aria-selected', 'true');
    expect(screen.getByRole('tab', { name: 'Members (2)' })).toBeInTheDocument();
    expect(screen.getByText(/Owns 1 pattern on 1 cluster · 2 members · no shares/)).toBeInTheDocument();
    expect(screen.getAllByRole('heading', { level: 1 })).toHaveLength(1);
  });

  it('opens the team and section the address names, and links back to the list', async () => {
    serveTeams();
    const person = userEvent.setup();
    const { router } = renderAppAt('/admin?tab=teams&team=t-orders&teamTab=members');

    expect(await screen.findByRole('tab', { name: /^Members/ })).toHaveAttribute('aria-selected', 'true');
    const back = screen.getByRole('link', { name: 'All teams' });
    expect(back).toHaveAttribute('href', expect.not.stringContaining('team='));
    await person.click(back);

    expect(await screen.findByRole('table', { name: 'Teams' })).toBeInTheDocument();
    expect(router.state.location.search).toEqual({ tab: 'teams' });
  });

  it('filters by name or pattern in the address, and says when the filter hides every team', async () => {
    serveTeams();
    const person = userEvent.setup();
    const { router } = renderAppAt('/admin?tab=teams');

    const filter = await screen.findByRole('textbox', { name: 'Filter teams' });
    await person.type(filter, 'orders.#');
    expect(await screen.findByRole('row', { name: /Orders/ })).toBeInTheDocument();
    await person.clear(filter);
    await person.type(filter, 'billing');

    expect(await screen.findByText('No team matches “billing”')).toBeInTheDocument();
    expect(router.state.location.search).toMatchObject({ teamQ: 'billing' });
    await person.click(screen.getByRole('button', { name: 'Clear filters' }));
    expect(await screen.findByRole('row', { name: /Orders/ })).toBeInTheDocument();
  });

  it('teaches what a team is when there are none, and offers to create one', async () => {
    serveLookups();
    server.use(http.get('*/api/v1/teams', () => HttpResponse.json(paged([]))));
    renderAppAt('/admin?tab=teams');

    expect(await screen.findByText('No teams')).toBeInTheDocument();
    expect(screen.getByText(/Create one to share a cluster between groups of people/)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Create team' })).toBeEnabled();
  });

  it('opens a new team on its patterns with focus in the first field, and Back returns to the list', async () => {
    serveTeams();
    const created = team({ id: 't-payments', name: 'Payments', patterns: [], members: [] });
    server.use(
      http.post('*/api/v1/teams', () => HttpResponse.json(created, { status: 201 })),
      http.get('*/api/v1/teams/t-payments', () => HttpResponse.json(created)),
    );
    const succeeded = vi.spyOn(notify, 'succeeded');
    const person = userEvent.setup();
    const { router } = renderAppAt('/admin?tab=teams');

    await person.click(await screen.findByRole('button', { name: 'New team' }));
    const dialog = await screen.findByRole('dialog', { name: 'New team' });
    await person.type(within(dialog).getByRole('textbox', { name: /Name/ }), 'Payments');
    await person.click(within(dialog).getByRole('button', { name: 'Create team' }));

    expect(await screen.findByRole('heading', { level: 2, name: 'Payments' })).toBeInTheDocument();
    expect(router.state.location.search).toMatchObject({ tab: 'teams', team: 't-payments' });
    expect(screen.getByRole('tab', { name: 'Patterns (0)' })).toHaveAttribute('aria-selected', 'true');
    await waitFor(() => expect(screen.getByRole('combobox', { name: /^Cluster/ })).toHaveFocus());
    expect(succeeded).toHaveBeenCalledWith(expect.objectContaining({ subject: 'team Payments' }));

    act(() => router.history.back());
    expect(await screen.findByRole('table', { name: 'Teams' })).toBeInTheDocument();
    expect(router.state.location.search).toEqual({ tab: 'teams' });
  });

  it('renames a team from its page and stays there', async () => {
    serveTeams();
    const renamed = { ...orders, name: 'Order desk' };
    server.use(http.put('*/api/v1/teams/t-orders', () => HttpResponse.json(renamed)));
    const succeeded = vi.spyOn(notify, 'succeeded');
    const person = userEvent.setup();
    const { router } = renderAppAt('/admin?tab=teams&team=t-orders');

    await person.click(await screen.findByRole('button', { name: 'Rename Orders' }));
    const dialog = await screen.findByRole('dialog', { name: 'Rename Orders' });
    const name = within(dialog).getByRole('textbox', { name: /Name/ });
    await person.clear(name);
    await person.type(name, 'Order desk');
    await person.click(within(dialog).getByRole('button', { name: 'Rename team' }));

    await waitFor(() =>
      expect(succeeded).toHaveBeenCalledWith(expect.objectContaining({ subject: 'team Order desk' })),
    );
    await waitFor(() => expect(screen.queryByRole('dialog', { name: 'Rename Orders' })).not.toBeInTheDocument());
    expect(router.state.location.search).toMatchObject({ team: 't-orders' });
  });

  it('states a failed load and offers a retry, rather than showing no teams', async () => {
    serveLookups();
    server.use(
      http.get('*/api/v1/teams', () =>
        HttpResponse.json({ title: 'Boom', detail: 'The database is down.' }, { status: 500 }),
      ),
    );
    renderAppAt('/admin?tab=teams');

    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent('The database is down.');
    expect(within(alert).getByRole('button', { name: 'Retry' })).toBeInTheDocument();
  });

  it('creates a team, and answers a name already taken beside the field', async () => {
    serveTeams();
    let attempts = 0;
    server.use(
      http.post('*/api/v1/teams', () => {
        attempts += 1;
        return HttpResponse.json(
          { type: 'https://rahdar/problems/duplicate-team-name', title: 'Conflict', detail: 'exists' },
          { status: 409 },
        );
      }),
    );
    const person = userEvent.setup();
    renderAppAt('/admin?tab=teams');

    await person.click(await screen.findByRole('button', { name: 'New team' }));
    const dialog = await screen.findByRole('dialog', { name: 'New team' });
    await person.click(within(dialog).getByRole('button', { name: 'Create team' }));
    expect(await within(dialog).findByText(/Name the team after/)).toBeInTheDocument();
    expect(attempts).toBe(0);

    await person.type(within(dialog).getByRole('textbox', { name: /Name/ }), 'Orders');
    await person.click(within(dialog).getByRole('button', { name: 'Create team' }));

    expect(
      await within(dialog).findByText('A team with that name already exists. Choose another name.'),
    ).toBeInTheDocument();
    expect(within(dialog).getByRole('textbox', { name: /Name/ })).toHaveFocus();
  });

  it('deletes a team only once its name is typed, stating what goes with it', async () => {
    serveTeams();
    let deleted = '';
    server.use(
      http.delete('*/api/v1/teams/:id', ({ params }) => {
        deleted = String(params.id);
        return new HttpResponse(null, { status: 204 });
      }),
    );
    const person = userEvent.setup();
    renderAppAt('/admin?tab=teams');

    await person.click(await screen.findByRole('button', { name: 'Delete Orders' }));
    const dialog = await screen.findByRole('dialog', { name: 'Delete Orders' });
    expect(dialog).toHaveTextContent("team's 1 pattern, 2 members and 0 shares");
    const confirm = within(dialog).getByRole('button', { name: 'Delete team' });
    await holdButton(confirm);

    await waitFor(() => expect(deleted).toBe('t-orders'));
  });

  it('deletes a team from its page and replaces it with the list, so Back cannot return to it', async () => {
    serveTeams();
    server.use(http.delete('*/api/v1/teams/:id', () => new HttpResponse(null, { status: 204 })));
    const person = userEvent.setup();
    const { router } = renderAppAt('/admin?tab=users');
    act(() => void router.navigate({ to: '/admin', search: { tab: 'teams', team: 't-orders' } as never }));

    await person.click(await screen.findByRole('button', { name: 'Delete Orders' }));
    const dialog = await screen.findByRole('dialog', { name: 'Delete Orders' });
    await holdButton(within(dialog).getByRole('button', { name: 'Delete team' }));

    expect(await screen.findByRole('table', { name: 'Teams' })).toBeInTheDocument();
    expect(router.state.location.search).toEqual({ tab: 'teams' });
    act(() => router.history.back());
    await waitFor(() => expect(router.state.location.search).toEqual({ tab: 'users' }));
  });

  it('shows a team admin their teams, and keeps creating, renaming and deleting for administrators', async () => {
    serveTeams(['team:admin']);
    renderAppAt('/admin?tab=teams');

    expect(await screen.findByRole('row', { name: /Orders/ })).toBeInTheDocument();
    await waitFor(() => expect(screen.getByRole('button', { name: 'New team' })).toBeDisabled());
    expect(screen.getByRole('button', { name: 'Rename Orders' })).toBeDisabled();
    expect(screen.getByRole('button', { name: 'Delete Orders' })).toBeDisabled();
    expect(screen.getByText(/You see the teams you administer/)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Why creating a team is unavailable' })).toBeInTheDocument();
  });
});

describe('the Unowned section', () => {
  it('lists what no team owns on a cluster and pre-fills a pattern from one name', async () => {
    serveTeams();
    let asked = '';
    server.use(
      http.get('*/api/v1/clusters/c-prod/unowned', ({ request }) => {
        asked = new URL(request.url).search;
        return HttpResponse.json(paged([{ name: 'legacy.inbox' }, { name: 'tmp.scratch' }]));
      }),
      http.get('*/api/v1/teams/t-orders/patterns/preview', ({ request }) =>
        HttpResponse.json(preview({ pattern: new URL(request.url).searchParams.get('pattern') ?? '' })),
      ),
    );
    const person = userEvent.setup();
    renderAppAt('/admin?tab=teams&team=t-orders&teamTab=unowned');

    await person.click(await screen.findByRole('combobox', { name: 'Cluster' }));
    await person.click(await screen.findByRole('option', { name: 'prod', hidden: true }));

    expect(await screen.findByRole('row', { name: /legacy\.inbox/ })).toBeInTheDocument();
    expect(asked).toContain('type=SUBSCRIPTION');

    await person.click(screen.getByRole('button', { name: 'Assign legacy.inbox to this team' }));
    await person.click(await screen.findByRole('menuitem', { name: 'Subscriptions and destinations' }));

    expect(await screen.findByRole('tab', { name: /^Patterns/ })).toHaveAttribute('aria-selected', 'true');
    expect(await screen.findByRole('textbox', { name: /^Pattern/ })).toHaveValue('legacy.inbox');
    expect(screen.getByRole('radio', { name: 'Subscriptions and destinations' })).toBeChecked();
  });

  it('says every name is owned when none is left', async () => {
    serveTeams();
    server.use(http.get('*/api/v1/clusters/c-prod/unowned', () => HttpResponse.json(paged([]))));
    const person = userEvent.setup();
    renderAppAt('/admin?tab=teams&team=t-orders&teamTab=unowned');

    await person.click(await screen.findByRole('combobox', { name: 'Cluster' }));
    await person.click(await screen.findByRole('option', { name: 'prod', hidden: true }));

    expect(await screen.findByText('Every subscription on prod is owned')).toBeInTheDocument();
  });

  it('is withheld from a team admin with the reason, and asks the server nothing', async () => {
    serveTeams(['team:admin']);
    renderAppAt('/admin?tab=teams&team=t-orders&teamTab=unowned');

    await waitFor(() => expect(screen.getByRole('combobox', { name: 'Cluster' })).toBeDisabled());
    expect(
      screen.getByText(/Listing what no team owns, and assigning it, needs the user:admin permission/),
    ).toBeInTheDocument();
  });
});
