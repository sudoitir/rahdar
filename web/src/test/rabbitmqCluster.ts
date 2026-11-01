import { http, HttpResponse } from 'msw';

import { paged } from '../kernel/api/paging.ts';
import { server } from './setup.ts';
import { RABBITMQ_KINDS, RABBITMQ_SYSTEM } from './wire.ts';

const AVAILABLE = { status: 'AVAILABLE', reason: 'ok', brokerXmlSnippet: null };

/** The shell around a RabbitMQ cluster `r1`: who is signed in, the cluster, and what the shell asks for. */
export function mockRabbitMqCluster(permissions: string[] = ['*']) {
  server.use(
    http.get('*/api/v1/auth/me', () =>
      HttpResponse.json({
        id: 'u1',
        username: 'operator',
        mustChangePassword: false,
        grants: [{ scopeType: 'GLOBAL', scopeId: null, permissions }],
      }),
    ),
    http.get('*/api/v1/clusters', () =>
      HttpResponse.json(
        paged([
          {
            id: 'r1',
            name: 'orders-mq',
            health: 'OK',
            nodeCount: 1,
            updatedAt: '2026-01-01T00:00:00Z',
            environmentId: null,
            system: RABBITMQ_SYSTEM,
            kinds: RABBITMQ_KINDS,
          },
        ]),
      ),
    ),
    http.get('*/api/v1/environments', () => HttpResponse.json(paged([]))),
    http.get('*/api/v1/alerts/firing', () => HttpResponse.json(paged([]))),
    http.get('*/api/v1/clusters/r1', () =>
      HttpResponse.json({
        id: 'r1',
        name: 'orders-mq',
        description: null,
        system: RABBITMQ_SYSTEM,
        kinds: RABBITMQ_KINDS,
        topology: { clusterId: 'r1', nodes: [] },
        capabilities: {
          managementRead: AVAILABLE,
          managementWrite: AVAILABLE,
          notifications: AVAILABLE,
          messageIo: AVAILABLE,
          slowConsumerDetection: AVAILABLE,
          versionGates: [],
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
  );
}
