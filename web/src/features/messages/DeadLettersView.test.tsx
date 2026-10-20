import { ARTEMIS_KINDS, destinationIdentity, subscriptionIdentity } from '../../test/wire.ts';
import { describe, expect, it, vi } from 'vitest';
import { http, HttpResponse } from 'msw';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

import { renderWithProviders } from '../../test/render.tsx';
import { server } from '../../test/setup.ts';

vi.mock('@tanstack/react-router', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@tanstack/react-router')>()),
  useParams: () => ({ clusterId: 'c1' }),
  useNavigate: () => () => {},
  Link: ({ children, to }: { children: React.ReactNode; to: string }) => <a href={to}>{children}</a>,
}));

const { DeadLettersView } = await import('./DeadLettersView.tsx');

const AVAILABLE = { status: 'AVAILABLE', reason: 'ok', brokerXmlSnippet: null };

function signedInWith(permissions: string[]) {
  server.use(
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
        name: 'prod',
        description: null,
        topology: { clusterId: 'c1', nodes: [] },
        kinds: ARTEMIS_KINDS,
        capabilities: {
          managementRead: AVAILABLE,
          managementWrite: AVAILABLE,
          notifications: AVAILABLE,
          messageIo: AVAILABLE,
        },
        health: {
          clusterId: 'c1',
          level: 'OK',
          liveEndpointNames: [],
          splitBrain: 'NONE',
          replicationBehind: false,
          notes: [],
        },
      }),
    ),
  );
}

function dlq(body: Record<string, unknown>) {
  server.use(http.get('*/api/v1/clusters/c1/dead-letters', () => HttpResponse.json(body)));
}

const QUEUE = {
  ...subscriptionIdentity('DLQ.orders', 'DLQ'),
  totalDepth: 7,
  perNode: [
    { nodeId: 'n1', nodeName: 'primary', depth: 4 },
    { nodeId: 'n2', nodeName: 'backup', depth: 3 },
  ],
};

describe('DeadLettersView', () => {
  it('is one page named Dead-letter subscriptions, listing each subscription with its depth as a link to its messages', async () => {
    signedInWith(['*']);
    dlq({
      settingsAvailable: true,
      destinations: [{ ...destinationIdentity('DLQ'), purpose: 'dead-letter', subscriptions: [QUEUE] }],
    });
    renderWithProviders(<DeadLettersView />);

    expect(await screen.findByRole('heading', { level: 1, name: 'Dead-letter subscriptions' })).toBeInTheDocument();
    expect(screen.getAllByRole('heading', { level: 1 })).toHaveLength(1);
    const grid = await screen.findByRole('grid', { name: 'Dead-letter subscriptions' });
    expect(await within(grid).findByRole('link', { name: 'DLQ.orders' })).toHaveAttribute(
      'href',
      '/clusters/c1/subscriptions/DLQ.orders/messages',
    );
    expect(within(grid).getByRole('gridcell', { name: '7' })).toBeInTheDocument();
  });

  it('opens the per-node depth as terms and values from the row menu', async () => {
    signedInWith(['*']);
    dlq({
      settingsAvailable: true,
      destinations: [{ ...destinationIdentity('DLQ'), purpose: 'dead-letter', subscriptions: [QUEUE] }],
    });
    const user = userEvent.setup();
    renderWithProviders(<DeadLettersView />);

    await user.click(await screen.findByRole('button', { name: /^Actions for dead-letter subscription DLQ.orders/ }));
    await user.click(await screen.findByRole('menuitem', { name: 'Per-node breakdown' }));

    const dialog = await screen.findByRole('dialog', { name: 'DLQ.orders by node' });
    const list = within(dialog).getByRole('group', { name: 'Messages on each node' });
    expect(within(list).getByText('primary')).toBeInTheDocument();
    expect(within(list).getByText('4 messages')).toBeInTheDocument();
    expect(within(list).getByText('3 messages')).toBeInTheDocument();
  });

  it('offers Replay all through the shared preview, and explains why not to an operator who may not move messages', async () => {
    signedInWith(['message:read']);
    dlq({
      settingsAvailable: true,
      destinations: [{ ...destinationIdentity('DLQ'), purpose: 'dead-letter', subscriptions: [QUEUE] }],
    });
    const user = userEvent.setup();
    renderWithProviders(<DeadLettersView />);

    await user.click(await screen.findByRole('button', { name: /^Actions for dead-letter subscription DLQ.orders/ }));
    const item = await screen.findByRole('menuitem', { name: /^Replay all…/ });
    await waitFor(() => expect(item).toHaveAttribute('aria-disabled', 'true'));
    await user.click(item);

    expect(
      await screen.findByRole('dialog', { name: 'Why replaying this subscription is unavailable' }),
    ).toHaveTextContent('Move or retry messages');
  });

  it('previews a replay of the subscription chosen from its row', async () => {
    signedInWith(['*']);
    dlq({
      settingsAvailable: true,
      destinations: [{ ...destinationIdentity('DLQ'), purpose: 'dead-letter', subscriptions: [QUEUE] }],
    });
    server.use(
      http.post('*/api/v1/clusters/c1/resources/artemis.queue/DLQ.orders/messages:retry', () =>
        HttpResponse.json({ affectedCount: 7, cap: 100, overCap: false, node: 'n1' }),
      ),
    );
    const user = userEvent.setup();
    renderWithProviders(<DeadLettersView />);

    await user.click(await screen.findByRole('button', { name: /^Actions for dead-letter subscription DLQ.orders/ }));
    await user.click(await screen.findByRole('menuitem', { name: /^Replay all…/ }));
    await user.click(await screen.findByRole('button', { name: 'Preview' }));

    expect(await screen.findByRole('button', { name: 'Retry 7 messages' })).toBeEnabled();
  });

  it('says the dead-letter settings could not be read, and infers no subscription from its name', async () => {
    signedInWith(['*']);
    dlq({ settingsAvailable: false, destinations: [] });
    renderWithProviders(<DeadLettersView />);

    expect(await screen.findByText('Dead-letter configuration unavailable')).toBeInTheDocument();
    expect(screen.getByText(/will not guess which subscriptions are dead-letter/)).toBeInTheDocument();
  });

  it('teaches what is empty when nothing is dead-lettered, naming the dead-letter destination', async () => {
    signedInWith(['*']);
    dlq({
      settingsAvailable: true,
      destinations: [{ ...destinationIdentity('DLQ'), purpose: 'dead-letter', subscriptions: [] }],
    });
    renderWithProviders(<DeadLettersView />);

    expect(await screen.findByText('No dead-lettered messages')).toBeInTheDocument();
    expect(screen.getByText('DLQ')).toBeInTheDocument();
  });

  it('states why the subscriptions could not be read, with a retry, in place of the rows', async () => {
    signedInWith(['*']);
    server.use(
      http.get('*/api/v1/clusters/c1/dead-letters', () =>
        HttpResponse.json({ title: 'Cluster unreachable', detail: 'No node answered.' }, { status: 502 }),
      ),
    );
    renderWithProviders(<DeadLettersView />);

    const alert = await screen.findByRole('alert');
    expect(within(alert).getByText('Rahdar failed to complete the request')).toBeInTheDocument();
    expect(within(alert).getByRole('button', { name: 'Retry' })).toBeInTheDocument();
  });
});
