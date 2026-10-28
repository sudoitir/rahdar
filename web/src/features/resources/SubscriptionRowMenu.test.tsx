import { ARTEMIS_KINDS, subscriptionIdentity } from '../../test/wire.ts';
import { describe, expect, it, vi } from 'vitest';
import { http, HttpResponse } from 'msw';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

import { renderWithProviders } from '../../test/render.tsx';
import { server } from '../../test/setup.ts';

let search: Record<string, unknown> = {};
vi.mock('@tanstack/react-router', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@tanstack/react-router')>()),
  useParams: () => ({ clusterId: 'c1' }),
  useSearch: () => search,
  useNavigate: () => () => {},
  // The drawer's "Browse messages" and the subscription link need no router to be asserted here.
  Link: ({
    children,
    to,
    search,
    'aria-label': label,
  }: {
    children: React.ReactNode;
    to?: string;
    search?: Record<string, string>;
    'aria-label'?: string;
  }) => (
    <a href={`${to ?? ''}?${new URLSearchParams(search ?? {})}`} aria-label={label}>
      {children}
    </a>
  ),
}));

const { SubscriptionsView } = await import('./SubscriptionsView.tsx');

const AVAILABLE = { status: 'AVAILABLE', reason: 'ok', brokerXmlSnippet: null };

function subscription(name: string, over: Record<string, unknown> = {}) {
  return {
    ...subscriptionIdentity(name, name),
    allowedActions: [
      'subscription:read',
      'subscription:pause',
      'subscription:update',
      'subscription:delete',
      'subscription:purge',
      'message:read',
      'message:move',
    ],
    durable: true,
    backlog: { value: 12, accuracy: 'EXACT', reason: null, breakdown: false },
    totalConsumerCount: 0,
    totalDeliveringCount: 0,
    totalScheduledCount: 0,
    nodesPresent: 1,
    nodesTotal: 1,
    paused: false,
    perNode: [
      {
        nodeId: 'n1',
        nodeName: 'node-a',
        stale: false,
        lastSeenAt: null,
        messageCount: 12,
        consumerCount: 0,
        deliveringCount: 0,
        scheduledCount: 0,
        paused: false,
      },
    ],
    ...over,
  };
}

function handlers(permissions: string[], rows = [subscription('orders'), subscription('payments')]) {
  return [
    http.get('*/api/v1/auth/me', () =>
      HttpResponse.json({
        id: 'u1',
        username: 'op',
        mustChangePassword: false,
        grants: [{ scopeType: 'GLOBAL', scopeId: null, permissions }],
      }),
    ),
    http.get('*/api/v1/clusters/c1', () =>
      HttpResponse.json({
        id: 'c1',
        name: 'c1',
        topology: { nodes: [] },
        kinds: ARTEMIS_KINDS,
        capabilities: {
          managementRead: AVAILABLE,
          managementWrite: AVAILABLE,
          notifications: AVAILABLE,
          messageIo: AVAILABLE,
          slowConsumerDetection: AVAILABLE,
          versionGates: [],
        },
      }),
    ),
    http.get('*/api/v1/clusters/c1/subscriptions', ({ request }) => {
      const q = new URL(request.url).searchParams.get('q');
      const data = q ? rows.filter((r) => r.name.includes(q)) : rows;
      return HttpResponse.json({ data, count: data.length, page: 1, pageSize: 200 });
    }),
  ];
}

