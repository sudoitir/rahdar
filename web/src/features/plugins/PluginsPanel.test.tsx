import { afterEach, describe, expect, it, vi } from 'vitest';
import { http, HttpResponse } from 'msw';
import { fireEvent, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import {
  createMemoryHistory,
  createRootRoute,
  createRoute,
  createRouter,
  RouterProvider,
} from '@tanstack/react-router';

import { renderWithProviders } from '../../test/render.tsx';
import { server } from '../../test/setup.ts';
import type { PluginPlanView, PluginsView, PluginView } from './api.ts';
import { PluginsPanel } from './PluginsPanel.tsx';
import { paged } from '../../kernel/api/paging.ts';
import { holdButton, holdByKeyboard } from '../../test/hold.ts';

const NOW = new Date().toISOString();

const INFO = {
  name: 'acme-notes',
  title: 'Notes',
  description: 'Shared notes on subscriptions.',
  vendor: { name: 'Acme', url: 'https://acme.example', email: null },
  license: 'Apache-2.0',
  changeNotes: null,
  since: '2026.01.0',
  until: null,
  restartToActivate: false,
  updateUrl: null,
  requires: [],
  requiresLicense: false,
  contributions: {
    ui: true,
    permissions: [{ action: 'acme-notes:write', description: 'Write notes' }],
    settingKeys: [],
    streamTopics: [],
    mcpTools: [{ name: 'acme_notes_search', posture: 'read', description: null }],
    identityProviders: [],
    auditReceive: false,
    auditRetentionHold: false,
  },
};

function plugin(overrides: Partial<PluginView> = {}): PluginView {
  return {
    id: 'acme-notes',
    version: '1.0.0',
    status: 'active',
    failure: null,
    progress: null,
    stepStartedAt: null,
    installedAt: NOW,
    activatedAt: NOW,
    installedBy: 'ops',
    sha256: 'a'.repeat(64),
    rollbackAvailable: false,
    stuck: false,
    iconUrl: null,
    dependants: [],
    signerFingerprint: 'AB:CD',
    signerSubject: 'CN=Acme',
    verified: true,
    license: null,
    info: INFO,
    ...overrides,
  } as PluginView;
}

function inventory(plugins: PluginView[], overrides: Partial<PluginsView> = {}): PluginsView {
  return {
    canInstall: true,
    cannotInstall: null,
    uploadEnabled: true,
    safeMode: false,
    safeModeReason: null,
    budget: { maxConnections: 100, inUse: 13, limit: 80, perPlugin: 3 },
    restart: {
      supervised: true,
      needed: false,
      restarting: false,
      allowedAt: null,
      command: 'docker compose restart rahdar',
      unreleased: [],
    },
    plugins,
    ...overrides,
  } as PluginsView;
}

const PLAN: PluginPlanView = {
  pluginId: 'acme-notes',
  fromVersion: null,
  toVersion: '1.0.0',
  activationClass: 'BRIEF_MAINTENANCE',
  pendingChangesets: [{ id: '0001', author: 'acme', reversible: false }],
  updateSql: 'CREATE TABLE note (id uuid);',
  reversible: false,
  diff: {
    permissionsAdded: [],
    permissionsRemoved: [],
    settingKeysAdded: [],
    settingKeysRemoved: [],
    streamTopicsAdded: [],
    streamTopicsRemoved: [],
    mcpToolsAdded: [],
    mcpToolsRemoved: [],
    identityProvidersAdded: [],
    identityProvidersRemoved: [],
    auditAdded: [],
    auditRemoved: [],
  },
  rolesLosingPermission: {},
  compatible: true,
  missingRequires: [],
  restart: 'NONE',
  trust: {
    status: 'TRUSTED',
    fingerprint: 'AB:CD',
    subject: 'CN=Acme',
    keyName: 'Acme',
    previousFingerprint: null,
    signerChanged: false,
    allowed: true,
  },
  acknowledgements: [],
  info: INFO,
} as PluginPlanView;

function me(authenticatedAt: string | null = NOW) {
  return http.get('*/api/v1/auth/me', () =>
    HttpResponse.json({
      id: 'u1',
      username: 'ops',
      mustChangePassword: false,
      grants: [{ scopeType: 'GLOBAL', scopeId: null, permissions: ['*'] }],
      reauthentication: { method: 'PASSWORD', startPath: null, authenticatedAt, windowSeconds: 300 },
    }),
  );
}

function renderPanel(path = '/admin?tab=plugins') {
  const rootRoute = createRootRoute();
  const admin = createRoute({
    getParentRoute: () => rootRoute,
    path: 'admin',
    component: PluginsPanel,
    validateSearch: (raw: Record<string, unknown>) => raw as { plugin?: string; upload?: string },
  });
  const router = createRouter({
    routeTree: rootRoute.addChildren([admin]),
    history: createMemoryHistory({ initialEntries: [path] }),
  });
  return renderWithProviders(<RouterProvider router={router} />);
}

afterEach(() => vi.restoreAllMocks());

describe('Administration → Plugins', () => {
  it('teaches what plugins are when there are none, and where to start', async () => {
    server.use(
      me(),
      http.get('*/api/v1/admin/plugins', () => HttpResponse.json(inventory([]))),
    );
    renderPanel();
    expect(await screen.findByText('No plugins yet')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Build a plugin →' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Install plugin…' })).toBeEnabled();
  });

  it('keeps install visible but disabled, with the reason, for someone who is not an installer', async () => {
    server.use(
      me(),
      http.get('*/api/v1/admin/plugins', () =>
        HttpResponse.json(
          inventory([], {
            canInstall: false,
            cannotInstall: {
              code: 'plugin-installer-required',
              message: 'Only someone who can install plugins can do this.',
            },
          }),
        ),
      ),
    );
    renderPanel();
    expect(await screen.findByRole('button', { name: 'Install plugin…' })).toBeDisabled();
    expect(screen.getByText('Only someone who can install plugins can do this.')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Who can install' })).toBeNull();
  });

  it('says so when installing is switched off', async () => {
    server.use(
      me(),
      http.get('*/api/v1/admin/plugins', () => HttpResponse.json(inventory([], { uploadEnabled: false }))),
    );
    renderPanel();
    expect(await screen.findByText(/switched off on this installation/)).toBeInTheDocument();
  });

  it('puts a failed plugin first, in words, and summarises what needs attention', async () => {
    server.use(
      me(),
      http.get('*/api/v1/admin/plugins', () =>
        HttpResponse.json(
          inventory([
            plugin({ id: 'acme-alpha', info: { ...INFO, title: 'Alpha' } }),
            plugin({ id: 'acme-zeta', status: 'failed', failure: 'boom', info: { ...INFO, title: 'Zeta' } }),
          ]),
        ),
      ),
    );
    renderPanel();
    await screen.findByRole('grid', { name: 'Plugins' });
    expect(screen.getByRole('status')).toHaveTextContent('1 plugin needs attention: Zeta (failed)');
    const rows = screen.getAllByRole('row').slice(1);
    expect(rows[0]).toHaveTextContent('Zeta');
    expect(rows[0]).toHaveTextContent('Failed');
    expect(within(rows[0]).getByRole('button', { name: 'See why' })).toBeInTheDocument();
  });

  it('shows a license state, in words, only on a plugin that needs a license, and offers the fix', async () => {
    const license = (state: string) => ({
      state,
      expiresAt: null,
      licensee: null,
      detail: null,
      uploadedAt: null,
      uploadedBy: null,
      reportedAt: null,
    });
    server.use(
      me(),
      http.get('*/api/v1/admin/plugins', () =>
        HttpResponse.json(
          inventory([
            plugin({ id: 'acme-plain', info: { ...INFO, title: 'Plain' } }),
            plugin({
              id: 'acme-fine',
              info: { ...INFO, title: 'Fine', requiresLicense: true },
              license: license('VALID') as PluginView['license'],
            }),
            plugin({
              id: 'acme-bare',
              info: { ...INFO, title: 'Bare', requiresLicense: true },
              license: license('MISSING') as PluginView['license'],
            }),
            plugin({
              id: 'acme-late',
              info: { ...INFO, title: 'Late', requiresLicense: true },
              license: license('EXPIRED') as PluginView['license'],
            }),
          ]),
        ),
      ),
    );
    renderPanel();

    await screen.findByText('Bare');
    const row = (title: string) => screen.getByText(title, { selector: 'p' }).closest('[role="row"]') as HTMLElement;
    expect(row('Plain')).not.toHaveTextContent(/licen/i);
    expect(row('Fine')).toHaveTextContent('Licensed');
    expect(within(row('Fine')).queryByRole('button')).toBeNull();
    expect(row('Bare')).toHaveTextContent('No license');
    expect(within(row('Bare')).getByRole('button', { name: 'Add license' })).toBeInTheDocument();
    expect(row('Late')).toHaveTextContent('License expired');
    expect(within(row('Late')).getByRole('button', { name: 'License' })).toBeInTheDocument();
  });

  it('lists everything wrong with a jar at once, and stores nothing', async () => {
    server.use(
      me(),
      http.get('*/api/v1/admin/plugins', () => HttpResponse.json(inventory([]))),
      http.put('*/api/v1/admin/plugins/upload', () =>
        HttpResponse.json(
          {
            type: 'https://rahdar.dev/problems/plugin-refused',
            title: 'Refused',
            status: 422,
            detail: 'refused',
            violations: [
              {
                code: 'manifest-attribute',
                message: 'Class-Path is not allowed.',
                fix: 'Shade dependencies instead.',
                severity: 'ERROR',
              },
              { code: 'denied-call', message: 'Calls System.exit.', fix: 'Remove the call.', severity: 'ERROR' },
            ],
          },
          { status: 422 },
        ),
      ),
    );
    const user = userEvent.setup();
    const { container } = renderPanel();
    await screen.findByText('No plugins yet');
    const input = container.ownerDocument.querySelector('input[type="file"]') as HTMLInputElement;
    await user.upload(input, new File(['PK'], 'acme-notes-1.0.0.jar', { type: 'application/java-archive' }));

    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent('Class-Path is not allowed.');
    expect(alert).toHaveTextContent('Calls System.exit.');
    expect(alert).toHaveTextContent('Nothing was stored.');
    expect(within(alert).getByRole('button', { name: 'Copy report for the plugin author' })).toBeInTheDocument();
  });

  it('installs through inspect, review and a typed confirmation, then follows it to active', async () => {
    let status: 'activating' | 'active' = 'activating';
    let activated = false;
    server.use(
      me(),
      http.get('*/api/v1/admin/plugins', () =>
        HttpResponse.json(
          inventory(
            activated
              ? [
                  plugin({
                    status,
                    progress: status === 'activating' ? 'migrating' : null,
                    activatedAt: new Date().toISOString(),
                  }),
                ]
              : [],
          ),
        ),
      ),
      http.put('*/api/v1/admin/plugins/upload', () =>
        HttpResponse.json({ sha256: 'b'.repeat(64), plan: PLAN, warnings: [] }, { status: 201 }),
      ),
      http.post('*/api/v1/admin/plugins/uploads/:sha/activate', () => {
        activated = true;
        return HttpResponse.json(PLAN, { status: 202 });
      }),
    );
    const user = userEvent.setup();
    const { container } = renderPanel();
    await screen.findByText('No plugins yet');
    await user.upload(
      container.ownerDocument.querySelector('input[type="file"]') as HTMLInputElement,
      new File(['PK'], 'acme-notes-1.0.0.jar'),
    );

    // Review: capabilities as sentences, the pause, and the irreversible change, before anything is armed.
    expect(await screen.findByText(/Give the assistant 1 read-only tool/)).toBeInTheDocument();
    expect(screen.getByText(/Its database schema is created, then it starts/)).toBeInTheDocument();
    expect(screen.getByText(/Irreversible/)).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Show the SQL' }));
    await user.click(screen.getByRole('button', { name: 'Continue' }));

    const confirm = screen.getByRole('button', { name: 'Install Notes 1.0.0 (1 database change)' });
    await holdButton(confirm);

    expect(await screen.findByText(/You can close this; it carries on/)).toBeInTheDocument();
    status = 'active';
    expect(await screen.findByText('Notes 1.0.0 is active')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Reload Rahdar' })).toBeInTheDocument();
  });

  it('asks a session that signed in long ago to confirm it is them before it can install', async () => {
    let reauthenticated = false;
    server.use(
      me(new Date(Date.now() - 10 * 60_000).toISOString()),
      http.get('*/api/v1/admin/plugins', () => HttpResponse.json(inventory([]))),
      http.put('*/api/v1/admin/plugins/upload', () =>
        HttpResponse.json(
          {
            sha256: 'b'.repeat(64),
            plan: { ...PLAN, activationClass: 'INSTANT', pendingChangesets: [], updateSql: '' },
            warnings: [],
          },
          { status: 201 },
        ),
      ),
      http.post('*/api/v1/auth/reauthenticate', async () => {
        reauthenticated = true;
        return HttpResponse.json({ status: 'AUTHENTICATED', me: null, methods: null });
      }),
    );
    const user = userEvent.setup();
    const { container } = renderPanel();
    await screen.findByText('No plugins yet');
    await user.upload(
      container.ownerDocument.querySelector('input[type="file"]') as HTMLInputElement,
      new File(['PK'], 'n.jar'),
    );
    await user.click(await screen.findByRole('button', { name: 'Continue' }));

    expect(screen.getByText('Confirm it is you')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Install Notes 1.0.0' })).toBeDisabled();

    await user.click(screen.getByRole('button', { name: 'Confirm' }));
    expect(await screen.findByText('Enter your password.')).toBeInTheDocument();
    await user.type(screen.getByLabelText('Your password'), 'secret');
    await user.click(screen.getByRole('button', { name: 'Confirm' }));
    await waitFor(() => expect(reauthenticated).toBe(true));
  });

  it('purges an uninstalled plugin only after its reach is shown and its id typed, by keyboard alone', async () => {
    let purged = false;
    server.use(
      me(),
      http.get('*/api/v1/admin/plugins', () =>
        HttpResponse.json(inventory([plugin({ status: 'uninstalled', activatedAt: null })])),
      ),
      http.get('*/api/v1/admin/plugins/acme-notes/history', () => HttpResponse.json(paged([]))),
      http.post('*/api/v1/admin/plugins/acme-notes/purge', ({ request }) => {
        if (new URL(request.url).searchParams.get('dryRun') === 'false') purged = true;
        return HttpResponse.json({
          schema: 'plugin_acme_notes',
          tables: [{ name: 'note', estimatedRows: 1200, bytes: 65536 }],
          grants: 2,
          settings: 0,
          artifacts: 1,
        });
      }),
    );
    const user = userEvent.setup();
    renderPanel('/admin?tab=plugins&plugin=acme-notes');

    await user.click(await screen.findByRole('tab', { name: 'Actions' }));
    const trigger = screen.getByRole('button', { name: 'Purge its data…' });
    trigger.focus();
    await user.keyboard('{Enter}');

    const dialog = await screen.findByRole('dialog', { name: "Purge Notes's data" });
    expect(await within(dialog).findByText(/about 1,200 rows/)).toBeInTheDocument();
    expect(within(dialog).getByText('2 role grants of its permissions')).toBeInTheDocument();
    await user.keyboard('{Escape}');
    await waitFor(() => expect(screen.queryByRole('dialog', { name: "Purge Notes's data" })).toBeNull());
    await waitFor(() => expect(trigger).toHaveFocus());

    await user.keyboard('{Enter}');
    const again = await screen.findByRole('dialog', { name: "Purge Notes's data" });
    // Held with the keyboard: Space down on the focused button for the length of the hold.
    await holdByKeyboard(within(again).getByRole('button', { name: 'Delete its data permanently' }));
    await waitFor(() => expect(purged).toBe(true));
  });

  it('marks an unverified plugin with a badge in words, in the list and in its drawer', async () => {
    server.use(
      me(),
      http.get('*/api/v1/admin/plugins', () =>
        HttpResponse.json(
          inventory([
            plugin({ id: 'acme-alpha', info: { ...INFO, title: 'Alpha' } }),
            plugin({
              id: 'acme-beta',
              info: { ...INFO, title: 'Beta' },
              verified: false,
              signerFingerprint: null,
              signerSubject: null,
            }),
          ]),
        ),
      ),
      http.get('*/api/v1/admin/plugins/acme-beta/history', () => HttpResponse.json(paged([]))),
    );
    renderPanel();
    const rows = (await screen.findAllByRole('row')).slice(1);
    expect(within(rows[0]).queryByText('Unverified')).toBeNull();
    expect(within(rows[1]).getByText('Unverified')).toBeInTheDocument();
  });

  it('shows the signer in the drawer', async () => {
    server.use(
      me(),
      http.get('*/api/v1/admin/plugins', () =>
        HttpResponse.json(inventory([plugin({ verified: false, signerFingerprint: 'AB:CD:EF' })])),
      ),
      http.get('*/api/v1/admin/plugins/acme-notes/history', () => HttpResponse.json(paged([]))),
    );
    renderPanel('/admin?tab=plugins&plugin=acme-notes');
    expect(await screen.findByText('CN=Acme (not a trusted key)')).toBeInTheDocument();
    expect(screen.getByText('AB:CD:EF')).toBeInTheDocument();
    expect(screen.getAllByText('Unverified').length).toBeGreaterThan(0);
  });

  it('asks for an explicit tick when the server says enabling needs confirming, then sends acknowledge', async () => {
    const calls: string[] = [];
    server.use(
      me(),
      http.get('*/api/v1/admin/plugins', () =>
        HttpResponse.json(inventory([plugin({ status: 'disabled', verified: false })])),
      ),
      http.get('*/api/v1/admin/plugins/acme-notes/history', () => HttpResponse.json(paged([]))),
      http.post('*/api/v1/admin/plugins/acme-notes/enable', ({ request }) => {
        calls.push(new URL(request.url).search);
        if (!calls.at(-1)) {
          return HttpResponse.json(
            {
              type: 'https://rahdar.dev/problems/plugin-refused',
              title: 'Conflict',
              status: 409,
              detail: 'refused',
              violations: [
                {
                  code: 'acknowledgement-required',
                  message: "Activating 'acme-notes' needs your confirmation: unverified.",
                  fix: 'Review the plan and confirm it.',
                  severity: 'ERROR',
                },
              ],
            },
            { status: 409 },
          );
        }
        return HttpResponse.json(PLAN, { status: 202 });
      }),
    );
    const user = userEvent.setup();
    renderPanel('/admin?tab=plugins&plugin=acme-notes');
    await user.click(await screen.findByRole('tab', { name: 'Actions' }));
    await user.click(screen.getByRole('button', { name: 'Enable…' }));
    const dialog = await screen.findByRole('dialog', { name: 'Start Notes again' });
    await user.click(within(dialog).getByRole('button', { name: 'Enable' }));
    expect(await within(dialog).findByText(/needs your confirmation: unverified/)).toBeInTheDocument();

    await user.click(within(dialog).getByRole('button', { name: 'Enable' }));
    expect(await within(dialog).findByText('Tick this to continue.')).toBeInTheDocument();
    expect(calls).toEqual(['']);

    await user.click(within(dialog).getByRole('checkbox', { name: /want to continue/ }));
    await user.click(within(dialog).getByRole('button', { name: 'Enable' }));
    await waitFor(() => expect(calls).toEqual(['', '?acknowledge=true']));
  });

  it('offers a restart when Rahdar can make one, and the command when it cannot', async () => {
    const waiting = plugin({ status: 'needs_restart', failure: 'Restart Rahdar to start acme-notes 1.1.0.' });
    server.use(
      me(),
      http.get('*/api/v1/admin/plugins', () =>
        HttpResponse.json(inventory([waiting], { restart: { ...inventory([]).restart, needed: true } })),
      ),
    );
    const first = renderPanel();
    expect(await screen.findByRole('button', { name: 'Restart Rahdar…' })).toBeEnabled();
    expect(screen.getByText('Notes starts after a restart.')).toBeInTheDocument();
    first.unmount();

    server.use(
      http.get('*/api/v1/admin/plugins', () =>
        HttpResponse.json(
          inventory([waiting], { restart: { ...inventory([]).restart, needed: true, supervised: false } }),
        ),
      ),
    );
    renderPanel();
    expect(await screen.findByText('docker compose restart rahdar')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Restart Rahdar…' })).toBeNull();
  });

  it('shows what a drop will do before anything is installed', async () => {
    server.use(
      me(),
      http.get('*/api/v1/admin/plugins', () => HttpResponse.json(inventory([]))),
    );
    renderPanel();
    const empty = await screen.findByText('No plugins yet');
    fireEvent.dragOver(empty);
    expect(screen.getByText('Drop to inspect. Nothing is installed until you confirm.')).toBeInTheDocument();
  });
});

function listing(plugins: PluginView[], overrides: Partial<PluginsView> = {}) {
  server.use(
    me(),
    http.get('*/api/v1/admin/plugins', () => HttpResponse.json(inventory(plugins, overrides))),
  );
}

describe('Administration → Plugins inventory', () => {
  it('is one section of the page, headed by a level-two heading, never a second page title', async () => {
    listing([plugin()]);
    renderPanel();

    expect(await screen.findByRole('heading', { name: 'Plugins', level: 2 })).toBeInTheDocument();
    expect(screen.queryByRole('heading', { level: 1 })).toBeNull();
  });

  it("shows a plugin's icon, and falls back to a monogram when the icon cannot be loaded", async () => {
    listing([plugin({ iconUrl: '/icons/acme-notes.svg' })]);
    const { container } = renderPanel();

    await screen.findByRole('row', { name: /Notes/ });
    const icon = container.ownerDocument.querySelector('img[src="/icons/acme-notes.svg"]') as HTMLImageElement;
    expect(icon).not.toBeNull();
    expect(screen.queryByText('NO')).toBeNull();

    fireEvent.error(icon);
    expect(await screen.findByText('NO')).toBeInTheDocument();
    expect(container.ownerDocument.querySelector('img[src="/icons/acme-notes.svg"]')).toBeNull();
  });

  it('shows a plugin with no icon as a monogram, with its vendor and what it adds', async () => {
    listing([plugin()]);
    renderPanel();

    const row = await screen.findByRole('row', { name: /Notes/ });
    expect(row).toHaveTextContent('NO');
    expect(row).toHaveTextContent('Acme');
    expect(row).toHaveTextContent('1.0.0');
    expect(row).toHaveTextContent('Active');
    expect(row).toHaveTextContent('screens · 1 assistant tool · 1 permission');
    // Nothing is wrong, so there is no fix button and no attention summary.
    expect(within(row).queryByRole('button')).toBeNull();
    expect(screen.queryByRole('status')).toBeNull();
    expect(
      screen.getByText(/Database connections: 13 in use of 80 allowed \(100 maximum; each active plugin uses 3\)/),
    ).toBeInTheDocument();
  });

  it('shows the step an activating plugin is on', async () => {
    listing([plugin({ status: 'activating', progress: 'migrating' })]);
    renderPanel();
    expect(await screen.findByRole('row', { name: /Activating · migrating/ })).toBeInTheDocument();
  });

  it('names what needs a restart: waiting plugins, ones that did not stop, and versions still in memory', async () => {
    listing(
      [
        plugin({ id: 'acme-notes', status: 'needs_restart' }),
        plugin({ id: 'acme-wiki', stuck: true, info: { ...INFO, title: 'Wiki' } }),
      ],
      {
        restart: {
          ...inventory([]).restart,
          needed: true,
          unreleased: ['acme-notes 1.0.0', 'acme-notes 1.0.1', 'gone-plugin 2.0.0'],
        },
      },
    );
    renderPanel();

    const alert = await screen.findByText(/Notes starts after a restart\./);
    expect(alert).toHaveTextContent('Wiki did not stop cleanly.');
    expect(alert).toHaveTextContent(
      'Stopped versions of Notes (1.0.0, 1.0.1) are still in memory; a restart frees it.',
    );
    // A plugin no longer listed is named by its id.
    expect(alert).toHaveTextContent('Stopped versions of gone-plugin (2.0.0) are still in memory');
    // A stuck plugin says so in its own row, and both count as needing attention.
    expect(screen.getByRole('row', { name: /Wiki/ })).toHaveTextContent('Did not stop cleanly');
    expect(screen.getByText(/2 plugins need attention/)).toBeVisible();
  });

  it('labels each fix by what is wrong and opens the plugin from the row or from its fix', async () => {
    listing([
      plugin({ id: 'a-failed', status: 'failed', failure: 'boom', info: { ...INFO, title: 'Alpha' } }),
      plugin({ id: 'b-old', status: 'incompatible', info: { ...INFO, title: 'Bravo' } }),
      plugin({ id: 'c-wait', status: 'needs_restart', info: { ...INFO, title: 'Charlie' } }),
    ]);
    const user = userEvent.setup();
    renderPanel();

    expect(await screen.findByRole('button', { name: 'See why' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Update…' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Details' })).toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: 'See why' }));
    expect(await screen.findByRole('dialog', { name: 'Alpha 1.0.0' })).toBeInTheDocument();
    await user.keyboard('{Escape}');
    await waitFor(() => expect(screen.queryByRole('dialog', { name: 'Alpha 1.0.0' })).toBeNull());

    await user.click(screen.getByRole('row', { name: /Bravo/ }));
    expect(await screen.findByRole('dialog', { name: 'Bravo 1.0.0' })).toBeInTheDocument();
  });

  it('opens the plugin a link names', async () => {
    listing([plugin()]);
    server.use(http.get('*/api/v1/admin/plugins/acme-notes/history', () => HttpResponse.json(paged([]))));
    renderPanel('/admin?tab=plugins&plugin=acme-notes');
    expect(await screen.findByRole('dialog', { name: 'Notes 1.0.0' })).toBeInTheDocument();
  });

  it('resumes an upload a link names at its confirmation, and forgets the upload when closed', async () => {
    listing([]);
    let discarded = false;
    server.use(
      http.get('*/api/v1/admin/plugins/uploads/abc123', () => HttpResponse.json(PLAN)),
      http.delete('*/api/v1/admin/plugins/uploads/abc123', () => {
        discarded = true;
        return new HttpResponse(null, { status: 204 });
      }),
    );
    const user = userEvent.setup();
    renderPanel('/admin?tab=plugins&upload=abc123');
    const dialog = await screen.findByRole('dialog', { name: 'Install Notes 1.0.0 (1 database change)' });
    expect(within(dialog).getByRole('button', { name: /^Install Notes 1\.0\.0/ })).toBeInTheDocument();

    await user.keyboard('{Escape}');
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());
    expect(discarded).toBe(true);
  });

  it('explains safe mode, with the reason when there is one', async () => {
    listing([], { safeMode: true, safeModeReason: 'A plugin crashed the last start.' });
    const first = renderPanel();
    await screen.findByText('Safe mode: no plugin is running');
    expect(screen.getByRole('status')).toHaveTextContent('A plugin crashed the last start.');
    first.unmount();

    listing([], { safeMode: true, safeModeReason: null });
    renderPanel();
    expect(await screen.findByText(/Rahdar started without plugins\./)).toBeInTheDocument();
  });

  it('lists who can install for an installer', async () => {
    listing([]);
    server.use(
      http.get('*/api/v1/admin/plugins/installers', () =>
        HttpResponse.json(paged([{ userId: 'u1', username: 'ops', grantedAt: NOW, grantedBy: null }])),
      ),
    );
    const user = userEvent.setup();
    renderPanel();

    await user.click(await screen.findByRole('button', { name: 'Who can install' }));
    const dialog = await screen.findByRole('dialog', { name: 'Who can install plugins' });
    expect(await within(dialog).findByText('ops')).toBeInTheDocument();
    await user.keyboard('{Escape}');
    await waitFor(() => expect(screen.queryByRole('dialog', { name: 'Who can install plugins' })).toBeNull());
  });
});

