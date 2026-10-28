import { describe, expect, it } from 'vitest';
import { http, HttpResponse } from 'msw';
import { screen, within } from '@testing-library/react';

import { renderWithProviders } from '../../test/render.tsx';
import { server } from '../../test/setup.ts';
import { BacklogDrawer } from './BacklogDrawer.tsx';

const TARGET = { clusterId: 'c1', id: 'kafka.group-topic/orders%2Fgroup/events', name: 'orders on events' };

describe('BacklogDrawer', () => {
  it('lists the partitions in the order sent, with the sample age and a lag that could not be read', async () => {
    let path = '';
    server.use(
      http.get('*/api/v1/clusters/c1/subscriptions/*', ({ request }) => {
        path = new URL(request.url).pathname;
        return HttpResponse.json({
          sampledAt: new Date(Date.now() - 12_000).toISOString(),
          partitions: [
            { partition: 2, committed: 100, end: 5100, lag: 5000, reason: null },
            { partition: 0, committed: 4000, end: 4100, lag: 100, reason: null },
            { partition: 1, committed: null, end: 900, lag: null, reason: 'The group never committed here.' },
          ],
        });
      }),
    );
    renderWithProviders(<BacklogDrawer target={TARGET} onClose={() => undefined} />);

    const table = await screen.findByRole('table', { name: 'orders on events backlog by partition' });
    const rows = within(table).getAllByRole('row').slice(1);
    expect(rows.map((r) => r.firstElementChild?.textContent)).toEqual(['2', '0', '1']);
    expect(within(rows[2]).getByText('Unavailable')).toBeInTheDocument();
    expect(within(rows[2]).getByText('The group never committed here.')).toBeInTheDocument();
    expect(screen.getByText(/Sampled/)).toHaveTextContent(/ago/);
    expect(path).toBe('/api/v1/clusters/c1/subscriptions/kafka.group-topic/orders%252Fgroup%2Fevents/backlog');
  });

  it('says why the breakdown could not be read', async () => {
    server.use(
      http.get('*/api/v1/clusters/c1/subscriptions/*', () =>
        HttpResponse.json({ title: 'Error', detail: 'The group coordinator did not answer.' }, { status: 502 }),
      ),
    );
    renderWithProviders(<BacklogDrawer target={TARGET} onClose={() => undefined} />);
    expect(await screen.findByText('The group coordinator did not answer.')).toBeInTheDocument();
  });

  it('renders nothing while no subscription is chosen', () => {
    renderWithProviders(<BacklogDrawer target={null} onClose={() => undefined} />);
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
  });
});
