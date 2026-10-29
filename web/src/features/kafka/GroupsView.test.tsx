import { describe, expect, it } from 'vitest';
import { http, HttpResponse } from 'msw';
import { screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

import { renderAppAt } from '../../test/render.tsx';
import { server } from '../../test/setup.ts';
import { mockKafkaCluster } from '../../test/kafkaCluster.ts';

const exact = (value: number) => ({ value, accuracy: 'EXACT', reason: null, breakdown: false });

function group(id: string, over: object = {}) {
  return { id, type: 'classic', state: 'STABLE', members: 3, lag: exact(42), readOnly: null, ...over };
}

describe('the consumer groups', () => {
  it('lists groups with state, members and a lag that says how sure it is', async () => {
    mockKafkaCluster();
    server.use(
      http.get('*/api/v1/clusters/k1/kafka/groups', () =>
        HttpResponse.json({
          items: [
            group('billing'),
            group('audit', {
              state: 'PREPARING_REBALANCE',
              lag: {
                value: 12400,
                accuracy: 'ESTIMATE',
                reason: 'Two partitions have no committed offset.',
                breakdown: false,
              },
            }),
            group('shares', {
              type: 'share',
              lag: { value: null, accuracy: 'UNAVAILABLE', reason: 'Share groups have no lag.', breakdown: false },
              readOnly: 'Share group offsets cannot be reset.',
            }),
          ],
          next: null,
        }),
      ),
    );
    renderAppAt('/clusters/k1/kafka/groups');

    const grid = await screen.findByRole('grid', { name: 'Consumer groups' });
    expect(await within(grid).findByRole('link', { name: 'billing' })).toHaveAttribute(
      'href',
      '/clusters/k1/kafka/groups/billing',
    );
    expect(within(grid).getByText('Rebalancing')).toBeInTheDocument();
    expect(within(grid).getByRole('button', { name: /^≈ 12.400.*estimate/ })).toBeInTheDocument();
    expect(within(grid).getByRole('button', { name: /^Unavailable.*why/ })).toBeInTheDocument();
    expect(within(grid).queryByText('0')).not.toBeInTheDocument();
  });

  it('reads the next page by cursor on request', async () => {
    mockKafkaCluster();
    const cursors: (string | null)[] = [];
    server.use(
      http.get('*/api/v1/clusters/k1/kafka/groups', ({ request }) => {
        const cursor = new URL(request.url).searchParams.get('cursor');
        cursors.push(cursor);
        return HttpResponse.json(
          cursor ? { items: [group('second')], next: null } : { items: [group('first')], next: 'c2' },
        );
      }),
    );
    const user = userEvent.setup();
    renderAppAt('/clusters/k1/kafka/groups');

    await screen.findByRole('link', { name: 'first' });
    await user.click(screen.getByRole('button', { name: 'Load more groups' }));
    expect(await screen.findByRole('link', { name: 'second' })).toBeInTheDocument();
    expect(cursors).toEqual([null, 'c2']);
  });

  it('teaches what a group is when there are none, and says when a filter matches none', async () => {
    mockKafkaCluster();
    server.use(http.get('*/api/v1/clusters/k1/kafka/groups', () => HttpResponse.json({ items: [], next: null })));
    renderAppAt('/clusters/k1/kafka/groups');
    expect(await screen.findByText('No consumer groups yet')).toBeInTheDocument();

    const { unmount } = renderAppAt('/clusters/k1/kafka/groups?state=empty');
    expect(await screen.findByText('No group matches this filter')).toBeInTheDocument();
    unmount();
  });

  it('says why the groups could not be read, and retries', async () => {
    mockKafkaCluster();
    let failing = true;
    server.use(
      http.get('*/api/v1/clusters/k1/kafka/groups', () =>
        failing
          ? HttpResponse.json({ title: 'Error', detail: 'The group coordinator is not available.' }, { status: 502 })
          : HttpResponse.json({ items: [group('billing')], next: null }),
      ),
    );
    const user = userEvent.setup();
    renderAppAt('/clusters/k1/kafka/groups');
    expect(await screen.findByText(/The group coordinator is not available/)).toBeInTheDocument();
    failing = false;
    await user.click(screen.getByRole('button', { name: /Try again|Retry/ }));
    expect(await screen.findByRole('link', { name: 'billing' })).toBeInTheDocument();
  });

  it('is not offered on a cluster of another system', async () => {
    mockKafkaCluster();
    server.use(
      http.get('*/api/v1/clusters/k1', () =>
        HttpResponse.json({
          id: 'k1',
          name: 'events',
          system: { id: 'artemis', name: 'Artemis' },
          kinds: [],
          topology: { clusterId: 'k1', nodes: [] },
          capabilities: {
            managementRead: { status: 'AVAILABLE', reason: 'ok' },
            managementWrite: { status: 'AVAILABLE', reason: 'ok' },
            notifications: { status: 'AVAILABLE', reason: 'ok' },
            messageIo: { status: 'AVAILABLE', reason: 'ok' },
            slowConsumerDetection: { status: 'AVAILABLE', reason: 'ok' },
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
    renderAppAt('/clusters/k1/kafka/groups');
    expect(await screen.findByRole('heading', { name: /not found|Page not found/i })).toBeInTheDocument();
  });
});
