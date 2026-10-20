import { describe, expect, it, vi } from 'vitest';
import { http, HttpResponse } from 'msw';
import { screen } from '@testing-library/react';

import { renderWithProviders } from '../../test/render.tsx';
import { server } from '../../test/setup.ts';
import { OwnerChip } from './OwnerChip.tsx';

vi.mock('@tanstack/react-router', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@tanstack/react-router')>()),
  Link: ({ children, 'aria-label': label }: { children: React.ReactNode; 'aria-label'?: string }) => (
    <a href="/admin" aria-label={label}>
      {children}
    </a>
  ),
}));

const team = { id: 't1', name: 'Orders' };

function serveAccess(permissions: string[], teamAdmin: boolean) {
  server.use(
    http.get('*/api/v1/me/access', () =>
      HttpResponse.json({
        permissions,
        anywhere: [],
        canSeeCluster: null,
        teams: [{ teamId: 't1', teamName: 'Orders', roleId: 'r1', roleName: 'TEAM_VIEWER', teamAdmin }],
        createPatterns: { subscription: [], destination: [] },
      }),
    ),
  );
}

describe('OwnerChip', () => {
  it('links to the team for an installation administrator', async () => {
    serveAccess(['user:admin'], false);
    renderWithProviders(<OwnerChip team={team} />);
    expect(await screen.findByRole('link', { name: 'Owner: team Orders' })).toBeInTheDocument();
  });

  it('links to the team for its own admin', async () => {
    serveAccess([], true);
    renderWithProviders(<OwnerChip team={team} />);
    expect(await screen.findByRole('link', { name: 'Owner: team Orders' })).toBeInTheDocument();
  });

  it('names the team without a link for a member who cannot open its page', async () => {
    serveAccess([], false);
    renderWithProviders(<OwnerChip team={team} />);
    expect(await screen.findByText('Orders')).toBeInTheDocument();
    expect(screen.queryByRole('link')).not.toBeInTheDocument();
  });

  it('says no team owns it', async () => {
    serveAccess([], false);
    renderWithProviders(<OwnerChip team={null} />);
    expect(await screen.findByText('No owner')).toBeInTheDocument();
  });
});
