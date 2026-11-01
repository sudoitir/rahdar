import { describe, expect, it, vi } from 'vitest';
import { http, HttpResponse } from 'msw';
import { screen } from '@testing-library/react';

import { renderWithProviders } from '../../test/render.tsx';
import { server } from '../../test/setup.ts';
import { ARTEMIS_SYSTEM, KAFKA_KINDS, KAFKA_SYSTEM } from '../../test/wire.ts';
import { manifestHandler } from '../../test/manifest.ts';

vi.mock('@tanstack/react-router', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@tanstack/react-router')>()),
  useParams: () => ({ clusterId: 'c1' }),
  Navigate: ({ to }: { to: string }) => <p>goes to {to}</p>,
}));

const { ClusterLanding } = await import('./ClusterLanding.tsx');

function cluster(system: { id: string; name: string }, kinds: unknown[]) {
  server.use(
    manifestHandler([]),
    http.get('*/api/v1/clusters/c1', () => HttpResponse.json({ id: 'c1', name: 'c1', system, kinds })),
  );
}

describe('ClusterLanding', () => {
  it('opens the topology of an Artemis cluster', async () => {
    cluster(ARTEMIS_SYSTEM, []);
    renderWithProviders(<ClusterLanding />);
    expect(await screen.findByText('goes to /clusters/c1/topology')).toBeInTheDocument();
  });

  it("opens a Kafka cluster on a view its system has, never the topology's management URLs", async () => {
    cluster(KAFKA_SYSTEM, KAFKA_KINDS);
    renderWithProviders(<ClusterLanding />);
    expect(await screen.findByText('goes to /clusters/c1/kafka/brokers')).toBeInTheDocument();
  });
});
