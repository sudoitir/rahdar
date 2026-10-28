import { afterEach, describe, expect, it, vi } from 'vitest';
import { holdButton } from '../../test/hold.ts';
import { act, screen, waitFor, within, fireEvent } from '@testing-library/react';
import { Notifications, notifications } from '@mantine/notifications';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';

import { renderWithProviders } from '../../test/render.tsx';
import { server } from '../../test/setup.ts';
import type { SubscriptionSelection } from '../../kernel/slots.ts';
import type { BulkItemView, BulkOperation, BulkRunDetailView, BulkRunView } from './api.ts';

const AVAILABLE = { status: 'AVAILABLE', reason: 'ok', brokerXmlSnippet: null };

function clusterHandler() {
  return http.get('*/api/v1/clusters/c1', () =>
    HttpResponse.json({
      id: 'c1',
      name: 'c1',
      description: null,
      topology: { nodes: [] },
      capabilities: {
        managementRead: AVAILABLE,
        managementWrite: AVAILABLE,
        notifications: AVAILABLE,
        messageIo: AVAILABLE,
        slowConsumerDetection: AVAILABLE,
        versionGates: [],
      },
      health: { level: 'OK', reasons: [] },
      environmentId: null,
    }),
  );
}

function meHandler(permissions: string[] = ['*']) {
  return http.get('*/api/v1/auth/me', () =>
    HttpResponse.json({
      id: 'u1',
      username: 'admin',
      mustChangePassword: false,
      grants: [{ scopeType: 'GLOBAL', scopeId: null, permissions }],
    }),
  );
}

function run(over: Partial<BulkRunView> = {}): BulkRunView {
  return {
    id: 'r1',
    clusterId: 'c1',
    operation: 'DELETE',
    status: 'PREVIEWED',
    username: 'admin',
    createdAt: '2026-09-21T10:00:00Z',
    expiresAt: '2026-09-21T10:10:00Z',
    startedAt: null,
    finishedAt: null,
    total: 3,
    succeeded: 0,
    failed: 0,
    skipped: 0,
    estimate: 40,
    estimateComplete: false,
    cap: 1000,
    overCap: false,
    overrideCap: false,
    continueOnFailure: false,
    disconnectConsumers: false,
    selection: { names: ['orders.a', 'orders.b', 'orders.c'], q: null },
    planHash: 'h1',
    auditEventId: 7,
    error: null,
    ...over,
  };
}

const nodeA = { nodeId: 'n1', nodeName: 'node-a', paused: false };
const nodeB = { nodeId: 'n2', nodeName: 'node-b', paused: false };

function item(over: Partial<BulkItemView> = {}): BulkItemView {
  return {
    ordinal: 0,
    subscription: 'artemis.queue/orders.a',
    kind: 'artemis.queue',
    kindLabel: { singular: 'Queue', plural: 'Queues' },
    status: 'PENDING',
    error: null,
    warning: null,
    affected: 40,
    nodes: [
      { ...nodeA, messageCount: 30, consumerCount: 0 },
      { ...nodeB, messageCount: 10, consumerCount: 0 },
    ],
    outcome: null,
    startedAt: null,
    finishedAt: null,
    ...over,
  };
}

/** A delete of three subscriptions: one known, one with an unknown figure, one refused. */
function deletePreview(): BulkRunDetailView {
  return {
    run: run(),
    items: [
      item(),
      item({
        ordinal: 1,
        subscription: 'artemis.queue/orders.b',
        affected: null,
        warning: 'node-b has not answered recently, so its message count is unknown',
        nodes: [{ ...nodeB, messageCount: null, consumerCount: null }],
      }),
      item({
        ordinal: 2,
        subscription: 'artemis.queue/orders.c',
        status: 'REFUSED',
        error: 'It has consumers attached',
        affected: 5,
        nodes: [{ ...nodeA, messageCount: 5, consumerCount: 2 }],
      }),
    ],
  };
}

afterEach(() => act(() => notifications.clean()));

const navigate = vi.fn();
vi.mock('@tanstack/react-router', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@tanstack/react-router')>()),
  useParams: () => ({ clusterId: 'c1', runId: 'r1' }),
  useSearch: () => ({}),
  useNavigate: () => navigate,
  Link: ({ children, to, search }: { children: React.ReactNode; to: string; search?: Record<string, string> }) => (
    <a href={search ? `${to}?${new URLSearchParams(search)}` : to}>{children}</a>
  ),
}));

const { BulkActionBar } = await import('./BulkActionBar.tsx');
const { BulkRunView } = await import('./BulkRunView.tsx');

