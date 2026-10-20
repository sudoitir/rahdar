import { describe, expect, it } from 'vitest';
import { http, HttpResponse } from 'msw';
import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

import { renderWithProviders } from '../../test/render.tsx';
import { server } from '../../test/setup.ts';
import { CreateQueueForm } from './CreateQueueForm.tsx';

function emptyQueues() {
  return http.get('*/api/v1/clusters/c1/subscriptions', () =>
    HttpResponse.json({ data: [], count: 0, page: 1, pageSize: 300 }),
  );
}

/** Signed in with every grant, so the name is not held to a team's patterns. */
function admin() {
  return http.get('*/api/v1/auth/me', () =>
    HttpResponse.json({
      id: 'u1',
      username: 'admin',
      mustChangePassword: false,
      grants: [{ scopeType: 'GLOBAL', scopeId: null, permissions: ['*'] }],
    }),
  );
}

/** A team member: nothing is granted on the cluster, and a team lets them create under these patterns. */
function teamMember(createPatterns: { subscription: string[]; destination: string[] }) {
  return http.get('*/api/v1/me/access', ({ request }) => {
    const clusterId = new URL(request.url).searchParams.get('clusterId');
    return HttpResponse.json({
      permissions: [],
      anywhere: ['subscription:create', 'destination:create'],
      canSeeCluster: clusterId ? true : null,
      teams: [],
      createPatterns,
    });
  });
}

function Harness() {
  return <CreateQueueForm clusterId="c1" opened onClose={() => {}} />;
}

describe('CreateQueueForm', () => {
  it('labels every field visibly rather than relying on a placeholder', async () => {
    server.use(emptyQueues());
    renderWithProviders(<Harness />);

    expect(await screen.findByRole('textbox', { name: /address/i })).toBeInTheDocument();
    expect(screen.getByRole('textbox', { name: /queue name/i })).toBeInTheDocument();
    expect(screen.getByRole('radiogroup', { name: /routing type/i })).toBeInTheDocument();
    expect(screen.getByRole('switch', { name: /durable/i })).toBeInTheDocument();
  });

  it('validates a required field when the operator leaves it, before any submit', async () => {
    server.use(emptyQueues());
    const user = userEvent.setup();
    renderWithProviders(<Harness />);

    const name = await screen.findByRole('textbox', { name: /queue name/i });
    await user.click(name);
    await user.tab();

    expect(await screen.findByText('A queue name is required.')).toBeInTheDocument();
  });

  it('keeps submit enabled and explains the problem on activation, never silently disabled', async () => {
    server.use(emptyQueues());
    const user = userEvent.setup();
    renderWithProviders(<Harness />);

    const create = await screen.findByRole('button', { name: 'Create queue' });
    expect(create).toBeEnabled();

    await user.click(create);

    // It reported what is wrong rather than doing nothing.
    expect(await screen.findByText('A queue name is required.')).toBeInTheDocument();
    expect(screen.getByText('Fix the fields above to continue.')).toBeInTheDocument();
  });

  it('moves focus to the first invalid field on a rejected submit', async () => {
    server.use(emptyQueues());
    const user = userEvent.setup();
    renderWithProviders(<Harness />);

    await user.click(await screen.findByRole('button', { name: 'Create queue' }));

    // Address is the first field, so it is the one that gets focus.
    await waitFor(() => expect(screen.getByRole('textbox', { name: /address/i })).toHaveFocus());
  });

  it('previews the per-node outcome without creating anything', async () => {
    server.use(
      admin(),
      emptyQueues(),
      http.post('*/api/v1/clusters/c1/subscriptions', ({ request }) => {
        const url = new URL(request.url);
        expect(url.searchParams.get('dryRun')).toBe('true');
        return HttpResponse.json({
          dryRun: true,
          cap: 1000,
          overCap: false,
          partial: false,
          totalAffected: 0,
          nodes: [
            { nodeId: 'a', nodeName: 'node-a', status: 'WOULD_APPLY', affected: null, error: null },
            {
              nodeId: 'b',
              nodeName: 'node-b',
              status: 'SKIPPED_NOT_LIVE',
              affected: null,
              error: null,
            },
          ],
        });
      }),
    );
    const user = userEvent.setup();
    renderWithProviders(<Harness />);

    await user.type(await screen.findByRole('textbox', { name: /address/i }), 'orders');
    await user.type(screen.getByRole('textbox', { name: /queue name/i }), 'orders');
    await user.click(screen.getByRole('button', { name: 'Preview' }));

    expect(await screen.findByText('Would apply to 1 of 2 nodes, 1 not live and will be skipped')).toBeInTheDocument();
    expect(screen.getByText('Nothing has been created yet. This is what would happen:')).toBeInTheDocument();
  });

  describe('for a member of a team', () => {
    it('shows the patterns they may create under, and checks the name as it is typed', async () => {
      server.use(teamMember({ subscription: ['orders.#'], destination: ['orders.#'] }), emptyQueues());
      const user = userEvent.setup();
      renderWithProviders(<Harness />);

      const name = await screen.findByRole('textbox', { name: /queue name/i });
      expect(await screen.findByText('You may create subscriptions under: orders.#.')).toBeInTheDocument();

      // No blur, no submit: the problem shows while the name is being typed.
      await user.type(name, 'billing.in');
      expect(
        await screen.findByText(/billing\.in is outside the patterns you may create subscriptions under/),
      ).toBeInTheDocument();

      await user.clear(name);
      await user.type(name, 'orders.in');
      await waitFor(() => expect(screen.queryByText(/is outside the patterns/)).not.toBeInTheDocument());
    });

    it('checks the address against the address patterns, which may differ from the queue ones', async () => {
      server.use(teamMember({ subscription: ['orders.#'], destination: ['orders.addr.#'] }), emptyQueues());
      const user = userEvent.setup();
      renderWithProviders(<Harness />);

      const address = await screen.findByRole('textbox', { name: /address/i });
      expect(await screen.findByText(/You may create destinations under: orders\.addr\.#\./)).toBeInTheDocument();
      await user.type(address, 'orders.in');

      expect(
        await screen.findByText(/orders\.in is outside the patterns you may create destinations under/),
      ).toBeInTheDocument();
    });

    it('does not restrict the name of someone whose grant reaches the cluster', async () => {
      server.use(admin(), emptyQueues());
      const user = userEvent.setup();
      renderWithProviders(<Harness />);

      await user.type(await screen.findByRole('textbox', { name: /queue name/i }), 'anything.at.all');
      expect(screen.queryByText(/You may create subscriptions under/)).not.toBeInTheDocument();
      expect(screen.queryByText(/is outside the patterns/)).not.toBeInTheDocument();
    });
  });
});
