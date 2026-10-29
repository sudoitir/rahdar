import { describe, expect, it, vi } from 'vitest';
import { http, HttpResponse } from 'msw';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { notifications } from '@mantine/notifications';

import { renderWithProviders } from '../../test/render.tsx';
import { server } from '../../test/setup.ts';
import { paged } from '../../kernel/api/paging.ts';
import { holdButton, holdByKeyboard } from '../../test/hold.ts';

vi.mock('@tanstack/react-router', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@tanstack/react-router')>()),
  useParams: () => ({ clusterId: 'c1' }),
}));

const { IndexSubscriptions } = await import('./IndexSubscriptions.tsx');

function subscription(over: Record<string, unknown> = {}) {
  return {
    id: 's1',
    subscriptionPattern: 'ORDER.IN',
    retentionDays: 7,
    intervalMs: 5000,
    captureFrom: '2026-09-01T09:00:00Z',
    createdAt: '2026-09-01T09:00:00Z',
    createdBy: 'op',
    enabled: true,
    messagesHeld: 1284,
    bytesHeld: 2_600_000,
    oldestObservedAt: '2026-09-01T09:00:03Z',
    ...over,
  };
}

function mockMe() {
  server.use(
    http.get('*/api/v1/auth/me', () =>
      HttpResponse.json({
        username: 'op',
        displayName: 'Op',
        provider: 'LOCAL',
        mustChangePassword: false,
        grants: [{ scopeType: 'GLOBAL', scopeId: null, roleName: 'admin', permissions: ['*'] }],
      }),
    ),
  );
}

