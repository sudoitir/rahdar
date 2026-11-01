import { describe, expect, it, vi } from 'vitest';
import { http, HttpResponse } from 'msw';
import { screen } from '@testing-library/react';

import { renderWithProviders } from '../../test/render.tsx';
import { server } from '../../test/setup.ts';
import { RABBITMQ_KINDS, RABBITMQ_SYSTEM } from '../../test/wire.ts';

let subscriptionName = 'rabbitmq.classic-queue/orders';

vi.mock('@tanstack/react-router', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@tanstack/react-router')>()),
  useParams: () => ({ clusterId: 'r1', subscriptionName }),
  useSearch: () => ({}),
  useNavigate: () => vi.fn(),
  Link: ({ children }: { children: React.ReactNode }) => <a>{children}</a>,
}));

const { MessagesView } = await import('./MessagesView.tsx');

const AVAILABLE = { status: 'AVAILABLE', reason: 'ok', brokerXmlSnippet: null };

/** A RabbitMQ cluster; every request to a queue's messages is recorded. */
function mockRabbit(findings: object[] = []) {
  const seen: URL[] = [];
  server.use(
    http.get('*/api/v1/auth/me', () =>
      HttpResponse.json({
        id: 'u1',
        username: 'op',
        mustChangePassword: false,
        grants: [{ scopeType: 'GLOBAL', scopeId: null, permissions: ['*'] }],
      }),
    ),
    http.get('*/api/v1/clusters/r1', () =>
      HttpResponse.json({
        id: 'r1',
        name: 'events',
        description: null,
        system: RABBITMQ_SYSTEM,
        topology: { clusterId: 'r1', nodes: [] },
        kinds: RABBITMQ_KINDS,
        capabilities: {
          managementRead: AVAILABLE,
          managementWrite: AVAILABLE,
          notifications: AVAILABLE,
          messageIo: AVAILABLE,
          findings,
        },
        health: {
          clusterId: 'r1',
          level: 'OK',
          liveEndpointNames: [],
          splitBrain: 'NONE',
          replicationBehind: false,
          notes: [],
        },
      }),
    ),
    http.get('*/api/v1/clusters/r1/resources/*/messages', ({ request }) => {
      seen.push(new URL(request.url));
      return HttpResponse.json({
        data: [
          {
            position: '0',
            type: 0,
            durable: true,
            priority: 0,
            timestamp: 1_790_000_000_000,
            expiration: 0,
            size: 12,
            bodyPreview: 'value-0',
            bodyTruncated: false,
            propertyCount: 0,
            redactions: [],
            tombstone: false,
          },
        ],
        count: 1,
        page: 1,
        pageSize: 200,
        hasNext: false,
        node: null,
        log: { partition: 0, start: 0, end: 1, next: null, retentionMoved: false, uncommittedHidden: false },
      });
    }),
  );
  return seen;
}

describe('MessagesView on a RabbitMQ cluster', () => {
  it('refuses to browse a classic queue with the driver reason and asks for no messages', async () => {
    subscriptionName = 'rabbitmq.classic-queue/orders';
    const seen = mockRabbit([
      {
        capability: 'messages.browse',
        status: 'UNAVAILABLE',
        reason: 'A classic queue cannot be read without consuming.',
      },
    ]);
    renderWithProviders(<MessagesView />);

    expect(await screen.findByText('These messages cannot be browsed')).toBeInTheDocument();
    expect(screen.getByText('A classic queue cannot be read without consuming.')).toBeInTheDocument();
    // Send and Purge stay.
    expect(screen.getByRole('button', { name: 'Send' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Purge subscription' })).toBeInTheDocument();
    expect(seen).toHaveLength(0);
  });

  it("falls back to the kind's refusal when the driver gave no reason", async () => {
    subscriptionName = 'rabbitmq.quorum-queue/orders';
    const seen = mockRabbit();
    renderWithProviders(<MessagesView />);

    expect(await screen.findByText('RabbitMQ quorum queues cannot be browsed.')).toBeInTheDocument();
    expect(seen).toHaveLength(0);
  });

  it('reads a stream as one log: no partition choice, headed by the kind', async () => {
    subscriptionName = 'rabbitmq.stream/orders';
    const seen = mockRabbit();
    renderWithProviders(<MessagesView />);

    expect(await screen.findByText('value-0')).toBeInTheDocument();
    expect(seen[0].searchParams.get('partition')).toBe('0');
    expect(screen.queryByRole('textbox', { name: 'Partition' })).not.toBeInTheDocument();
    expect(screen.queryByRole('combobox', { name: 'Partition' })).not.toBeInTheDocument();
    expect(screen.getByRole('combobox', { name: 'Start from' })).toBeInTheDocument();
    expect(screen.getByText('Stream')).toBeInTheDocument();
  });
});