const THREE: SubscriptionSelection = { kind: 'names', names: ['orders.a', 'orders.b', 'orders.c'] };

function Bar({ selection = THREE, count = 3 }: { selection?: SubscriptionSelection; count?: number }) {
  return <BulkActionBar clusterId="c1" selection={selection} count={count} clear={() => {}} />;
}

/** A preview handler that records each request body, the payload the server must accept (ADR-0096). */
function capturePreview() {
  const bodies: unknown[] = [];
  const handler = http.post('*/api/v1/clusters/c1/bulk/preview', async ({ request }) => {
    bodies.push(await request.json());
    return HttpResponse.json(deletePreview(), { status: 201 });
  });
  return { bodies, handler };
}

const problem = (status: number, type: string, title: string, detail: string) =>
  HttpResponse.json({ type: `https://rahdar.dev/problems/${type}`, title, status, detail }, { status });

async function openPreview(user: ReturnType<typeof userEvent.setup>, label = 'Delete…') {
  await waitFor(() => expect(screen.getByRole('button', { name: label })).toBeEnabled());
  await user.click(screen.getByRole('button', { name: label }));
  return screen.findByRole('dialog');
}

describe('BulkActionBar', () => {
  it('keeps an action the operator may not take visible, disabled, with a reachable reason', async () => {
    server.use(meHandler(['subscription:pause', 'cluster:read']), clusterHandler());
    const user = userEvent.setup();
    renderWithProviders(<Bar />);

    await waitFor(() => expect(screen.getByRole('button', { name: 'Delete…' })).toBeDisabled());
    expect(screen.getByRole('button', { name: 'Pause' })).toBeEnabled();

    await user.click(screen.getByRole('button', { name: 'Why deleting these subscriptions is unavailable' }));
    expect(await screen.findByText(/Destroy subscriptions and destinations/)).toBeInTheDocument();
  });

  it('offers every action disabled while nothing is selected', async () => {
    server.use(meHandler(), clusterHandler());
    const { rerender } = renderWithProviders(<Bar />);
    // Grants and capabilities have landed and allow every action, so what follows is the selection's doing.
    await waitFor(() => expect(screen.getByRole('button', { name: 'Delete…' })).toBeEnabled());

    rerender(<Bar selection={{ kind: 'names', names: [] }} count={0} />);
    for (const label of ['Pause', 'Resume', 'Purge…', 'Delete…']) {
      expect(screen.getByRole('button', { name: label })).toBeDisabled();
    }
  });
});

