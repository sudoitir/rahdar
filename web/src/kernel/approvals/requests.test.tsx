import { describe, expect, it } from 'vitest';
import { http, HttpResponse } from 'msw';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import {
  createMemoryHistory,
  createRootRoute,
  createRoute,
  createRouter,
  Outlet,
  RouterProvider,
} from '@tanstack/react-router';

import { renderWithProviders } from '../../test/render.tsx';
import { server } from '../../test/setup.ts';
import type { HeldOperationSummary } from './api.ts';
import { ApprovalsView } from './ApprovalsView.tsx';
import { HELD_ID, detail, summary } from './fixtures.ts';
import { MyRequests } from './MyRequests.tsx';

/** Answers the list per scope; records what each request asked for. */
function serveLists(lists: Partial<Record<'MINE' | 'DECIDABLE', HeldOperationSummary[]>>, next: string | null = null) {
  const asked: URLSearchParams[] = [];
  server.use(
    http.get('*/api/v1/held-operations', ({ request }) => {
      const params = new URL(request.url).searchParams;
      asked.push(params);
      const scope = (params.get('scope') ?? 'MINE') as 'MINE' | 'DECIDABLE';
      return HttpResponse.json({ items: lists[scope] ?? [], next });
    }),
  );
  return asked;
}

function renderAt(path: string, View: () => React.ReactNode) {
  const root = createRootRoute({ component: Outlet });
  const routes = [
    createRoute({
      getParentRoute: () => root,
      path: 'approvals',
      component: View,
      validateSearch: (raw: Record<string, unknown>) => (raw.tab === 'mine' ? { tab: 'mine' } : {}),
    }),
    createRoute({ getParentRoute: () => root, path: 'approvals/$id', component: () => <p>Request page</p> }),
    createRoute({ getParentRoute: () => root, path: 'account', component: View }),
  ];
  const router = createRouter({
    routeTree: root.addChildren(routes),
    history: createMemoryHistory({ initialEntries: [path] }),
  });
  return { ...renderWithProviders(<RouterProvider router={router as never} />), router };
}

describe('MyRequests', () => {
  it('teaches what a request is when the user has none', async () => {
    serveLists({});
    renderAt('/account', MyRequests);
    expect(await screen.findByText('You have no approval requests')).toBeInTheDocument();
    expect(screen.getByText(/sent as a request and listed here/)).toBeInTheDocument();
  });

  it('lists the user’s requests with their state in words, linked to their pages', async () => {
    const asked = serveLists({
      MINE: [
        summary(),
        summary({ id: 'r2', state: 'REJECTED', summary: 'Delete address legacy.in on prod-eu' }),
        summary({ id: 'r3', state: 'FAILED', summary: 'Move messages from a to b' }),
      ],
    });
    renderAt('/account', MyRequests);
    const list = await screen.findByRole('list', { name: 'Your requests' });
    const items = within(list).getAllByRole('listitem');
    expect(items).toHaveLength(3);
    expect(within(items[0]!).getByText('Waiting for approval')).toBeInTheDocument();
    expect(within(items[1]!).getByText('Rejected')).toBeInTheDocument();
    expect(within(items[2]!).getByText('Failed')).toBeInTheDocument();
    expect(within(items[0]!).getByRole('link', { name: 'Purge subscription orders.dlq on prod-eu' })).toHaveAttribute(
      'href',
      `/approvals/${HELD_ID}`,
    );
    // Only a request that still waits can be cancelled.
    expect(within(items[0]!).getByRole('button', { name: /Cancel request/ })).toBeInTheDocument();
    expect(within(items[1]!).queryByRole('button')).not.toBeInTheDocument();
    expect(asked[0]?.get('scope')).toBe('MINE');
  });

  it('cancels a waiting request after confirming it', async () => {
    let cancelled = 0;
    serveLists({ MINE: [summary()] });
    server.use(
      http.post(`*/api/v1/held-operations/${HELD_ID}/cancel`, () => {
        cancelled += 1;
        return HttpResponse.json(detail({ mine: true }, { state: 'CANCELLED' }));
      }),
    );
    renderAt('/account', MyRequests);
    const user = userEvent.setup();
    await user.click(
      await screen.findByRole('button', { name: 'Cancel request "Purge subscription orders.dlq on prod-eu"' }),
    );
    const dialog = await screen.findByRole('dialog', { name: 'Cancel this request?' });
    expect(dialog).toHaveTextContent('Purge subscription orders.dlq on prod-eu');
    await user.click(within(dialog).getByRole('button', { name: 'Cancel request' }));
    await waitFor(() => expect(cancelled).toBe(1));
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
  });

  it('shows a failed read as an error, not as no requests', async () => {
    server.use(
      http.get('*/api/v1/held-operations', () =>
        HttpResponse.json({ type: 'about:blank', title: 'Boom', status: 500 }, { status: 500 }),
      ),
    );
    renderAt('/account', MyRequests);
    expect(await screen.findByRole('alert')).toBeInTheDocument();
    expect(screen.queryByText('You have no approval requests')).not.toBeInTheDocument();
  });
});

describe('ApprovalsView', () => {
  it('opens on the requests waiting for the user, asking only for those still held', async () => {
    const asked = serveLists({ DECIDABLE: [summary({ requesterUsername: 'carol' })] });
    renderAt('/approvals', ApprovalsView);
    expect(await screen.findByRole('heading', { level: 1, name: 'Approval requests' })).toBeInTheDocument();
    expect(screen.getByRole('tab', { name: 'Waiting for me' })).toHaveAttribute('aria-selected', 'true');
    const table = await screen.findByRole('table', { name: 'Requests waiting for your decision' });
    expect(within(table).getByRole('link', { name: 'Purge subscription orders.dlq on prod-eu' })).toBeInTheDocument();
    expect(within(table).getByText('carol')).toBeInTheDocument();
    expect(within(table).getByRole('columnheader', { name: /Requested by/ })).toBeInTheDocument();
    expect(asked[0]?.get('scope')).toBe('DECIDABLE');
    expect(asked[0]?.get('state')).toBe('HELD');
  });

  it('keeps the open tab in the URL', async () => {
    serveLists({ MINE: [summary({ state: 'SUCCEEDED' })] });
    const { router } = renderAt('/approvals', ApprovalsView);
    const user = userEvent.setup();
    await user.click(await screen.findByRole('tab', { name: 'Mine' }));
    await waitFor(() => expect(router.state.location.search).toEqual({ tab: 'mine' }));
    const table = await screen.findByRole('table', { name: 'Your requests' });
    expect(within(table).getByText('Succeeded')).toBeInTheDocument();
    // Every row of the user's own requests was asked by them: the column would only repeat their name.
    expect(within(table).queryByRole('columnheader', { name: /Requested by/ })).not.toBeInTheDocument();
  });

  it('says nothing waits for the user when nothing does', async () => {
    serveLists({});
    renderAt('/approvals', ApprovalsView);
    expect(await screen.findByText('Nothing waits for your decision')).toBeInTheDocument();
  });

  it('loads the next page by keyset', async () => {
    const asked = serveLists({ DECIDABLE: [summary()] }, 'cursor-1');
    renderAt('/approvals', ApprovalsView);
    const user = userEvent.setup();
    await user.click(await screen.findByRole('button', { name: 'Load more' }));
    await waitFor(() => expect(asked.at(-1)?.get('before')).toBe('cursor-1'));
  });
});