describe('IndexSubscriptions', () => {
  it('states that message bodies will be stored before the subscription is created', async () => {
    mockMe();
    server.use(http.get('*/api/v1/clusters/c1/sql/index', () => HttpResponse.json(paged([]))));
    const user = userEvent.setup();
    renderWithProviders(<IndexSubscriptions />);

    await user.type(await screen.findByLabelText('Subscription or pattern'), 'ORDER.IN');

    // The disclosure is on the form, not behind a link: this is the last screen
    // before Rahdar starts keeping a copy of application payload.
    expect(screen.getByText(/This stores message bodies/i)).toBeInTheDocument();
    expect(screen.getByText(/headers, application properties and the body/i)).toBeInTheDocument();
    expect(screen.getByText(/for 7 days/i)).toBeInTheDocument();
  });

  it('is two h3 sections, the subscriptions in a native table and the form to start one', async () => {
    mockMe();
    server.use(http.get('*/api/v1/clusters/c1/sql/index', () => HttpResponse.json(paged([subscription()]))));
    renderWithProviders(<IndexSubscriptions />);

    expect(await screen.findByRole('heading', { level: 3, name: 'Subscriptions' })).toBeInTheDocument();
    expect(screen.getByRole('heading', { level: 3, name: 'Index a subscription' })).toBeInTheDocument();
    const table = screen.getByRole('table', { name: 'Index subscriptions' });
    expect(
      within(table)
        .getAllByRole('columnheader')
        .map((header) => header.textContent),
    ).toEqual(['Subscriptions', 'Mode', 'Held', 'Retention', 'State', 'Delete']);
    expect(within(table).getByText('Sample')).toBeInTheDocument();
    expect(within(table).getByText('7 days')).toBeInTheDocument();
  });

  it('teaches what an index is when none exists, rather than showing an empty table', async () => {
    mockMe();
    server.use(http.get('*/api/v1/clusters/c1/sql/index', () => HttpResponse.json(paged([]))));
    renderWithProviders(<IndexSubscriptions />);

    expect(await screen.findByText(/Nothing is being indexed/i)).toBeInTheDocument();
    expect(screen.getByText(/cannot find a message that has already been consumed/i)).toBeInTheDocument();
  });

  it('shows what a subscription is holding', async () => {
    mockMe();
    server.use(http.get('*/api/v1/clusters/c1/sql/index', () => HttpResponse.json(paged([subscription()]))));
    renderWithProviders(<IndexSubscriptions />);

    expect(await screen.findByText('ORDER.IN')).toBeInTheDocument();
    expect(screen.getByText(/1,284 messages/)).toBeInTheDocument();
    expect(screen.getByText(/2\.5 MB of payload/)).toBeInTheDocument();
    // A sampled subscription says what sampling misses, next to what it holds.
    expect(screen.getByText(/Just sampling: a message consumed between two polls/)).toBeInTheDocument();
  });

  it('states the blast radius and needs the pattern typed before it will delete', async () => {
    mockMe();
    server.use(
      http.get('*/api/v1/clusters/c1/sql/index', () => HttpResponse.json(paged([subscription()]))),
      http.delete('*/api/v1/clusters/c1/sql/index/s1', () => HttpResponse.json({ messagesDestroyed: 1284 })),
    );
    const user = userEvent.setup();
    renderWithProviders(<IndexSubscriptions />);

    await user.click(await screen.findByRole('button', { name: 'Delete' }));

    expect(await screen.findByText(/1,284 captured messages/)).toBeInTheDocument();
    expect(screen.getByRole('dialog', { name: 'Delete index subscription ORDER.IN' })).toBeInTheDocument();
    const confirm = screen.getByRole('button', { name: /Delete and destroy captured messages/i });
    // Not armed by a click, and not by a checkbox: the resource's own name.

    expect(confirm).toBeEnabled();
  });

  it('states the full blast radius before capture can be started', async () => {
    mockMe();
    server.use(http.get('*/api/v1/clusters/c1/sql/index', () => HttpResponse.json(paged([]))));
    const user = userEvent.setup();
    renderWithProviders(<IndexSubscriptions />);

    await user.type(await screen.findByLabelText('Subscription or pattern'), 'ORDER.IN');
    await user.click(screen.getByRole('radio', { name: /capture everything/i }));

    // Every object Rahdar will create on the broker, named, before the button that
    // creates them can be pressed.
    expect(screen.getByText(/changes routing on every live node/i)).toBeInTheDocument();
    expect(screen.getByText(/non-exclusive divert/i)).toBeInTheDocument();
    expect(screen.getByText(/ring-bounded, non-durable subscription/i)).toBeInTheDocument();
    expect(screen.getByText(/security setting/i)).toBeInTheDocument();
    // ADR-0065: nothing here is temporary, and the screen must not imply otherwise.
    expect(screen.getByText(/None of those objects disappears when the broker restarts/i)).toBeInTheDocument();
    expect(screen.queryByText(/lost when the broker restarts/i)).not.toBeInTheDocument();
    // The configuration equivalent, for an estate that deploys from broker.xml.
    expect(screen.getByText('rahdar.capture.<instance>.#')).toBeInTheDocument();
  });

  it('reports capture state per node, never as one rolled-up answer', async () => {
    mockMe();
    server.use(
      http.get('*/api/v1/clusters/c1/sql/index', () =>
        HttpResponse.json(
          paged([
            subscription({
              mode: 'CAPTURE',
              maxBytes: 5_368_709_120,
              nodes: [
                {
                  nodeId: 'n1',
                  nodeName: 'primary',
                  state: 'ACTIVE',
                  detail: null,
                  capturedFrom: '2026-09-07T09:00:00Z',
                  droppedEstimate: 0,
                },
                {
                  nodeId: 'n2',
                  nodeName: 'backup',
                  state: 'FAILED',
                  detail: 'an exclusive divert on ORDER.IN would shadow the capture divert',
                  capturedFrom: null,
                  droppedEstimate: 0,
                },
              ],
            }),
          ]),
        ),
      ),
    );
    renderWithProviders(<IndexSubscriptions />);

    expect(await screen.findByText(/primary: capturing since/i)).toBeInTheDocument();
    // The node that is not capturing says so, and says why. A single "capturing:
    // yes" would erase exactly the gap the operator needs.
    expect(screen.getByText(/backup: not capturing/i)).toBeInTheDocument();
    expect(screen.getByText(/exclusive divert/i)).toBeInTheDocument();
  });

  it('can be driven to capture-delete on the keyboard alone, and returns focus', async () => {
    mockMe();
    server.use(
      http.get('*/api/v1/clusters/c1/sql/index', () =>
        HttpResponse.json(paged([subscription({ mode: 'CAPTURE', nodes: [] })])),
      ),
    );
    const user = userEvent.setup();
    renderWithProviders(<IndexSubscriptions />);

    const trigger = await screen.findByRole('button', { name: 'Delete' });
    trigger.focus();
    expect(trigger).toHaveFocus();

    await user.keyboard('{Enter}');

    // The confirmation is reachable and armable without a pointer.
    const confirm = await screen.findByRole('button', {
      name: /Delete and destroy captured messages/i,
    });
    await holdByKeyboard(confirm);

    // Cancelling returns focus to the control that opened it, rather than dropping
    // the operator back at the top of the document.
    const cancel = screen.getByRole('button', { name: 'Cancel' });
    cancel.focus();
    await user.keyboard('{Enter}');
    const again = await screen.findByRole('button', { name: 'Delete' });
    again.focus();
    expect(again).toHaveFocus();
  });

  it('arms capture only from its dry run, by typing the pattern, on the keyboard alone', async () => {
    mockMe();
    let created = false;
    server.use(
      http.get('*/api/v1/clusters/c1/sql/index', () => HttpResponse.json(paged([]))),
      http.post('*/api/v1/clusters/c1/sql/index', ({ request }) => {
        if (new URL(request.url).searchParams.get('dryRun') === 'true') {
          return HttpResponse.json({
            destinations: ['artemis.address/ORDER.IN'],
            nodes: ['primary'],
            ringMessages: 10000,
            ringBytes: 67_108_864,
            brokerObjects: ['divert rahdar.capture.abc.ORDER.IN.s1'],
            brokerXml: '<diverts/>',
            refusal: null,
          });
        }
        created = true;
        return HttpResponse.json(subscription({ mode: 'CAPTURE', nodes: [] }));
      }),
    );
    const user = userEvent.setup();
    renderWithProviders(<IndexSubscriptions />);

    const pattern = await screen.findByLabelText('Subscription or pattern');
    pattern.focus();
    await user.keyboard('ORDER.IN');

    // The mode is a radio group, so it is reachable and switchable with the arrow keys.
    const sample = screen.getByRole('radio', { name: /^sample$/i });
    sample.focus();
    await user.keyboard('{ArrowRight}');
    expect(screen.getByText(/changes routing on every live node/i)).toBeInTheDocument();

    const previewButton = screen.getByRole('button', { name: /Preview capture/i });
    previewButton.focus();
    await user.keyboard('{Enter}');

    // What will be created, where, and how big, before anything can be armed.
    expect(await screen.findByText(/Installed on 1 live node: primary/i)).toBeInTheDocument();
    expect(screen.getByText(/64 MB, whichever is reached first/)).toBeInTheDocument();
    expect(screen.getByLabelText('Subscription or pattern')).toHaveAttribute('readonly');

    const start = screen.getByRole('button', { name: /Start capturing/i });
    expect(start).toBeEnabled();
    await holdByKeyboard(start);
    await waitFor(() => expect(created).toBe(true));
  });

  it('states why capture would be refused instead of offering to arm it', async () => {
    mockMe();
    server.use(
      http.get('*/api/v1/clusters/c1/sql/index', () => HttpResponse.json(paged([]))),
      http.post('*/api/v1/clusters/c1/sql/index', () =>
        HttpResponse.json({
          destinations: [],
          nodes: ['primary'],
          ringMessages: 10000,
          ringBytes: 1024,
          brokerObjects: [],
          brokerXml: '',
          refusal: 'The pattern matches no destination on this cluster.',
        }),
      ),
    );
    const user = userEvent.setup();
    renderWithProviders(<IndexSubscriptions />);

    await user.type(await screen.findByLabelText('Subscription or pattern'), 'NOTHING.*');
    await user.click(screen.getByRole('radio', { name: /capture everything/i }));
    await user.click(screen.getByRole('button', { name: /Preview capture/i }));

    expect(await screen.findByText(/Capture would be refused/)).toBeInTheDocument();
    expect(screen.getByText(/matches no destination/i)).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /Start capturing/i })).not.toBeInTheDocument();
  });

  it('checks a bound on blur against the limit the server enforces', async () => {
    mockMe();
    server.use(http.get('*/api/v1/clusters/c1/sql/index', () => HttpResponse.json(paged([]))));
    const user = userEvent.setup();
    renderWithProviders(<IndexSubscriptions />);

    await user.type(await screen.findByLabelText('Subscription or pattern'), 'ORDER.IN');
    await user.click(screen.getByRole('radio', { name: /capture everything/i }));
    await user.click(screen.getByRole('button', { name: /Capture bounds/i }));
    await user.type(screen.getByLabelText(/Ring size/i), '5');
    await user.tab();

    expect(await screen.findByText(/Must be between 100 and 1,000,000/)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /Preview capture/i })).toBeDisabled();
    expect(screen.getByText(/Correct the highlighted fields first/)).toBeInTheDocument();
  });
});