describe('the subscription row menu (ADR-0107)', () => {
  it('deletes a subscription from the keyboard alone, and hands focus back to the row', async () => {
    search = {};
    const urls: string[] = [];
    server.use(
      ...handlers(['*']),
      http.delete('*/api/v1/clusters/c1/resources/artemis.queue/orders', ({ request }) => {
        const url = new URL(request.url);
        urls.push(url.search);
        const dryRun = url.searchParams.get('dryRun') === 'true';
        return HttpResponse.json({
          dryRun,
          cap: 1000,
          overCap: false,
          partial: false,
          totalAffected: 12,
          nodes: [
            { nodeId: 'n1', nodeName: 'node-a', status: dryRun ? 'WOULD_APPLY' : 'APPLIED', affected: 12, error: null },
          ],
        });
      }),
    );
    const user = userEvent.setup();
    renderWithProviders(<SubscriptionsView />);
    await screen.findByRole('button', { name: 'Actions for orders' });

    // Into the grid (one tab stop), onto the first subscription, and open its menu.
    const grid = screen.getByRole('grid', { name: 'Subscriptions' });
    within(grid).getAllByText('orders')[0].closest<HTMLElement>('[role="rowheader"]')!.focus();
    await user.keyboard('{Shift>}{F10}{/Shift}');
    const menu = await screen.findByRole('menu', { name: 'Actions for orders' });
    expect(within(menu).getByText('Destroy')).toBeInTheDocument();

    const del = within(menu).getByRole('menuitem', { name: /Delete subscription/ });
    // Type-ahead to the item, as a keyboard user would.
    await waitFor(() => expect(within(menu).getByRole('menuitem', { name: /Open details/ })).toHaveFocus());
    while (document.activeElement !== del) await user.keyboard('{ArrowDown}');
    await user.keyboard('{Enter}');

    // The dialog opens with its preview already taken — the two-phase mount keeps that working.
    const dialog = await screen.findByRole('dialog', { name: 'Delete orders' });
    await waitFor(() => expect(urls.some((u) => u.includes('dryRun=true'))).toBe(true));
    await waitFor(() => expect(dialog).toContainElement(document.activeElement as HTMLElement));

    await user.keyboard('{Escape}');
    await waitFor(() => expect(screen.queryByRole('dialog', { name: 'Delete orders' })).not.toBeInTheDocument());
    await waitFor(() => expect(screen.getByRole('button', { name: 'Actions for orders' })).toHaveFocus());
    expect(urls.every((u) => u.includes('dryRun=true'))).toBe(true);
  });

  it('keeps a blocked action reachable, and explains it in full', async () => {
    search = {};
    // Delete is held through a team, somewhere on the cluster, and not on payments, which Payments owns.
    server.use(
      ...handlers(
        ['cluster:read'],
        [
          subscription('orders'),
          subscription('payments', {
            allowedActions: ['subscription:read'],
            ownerTeam: { id: 't9', name: 'Payments' },
          }),
        ],
      ),
      http.get('*/api/v1/me/access', ({ request }) => {
        const clusterId = new URL(request.url).searchParams.get('clusterId');
        return HttpResponse.json({
          permissions: ['cluster:read'],
          anywhere: ['subscription:delete', 'subscription:pause', 'subscription:update'],
          canSeeCluster: clusterId ? true : null,
          teams: [],
          createPatterns: { subscription: [], destination: [] },
        });
      }),
    );
    const user = userEvent.setup();
    renderWithProviders(<SubscriptionsView />);

    await user.click(await screen.findByRole('button', { name: 'Actions for payments' }));
    const menu = await screen.findByRole('menu', { name: 'Actions for payments' });
    const del = await within(menu).findByRole('menuitem', { name: /Delete subscription/ });
    await waitFor(() => expect(del).toHaveAttribute('aria-disabled', 'true'));
    expect(del).toHaveAccessibleDescription(
      /do not have the "Destroy subscriptions and destinations" permission \(subscription:delete\)/,
    );

    // Reachable with the arrow keys — a disabled item would be skipped.
    while (document.activeElement !== del) await user.keyboard('{ArrowDown}');
    await user.keyboard('{Enter}');
    const why = await screen.findByRole('dialog', { name: 'Why deleting this subscription is unavailable' });
    expect(within(why).getByText(/Ask an admin of team Payments/)).toBeInTheDocument();
  });

  it('leaves out an action the caller cannot take anywhere on the cluster', async () => {
    search = {};
    server.use(...handlers(['cluster:read', 'subscription:pause']));
    const user = userEvent.setup();
    renderWithProviders(<SubscriptionsView />);

    await user.click(await screen.findByRole('button', { name: 'Actions for payments' }));
    const menu = await screen.findByRole('menu', { name: 'Actions for payments' });
    await within(menu).findByRole('menuitem', { name: /Open details/ });
    await waitFor(() =>
      expect(within(menu).queryByRole('menuitem', { name: /Delete subscription/ })).not.toBeInTheDocument(),
    );
  });

  it('opens a linked subscription that is not on the loaded page', async () => {
    search = { subscription: 'archive.2019' };
    server.use(...handlers(['*'], [subscription('orders'), subscription('archive.2019')]));
    // The listing's first page does not hold it; the lookup by name does.
    server.use(
      http.get('*/api/v1/clusters/c1/subscriptions', ({ request }) => {
        const q = new URL(request.url).searchParams.get('q');
        const data = q === 'archive.2019' ? [subscription('archive.2019')] : [subscription('orders')];
        return HttpResponse.json({ data, count: q ? 1 : 2, page: 1, pageSize: 200 });
      }),
    );
    renderWithProviders(<SubscriptionsView />);
    expect(await screen.findByRole('dialog', { name: /archive\.2019/ })).toBeInTheDocument();
  });
});
