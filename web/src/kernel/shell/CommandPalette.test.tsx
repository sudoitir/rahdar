import { subscriptionIdentity } from '../../test/wire.ts';
import { describe, expect, it, vi } from 'vitest';
import { http, HttpResponse } from 'msw';
import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

import { renderWithProviders } from '../../test/render.tsx';
import { manifestHandler } from '../../test/manifest.ts';
import { server } from '../../test/setup.ts';
import { paged } from '../api/paging.ts';

const navigate = vi.fn();

vi.mock('@tanstack/react-router', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@tanstack/react-router')>()),
  useNavigate: () => navigate,
  useParams: () => ({ clusterId: 'c1' }),
  useLocation: () => ({ pathname: '/clusters/c1/topology', search: {} }),
}));

// Imported after the mock is registered.
const { CommandPalette } = await import('./CommandPalette.tsx');

const subscriptionRequests: string[] = [];

function mockApi(permissions: string[] = ['*']) {
  subscriptionRequests.length = 0;
  server.use(
    manifestHandler(),
    http.get('*/api/v1/auth/me', () =>
      HttpResponse.json({
        id: 'u1',
        username: 'op',
        mustChangePassword: false,
        grants: [{ scopeType: 'GLOBAL', scopeId: null, permissions }],
      }),
    ),
    http.get('*/api/v1/clusters', () =>
      HttpResponse.json(
        paged([
          {
            id: 'c1',
            name: 'prod-eu',
            description: null,
            health: 'OK',
            nodeCount: 2,
            updatedAt: new Date().toISOString(),
          },
        ]),
      ),
    ),
    http.get('*/api/v1/clusters/c1/subscriptions', ({ request }) => {
      subscriptionRequests.push(new URL(request.url).search);
      return HttpResponse.json({
        data: [
          {
            ...subscriptionIdentity('ORDERS', 'ORDERS'),
            durable: true,
            totalMessageCount: 7,
            totalConsumerCount: 1,
            totalDeliveringCount: 0,
            totalScheduledCount: 0,
            nodesPresent: 1,
            nodesTotal: 2,
            perNode: [],
          },
        ],
        count: 1,
        page: 1,
        pageSize: 50,
      });
    }),
  );
}

describe('CommandPalette', () => {
  it('opens on mod+K, filters, and navigates when an action is invoked', async () => {
    navigate.mockClear();
    mockApi();
    const user = userEvent.setup();
    renderWithProviders(<CommandPalette />);

    await user.keyboard('{Control>}k{/Control}');

    const search = await screen.findByPlaceholderText(/jump to a cluster/i);
    await user.type(search, 'prod-eu');

    const action = await screen.findByRole('button', { name: /prod-eu/i });
    await user.click(action);

    // Already on the Topology view, so the other cluster opens on the same view.
    expect(navigate).toHaveBeenCalledWith({ to: '/clusters/c1/topology' });
  });

  it('searches subscriptions only once the palette is open and two characters are typed, and opens the subscription', async () => {
    navigate.mockClear();
    mockApi();
    const user = userEvent.setup();
    renderWithProviders(<CommandPalette />);

    // Closed, the palette reads nothing.
    await new Promise((r) => setTimeout(r, 300));
    expect(subscriptionRequests).toEqual([]);

    await user.keyboard('{Control>}k{/Control}');
    const search = await screen.findByPlaceholderText(/jump to a cluster/i);
    await user.type(search, 'O');
    await new Promise((r) => setTimeout(r, 300));
    expect(subscriptionRequests).toEqual([]);
    await user.type(search, 'RDERS');

    const action = await screen.findByRole('button', { name: /^ORDERS/ });
    await user.click(action);

    expect(subscriptionRequests.every((q) => q.includes('q=ORDERS'))).toBe(true);
    expect(navigate).toHaveBeenCalledWith({ to: '/clusters/c1/subscriptions', search: { subscription: 'ORDERS' } });
  });

  it('offers to search the live views without reading them', async () => {
    navigate.mockClear();
    mockApi();
    const user = userEvent.setup();
    renderWithProviders(<CommandPalette />);

    await user.keyboard('{Control>}k{/Control}');
    await user.type(await screen.findByPlaceholderText(/jump to a cluster/i), '10.4.2');
    await user.click(await screen.findByRole('button', { name: /Search connections for "10.4.2"/ }));
    expect(navigate).toHaveBeenCalledWith({ to: '/clusters/c1/connections', search: { q: '10.4.2' } });
  });

  it('lists a view the operator may not open as unavailable, with the reason', async () => {
    mockApi(['cluster:read']);
    const user = userEvent.setup();
    renderWithProviders(<CommandPalette />);

    await user.keyboard('{Control>}k{/Control}');
    await user.type(await screen.findByPlaceholderText(/jump to a cluster/i), 'Audit');
    await screen.findByRole('button', { name: /Audit/ });
    await user.clear(screen.getByPlaceholderText(/jump to a cluster/i));
    await user.type(screen.getByPlaceholderText(/jump to a cluster/i), 'Transfers');
    const transfers = await screen.findByRole('button', { name: /Transfers/ });
    expect(transfers).toBeDisabled();
    expect(transfers).toHaveTextContent(/needs the message:read permission/);
  });
});