function grants(permissions: string[]) {
  server.use(
    http.get('*/api/v1/auth/me', () =>
      HttpResponse.json({
        username: 'op',
        displayName: 'Op',
        provider: 'LOCAL',
        mustChangePassword: false,
        grants: [{ scopeType: 'GLOBAL', scopeId: null, roleName: 'r', permissions }],
      }),
    ),
  );
}

function listing(rows: unknown[]) {
  server.use(http.get('*/api/v1/clusters/c1/sql/index', () => HttpResponse.json(paged(rows))));
}

const node = (over: Record<string, unknown>) => ({
  nodeId: 'n1',
  nodeName: 'primary',
  state: 'ACTIVE',
  detail: null,
  capturedFrom: '2026-09-07T09:00:00Z',
  droppedEstimate: 0,
  ...over,
});

describe('IndexSubscriptions loading and failure', () => {
  it('says why the subscriptions could not be listed', async () => {
    mockMe();
    server.use(
      http.get('*/api/v1/clusters/c1/sql/index', () =>
        HttpResponse.json({ title: 'Forbidden', detail: 'You may not see this cluster.' }, { status: 403 }),
      ),
    );
    renderWithProviders(<IndexSubscriptions />);

    expect(await screen.findByText('You are not allowed to do this')).toBeInTheDocument();
    expect(screen.getByText('Your role does not allow it.')).toBeInTheDocument();
    expect(screen.queryByLabelText('Subscription or pattern')).toBeNull();
  });

  it('shows nothing of the form while the subscriptions are on their way', () => {
    mockMe();
    server.use(http.get('*/api/v1/clusters/c1/sql/index', () => new Promise(() => {})));
    renderWithProviders(<IndexSubscriptions />);

    expect(screen.queryByLabelText('Subscription or pattern')).toBeNull();
    expect(screen.queryByText(/Nothing is being indexed/)).toBeNull();
  });
});

