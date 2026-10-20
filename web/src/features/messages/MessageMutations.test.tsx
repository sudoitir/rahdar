import { afterEach, describe, expect, it, vi } from 'vitest';
import { http, HttpResponse } from 'msw';
import { act, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { Notifications, notifications } from '@mantine/notifications';

import { renderWithProviders } from '../../test/render.tsx';
import { server } from '../../test/setup.ts';
import { holdButton } from '../../test/hold.ts';
import { ARTEMIS_KINDS } from '../../test/wire.ts';

vi.mock('@tanstack/react-router', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@tanstack/react-router')>()),
  useParams: () => ({ clusterId: 'c1', queueName: 'ORDERS' }),
  useNavigate: () => () => {},
}));

const { BulkActionPreview } = await import('./BulkActionPreview.tsx');
const { MessageActions } = await import('./MessageActions.tsx');
const { PurgeQueue } = await import('./PurgeQueue.tsx');
const { SendMessage } = await import('./SendMessage.tsx');

afterEach(() => act(() => notifications.clean()));

const AVAILABLE = { status: 'AVAILABLE', reason: 'ok', brokerXmlSnippet: null };

/** Who the caller is, and a cluster whose broker accepts management writes and message I/O. */
function signedInWith(permissions: string[]) {
  server.use(
    http.get('*/api/v1/auth/me', () =>
      HttpResponse.json({
        id: 'u1',
        username: 'op',
        mustChangePassword: false,
        grants: [{ scopeType: 'GLOBAL', scopeId: null, permissions }],
      }),
    ),
    http.get('*/api/v1/clusters/c1', () =>
      HttpResponse.json({
        id: 'c1',
        name: 'prod',
        description: null,
        topology: { clusterId: 'c1', nodes: [] },
        kinds: ARTEMIS_KINDS,
        capabilities: {
          managementRead: AVAILABLE,
          managementWrite: AVAILABLE,
          notifications: AVAILABLE,
          messageIo: AVAILABLE,
        },
        health: {
          clusterId: 'c1',
          level: 'OK',
          liveEndpointNames: [],
          splitBrain: 'NONE',
          replicationBehind: false,
          notes: [],
        },
      }),
    ),
  );
}

/** A view that mounts the toast host the app mounts once at its root. */
function withToasts(ui: React.ReactElement) {
  return (
    <>
      <Notifications />
      {ui}
    </>
  );
}

describe('BulkActionPreview', () => {
  const open = (action: 'delete' | 'move' | 'retry') =>
    renderWithProviders(
      <BulkActionPreview
        clusterId="c1"
        queueName="ORDERS"
        action={action}
        opened
        onClose={() => {}}
        onDone={() => {}}
      />,
    );

  it('gates "Run anyway" behind the typed queue name when over cap', async () => {
    server.use(
      http.post('*/api/v1/clusters/c1/resources/artemis.queue/ORDERS/messages:delete', () =>
        HttpResponse.json({ affectedCount: 50, cap: 10, overCap: true, node: 'n1' }),
      ),
    );
    const user = userEvent.setup();
    open('delete');

    await user.type(screen.getByLabelText('Selector'), "region = 'eu'");
    await user.click(screen.getByRole('button', { name: 'Preview' }));

    expect(await screen.findByText(/≈ 50 messages/)).toBeInTheDocument();
    const runAnyway = screen.getByRole('button', { name: /Delete 50 messages anyway/i });

    expect(runAnyway).toBeEnabled();
  });

  it('says what is missing beside the field, and takes no preview, when Preview is pressed on an empty form', async () => {
    const requests: string[] = [];
    server.use(
      http.post('*/api/v1/clusters/c1/resources/artemis.queue/ORDERS/messages:move', ({ request }) => {
        requests.push(request.url);
        return HttpResponse.json({ affectedCount: 1, cap: 10, overCap: false, node: 'n1' });
      }),
    );
    const user = userEvent.setup();
    open('move');

    // Never disabled with no reason: the button is pressable and the reason appears beside the field.
    await user.click(screen.getByRole('button', { name: 'Preview' }));
    const selector = screen.getByLabelText('Selector');
    expect(selector).toBeInvalid();
    expect(screen.getByText('Enter the selector the messages must match.')).toBeInTheDocument();
    expect(selector).toHaveFocus();

    await user.type(selector, 'a = 1');
    await user.click(screen.getByRole('button', { name: 'Preview' }));
    expect(screen.getByLabelText('Target queue')).toBeInvalid();
    expect(screen.getByLabelText('Target queue')).toHaveFocus();
    expect(requests).toHaveLength(0);
  });

  it('arms a delete under the cap only once the queue name is typed, and announces what it deleted', async () => {
    const bodies: string[] = [];
    server.use(
      http.post('*/api/v1/clusters/c1/resources/artemis.queue/ORDERS/messages:delete', ({ request }) => {
        const dry = new URL(request.url).searchParams.get('dryRun') === 'true';
        bodies.push(dry ? 'preview' : 'run');
        return HttpResponse.json(
          dry ? { affectedCount: 3, cap: 10, overCap: false, node: 'n1' } : { affectedCount: 3, node: 'n1' },
        );
      }),
    );
    const user = userEvent.setup();
    renderWithProviders(
      withToasts(
        <BulkActionPreview
          clusterId="c1"
          queueName="ORDERS"
          action="delete"
          opened
          onClose={() => {}}
          onDone={() => {}}
        />,
      ),
    );

    await user.type(screen.getByLabelText('Selector'), 'a = 1');
    await user.click(screen.getByRole('button', { name: 'Preview' }));
    const confirm = await screen.findByRole('button', { name: 'Delete 3 messages' });
    await holdButton(confirm);

    expect(await screen.findByText('Deleted 3 messages in queue "ORDERS"')).toBeInTheDocument();
    expect(bodies).toEqual(['preview', 'run']);
  });

  it('replays without typing a name, because a retry destroys nothing', async () => {
    server.use(
      http.post('*/api/v1/clusters/c1/resources/artemis.queue/ORDERS/messages:retry', () =>
        HttpResponse.json({ affectedCount: 5, cap: 10, overCap: false, node: 'n1' }),
      ),
    );
    const user = userEvent.setup();
    open('retry');

    await user.click(screen.getByRole('button', { name: 'Preview' }));

    expect(await screen.findByRole('button', { name: 'Retry 5 messages' })).toBeEnabled();
    expect(screen.queryByLabelText(/Type "ORDERS" to confirm/)).not.toBeInTheDocument();
  });
});

