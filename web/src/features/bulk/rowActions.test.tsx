import { describe, expect, it, vi } from 'vitest';
import { http, HttpResponse } from 'msw';
import { screen, waitFor } from '@testing-library/react';
import { Menu } from '@mantine/core';

import type { ActionHost, SubscriptionTarget } from '../../kernel/actions/types.ts';
import { renderWithProviders } from '../../test/render.tsx';
import { server } from '../../test/setup.ts';
import type { components } from '../../kernel/api/schema.d.ts';
import { ARTEMIS_KINDS, ARTEMIS_SYSTEM } from '../../test/wire.ts';
import { PurgeSubscription } from './rowActions.tsx';

type SubscriptionView = components['schemas']['SubscriptionView'];

const HOST: ActionHost = { open: () => {}, explain: vi.fn(), copy: () => {} };

function purge(snapshot: Partial<SubscriptionView>) {
  const target = {
    id: 'artemis.queue/orders.in',
    kind: 'artemis.queue',
    name: 'orders.in',
    destination: 'artemis.address/orders.in',
    snapshot: snapshot as SubscriptionView,
  } as SubscriptionTarget;
  return renderWithProviders(
    <Menu opened>
      <Menu.Dropdown>
        <PurgeSubscription clusterId="c1" target={target} host={HOST} mode="act" />
      </Menu.Dropdown>
    </Menu>,
  );
}

/** Holds subscription:purge through a team, somewhere on the cluster; nothing on the cluster itself. */
function teamHoldsPurge(held: boolean, kinds: unknown[] = ARTEMIS_KINDS) {
  const available = { status: 'AVAILABLE', reason: '', brokerXmlSnippet: null };
  server.use(
    http.get('*/api/v1/clusters/c1', () =>
      HttpResponse.json({
        id: 'c1',
        name: 'c1',
        description: null,
        topology: { nodes: [] },
        capabilities: {
          managementRead: available,
          managementWrite: available,
          notifications: available,
          messageIo: available,
          slowConsumerDetection: available,
          versionGates: [],
        },
        health: { level: 'OK', reasons: [] },
        environmentId: null,
        system: ARTEMIS_SYSTEM,
        kinds,
      }),
    ),
    http.get('*/api/v1/me/access', ({ request }) => {
      const clusterId = new URL(request.url).searchParams.get('clusterId');
      return HttpResponse.json({
        permissions: [],
        anywhere: held ? ['subscription:purge'] : [],
        canSeeCluster: clusterId ? true : null,
        teams: [],
        createPatterns: { subscription: [], destination: [] },
      });
    }),
  );
}

describe('PurgeSubscription on a subscription row', () => {
  it('is disabled on a subscription the team does not allow it on, naming the permission and the owner to ask', async () => {
    teamHoldsPurge(true);
    purge({ allowedActions: ['subscription:read'], ownerTeam: { id: 't1', name: 'Orders' } });

    const item = await screen.findByRole('menuitem', { name: /Purge messages/ });
    // aria-disabled, not disabled: it stays in the arrow-key order, so a keyboard user can reach its reason.
    await waitFor(() => expect(item).toHaveAttribute('aria-disabled', 'true'));
    expect(item).toHaveAccessibleDescription(/\(subscription:purge\) on this subscription\./);
  });

  it('is offered on a subscription the row allows it on', async () => {
    teamHoldsPurge(true);
    purge({ allowedActions: ['subscription:read', 'subscription:purge'] });

    const item = await screen.findByRole('menuitem', { name: /Purge messages/ });
    expect(item).not.toHaveAttribute('aria-disabled');
  });

  it('is left out for someone who can purge nowhere on the cluster', async () => {
    teamHoldsPurge(false);
    purge({ allowedActions: ['subscription:read'] });

    await new Promise((resolve) => setTimeout(resolve, 100));
    expect(screen.queryByRole('menuitem', { name: /Purge messages/ })).not.toBeInTheDocument();
  });

  it('is disabled, never left out, on a kind its system cannot purge, and says so before any permission', async () => {
    // Nobody holds the permission anywhere, which would leave the item out of any other row.
    teamHoldsPurge(
      false,
      ARTEMIS_KINDS.map((kind) => ({ ...kind, verbs: kind.verbs.filter((verb) => verb !== 'purge') })),
    );
    purge({ allowedActions: ['subscription:read'] });

    const item = await screen.findByRole('menuitem', { name: /Purge messages/ });
    await waitFor(() => expect(item).toHaveAttribute('aria-disabled', 'true'));
    expect(item).toHaveAccessibleDescription('Artemis queues cannot be purged.');
  });
});
