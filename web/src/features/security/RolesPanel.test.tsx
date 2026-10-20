import { afterEach, describe, expect, it } from 'vitest';
import { http, HttpResponse } from 'msw';
import { act, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { Notifications, notifications } from '@mantine/notifications';

import { renderWithProviders } from '../../test/render.tsx';
import { server } from '../../test/setup.ts';
import type { PermissionView, RoleView } from './api.ts';
import { RolesPanel } from './RolesPanel.tsx';
import { paged } from '../../kernel/api/paging.ts';
import { holdButton } from '../../test/hold.ts';

const CATALOGUE: PermissionView[] = [
  {
    action: 'subscription:create',
    label: 'Create queues',
    featureId: 'queues',
    featureTitle: 'Queues',
    scope: 'CLUSTER',
    resourceTypes: [],
    requires: [],
  },
  {
    action: 'subscription:delete',
    label: 'Destroy queues',
    featureId: 'queues',
    featureTitle: 'Queues',
    scope: 'CLUSTER',
    resourceTypes: [],
    requires: [],
  },
];

const ROLES: RoleView[] = [
  { id: 'r-admin', name: 'ADMIN', builtin: true, permissions: ['*'], requiresMfa: true, teamAssignable: false },
  {
    id: 'r-1',
    name: 'queue-operator',
    builtin: false,
    permissions: ['subscription:create', 'subscription:delete'],
    requiresMfa: false,
    teamAssignable: false,
  },
  {
    id: 'r-2',
    name: 'queue-creator',
    builtin: false,
    permissions: ['subscription:create'],
    requiresMfa: false,
    teamAssignable: false,
  },
  {
    id: 'r-3',
    name: 'queue-clone',
    builtin: false,
    permissions: ['subscription:create', 'subscription:delete'],
    requiresMfa: false,
    teamAssignable: false,
  },
];

function serve(roles: RoleView[] = ROLES, catalogue: PermissionView[] | Response = CATALOGUE) {
  server.use(
    http.get('*/api/v1/roles', () => HttpResponse.json(paged(roles))),
    http.get('*/api/v1/permissions', () =>
      catalogue instanceof Response ? catalogue : HttpResponse.json(paged(catalogue)),
    ),
  );
}

function renderRoles() {
  return renderWithProviders(
    <>
      <Notifications />
      <RolesPanel />
    </>,
  );
}

afterEach(() => act(() => notifications.clean()));

describe('RolesPanel list', () => {
  it('counts the roles, marks built-in ones and offers delete only for custom ones', async () => {
    serve();
    renderRoles();

    expect(await screen.findByText('4 roles')).toBeInTheDocument();
    const builtin = screen.getByRole('row', { name: /ADMIN/ });
    expect(within(builtin).getByText('built-in')).toBeInTheDocument();
    // A built-in role can still change whether it requires two-step verification, but is never deleted.
    expect(within(builtin).getByRole('button', { name: 'Edit ADMIN' })).toBeInTheDocument();
    expect(within(builtin).queryByRole('button', { name: 'Delete ADMIN' })).toBeNull();
    const custom = screen.getByRole('row', { name: /queue-operator/ });
    expect(custom).toHaveTextContent('subscription:create, subscription:delete');
    expect(within(custom).getByRole('button', { name: 'Edit queue-operator' })).toBeInTheDocument();
    expect(within(custom).getByRole('button', { name: 'Delete queue-operator' })).toBeInTheDocument();
  });

  it('counts a single role in the singular', async () => {
    serve([ROLES[0]]);
    renderRoles();
    expect(await screen.findByText('1 role')).toBeInTheDocument();
  });

  it('deletes a custom role only once its name is typed, and announces it', async () => {
    serve();
    let deleted = '';
    server.use(
      http.delete('*/api/v1/roles/:id', ({ params }) => {
        deleted = String(params.id);
        return new HttpResponse(null, { status: 204 });
      }),
    );
    const user = userEvent.setup();
    renderRoles();

    await user.click(await screen.findByRole('button', { name: 'Delete queue-creator' }));
    const dialog = await screen.findByRole('dialog', { name: 'Delete "queue-creator"' });
    expect(dialog).toHaveTextContent('1 permission');
    const confirm = within(dialog).getByRole('button', { name: 'Delete role' });
    await holdButton(confirm);

    await waitFor(() => expect(deleted).toBe('r-2'));
    expect(await screen.findByRole('status')).toHaveTextContent('Deleted role "queue-creator"');
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
  });

  it('says why a role could not be deleted and what to do', async () => {
    serve();
    server.use(
      http.delete('*/api/v1/roles/r-2', () =>
        HttpResponse.json({ title: 'Conflict', detail: 'The role is still granted to 2 users.' }, { status: 409 }),
      ),
    );
    const user = userEvent.setup();
    renderRoles();

    await user.click(await screen.findByRole('button', { name: 'Delete queue-creator' }));
    const dialog = await screen.findByRole('dialog');
    await holdButton(within(dialog).getByRole('button', { name: 'Delete role' }));

    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent('Could not delete role "queue-creator"');
    expect(alert).toHaveTextContent(
      'The role is still granted to 2 users. Remove it from the users who hold it, then delete it.',
    );
    expect(screen.getByRole('dialog')).toBeInTheDocument();
  });
});

describe('RolesPanel editor', () => {
  it('creates a role from a name and the permissions picked', async () => {
    serve();
    let body: unknown;
    server.use(
      http.post('*/api/v1/roles', async ({ request }) => {
        body = await request.json();
        return HttpResponse.json({
          id: 'r-9',
          name: 'new-role',
          builtin: false,
          permissions: ['subscription:create', 'subscription:delete'],
        });
      }),
    );
    const user = userEvent.setup();
    renderRoles();

    await user.click(await screen.findByRole('button', { name: 'New role' }));
    const dialog = await screen.findByRole('dialog', { name: 'New role' });
    await user.type(within(dialog).getByRole('textbox', { name: /Name/ }), 'new-role');
    await user.click(await within(dialog).findByRole('checkbox', { name: 'Select all in Queues' }));
    await user.click(within(dialog).getByRole('button', { name: 'Create role' }));

    await waitFor(() =>
      expect(body).toEqual({
        name: 'new-role',
        permissions: ['subscription:create', 'subscription:delete'],
        requiresMfa: false,
        teamAssignable: false,
      }),
    );
    await waitFor(() => expect(screen.queryByRole('dialog', { name: 'New role' })).toBeNull());
  });

  it('offers a team role only resource permissions, and saves it as team-assignable', async () => {
    const mixed: PermissionView[] = [
      ...CATALOGUE,
      {
        action: 'subscription:read',
        label: 'Read queues',
        featureId: 'queues',
        featureTitle: 'Queues',
        scope: 'RESOURCE',
        resourceTypes: ['SUBSCRIPTION'],
        requires: [],
      },
    ];
    serve(ROLES, mixed);
    let body: unknown;
    server.use(
      http.post('*/api/v1/roles', async ({ request }) => {
        body = await request.json();
        return HttpResponse.json({ ...ROLES[1], id: 'r-9' });
      }),
    );
    const user = userEvent.setup();
    renderRoles();

    await user.click(await screen.findByRole('button', { name: 'New role' }));
    const dialog = await screen.findByRole('dialog', { name: 'New role' });
    expect(await within(dialog).findByRole('button', { name: /Queues, 0 of 3 selected/ })).toBeInTheDocument();
    await user.type(within(dialog).getByRole('textbox', { name: /Name/ }), 'orders-reader');
    await user.click(within(dialog).getByRole('switch', { name: /Team role/ }));

    expect(await within(dialog).findByRole('button', { name: /Queues, 0 of 1 selected/ })).toBeInTheDocument();
    await user.click(within(dialog).getByRole('checkbox', { name: 'Select all in Queues' }));
    await user.click(within(dialog).getByRole('button', { name: 'Create role' }));

    await waitFor(() =>
      expect(body).toEqual({
        name: 'orders-reader',
        permissions: ['subscription:read'],
        requiresMfa: false,
        teamAssignable: true,
      }),
    );
  });

  it('shows a built-in role as a team role or not, as a fact', async () => {
    serve([{ ...ROLES[0], id: 'r-team', name: 'Team Viewer', teamAssignable: true }]);
    const user = userEvent.setup();
    renderRoles();

    await user.click(await screen.findByRole('button', { name: 'Edit Team Viewer' }));
    const dialog = await screen.findByRole('dialog', { name: 'Edit "Team Viewer"' });
    expect(within(dialog).getByText('Team role')).toBeInTheDocument();
    expect(within(dialog).getByText('Yes')).toBeInTheDocument();
    expect(within(dialog).queryByRole('switch', { name: /Team role/ })).not.toBeInTheDocument();
  });

  it('edits a role with its name and permissions filled in, and saves it under its id', async () => {
    serve();
    let put: { url: string; body: unknown } | undefined;
    server.use(
      http.put('*/api/v1/roles/:id', async ({ request }) => {
        put = { url: new URL(request.url).pathname, body: await request.json() };
        return HttpResponse.json(ROLES[2]);
      }),
    );
    const user = userEvent.setup();
    renderRoles();

    await user.click(await screen.findByRole('button', { name: 'Edit queue-creator' }));
    const dialog = await screen.findByRole('dialog', { name: 'Edit "queue-creator"' });
    const name = within(dialog).getByRole('textbox', { name: /Name/ });
    expect(name).toHaveValue('queue-creator');
    expect(await within(dialog).findByRole('button', { name: /Queues, 1 of 2 selected/ })).toBeInTheDocument();
    await user.clear(name);
    await user.type(name, 'queue-maker');
    await user.click(within(dialog).getByRole('button', { name: 'Save role' }));

    await waitFor(() =>
      expect(put).toEqual({
        url: '/api/v1/roles/r-2',
        body: { name: 'queue-maker', permissions: ['subscription:create'], requiresMfa: false, teamAssignable: false },
      }),
    );
  });

  it('states what saving adds to and removes from a role’s permissions, by name', async () => {
    serve();
    const user = userEvent.setup();
    renderRoles();

    await user.click(await screen.findByRole('button', { name: 'Edit queue-operator' }));
    const dialog = await screen.findByRole('dialog', { name: 'Edit "queue-operator"' });
    expect(within(dialog).queryByRole('status', { name: 'Permission changes' })).not.toBeInTheDocument();
    await user.click(await within(dialog).findByRole('checkbox', { name: /subscription:delete/ }));

    const diff = within(dialog).getByRole('status', { name: 'Permission changes' });
    expect(diff).toHaveTextContent('−1 removed: subscription:delete');
    expect(diff).not.toHaveTextContent('added');
  });

  it('keeps the editor open and says why when a save is refused', async () => {
    serve();
    server.use(
      http.post('*/api/v1/roles', () =>
        HttpResponse.json({ title: 'Conflict', detail: 'A role named "x" already exists.' }, { status: 409 }),
      ),
      http.put('*/api/v1/roles/r-2', () =>
        HttpResponse.json({ title: 'Invalid', detail: 'Unknown permission.' }, { status: 400 }),
      ),
    );
    const user = userEvent.setup();
    renderRoles();

    await user.click(await screen.findByRole('button', { name: 'New role' }));
    let dialog = await screen.findByRole('dialog', { name: 'New role' });
    await user.type(within(dialog).getByRole('textbox', { name: /Name/ }), 'x');
    await user.click(within(dialog).getByRole('button', { name: 'Create role' }));
    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent('Could not create role "x"');
    expect(alert).toHaveTextContent('A role named "x" already exists. No role was created. Try again.');
    expect(screen.getByRole('dialog', { name: 'New role' })).toBeInTheDocument();

    // A typed name is unsaved input: Escape asks before it is thrown away.
    await user.keyboard('{Escape}');
    await user.click(within(dialog).getByRole('button', { name: 'Discard changes' }));
    await waitFor(() => expect(screen.queryByRole('dialog', { name: 'New role' })).toBeNull());
    await user.click(screen.getByRole('button', { name: 'Edit queue-creator' }));
    dialog = await screen.findByRole('dialog', { name: 'Edit "queue-creator"' });
    await user.click(within(dialog).getByRole('button', { name: 'Save role' }));
    await waitFor(() =>
      expect(screen.getAllByRole('alert').map((a) => a.textContent)).toContainEqual(
        expect.stringContaining('Unknown permission. The role is unchanged. Try again.'),
      ),
    );
  });

  it('asks for a name on save, beside the field, and puts focus there', async () => {
    serve();
    const user = userEvent.setup();
    renderRoles();

    await user.click(await screen.findByRole('button', { name: 'New role' }));
    const dialog = await screen.findByRole('dialog', { name: 'New role' });
    await user.click(within(dialog).getByRole('button', { name: 'Create role' }));

    expect(await within(dialog).findByText('Name the role after what its holders do.')).toBeInTheDocument();
    expect(within(dialog).getByRole('textbox', { name: /Name/ })).toHaveFocus();
  });

  it('states that the permission catalogue could not be loaded, and retries it', async () => {
    serve(ROLES, HttpResponse.json({ title: 'Down', detail: 'catalogue unavailable' }, { status: 503 }));
    const user = userEvent.setup();
    renderRoles();

    await user.click(await screen.findByRole('button', { name: 'New role' }));
    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent('Rahdar failed to complete the request');
    expect(alert).toHaveTextContent('catalogue unavailable');

    serve(ROLES, CATALOGUE);
    await user.click(within(alert).getByRole('button', { name: 'Retry' }));
    expect(await screen.findByRole('checkbox', { name: 'Select all in Queues' })).toBeInTheDocument();
  });

  it('says the permissions are loading while the catalogue has not arrived', async () => {
    server.use(
      http.get('*/api/v1/roles', () => HttpResponse.json(paged(ROLES))),
      http.get('*/api/v1/permissions', () => new Promise(() => {})),
    );
    const user = userEvent.setup();
    renderRoles();

    await user.click(await screen.findByRole('button', { name: 'New role' }));
    expect(await screen.findByRole('status')).toHaveTextContent('Loading permissions');
  });
});

describe('RolesPanel compare', () => {
  async function openCompare() {
    const user = userEvent.setup();
    renderRoles();
    await user.click(await screen.findByRole('button', { name: 'Compare roles' }));
    const dialog = await screen.findByRole('dialog', { name: 'Compare roles' });
    const pick = async (label: string, role: string) => {
      await user.click(within(dialog).getByRole('combobox', { name: label }));
      const list = await screen.findByRole('listbox', { name: label });
      await user.click(within(list).getByRole('option', { name: role }));
    };
    return { dialog, pick };
  }

  it('asks for two roles before it compares', async () => {
    serve();
    const { dialog, pick } = await openCompare();

    expect(
      within(dialog).getByText('Pick two roles to see the permissions only one of them holds.'),
    ).toBeInTheDocument();
    await pick('First role', 'queue-operator');
    expect(
      within(dialog).getByText('Pick two roles to see the permissions only one of them holds.'),
    ).toBeInTheDocument();
  });

  it('lists what only one of two roles holds', async () => {
    serve();
    const { dialog, pick } = await openCompare();

    await pick('First role', 'queue-operator');
    await pick('Second role', 'queue-creator');

    expect(within(dialog).getByText('Only in queue-operator (1)')).toBeInTheDocument();
    expect(within(dialog).getByRole('list', { name: 'Only in queue-operator' })).toHaveTextContent(
      'subscription:delete',
    );
    expect(within(dialog).getByText('Only in queue-creator (0)')).toBeInTheDocument();
    expect(within(dialog).getByText('Nothing')).toBeInTheDocument();
  });

  it('says two roles that hold the same permissions do', async () => {
    serve();
    const { dialog, pick } = await openCompare();

    await pick('First role', 'queue-operator');
    await pick('Second role', 'queue-clone');

    expect(within(dialog).getByRole('status')).toHaveTextContent(
      'queue-operator and queue-clone hold the same permissions.',
    );
  });
});