describe('BulkPreviewDialog', () => {
  const LABEL: Record<BulkOperation, string> = { PAUSE: 'Pause', RESUME: 'Resume', PURGE: 'Purge…', DELETE: 'Delete…' };

  it.each(['PAUSE', 'RESUME', 'PURGE', 'DELETE'] as const)(
    'previews %s with every field the server requires',
    async (operation) => {
      const preview = capturePreview();
      server.use(meHandler(), clusterHandler(), preview.handler);
      const user = userEvent.setup();
      renderWithProviders(<Bar />);

      await openPreview(user, LABEL[operation]);

      await waitFor(() => expect(preview.bodies).toHaveLength(1));
      expect(preview.bodies[0]).toEqual({
        operation,
        names: ['orders.a', 'orders.b', 'orders.c'],
        q: null,
        disconnectConsumers: false,
      });
    },
  );

  it('previews "all matching" as the filter, not as names', async () => {
    const preview = capturePreview();
    server.use(meHandler(), clusterHandler(), preview.handler);
    const user = userEvent.setup();
    renderWithProviders(<Bar selection={{ kind: 'filter', q: 'orders', total: 140 }} count={140} />);

    await openPreview(user, 'Pause');

    await waitFor(() => expect(preview.bodies).toHaveLength(1));
    expect(preview.bodies[0]).toEqual({ operation: 'PAUSE', names: null, q: 'orders', disconnectConsumers: false });
  });

  it('previews a partial selection as exactly the names picked', async () => {
    const preview = capturePreview();
    server.use(meHandler(), clusterHandler(), preview.handler);
    const user = userEvent.setup();
    renderWithProviders(<Bar selection={{ kind: 'names', names: ['orders.b'] }} count={1} />);

    await openPreview(user, 'Purge…');

    await waitFor(() => expect(preview.bodies).toHaveLength(1));
    expect(preview.bodies[0]).toEqual({ operation: 'PURGE', names: ['orders.b'], q: null, disconnectConsumers: false });
  });

  it('states a rejected preview (400) and offers to preview again', async () => {
    let calls = 0;
    server.use(
      meHandler(),
      clusterHandler(),
      http.post('*/api/v1/clusters/c1/bulk/preview', () => {
        calls += 1;
        return problem(400, 'validation', 'Invalid request', 'One or more fields are invalid.');
      }),
    );
    const user = userEvent.setup();
    renderWithProviders(<Bar />);

    const dialog = await openPreview(user);
    const alert = await within(dialog).findByRole('alert');
    expect(alert).toHaveTextContent('Invalid request');
    expect(alert).toHaveTextContent('One or more fields are invalid.');

    await user.click(within(dialog).getByRole('button', { name: 'Preview again' }));
    await waitFor(() => expect(calls).toBe(2));
  });

  it('states why a preview was refused (422) and what to change', async () => {
    server.use(
      meHandler(),
      clusterHandler(),
      http.post('*/api/v1/clusters/c1/bulk/preview', () =>
        problem(
          422,
          'bulk-subscription-cap-exceeded',
          'Bulk run refused',
          '140 subscriptions matched; a bulk run is capped at 100 subscriptions (safety.bulk-subscription-cap). Narrow the selection.',
        ),
      ),
    );
    const user = userEvent.setup();
    renderWithProviders(<Bar />);

    const dialog = await openPreview(user);
    const alert = await within(dialog).findByRole('alert');
    expect(alert).toHaveTextContent('Bulk run refused');
    expect(alert).toHaveTextContent('140 subscriptions matched; a bulk run is capped at 100 subscriptions');
    expect(alert).toHaveTextContent('Narrow the selection.');
    expect(within(dialog).getByRole('button', { name: 'Preview again' })).toBeInTheDocument();
  });

  it('says nothing ran when the plan changed (409), and offers a fresh preview', async () => {
    server.use(
      meHandler(),
      clusterHandler(),
      http.post('*/api/v1/clusters/c1/bulk/preview', () =>
        HttpResponse.json({ ...deletePreview(), run: run({ operation: 'PAUSE' }) }, { status: 201 }),
      ),
      http.post('*/api/v1/clusters/c1/bulk/runs/r1/execute', () =>
        problem(
          409,
          'bulk-plan-mismatch',
          'Conflict',
          'This is not the plan that was previewed. Preview again and confirm what it shows.',
        ),
      ),
    );
    const user = userEvent.setup();
    renderWithProviders(<Bar />);

    const dialog = await openPreview(user, 'Pause');
    await user.click(await within(dialog).findByRole('button', { name: 'Pause 2 subscriptions' }));

    const alert = await within(dialog).findByRole('alert');
    expect(alert).toHaveTextContent('This is not the plan that was previewed.');
    expect(within(dialog).getByText(/Nothing was run\./)).toBeInTheDocument();
    expect(within(dialog).getByRole('button', { name: 'Preview again' })).toBeInTheDocument();
  });

  it('states the blast radius, says what is unknown, and lists the refused subscription', async () => {
    server.use(
      meHandler(),
      clusterHandler(),
      http.post('*/api/v1/clusters/c1/bulk/preview', () => HttpResponse.json(deletePreview(), { status: 201 })),
    );
    const user = userEvent.setup();
    renderWithProviders(<Bar />);

    await waitFor(() => expect(screen.getByRole('button', { name: 'Delete…' })).toBeEnabled());
    await user.click(screen.getByRole('button', { name: 'Delete…' }));
    const dialog = await screen.findByRole('dialog');

    expect(await within(dialog).findByText(/Delete 2 subscriptions on 2 nodes/)).toBeInTheDocument();
    // An incomplete estimate is a floor, and the gap is stated rather than read as zero.
    expect(within(dialog).getByText(/at least 40 messages/)).toBeInTheDocument();
    expect(within(dialog).getByText(/1 subscription has a figure Rahdar does not know/)).toBeInTheDocument();
    expect(within(dialog).getByText(/1 subscription is refused and will not be touched/)).toBeInTheDocument();
    expect(within(dialog).getByText('It has consumers attached')).toBeInTheDocument();
    expect(within(dialog).getAllByText('unknown').length).toBeGreaterThan(0);
  });

  it('cannot arm a delete until the action and count are typed, then runs it and opens the run', async () => {
    let executed: unknown = null;
    server.use(
      meHandler(),
      clusterHandler(),
      http.post('*/api/v1/clusters/c1/bulk/preview', () => HttpResponse.json(deletePreview(), { status: 201 })),
      http.post('*/api/v1/clusters/c1/bulk/runs/r1/execute', async ({ request }) => {
        executed = await request.json();
        return HttpResponse.json(run({ status: 'RUNNING' }), { status: 202 });
      }),
    );
    const user = userEvent.setup();
    renderWithProviders(<Bar />);

    await waitFor(() => expect(screen.getByRole('button', { name: 'Delete…' })).toBeEnabled());
    await user.click(screen.getByRole('button', { name: 'Delete…' }));
    const dialog = await screen.findByRole('dialog');
    await within(dialog).findByText(/Delete 2 subscriptions on 2 nodes/);

    // A press that is let go too early does nothing; the full hold runs it.
    const confirm = within(dialog).getByRole('button', { name: 'Delete 2 subscriptions' });
    fireEvent.mouseDown(confirm);
    fireEvent.mouseUp(confirm);
    expect(executed).toBeNull();
    await holdButton(confirm);

    await waitFor(() => expect(navigate).toHaveBeenCalledWith({ to: '/clusters/c1/bulk/r1' }));
    expect(executed).toEqual({ planHash: 'h1', override: false, continueOnFailure: false });
  });

  it('is keyboard-complete: focus enters the dialog, escape dismisses it, focus returns', async () => {
    server.use(
      meHandler(),
      clusterHandler(),
      http.post('*/api/v1/clusters/c1/bulk/preview', () => HttpResponse.json(deletePreview(), { status: 201 })),
    );
    const user = userEvent.setup();
    renderWithProviders(<Bar />);

    const trigger = screen.getByRole('button', { name: 'Delete…' });
    await waitFor(() => expect(trigger).toBeEnabled());
    trigger.focus();
    await user.keyboard('{Enter}');

    const dialog = await screen.findByRole('dialog');
    await waitFor(() => expect(dialog.contains(document.activeElement)).toBe(true));

    await user.keyboard('{Escape}');
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
    await waitFor(() => expect(screen.getByRole('button', { name: 'Delete…' })).toHaveFocus());
  });
});