describe('IndexSubscriptions rows', () => {
  it('states each capture node in words: degraded and losing messages, not reached, and why', async () => {
    mockMe();
    listing([
      subscription({
        mode: 'CAPTURE',
        maxBytes: 1024 * 1024 * 1024,
        nodes: [
          node({ nodeName: 'a', state: 'DEGRADED', droppedEstimate: 1200, detail: 'ring full' }),
          node({ nodeId: 'n2', nodeName: null, state: 'PENDING' }),
        ],
      }),
    ]);
    renderWithProviders(<IndexSubscriptions />);

    expect(
      await screen.findByText('a: capturing, losing messages · about 1,200 missed — ring full'),
    ).toBeInTheDocument();
    // A node with no name is named by its id, and a state the screen has no words for is "not reached yet".
    expect(screen.getByText('n2: not reached yet')).toBeInTheDocument();
    expect(screen.getByText(/of 1\.0 GB allowed/)).toBeInTheDocument();
  });

  it('says how many were missed cannot be known when a filter narrows the capture', async () => {
    mockMe();
    listing([
      subscription({
        mode: 'CAPTURE',
        filterString: "tenant = 'acme'",
        nodes: [node({ state: 'DEGRADED', droppedEstimate: 50 })],
      }),
    ]);
    renderWithProviders(<IndexSubscriptions />);

    expect(await screen.findByText(/how many were missed is unavailable/)).toBeInTheDocument();
    expect(screen.queryByText(/about 50 missed/)).toBeNull();
    expect(screen.getByText(/filter tenant = 'acme'/)).toBeInTheDocument();
    expect(screen.getByText(/capturing since/)).toBeInTheDocument();
  });

  it('says no node has been reached yet, and offers no sampling caveat for a capture', async () => {
    mockMe();
    listing([subscription({ mode: 'CAPTURE', nodes: [] })]);
    renderWithProviders(<IndexSubscriptions />);

    expect(await screen.findByText(/No node has been reached yet/)).toBeInTheDocument();
    expect(screen.queryByText(/Just sampling: a message consumed between two polls/)).toBeNull();
  });

  it('says what is not being recorded and that the backlog is still being indexed', async () => {
    mockMe();
    listing([subscription({ notCapturing: 'the subscription does not exist on any node.', backlogInProgress: true })]);
    renderWithProviders(<IndexSubscriptions />);

    expect(
      await screen.findByText('Recording nothing — the subscription does not exist on any node.'),
    ).toBeInTheDocument();
    expect(
      screen.getByText(/Still indexing the messages that were already on these subscriptions/),
    ).toBeInTheDocument();
  });

  it('says one day, one message and a small payload in the singular and in bytes', async () => {
    mockMe();
    listing([subscription({ retentionDays: 1, messagesHeld: 1, bytesHeld: 512, oldestObservedAt: null })]);
    renderWithProviders(<IndexSubscriptions />);

    expect(await screen.findByText('1 message')).toBeInTheDocument();
    expect(screen.getByText('1 day')).toBeInTheDocument();
    expect(screen.getByText('512 B of payload')).toBeInTheDocument();
    expect(screen.queryByText(/oldest/)).toBeNull();
  });

  it('says a depth that was not reported is unavailable and not zero', async () => {
    mockMe();
    listing([subscription({ messagesHeld: null, bytesHeld: null })]);
    renderWithProviders(<IndexSubscriptions />);

    expect(await screen.findByText('Unavailable')).toBeInTheDocument();
    expect(screen.getByText(/size unavailable/)).toBeInTheDocument();
    expect(screen.queryByText(/^0 messages$/)).toBeNull();
  });

  it('shows a date that cannot be read as a dash, and a large payload in its own unit', async () => {
    mockMe();
    listing([subscription({ captureFrom: 'not a date', bytesHeld: 3 * 1024 ** 4 })]);
    renderWithProviders(<IndexSubscriptions />);

    expect(await screen.findByText(/sampling since —/)).toBeInTheDocument();
    expect(screen.getByText(/3\.0 TB of payload/)).toBeInTheDocument();
  });

  it('pauses and resumes a subscription from its switch', async () => {
    mockMe();
    listing([subscription({ enabled: false })]);
    const bodies: unknown[] = [];
    server.use(
      http.patch('*/api/v1/clusters/c1/sql/index/s1', async ({ request }) => {
        bodies.push(await request.json());
        return HttpResponse.json(subscription({ enabled: true }));
      }),
    );
    const user = userEvent.setup();
    renderWithProviders(<IndexSubscriptions />);

    const toggle = await screen.findByRole('switch', { name: 'Paused' });
    expect(toggle).not.toBeChecked();
    await user.click(toggle);
    await waitFor(() => expect(bodies).toEqual([{ enabled: true }]));
  });

  it('keeps the switch and delete visible but disabled without the permission the subscription needs', async () => {
    // Sampling is settings:write; capture is its own authority, capture:write.
    grants(['capture:write']);
    listing([subscription(), subscription({ id: 's2', subscriptionPattern: 'PAY.IN', mode: 'CAPTURE', nodes: [] })]);
    renderWithProviders(<IndexSubscriptions />);

    const sampled = (await screen.findByText('ORDER.IN')).closest('tr')!;
    // The cluster's rights arrive after the rows; until they do, the controls are offered.
    await waitFor(() => expect(within(sampled).getByRole('switch')).toBeDisabled());
    expect(within(sampled).getByRole('button', { name: 'Delete' })).toBeDisabled();
    const captured = screen.getByText('PAY.IN').closest('tr')!;
    expect(within(captured).getByRole('switch')).toBeEnabled();
    expect(within(captured).getByRole('button', { name: 'Delete' })).toBeEnabled();
  });
});

