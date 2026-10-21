import type { ReactNode } from 'react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { http, HttpResponse } from 'msw';
import { screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

import { renderWithProviders } from '../../test/render.tsx';
import { manifestHandler } from '../../test/manifest.ts';
import { server } from '../../test/setup.ts';
import { paged } from '../api/paging.ts';

const navigate = vi.fn();
// Where the mocked router says the address is; a cluster's view unless a test moves it.
const place = vi.hoisted(() => ({ pathname: '/clusters/c1/subscriptions', clusterId: 'c1' as string | undefined }));

vi.mock('@tanstack/react-router', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@tanstack/react-router')>()),
  useParams: () => (place.clusterId ? { clusterId: place.clusterId } : {}),
  useNavigate: () => navigate,
  useLocation: () => ({ pathname: place.pathname, search: {} }),
  Outlet: () => null,
  Link: ({
    to,
    children,
    className,
    'aria-label': ariaLabel,
    'aria-current': ariaCurrent,
  }: {
    to: string;
    children?: ReactNode;
    className?: string;
    'aria-label'?: string;
    'aria-current'?: 'page';
  }) => (
    <a href={to} className={className} aria-label={ariaLabel} aria-current={ariaCurrent}>
      {children}
    </a>
  ),
}));

const { RootLayout } = await import('./RootLayout.tsx');

// CommandPalette (mounted by RootLayout) always queries a subscriptions endpoint,
// even with no active cluster — a pre-existing behavior, not introduced here.
function mockEmptySubscriptions() {
  server.use(
    http.get(/\/api\/v1\/clusters\/.*\/subscriptions/, () =>
      HttpResponse.json({ data: [], count: 0, page: 1, pageSize: 50 }),
    ),
    http.get('*/api/v1/alerts/firing', () => HttpResponse.json(paged([]))),
    // The cluster switcher groups clusters by environment (authorization spec).
    http.get('*/api/v1/environments', () => HttpResponse.json(paged([]))),
    // The command palette lists the views of the installation's enabled features.
    manifestHandler(),
  );
}

// RootLayout gates its shell behind `/auth/me` (identity-and-sessions spec).
function mockAuthenticated() {
  server.use(
    http.get('*/api/v1/auth/me', () =>
      HttpResponse.json({
        id: 'u1',
        username: 'test-user',
        mustChangePassword: false,
        grants: [{ scopeType: 'GLOBAL', scopeId: null, permissions: ['*'] }],
      }),
    ),
  );
}

describe('RootLayout sidebar collapse', () => {
  afterEach(() => navigate.mockClear());

  it('persists the collapse toggle to localStorage and restores it on remount without a flash', async () => {
    mockAuthenticated();
    mockEmptySubscriptions();
    server.use(http.get('*/api/v1/clusters', () => HttpResponse.json(paged([]))));
    const user = userEvent.setup();
    localStorage.removeItem('as:nav:collapsed');

    const { unmount } = renderWithProviders(<RootLayout />);
    const toggle = await screen.findByRole('button', { name: 'Collapse sidebar' });
    await user.click(toggle);

    expect(localStorage.getItem('as:nav:collapsed')).toBe('true');
    unmount();

    renderWithProviders(<RootLayout />);
    expect(await screen.findByRole('button', { name: 'Expand sidebar' })).toBeInTheDocument();
  });

  it('keeps the collapsed cluster switcher reachable by name for a screen reader', async () => {
    mockAuthenticated();
    mockEmptySubscriptions();
    server.use(
      http.get('*/api/v1/clusters', () =>
        HttpResponse.json(paged([{ id: 'c1', name: 'prod-emea', health: 'OK', nodeCount: 3 }])),
      ),
    );
    localStorage.setItem('as:nav:collapsed', 'true');
    renderWithProviders(<RootLayout />);

    // The rail's one control names the cluster the address is under.
    expect(await screen.findByRole('button', { name: /^PR, Switch cluster, now prod-emea/ })).toBeInTheDocument();
  });
});

