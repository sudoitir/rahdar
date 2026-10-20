import { beforeEach, describe, expect, it, vi } from 'vitest';
import { http, HttpResponse } from 'msw';
import { screen, waitFor, within } from '@testing-library/react';
import { Notifications } from '@mantine/notifications';
import userEvent from '@testing-library/user-event';

import { renderWithProviders } from '../../test/render.tsx';
import { server } from '../../test/setup.ts';
import { ExpectationsView } from './ExpectationsView.tsx';
import { paged } from '../../kernel/api/paging.ts';
import { holdButton } from '../../test/hold.ts';

vi.mock('@tanstack/react-router', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@tanstack/react-router')>()),
  Link: ({ children }: { children: React.ReactNode }) => <a>{children}</a>,
}));

function expectation(over: Record<string, unknown> = {}) {
  return {
    id: 'e1',
    requestDestination: 'orders.request',
    replyDestinations: ['orders.reply'],
    resolvedReplyDestinations: ['orders.reply'],
    replyDestinationsCapped: false,
    correlationProperty: null,
    deadlineMs: 30_000,
    samplePerMin: 10,
    capturePayload: false,
    enabled: true,
    ...over,
  };
}

function signedIn(permissions: string[]) {
  return http.get('*/api/v1/auth/me', () =>
    HttpResponse.json({
      id: 'u1',
      username: 'ops',
      mustChangePassword: false,
      grants: [{ scopeType: 'GLOBAL', scopeId: null, permissions }],
    }),
  );
}

const writer = signedIn(['cluster:read', 'cluster:write']);