const RUNNING = () =>
  http.get('*/api/v1/clusters/c1/bulk/runs/r1', () =>
    HttpResponse.json({
      run: run({ status: 'RUNNING', total: 3, succeeded: 1, startedAt: '2026-09-21T10:01:00Z' }),
      items: [
        item({ status: 'SUCCEEDED' }),
        item({ ordinal: 1, subscription: 'artemis.queue/orders.b', status: 'RUNNING' }),
      ],
    }),
  );

describe('BulkRunView', () => {
  it('is one page with a single h1, and sections below it', async () => {
    server.use(meHandler(), RUNNING());
    renderWithProviders(<BulkRunView />);

    expect(await screen.findByRole('heading', { level: 1, name: 'Delete 3 subscriptions' })).toBeInTheDocument();
    expect(screen.getAllByRole('heading', { level: 1 })).toHaveLength(1);
    expect(screen.getByRole('heading', { level: 2, name: 'Outcome' })).toBeInTheDocument();
    expect(screen.getByRole('heading', { level: 2, name: 'Subscriptions' })).toBeInTheDocument();
  });

  it('holds the page header while the run loads, then says why it could not be read, with a retry', async () => {
    let calls = 0;
    server.use(
      meHandler(),
      http.get('*/api/v1/clusters/c1/bulk/runs/r1', () => {
        calls += 1;
        return HttpResponse.json({ title: 'Not Found', detail: 'Bulk run r1 does not exist.' }, { status: 404 });
      }),
    );
    renderWithProviders(<BulkRunView />);

    expect(screen.getByRole('heading', { level: 1, name: 'Bulk run' })).toBeInTheDocument();
    expect(screen.getByText('Loading the run')).toBeInTheDocument();
    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent('Not found');
    expect(alert).toHaveTextContent('Bulk run r1 does not exist.');
    expect(screen.getAllByRole('heading', { level: 1 })).toHaveLength(1);
    expect(calls).toBeGreaterThan(0);
  });

  it('retries a failed read from the error state', async () => {
    let calls = 0;
    server.use(
      meHandler(),
      http.get('*/api/v1/clusters/c1/bulk/runs/r1', () => {
        calls += 1;
        return calls === 1
          ? HttpResponse.json({ title: 'Error', detail: 'The cluster did not answer.' }, { status: 503 })
          : HttpResponse.json({ run: run({ status: 'SUCCEEDED', total: 1, succeeded: 1 }), items: [item()] });
      }),
    );
    const user = userEvent.setup();
    renderWithProviders(<BulkRunView />);

    await user.click(await screen.findByRole('button', { name: 'Retry' }));
    expect(await screen.findByRole('heading', { level: 1, name: 'Delete 1 subscription' })).toBeInTheDocument();
  });

  it('confirms before stopping a run, keeps the control busy, and announces the outcome', async () => {
    let stopped = 0;
    server.use(
      meHandler(),
      RUNNING(),
      http.post('*/api/v1/clusters/c1/bulk/runs/r1/stop', () => {
        stopped += 1;
        return HttpResponse.json(run({ status: 'STOPPED' }));
      }),
    );
    const user = userEvent.setup();
    renderWithProviders(
      <>
        <Notifications />
        <BulkRunView />
      </>,
    );

    const trigger = await screen.findByRole('button', { name: 'Stop run' });
    await waitFor(() => expect(trigger).toBeEnabled());
    trigger.focus();
    await user.keyboard('{Enter}');

    const dialog = await screen.findByRole('dialog', { name: 'Stop this run?' });
    expect(dialog).toHaveTextContent('every subscription after it is cancelled');
    expect(stopped).toBe(0);

    // Escape dismisses it, and focus returns to the control that opened it.
    await user.keyboard('{Escape}');
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
    await waitFor(() => expect(screen.getByRole('button', { name: 'Stop run' })).toHaveFocus());

    await user.click(screen.getByRole('button', { name: 'Stop run' }));
    await user.click(within(await screen.findByRole('dialog')).getByRole('button', { name: 'Stop run' }));
    await waitFor(() => expect(stopped).toBe(1));
    expect(await screen.findByText('Stopped this run')).toBeInTheDocument();
  });

  it('says why a stop failed and what to do next', async () => {
    server.use(
      meHandler(),
      RUNNING(),
      http.post('*/api/v1/clusters/c1/bulk/runs/r1/stop', () =>
        HttpResponse.json({ title: 'Conflict', detail: 'The run already finished.' }, { status: 409 }),
      ),
    );
    const user = userEvent.setup();
    renderWithProviders(
      <>
        <Notifications />
        <BulkRunView />
      </>,
    );

    const trigger = await screen.findByRole('button', { name: 'Stop run' });
    await waitFor(() => expect(trigger).toBeEnabled());
    await user.click(trigger);
    await user.click(within(await screen.findByRole('dialog')).getByRole('button', { name: 'Stop run' }));

    const failure = await screen.findByText('Could not stop this run');
    expect(failure).toBeInTheDocument();
    expect(screen.getByText(/The run already finished\. Reload to see how far the run got/)).toBeInTheDocument();
  });

  it('states and announces a partial outcome with its counts', async () => {
    server.use(
      meHandler(),
      http.get('*/api/v1/clusters/c1/bulk/runs/r1', () =>
        HttpResponse.json({
          run: run({ status: 'PARTIAL', total: 10, succeeded: 8, failed: 2, finishedAt: '2026-09-21T10:02:00Z' }),
          items: [
            item({ status: 'SUCCEEDED' }),
            item({
              ordinal: 1,
              subscription: 'artemis.queue/orders.b',
              status: 'FAILED',
              error: 'node-b refused the delete',
            }),
          ],
        }),
      ),
    );
    renderWithProviders(<BulkRunView />);

    const status = await screen.findByRole('status', { name: 'Run outcome' });
    await waitFor(() => expect(status).toHaveTextContent(/partial/i));
    expect(status).toHaveTextContent('8 succeeded');
    expect(status).toHaveTextContent('2 failed');
    expect(screen.getByRole('link', { name: /audit/i })).toHaveAttribute('href', '/clusters/c1/audit?parentId=7');
  });

  it('expands a subscription to its per-node detail', async () => {
    server.use(
      meHandler(),
      http.get('*/api/v1/clusters/c1/bulk/runs/r1', () =>
        HttpResponse.json({
          run: run({ status: 'FAILED', total: 1, failed: 1 }),
          items: [
            item({
              status: 'FAILED',
              error: 'node-b refused the delete',
              outcome: [
                { nodeId: 'n1', nodeName: 'node-a', status: 'APPLIED', affected: 30 },
                { nodeId: 'n2', nodeName: 'node-b', status: 'FAILED', error: 'node-b refused the delete' },
              ],
            }),
          ],
        }),
      ),
    );
    const user = userEvent.setup();
    renderWithProviders(<BulkRunView />);

    await user.click(await screen.findByRole('button', { name: 'Show node detail for orders.a' }));
    expect(await screen.findByText('Applied to some nodes and not others')).toBeInTheDocument();
  });
});
