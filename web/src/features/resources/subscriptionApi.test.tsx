import { describe, expect, it } from 'vitest';
import { http, HttpResponse } from 'msw';
import { screen, waitFor } from '@testing-library/react';

import { renderWithProviders } from '../../test/render.tsx';
import { server } from '../../test/setup.ts';
import { useSubscriptions } from './api.ts';

function Probe({ clusterId }: { clusterId: string }) {
  const q = useSubscriptions(clusterId);
  return <div>{`${q.fetchStatus}/${q.isError ? 'error' : 'ok'}`}</div>;
}

/**
 * The command palette mounts on every route and has no cluster id outside one.
 * Before this guard it requested `/clusters//subscriptions`, a 400, and an errored
 * observed query puts the whole shell into its offline state — so every
 * cluster-less screen claimed Rahdar had lost contact with the brokers.
 */
describe('useSubscriptions without a cluster', () => {
  it('requests nothing, and reports no error', async () => {
    const seen: string[] = [];
    server.use(
      http.get('*/api/v1/clusters/*/subscriptions', ({ request }) => {
        seen.push(new URL(request.url).pathname);
        return HttpResponse.json({ data: [], page: 1, size: 50, count: 0, truncated: false });
      }),
    );

    renderWithProviders(<Probe clusterId="" />);

    await waitFor(() => expect(screen.getByText('idle/ok')).toBeInTheDocument());
    expect(seen).toEqual([]);
  });

  it('requests once it has one', async () => {
    const seen: string[] = [];
    server.use(
      http.get('*/api/v1/clusters/*/subscriptions', ({ request }) => {
        seen.push(new URL(request.url).pathname);
        return HttpResponse.json({ data: [], page: 1, size: 50, count: 0, truncated: false });
      }),
    );

    renderWithProviders(<Probe clusterId="c1" />);

    await waitFor(() => expect(seen).toEqual(['/api/v1/clusters/c1/subscriptions']));
  });
});
