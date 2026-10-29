import { http, HttpResponse } from 'msw';

import { paged } from '../kernel/api/paging.ts';
import { server } from './setup.ts';
import { KAFKA_KINDS, KAFKA_SYSTEM } from './wire.ts';

const AVAILABLE = { status: 'AVAILABLE', reason: 'ok', brokerXmlSnippet: null };

/** The shell around a Kafka cluster `k1`: who is signed in, the cluster, and what the shell asks for. */
export function mockKafkaCluster(permissions: string[] = ['*']) {
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
            id: 'k1',
            name: 'events',
            health: 'OK',
            nodeCount: 3,
            updatedAt: '2026-01-01T00:00:00Z',
            environmentId: null,
            system: KAFKA_SYSTEM,
            kinds: KAFKA_KINDS,
          },
        ]),
      ),
    ),
    http.get('*/api/v1/environments', () => HttpResponse.json(paged([]))),
    http.get('*/api/v1/alerts/firing', () => HttpResponse.json(paged([]))),
    http.get('*/api/v1/clusters/k1', () =>
      HttpResponse.json({
        id: 'k1',
        name: 'events',
        description: null,
        system: KAFKA_SYSTEM,
        kinds: KAFKA_KINDS,
        topology: { clusterId: 'k1', nodes: [] },
        capabilities: {
          managementRead: AVAILABLE,
          managementWrite: AVAILABLE,
          notifications: AVAILABLE,
          messageIo: AVAILABLE,
          slowConsumerDetection: AVAILABLE,
          versionGates: [],
        },
        health: {
          clusterId: 'k1',
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