describe('IndexSubscriptions deleting', () => {
  it('deletes once the pattern is typed, and says how many captured messages were destroyed', async () => {
    mockMe();
    listing([subscription()]);
    server.use(http.delete('*/api/v1/clusters/c1/sql/index/s1', () => HttpResponse.json({ messagesDestroyed: 1284 })));
    const show = vi.spyOn(notifications, 'show').mockReturnValue('n');
    const user = userEvent.setup();
    renderWithProviders(<IndexSubscriptions />);

    await user.click(await screen.findByRole('button', { name: 'Delete' }));
    expect(await screen.findByText(/1,284 captured messages/)).toBeInTheDocument();
    expect(screen.queryByText(/removes the divert, the capture subscription/)).toBeNull();
    await holdButton(screen.getByRole('button', { name: /Delete and destroy captured messages/ }));

    await waitFor(() =>
      expect(show).toHaveBeenCalledWith(
        expect.objectContaining({
          message: 'Deleted index subscription ORDER.IN — 1,284 captured messages destroyed',
        }),
      ),
    );
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());
    show.mockRestore();
  });

  it('states that a capture leaves objects on every node, and can be cancelled', async () => {
    mockMe();
    listing([subscription({ mode: 'CAPTURE', nodes: [], messagesHeld: 1 })]);
    const user = userEvent.setup();
    renderWithProviders(<IndexSubscriptions />);

    await user.click(await screen.findByRole('button', { name: 'Delete' }));
    expect(await screen.findByText(/1 captured message it holds/)).toBeInTheDocument();
    expect(
      screen.getByText(/removes the divert, the capture subscription, the address setting and the security setting/),
    ).toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: 'Cancel' }));
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());
    expect(screen.getByRole('button', { name: 'Delete' })).toBeInTheDocument();
  });

  it('closes on Escape and gives focus back to the Delete that opened it', async () => {
    mockMe();
    listing([subscription()]);
    const user = userEvent.setup();
    renderWithProviders(<IndexSubscriptions />);

    const trigger = await screen.findByRole('button', { name: 'Delete' });
    await user.click(trigger);
    await screen.findByRole('button', { name: /Delete and destroy captured messages/ });

    await user.keyboard('{Escape}');

    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());
    await waitFor(() => expect(screen.getByRole('button', { name: 'Delete' })).toHaveFocus());
  });

  it('says why a delete failed and leaves the confirmation open', async () => {
    mockMe();
    listing([subscription()]);
    server.use(
      http.delete('*/api/v1/clusters/c1/sql/index/s1', () =>
        HttpResponse.json({ title: 'Conflict', detail: 'The subscription is being reconciled.' }, { status: 409 }),
      ),
    );
    const show = vi.spyOn(notifications, 'show').mockReturnValue('n');
    const user = userEvent.setup();
    renderWithProviders(<IndexSubscriptions />);

    await user.click(await screen.findByRole('button', { name: 'Delete' }));
    await holdButton(await screen.findByRole('button', { name: /Delete and destroy captured messages/ }));

    // The failure is read inside the dialog, by its cause and next step, and the confirmation stays open.
    expect(await screen.findByText('This conflicts with the current state')).toBeInTheDocument();
    expect(screen.getByText('The subscription is being reconciled.')).toBeInTheDocument();
    expect(show).not.toHaveBeenCalled();
    expect(screen.getByRole('button', { name: /Delete and destroy captured messages/ })).toBeInTheDocument();
    show.mockRestore();
  });
});

