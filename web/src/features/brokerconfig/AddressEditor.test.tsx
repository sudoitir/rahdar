import { describe, expect, it, vi } from 'vitest';
import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';

import { renderWithProviders } from '../../test/render.tsx';
import { server } from '../../test/setup.ts';
import { notify } from '../../ui/notify.ts';
import type { ConfigAddressView } from './api.ts';
import { AddressEditor } from './AddressEditor.tsx';
import { declaration } from './fixtures.ts';

const DECLARED = declaration();
const ORDERS = DECLARED.document.addresses[0]!;

/** Records the body of the one PUT the editor sends, and answers it as the server would. */
function captureSave() {
  const saved = vi.fn();
  server.use(
    http.put('*/api/v1/clusters/c1/artemis/config', async ({ request }) => {
      saved(await request.json());
      return HttpResponse.json(DECLARED);
    }),
  );
  return saved;
}

function open(item: ConfigAddressView | null, prefill?: Partial<ConfigAddressView>) {
  const onClose = vi.fn();
  renderWithProviders(<AddressEditor declaration={DECLARED} item={item} prefill={prefill} opened onClose={onClose} />);
  return { onClose, user: userEvent.setup() };
}

const SAVE = 'Save as revision 4';

describe('AddressEditor', () => {
  it('refuses an empty submit, says why beside the field and in the footer, and focuses the name', async () => {
    const saved = captureSave();
    const { user } = open(null);

    await user.click(await screen.findByRole('button', { name: SAVE }));

    expect(await screen.findByText('An address name is required.')).toBeInTheDocument();
    expect(screen.getByText('Fix the fields above to continue.')).toBeInTheDocument();
    expect(screen.getByRole('textbox', { name: /Address/ })).toHaveFocus();
    expect(saved).not.toHaveBeenCalled();
  });

  it('validates the name on blur, before any submit, and refuses one already declared', async () => {
    const { user } = open(null);

    const name = await screen.findByRole('textbox', { name: /Address/ });
    await user.click(name);
    await user.tab();
    expect(await screen.findByText('An address name is required.')).toBeInTheDocument();

    await user.type(name, 'orders.request');
    expect(screen.getByText('"orders.request" is already declared. Edit that address instead.')).toBeInTheDocument();
  });

  it('adds a new address with a queue, trimming names and dropping a blank filter, then closes', async () => {
    const saved = captureSave();
    const { user, onClose } = open(null);

    await user.type(await screen.findByRole('textbox', { name: /Address/ }), '  billing  ');
    expect(screen.getByText(/No queues declared on this address/)).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Add queue' }));
    expect(screen.queryByText(/No queues declared on this address/)).not.toBeInTheDocument();
    await user.type(screen.getByRole('textbox', { name: 'Queue name' }), ' billing.in ');
    await user.click(screen.getByRole('switch', { name: 'Durable' }));
    await user.click(screen.getByRole('button', { name: SAVE }));

    await vi.waitFor(() => expect(onClose).toHaveBeenCalled());
    const body = saved.mock.calls[0]![0];
    expect(body.expectedRevision).toBe(3);
    expect(body.note).toBe('Added address billing');
    expect(body.document.addresses).toEqual([
      ORDERS,
      {
        name: 'billing',
        routingTypes: ['ANYCAST'],
        queues: [{ name: 'billing.in', routingType: 'ANYCAST', durable: false, filter: null }],
      },
    ]);
  });

  it('needs at least one routing type, and a queue named, unique and of a supported type', async () => {
    const saved = captureSave();
    const { user } = open(null, {
      name: 'events',
      routingTypes: ['ANYCAST', 'MULTICAST'],
      queues: [{ name: 'events.a', routingType: 'MULTICAST', durable: true }],
    });

    // Prefilled from the builder's "Add queue".
    expect(await screen.findByRole('textbox', { name: /Address/ })).toHaveValue('events');
    expect(screen.getByRole('textbox', { name: 'Queue name' })).toHaveValue('events.a');

    await user.click(screen.getByRole('checkbox', { name: 'Multicast' }));
    await user.click(screen.getByRole('button', { name: SAVE }));
    expect(await screen.findByText("A queue's routing type must be one the address supports.")).toBeInTheDocument();

    await user.click(screen.getByRole('checkbox', { name: 'Anycast' }));
    expect(screen.getByText('An address has at least one routing type.')).toBeInTheDocument();

    await user.click(screen.getByRole('checkbox', { name: 'Anycast' }));
    await user.click(screen.getByRole('button', { name: 'Add queue' }));
    expect(screen.getByText('Every queue needs a name.')).toBeInTheDocument();

    const names = screen.getAllByRole('textbox', { name: 'Queue name' });
    await user.type(names[1]!, 'events.a');
    expect(screen.getByText('Queue names must be unique on an address.')).toBeInTheDocument();
    expect(saved).not.toHaveBeenCalled();
  });

  it('changes a queue routing type and removes a queue from the declaration only', async () => {
    const saved = captureSave();
    const { user } = open(null, {
      name: 'events',
      routingTypes: ['ANYCAST', 'MULTICAST'],
      queues: [
        { name: 'events.a', routingType: 'ANYCAST', durable: true },
        { name: 'events.b', routingType: 'ANYCAST', durable: true },
      ],
    });

    await screen.findByRole('textbox', { name: /Address/ });
    await user.click(screen.getAllByRole('combobox', { name: 'Routing' })[1]!);
    // Only the open list is accessible, and it belongs to events.b.
    await user.click(await screen.findByRole('option', { name: 'MULTICAST' }));
    await user.click(screen.getByRole('button', { name: 'Remove queue events.a from the declaration' }));
    expect(screen.getByRole('textbox', { name: 'Queue name' })).toHaveValue('events.b');
    await user.click(screen.getByRole('button', { name: SAVE }));

    await vi.waitFor(() => expect(saved).toHaveBeenCalled());
    expect(saved.mock.calls[0]![0].document.addresses[1].queues).toEqual([
      { name: 'events.b', routingType: 'MULTICAST', durable: true, filter: null },
    ]);
  });

  it('edits a declared address in place, allowing its own name', async () => {
    const saved = captureSave();
    const { user } = open(ORDERS);

    expect(await screen.findByText('Address orders.request')).toBeInTheDocument();
    await user.click(screen.getByRole('checkbox', { name: 'Multicast' }));
    await user.click(screen.getByRole('button', { name: SAVE }));

    await vi.waitFor(() => expect(saved).toHaveBeenCalled());
    const body = saved.mock.calls[0]![0];
    expect(body.note).toBe('Edited address orders.request');
    expect(body.document.addresses).toEqual([
      {
        ...ORDERS,
        routingTypes: ['ANYCAST', 'MULTICAST'],
        queues: [{ ...ORDERS.queues[0], filter: null }],
      },
    ]);
  });

  it('removes an address from the declaration, saying nothing on the broker is deleted', async () => {
    const saved = captureSave();
    const { user, onClose } = open(ORDERS);

    const remove = await screen.findByRole('button', { name: 'Remove from declaration' });
    expect(remove).toHaveAttribute('title', 'Stops Rahdar checking for it. Nothing on the broker is deleted.');
    await user.click(remove);

    await vi.waitFor(() => expect(onClose).toHaveBeenCalled());
    const body = saved.mock.calls[0]![0];
    expect(body.note).toBe('Removed address orders.request from the declaration');
    expect(body.document.addresses).toEqual([]);
  });

  it('states a stale revision with what to do next, and Cancel closes without saving', async () => {
    server.use(
      http.put('*/api/v1/clusters/c1/artemis/config', () =>
        HttpResponse.json(
          { type: 'urn:rahdar:stale-revision', title: 'Declaration changed', detail: 'Revision 4 exists.' },
          { status: 409 },
        ),
      ),
    );
    const { user, onClose } = open(ORDERS);

    await user.click(await screen.findByRole('button', { name: SAVE }));
    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent('This conflicts with the current state');
    expect(alert).toHaveTextContent('Revision 4 exists.');
    expect(screen.getByText(/Reload the declaration and make this edit again/)).toBeInTheDocument();
    expect(onClose).not.toHaveBeenCalled();

    await user.click(screen.getByRole('button', { name: 'Cancel' }));
    expect(onClose).toHaveBeenCalledTimes(1);
  });

  it('announces the saved revision with what was done, because the editor closes and focus moves', async () => {
    captureSave();
    const succeeded = vi.spyOn(notify, 'succeeded');
    const { user, onClose } = open(null);

    await user.type(await screen.findByRole('textbox', { name: /Address/ }), 'orders.new');
    await user.click(screen.getByRole('button', { name: SAVE }));

    await vi.waitFor(() => expect(onClose).toHaveBeenCalledTimes(1));
    expect(succeeded).toHaveBeenCalledWith(
      expect.objectContaining({
        action: expect.objectContaining({ past: 'Saved' }),
        subject: expect.stringContaining('Added address orders.new'),
      }),
    );
    succeeded.mockRestore();
  });

  it('keeps the save control busy while the request runs, and re-enables it when it fails', async () => {
    server.use(
      http.put('*/api/v1/clusters/c1/artemis/config', async () => {
        await new Promise((r) => setTimeout(r, 100));
        return HttpResponse.json({ title: 'Down', detail: 'Store down.' }, { status: 503 });
      }),
    );
    const { user } = open(ORDERS);

    await user.click(await screen.findByRole('button', { name: SAVE }));
    expect(screen.getByRole('button', { name: SAVE })).toHaveAttribute('data-loading', 'true');

    expect(await screen.findByRole('alert')).toHaveTextContent('Store down.');
    expect(screen.getByRole('button', { name: SAVE })).not.toHaveAttribute('data-loading');
  });
});
