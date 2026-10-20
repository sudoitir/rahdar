import { describe, expect, it, vi } from 'vitest';
import { http, HttpResponse } from 'msw';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

import { renderWithProviders } from '../../test/render.tsx';
import { server } from '../../test/setup.ts';

let currentSearch: Record<string, unknown> = {};
const navigateSpy = vi.fn();

vi.mock('@tanstack/react-router', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@tanstack/react-router')>()),
  useParams: () => ({ clusterId: 'c1' }),
  useSearch: () => currentSearch,
  useNavigate: () => navigateSpy,
}));

const { MetricsView } = await import('./MetricsView.tsx');

function series(metric: string, kind: string, points: { ts: string; value: number; peak?: number }[]) {
  return { metric, kind, unit: kind === 'GAUGE' ? 'count' : 'msg/s', points };
}

describe('MetricsView', () => {
  it('reports a failed read rather than an absence of samples', async () => {
    currentSearch = { range: '1h' };
    server.use(
      http.get('*/api/v1/clusters/c1/metrics', () =>
        HttpResponse.json({ title: 'Upstream unavailable', detail: 'the broker did not answer' }, { status: 502 }),
      ),
      http.get('*/api/v1/clusters/c1/rr/stats', () => HttpResponse.json({ addresses: [] })),
    );

    renderWithProviders(<MetricsView />);

    expect(await screen.findAllByText(/did not answer/)).not.toHaveLength(0);
    expect(screen.queryByText(/No depth samples/)).not.toBeInTheDocument();
  });

  it('states the current values, and says so when one was never sampled', async () => {
    currentSearch = { range: '1h' };
    server.use(
      http.get('*/api/v1/clusters/c1/metrics', () =>
        HttpResponse.json({
          from: '2026-09-04T09:00:00.000Z',
          to: '2026-09-04T10:00:00.000Z',
          step: 'PT1M',
          truncated: false,
          series: [
            series('openbroker.backlog', 'GAUGE', [
              { ts: '2026-09-04T09:00:00.000Z', value: 10 },
              { ts: '2026-09-04T09:01:00.000Z', value: 4200 },
            ]),
            // consumerCount is absent entirely: not zero, not sampled.
          ],
        }),
      ),
      http.get('*/api/v1/clusters/c1/rr/stats', () => HttpResponse.json({ addresses: [] })),
    );

    renderWithProviders(<MetricsView />);

    // 4,200 messages, compacted — the digits do not fit an axis tick or a tile.
    const current = await screen.findByRole('group', { name: 'Current values' });
    expect(await within(current).findByText('4.2K')).toBeInTheDocument();
    // An unsampled metric is stated as unavailable, with why, and never rendered as zero.
    // Added, acked and consumers were never sampled: three figures, each saying so.
    expect(within(current).getAllByText('Unavailable')).toHaveLength(3);
    expect(within(current).getAllByText('Not sampled recently enough to state a value.')).toHaveLength(3);
  });

  it('is one page with a single h1, and keeps the window table behind a disclosure that says so', async () => {
    currentSearch = { range: '1h' };
    server.use(
      http.get('*/api/v1/clusters/c1/metrics', () =>
        HttpResponse.json({
          from: '2026-09-04T09:00:00.000Z',
          to: '2026-09-04T10:00:00.000Z',
          step: 'PT1M',
          truncated: false,
          series: [
            series('openbroker.backlog', 'GAUGE', [
              { ts: '2026-09-04T09:00:00.000Z', value: 10 },
              { ts: '2026-09-04T09:01:00.000Z', value: 4200 },
            ]),
          ],
        }),
      ),
      http.get('*/api/v1/clusters/c1/rr/stats', () => HttpResponse.json({ addresses: [] })),
    );
    const user = userEvent.setup();
    renderWithProviders(<MetricsView />);

    expect(await screen.findByRole('heading', { level: 1, name: 'Metrics' })).toBeInTheDocument();
    expect(screen.getAllByRole('heading', { level: 1 })).toHaveLength(1);
    expect(screen.getByRole('heading', { level: 2, name: 'This window as a table' })).toBeInTheDocument();
    expect(await screen.findByText('Each point is a 1m bucket.')).toBeInTheDocument();

    const toggle = screen.getByRole('button', { name: 'Show this window as a table' });
    expect(toggle).toHaveAttribute('aria-expanded', 'false');
    expect(screen.queryByRole('table', { name: 'Metric buckets' })).not.toBeInTheDocument();

    await user.click(toggle);
    const table = await screen.findByRole('table', { name: 'Metric buckets' });
    expect(screen.getByRole('button', { name: 'Hide the table' })).toHaveAttribute('aria-expanded', 'true');
    // Newest first; a bucket with no sample for a metric reads "—", never zero.
    const rows = within(table).getAllByRole('row');
    expect(rows).toHaveLength(3);
    expect(rows[1]).toHaveTextContent('4.2K');
    expect(rows[1]).toHaveTextContent('—');
  });

  it('says the window was adjusted, in the line that is always there', async () => {
    currentSearch = { range: '7d' };
    server.use(
      http.get('*/api/v1/clusters/c1/metrics', () =>
        HttpResponse.json({
          from: '2026-09-04T09:00:00.000Z',
          to: '2026-09-04T10:00:00.000Z',
          step: 'PT5M',
          truncated: true,
          series: [],
        }),
      ),
      http.get('*/api/v1/clusters/c1/rr/stats', () => HttpResponse.json({ addresses: [] })),
    );
    renderWithProviders(<MetricsView />);

    expect(screen.getByText('Reading the window…')).toBeInTheDocument();
    expect(
      await screen.findByText("Window adjusted to 5m buckets, the finest this cluster's retention allows."),
    ).toBeInTheDocument();
  });

  it('advances a relative window, and leaves an absolute one alone', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    const windows: Array<{ from: string; to: string }> = [];
    server.use(
      http.get('*/api/v1/clusters/c1/metrics', ({ request }) => {
        const url = new URL(request.url);
        windows.push({ from: url.searchParams.get('from')!, to: url.searchParams.get('to')! });
        return HttpResponse.json({
          from: url.searchParams.get('from'),
          to: url.searchParams.get('to'),
          step: 'PT1M',
          truncated: false,
          series: [],
        });
      }),
      http.get('*/api/v1/clusters/c1/rr/stats', () => HttpResponse.json({ addresses: [] })),
    );

    try {
      currentSearch = { range: '1h' };
      const { unmount } = renderWithProviders(<MetricsView />);
      await waitFor(() => expect(windows.length).toBeGreaterThan(0));
      const firstTo = windows[0].to;

      // One bucket (PT1M) later the window must have moved on by exactly one.
      await vi.advanceTimersByTimeAsync(61_000);
      await waitFor(() => expect(windows.at(-1)!.to).not.toBe(firstTo));
      expect(Date.parse(windows.at(-1)!.to) - Date.parse(firstTo)).toBe(60_000);
      unmount();

      windows.length = 0;
      currentSearch = { from: '2026-09-04T09:00:00.000Z', to: '2026-09-04T10:00:00.000Z' };
      renderWithProviders(<MetricsView />);
      await waitFor(() => expect(windows.length).toBeGreaterThan(0));
      await vi.advanceTimersByTimeAsync(300_000);
      expect(new Set(windows.map((w) => w.to)).size).toBe(1);
    } finally {
      vi.useRealTimers();
    }
  });

  it('lets the operator change the range, which updates the URL', async () => {
    currentSearch = { range: '1h' };
    server.use(
      http.get('*/api/v1/clusters/c1/metrics', () =>
        HttpResponse.json({
          from: '2026-09-04T09:00:00.000Z',
          to: '2026-09-04T10:00:00.000Z',
          step: 'PT1M',
          truncated: false,
          series: [],
        }),
      ),
      http.get('*/api/v1/clusters/c1/rr/stats', () => HttpResponse.json({ addresses: [] })),
    );

    const user = userEvent.setup();
    navigateSpy.mockClear();
    renderWithProviders(<MetricsView />);

    await user.click(await screen.findByRole('radio', { name: '6h' }));
    expect(navigateSpy).toHaveBeenCalledTimes(1);
    // The range is merged into whatever else the URL carries, so a queue scope
    // survives it; an absolute window is what choosing a relative range clears.
    const { search } = navigateSpy.mock.calls[0][0] as {
      search: (prev: Record<string, unknown>) => Record<string, unknown>;
    };
    expect(search({ subject: 'ORDERS', from: 'x', to: 'y' })).toEqual({
      subject: 'ORDERS',
      range: '6h',
      from: undefined,
      to: undefined,
    });
  });

  it('splits a queue by broker node on request, one chart per node, and says which node was not sampled', async () => {
    const urls: string[] = [];
    server.use(
      http.get('*/api/v1/clusters/c1/metrics', ({ request }) => {
        urls.push(request.url);
        const split = new URL(request.url).searchParams.get('splitBy') === 'NODE';
        const points = [{ ts: '2026-09-04T09:00:00.000Z', value: 30 }];
        return HttpResponse.json({
          from: '2026-09-04T09:00:00.000Z',
          to: '2026-09-04T10:00:00.000Z',
          step: 'PT1M',
          truncated: false,
          series: [series('openbroker.enqueued', 'RATE', [{ ts: '2026-09-04T09:00:00.000Z', value: 40 }])],
          ...(split
            ? {
                splitBy: 'NODE',
                byNode: [
                  {
                    nodeId: 'a',
                    nodeName: 'artemis-a',
                    sampled: true,
                    series: [series('openbroker.enqueued', 'RATE', points)],
                  },
                  { nodeId: 'b', nodeName: 'artemis-b', sampled: false, series: [] },
                ],
              }
            : {}),
        });
      }),
      http.get('*/api/v1/clusters/c1/rr/stats', () => HttpResponse.json({ addresses: [] })),
    );

    currentSearch = { range: '1h', subject: 'orders' };
    const { unmount } = renderWithProviders(<MetricsView />);
    await userEvent.click(await screen.findByRole('switch', { name: 'Break down by broker node' }));
    const call = navigateSpy.mock.calls.at(-1)?.[0] as { search: (prev: object) => Record<string, unknown> };
    expect(call.search({ subject: 'orders' })).toEqual({ subject: 'orders', split: 'node' });
    unmount();

    currentSearch = { range: '1h', subject: 'orders', split: 'node' };
    renderWithProviders(<MetricsView />);
    expect(await screen.findByRole('region', { name: 'History on artemis-a' })).toHaveTextContent('in 30 msg/s');
    expect(screen.getByRole('region', { name: 'History on artemis-b' })).toHaveTextContent(
      'Not sampled in this window',
    );
    expect(urls.at(-1)).toContain('splitBy=NODE');
  });

  it('offers no split for the whole cluster', async () => {
    currentSearch = { range: '1h', split: 'node' };
    server.use(
      http.get('*/api/v1/clusters/c1/metrics', ({ request }) => {
        expect(request.url).not.toContain('splitBy');
        return HttpResponse.json({
          from: '2026-09-04T09:00:00.000Z',
          to: '2026-09-04T10:00:00.000Z',
          step: 'PT1M',
          truncated: false,
          series: [],
        });
      }),
      http.get('*/api/v1/clusters/c1/rr/stats', () => HttpResponse.json({ addresses: [] })),
    );
    renderWithProviders(<MetricsView />);
    await screen.findByRole('heading', { name: 'Metrics' });
    expect(screen.queryByRole('switch', { name: 'Break down by broker node' })).not.toBeInTheDocument();
  });
});
