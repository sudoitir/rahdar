import { describe, expect, it } from 'vitest';
import { http, HttpResponse } from 'msw';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

import { renderWithProviders } from '../../test/render.tsx';
import { server } from '../../test/setup.ts';
import type { RoleView } from './api.ts';
import { RolesPanel } from './RolesPanel.tsx';
import { paged } from '../../kernel/api/paging.ts';

const ADMIN: RoleView = {
  id: 'r-admin',
  name: 'ADMIN',
  builtin: true,
  permissions: ['*'],
  requiresMfa: true,
  teamAssignable: false,
};
const VIEWER: RoleView = {
  id: 'r-viewer',
  name: 'VIEWER',
  builtin: true,
  permissions: ['cluster:read'],
  requiresMfa: false,
  teamAssignable: false,
};
const AUDITOR: RoleView = {
  id: 'r-auditor',
  name: 'AUDITOR',
  builtin: false,
  permissions: ['audit:read'],
  requiresMfa: false,
  teamAssignable: false,
};

function serve(roles: RoleView[]) {
  server.use(
    http.get('*/api/v1/roles', () => HttpResponse.json(paged(roles))),
    http.get('*/api/v1/permissions', () =>
      HttpResponse.json(
        paged([
          {
            action: 'audit:read',
            label: 'Read the audit trail',
            featureId: 'audit',
            featureTitle: 'Audit',
            scope: 'CLUSTER',
            resourceTypes: [],
            requires: [],
          },
        ]),
      ),
    ),
  );
}

describe('RolesPanel two-step verification', () => {
  it('says in words which roles require it', async () => {
    serve([ADMIN, VIEWER]);
    renderWithProviders(<RolesPanel />);

    const admin = await screen.findByRole('row', { name: /^ADMIN/ });
    const viewer = screen.getByRole('row', { name: /^VIEWER/ });
    expect(within(admin).getByText('Required')).toBeInTheDocument();
    expect(within(viewer).getByText('Not required')).toBeInTheDocument();
  });

  it('lets a built-in role change only the switch, showing the rest as facts', async () => {
    let sent: unknown;
    serve([ADMIN, VIEWER]);
    server.use(
      http.put('*/api/v1/roles/r-viewer', async ({ request }) => {
        sent = await request.json();
        return HttpResponse.json({ ...VIEWER, requiresMfa: true, teamAssignable: false });
      }),
    );
    const user = userEvent.setup();
    renderWithProviders(<RolesPanel />);

    await user.click(await screen.findByRole('button', { name: 'Edit VIEWER' }));

    const dialog = await screen.findByRole('dialog', { name: 'Edit "VIEWER"' });
    // The name and permissions are facts, not inputs.
    expect(within(dialog).queryByLabelText(/^Name/)).not.toBeInTheDocument();
    expect(within(dialog).getByText('cluster:read')).toBeInTheDocument();
    expect(within(dialog).getByText(/Built-in roles keep their name and permissions/)).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Delete VIEWER' })).not.toBeInTheDocument();

    const mfa = within(dialog).getByRole('switch', { name: /^Require two-step verification/ });
    expect(mfa).not.toBeChecked();
    expect(
      within(dialog).getByText('Applies to local accounts. Single sign-on users rely on their identity provider.'),
    ).toBeInTheDocument();
    expect(within(dialog).queryByText('Saving signs out everyone who holds this role.')).not.toBeInTheDocument();
    await user.click(mfa);
    expect(within(dialog).getByText('Saving signs out everyone who holds this role.')).toBeInTheDocument();
    await user.click(within(dialog).getByRole('button', { name: 'Save role' }));

    await waitFor(() =>
      expect(sent).toEqual({ name: 'VIEWER', permissions: ['cluster:read'], requiresMfa: true, teamAssignable: false }),
    );
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
  });

  it('keeps a custom role editable in full and sends its setting with it', async () => {
    let sent: unknown;
    serve([AUDITOR]);
    server.use(
      http.put('*/api/v1/roles/r-auditor', async ({ request }) => {
        sent = await request.json();
        return HttpResponse.json(AUDITOR);
      }),
    );
    const user = userEvent.setup();
    renderWithProviders(<RolesPanel />);

    await user.click(await screen.findByRole('button', { name: 'Edit AUDITOR' }));
    const dialog = await screen.findByRole('dialog');
    expect(within(dialog).getByLabelText(/^Name/)).toHaveValue('AUDITOR');
    await user.click(within(dialog).getByRole('switch', { name: /^Require two-step verification/ }));
    await user.click(within(dialog).getByRole('button', { name: 'Save role' }));

    await waitFor(() =>
      expect(sent).toEqual({ name: 'AUDITOR', permissions: ['audit:read'], requiresMfa: true, teamAssignable: false }),
    );
  });
});