describe('MessageActions', () => {
  const selectTwo = () => (
    <MessageActions clusterId="c1" queueName="ORDERS" selected={new Set(['1', '2'])} onCleared={() => {}} />
  );

  it('reports a by-id operation that stopped part-way, naming the ids left undone', async () => {
    signedInWith(['*']);
    server.use(
      http.post('*/api/v1/clusters/c1/resources/artemis.queue/ORDERS/messages:delete', () =>
        HttpResponse.json({
          affectedCount: 1,
          notDone: [2],
          error: 'The broker stopped answering.',
          partial: true,
        }),
      ),
    );
    const user = userEvent.setup();
    renderWithProviders(withToasts(selectTwo()));

    await user.click(await screen.findByRole('button', { name: 'Delete' }));
    const dialog = await screen.findByRole('dialog', { name: 'Delete 2 messages' });
    await holdButton(within(dialog).getByRole('button', { name: 'Delete 2 messages' }));

    // Partial is announced assertively and stays until dismissed, with the ids still where they were.
    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent('Deleted 1 of 2 messages on queue "ORDERS"');
    expect(alert).toHaveTextContent(/The broker stopped answering\. Not deleted: 2\./);
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
  });

  it('keeps the dialog open and states the cause and the next step when the operation fails', async () => {
    signedInWith(['*']);
    server.use(
      http.post('*/api/v1/clusters/c1/resources/artemis.queue/ORDERS/messages:expire', () =>
        HttpResponse.json({ title: 'Broker refused', detail: 'The node is read-only.' }, { status: 409 }),
      ),
    );
    const user = userEvent.setup();
    renderWithProviders(withToasts(selectTwo()));

    await user.click(await screen.findByRole('button', { name: 'Expire' }));
    const dialog = await screen.findByRole('dialog', { name: 'Expire 2 messages' });
    await holdButton(within(dialog).getByRole('button', { name: 'Expire 2 messages' }));

    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent('Could not expire 2 messages in queue "ORDERS"');
    expect(alert).toHaveTextContent('The node is read-only. Check the audit log for what ran, then try again.');
    expect(screen.getByRole('dialog', { name: 'Expire 2 messages' })).toBeInTheDocument();
  });

  it('asks for the target queue on the field when a move is confirmed without one', async () => {
    signedInWith(['*']);
    const requests: string[] = [];
    server.use(
      http.post('*/api/v1/clusters/c1/resources/artemis.queue/ORDERS/messages:move', ({ request }) => {
        requests.push(request.url);
        return HttpResponse.json({ affectedCount: 2, node: 'n1' });
      }),
    );
    const user = userEvent.setup();
    renderWithProviders(withToasts(selectTwo()));

    await user.click(await screen.findByRole('button', { name: 'Move' }));
    const dialog = await screen.findByRole('dialog', { name: 'Move 2 messages' });
    // A move is not destructive, so no name is typed; it is armed, and refuses an empty target.
    expect(within(dialog).queryByLabelText(/Type "ORDERS" to confirm/)).not.toBeInTheDocument();
    await user.click(within(dialog).getByRole('button', { name: 'Move 2 messages' }));

    const target = within(dialog).getByLabelText('Target queue');
    expect(target).toBeInvalid();
    expect(within(dialog).getByText('Name the queue to move the messages to.')).toBeInTheDocument();
    expect(target).toHaveFocus();
    expect(requests).toHaveLength(0);

    await user.type(target, 'ARCHIVE');
    await user.click(within(dialog).getByRole('button', { name: 'Move 2 messages' }));
    expect(await screen.findByText('Moved 2 messages in queue "ORDERS"')).toBeInTheDocument();
  });

  it('keeps every action visible but disabled, with the reason, for an operator who may only read', async () => {
    signedInWith(['message:read']);
    const user = userEvent.setup();
    renderWithProviders(selectTwo());

    await waitFor(() => expect(screen.getByRole('button', { name: 'Delete' })).toBeDisabled());
    expect(screen.getByRole('button', { name: 'Move' })).toBeDisabled();

    await user.click(screen.getByRole('button', { name: 'Why deleting the selected messages is unavailable' }));
    expect(await screen.findByText(/You do not have the "Delete or expire messages" permission/)).toBeInTheDocument();
    await user.keyboard('{Escape}');

    // The selector menu keeps its items too; activating one explains instead of acting.
    await user.click(screen.getByRole('button', { name: 'By selector…' }));
    const item = await screen.findByRole('menuitem', { name: /^Delete by selector/ });
    expect(item).toHaveAttribute('aria-disabled', 'true');
    await user.click(item);
    const reason = await screen.findByRole('dialog', { name: 'Why delete by selector is unavailable' });
    expect(reason).toHaveTextContent('Delete or expire messages');
  });
});

