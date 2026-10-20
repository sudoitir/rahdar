import { describe, expect, it, vi } from 'vitest';
import { holdButton } from '../../test/hold.ts';
import { http, HttpResponse } from 'msw';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

import { renderWithProviders } from '../../test/render.tsx';
import { server } from '../../test/setup.ts';
import type { CapabilityView, ConnectionCloseView } from './api.ts';
import { CloseAddressConsumersAction, CloseConnectionAction } from './CloseConnection.tsx';
import { ARTEMIS_KINDS } from '../../test/wire.ts';

const AVAILABLE: CapabilityView = { status: 'AVAILABLE', reason: 'ok', brokerXmlSnippet: null };

function clusterHandler() {
  return http.get('*/api/v1/clusters/c1', () =>
    HttpResponse.json({
      id: 'c1',
      name: 'c1',
      description: null,
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
      health: { level: 'OK', reasons: [] },
      environmentId: null,
    }),
  );
}

function meHandler(permissions: string[] = ['*']) {
  return http.get('*/api/v1/auth/me', () =>
    HttpResponse.json({
      id: 'u1',
      username: 'admin',
      mustChangePassword: false,
      grants: [{ scopeType: 'GLOBAL', scopeId: null, permissions }],
    }),
  );
}

const NODE = { nodeId: 'n1', nodeName: 'node-a' };

function outcome(status: string, affected: number | null, dryRun: boolean) {
  return {
    dryRun,
    cap: 0,
    overCap: false,
    partial: false,
    totalAffected: affected ?? 0,
    nodes: [{ ...NODE, status, affected, error: null }],
  };
}

/** A live target: the preview names the application and what it is holding. */
function live(dryRun: boolean): ConnectionCloseView {
  return {
    kind: 'CONNECTION',
    subject: 'a3f1c9de',
    alreadyGone: false,
    target: {
      connection: 'a3f1c9de',
      clientId: 'orders-worker-7',
      remoteAddress: '10.4.2.9:53160',
      user: 'apps',
      protocol: 'CORE',
      sessionCount: 2,
      consumerCount: 3,
      messagesInTransit: 41,
      confirmToken: 'orders-worker-7',
    },
    outcome: outcome(dryRun ? 'WOULD_APPLY' : 'APPLIED', 41, dryRun),
  } as ConnectionCloseView;
}

/** The row was stale: by the time the operator clicked, the connection had gone. */
function gone(dryRun: boolean): ConnectionCloseView {
  // No `target` at all — the broker had nothing to describe.
  return {
    kind: 'CONNECTION',
    subject: 'a3f1c9de',
    alreadyGone: true,
    outcome: outcome(dryRun ? 'WOULD_APPLY' : 'ALREADY', null, dryRun),
  } as unknown as ConnectionCloseView;
}

function closeHandler(reply: (dryRun: boolean) => ConnectionCloseView) {
  return http.post('*/api/v1/clusters/c1/resources/artemis.connection/a3f1c9de:close', ({ request }) =>
    HttpResponse.json(reply(new URL(request.url).searchParams.get('dryRun') === 'true')),
  );
}

function Harness() {
  return (
    <CloseConnectionAction
      clusterId="c1"
      kind="connection"
      nodeId="n1"
      nodeName="node-a"
      targetId="artemis.connection/a3f1c9de"
      rowLabel="orders-worker-7"
      fetchedAt={Date.now() - 4_000}
    />
  );
}

