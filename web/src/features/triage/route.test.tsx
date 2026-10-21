import { describe, expect, it } from 'vitest';
import { http, HttpResponse } from 'msw';
import { screen } from '@testing-library/react';

import { renderAppAt } from '../../test/render.tsx';
import { server } from '../../test/setup.ts';
import { paged } from '../../kernel/api/paging.ts';

const capability = { status: 'AVAILABLE', reason: null, brokerXmlSnippet: null };

/** What the shell itself reads on any cluster screen. */
function shell() {
  return [
    http.get('*/api/v1/clusters', () =>
      HttpResponse.json(paged([{ id: 'c1', name: 'prod', health: 'OK', nodeCount: 1 }])),
    ),
    http.get('*/api/v1/environments', () => HttpResponse.json(paged([]))),
    http.get('*/api/v1/auth/me', () =>
      HttpResponse.json({
        id: 'u1',
        username: 'admin',
        mustChangePassword: false,
        grants: [{ scopeType: 'GLOBAL', scopeId: null, permissions: ['*'] }],
      }),
    ),
    http.get('*/api/v1/alerts/firing', () => HttpResponse.json(paged([]))),
    http.get('*/api/v1/clusters/c1/subscriptions', () =>
      HttpResponse.json({ data: [], count: 0, page: 1, pageSize: 50 }),
    ),
    http.get('*/api/v1/clusters/c1/dead-letters', () =>
      HttpResponse.json({ settingsAvailable: true, destinations: [] }),
    ),
    http.get('*/api/v1/clusters/c1', () =>
      HttpResponse.json({
        id: 'c1',
        name: 'prod',
        description: null,
        topology: { clusterId: 'c1', nodes: [], unmanaged: [] },
        health: { clusterId: 'c1', level: 'OK', splitBrain: 'NONE', replicationBehind: false, notes: [] },
        capabilities: {
          managementRead: capability,
          managementWrite: capability,
          messageIo: capability,
          notifications: capability,
        },
      }),
    ),
  ];
}

describe('the consumer health destination', () => {
  it('reaches the ranked view, worst first', async () => {
    server.use(
      ...shell(),
      http.get('*/api/v1/clusters/c1/consumer-health', () =>
        HttpResponse.json({
          data: [
            {
              destination: 'artemis.address/orders',
              subscription: 'artemis.queue/orders',
              verdict: 'NO_CONSUMERS',
              severity: 4,
              cause: 'No consumers are attached.',
              source: 'DERIVED',
              brokerConsumerName: null,
              depth: 9000,
              consumers: 0,
              delivering: 0,
              scheduled: 0,
              paused: false,
              depthSlopePerSecond: 2,
              addRate: 5,
              ackRate: 0,
              netRate: 5,
              ackRatePerConsumer: null,
              drainEtaSeconds: null,
              asOf: '2026-09-20T10:00:00Z',
              sampleSpanSeconds: 30,
              stale: false,
              nodesPresent: 1,
              nodesTotal: 1,
            },
          ],
          count: 1,
          page: 1,
          pageSize: 200,
        }),
      ),
    );

    renderAppAt('/clusters/c1/consumer-health');

    // The real router resolves the destination to the feature's view.
    expect(await screen.findByText('No consumers')).toBeInTheDocument();
    expect(await screen.findByRole('textbox', { name: 'Filter subscriptions' })).toBeInTheDocument();
  });
});
