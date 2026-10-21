import { describe, expect, it } from 'vitest';
import { holdButton } from '../../test/hold.ts';
import { http, HttpResponse } from 'msw';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import {
  createMemoryHistory,
  createRootRoute,
  createRoute,
  createRouter,
  Outlet,
  RouterProvider,
} from '@tanstack/react-router';

import { renderWithProviders } from '../../test/render.tsx';
import { CONTRACT, defineFeature, type RahdarFeature } from '../feature.ts';
import { FeatureProvider } from '../FeatureProvider.tsx';
import { server } from '../../test/setup.ts';
import type { HeldDecision, HeldOperationDetail } from './api.ts';
import { ApprovalView } from './ApprovalView.tsx';
import { detail, HELD_ID } from './fixtures.ts';

const PATH = `*/api/v1/held-operations/${HELD_ID}`;

function problem(slug: string, status: number, detailText?: string) {
  return HttpResponse.json(
    { type: `https://rahdar/problems/${slug}`, title: slug, status, detail: detailText },
    { status, headers: { 'Content-Type': 'application/problem+json' } },
  );
}

function me(authenticatedAt = new Date().toISOString()) {
  return http.get('*/api/v1/auth/me', () =>
    HttpResponse.json({
      id: 'u-bob',
      username: 'bob',
      mustChangePassword: false,
      grants: [{ scopeType: 'GLOBAL', scopeId: null, permissions: ['*'] }],
      reauthentication: { method: 'PASSWORD', startPath: null, authenticatedAt, windowSeconds: 300 },
    }),
  );
}

/** Serves the request; each GET answers the next of `views`, then repeats the last. Counts the GETs. */
function serve(...views: HeldOperationDetail[]) {
  const seen = { gets: 0 };
  server.use(
    me(),
    http.get(PATH, () => {
      const view = views[Math.min(seen.gets, views.length - 1)];
      seen.gets += 1;
      return HttpResponse.json(view);
    }),
  );
  return seen;
}

function renderPage(features?: RahdarFeature[]) {
  const root = createRootRoute({ component: Outlet });
  const list = createRoute({ getParentRoute: () => root, path: 'approvals', component: () => null });
  const page = createRoute({ getParentRoute: () => root, path: 'approvals/$id', component: ApprovalView });
  const router = createRouter({
    routeTree: root.addChildren([list, page]),
    history: createMemoryHistory({ initialEntries: [`/approvals/${HELD_ID}`] }),
  });
  const app = <RouterProvider router={router as never} />;
  return renderWithProviders(features ? <FeatureProvider features={features}>{app}</FeatureProvider> : app);
}

