import { subscriptionIdentity } from '../../test/wire.ts';
import { describe, expect, it, vi } from 'vitest';
import { http, HttpResponse } from 'msw';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

import { renderWithProviders } from '../../test/render.tsx';
import { server } from '../../test/setup.ts';
import type { CapabilityView, SubscriptionView } from './api.ts';
import { QueueLifecycleActions } from './QueueLifecycleActions.tsx';
import { holdButton, holdByKeyboard } from '../../test/hold.ts';

const AVAILABLE: CapabilityView = { status: 'AVAILABLE', reason: 'ok', brokerXmlSnippet: null };

function capabilities(managementWrite: CapabilityView) {
  return {
    managementRead: AVAILABLE,
    managementWrite,
    notifications: AVAILABLE,
    messageIo: AVAILABLE,
    slowConsumerDetection: AVAILABLE,
    versionGates: [],
  };
}

/** The cluster detail carries the capability ledger the controls gate on. */
function clusterHandler(managementWrite: CapabilityView) {
  return http.get('*/api/v1/clusters/c1', () =>
    HttpResponse.json({
      id: 'c1',
      name: 'c1',
      description: null,
      topology: { nodes: [] },
      capabilities: capabilities(managementWrite),
      health: { level: 'OK', reasons: [] },
      environmentId: null,
    }),
  );
}

/** An admin with every grant, so the permission gate never masks the capability gate. */
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

function queue(over: Partial<SubscriptionView> = {}): SubscriptionView {
  return {
    ...subscriptionIdentity('orders', 'orders.addr'),
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
    totalMessageCount: 12,
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
  } as SubscriptionView;
}

function Harness() {
  return <QueueLifecycleActions clusterId="c1" queue={queue()} onClose={() => {}} />;
}

describe('QueueLifecycleActions capability gating', () => {
  it('offers the actions and states the uncertainty when the capability is unknown', async () => {
    server.use(
      meHandler(),
      clusterHandler({
        status: 'UNKNOWN',
        reason: 'No management write has been attempted on this connection yet.',
        brokerXmlSnippet: null,
      }),
    );
    renderWithProviders(<Harness />);

    // The uncertainty is stated once the capability ledger has loaded...
    expect(await screen.findByText(/has not yet seen a management write on this connection/i)).toBeInTheDocument();
    // ...and absence of evidence still does not block the operator.
    expect(screen.getByRole('button', { name: 'Delete queue' })).toBeEnabled();
    expect(screen.getByRole('button', { name: 'Pause' })).toBeEnabled();
  });

  it('keeps the action visible but disabled, with the reason, when the capability is refused', async () => {
    server.use(
      meHandler(),
      clusterHandler({
        status: 'UNAVAILABLE',
        reason: 'The broker refused a management write for these credentials.',
        brokerXmlSnippet: '<security-setting match="activemq.management.#"/>',
      }),
    );
    const user = userEvent.setup();
    renderWithProviders(<Harness />);

    // Visible and disabled — never silently absent (non-negotiable #5).
    await waitFor(() => expect(screen.getByRole('button', { name: 'Delete queue' })).toBeDisabled());

    // And the reason is reachable without a pointer hover: the wrapper is a real
    // focusable control carrying the explanation.
    const why = screen.getByRole('button', { name: 'Why deleting this queue is unavailable' });
    await user.click(why);

    expect(await screen.findByText('The broker refused a management write for these credentials.')).toBeInTheDocument();
    expect(screen.getByText(/security-setting match/)).toBeInTheDocument();
  });

  it('hides an action the caller cannot take anywhere on the cluster', async () => {
    // Every lifecycle permission except delete, held on the cluster itself: delete is not theirs to be offered.
    server.use(
      meHandler(['subscription:create', 'subscription:update', 'subscription:pause', 'cluster:read']),
      clusterHandler(AVAILABLE),
    );
    renderWithProviders(<Harness />);

    await waitFor(() => expect(screen.queryByRole('button', { name: 'Delete queue' })).not.toBeInTheDocument());
    expect(screen.getByRole('button', { name: 'Pause' })).toBeEnabled();
    expect(screen.queryByRole('button', { name: 'Why deleting this queue is unavailable' })).not.toBeInTheDocument();
  });

  describe('for a team member on a queue the team does not own', () => {
    /** Holds pause, edit and delete through teams somewhere on the cluster, and on this queue only pause. */
    function teamAccess() {
      return http.get('*/api/v1/me/access', ({ request }) => {
        const clusterId = new URL(request.url).searchParams.get('clusterId');
        return HttpResponse.json({
          permissions: [],
          anywhere: ['subscription:pause', 'subscription:update', 'subscription:delete'],
          canSeeCluster: clusterId ? true : null,
          teams: [],
          createPatterns: { subscription: [], destination: [] },
        });
      });
    }

    function SharedQueue() {
      return (
        <QueueLifecycleActions
          clusterId="c1"
          queue={queue({
            allowedActions: ['subscription:read', 'subscription:pause'],
            ownerTeam: { id: 't1', name: 'Orders' },
          })}
          onClose={() => {}}
        />
      );
    }

    it('shows the others disabled, naming the permission and the team to ask', async () => {
      server.use(teamAccess(), clusterHandler(AVAILABLE));
      const user = userEvent.setup();
      renderWithProviders(<SharedQueue />);

      await waitFor(() => expect(screen.getByRole('button', { name: 'Delete queue' })).toBeDisabled());
      expect(screen.getByRole('button', { name: 'Pause' })).toBeEnabled();

      await user.click(screen.getByRole('button', { name: 'Why deleting this queue is unavailable' }));
      expect(
        await screen.findByText(
          'You do not have the "Destroy queues and addresses" permission (subscription:delete) on this queue. Ask an admin of team Orders.',
        ),
      ).toBeInTheDocument();
    });

    it('puts the reason one Tab from the enabled control, and opens it from the keyboard', async () => {
      server.use(teamAccess(), clusterHandler(AVAILABLE));
      const user = userEvent.setup();
      renderWithProviders(<SharedQueue />);

      await waitFor(() => expect(screen.getByRole('button', { name: 'Edit' })).toBeDisabled());
      screen.getByRole('button', { name: 'Pause' }).focus();
      // The disabled Edit takes no focus; the control beside it that carries its reason does.
      await user.tab();
      expect(screen.getByRole('button', { name: 'Why editing this queue is unavailable' })).toHaveFocus();

      await user.keyboard('{Enter}');
      expect(
        await screen.findByText(/\(subscription:update\) on this queue\. Ask an admin of team Orders\./),
      ).toBeInTheDocument();
    });

    it('points at a platform administrator when no team owns the queue', async () => {
      server.use(teamAccess(), clusterHandler(AVAILABLE));
      const user = userEvent.setup();
      renderWithProviders(
        <QueueLifecycleActions
          clusterId="c1"
          queue={queue({ allowedActions: ['subscription:read'] })}
          onClose={() => {}}
        />,
      );

      await user.click(await screen.findByRole('button', { name: 'Why deleting this queue is unavailable' }));
      expect(await screen.findByText(/Ask a platform administrator\./)).toBeInTheDocument();
    });
  });
});

