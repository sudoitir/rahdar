import { describe, expect, it, vi } from 'vitest';
import { http, HttpResponse } from 'msw';
import { screen, waitFor } from '@testing-library/react';

import { renderWithProviders } from '../../test/render.tsx';
import { holding } from '../../test/access.ts';
import { server } from '../../test/setup.ts';
import { paged } from '../../kernel/api/paging.ts';
import { ARTEMIS_SYSTEM, KAFKA_SYSTEM } from '../../test/wire.ts';

// A plain anchor: the router is not under test.
vi.mock('@tanstack/react-router', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@tanstack/react-router')>()),
  Link: ({ children, to }: { children: React.ReactNode; to: string }) => <a href={to}>{children}</a>,
}));

const { ClusterHeader } = await import('./ClusterHeader.tsx');

const capability = { status: 'AVAILABLE', reason: null, brokerXmlSnippet: null };

function mockCluster(over: object = {}) {
  server.use(
    holding('environment:read'),
    http.get('*/api/v1/environments', () =>
      HttpResponse.json(paged([{ id: 'e1', name: 'Production', colour: '#d6336c', sortOrder: 1 }])),
    ),
    http.get('*/api/v1/clusters/c1', () =>
      HttpResponse.json({
        id: 'c1',
        name: 'prod-eu',
        system: ARTEMIS_SYSTEM,
        environmentId: 'e1',
        topology: { nodes: [{ endpoints: [{}, {}] }] },
        health: { level: 'OK', splitBrain: 'NONE', notes: [], credentialRejections: [] },
        capabilities: {
          managementRead: capability,
          managementWrite: capability,
          messageIo: capability,
          notifications: capability,
        },
        ...over,
      }),
    ),
  );
}

describe('ClusterHeader', () => {
  it('is context with no heading: the environment and what is known of the cluster, not its name again', async () => {
    mockCluster();
    renderWithProviders(<ClusterHeader clusterId="c1" />);

    const strip = await screen.findByRole('group', { name: 'Cluster prod-eu' });
    // The breadcrumb beside it already names the cluster.
    expect(strip).not.toHaveTextContent('prod-eu');
    await waitFor(() => expect(strip).toHaveTextContent('Production'));
    expect(strip).toHaveTextContent('2 nodes · replication · reachable');
    // Each view's page header is the page's one h1.
    expect(screen.queryAllByRole('heading')).toHaveLength(0);
  });

  it('does not call a Kafka cluster standalone: it replicates per partition, not in live and backup pairs', async () => {
    mockCluster({
      system: KAFKA_SYSTEM,
      topology: { nodes: [{ endpoints: [{}] }, { endpoints: [{}] }, { endpoints: [{}] }] },
    });
    renderWithProviders(<ClusterHeader clusterId="c1" />);

    const strip = await screen.findByRole('group', { name: 'Cluster prod-eu' });
    await waitFor(() => expect(strip).toHaveTextContent('3 nodes · reachable'));
    expect(strip).not.toHaveTextContent('standalone');
  });

  it('leaves the environment out for a cluster that has none', async () => {
    mockCluster({ environmentId: null });
    renderWithProviders(<ClusterHeader clusterId="c1" />);

    const strip = await screen.findByRole('group', { name: 'Cluster prod-eu' });
    expect(strip).not.toHaveTextContent('Production');
  });

  it('announces a split brain as an alert and lists what is wrong', async () => {
    mockCluster({
      health: {
        level: 'CRITICAL',
        splitBrain: 'CRITICAL',
        notes: ['Both nodes are serving'],
        credentialRejections: [],
      },
    });
    renderWithProviders(<ClusterHeader clusterId="c1" />);

    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent('Two nodes are live in one pair');
    expect(alert).toHaveTextContent('Both nodes are serving');
  });

  it('names the account the brokers rejected and links to the connection settings', async () => {
    mockCluster({
      health: {
        level: 'DEGRADED',
        splitBrain: 'NONE',
        notes: [
          "The brokers rejected the management account on every node. Update it in the cluster's Connection settings.",
        ],
        credentialRejections: [{ account: 'MANAGEMENT', nodeNames: ['broker-1', 'broker-2'] }],
      },
    });
    renderWithProviders(<ClusterHeader clusterId="c1" />);

    expect(await screen.findByText(/rejected the management account on every node/)).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Open the connection settings' })).toHaveAttribute(
      'href',
      '/clusters/c1/settings?tab=clusters-connection',
    );
  });

  it('offers no link to the settings when nothing was rejected', async () => {
    mockCluster({
      health: {
        level: 'CRITICAL',
        splitBrain: 'CRITICAL',
        notes: ['Both nodes are serving'],
        credentialRejections: [],
      },
    });
    renderWithProviders(<ClusterHeader clusterId="c1" />);

    await screen.findByRole('alert');
    expect(screen.queryByRole('link')).toBeNull();
  });

  it('names its cause when the cluster cannot be read', async () => {
    server.use(
      http.get('*/api/v1/environments', () => HttpResponse.json(paged([]))),
      http.get('*/api/v1/clusters/c1', () => HttpResponse.json({ title: 'Forbidden', status: 403 }, { status: 403 })),
    );
    renderWithProviders(<ClusterHeader clusterId="c1" />);

    expect(await screen.findByRole('alert')).toHaveTextContent('You are not allowed to do this');
  });
});