describe('Administration → Plugins updates', () => {
  it('says when no plugin names an update URL, and when every one is up to date', async () => {
    listing([plugin()]);
    let answer: unknown[] = [];
    server.use(http.post('*/api/v1/admin/plugins/check-updates', () => HttpResponse.json(answer)));
    const user = userEvent.setup();
    renderPanel();

    await user.click(await screen.findByRole('button', { name: 'Check for updates' }));
    expect(await screen.findByText('No installed plugin names an update URL to check.')).toBeInTheDocument();

    answer = [{ id: 'acme-notes', currentVersion: '1.0.0', availableVersion: null, error: null }];
    await user.click(screen.getByRole('button', { name: 'Check for updates' }));
    expect(await screen.findByText('Every plugin with an update URL is up to date.')).toBeInTheDocument();
  });

  it('offers an available update beside the version, and names a plugin that could not be checked', async () => {
    listing([plugin(), plugin({ id: 'acme-wiki', info: { ...INFO, title: 'Wiki' } })]);
    server.use(
      http.post('*/api/v1/admin/plugins/check-updates', () =>
        HttpResponse.json([
          { id: 'acme-notes', currentVersion: '1.0.0', availableVersion: '1.1.0', error: null },
          { id: 'acme-wiki', currentVersion: '1.0.0', availableVersion: null, error: 'timed out' },
        ]),
      ),
      http.post('*/api/v1/admin/plugins/acme-notes/download-update', () =>
        HttpResponse.json({
          sha256: 'b'.repeat(64),
          plan: { ...PLAN, fromVersion: '1.0.0', toVersion: '1.1.0' },
          warnings: [],
        }),
      ),
    );
    const user = userEvent.setup();
    renderPanel();

    await user.click(await screen.findByRole('button', { name: 'Check for updates' }));
    expect(
      await screen.findByText(/1 update is available\. acme-wiki: could not check \(timed out\)\./),
    ).toBeInTheDocument();
    const notes = screen.getByRole('row', { name: /Notes/ });
    await user.click(within(notes).getByRole('button', { name: '1.1.0 available' }));
    expect(await screen.findByRole('dialog')).toBeInTheDocument();
  });

  it('says why an update check failed, and offers to retry it', async () => {
    listing([plugin()]);
    let answered = 0;
    server.use(
      http.post('*/api/v1/admin/plugins/check-updates', () => {
        answered += 1;
        return answered === 1
          ? HttpResponse.json({ title: 'Bad gateway', detail: 'The update server did not answer.' }, { status: 502 })
          : HttpResponse.json([{ id: 'acme-notes', currentVersion: '1.0.0', availableVersion: null, error: null }]);
      }),
    );
    const user = userEvent.setup();
    renderPanel();

    await user.click(await screen.findByRole('button', { name: 'Check for updates' }));
    const failure = await screen.findByRole('alert');
    expect(failure).toHaveTextContent('The update server did not answer.');
    await user.click(within(failure).getByRole('button', { name: 'Retry' }));
    expect(await screen.findByText('Every plugin with an update URL is up to date.')).toBeInTheDocument();
    expect(screen.queryByRole('alert')).toBeNull();
  });

  it('keeps update checks disabled when installing is not allowed', async () => {
    listing([plugin()], { canInstall: false });
    renderPanel();
    expect(await screen.findByRole('button', { name: 'Check for updates' })).toBeDisabled();
  });
});

