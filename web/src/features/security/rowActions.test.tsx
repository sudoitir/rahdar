import { describe, expect, it } from 'vitest';
import { http, HttpResponse } from 'msw';
import { screen } from '@testing-library/react';
import { Menu } from '@mantine/core';

import { ActionHostProvider } from '../../kernel/actions/ActionHost.tsx';
import type { ActionHost } from '../../kernel/actions/types.ts';
import { renderWithProviders } from '../../test/render.tsx';
import { server } from '../../test/setup.ts';
import { AddressAccess } from './rowActions.tsx';

const HOST: ActionHost = { open: () => {}, explain: () => {}, copy: () => {} };

function renderItem() {
  return renderWithProviders(
    <ActionHostProvider>
      <Menu opened>
        <Menu.Dropdown>
          <AddressAccess clusterId="c1" target={{ address: 'orders.addr' }} host={HOST} mode="act" />
        </Menu.Dropdown>
      </Menu>
    </ActionHostProvider>,
  );
}

function signInWith(permissions: string[]) {
  server.use(
    http.get('*/api/v1/auth/me', () =>
      HttpResponse.json({
        id: 'u1',
        username: 'op',
        mustChangePassword: false,
        grants: [{ scopeType: 'GLOBAL', scopeId: null, permissions }],
      }),
    ),
  );
}

describe('AddressAccess', () => {
  it('offers who has access to an administrator', async () => {
    signInWith(['user:admin']);
    renderItem();

    expect(await screen.findByRole('menuitem', { name: /Who has access/ })).toBeInTheDocument();
  });

  it('leaves it out for someone who administers nothing', async () => {
    signInWith(['destination:read']);
    renderItem();

    await new Promise((resolve) => setTimeout(resolve, 100));
    expect(screen.queryByRole('menuitem', { name: /Who has access/ })).not.toBeInTheDocument();
  });
});