describe('ApprovalView', () => {
  it('shows what will happen, the request and the timeline to an approver', async () => {
    serve(detail());
    renderPage();
    expect(
      await screen.findByRole('heading', { level: 1, name: 'Purge subscription orders.dlq on prod-eu' }),
    ).toBeInTheDocument();
    expect(screen.getByText('Waiting for approval')).toBeInTheDocument();
    expect(screen.getByRole('heading', { level: 2, name: 'What will happen' })).toBeInTheDocument();
    expect(screen.getByText('orders.dlq')).toBeInTheDocument();
    expect(screen.getByText('1,204')).toBeInTheDocument();
    expect(screen.getByText('messages')).toBeInTheDocument();
    expect(screen.getByText('Signed-in session')).toBeInTheDocument();
    expect(screen.getByText('Two people for destructive changes')).toBeInTheDocument();
    expect(screen.getByText('Anyone with the operator role on prod-eu')).toBeInTheDocument();
    const timeline = screen.getByRole('list', { name: 'Timeline' });
    expect(within(timeline).getByText('Requested')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Approve…' })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Cancel request' })).not.toBeInTheDocument();
  });

  it('names the cluster once, in the facts, when the operation also lists it among its rows', async () => {
    serve(detail({}, { clusterId: 'c-1' }));
    renderPage();
    await screen.findByRole('heading', { level: 2, name: 'What will happen' });
    expect(screen.getAllByText('Cluster')).toHaveLength(1);
    expect(screen.queryByText('prod-eu', { exact: true })).not.toBeInTheDocument();
  });

  it('keeps the operation’s Cluster row when the request names no cluster of its own', async () => {
    serve(detail());
    renderPage();
    expect(await screen.findByText('prod-eu', { exact: true })).toBeInTheDocument();
  });

  it('shows changed values as a table, with a secret as hidden', async () => {
    serve(
      detail({
        display: [
          { label: 'Max delivery attempts', from: '10', to: '3' },
          { label: 'Password', from: '[redacted]', to: '[redacted]' },
          { label: 'Expiry address', from: null, to: 'DLQ' },
        ],
      }),
    );
    renderPage();
    const table = await screen.findByRole('table', { name: 'Changes' });
    expect(within(table).getByRole('columnheader', { name: 'Current' })).toBeInTheDocument();
    expect(within(table).getAllByText('Hidden')).toHaveLength(2);
    expect(within(table).getByText('Not set')).toBeInTheDocument();
    expect(within(table).queryByText('[redacted]')).not.toBeInTheDocument();
  });

  it('states an effect it could not estimate as unavailable, never as zero', async () => {
    serve(detail({ effect: null as never }));
    renderPage();
    expect(await screen.findByText('Unavailable')).toBeInTheDocument();
  });

  it('tells the requester someone else decides and offers Cancel, with no decision to make', async () => {
    serve(
      detail({
        mine: true,
        canDecide: false,
        canCancel: true,
        decideRefusal: 'You made this request, so someone else must decide it.',
      }),
    );
    renderPage();
    expect(await screen.findByText('Someone else decides')).toBeVisible();
    expect(screen.getByText('It runs only once another person approves it.', { exact: false })).toBeVisible();
    expect(screen.queryByRole('heading', { name: 'Decision' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Approve…' })).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Cancel request' })).toBeInTheDocument();
  });

  it('warns when the requester lost the permission', async () => {
    serve(detail({ requesterLacksPermission: true }));
    renderPage();
    expect(await screen.findByText('The requester lost the permission')).toBeInTheDocument();
  });

  it('shows an approved request as running, read-only', async () => {
    serve(detail({ canDecide: false }, { state: 'EXECUTING', approverUsername: 'bob' }));
    renderPage();
    expect(await screen.findByText('bob approved it, and Rahdar is running it now.')).toBeInTheDocument();
    expect(screen.queryByRole('heading', { name: 'Decision' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /Approve/ })).not.toBeInTheDocument();
  });

  it('shows a succeeded request with its outcome', async () => {
    serve(
      detail(
        { canDecide: false, outcomeDetail: 'Removed 1,204 messages.' },
        { state: 'SUCCEEDED', approverUsername: 'bob', finishedAt: '2026-10-07T10:05:00Z' },
      ),
    );
    renderPage();
    expect(await screen.findByText('Removed 1,204 messages.')).toBeInTheDocument();
    expect(screen.getAllByText('Succeeded').length).toBeGreaterThan(0);
  });

  it('shows a request refused at run time as an alert with its cause', async () => {
    serve(
      detail(
        { canDecide: false, outcomeDetail: 'The subscription changed since it was requested.' },
        { state: 'REFUSED', approverUsername: 'bob' },
      ),
    );
    renderPage();
    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent('The subscription changed since it was requested.');
    expect(alert).toHaveTextContent('Nothing was changed');
    expect(screen.getByRole('heading', { level: 2, name: 'What it asked for' })).toBeInTheDocument();
  });

  it('shows a rejected request with the approver and their reason', async () => {
    serve(
      detail({ canDecide: false, decisionReason: 'Drain it instead' }, { state: 'REJECTED', approverUsername: 'bob' }),
    );
    renderPage();
    expect(await screen.findByText('It will not run')).toBeInTheDocument();
    expect(screen.getByText('bob rejected it: “Drain it instead”')).toBeInTheDocument();
  });

  it('shows an expired request as closed, with no countdown and no decision', async () => {
    serve(detail({ canDecide: false }, { state: 'EXPIRED' }));
    renderPage();
    expect(await screen.findByText('Nobody decided it in time', { exact: false })).toBeInTheDocument();
    expect(screen.queryByText(/left$/)).not.toBeInTheDocument();
    expect(screen.queryByRole('heading', { name: 'Decision' })).not.toBeInTheDocument();
  });

  it('says a request that does not exist, or is not the caller’s to see, cannot be shown', async () => {
    server.use(
      me(),
      http.get(PATH, () => problem('not-found', 404)),
    );
    renderPage();
    expect(await screen.findByRole('heading', { level: 1, name: 'Request not found' })).toBeInTheDocument();
    expect(screen.getByText('This request does not exist or you cannot see it')).toBeInTheDocument();
  });

  it('shows a failed read as an error with Retry', async () => {
    server.use(
      me(),
      http.get(PATH, () => problem('internal', 500)),
    );
    renderPage();
    expect(await screen.findByRole('alert')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /retry/i })).toBeInTheDocument();
  });

  it('counts down to expiry and asks again once the time is up', async () => {
    const soon = detail({}, { expiresAt: new Date(Date.now() + 1_500).toISOString() });
    const seen = serve(soon, detail({ canDecide: false }, { state: 'EXPIRED' }));
    renderPage();
    expect(await screen.findByText(/^\ds left$/)).toBeInTheDocument();
    expect(await screen.findByText('Nobody decided it in time', { exact: false })).toBeInTheDocument();
    expect(seen.gets).toBeGreaterThanOrEqual(2);
  });

  it('shows a long time left in hours and minutes', async () => {
    serve(detail({}, { expiresAt: new Date(Date.now() + 90 * 60_000 + 30_000).toISOString() }));
    renderPage();
    expect(await screen.findByText(/^1h 30m left$/)).toBeInTheDocument();
  });

  it('approves after a confirmation that repeats what is approved, bound to what was shown', async () => {
    const sent: HeldDecision[] = [];
    serve(detail(), detail({ canDecide: false }, { state: 'EXECUTING', approverUsername: 'bob' }));
    server.use(
      http.post(`${PATH}/decision`, async ({ request }) => {
        sent.push((await request.json()) as HeldDecision);
        return HttpResponse.json(detail({ canDecide: false }, { state: 'EXECUTING', approverUsername: 'bob' }));
      }),
    );
    renderPage();
    const user = userEvent.setup();
    const trigger = await screen.findByRole('button', { name: 'Approve…' });
    await user.click(trigger);
    const dialog = await screen.findByRole('dialog', { name: 'Approve this request?' });
    expect(dialog).toHaveTextContent('Purge subscription orders.dlq on prod-eu');
    expect(dialog).toHaveTextContent('1,204 messages');
    // Keyboard: focus starts inside the dialog, Escape dismisses it, and focus returns to the trigger.
    expect(dialog).toContainElement(document.activeElement as HTMLElement);
    await user.keyboard('{Escape}');
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
    await waitFor(() => expect(trigger).toHaveFocus());

    await user.click(trigger);
    await holdButton(within(await screen.findByRole('dialog')).getByRole('button', { name: 'Approve' }));
    await waitFor(() => expect(sent).toHaveLength(1));
    expect(sent[0]).toEqual({ vote: 'APPROVE', reason: null, paramsHash: 'hash-1', version: 3 });
    expect(await screen.findByText('bob approved it, and Rahdar is running it now.')).toBeInTheDocument();
  });

  it('requires a reason to reject, beside the field', async () => {
    const sent: HeldDecision[] = [];
    serve(detail());
    server.use(
      http.post(`${PATH}/decision`, async ({ request }) => {
        sent.push((await request.json()) as HeldDecision);
        return HttpResponse.json(
          detail({ canDecide: false, decisionReason: 'Drain it instead' }, { state: 'REJECTED' }),
        );
      }),
    );
    renderPage();
    const user = userEvent.setup();
    await user.click(await screen.findByRole('button', { name: 'Reject…' }));
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    const reason = screen.getByRole('textbox', { name: 'Reason' });
    expect(reason).toHaveFocus();
    expect(screen.getByText('Give a reason to reject: the requester reads it.')).toBeInTheDocument();

    await user.type(reason, 'Drain it instead');
    await user.click(screen.getByRole('button', { name: 'Reject…' }));
    const dialog = await screen.findByRole('dialog', { name: 'Reject this request?' });
    expect(dialog).toHaveTextContent('Drain it instead');
    await user.click(within(dialog).getByRole('button', { name: 'Reject' }));
    await waitFor(() => expect(sent).toHaveLength(1));
    expect(sent[0]).toMatchObject({ vote: 'REJECT', reason: 'Drain it instead' });
  });

  it('refetches and says so when the request changed while it was being reviewed', async () => {
    const seen = serve(detail(), detail({ version: 4, paramsHash: 'hash-2' }));
    server.use(
      http.post(`${PATH}/decision`, () =>
        problem('held-operation-changed', 409, 'The request changed after you opened it.'),
      ),
    );
    renderPage();
    const user = userEvent.setup();
    await user.click(await screen.findByRole('button', { name: 'Approve…' }));
    await holdButton(within(await screen.findByRole('dialog')).getByRole('button', { name: 'Approve' }));
    expect(await screen.findByText('The request changed')).toBeInTheDocument();
    expect(screen.getByText(/nothing was decided/)).toBeInTheDocument();
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
    await waitFor(() => expect(seen.gets).toBeGreaterThanOrEqual(2));
  });

  it('asks a stale session to confirm it is you, then sends the same vote again', async () => {
    let signedIn = new Date(Date.now() - 3_600_000).toISOString();
    const sent: HeldDecision[] = [];
    serve(detail());
    server.use(
      http.get('*/api/v1/auth/me', () =>
        HttpResponse.json({
          id: 'u-bob',
          username: 'bob',
          mustChangePassword: false,
          grants: [{ scopeType: 'GLOBAL', scopeId: null, permissions: ['*'] }],
          reauthentication: { method: 'PASSWORD', startPath: null, authenticatedAt: signedIn, windowSeconds: 300 },
        }),
      ),
      http.post(`${PATH}/decision`, async ({ request }) => {
        sent.push((await request.json()) as HeldDecision);
        if (new Date(signedIn).getTime() < Date.now() - 300_000) return problem('reauthentication-required', 403);
        return HttpResponse.json(detail({ canDecide: false }, { state: 'EXECUTING', approverUsername: 'bob' }));
      }),
      http.post('*/api/v1/auth/reauthenticate', () => {
        signedIn = new Date().toISOString();
        return HttpResponse.json({ status: 'AUTHENTICATED', methods: [] });
      }),
    );
    renderPage();
    const user = userEvent.setup();
    await user.click(await screen.findByRole('button', { name: 'Approve…' }));
    await holdButton(within(await screen.findByRole('dialog')).getByRole('button', { name: 'Approve' }));
    await user.type(await screen.findByLabelText('Your password'), 'secret');
    await user.click(screen.getByRole('button', { name: 'Confirm' }));
    await waitFor(() => expect(sent).toHaveLength(2));
    expect(sent[1]).toEqual(sent[0]);
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
  });

  it('shows a refused vote in the dialog with its reason', async () => {
    serve(detail());
    server.use(
      http.post(`${PATH}/decision`, () =>
        problem('vote-refused', 403, 'You approved a change to this subscription today.'),
      ),
    );
    renderPage();
    const user = userEvent.setup();
    await user.click(await screen.findByRole('button', { name: 'Approve…' }));
    const dialog = await screen.findByRole('dialog');
    await holdButton(within(dialog).getByRole('button', { name: 'Approve' }));
    expect(
      await within(dialog).findByText('You approved a change to this subscription today.', { exact: false }),
    ).toBeVisible();
  });

  it('lets the requester cancel after a confirmation saying what cancelling does', async () => {
    let cancelled = 0;
    const closed = detail({ mine: true, canDecide: false, canCancel: false }, { state: 'CANCELLED' });
    serve(detail({ mine: true, canDecide: false, canCancel: true }), closed);
    server.use(
      http.post(`${PATH}/cancel`, () => {
        cancelled += 1;
        return HttpResponse.json(closed);
      }),
    );
    renderPage();
    const user = userEvent.setup();
    await user.click(await screen.findByRole('button', { name: 'Cancel request' }));
    const dialog = await screen.findByRole('dialog', { name: 'Cancel this request?' });
    expect(dialog).toHaveTextContent('It will not run, and its approvers are told it was cancelled.');
    expect(dialog).toHaveTextContent('Purge subscription orders.dlq on prod-eu');
    await user.click(within(dialog).getByRole('button', { name: 'Cancel request' }));
    await waitFor(() => expect(cancelled).toBe(1));
    expect(await screen.findByText('alice cancelled it.')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Cancel request' })).not.toBeInTheDocument();
  });

  it('shows a provider’s decision controls above Rahdar’s own', async () => {
    serve(detail());
    const provider = defineFeature({
      contract: CONTRACT,
      id: 'rr',
      slots: {
        'approval.decision': [
          {
            id: 'policy-check',
            order: 0,
            Component: ({ heldOperation }) => <p>Policy check for {heldOperation.operation.summary}</p>,
          },
        ],
      },
    });
    renderPage([provider]);
    expect(await screen.findByText('Policy check for Purge subscription orders.dlq on prod-eu')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Approve…' })).toBeInTheDocument();
  });
});