describe('closing a connection from a row', () => {
  it('confirms against the client id, not the opaque connection id', async () => {
    server.use(meHandler(), clusterHandler(), closeHandler(live));
    const user = userEvent.setup();
    renderWithProviders(<Harness />);

    await user.click(await screen.findByRole('button', { name: /close the connection for/i }));

    const dialog = await screen.findByRole('dialog');
    // The client id is the recognisable name the dialog states; the opaque connection id is not offered.
    await screen.findByRole('button', { name: /close this connection/i });
    expect(dialog).toHaveTextContent('orders-worker-7');
    expect(dialog).not.toHaveTextContent('a3f1c9de');
  });

  it('states how many in-flight messages return to their queue before it can be armed', async () => {
    server.use(meHandler(), clusterHandler(), closeHandler(live));
    const user = userEvent.setup();
    renderWithProviders(<Harness />);

    await user.click(await screen.findByRole('button', { name: /close the connection for/i }));

    const dialog = await screen.findByRole('dialog');
    await within(dialog).findByText(/41 in-flight messages will return to their queue/i);
    await within(dialog).findByText(/increased delivery count/i);
    // The scale of the disconnect, not just the one connection.
    expect(within(dialog).getByText('Sessions closed with it')).toBeInTheDocument();
    expect(within(dialog).getByText('Consumers closed with it')).toBeInTheDocument();
  });

  it('reports a target that has already gone as done, in the success position', async () => {
    server.use(meHandler(), clusterHandler(), closeHandler(gone));
    const user = userEvent.setup();
    renderWithProviders(<Harness />);

    await user.click(await screen.findByRole('button', { name: /close the connection for/i }));

    const dialog = await screen.findByRole('dialog');
    await within(dialog).findByText(/had already gone/i);
    // Not an error, and there is nothing left to confirm.
    expect(within(dialog).queryByRole('alert')).toBeNull();
    expect(within(dialog).queryByRole('textbox')).toBeNull();
  });

  it('is reachable and dismissable by keyboard alone, and returns focus to the trigger', async () => {
    server.use(meHandler(), clusterHandler(), closeHandler(live));
    const user = userEvent.setup();
    renderWithProviders(<Harness />);

    const trigger = await screen.findByRole('button', { name: /close the connection for/i });
    trigger.focus();
    await user.keyboard('{Enter}');

    const dialog = await screen.findByRole('dialog');
    await waitFor(() => expect(dialog.contains(document.activeElement)).toBe(true));

    await user.keyboard('{Escape}');
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());
    await waitFor(() => expect(document.activeElement).toBe(trigger));
  });

  it('does not act until the button has been held', async () => {
    const closes = vi.fn();
    server.use(
      meHandler(),
      clusterHandler(),
      http.post('*/api/v1/clusters/c1/resources/artemis.connection/a3f1c9de:close', ({ request }) => {
        const dryRun = new URL(request.url).searchParams.get('dryRun') === 'true';
        if (!dryRun) closes();
        return HttpResponse.json(live(dryRun));
      }),
    );
    const user = userEvent.setup();
    renderWithProviders(<Harness />);

    await user.click(await screen.findByRole('button', { name: /close the connection for/i }));
    const confirm = await screen.findByRole('button', { name: /close this connection/i });

    // A click does nothing; only holding the button closes it.
    await user.click(confirm);
    expect(closes).not.toHaveBeenCalled();
    await holdButton(confirm);
    await waitFor(() => expect(closes).toHaveBeenCalledTimes(1));
  });
});

describe('the close outcome outlives its row (ADR-0107)', () => {
  it('stays on screen after the refreshed listing drops the closed row', async () => {
    server.use(meHandler(), clusterHandler(), closeHandler(live));
    const user = userEvent.setup();
    const { rerender } = renderWithProviders(<Harness />);

    await user.click(await screen.findByRole('button', { name: /close the connection for/i }));
    const dialog = await screen.findByRole('dialog');
    await holdButton(await within(dialog).findByRole('button', { name: 'Close this connection' }));
    await screen.findByRole('button', { name: 'Close' });

    // The listing refetches without the connection, so its row — and the action in it — is gone.
    rerender(<></>);
    expect(screen.getByRole('dialog')).toBeInTheDocument();
    expect(within(screen.getByRole('dialog')).getAllByText(/applied/i).length).toBeGreaterThan(0);
    expect(within(screen.getByRole('dialog')).getByRole('button', { name: 'Close' })).toBeInTheDocument();
  });
});