describe('RootLayout in a narrow window', () => {
  const originalMatchMedia = window.matchMedia;
  afterEach(() => {
    window.matchMedia = originalMatchMedia;
    navigate.mockClear();
  });

  /** A window narrower than 64rem, whatever its size in CSS pixels: the threshold follows zoom. */
  function narrowWindow() {
    window.matchMedia = ((query: string) => ({
      ...originalMatchMedia(query),
      matches: query === '(width < 64rem)',
    })) as typeof window.matchMedia;
  }

  it('shows the icon rail whatever was chosen, and says why the toggle does nothing', async () => {
    mockAuthenticated();
    mockEmptySubscriptions();
    server.use(
      http.get('*/api/v1/clusters', () =>
        HttpResponse.json(paged([{ id: 'c1', name: 'prod-emea', health: 'OK', nodeCount: 3 }])),
      ),
    );
    localStorage.setItem('as:nav:collapsed', 'false');
    narrowWindow();
    const user = userEvent.setup();
    renderWithProviders(<RootLayout />);

    // Not `disabled`: it keeps its focus, so the reason is reachable by keyboard.
    const toggle = await screen.findByRole('button', { name: 'Sidebar stays collapsed in a narrow window' });
    expect(toggle).toHaveAttribute('aria-disabled', 'true');
    expect(toggle).toBeEnabled();
    expect(toggle).toHaveAttribute('aria-expanded', 'false');
    await user.click(toggle);
    expect(toggle).toHaveAttribute('aria-expanded', 'false');
    expect(await screen.findByRole('button', { name: /^PR, Switch cluster, now prod-emea/ })).toBeInTheDocument();
    // The viewer's own choice is kept for when the window is wide again.
    expect(localStorage.getItem('as:nav:collapsed')).toBe('false');
  });
});

describe('RootLayout user menu', () => {
  afterEach(() => navigate.mockClear());

  it('shows the username, an Administration link for an admin, and logs out', async () => {
    mockAuthenticated();
    mockEmptySubscriptions();
    server.use(
      http.get('*/api/v1/clusters', () => HttpResponse.json(paged([]))),
      http.post('*/api/v1/auth/logout', () => new HttpResponse(null, { status: 204 })),
    );
    const user = userEvent.setup();
    renderWithProviders(<RootLayout />);

    await user.click(await screen.findByRole('button', { name: 'User menu' }));
    expect(await screen.findByText('test-user')).toBeInTheDocument();
    expect(await screen.findByText('Administration')).toBeInTheDocument();

    await user.click(await screen.findByText('Log out'));

    await vi.waitFor(() => expect(navigate).toHaveBeenCalledWith({ to: '/login' }));
  });

  it('hides the Administration entry for a non-admin user', async () => {
    server.use(
      http.get('*/api/v1/auth/me', () =>
        HttpResponse.json({
          id: 'u2',
          username: 'viewer',
          mustChangePassword: false,
          grants: [{ scopeType: 'GLOBAL', scopeId: null, permissions: ['cluster:read'] }],
        }),
      ),
    );
    mockEmptySubscriptions();
    server.use(http.get('*/api/v1/clusters', () => HttpResponse.json(paged([]))));
    const user = userEvent.setup();
    renderWithProviders(<RootLayout />);

    await user.click(await screen.findByRole('button', { name: 'User menu' }));
    expect(await screen.findByText('viewer')).toBeInTheDocument();
    expect(screen.queryByText('Administration')).not.toBeInTheDocument();
  });
});

describe('RootLayout second-factor enrolment', () => {
  afterEach(() => navigate.mockClear());

  const meWith = (over: object) =>
    server.use(
      http.get('*/api/v1/auth/me', () =>
        HttpResponse.json({
          id: 'u1',
          username: 'admin',
          mustChangePassword: false,
          secondFactorEnrolmentRequired: false,
          grants: [],
          ...over,
        }),
      ),
    );

  it('sends a session whose role requires a second factor to enrol one, and shows no shell', async () => {
    meWith({ secondFactorEnrolmentRequired: true });
    mockEmptySubscriptions();
    renderWithProviders(<RootLayout />);

    await vi.waitFor(() => expect(navigate).toHaveBeenCalledWith({ to: '/enrol-second-factor' }));
    expect(screen.queryByRole('button', { name: 'User menu' })).not.toBeInTheDocument();
  });

  it('asks for the new password first when both are due', async () => {
    meWith({ secondFactorEnrolmentRequired: true, mustChangePassword: true });
    mockEmptySubscriptions();
    renderWithProviders(<RootLayout />);

    await vi.waitFor(() => expect(navigate).toHaveBeenCalledWith({ to: '/change-password' }));
    expect(navigate).not.toHaveBeenCalledWith({ to: '/enrol-second-factor' });
  });
});