describe('PurgeQueue', () => {
  const dry = (affectedCount: number) => HttpResponse.json({ affectedCount, cap: 10, overCap: false, node: 'n1' });

  it('states the estimate, arms on the typed name, and announces what it purged', async () => {
    signedInWith(['*']);
    server.use(
      http.post('*/api/v1/clusters/c1/resources/artemis.queue/ORDERS:purge', ({ request }) =>
        new URL(request.url).searchParams.get('dryRun') === 'true'
          ? dry(4)
          : HttpResponse.json({ affectedCount: 4, node: 'n1' }),
      ),
    );
    const user = userEvent.setup();
    renderWithProviders(withToasts(<PurgeQueue clusterId="c1" queueName="ORDERS" />));

    await user.click(await screen.findByRole('button', { name: 'Purge queue' }));
    const dialog = await screen.findByRole('dialog', { name: 'Purge queue ORDERS' });
    expect(dialog).toHaveTextContent('approximately 4 messages');
    const confirm = within(dialog).getByRole('button', { name: 'Purge queue' });
    await holdButton(confirm);

    expect(await screen.findByText('Purged 4 messages in queue "ORDERS"')).toBeInTheDocument();
    await waitFor(() => expect(screen.queryByRole('dialog', { name: 'Purge queue ORDERS' })).not.toBeInTheDocument());
  });

  it('keeps the dialog open and states the cause when the purge itself fails', async () => {
    signedInWith(['*']);
    server.use(
      http.post('*/api/v1/clusters/c1/resources/artemis.queue/ORDERS:purge', ({ request }) =>
        new URL(request.url).searchParams.get('dryRun') === 'true'
          ? dry(4)
          : HttpResponse.json({ title: 'Broker refused', detail: 'The queue is being deleted.' }, { status: 409 }),
      ),
    );
    const user = userEvent.setup();
    renderWithProviders(withToasts(<PurgeQueue clusterId="c1" queueName="ORDERS" />));

    await user.click(await screen.findByRole('button', { name: 'Purge queue' }));
    const dialog = await screen.findByRole('dialog', { name: 'Purge queue ORDERS' });
    await holdButton(within(dialog).getByRole('button', { name: 'Purge queue' }));

    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent('Could not purge queue "ORDERS"');
    expect(alert).toHaveTextContent('The queue is being deleted.');
    expect(screen.getByRole('dialog', { name: 'Purge queue ORDERS' })).toBeInTheDocument();
  });
});