describe('IndexSubscriptions sampling form', () => {
  it('starts sampling a pattern with the defaults, says so, and clears the form', async () => {
    mockMe();
    listing([]);
    const bodies: unknown[] = [];
    server.use(
      http.post('*/api/v1/clusters/c1/sql/index', async ({ request }) => {
        bodies.push(await request.json());
        return HttpResponse.json(subscription());
      }),
    );
    const show = vi.spyOn(notifications, 'show').mockReturnValue('n');
    const user = userEvent.setup();
    renderWithProviders(<IndexSubscriptions />);

    const pattern = await screen.findByLabelText('Subscription or pattern');
    await user.type(pattern, '  ORDER.IN ');
    await user.click(screen.getByRole('button', { name: 'Start sampling' }));

    await waitFor(() => expect(bodies).toHaveLength(1));
    expect(bodies[0]).toEqual({
      subscriptionPattern: 'ORDER.IN',
      retentionDays: 7,
      intervalMs: 5000,
      enabled: true,
      mode: 'SAMPLE',
    });
    await waitFor(() =>
      expect(show).toHaveBeenCalledWith(expect.objectContaining({ message: 'Started sampling ORDER.IN' })),
    );
    await waitFor(() => expect(pattern).toHaveValue(''));
    show.mockRestore();
  });

  it('says why a subscription was refused, beside the form', async () => {
    mockMe();
    listing([]);
    server.use(
      http.post('*/api/v1/clusters/c1/sql/index', () =>
        HttpResponse.json({ title: 'Already indexed', detail: 'ORDER.IN is already indexed.' }, { status: 409 }),
      ),
    );
    const user = userEvent.setup();
    renderWithProviders(<IndexSubscriptions />);

    await user.type(await screen.findByLabelText('Subscription or pattern'), 'ORDER.IN');
    await user.click(screen.getByRole('button', { name: 'Start sampling' }));

    const alert = (await screen.findByText('This conflicts with the current state')).closest('[role="alert"]');
    expect(alert).toHaveTextContent('This conflicts with the current state');
    expect(alert).toHaveTextContent('ORDER.IN is already indexed.');
  });

  it('says what stops the form, by reason: nothing entered, or no permission', async () => {
    mockMe();
    listing([]);
    const first = renderWithProviders(<IndexSubscriptions />);

    expect(await screen.findByText('Enter a subscription or pattern first.')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Start sampling' })).toBeDisabled();
    first.unmount();

    grants(['cluster:read']);
    const user = userEvent.setup();
    renderWithProviders(<IndexSubscriptions />);
    await user.type(await screen.findByLabelText('Subscription or pattern'), 'ORDER.IN');
    expect(await screen.findByText('Creating a subscription needs the settings write permission.')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Start sampling' })).toBeDisabled();

    await user.click(screen.getByRole('radio', { name: /capture everything/i }));
    expect(screen.getByText('Turning capture on needs the capture write permission.')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Preview capture' })).toBeDisabled();
  });

  it('states retention in the singular and checks the day and interval bounds on blur', async () => {
    mockMe();
    listing([]);
    const user = userEvent.setup();
    renderWithProviders(<IndexSubscriptions />);

    await user.type(await screen.findByLabelText('Subscription or pattern'), 'ORDER.IN');
    const days = screen.getByLabelText('Keep for (days)');
    await user.clear(days);
    await user.type(days, '1');
    expect(screen.getByText(/for 1 day, and then delete it/)).toBeInTheDocument();

    await user.clear(days);
    await user.type(days, '99');
    await user.tab();
    expect(await screen.findByText('Must be between 1 and 90.')).toBeInTheDocument();
    expect(screen.getByText('Correct the highlighted fields first.')).toBeInTheDocument();

    await user.clear(days);
    await user.type(days, '30');
    await user.tab();
    await waitFor(() => expect(screen.queryByText('Must be between 1 and 90.')).toBeNull());

    const interval = screen.getByLabelText('Read every (ms)');
    await user.clear(interval);
    await user.type(interval, '10');
    await user.tab();
    expect(await screen.findByText('Must be between 1,000 and 3,600,000.')).toBeInTheDocument();
  });
});

describe('IndexSubscriptions capture form', () => {
  const previewBody = {
    destinations: ['artemis.address/ORDER.IN', 'artemis.address/ORDER.OUT'],
    nodes: ['primary', 'backup'],
    ringMessages: 10000,
    ringBytes: 500,
    brokerObjects: ['divert rahdar.capture.abc.ORDER.IN.s1'],
    brokerXml: '<diverts/>',
    refusal: null,
  };

  async function fillCapture(user: ReturnType<typeof userEvent.setup>) {
    await user.type(await screen.findByLabelText('Subscription or pattern'), 'ORDER.#');
    await user.click(screen.getByRole('radio', { name: /capture everything/i }));
    await user.type(screen.getByLabelText(/Capture filter/), " tenant = 'acme' ");
    await user.click(screen.getByRole('button', { name: /Capture bounds/ }));
    await user.type(screen.getByLabelText(/Ring size/), '500');
    await user.type(screen.getByLabelText(/Stored payload limit/), '2');
    await user.type(screen.getByLabelText(/Rate limit/), '50');
    await user.type(screen.getByLabelText(/Body stored per message/), '4');
  }

  it('previews exactly what it will create, from the bounds given, and lets the operator edit them again', async () => {
    mockMe();
    listing([]);
    const bodies: unknown[] = [];
    server.use(
      http.post('*/api/v1/clusters/c1/sql/index', async ({ request }) => {
        bodies.push(await request.json());
        return HttpResponse.json(previewBody);
      }),
    );
    const user = userEvent.setup();
    renderWithProviders(<IndexSubscriptions />);

    await fillCapture(user);
    expect(screen.getByRole('button', { name: 'Hide capture bounds' })).toHaveAttribute('aria-expanded', 'true');
    await user.click(screen.getByRole('button', { name: 'Preview capture' }));

    await waitFor(() => expect(bodies).toHaveLength(1));
    expect(bodies[0]).toEqual({
      subscriptionPattern: 'ORDER.#',
      retentionDays: 7,
      intervalMs: 5000,
      enabled: true,
      mode: 'CAPTURE',
      filterString: "tenant = 'acme'",
      ringSize: 500,
      maxBytes: 2 * 1024 * 1024,
      maxRate: 50,
      bodyCapBytes: 4 * 1024,
    });
    expect(await screen.findByText('Covers 2 destinations: ORDER.IN, ORDER.OUT')).toBeInTheDocument();
    expect(screen.getByText('Installed on 2 live nodes: primary, backup')).toBeInTheDocument();
    expect(screen.getByText(/at most 10,000 messages or 500 B, whichever is reached first/)).toBeInTheDocument();
    expect(screen.getByText('divert rahdar.capture.abc.ORDER.IN.s1')).toBeInTheDocument();
    expect(screen.getByText('<diverts/>')).toBeInTheDocument();
    // The form is frozen on what was previewed until the operator asks to edit it.
    expect(screen.getByLabelText(/Capture filter/)).toHaveAttribute('readonly');
    expect(screen.getByLabelText(/Ring size/)).toHaveAttribute('readonly');

    await user.click(screen.getByRole('button', { name: 'Edit' }));
    expect(screen.queryByText(/Covers 2 destinations/)).toBeNull();
    expect(screen.getByLabelText('Subscription or pattern')).not.toHaveAttribute('readonly');
    expect(screen.getByRole('button', { name: 'Preview capture' })).toBeEnabled();
  });

  it('sends only the bounds that were filled, and the capture toggle back off drops them', async () => {
    mockMe();
    listing([]);
    const bodies: unknown[] = [];
    server.use(
      http.post('*/api/v1/clusters/c1/sql/index', async ({ request }) => {
        bodies.push(await request.json());
        return HttpResponse.json({
          ...previewBody,
          destinations: ['artemis.address/ORDER.IN'],
          nodes: ['primary'],
          brokerXml: null,
        });
      }),
    );
    const user = userEvent.setup();
    renderWithProviders(<IndexSubscriptions />);

    await user.type(await screen.findByLabelText('Subscription or pattern'), 'ORDER.IN');
    await user.click(screen.getByRole('radio', { name: /capture everything/i }));
    await user.click(screen.getByRole('button', { name: 'Preview capture' }));

    await waitFor(() => expect(bodies).toHaveLength(1));
    expect(bodies[0]).toEqual({
      subscriptionPattern: 'ORDER.IN',
      retentionDays: 7,
      intervalMs: 5000,
      enabled: true,
      mode: 'CAPTURE',
    });
    expect(await screen.findByText('Covers 1 destination: ORDER.IN')).toBeInTheDocument();
    expect(screen.getByText('Installed on 1 live node: primary')).toBeInTheDocument();
    expect(screen.queryByText('The equivalent broker.xml')).toBeNull();
  });

  it('says why a preview failed', async () => {
    mockMe();
    listing([]);
    server.use(
      http.post('*/api/v1/clusters/c1/sql/index', () =>
        HttpResponse.json({ title: 'No live node', detail: 'No node of this cluster is live.' }, { status: 409 }),
      ),
    );
    const user = userEvent.setup();
    renderWithProviders(<IndexSubscriptions />);

    await user.type(await screen.findByLabelText('Subscription or pattern'), 'ORDER.IN');
    await user.click(screen.getByRole('radio', { name: /capture everything/i }));
    await user.click(screen.getByRole('button', { name: 'Preview capture' }));

    const alert = (await screen.findByText('This conflicts with the current state')).closest('[role="alert"]');
    expect(alert).toHaveTextContent('This conflicts with the current state');
    expect(alert).toHaveTextContent('No node of this cluster is live.');
  });

  it('names the subscriptions when no pattern is typed yet, and says capture records everything up to its bounds', async () => {
    mockMe();
    listing([]);
    const user = userEvent.setup();
    renderWithProviders(<IndexSubscriptions />);

    await user.click(await screen.findByRole('radio', { name: /capture everything/i }));
    expect(screen.getByText(/On each live node Rahdar will create a/)).toHaveTextContent('from these subscriptions');
    expect(screen.getByText(/Capture records everything the destination routed, up to its bounds/)).toBeInTheDocument();
    expect(screen.getByText(/for 7 days or until the size bound is reached/)).toBeInTheDocument();

    await user.click(screen.getByRole('radio', { name: /^sample$/i }));
    expect(screen.getByText(/Sampling records what was seen, not everything that passed through/)).toBeInTheDocument();
    expect(screen.queryByLabelText(/Capture filter/)).toBeNull();
  });
});
