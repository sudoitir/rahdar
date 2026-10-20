import { describe, expect, it } from 'vitest';
import { http, HttpResponse } from 'msw';
import { screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

import { renderWithProviders } from '../../test/render.tsx';
import { server } from '../../test/setup.ts';
import type { EffectivePermissionView } from './api.ts';
import { RoleGrants } from './RoleGrants.tsx';
import { paged } from '../../kernel/api/paging.ts';

const USER = { id: 'u-1', username: 'alice' };
const CLUSTER_ID = 'c1c1c1c1-0000-0000-0000-000000000000';
const ENV_ID = 'e2e2e2e2-0000-0000-0000-000000000000';

const perm = (over: Partial<EffectivePermissionView>): EffectivePermissionView => ({
  action: 'subscription:read',
  description: null,
  scopeType: 'GLOBAL',
  scopeId: null,
  roleId: 'r-1',
  roleName: 'operator',
  via: over.action ?? 'subscription:read',
  effective: true,
  reason: null,
  ...over,
});

const PERMISSIONS: EffectivePermissionView[] = [
  perm({ action: 'subscription:read', description: 'See queues' }),
  perm({ action: 'subscription:write', via: 'subscription:*' }),
  perm({
    action: 'cluster:manage',
    scopeType: 'CLUSTER',
    scopeId: CLUSTER_ID,
    roleName: 'cluster-admin',
    effective: false,
    reason: 'This action is global only.',
  }),
  perm({ action: 'audit:read', scopeType: 'ENVIRONMENT', scopeId: ENV_ID, roleName: 'auditor' }),
  perm({ action: 'audit:export', scopeType: 'ENVIRONMENT', scopeId: null, roleName: 'auditor' }),
];

function serve(body: EffectivePermissionView[] | Response) {
  server.use(
    http.get('*/api/v1/users/u-1/effective-permissions', () =>
      body instanceof Response ? body : HttpResponse.json(paged(body)),
    ),
  );
}

const renderGrants = () => renderWithProviders(<RoleGrants userId={USER.id} />);

describe('RoleGrants', () => {
  it('says what a user with no role can do', async () => {
    serve([]);
    renderGrants();

    expect(await screen.findByText('This user holds no role')).toBeInTheDocument();
    expect(screen.getByText(/So they can do nothing/)).toBeInTheDocument();
    expect(screen.queryByRole('textbox', { name: 'Filter by permission or role' })).toBeNull();
  });

  it('states the failure and retries it', async () => {
    serve(HttpResponse.json({ title: 'Down', detail: 'permissions unavailable' }, { status: 503 }));
    const user = userEvent.setup();
    renderGrants();

    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent('Rahdar failed to complete the request');
    expect(alert).toHaveTextContent('permissions unavailable');

    serve(PERMISSIONS);
    await user.click(within(alert).getByRole('button', { name: 'Retry' }));
    expect(await screen.findByText('subscription:read')).toBeInTheDocument();
  });

  it('groups permissions per scope and names the role and wildcard each came through', async () => {
    serve(PERMISSIONS);
    renderGrants();

    await screen.findByText('subscription:read');
    expect(screen.getByText('Global')).toBeInTheDocument();
    expect(screen.getByText(`Cluster ${CLUSTER_ID.slice(0, 8)}`)).toBeInTheDocument();
    expect(screen.getByText(`Environment ${ENV_ID.slice(0, 8)}`)).toBeInTheDocument();
    // A scope with no id still names its kind.
    expect(screen.getByText('Environment')).toBeInTheDocument();

    const read = screen.getByRole('row', { name: /subscription:read/ });
    expect(read).toHaveTextContent('See queues');
    expect(read).toHaveTextContent('operator');
    expect(read).toHaveTextContent('Granted');
    expect(read).not.toHaveTextContent('through');

    // Granted by a wildcard: the pattern is named.
    expect(screen.getByRole('row', { name: /subscription:write/ })).toHaveTextContent('through subscription:*');
  });

  it('states a permission that has no effect at its scope, with the reason', async () => {
    serve(PERMISSIONS);
    renderGrants();

    const row = await screen.findByRole('row', { name: /cluster:manage/ });
    expect(row).toHaveTextContent('No effect at this scope');
    expect(row).toHaveTextContent('This action is global only.');
    expect(row).not.toHaveTextContent('Granted');
  });

  it('filters by permission or role, and offers to clear a filter that matches nothing', async () => {
    serve(PERMISSIONS);
    const user = userEvent.setup();
    renderGrants();

    const filter = await screen.findByRole('textbox', { name: 'Filter by permission or role' });
    await user.type(filter, 'AUDITOR');
    expect(screen.getByText('audit:read')).toBeInTheDocument();
    expect(screen.queryByText('subscription:read')).toBeNull();
    // A scope whose rows are all filtered out disappears with them.
    expect(screen.queryByText('Global')).toBeNull();

    await user.clear(filter);
    await user.type(filter, 'subscription:w');
    expect(screen.getByText('subscription:write')).toBeInTheDocument();
    expect(screen.queryByText('audit:read')).toBeNull();

    await user.clear(filter);
    await user.type(filter, 'nothing-like-this');
    expect(screen.getByText('Nothing matches “nothing-like-this”')).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Clear filters' }));
    expect(filter).toHaveValue('');
    expect(screen.getByText('subscription:read')).toBeInTheDocument();
  });
});