describe('RootLayout break-glass banner', () => {
  it('says in words, on every page, that approval checks are bypassed while break-glass is on', async () => {
    mockAuthenticated();
    mockEmptySubscriptions();
    server.use(
      http.get('*/api/v1/clusters', () => HttpResponse.json(paged([]))),
      http.get('*/api/v1/gate/status', () =>
        HttpResponse.json({ armed: true, providerId: 'acme', attached: true, breakGlass: true }),
      ),
    );
    renderWithProviders(<RootLayout />);

    const banner = await screen.findByRole('alert');
    expect(banner).toHaveTextContent('Break-glass is on');
    expect(banner).toHaveTextContent(
      "Approval checks are bypassed by the deployment's break-glass setting. Every bypassed operation is audited.",
    );
    expect(screen.queryByRole('button', { name: /dismiss|close/i })).not.toBeInTheDocument();
  });

  it('shows no banner while break-glass is off', async () => {
    mockAuthenticated();
    mockEmptySubscriptions();
    server.use(http.get('*/api/v1/clusters', () => HttpResponse.json(paged([]))));
    renderWithProviders(<RootLayout />);

    expect(await screen.findByRole('button', { name: 'User menu' })).toBeInTheDocument();
    expect(screen.queryByText('Break-glass is on')).not.toBeInTheDocument();
  });
});

describe('RootLayout outside any cluster', () => {
  afterEach(() => {
    place.pathname = '/clusters/c1/subscriptions';
    place.clusterId = 'c1';
    localStorage.removeItem('as:last-place');
  });

  function outside(pathname: string) {
    place.pathname = pathname;
    place.clusterId = undefined;
    mockAuthenticated();
    mockEmptySubscriptions();
    server.use(http.get('*/api/v1/clusters', () => HttpResponse.json(paged([]))));
  }

  it('has no sidebar, and offers to open a cluster when there is no place to go back to', async () => {
    outside('/inbox');
    renderWithProviders(<RootLayout />);

    const where = await screen.findByRole('navigation', { name: 'Where you are' });
    expect(screen.queryByRole('button', { name: 'Collapse sidebar' })).not.toBeInTheDocument();
    expect(screen.queryByRole('navigation', { name: 'Cluster views' })).not.toBeInTheDocument();
    expect(within(where).getByRole('link', { name: 'Open a cluster' })).toHaveAttribute('href', '/');
    expect(within(where).getByText('Inbox')).toHaveAttribute('aria-current', 'page');
  });

  it('leads straight back to the cluster view the operator came from', async () => {
    localStorage.setItem(
      'as:last-place',
      JSON.stringify({
        clusterId: 'c1',
        clusterName: 'prod-emea',
        label: 'Subscriptions',
        to: '/clusters/c1/subscriptions',
        search: {},
      }),
    );
    outside('/admin');
    renderWithProviders(<RootLayout />);

    const back = await screen.findByRole('link', { name: 'Back to prod-emea · Subscriptions' });
    expect(back).toHaveAttribute('href', '/clusters/c1/subscriptions');
  });

  it('keeps the sidebar on a cluster page, with no line above it', async () => {
    mockAuthenticated();
    mockEmptySubscriptions();
    server.use(http.get('*/api/v1/clusters', () => HttpResponse.json(paged([]))));
    renderWithProviders(<RootLayout />);

    expect(await screen.findByRole('button', { name: 'Collapse sidebar' })).toBeInTheDocument();
    expect(screen.queryByRole('navigation', { name: 'Where you are' })).not.toBeInTheDocument();
  });
});