describe('a target that could not be read', () => {
  it('offers no close, and says why, rather than arming one that was never previewed', async () => {
    server.use(
      meHandler(),
      clusterHandler(),
      http.post('*/api/v1/clusters/c1/resources/artemis.connection/a3f1c9de:close', () =>
        HttpResponse.json({ title: 'Read failed', detail: 'node-a did not answer.' }, { status: 502 }),
      ),
    );
    const user = userEvent.setup();
    renderWithProviders(<Harness />);

    await user.click(await screen.findByRole('button', { name: /close the connection for/i }));
    const dialog = await screen.findByRole('dialog', { name: 'Close this connection' });

    expect(await within(dialog).findByText('node-a did not answer.')).toBeInTheDocument();
    expect(within(dialog).getByText('The target could not be read.')).toBeInTheDocument();
    expect(within(dialog).queryByRole('textbox')).not.toBeInTheDocument();
    // The close is visible and cannot be armed, and the reason is what the button is described by.
    const close = within(dialog).getByRole('button', { name: 'Close this connection' });
    expect(close).toBeDisabled();
    expect(close).toHaveAccessibleDescription(/the close is not offered until the read succeeds/);
    expect(within(dialog).getByRole('button', { name: 'Cancel' })).toBeInTheDocument();
  });

  it('lists what it would close as terms and values once it is read', async () => {
    server.use(meHandler(), clusterHandler(), closeHandler(live));
    const user = userEvent.setup();
    renderWithProviders(<Harness />);

    await user.click(await screen.findByRole('button', { name: /close the connection for/i }));
    const dialog = await screen.findByRole('dialog', { name: 'Close this connection' });

    const group = await within(dialog).findByRole('group', { name: 'What this closes' });
    expect(within(group).getByText('Remote address')).toBeInTheDocument();
    expect(within(group).getByText('10.4.2.9:53160')).toBeInTheDocument();
    expect(within(group).getByText('Consumers closed with it')).toBeInTheDocument();
  });
});

describe('closing every consumer on an address', () => {
  function addressHandler(replyFor: (dryRun: boolean) => object) {
    return http.post('*/api/v1/clusters/c1/resources/artemis.address/orders:close', ({ request }) =>
      HttpResponse.json(replyFor(new URL(request.url).searchParams.get('dryRun') === 'true')),
    );
  }

  const reply = (dryRun: boolean) => ({
    kind: 'DESTINATION',
    subject: 'orders',
    alreadyGone: false,
    outcome: {
      dryRun,
      cap: 100,
      overCap: false,
      partial: true,
      totalAffected: 5,
      nodes: [
        { nodeId: 'a', nodeName: 'node-a', status: dryRun ? 'WOULD_APPLY' : 'APPLIED', affected: 5, error: null },
        { nodeId: 'b', nodeName: 'node-b', status: 'FAILED', affected: null, error: 'node-b refused' },
      ],
    },
  });

  it('arms on the address, then reads a partial result per node in a dialog of its own', async () => {
    server.use(meHandler(), clusterHandler(), addressHandler(reply));
    const user = userEvent.setup();
    renderWithProviders(<CloseAddressConsumersAction clusterId="c1" address="orders" />);

    await user.click(await screen.findByRole('button', { name: 'Close consumers on orders' }));
    const confirmation = await screen.findByRole('dialog', { name: 'Close every consumer on orders' });
    expect(await within(confirmation).findByText('would close 5 consumers')).toBeInTheDocument();

    const confirm = within(confirmation).getByRole('button', { name: 'Close these consumers' });
    await waitFor(() => expect(confirm).toBeEnabled());
    await holdButton(confirm);

    const result = await screen.findByRole('dialog', { name: 'Result of closing the consumers on orders' });
    expect(within(result).getByText('Applied to some nodes and not others')).toBeInTheDocument();
    expect(within(result).getByText('node-b refused')).toBeInTheDocument();
  });
});