describe('Administration → Plugins drop', () => {
  const jar = () => new File(['PK'], 'acme-notes-1.0.0.jar', { type: 'application/java-archive' });
  const dropOf = (files: File[]) => ({ dataTransfer: { files } });

  it('takes a dropped jar into review, and removes the overlay when the drag leaves', async () => {
    listing([]);
    server.use(
      http.put('*/api/v1/admin/plugins/upload', () =>
        HttpResponse.json({ sha256: 'b'.repeat(64), plan: PLAN, warnings: [] }, { status: 201 }),
      ),
    );
    renderPanel();
    const empty = await screen.findByText('No plugins yet');
    const zone = empty.closest('div[class*="drop"]') ?? empty.parentElement!.parentElement!;

    fireEvent.dragOver(zone);
    expect(screen.getByText('Drop to inspect. Nothing is installed until you confirm.')).toBeInTheDocument();
    fireEvent.dragLeave(zone);
    expect(screen.queryByText('Drop to inspect. Nothing is installed until you confirm.')).toBeNull();

    fireEvent.dragOver(zone);
    fireEvent.drop(zone, dropOf([new File(['x'], 'readme.txt'), jar()]));
    expect(screen.queryByText('Drop to inspect. Nothing is installed until you confirm.')).toBeNull();
    expect(await screen.findByText(/Give the assistant 1 read-only tool/)).toBeInTheDocument();
  });

  it('ignores a drop with no jar in it', async () => {
    listing([]);
    renderPanel();
    const empty = await screen.findByText('No plugins yet');
    const zone = empty.parentElement!.parentElement!;

    fireEvent.drop(zone, dropOf([new File(['x'], 'readme.txt')]));
    expect(screen.queryByRole('dialog')).toBeNull();
  });

  it('accepts no drop from someone who cannot install', async () => {
    listing([], { canInstall: false });
    renderPanel();
    const empty = await screen.findByText('No plugins yet');
    const zone = empty.parentElement!.parentElement!;

    fireEvent.dragOver(zone);
    expect(screen.queryByText('Drop to inspect. Nothing is installed until you confirm.')).toBeNull();
    fireEvent.drop(zone, dropOf([jar()]));
    expect(screen.queryByRole('dialog')).toBeNull();
  });
});

describe('Administration → Plugins loading and failure', () => {
  it('shows a loader while the inventory is on its way', async () => {
    server.use(
      me(),
      http.get('*/api/v1/admin/plugins', () => new Promise(() => {})),
    );
    renderPanel();
    expect(await screen.findByRole('status')).toHaveTextContent('Loading plugins');
    expect(screen.queryByText('No plugins yet')).toBeNull();
  });

  it('says listing needs user:admin when it is refused', async () => {
    server.use(
      me(),
      http.get('*/api/v1/admin/plugins', () =>
        HttpResponse.json({ title: 'Forbidden', permission: 'user:admin' }, { status: 403 }),
      ),
    );
    renderPanel();
    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent('You are not allowed to do this');
    expect(alert).toHaveTextContent('Your role does not include the user:admin permission.');
  });
});
