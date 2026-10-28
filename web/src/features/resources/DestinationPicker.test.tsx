import { destinationId, destinationIdentity, subscriptionIdentity } from '../../test/wire.ts';
import { describe, expect, it } from 'vitest';
import { http, HttpResponse } from 'msw';
import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

import { renderWithProviders } from '../../test/render.tsx';
import { server } from '../../test/setup.ts';
import { DestinationPicker } from './DestinationPicker.tsx';

function subscription(over: Record<string, unknown> = {}) {
  return {
    ...subscriptionIdentity('orders.request', 'orders.request'),
    durable: true,
    backlog: { value: 12, accuracy: 'EXACT', reason: null, breakdown: false },
    totalConsumerCount: 1,
    totalDeliveringCount: 0,
    totalScheduledCount: 0,
    nodesPresent: 3,
    nodesTotal: 3,
    perNode: [],
    ...over,
  };
}

function page(rows: ReturnType<typeof subscription>[]) {
  return { data: rows, count: rows.length, page: 1, pageSize: 300 };
}

function Harness({ initial = '' }: { initial?: string }) {
  return <DestinationPicker clusterId="c1" value={initial} onChange={() => {}} label="Request destination" />;
}

describe('DestinationPicker', () => {
  it('suggests the broker’s destinations with their type and depth', async () => {
    server.use(
      http.get('*/api/v1/clusters/c1/subscriptions', () =>
        HttpResponse.json(
          page([
            subscription(),
            subscription({
              destination: destinationId('orders.events'),
              nativeAttributes: { routingType: 'MULTICAST' },
            }),
          ]),
        ),
      ),
    );
    const user = userEvent.setup();
    renderWithProviders(<Harness />);

    await user.click(screen.getByRole('textbox', { name: /request destination/i }));

    // The whole row is one accessible name, so a screen reader announces the
    // destination with the two facts that distinguish a request subscription from a reply one.
    expect(
      await screen.findByRole('option', { name: 'orders.request, anycast, 12 messages, on 3 of 3 nodes' }),
    ).toBeInTheDocument();
    expect(screen.getByRole('option', { name: /^orders\.events, multicast,/ })).toBeInTheDocument();
  });

  it('shows an destination in full rather than truncating it to fit the field', async () => {
    // The option used to lay the destination and its meta out as two columns inside a
    // 240px dropdown, so `flex: none` meta won and every long destination rendered as
    // an ellipsis — useless for exactly the names this picker exists to tell apart.
    const long = 'orders.reply.responder-with-a-rather-long-node-name.v1';
    server.use(
      http.get('*/api/v1/clusters/c1/subscriptions', () =>
        HttpResponse.json(page([subscription({ destination: destinationId(long) })])),
      ),
    );
    const user = userEvent.setup();
    renderWithProviders(<Harness />);

    await user.click(screen.getByRole('textbox', { name: /request destination/i }));

    const option = await screen.findByRole('option', { name: new RegExp(`^${long},`) });
    const name = option.querySelector(`[title="${long}"]`);
    expect(name).not.toBeNull();
    expect(name).toHaveTextContent(long);
  });

  it('filters the suggestions by routing type', async () => {
    server.use(
      http.get('*/api/v1/clusters/c1/subscriptions', () =>
        HttpResponse.json(
          page([
            subscription(),
            subscription({
              destination: destinationId('orders.events'),
              nativeAttributes: { routingType: 'MULTICAST' },
            }),
          ]),
        ),
      ),
    );
    const user = userEvent.setup();
    renderWithProviders(<Harness />);

    await user.click(screen.getByRole('textbox', { name: /request destination/i }));
    await screen.findByRole('option', { name: /orders\.events/ });

    await user.click(screen.getByRole('checkbox', { name: 'multicast' }));

    await waitFor(() => expect(screen.queryByRole('option', { name: /orders\.request/ })).not.toBeInTheDocument());
    expect(screen.getByRole('option', { name: /orders\.events/ })).toBeInTheDocument();
  });

  it('says so when the typed name matches nothing, without blocking it', async () => {
    server.use(http.get('*/api/v1/clusters/c1/subscriptions', () => HttpResponse.json(page([]))));
    renderWithProviders(
      <DestinationPicker
        clusterId="c1"
        value="not.a.real.destination"
        onChange={() => {}}
        label="Request destination"
        unknownHint="No destination on this cluster has that name yet."
      />,
    );

    expect(await screen.findByText('No destination on this cluster has that name yet.')).toBeInTheDocument();
    expect(screen.getByRole('textbox', { name: /request destination/i })).toHaveValue('not.a.real.destination');
  });

  it('stays usable when the subscription list cannot be read', async () => {
    server.use(
      http.get('*/api/v1/clusters/c1/subscriptions', () =>
        HttpResponse.json(
          { title: 'Unexpected broker response', detail: 'The broker is unreachable.' },
          { status: 502 },
        ),
      ),
    );
    const user = userEvent.setup();
    renderWithProviders(<Harness />);

    await user.click(screen.getByRole('textbox', { name: /request destination/i }));

    expect(await screen.findByText(/type an destination by hand/i)).toBeInTheDocument();
  });

  describe('offering only the destinations the caller holds a permission on', () => {
    function destination(name: string, allowedActions: string[]) {
      return {
        nodeId: 'n1',
        nodeName: 'node-a',
        ...destinationIdentity(name),
        routingTypes: 'ANYCAST',
        subscriptionCount: 1,
        messageCount: 0,
        allowedActions,
      };
    }

    function serveDestinations() {
      server.use(
        http.get('*/api/v1/clusters/c1/subscriptions', () =>
          HttpResponse.json(
            page([
              subscription({ destination: destinationId('orders.in') }),
              subscription({ destination: destinationId('billing.in') }),
            ]),
          ),
        ),
        http.get('*/api/v1/clusters/c1/destinations', () =>
          HttpResponse.json({
            data: [destination('orders.in', ['message:send']), destination('billing.in', ['destination:read'])],
            count: 2,
            page: 1,
            pageSize: 300,
          }),
        ),
      );
    }

    it('lists the destinations that allow it, and leaves out the ones that do not', async () => {
      serveDestinations();
      const user = userEvent.setup();
      renderWithProviders(
        <DestinationPicker clusterId="c1" value="" onChange={() => {}} label="Target" permission="message:send" />,
      );

      await user.click(screen.getByRole('textbox', { name: /target/i }));

      expect(await screen.findByRole('option', { name: /^orders\.in,/ })).toBeInTheDocument();
      await waitFor(() => expect(screen.queryByRole('option', { name: /^billing\.in,/ })).not.toBeInTheDocument());
    });

    it('says so when the typed name is an destination the caller may not use', async () => {
      serveDestinations();
      renderWithProviders(
        <DestinationPicker
          clusterId="c1"
          value="billing.in"
          onChange={() => {}}
          label="Target"
          permission="message:send"
        />,
      );

      expect(
        await screen.findByText('You do not hold message:send on the destination billing.in.'),
      ).toBeInTheDocument();
    });

    it('offers every destination when no permission is asked for', async () => {
      serveDestinations();
      const user = userEvent.setup();
      renderWithProviders(<DestinationPicker clusterId="c1" value="" onChange={() => {}} label="Target" />);

      await user.click(screen.getByRole('textbox', { name: /target/i }));

      expect(await screen.findByRole('option', { name: /^billing\.in,/ })).toBeInTheDocument();
      expect(screen.getByRole('option', { name: /^orders\.in,/ })).toBeInTheDocument();
    });
  });
});
