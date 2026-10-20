import { afterEach, describe, expect, it } from 'vitest';
import { http, HttpResponse } from 'msw';
import { Notifications, notifications } from '@mantine/notifications';
import { act, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

import { renderWithProviders } from '../../test/render.tsx';
import { server } from '../../test/setup.ts';
import { ApiKeysPanel } from './ApiKeysPanel.tsx';
import { paged } from '../../kernel/api/paging.ts';
import { holdButton } from '../../test/hold.ts';

function renderKeys() {
  return renderWithProviders(
    <>
      <Notifications />
      <ApiKeysPanel />
    </>,
  );
}

afterEach(() => act(() => notifications.clean()));

const CATALOGUE = [
  {
    action: 'cluster:read',
    label: 'Read clusters',
    featureId: 'clusters',
    featureTitle: 'Clusters',
    scope: 'CLUSTER',
    resourceTypes: [],
    requires: [],
  },
  {
    action: 'subscription:purge',
    label: 'Purge queues',
    featureId: 'queues',
    featureTitle: 'Queues',
    scope: 'CLUSTER',
    resourceTypes: [],
    requires: [],
  },
  {
    action: 'message:read',
    label: 'Read messages',
    featureId: 'messages',
    featureTitle: 'Messages',
    scope: 'RESOURCE',
    resourceTypes: ['SUBSCRIPTION'],
    requires: [],
  },
];

const DAY = 86_400_000;

function token(over: Record<string, unknown> = {}) {
  return {
    id: 't1',
    name: 'laptop',
    owner: 'ada',
    prefix: 'as_abcd',
    expiresAt: new Date(Date.now() + 30 * DAY).toISOString(),
    lastUsedAt: null,
    revokedAt: null,
    createdAt: new Date().toISOString(),
    previousValidUntil: null,
    grants: [],
    mcpTools: [],
    stale: false,
    ...over,
  };
}

function mockBaseApis(grants: unknown[], tokens: unknown[] = []) {
  server.use(
    http.get('*/api/v1/auth/me', () =>
      HttpResponse.json({ id: 'u1', username: 'ada', mustChangePassword: false, grants }),
    ),
    http.get('*/api/v1/tokens', () => HttpResponse.json(paged(tokens))),
    http.get('*/api/v1/tokens/policy', () =>
      HttpResponse.json({
        maxLifetime: 'PT2160H',
        latestExpiry: new Date(Date.now() + 90 * DAY).toISOString(),
        rotationOverlap: 'PT24H',
      }),
    ),
    http.get('*/api/v1/mcp/tools', () =>
      HttpResponse.json(
        paged([
          { name: 'list_resources', posture: 'READ', summary: 'List resources' },
          { name: 'queue_lifecycle', posture: 'MUTATE', summary: 'Create, purge or delete a queue' },
        ]),
      ),
    ),
    http.get('*/api/v1/permissions', () => HttpResponse.json(paged(CATALOGUE))),
    http.get('*/api/v1/clusters', () => HttpResponse.json(paged([]))),
  );
}

describe('ApiKeysPanel', () => {
  it('lists existing keys with their expiry and tool restriction', async () => {
    mockBaseApis([], [token({ mcpTools: ['list_resources'] })]);
    renderKeys();

    expect(await screen.findByText('laptop')).toBeInTheDocument();
    expect(screen.getByText('as_abcd')).toBeInTheDocument();
    expect(screen.getByText('list_resources')).toBeInTheDocument();
  });

  it('teaches what a key is when there are none', async () => {
    mockBaseApis([]);
    renderKeys();

    expect(await screen.findByText(/You have no keys/)).toBeInTheDocument();
  });

  it('says so when the keys cannot be loaded', async () => {
    mockBaseApis([]);
    server.use(
      http.get('*/api/v1/tokens', () =>
        HttpResponse.json({ title: 'Boom', detail: 'Database unavailable' }, { status: 500 }),
      ),
    );
    renderKeys();

    expect(await screen.findByText('Rahdar failed to complete the request')).toBeInTheDocument();
    expect(screen.getByText('Database unavailable')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Retry' })).toBeInTheDocument();
  });

  it('offers only the permissions the user holds', async () => {
    mockBaseApis([{ scopeType: 'GLOBAL', scopeId: null, permissions: ['cluster:read'] }]);
    const user = userEvent.setup();
    renderKeys();

    await user.click(await screen.findByRole('button', { name: 'New key' }));

    expect(await screen.findByRole('button', { name: /Clusters, 0 of 1 selected/ })).toBeInTheDocument();
    // Offering a permission the server would strip out would mint a key that
    // silently does less than the operator ticked.
    expect(screen.queryByRole('button', { name: /Queues/ })).not.toBeInTheDocument();
  });

  it('posts the chosen grants, an expiry within the cap and the tools, and shows the value once', async () => {
    let posted: { grants?: unknown[]; expiresAt?: string; mcpTools?: string[] } = {};
    mockBaseApis([{ scopeType: 'GLOBAL', scopeId: null, permissions: ['cluster:read'] }]);
    server.use(
      http.post('*/api/v1/tokens', async ({ request }) => {
        posted = (await request.json()) as typeof posted;
        return HttpResponse.json(
          { token: token({ id: 't9', name: 'agent' }), value: 'as_zzzz_secret-value' },
          { status: 201 },
        );
      }),
    );
    const user = userEvent.setup();
    renderKeys();

    await user.click(await screen.findByRole('button', { name: 'New key' }));
    await user.type(await screen.findByRole('textbox', { name: /Name/ }), 'agent');
    await user.click(await screen.findByRole('checkbox', { name: 'Select all in Clusters' }));
    await user.click(screen.getByRole('button', { name: 'Create key' }));

    expect(await screen.findByDisplayValue('as_zzzz_secret-value')).toBeInTheDocument();
    expect(posted.grants).toEqual([{ action: 'cluster:read', scopeType: 'GLOBAL', scopeId: null }]);
    expect(posted.mcpTools).toEqual([]);
    const days = (Date.parse(posted.expiresAt!) - Date.now()) / DAY;
    expect(days).toBeGreaterThan(29);
    expect(days).toBeLessThanOrEqual(30);
  });

  it('limits the permissions that act on queues to the names a pattern matches', async () => {
    let posted: { grants?: unknown[] } = {};
    mockBaseApis([{ scopeType: 'GLOBAL', scopeId: null, permissions: ['cluster:read', 'message:read'] }]);
    server.use(
      http.post('*/api/v1/tokens', async ({ request }) => {
        posted = (await request.json()) as typeof posted;
        return HttpResponse.json(
          { token: token({ id: 't9', name: 'orders' }), value: 'as_zzzz_secret' },
          { status: 201 },
        );
      }),
    );
    const user = userEvent.setup();
    renderKeys();

    await user.click(await screen.findByRole('button', { name: 'New key' }));
    await user.type(await screen.findByRole('textbox', { name: /Name/ }), 'orders');
    await user.click(await screen.findByRole('checkbox', { name: 'Select all in Clusters' }));
    await user.click(await screen.findByRole('checkbox', { name: 'Select all in Messages' }));
    await user.click(screen.getByRole('combobox', { name: 'Limit to' }));
    await user.click(await screen.findByRole('option', { name: 'Subscriptions whose names match a pattern', hidden: true }));
    await user.type(await screen.findByRole('textbox', { name: /Name pattern/ }), 'orders.#');
    await user.click(screen.getByRole('button', { name: 'Create key' }));

    expect(await screen.findByDisplayValue('as_zzzz_secret')).toBeInTheDocument();
    expect(posted.grants).toEqual(
      expect.arrayContaining([
        { action: 'cluster:read', scopeType: 'GLOBAL', scopeId: null },
        {
          action: 'message:read',
          scopeType: 'GLOBAL',
          scopeId: null,
          resourceType: 'SUBSCRIPTION',
          resourcePattern: 'orders.#',
        },
      ]),
    );
  });

  it('asks for the pattern when the key is limited to names', async () => {
    mockBaseApis([{ scopeType: 'GLOBAL', scopeId: null, permissions: ['message:read'] }]);
    const user = userEvent.setup();
    renderKeys();

    await user.click(await screen.findByRole('button', { name: 'New key' }));
    await user.type(await screen.findByRole('textbox', { name: /Name/ }), 'orders');
    await user.click(await screen.findByRole('checkbox', { name: 'Select all in Messages' }));
    await user.click(screen.getByRole('combobox', { name: 'Limit to' }));
    await user.click(await screen.findByRole('option', { name: 'Subscriptions whose names match a pattern', hidden: true }));
    await user.click(screen.getByRole('button', { name: 'Create key' }));

    expect(await screen.findByText('Give the pattern the names must match, such as orders.#')).toBeInTheDocument();
  });

  it('rotates a key and says until when the old secret works', async () => {
    const overlapEnds = new Date(Date.now() + DAY).toISOString();
    mockBaseApis([], [token()]);
    server.use(
      http.post('*/api/v1/tokens/t1/rotate', () =>
        HttpResponse.json({ token: token({ previousValidUntil: overlapEnds }), value: 'as_new_secret' }),
      ),
    );
    const user = userEvent.setup();
    renderKeys();

    await user.click(await screen.findByRole('button', { name: 'Rotate laptop' }));
    await user.click(await screen.findByRole('button', { name: 'Rotate key' }));

    expect(await screen.findByDisplayValue('as_new_secret')).toBeInTheDocument();
    expect(screen.getByText(/The old secret keeps working until/)).toBeInTheDocument();
  });

  it('keeps a one-time secret on screen until it is confirmed copied', async () => {
    mockBaseApis([], [token()]);
    server.use(
      http.post('*/api/v1/tokens/t1/rotate', () =>
        HttpResponse.json({ token: token({ previousValidUntil: new Date().toISOString() }), value: 'as_new_secret' }),
      ),
    );
    const user = userEvent.setup();
    renderKeys();

    await user.click(await screen.findByRole('button', { name: 'Rotate laptop' }));
    await user.click(await screen.findByRole('button', { name: 'Rotate key' }));
    const dialog = await screen.findByRole('dialog', { name: 'Rotate laptop' });
    await within(dialog).findByDisplayValue('as_new_secret');
    expect(within(dialog).queryByRole('button', { name: /close/i })).not.toBeInTheDocument();

    await user.keyboard('{Escape}');
    expect(screen.getByRole('dialog', { name: 'Rotate laptop' })).toBeInTheDocument();

    await user.click(within(dialog).getByRole('button', { name: 'Copy key' }));
    expect(await within(dialog).findByRole('button', { name: 'Copied' })).toBeInTheDocument();
    await user.click(within(dialog).getByRole('button', { name: "I've copied the key" }));
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
  });

  it('revokes a key only once its name is typed, and announces it', async () => {
    let revoked = false;
    mockBaseApis([], [token()]);
    server.use(
      http.delete('*/api/v1/tokens/t1', () => {
        revoked = true;
        return new HttpResponse(null, { status: 204 });
      }),
    );
    const user = userEvent.setup();
    renderKeys();

    await user.click(await screen.findByRole('button', { name: 'Revoke laptop' }));
    const confirm = await screen.findByRole('button', { name: 'Revoke key' });
    await holdButton(confirm);

    await expect.poll(() => revoked).toBe(true);
    // The dialog closing is not the only signal: the outcome is announced politely.
    expect(await screen.findByRole('status')).toHaveTextContent('Revoked key "laptop"');
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
  });

  it('says why a revoke failed and what to do, and carries nothing over to the next key', async () => {
    mockBaseApis([], [token(), token({ id: 't2', name: 'ci' })]);
    server.use(
      http.delete('*/api/v1/tokens/t1', () =>
        HttpResponse.json({ title: 'Boom', detail: 'Database unavailable' }, { status: 500 }),
      ),
    );
    const user = userEvent.setup();
    renderKeys();

    await user.click(await screen.findByRole('button', { name: 'Revoke laptop' }));
    let dialog = await screen.findByRole('dialog');
    await holdButton(within(dialog).getByRole('button', { name: 'Revoke key' }));

    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent('Could not revoke key "laptop"');
    expect(alert).toHaveTextContent('Database unavailable The key still works. Try again.');

    await user.keyboard('{Escape}');
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
    await user.click(screen.getByRole('button', { name: 'Revoke ci' }));
    dialog = await screen.findByRole('dialog');
    // The dialog for the next key holds no trace of the last key's failure.
    expect(within(dialog).queryByText(/Database unavailable/)).not.toBeInTheDocument();
    expect(within(dialog).queryByRole('alert')).not.toBeInTheDocument();
  });

  it('moves focus into the revoke dialog, closes it with Escape and returns focus to the trigger', async () => {
    mockBaseApis([], [token()]);
    const user = userEvent.setup();
    renderKeys();

    const trigger = await screen.findByRole('button', { name: 'Revoke laptop' });
    await user.click(trigger);
    const dialog = await screen.findByRole('dialog');
    await waitFor(() => expect(dialog.contains(document.activeElement)).toBe(true));
    await user.keyboard('{Escape}');
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
    await waitFor(() => expect(trigger).toHaveFocus());
  });

  it('keeps rotate and revoke visible but disabled for a key that already ended', async () => {
    mockBaseApis([], [token({ revokedAt: new Date().toISOString() })]);
    renderKeys();

    expect(await screen.findByText('Revoked')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Rotate laptop' })).toBeDisabled();
    expect(screen.getByRole('button', { name: 'Revoke laptop' })).toBeDisabled();
  });

  it('offers the permissions once the user’s grants arrive after the catalogue', async () => {
    mockBaseApis([{ scopeType: 'GLOBAL', scopeId: null, permissions: ['cluster:read'] }]);
    let release: () => void = () => {};
    const gate = new Promise<void>((resolve) => (release = resolve));
    server.use(
      http.get('*/api/v1/auth/me', async () => {
        await gate;
        return HttpResponse.json({
          id: 'u1',
          username: 'ada',
          mustChangePassword: false,
          grants: [{ scopeType: 'GLOBAL', scopeId: null, permissions: ['cluster:read'] }],
        });
      }),
    );
    const user = userEvent.setup();
    renderKeys();

    await user.click(await screen.findByRole('button', { name: 'New key' }));
    // The catalogue is here and the grants are not: nothing is held yet.
    expect(await screen.findByText(/You hold nothing at this scope/)).toBeInTheDocument();
    release();
    // Once they land the list follows them; it is not stuck on the first answer.
    expect(await screen.findByRole('button', { name: /Clusters, 0 of 1 selected/ })).toBeInTheDocument();
    expect(screen.queryByText(/You hold nothing at this scope/)).not.toBeInTheDocument();
  });

  it('asks for a permission on submit and moves focus to the first thing missing', async () => {
    mockBaseApis([{ scopeType: 'GLOBAL', scopeId: null, permissions: ['cluster:read'] }]);
    const user = userEvent.setup();
    renderKeys();

    await user.click(await screen.findByRole('button', { name: 'New key' }));
    await user.click(await screen.findByRole('button', { name: 'Create key' }));
    expect(await screen.findByText('Name the key after where it will be used.')).toBeInTheDocument();
    expect(screen.getByRole('textbox', { name: /Name/ })).toHaveFocus();

    await user.type(screen.getByRole('textbox', { name: /Name/ }), 'agent');
    await user.click(screen.getByRole('button', { name: 'Create key' }));
    expect(await screen.findByText(/Choose at least one permission/)).toBeInTheDocument();
  });

  it('shows a key’s usage per day', async () => {
    mockBaseApis([], [token()]);
    server.use(
      http.get('*/api/v1/tokens/t1/usage', () =>
        HttpResponse.json({
          days: 7,
          requests: 42,
          denied: 3,
          limited: 1,
          errors: 0,
          perDay: [{ day: '2026-09-30', requests: 42, denied: 3, limited: 1, errors: 0 }],
        }),
      ),
    );
    const user = userEvent.setup();
    renderKeys();

    await user.click(await screen.findByRole('button', { name: 'Usage of laptop' }));

    expect(await screen.findByText('2026-09-30')).toBeInTheDocument();
    expect(screen.getAllByText('42').length).toBeGreaterThan(0);
  });

  it.each([
    [
      'session-required',
      /only be created from a signed-in console session.*Sign in to the console and create it there/,
    ],
    ['mfa-required', /has not completed it\. Sign out, sign in with your second factor, then create the key/],
  ])('says why a key was refused (%s) and what to do', async (slug, advice) => {
    mockBaseApis([{ scopeType: 'GLOBAL', scopeId: null, permissions: ['cluster:read'] }]);
    server.use(
      http.post('*/api/v1/tokens', () =>
        HttpResponse.json({ type: `https://rahdar.dev/problems/${slug}`, title: slug }, { status: 403 }),
      ),
    );
    const user = userEvent.setup();
    renderKeys();

    await user.click(await screen.findByRole('button', { name: 'New key' }));
    await user.type(await screen.findByRole('textbox', { name: /Name/ }), 'agent');
    await user.click(await screen.findByRole('checkbox', { name: 'Select all in Clusters' }));
    await user.click(screen.getByRole('button', { name: 'Create key' }));

    expect(await screen.findByRole('alert')).toHaveTextContent(advice);
  });
});
