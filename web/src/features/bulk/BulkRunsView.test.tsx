import { describe, expect, it, vi } from 'vitest';
import { screen, within } from '@testing-library/react';
import { http, HttpResponse } from 'msw';

import { renderWithProviders } from '../../test/render.tsx';
import { server } from '../../test/setup.ts';
import type { BulkRunView } from './api.ts';
import { paged } from '../../kernel/api/paging.ts';

vi.mock('@tanstack/react-router', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@tanstack/react-router')>()),
  useParams: () => ({ clusterId: 'c1' }),
  Link: ({ children, to }: { children: React.ReactNode; to: string }) => <a href={to}>{children}</a>,
}));

const { BulkRunsView } = await import('./BulkRunsView.tsx');

function run(over: Partial<BulkRunView>): BulkRunView {
  return {
    id: 'r1',
    clusterId: 'c1',
    operation: 'DELETE',
    status: 'SUCCEEDED',
    username: 'admin',
    createdAt: '2026-09-21T10:00:00Z',
    expiresAt: '2026-09-21T10:10:00Z',
    startedAt: '2026-09-21T10:01:00Z',
    finishedAt: '2026-09-21T10:02:00Z',
    total: 3,
    succeeded: 3,
    failed: 0,
    skipped: 0,
    estimate: 40,
    estimateComplete: true,
    cap: 1000,
    overCap: false,
    overrideCap: false,
    continueOnFailure: false,
    disconnectConsumers: false,
    selection: { ids: ['artemis.queue/orders.a'], q: null },
    planHash: 'h1',
    auditEventId: 7,
    error: null,
    ...over,
  };
}

describe('BulkRunsView', () => {
  it('lists each run with its operation, size, author and outcome, linked to the run', async () => {
    server.use(
      http.get('*/api/v1/clusters/c1/bulk/runs', () =>
        HttpResponse.json(
          paged([
            run({ id: 'r1', operation: 'DELETE', status: 'SUCCEEDED', total: 3, username: 'admin' }),
            run({ id: 'r2', operation: 'PAUSE', status: 'PREVIEWED', total: 12, username: 'ops', startedAt: null }),
          ]),
        ),
      ),
    );
    renderWithProviders(<BulkRunsView />);

    const table = await screen.findByRole('grid', { name: 'Bulk runs' });
    const links = await within(table).findAllByRole('link');
    expect(links.map((l) => l.getAttribute('href'))).toEqual(['/clusters/c1/bulk/r1', '/clusters/c1/bulk/r2']);
    expect(within(table).getByText('Delete')).toBeInTheDocument();
    expect(within(table).getByText('Pause')).toBeInTheDocument();
    expect(within(table).getByText('ops')).toBeInTheDocument();
    expect(within(table).getByText('Succeeded')).toBeInTheDocument();
    expect(within(table).getByText('Previewed, never started')).toBeInTheDocument();
  });

  it('is one page with a single h1, present before the runs arrive', async () => {
    server.use(http.get('*/api/v1/clusters/c1/bulk/runs', () => HttpResponse.json(paged([run({})]))));
    renderWithProviders(<BulkRunsView />);

    expect(screen.getByRole('heading', { level: 1, name: 'Bulk runs' })).toBeInTheDocument();
    await screen.findByRole('grid', { name: 'Bulk runs' });
    expect(screen.getAllByRole('heading', { level: 1 })).toHaveLength(1);
  });

  it('teaches what a bulk run is when there are none, and links to Subscriptions', async () => {
    server.use(http.get('*/api/v1/clusters/c1/bulk/runs', () => HttpResponse.json(paged([]))));
    renderWithProviders(<BulkRunsView />);

    expect(await screen.findByText('No bulk runs yet')).toBeInTheDocument();
    expect(screen.getByText(/pauses, resumes, purges or deletes many subscriptions at once/)).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Go to Subscriptions' })).toHaveAttribute(
      'href',
      '/clusters/c1/subscriptions',
    );
    // The empty state is beside the grid, never in it: the grid has only its header row.
    expect(screen.getByRole('grid', { name: 'Bulk runs' })).toHaveAttribute('aria-rowcount', '1');
  });

  it('states why the runs could not be loaded instead of showing an empty table', async () => {
    server.use(
      http.get('*/api/v1/clusters/c1/bulk/runs', () =>
        HttpResponse.json({ title: 'Runs unavailable', detail: 'The cluster did not answer.' }, { status: 503 }),
      ),
    );
    renderWithProviders(<BulkRunsView />);

    expect(await screen.findByRole('alert')).toHaveTextContent('Rahdar failed to complete the request');
    expect(screen.getByRole('button', { name: 'Retry' })).toBeInTheDocument();
    expect(screen.queryByText('No bulk runs yet')).not.toBeInTheDocument();
  });
});