describe('ExpectationsView', () => {
  // The capture hint reads capture subscriptions; a test that is not about it sees none.
  beforeEach(() => {
    server.use(http.get('*/api/v1/clusters/c1/sql/index', () => HttpResponse.json(paged([]))));
  });

  it('lists declared expectations', async () => {
    server.use(http.get('*/api/v1/clusters/c1/rr/expectations', () => HttpResponse.json(paged([expectation()]))));
    renderWithProviders(<ExpectationsView clusterId="c1" />);

    expect(await screen.findByRole('rowheader', { name: 'orders.request' })).toBeInTheDocument();
  });

  it('shows an empty state with no expectations', async () => {
    server.use(http.get('*/api/v1/clusters/c1/rr/expectations', () => HttpResponse.json(paged([]))));
    renderWithProviders(<ExpectationsView clusterId="c1" />);

    expect(await screen.findByText(/No addresses declared yet/)).toBeInTheDocument();
  });

  it('creates a new expectation from the form', async () => {
    let created = false;
    server.use(
      writer,
      http.get('*/api/v1/clusters/c1/rr/expectations', () => HttpResponse.json(paged(created ? [expectation()] : []))),
      http.post('*/api/v1/clusters/c1/rr/expectations', () => {
        created = true;
        return HttpResponse.json(expectation(), { status: 201 });
      }),
    );
    const user = userEvent.setup();
    renderWithProviders(<ExpectationsView clusterId="c1" />);

    await screen.findByText(/No addresses declared yet/);
    await user.type(screen.getByLabelText('Request address'), 'orders.request');
    await user.click(screen.getByRole('button', { name: 'Add' }));

    expect(await screen.findByRole('rowheader', { name: 'orders.request' })).toBeInTheDocument();
  });

  it('sends every reply address pattern the operator entered', async () => {
    const sent: { replyDestinations?: string[] }[] = [];
    let created = false;
    server.use(
      writer,
      http.get('*/api/v1/clusters/c1/queues', () => HttpResponse.json({ data: [], page: 1, size: 300, total: 0 })),
      http.get('*/api/v1/clusters/c1/rr/expectations', () => HttpResponse.json(paged(created ? [expectation()] : []))),
      http.post('*/api/v1/clusters/c1/rr/expectations', async ({ request }) => {
        sent.push((await request.json()) as { replyDestinations?: string[] });
        created = true;
        return HttpResponse.json(expectation(), { status: 201 });
      }),
    );
    const user = userEvent.setup();
    renderWithProviders(<ExpectationsView clusterId="c1" />);

    await screen.findByText(/No addresses declared yet/);
    await user.type(screen.getByLabelText('Request address'), 'orders.request');

    // The label is associated with three nodes: the option listbox, the hidden
    // input carrying the value, and the visible one that takes typing.
    const replies = screen
      .getAllByLabelText('Reply addresses')
      .find((el): el is HTMLInputElement => el.tagName === 'INPUT' && (el as HTMLInputElement).type !== 'hidden')!;
    await user.type(replies, 'orders.reply.a{enter}');
    await user.type(replies, 'orders.reply.*{enter}');
    await user.click(screen.getByRole('button', { name: 'Add' }));

    await screen.findByRole('rowheader', { name: 'orders.request' });
    expect(sent[0]?.replyDestinations).toEqual(['orders.reply.a', 'orders.reply.*']);
  });

  it('explains what leaving the reply addresses empty means', async () => {
    // Conflating "I meant temporary queues" with "I have not filled this in" is
    // what produced an expectation that could never be joined.
    server.use(http.get('*/api/v1/clusters/c1/rr/expectations', () => HttpResponse.json(paged([]))));
    renderWithProviders(<ExpectationsView clusterId="c1" />);

    expect(await screen.findByText(/temporary queue/)).toBeInTheDocument();
  });

  it('keeps the reply-address help out of the control row', async () => {
    // The help is four lines of prose. Inside a bottom-aligned row it was what sat
    // on the baseline, so the field it belongs to floated above every other control
    // on the form. It renders below the row now, and this is the regression guard.
    server.use(http.get('*/api/v1/clusters/c1/rr/expectations', () => HttpResponse.json(paged([]))));
    renderWithProviders(<ExpectationsView clusterId="c1" />);

    const help = await screen.findByText(/temporary queue/);
    const field = screen
      .getAllByLabelText('Reply addresses')
      .find((el): el is HTMLInputElement => el.tagName === 'INPUT' && (el as HTMLInputElement).type !== 'hidden')!;
    // The nearest common ancestor is the form grid, never the field's own wrapper.
    expect(field.closest('.mantine-TagsInput-root')?.contains(help)).toBe(false);
  });

  it('says when a declared pattern matches nothing on the cluster yet', async () => {
    server.use(
      http.get('*/api/v1/clusters/c1/rr/expectations', () =>
        HttpResponse.json(
          paged([expectation({ replyDestinations: ['orders.reply.*'], resolvedReplyDestinations: [] })]),
        ),
      ),
    );
    renderWithProviders(<ExpectationsView clusterId="c1" />);

    expect(await screen.findByText('no matching queue yet')).toBeInTheDocument();
  });

  it('flags an over-broad pattern rather than silently tracing a subset', async () => {
    server.use(
      http.get('*/api/v1/clusters/c1/rr/expectations', () =>
        HttpResponse.json(
          paged([
            expectation({
              replyDestinations: ['*'],
              resolvedReplyDestinations: Array.from({ length: 32 }, (_, i) => `a.${i}`),
              replyDestinationsCapped: true,
            }),
          ]),
        ),
      ),
    );
    renderWithProviders(<ExpectationsView clusterId="c1" />);

    expect(await screen.findByText(/too broad/)).toBeInTheDocument();
  });

  it('names traced addresses that are only sampled, and hides the hint once they are captured', async () => {
    let captured = false;
    server.use(
      http.get('*/api/v1/clusters/c1/rr/expectations', () => HttpResponse.json(paged([expectation()]))),
      http.get('*/api/v1/clusters/c1/sql/index', () =>
        HttpResponse.json(
          paged(
            captured
              ? [{ id: 's1', subscriptionPattern: 'orders.#', mode: 'CAPTURE', enabled: true, nodes: [] }]
              : [{ id: 's1', subscriptionPattern: 'orders.request', mode: 'SAMPLE', enabled: true, nodes: [] }],
          ),
        ),
      ),
    );
    const { unmount } = renderWithProviders(<ExpectationsView clusterId="c1" />);

    expect(await screen.findByText(/orders\.request, orders\.reply are not captured/)).toBeInTheDocument();
    unmount();

    captured = true;
    renderWithProviders(<ExpectationsView clusterId="c1" />);
    expect(await screen.findByRole('rowheader', { name: 'orders.request' })).toBeInTheDocument();
    await new Promise((resolve) => setTimeout(resolve, 50));
    expect(screen.queryByText(/are not captured/)).not.toBeInTheDocument();
  });

  it('names every enabled switch by its address, so a reader hears which one it is', async () => {
    server.use(
      writer,
      http.get('*/api/v1/clusters/c1/rr/expectations', () =>
        HttpResponse.json(paged([expectation(), expectation({ id: 'e2', requestDestination: 'billing.request' })])),
      ),
    );
    renderWithProviders(<ExpectationsView clusterId="c1" />);

    expect(await screen.findByRole('switch', { name: 'Trace orders.request' })).toBeChecked();
    expect(screen.getByRole('switch', { name: 'Trace billing.request' })).toBeInTheDocument();
  });

  it('sends one update for a toggle and holds the switch while it is saving', async () => {
    const sent: { enabled?: boolean }[] = [];
    let release: () => void = () => {};
    const gate = new Promise<void>((resolve) => {
      release = resolve;
    });
    server.use(
      writer,
      http.get('*/api/v1/clusters/c1/rr/expectations', () => HttpResponse.json(paged([expectation()]))),
      http.put('*/api/v1/clusters/c1/rr/expectations/e1', async ({ request }) => {
        sent.push((await request.json()) as { enabled?: boolean });
        await gate;
        return HttpResponse.json(expectation({ enabled: false }));
      }),
    );
    const user = userEvent.setup();
    renderWithProviders(<ExpectationsView clusterId="c1" />);

    const toggle = await screen.findByRole('switch', { name: 'Trace orders.request' });
    await user.click(toggle);
    await waitFor(() => expect(toggle).toBeDisabled());
    await user.click(toggle);
    release();

    await waitFor(() => expect(toggle).toBeEnabled());
    expect(sent).toEqual([expect.objectContaining({ enabled: false })]);
  });

  it('says so when a switch could not be saved, with the cause and what to do', async () => {
    server.use(
      writer,
      http.get('*/api/v1/clusters/c1/rr/expectations', () => HttpResponse.json(paged([expectation()]))),
      http.put('*/api/v1/clusters/c1/rr/expectations/e1', () =>
        HttpResponse.json({ title: 'Conflict', detail: 'Another operator changed it' }, { status: 409 }),
      ),
    );
    const user = userEvent.setup();
    renderWithProviders(
      <>
        <Notifications />
        <ExpectationsView clusterId="c1" />
      </>,
    );

    await user.click(await screen.findByRole('switch', { name: 'Trace orders.request' }));

    expect(await screen.findByText('Could not disable tracing of orders.request')).toBeInTheDocument();
    expect(screen.getByText(/The switch shows what is stored; try again/)).toBeInTheDocument();
    expect(screen.getByRole('switch', { name: 'Trace orders.request' })).toBeChecked();
  });

  it('asks for the address to be typed before it stops tracing, and only then deletes', async () => {
    let deleted = 0;
    server.use(
      writer,
      http.get('*/api/v1/clusters/c1/rr/expectations', () => HttpResponse.json(paged([expectation()]))),
      http.delete('*/api/v1/clusters/c1/rr/expectations/e1', () => {
        deleted += 1;
        return new HttpResponse(null, { status: 204 });
      }),
    );
    const user = userEvent.setup();
    renderWithProviders(<ExpectationsView clusterId="c1" />);

    const remove = await screen.findByRole('button', { name: 'Remove orders.request' });
    await user.click(remove);

    const dialog = await screen.findByRole('dialog', { name: 'Stop tracing this address' });
    expect(within(dialog).getByText(/Flows already recorded stay/)).toBeInTheDocument();
    const confirm = within(dialog).getByRole('button', { name: 'Stop tracing' });
    expect(deleted).toBe(0);

    await holdButton(confirm);

    await waitFor(() => expect(deleted).toBe(1));
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
  });

  it('dismisses the removal with Escape and gives focus back to the button that opened it', async () => {
    server.use(
      writer,
      http.get('*/api/v1/clusters/c1/rr/expectations', () => HttpResponse.json(paged([expectation()]))),
    );
    const user = userEvent.setup();
    renderWithProviders(<ExpectationsView clusterId="c1" />);

    const remove = await screen.findByRole('button', { name: 'Remove orders.request' });
    await user.click(remove);
    await screen.findByRole('dialog', { name: 'Stop tracing this address' });
    await user.keyboard('{Escape}');

    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
    await waitFor(() => expect(remove).toHaveFocus());
  });

  it('keeps the controls visible but disabled for a reader, and says which permission is missing', async () => {
    server.use(
      signedIn(['cluster:read']),
      http.get('*/api/v1/clusters/c1/rr/expectations', () => HttpResponse.json(paged([expectation()]))),
    );
    renderWithProviders(<ExpectationsView clusterId="c1" />);

    expect(await screen.findByText(/needs the/)).toHaveTextContent('cluster:write');
    expect(screen.getByRole('switch', { name: 'Trace orders.request' })).toBeDisabled();
    expect(screen.getByRole('button', { name: 'Remove orders.request' })).toBeDisabled();
    expect(screen.getByRole('button', { name: 'Add' })).toBeDisabled();
  });

  it('offers the controls while the grants are still loading', async () => {
    server.use(
      http.get('*/api/v1/auth/me', async () => {
        await new Promise(() => {});
        return HttpResponse.json({});
      }),
      http.get('*/api/v1/clusters/c1/rr/expectations', () => HttpResponse.json(paged([expectation()]))),
    );
    renderWithProviders(<ExpectationsView clusterId="c1" />);

    expect(await screen.findByRole('switch', { name: 'Trace orders.request' })).toBeEnabled();
    expect(screen.getByRole('button', { name: 'Add' })).toBeEnabled();
    expect(screen.queryByText(/cluster:write/)).not.toBeInTheDocument();
  });

  it('says what is missing when Add is pressed without a request address, and sends nothing', async () => {
    let posted = 0;
    server.use(
      writer,
      http.get('*/api/v1/clusters/c1/rr/expectations', () => HttpResponse.json(paged([]))),
      http.post('*/api/v1/clusters/c1/rr/expectations', () => {
        posted += 1;
        return HttpResponse.json(expectation(), { status: 201 });
      }),
    );
    const user = userEvent.setup();
    renderWithProviders(<ExpectationsView clusterId="c1" />);

    await screen.findByText(/No addresses declared yet/);
    await user.click(screen.getByRole('button', { name: 'Add' }));

    expect(await screen.findByText(/Enter the request address to trace/)).toBeInTheDocument();
    expect(screen.getByLabelText('Request address')).toHaveFocus();
    expect(posted).toBe(0);
  });

  it('keeps a cleared samples-per-minute field out of the request and says what is allowed', async () => {
    let posted = 0;
    server.use(
      writer,
      http.get('*/api/v1/clusters/c1/rr/expectations', () => HttpResponse.json(paged([]))),
      http.post('*/api/v1/clusters/c1/rr/expectations', () => {
        posted += 1;
        return HttpResponse.json(expectation(), { status: 201 });
      }),
    );
    const user = userEvent.setup();
    renderWithProviders(<ExpectationsView clusterId="c1" />);

    await screen.findByText(/No addresses declared yet/);
    await user.type(screen.getByLabelText('Request address'), 'orders.request');
    await user.clear(screen.getByLabelText('Samples/min'));
    await user.click(screen.getByRole('button', { name: 'Add' }));

    expect(await screen.findByText(/Enter how many samples to take a minute/)).toBeInTheDocument();
    expect(screen.getByLabelText('Samples/min')).toHaveFocus();
    expect(posted).toBe(0);
  });

  it('states the cause and offers a retry when the declared addresses cannot be read', async () => {
    server.use(
      http.get('*/api/v1/clusters/c1/rr/expectations', () => HttpResponse.json({ title: 'Down' }, { status: 503 })),
    );
    renderWithProviders(<ExpectationsView clusterId="c1" />);

    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent('Rahdar failed to complete the request');
    expect(within(alert).getByRole('button', { name: 'Retry' })).toBeInTheDocument();
    expect(screen.queryByText(/No addresses declared yet/)).not.toBeInTheDocument();
  });

  it('is a section with its own headings and no heading above level two', async () => {
    server.use(http.get('*/api/v1/clusters/c1/rr/expectations', () => HttpResponse.json(paged([]))));
    renderWithProviders(<ExpectationsView clusterId="c1" />);

    expect(await screen.findByRole('heading', { level: 2, name: 'Traced addresses' })).toBeInTheDocument();
    expect(screen.queryByRole('heading', { level: 1 })).not.toBeInTheDocument();
  });
});