describe('SendMessage', () => {
  const open = () =>
    renderWithProviders(withToasts(<SendMessage clusterId="c1" queueName="ORDERS" opened onClose={vi.fn()} />));

  it('names a property that has no key, on the field, and sends nothing', async () => {
    const posts: unknown[] = [];
    server.use(
      http.post('*/api/v1/clusters/c1/resources/artemis.queue/ORDERS/messages', async ({ request }) => {
        posts.push(await request.json());
        return HttpResponse.json({ affectedCount: 1, node: 'n1' });
      }),
    );
    const user = userEvent.setup();
    open();

    await user.click(screen.getByRole('button', { name: 'Add property' }));
    await user.click(screen.getByRole('button', { name: 'Send' }));

    const key = screen.getByLabelText('Key');
    expect(key).toBeInvalid();
    expect(screen.getByText('Name the property, or remove the row.')).toBeInTheDocument();
    expect(key).toHaveFocus();
    expect(posts).toHaveLength(0);
  });

  it('refuses two properties with one name', async () => {
    const user = userEvent.setup();
    open();

    await user.click(screen.getByRole('button', { name: 'Add property' }));
    await user.click(screen.getByRole('button', { name: 'Add property' }));
    const [first, second] = screen.getAllByLabelText('Key');
    await user.type(first, 'region');
    await user.type(second, 'region');
    await user.click(screen.getByRole('button', { name: 'Send' }));

    expect(screen.getByText('Another property is already named "region".')).toBeInTheDocument();
    expect(second).toHaveFocus();
  });

  it('sends the message with its trimmed property names and announces it', async () => {
    const posts: unknown[] = [];
    server.use(
      http.post('*/api/v1/clusters/c1/resources/artemis.queue/ORDERS/messages', async ({ request }) => {
        posts.push(await request.json());
        return HttpResponse.json({ affectedCount: 1, node: 'n1' });
      }),
    );
    const user = userEvent.setup();
    open();

    await user.type(screen.getByLabelText(/^Content/), 'hello');
    await user.click(screen.getByRole('button', { name: 'Add property' }));
    await user.type(screen.getByLabelText('Key'), ' region ');
    await user.type(screen.getByLabelText('Value'), 'eu');
    await user.click(screen.getByRole('button', { name: 'Send' }));

    expect(await screen.findByText('Sent a message to queue "ORDERS"')).toBeInTheDocument();
    expect(posts).toEqual([{ kind: 'TEXT', durable: true, body: 'hello', headers: {}, properties: { region: 'eu' } }]);
  });

  it('sends the bytes kind with a base64 body', async () => {
    const posts: unknown[] = [];
    server.use(
      http.post('*/api/v1/clusters/c1/resources/artemis.queue/ORDERS/messages', async ({ request }) => {
        posts.push(await request.json());
        return HttpResponse.json({ affectedCount: 1, node: 'n1' });
      }),
    );
    const user = userEvent.setup();
    open();

    await user.click(screen.getByRole('radio', { name: 'Bytes (base64)' }));
    await user.type(screen.getByLabelText(/^Content/), 'CQgH');
    await user.click(screen.getByRole('button', { name: 'Send' }));

    expect(await screen.findByText('Sent a message to queue "ORDERS"')).toBeInTheDocument();
    expect(posts).toEqual([{ kind: 'BYTES', durable: true, body: 'CQgH', headers: {}, properties: {} }]);
  });

  it('refuses a bytes body that is not base64, on the field, and sends nothing', async () => {
    const posts: unknown[] = [];
    server.use(
      http.post('*/api/v1/clusters/c1/resources/artemis.queue/ORDERS/messages', async ({ request }) => {
        posts.push(await request.json());
        return HttpResponse.json({ affectedCount: 1, node: 'n1' });
      }),
    );
    const user = userEvent.setup();
    open();

    await user.click(screen.getByRole('radio', { name: 'Bytes (base64)' }));
    await user.type(screen.getByLabelText(/^Content/), 'not base64!');
    await user.click(screen.getByRole('button', { name: 'Send' }));

    expect(screen.getByLabelText(/^Content/)).toBeInvalid();
    expect(screen.getByText('Enter the body as base64, or switch the body to text.')).toBeInTheDocument();
    expect(posts).toHaveLength(0);
  });

  it('states the cause and the next step when the broker refuses the message, and keeps what was typed', async () => {
    server.use(
      http.post('*/api/v1/clusters/c1/resources/artemis.queue/ORDERS/messages', () =>
        HttpResponse.json({ title: 'Broker refused', detail: 'The address is full.' }, { status: 409 }),
      ),
    );
    const user = userEvent.setup();
    open();

    await user.type(screen.getByLabelText(/^Content/), 'hello');
    await user.click(screen.getByRole('button', { name: 'Send' }));

    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent('Could not send a message to queue "ORDERS"');
    expect(alert).toHaveTextContent('The address is full. Check the audit log for what ran, then try again.');
    expect(screen.getByLabelText(/^Content/)).toHaveValue('hello');
  });
});
