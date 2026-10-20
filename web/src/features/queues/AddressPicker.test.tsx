import { addressId, destinationIdentity, subscriptionIdentity } from '../../test/wire.ts';
import { describe, expect, it } from 'vitest';
import { http, HttpResponse } from 'msw';
import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

import { renderWithProviders } from '../../test/render.tsx';
import { server } from '../../test/setup.ts';
import { AddressPicker } from './AddressPicker.tsx';

function queue(over: Record<string, unknown> = {}) {
  return {
    ...subscriptionIdentity('orders.request', 'orders.request'),
    durable: true,
    totalMessageCount: 12,
    totalConsumerCount: 1,
    totalDeliveringCount: 0,
    totalScheduledCount: 0,
    nodesPresent: 3,
    nodesTotal: 3,
    perNode: [],
    ...over,
  };
}

function page(rows: ReturnType<typeof queue>[]) {
  return { data: rows, count: rows.length, page: 1, pageSize: 300 };
}

function Harness({ initial = '' }: { initial?: string }) {
  return <AddressPicker clusterId="c1" value={initial} onChange={() => {}} label="Request address" />;
}

describe('AddressPicker', () => {
  it('suggests the broker’s addresses with their type and depth', async () => {
    server.use(
      http.get('*/api/v1/clusters/c1/subscriptions', () =>
        HttpResponse.json(
          page([
            queue(),
            queue({ destination: addressId('orders.events'), nativeAttributes: { routingType: 'MULTICAST' } }),
          ]),
        ),
      ),
    );
    const user = userEvent.setup();
    renderWithProviders(<Harness />);

    await user.click(screen.getByRole('textbox', { name: /request address/i }));

    // The whole row is one accessible name, so a screen reader announces the
    // address with the two facts that distinguish a request queue from a reply one.
    expect(
      await screen.findByRole('option', { name: 'orders.request, anycast, 12 messages, on 3 of 3 nodes' }),
    ).toBeInTheDocument();
    expect(screen.getByRole('option', { name: /^orders\.events, multicast,/ })).toBeInTheDocument();
  });

  it('shows an address in full rather than truncating it to fit the field', async () => {
    // The option used to lay the address and its meta out as two columns inside a
    // 240px dropdown, so `flex: none` meta won and every long address rendered as
    // an ellipsis — useless for exactly the names this picker exists to tell apart.
    const long = 'orders.reply.responder-with-a-rather-long-node-name.v1';
    server.use(
      http.get('*/api/v1/clusters/c1/subscriptions', () =>
        HttpResponse.json(page([queue({ destination: addressId(long) })])),
      ),
    );
    const user = userEvent.setup();
    renderWithProviders(<Harness />);

    await user.click(screen.getByRole('textbox', { name: /request address/i }));

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
            queue(),
            queue({ destination: addressId('orders.events'), nativeAttributes: { routingType: 'MULTICAST' } }),
          ]),
        ),
      ),
    );
    const user = userEvent.setup();
    renderWithProviders(<Harness />);

    await user.click(screen.getByRole('textbox', { name: /request address/i }));
    await screen.findByRole('option', { name: /orders\.events/ });

    await user.click(screen.getByRole('checkbox', { name: 'multicast' }));

    await waitFor(() => expect(screen.queryByRole('option', { name: /orders\.request/ })).not.toBeInTheDocument());
    expect(screen.getByRole('option', { name: /orders\.events/ })).toBeInTheDocument();
  });

  it('says so when the typed name matches nothing, without blocking it', async () => {
    server.use(http.get('*/api/v1/clusters/c1/subscriptions', () => HttpResponse.json(page([]))));
    renderWithProviders(
      <AddressPicker
        clusterId="c1"
        value="not.a.real.address"
        onChange={() => {}}
        label="Request address"
        unknownHint="No address on this cluster has that name yet."
      />,
    );

    expect(await screen.findByText('No address on this cluster has that name yet.')).toBeInTheDocument();
    expect(screen.getByRole('textbox', { name: /request address/i })).toHaveValue('not.a.real.address');
  });

  it('stays usable when the queue list cannot be read', async () => {
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

    await user.click(screen.getByRole('textbox', { name: /request address/i }));

    expect(await screen.findByText(/type an address by hand/i)).toBeInTheDocument();
  });

  describe('offering only the addresses the caller holds a permission on', () => {
    function address(name: string, allowedActions: string[]) {
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

    function serveAddresses() {
      server.use(
        http.get('*/api/v1/clusters/c1/subscriptions', () =>
          HttpResponse.json(
            page([queue({ destination: addressId('orders.in') }), queue({ destination: addressId('billing.in') })]),
          ),
        ),
        http.get('*/api/v1/clusters/c1/destinations', () =>
          HttpResponse.json({
            data: [address('orders.in', ['message:send']), address('billing.in', ['destination:read'])],
            count: 2,
            page: 1,
            pageSize: 300,
          }),
        ),
      );
    }

    it('lists the addresses that allow it, and leaves out the ones that do not', async () => {
      serveAddresses();
      const user = userEvent.setup();
      renderWithProviders(
        <AddressPicker clusterId="c1" value="" onChange={() => {}} label="Target" permission="message:send" />,
      );

      await user.click(screen.getByRole('textbox', { name: /target/i }));

      expect(await screen.findByRole('option', { name: /^orders\.in,/ })).toBeInTheDocument();
      await waitFor(() => expect(screen.queryByRole('option', { name: /^billing\.in,/ })).not.toBeInTheDocument());
    });

    it('says so when the typed name is an address the caller may not use', async () => {
      serveAddresses();
      renderWithProviders(
        <AddressPicker
          clusterId="c1"
          value="billing.in"
          onChange={() => {}}
          label="Target"
          permission="message:send"
        />,
      );

      expect(await screen.findByText('You do not hold message:send on the address billing.in.')).toBeInTheDocument();
    });

    it('offers every address when no permission is asked for', async () => {
      serveAddresses();
      const user = userEvent.setup();
      renderWithProviders(<AddressPicker clusterId="c1" value="" onChange={() => {}} label="Target" />);

      await user.click(screen.getByRole('textbox', { name: /target/i }));

      expect(await screen.findByRole('option', { name: /^billing\.in,/ })).toBeInTheDocument();
      expect(screen.getByRole('option', { name: /^orders\.in,/ })).toBeInTheDocument();
    });
  });
});
