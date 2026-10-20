import { describe, expect, it } from 'vitest';
import { http, HttpResponse } from 'msw';
import { screen } from '@testing-library/react';

import { renderWithProviders } from '../../test/render.tsx';
import { server } from '../../test/setup.ts';
import { SubscriptionHealthPanel } from './SubscriptionHealthPanel.tsx';

function row(over: Record<string, unknown> = {}) {
  return {
    destination: 'artemis.address/orders',
    subscription: 'artemis.queue/orders',
    verdict: 'STALLED',
    severity: 4,
    cause: 'The consumer is blocked, or a message it cannot process is being redelivered.',
    source: 'DERIVED',
    brokerConsumerName: null,
    depth: 5000,
    consumers: 3,
    delivering: 30,
    scheduled: 0,
    paused: false,
    depthSlopePerSecond: 1.5,
    addRate: 10,
    ackRate: 0,
    netRate: 10,
    ackRatePerConsumer: 0,
    drainEtaSeconds: null,
    asOf: '2026-09-20T10:00:00Z',
    sampleSpanSeconds: 30,
    stale: false,
    nodesPresent: 1,
    nodesTotal: 1,
    ...over,
  };
}

function serve(over: Record<string, unknown> = {}) {
  server.use(
    http.get('*/api/v1/clusters/c1/consumer-health', () =>
      HttpResponse.json({ data: [row(over)], count: 1, page: 1, pageSize: 1 }),
    ),
  );
}

describe('SubscriptionHealthPanel', () => {
  it('states the verdict and the evidence behind it', async () => {
    serve();

    renderWithProviders(
      <SubscriptionHealthPanel
        clusterId="c1"
        subscription={{ id: 'artemis.queue/orders', kind: 'artemis.queue', name: 'orders' }}
      />,
    );

    expect(await screen.findByText('Stalled')).toBeInTheDocument();
    expect(screen.getByText('Consumers are holding messages and acknowledging none')).toBeInTheDocument();
    // The evidence, not just the label.
    expect(screen.getByText('In flight')).toBeInTheDocument();
    expect(screen.getByText(/depth rising 1.50\/s/)).toBeInTheDocument();
  });

  it('credits the broker when the broker is what judged', async () => {
    serve({ verdict: 'BROKER_SLOW', source: 'BROKER', brokerConsumerName: 'consumer-7' });

    renderWithProviders(
      <SubscriptionHealthPanel
        clusterId="c1"
        subscription={{ id: 'artemis.queue/orders', kind: 'artemis.queue', name: 'orders' }}
      />,
    );

    expect(await screen.findByText('Slow consumer')).toBeInTheDocument();
    expect(screen.getByText(/consumer-7/)).toBeInTheDocument();
  });

  it('says the numbers cover only the reporting nodes', async () => {
    serve({ nodesPresent: 1, nodesTotal: 3 });

    renderWithProviders(
      <SubscriptionHealthPanel
        clusterId="c1"
        subscription={{ id: 'artemis.queue/orders', kind: 'artemis.queue', name: 'orders' }}
      />,
    );

    expect(await screen.findByText(/Present on 1 of 3 nodes/)).toBeInTheDocument();
  });

  it('says health is unavailable rather than showing nothing', async () => {
    server.use(
      http.get('*/api/v1/clusters/c1/consumer-health', () =>
        HttpResponse.json({ title: 'Upstream unavailable', detail: 'the database did not answer' }, { status: 502 }),
      ),
    );

    renderWithProviders(
      <SubscriptionHealthPanel
        clusterId="c1"
        subscription={{ id: 'artemis.queue/orders', kind: 'artemis.queue', name: 'orders' }}
      />,
    );

    expect(await screen.findByText(/Consumer health is unavailable/)).toBeInTheDocument();
    expect(screen.getByRole('alert')).toHaveTextContent('Rahdar failed to complete the request');
    expect(screen.getByRole('button', { name: 'Retry' })).toBeInTheDocument();
  });
});
