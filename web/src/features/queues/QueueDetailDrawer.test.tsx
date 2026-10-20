import { describe, expect, it, vi } from 'vitest';
import { http, HttpResponse } from 'msw';
import { screen, within } from '@testing-library/react';

import { renderWithProviders } from '../../test/render.tsx';
import { server } from '../../test/setup.ts';
import type { QueueView } from './api.ts';

vi.mock('@tanstack/react-router', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@tanstack/react-router')>()),
  useParams: () => ({ clusterId: 'c1' }),
  Link: ({
    children,
    to,
    search,
    'aria-label': label,
  }: {
    children: React.ReactNode;
    to: string;
    search?: Record<string, string>;
    'aria-label'?: string;
  }) => (
    <a href={`${to}?${new URLSearchParams(search ?? {})}`} aria-label={label}>
      {children}
    </a>
  ),
}));

const { QueueDetailDrawer } = await import('./QueueDetailDrawer.tsx');

function cell(nodeId: string, nodeName: string, messageCount: number, stale = false) {
  return {
    nodeId,
    nodeName,
    messageCount,
    consumerCount: 2,
    deliveringCount: 1,
    scheduledCount: 0,
    paused: false,
    stale,
  };
}

const QUEUE = {
  address: 'orders',
  queueName: 'orders.in',
  allowedActions: [
    'subscription:read',
    'subscription:pause',
    'subscription:update',
    'subscription:delete',
    'subscription:purge',
    'message:read',
    'message:move',
  ],
  routingType: 'ANYCAST',
  durable: true,
  totalMessageCount: 42,
  totalConsumerCount: 4,
  totalDeliveringCount: 2,
  totalScheduledCount: 0,
  nodesPresent: 2,
  nodesTotal: 3,
  paused: false,
  perNode: [cell('a', 'node-a', 40), cell('b', 'node-b', 2, true)],
} as unknown as QueueView;

function serve() {
  server.use(
    http.get('*/api/v1/auth/me', () =>
      HttpResponse.json({
        id: 'u1',
        username: 'admin',
        mustChangePassword: false,
        grants: [{ scopeType: 'GLOBAL', scopeId: null, permissions: ['*'] }],
      }),
    ),
    http.get('*/api/v1/clusters/c1', () =>
      HttpResponse.json({ id: 'c1', name: 'c1', topology: { nodes: [] }, capabilities: {} }),
    ),
  );
}

describe('QueueDetailDrawer', () => {
  it('lists each node in a table of its own, with stale figures named in words', async () => {
    serve();
    renderWithProviders(<QueueDetailDrawer queue={QUEUE} onClose={() => {}} />);

    const table = await screen.findByRole('table', { name: 'orders.in per node' });
    const rows = within(table).getAllByRole('row');
    // The header, then one row per node.
    expect(rows).toHaveLength(3);
    expect(within(rows[1]!).getByText('node-a')).toBeInTheDocument();
    expect(within(rows[1]!).getByText('40')).toBeInTheDocument();
    expect(within(rows[2]!).getByText('stale')).toBeInTheDocument();
    expect(within(rows[1]!).queryByText('stale')).not.toBeInTheDocument();
  });

  it('heads the breakdown as a section under the drawer title, and states the queue as words', async () => {
    serve();
    renderWithProviders(<QueueDetailDrawer queue={QUEUE} onClose={() => {}} />);

    expect(await screen.findByRole('heading', { level: 3, name: 'Per node' })).toBeInTheDocument();
    expect(screen.getByText('anycast')).toBeInTheDocument();
    expect(screen.getByText('durable')).toBeInTheDocument();
    expect(screen.getByText('2/3 nodes')).toBeInTheDocument();
    expect(screen.queryByRole('heading', { level: 1 })).not.toBeInTheDocument();
  });

  it('names the team that owns the queue, as a link to it', async () => {
    serve();
    renderWithProviders(
      <QueueDetailDrawer queue={{ ...QUEUE, ownerTeam: { id: 't1', name: 'Orders' } }} onClose={() => {}} />,
    );

    expect(await screen.findByRole('link', { name: 'Owner: team Orders' })).toHaveAttribute(
      'href',
      expect.stringContaining('team=t1'),
    );
  });

  it('says the queue is not found, instead of closing silently, when it can no longer be seen', async () => {
    serve();
    renderWithProviders(<QueueDetailDrawer queue={null} missing="orders.in" onClose={() => {}} />);

    expect(await screen.findByText('Queue not found')).toBeInTheDocument();
    expect(screen.getByText(/your access to it may have been removed/)).toBeInTheDocument();
  });
});
