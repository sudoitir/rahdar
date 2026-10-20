import { describe, expect, it } from 'vitest';
import { http, HttpResponse } from 'msw';
import { screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

import { renderWithProviders } from '../../test/render.tsx';
import { server } from '../../test/setup.ts';
import { LatencyPanel } from './LatencyPanel.tsx';

function address(name: string, over: Record<string, unknown> = {}) {
  return { destination: name, p50Ms: 20, p95Ms: 80, p99Ms: 120, coverageRatio: 0.5, ...over };
}

function serve(addresses: unknown[]) {
  server.use(http.get('*/api/v1/clusters/c1/rr/stats', () => HttpResponse.json({ destinations: addresses })));
}

describe('LatencyPanel', () => {
  it('says what a missing latency means rather than showing an empty chart', async () => {
    serve([]);
    renderWithProviders(<LatencyPanel clusterId="c1" />);

    expect(await screen.findByText('No completed flows yet')).toBeInTheDocument();
    expect(screen.getByText(/appears once at least one traced request has been answered/)).toBeInTheDocument();
  });

  it('holds its place with a labelled loading state while the stats load', () => {
    server.use(http.get('*/api/v1/clusters/c1/rr/stats', () => new Promise(() => {})));
    renderWithProviders(<LatencyPanel clusterId="c1" />);

    expect(screen.getByRole('status')).toHaveTextContent('Loading latency');
  });

  it('says latency could not be read, which is not the same as no flows, with a retry', async () => {
    server.use(http.get('*/api/v1/clusters/c1/rr/stats', () => HttpResponse.json({ title: 'Down' }, { status: 503 })));
    renderWithProviders(<LatencyPanel clusterId="c1" />);

    expect(await screen.findByText(/not the same as there being no traced flows/)).toBeInTheDocument();
    const alert = screen.getByRole('alert');
    expect(alert).toHaveTextContent('Rahdar failed to complete the request');
    expect(within(alert).getByRole('button', { name: 'Retry' })).toBeInTheDocument();
  });

  it('states the sampling caveat and each address’s coverage beside the chart', async () => {
    serve([address('orders.request'), address('billing.request', { coverageRatio: null })]);
    renderWithProviders(<LatencyPanel clusterId="c1" />);

    expect(await screen.findByRole('heading', { level: 3, name: 'Sampled, not exhaustive' })).toBeInTheDocument();
    expect(screen.getByText(/~50% of requests observed/)).toBeInTheDocument();
    expect(screen.getByText(/billing\.request: coverage unknown/)).toBeInTheDocument();
  });

  it('folds a long coverage list and unfolds it on request', async () => {
    serve(Array.from({ length: 8 }, (_, i) => address(`a.${i}`)));
    const user = userEvent.setup();
    renderWithProviders(<LatencyPanel clusterId="c1" />);

    const list = await screen.findByRole('list', { name: 'Coverage per address' });
    expect(within(list).getAllByRole('listitem')).toHaveLength(6);

    await user.click(screen.getByRole('button', { name: 'Show coverage for all 8 addresses' }));
    expect(within(list).getAllByRole('listitem')).toHaveLength(8);
    expect(screen.getByRole('button', { name: 'Show fewer' })).toHaveAttribute('aria-expanded', 'true');
  });
});