describe('the destructive flow is keyboard-complete', () => {
  it('opens on a preview, arms only on the typed name, and closes on escape', async () => {
    server.use(
      meHandler(),
      clusterHandler(AVAILABLE),
      http.delete('*/api/v1/clusters/c1/resources/artemis.queue/orders', () =>
        HttpResponse.json({
          dryRun: true,
          cap: 1000,
          overCap: false,
          partial: false,
          totalAffected: 12,
          nodes: [{ nodeId: 'n1', nodeName: 'node-a', status: 'WOULD_APPLY', affected: 12, error: null }],
        }),
      ),
    );
    const user = userEvent.setup();
    renderWithProviders(<Harness />);

    // Wait for the gate to settle so the control is the real, ungated button.
    await waitFor(() => expect(screen.getByRole('button', { name: 'Delete queue' })).toBeEnabled());
    await user.click(screen.getByRole('button', { name: 'Delete queue' }));

    const dialog = await screen.findByRole('dialog');
    // The blast radius is named before the confirmation can arm.
    expect(await within(dialog).findByText('would destroy 12 messages')).toBeInTheDocument();
    expect(within(dialog).getByText('node-a')).toBeInTheDocument();

    const confirm = within(dialog).getByRole('button', { name: 'Delete this queue' });
    await waitFor(() => expect(confirm).toBeEnabled());

    await user.keyboard('{Escape}');
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
  });

  it('names the consumers and dependent diverts, and disconnects only when asked, by keyboard alone', async () => {
    const urls: string[] = [];
    server.use(
      meHandler(),
      clusterHandler(AVAILABLE),
      http.delete('*/api/v1/clusters/c1/resources/artemis.queue/orders', ({ request }) => {
        const url = new URL(request.url);
        urls.push(url.search);
        const dryRun = url.searchParams.get('dryRun') === 'true';
        const disconnect = url.searchParams.get('disconnectConsumers') === 'true';
        const note =
          "2 consumers will be disconnected. Removed with the queue, because they forward into 'orders.addr'" +
          " and this is its last queue: 'feed' (incoming → orders.addr, routing name feed, routing type STRIP).";
        const node = !disconnect
          ? {
              nodeId: 'n1',
              nodeName: 'node-a',
              status: 'FAILED',
              affected: null,
              error: "Queue 'orders' has 2 consumers attached on this node. Delete with disconnectConsumers.",
            }
          : {
              nodeId: 'n1',
              nodeName: 'node-a',
              status: dryRun ? 'WOULD_APPLY' : 'APPLIED',
              affected: 12,
              error: note,
            };
        return HttpResponse.json({
          dryRun,
          cap: 1000,
          overCap: false,
          partial: false,
          totalAffected: node.affected ?? 0,
          nodes: [node],
        });
      }),
    );
    const user = userEvent.setup();
    renderWithProviders(
      <QueueLifecycleActions clusterId="c1" queue={queue({ totalConsumerCount: 2 })} onClose={() => {}} />,
    );

    const trigger = screen.getByRole('button', { name: 'Delete queue' });
    await waitFor(() => expect(trigger).toBeEnabled());
    trigger.focus();
    await user.keyboard('{Enter}');

    const dialog = await screen.findByRole('dialog');
    await waitFor(() => expect(dialog).toContainElement(document.activeElement as HTMLElement));
    // Without the flag, the preview says why the node would refuse.
    expect(await within(dialog).findByText(/has 2 consumers attached/)).toBeInTheDocument();
    // The headline agrees with the row: a refused node is not one the delete would apply to.
    expect(within(dialog).getByText('Would apply to 0 of 1 nodes, 1 refused')).toBeInTheDocument();

    const disconnect = within(dialog).getByRole('checkbox', { name: /Disconnect this queue's consumers/ });
    disconnect.focus();
    await user.keyboard(' ');
    expect(disconnect).toBeChecked();

    // The preview is taken again with the flag, and names the divert that goes with the queue.
    expect(await within(dialog).findByText(/'feed' \(incoming → orders\.addr/)).toBeInTheDocument();
    expect(urls.at(-1)).toContain('disconnectConsumers=true');
    expect(urls.at(-1)).toContain('dryRun=true');

    await holdByKeyboard(within(dialog).getByRole('button', { name: 'Delete this queue' }));

    const result = await screen.findByRole('dialog', { name: /result of deleting orders/i });
    expect(await within(result).findByText('applied')).toBeInTheDocument();
    expect(urls.at(-1)).toContain('dryRun=false');
    expect(urls.at(-1)).toContain('disconnectConsumers=true');

    await user.keyboard('{Escape}');
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
    await waitFor(() => expect(trigger).toHaveFocus());
  });

  it('keeps the consumer choice locked while the delete is in flight, so its result is never lost', async () => {
    let release: () => void = () => {};
    const gate = new Promise<void>((resolve) => {
      release = resolve;
    });
    server.use(
      meHandler(),
      clusterHandler(AVAILABLE),
      http.delete('*/api/v1/clusters/c1/resources/artemis.queue/orders', async ({ request }) => {
        const dryRun = new URL(request.url).searchParams.get('dryRun') === 'true';
        if (!dryRun) await gate;
        return HttpResponse.json({
          dryRun,
          cap: 1000,
          overCap: false,
          partial: false,
          totalAffected: 12,
          nodes: [
            {
              nodeId: 'n1',
              nodeName: 'node-a',
              status: dryRun ? 'WOULD_APPLY' : 'APPLIED',
              affected: 12,
              error: null,
            },
          ],
        });
      }),
    );
    const user = userEvent.setup();
    renderWithProviders(<Harness />);

    await waitFor(() => expect(screen.getByRole('button', { name: 'Delete queue' })).toBeEnabled());
    await user.click(screen.getByRole('button', { name: 'Delete queue' }));
    const dialog = await screen.findByRole('dialog');
    expect(await within(dialog).findByText('would destroy 12 messages')).toBeInTheDocument();

    await holdButton(within(dialog).getByRole('button', { name: 'Delete this queue' }));

    const disconnect = within(dialog).getByRole('checkbox', { name: /Disconnect this queue's consumers/ });
    await waitFor(() => expect(disconnect).toBeDisabled());
    await user.click(disconnect);

    release();
    const result = await screen.findByRole('dialog', { name: /result of deleting orders/i });
    expect(await within(result).findByText('applied')).toBeInTheDocument();
    expect(within(result).getByText('destroyed 12 messages')).toBeInTheDocument();
  });
});

describe('a delete that failed everywhere', () => {
  it('leaves the queue open instead of closing it as though the queue were gone', async () => {
    server.use(
      meHandler(),
      clusterHandler(AVAILABLE),
      http.delete('*/api/v1/clusters/c1/resources/artemis.queue/orders', ({ request }) => {
        const dryRun = new URL(request.url).searchParams.get('dryRun') === 'true';
        return HttpResponse.json({
          dryRun,
          cap: 1000,
          overCap: false,
          // Every node failed, so the run is not partial — and the queue is still there.
          partial: false,
          totalAffected: dryRun ? 12 : 0,
          nodes: [
            {
              nodeId: 'n1',
              nodeName: 'node-a',
              status: dryRun ? 'WOULD_APPLY' : 'FAILED',
              affected: dryRun ? 12 : null,
              error: dryRun ? null : 'The broker did not answer in time.',
            },
          ],
        });
      }),
    );
    const user = userEvent.setup();
    const onClose = vi.fn();
    renderWithProviders(<QueueLifecycleActions clusterId="c1" queue={queue()} onClose={onClose} />);

    await waitFor(() => expect(screen.getByRole('button', { name: 'Delete queue' })).toBeEnabled());
    await user.click(screen.getByRole('button', { name: 'Delete queue' }));

    const dialog = await screen.findByRole('dialog');
    expect(await within(dialog).findByText('would destroy 12 messages')).toBeInTheDocument();
    await holdButton(within(dialog).getByRole('button', { name: 'Delete this queue' }));

    const result = await screen.findByRole('dialog', { name: /result of deleting orders/i });
    expect(await within(result).findByText('Failed on every node')).toBeInTheDocument();
    await user.click(within(result).getByRole('button', { name: 'Close' }));

    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
    expect(onClose).not.toHaveBeenCalled();
  });
});

describe('a delete no node was live for', () => {
  it('leaves the queue open, because nothing was deleted', async () => {
    server.use(
      meHandler(),
      clusterHandler(AVAILABLE),
      http.delete('*/api/v1/clusters/c1/resources/artemis.queue/orders', ({ request }) => {
        const dryRun = new URL(request.url).searchParams.get('dryRun') === 'true';
        return HttpResponse.json({
          dryRun,
          cap: 1000,
          overCap: false,
          // Nothing settled anywhere, so the server does not call this partial either.
          partial: false,
          totalAffected: dryRun ? 12 : 0,
          nodes: [
            {
              nodeId: 'n1',
              nodeName: 'node-a',
              status: dryRun ? 'WOULD_APPLY' : 'SKIPPED_NOT_LIVE',
              affected: dryRun ? 12 : null,
              error: null,
            },
          ],
        });
      }),
    );
    const user = userEvent.setup();
    const onClose = vi.fn();
    renderWithProviders(<QueueLifecycleActions clusterId="c1" queue={queue()} onClose={onClose} />);

    await waitFor(() => expect(screen.getByRole('button', { name: 'Delete queue' })).toBeEnabled());
    await user.click(screen.getByRole('button', { name: 'Delete queue' }));

    const dialog = await screen.findByRole('dialog');
    expect(await within(dialog).findByText('would destroy 12 messages')).toBeInTheDocument();
    await holdButton(within(dialog).getByRole('button', { name: 'Delete this queue' }));

    const result = await screen.findByRole('dialog', { name: /result of deleting orders/i });
    expect(await within(result).findByText('No node was live, so nothing was applied')).toBeInTheDocument();
    await user.click(within(result).getByRole('button', { name: 'Close' }));

    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
    expect(onClose).not.toHaveBeenCalled();
  });
});

describe('the edit form', () => {
  /** What the broker reports for the queue, and what a PATCH is sent as. */
  function editHandlers(values: Record<string, unknown>, onPatch?: (body: unknown) => void) {
    let current = { ...values };
    return [
      clusterHandler(AVAILABLE),
      meHandler(),
      http.get('*/api/v1/clusters/c1/resources/artemis.queue/orders', () =>
        HttpResponse.json({
          ...subscriptionIdentity('orders', 'orders.addr'),
          nodes: [{ nodeId: 'n1', nodeName: 'node-a', values: current, unavailableReason: null }],
        }),
      ),
      http.patch('*/api/v1/clusters/c1/resources/artemis.queue/orders', async ({ request }) => {
        const body = (await request.json()) as { attributes?: Record<string, unknown> };
        onPatch?.(body);
        if (body.attributes?.maxConsumers !== undefined)
          current = { ...current, 'max-consumers': body.attributes.maxConsumers };
        return HttpResponse.json({
          dryRun: false,
          cap: 1000,
          overCap: false,
          partial: false,
          totalAffected: 1,
          nodes: [{ nodeId: 'n1', nodeName: 'node-a', status: 'APPLIED', affected: null, error: null }],
        });
      }),
    ];
  }

  it('opens on what the queue runs, sends only what changed, and shows the applied value again', async () => {
    const patched = vi.fn();
    server.use(...editHandlers({ 'max-consumers': -1, 'ring-size': -1, 'filter-string': null }, patched));
    const user = userEvent.setup();
    renderWithProviders(<Harness />);

    await user.click(await screen.findByRole('button', { name: 'Edit' }));
    const dialog = await screen.findByRole('dialog', { name: /Edit orders/ });
    // Seeded from the broker, not blank: the operator edits what is there.
    const consumers = await within(dialog).findByRole('textbox', { name: /Max consumers/ });
    await waitFor(() => expect(consumers).toHaveValue('-1'));
    // Nothing changed yet, so there is nothing to apply.
    expect(within(dialog).getByRole('button', { name: 'Apply' })).toBeDisabled();

    await user.clear(consumers);
    await user.type(consumers, '4');
    await user.click(within(dialog).getByRole('button', { name: 'Apply' }));

    // The outcome is stated per node, and the form now shows what was written.
    expect(await within(dialog).findByText(/Applied to all 1 node/)).toBeInTheDocument();
    await waitFor(() => expect(consumers).toHaveValue('4'));
    // Only the field the operator changed is sent; the rest is left to the merge.
    expect(patched).toHaveBeenCalledTimes(1);
    expect(patched.mock.calls[0][0]).toMatchObject({ attributes: { maxConsumers: 4 } });
    expect((patched.mock.calls[0][0] as { attributes: Record<string, unknown> }).attributes.ringSize).toBeUndefined();
  });
});

describe('a pause the broker refused', () => {
  it('states the cause and the next step, and the control is usable again', async () => {
    server.use(
      meHandler(),
      clusterHandler(AVAILABLE),
      http.post('*/api/v1/clusters/c1/resources/artemis.queue/orders:pause', () =>
        HttpResponse.json({ title: 'Pause failed', detail: 'node-a did not answer.' }, { status: 502 }),
      ),
    );
    const user = userEvent.setup();
    renderWithProviders(<Harness />);

    const pause = await screen.findByRole('button', { name: 'Pause' });
    await user.click(pause);

    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent('Rahdar failed to complete the request');
    expect(alert).toHaveTextContent('node-a did not answer.');
    expect(alert).toHaveTextContent('Retry.');
    expect(screen.getByRole('button', { name: 'Pause' })).toBeEnabled();
  });
});

describe('the delete result', () => {
  it('replaces the confirmation with the result in the same dialog, and hands focus back to the Delete button', async () => {
    server.use(
      meHandler(),
      clusterHandler(AVAILABLE),
      http.delete('*/api/v1/clusters/c1/resources/artemis.queue/orders', ({ request }) => {
        const dryRun = new URL(request.url).searchParams.get('dryRun') === 'true';
        return HttpResponse.json({
          dryRun,
          cap: 1000,
          overCap: false,
          partial: true,
          totalAffected: 12,
          nodes: [
            { nodeId: 'a', nodeName: 'node-a', status: dryRun ? 'WOULD_APPLY' : 'APPLIED', affected: 12, error: null },
            { nodeId: 'b', nodeName: 'node-b', status: 'FAILED', affected: null, error: 'node-b refused' },
          ],
        });
      }),
    );
    const user = userEvent.setup();
    renderWithProviders(<Harness />);

    const trigger = await screen.findByRole('button', { name: 'Delete queue' });
    await user.click(trigger);
    const confirmation = await screen.findByRole('dialog', { name: 'Delete orders' });
    await holdButton(within(confirmation).getByRole('button', { name: 'Delete this queue' }));

    // Partial is read per node, where the typed confirmation was.
    const result = await screen.findByRole('dialog', { name: 'Result of deleting orders' });
    expect(within(result).getByText('Applied to some nodes and not others')).toBeInTheDocument();
    expect(within(result).getByText('node-b refused')).toBeInTheDocument();
    await waitFor(() => expect(screen.queryByRole('textbox')).not.toBeInTheDocument());

    await user.click(within(result).getByRole('button', { name: 'Close' }));
    await waitFor(() => expect(trigger).toHaveFocus());
  });
});
