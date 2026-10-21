import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { http, HttpResponse } from 'msw';
import { Notifications, notifications } from '@mantine/notifications';
import { act, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

import { renderWithProviders } from '../../test/render.tsx';
import { server } from '../../test/setup.ts';
import type { PluginView } from './api.ts';
import { PluginDrawer } from './PluginDrawer.tsx';
import { paged } from '../../kernel/api/paging.ts';
import { holdButton } from '../../test/hold.ts';

const NOW = new Date().toISOString();

const INFO = {
  name: 'acme-notes',
  title: 'Notes',
  description: 'Shared notes on subscriptions.',
  vendor: { name: 'Acme', url: 'https://acme.example', email: 'support@acme.example' },
  license: 'Apache-2.0',
  changeNotes: 'Fixed the search index.',
  since: '2026.01.0',
  until: null,
  restartToActivate: false,
  updateUrl: null,
  requires: [],
  requiresLicense: false,
  contributions: {
    ui: true,
    permissions: [],
    settingKeys: [],
    streamTopics: [],
    mcpTools: [],
    identityProviders: [],
    auditReceive: false,
    auditRetentionHold: false,
  },
};

function plugin(over: Partial<PluginView> = {}, info: Record<string, unknown> = {}): PluginView {
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
    ...over,
    info: { ...INFO, ...info },
  } as PluginView;
}

function me() {
  return http.get('*/api/v1/auth/me', () =>
    HttpResponse.json({
      id: 'u1',
      username: 'ops',
      mustChangePassword: false,
      grants: [{ scopeType: 'GLOBAL', scopeId: null, permissions: ['*'] }],
      reauthentication: { method: 'PASSWORD', startPath: null, authenticatedAt: NOW, windowSeconds: 300 },
    }),
  );
}

function history(rows: unknown[] = []) {
  return http.get('*/api/v1/admin/plugins/acme-notes/history', () => HttpResponse.json(paged(rows)));
}

function purgePlan(body: unknown, status = 200) {
  return http.post('*/api/v1/admin/plugins/acme-notes/purge', () => HttpResponse.json(body as never, { status }));
}

beforeEach(() => server.use(me(), history()));
afterEach(() => act(() => notifications.clean()));

function setup(p: PluginView | undefined, props: Partial<Parameters<typeof PluginDrawer>[0]> = {}) {
  const onClose = vi.fn();
  const onUpdate = vi.fn();
  const user = userEvent.setup();
  renderWithProviders(
    <>
      <Notifications />
      <PluginDrawer plugin={p} canInstall onClose={onClose} onUpdate={onUpdate} {...props} />
    </>,
  );
  return { user, onClose, onUpdate };
}

async function tab(user: ReturnType<typeof userEvent.setup>, name: string) {
  await user.click(await screen.findByRole('tab', { name }));
}

describe('PluginDrawer: overview', () => {
  it('renders nothing without a plugin', () => {
    setup(undefined);
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
  });

  it('states the plugin, its vendor, support range, installer and artifact', async () => {
    setup(plugin());

    expect(await screen.findByRole('dialog', { name: 'Notes 1.0.0' })).toBeInTheDocument();
    expect(screen.getByText('Active')).toBeInTheDocument();
    expect(screen.getByText('Shared notes on subscriptions.')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'https://acme.example' })).toHaveAttribute('rel', 'noopener noreferrer');
    expect(screen.getByText('support@acme.example')).toBeInTheDocument();
    expect(screen.getByText('2026.01.0 and later')).toBeInTheDocument();
    expect(screen.getByText(/ by ops$/)).toBeInTheDocument();
    expect(screen.getByText('Last activated')).toBeInTheDocument();
    expect(screen.getByText(`${'a'.repeat(16)}…`)).toBeInTheDocument();
    expect(screen.getByText('Apache-2.0')).toBeInTheDocument();
    expect(screen.getByText('Change notes')).toBeInTheDocument();
    expect(screen.getByText('Fixed the search index.')).toBeInTheDocument();
  });

  it('copies the full digest', async () => {
    const { user } = setup(plugin());
    await user.click(await screen.findByRole('button', { name: 'Copy' }));
    expect(await screen.findByRole('button', { name: 'Copied' })).toBeInTheDocument();
  });

  it('omits what the plugin does not declare, and never links a vendor address that is not http(s)', async () => {
    setup(
      plugin(
        { installedBy: null, activatedAt: null, status: 'failed', failure: 'The jar no longer verifies.' },
        {
          description: null,
          vendor: { name: 'Acme', url: 'javascript:alert(1)', email: null },
          license: null,
          changeNotes: null,
          until: '2026.06.0',
        },
      ),
    );

    expect(await screen.findByText('Failed')).toBeInTheDocument();
    expect(screen.getByText(/— The jar no longer verifies\./)).toBeInTheDocument();
    expect(screen.getByText('javascript:alert(1)')).toBeInTheDocument();
    expect(screen.queryByRole('link')).not.toBeInTheDocument();
    expect(screen.getByText('2026.01.0 to 2026.06.0')).toBeInTheDocument();
    for (const label of ['Last activated', 'License', 'Change notes']) {
      expect(screen.queryByText(label)).not.toBeInTheDocument();
    }
    expect(screen.queryByText(/ by ops/)).not.toBeInTheDocument();
  });

  it('falls back to a vendor without any address', async () => {
    setup(plugin({}, { vendor: { name: 'Acme', url: null, email: null } }));
    expect(await screen.findByText('Acme')).toBeInTheDocument();
  });
});

describe('PluginDrawer: contributions', () => {
  it('lists everything the plugin adds, and what it requires and what requires it', async () => {
    const { user } = setup(
      plugin(
        { dependants: ['acme-extras', 'acme-more'] },
        {
          contributions: {
            ui: true,
            permissions: [
              { action: 'acme-notes:write', description: 'Write notes' },
              { action: 'acme-notes:read', description: null },
            ],
            mcpTools: [
              { name: 'notes_search', posture: 'read', description: 'Search notes' },
              { name: 'notes_edit', posture: 'write', description: null },
            ],
            settingKeys: ['notes.limit'],
            streamTopics: ['notes.changed'],
            identityProviders: [{ id: 'acme-notes:corp', label: 'Corporate directory' }],
          },
          requires: ['acme-core'],
        },
      ),
    );
    await tab(user, 'Contributions');

    expect(screen.getByText('Screens in Rahdar')).toBeInTheDocument();
    expect(screen.getByText('acme-notes:write').closest('li')).toHaveTextContent(
      'Permission acme-notes:write — Write notes',
    );
    expect(screen.getByText('acme-notes:read').closest('li')).toHaveTextContent('Permission acme-notes:read');
    expect(screen.getByText('acme-notes:read').closest('li')).not.toHaveTextContent('—');
    expect(screen.getByText('notes_search').closest('li')).toHaveTextContent(
      'Assistant tool notes_search (reads) — Search notes',
    );
    expect(screen.getByText('notes_edit').closest('li')).toHaveTextContent(
      'Assistant tool notes_edit (changes things)',
    );
    expect(screen.getByText('notes.limit').closest('li')).toHaveTextContent('Setting notes.limit');
    expect(screen.getByText('notes.changed').closest('li')).toHaveTextContent('Live topic notes.changed');
    expect(screen.getByText('acme-notes:corp').closest('li')).toHaveTextContent(
      'Sign-in acme-notes:corp — Corporate directory; receives the passwords typed for it',
    );
    expect(screen.getByText('Requires acme-core')).toBeInTheDocument();
    expect(screen.getByText('Required by acme-extras, acme-more')).toBeInTheDocument();
  });

  it('says a plugin without a UI has no screens of its own, and lists nothing else', async () => {
    const { user } = setup(plugin({}, { contributions: { ...INFO.contributions, ui: false } }));
    await tab(user, 'Contributions');

    expect(screen.getByText('No screens of its own')).toBeInTheDocument();
    expect(screen.queryByText(/^Requires/)).not.toBeInTheDocument();
    expect(screen.queryByText(/^Required by/)).not.toBeInTheDocument();
  });
});

describe('PluginDrawer: data', () => {
  it('measures its tables, with counts and sizes in the right unit', async () => {
    server.use(
      purgePlan({
        schema: 'plugin_acme_notes',
        tables: [
          { name: 'note', estimatedRows: 1200, bytes: 2048 },
          { name: 'blob', estimatedRows: 5, bytes: 5 * 1024 * 1024 },
          { name: 'archive', estimatedRows: 9, bytes: 3 * 1024 * 1024 * 1024 },
          { name: 'fresh', estimatedRows: -1, bytes: 100 },
        ],
        grants: 0,
        settings: 0,
        artifacts: 0,
      }),
    );
    const { user } = setup(plugin());
    await tab(user, 'Data');

    const row = async (name: string) => within((await screen.findByText(name)).closest('tr')!);
    expect((await row('note')).getByText('1,200')).toBeInTheDocument();
    expect((await row('note')).getByText('2 KB')).toBeInTheDocument();
    expect((await row('blob')).getByText('5.0 MB')).toBeInTheDocument();
    expect((await row('archive')).getByText('3.0 GB')).toBeInTheDocument();
    expect((await row('fresh')).getByText('not yet counted')).toBeInTheDocument();
    expect(screen.getByText('plugin_acme_notes')).toBeInTheDocument();
  });

  it('says a plugin without tables has none', async () => {
    server.use(purgePlan({ schema: 'plugin_acme_notes', tables: [], grants: 0, settings: 0, artifacts: 0 }));
    const { user } = setup(plugin());
    await tab(user, 'Data');

    expect(await screen.findByText('No tables')).toBeInTheDocument();
  });

  it('says why its data could not be measured', async () => {
    server.use(purgePlan({ title: 'Error', detail: 'schema is locked' }, 500));
    const { user } = setup(plugin());
    await tab(user, 'Data');

    expect(await screen.findByText('schema is locked')).toBeInTheDocument();
    expect(screen.getByText('Rahdar failed to complete the request')).toBeInTheDocument();
  });
});

describe('PluginDrawer: history', () => {
  it('says when nothing is recorded', async () => {
    const { user } = setup(plugin());
    await tab(user, 'History');
    expect(await screen.findByText('Nothing recorded yet')).toBeInTheDocument();
  });

  it('lists what was done, by whom, and how it went, with the error of a failure', async () => {
    server.use(
      history([
        { id: 1, ts: NOW, username: 'ops', action: 'PLUGIN_ROLLED_BACK', outcome: 'SUCCESS', error: null },
        {
          id: 2,
          ts: NOW,
          username: null,
          action: 'PLUGIN_ACTIVATE',
          outcome: 'FAILED',
          error: 'migration 0002 failed',
        },
      ]),
    );
    const { user } = setup(plugin());
    await tab(user, 'History');

    const rolled = (await screen.findByText('rolled back')).closest('tr')!;
    expect(within(rolled).getByText('ops')).toBeInTheDocument();
    expect(within(rolled).getByText('success')).toBeInTheDocument();
    const failed = screen.getByText('activate').closest('tr')!;
    expect(within(failed).getByText('—')).toBeInTheDocument();
    expect(within(failed).getByText('failed')).toBeInTheDocument();
    expect(within(failed).getByText('migration 0002 failed')).toBeInTheDocument();
  });
});

describe('PluginDrawer: actions offered', () => {
  const offered = () => screen.getAllByRole('button').map((b) => b.textContent);

  it.each([
    ['active', ['Disable…', 'Uninstall…']],
    ['needs_restart', ['Disable…', 'Uninstall…']],
    ['disabled', ['Enable…', 'Uninstall…']],
    ['failed', ['Retry…', 'Uninstall…']],
    ['uninstalled', ['Purge its data…']],
    ['incompatible', ['Uninstall…']],
    ['activating', []],
  ])('a %s plugin offers %j', async (status, labels) => {
    const { user } = setup(plugin({ status }));
    await tab(user, 'Actions');

    const buttons = offered().filter((t) => t?.endsWith('…'));
    expect(buttons).toEqual(labels);
  });

  it('offers a roll back to the previous version when there is one', async () => {
    const { user } = setup(plugin({ rollbackAvailable: true }));
    await tab(user, 'Actions');
    expect(screen.getByRole('button', { name: 'Roll back to the previous version…' })).toBeEnabled();
  });

  it('shows the actions disabled with the reason for someone who cannot install', async () => {
    const { user } = setup(plugin(), { canInstall: false, cannotInstall: 'Ask an administrator.' });
    await tab(user, 'Actions');

    expect(screen.getByText('Ask an administrator.')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Disable…' })).toBeDisabled();
    expect(screen.getByRole('button', { name: 'Uninstall…' })).toBeDisabled();
  });

  it('gives a default reason when none is supplied', async () => {
    const { user } = setup(plugin(), { canInstall: false });
    await tab(user, 'Actions');
    expect(screen.getByText('Only someone who can install plugins can change this one.')).toBeInTheDocument();
  });

  it('offers to check the update URL, unless the plugin is uninstalled', async () => {
    const { user, onUpdate } = setup(plugin({}, { updateUrl: 'https://acme.example/update' }));
    await tab(user, 'Actions');
    await user.click(screen.getByRole('button', { name: 'Check its update URL for a newer version' }));
    expect(onUpdate).toHaveBeenCalledWith('acme-notes');
  });

  it('does not offer an update check for an uninstalled plugin or one without an update URL', async () => {
    const { user } = setup(plugin({ status: 'uninstalled' }, { updateUrl: 'https://acme.example/update' }));
    await tab(user, 'Actions');
    expect(screen.queryByRole('button', { name: /update URL/ })).not.toBeInTheDocument();
  });
});

describe('PluginDrawer: confirmations', () => {
  it('disables a plugin only after a confirmation, and closes it when done', async () => {
    let posted = '';
    server.use(
      http.post('*/api/v1/admin/plugins/acme-notes/disable', ({ request }) => {
        posted = new URL(request.url).search;
        return new HttpResponse(null, { status: 204 });
      }),
    );
    const { user } = setup(plugin());
    await tab(user, 'Actions');
    await user.click(screen.getByRole('button', { name: 'Disable…' }));

    const dialog = await screen.findByRole('dialog', { name: 'Disable Notes' });
    expect(within(dialog).getByText(/Enable brings it back as it was/)).toBeInTheDocument();
    await user.click(within(dialog).getByRole('button', { name: 'Disable Notes' }));

    await waitFor(() => expect(screen.queryByRole('dialog', { name: 'Disable Notes' })).not.toBeInTheDocument());
    expect(posted).toBe('');
  });

  it('cascades to what requires the plugin only when asked to', async () => {
    let posted = '';
    server.use(
      http.post('*/api/v1/admin/plugins/acme-notes/disable', ({ request }) => {
        posted = new URL(request.url).search;
        return new HttpResponse(null, { status: 204 });
      }),
    );
    const { user } = setup(plugin({ dependants: ['acme-extras'] }));
    await tab(user, 'Actions');
    await user.click(screen.getByRole('button', { name: 'Disable…' }));

    const dialog = await screen.findByRole('dialog', { name: 'Disable Notes' });
    const box = within(dialog).getByRole('checkbox', { name: /Also disable what requires it: acme-extras/ });
    expect(box).not.toBeChecked();
    await user.click(box);
    await user.click(within(dialog).getByRole('button', { name: 'Disable Notes' }));

    await waitFor(() => expect(posted).toBe('?cascade=true'));
  });

  it('states a refusal beside the button and keeps the confirmation open', async () => {
    server.use(
      http.post('*/api/v1/admin/plugins/acme-notes/disable', () =>
        HttpResponse.json(
          {
            title: 'Refused',
            detail: 'refused',
            violations: [{ message: 'acme-extras is active.', fix: 'Disable it first.' }],
          },
          { status: 409 },
        ),
      ),
    );
    const { user } = setup(plugin());
    await tab(user, 'Actions');
    await user.click(screen.getByRole('button', { name: 'Disable…' }));
    const dialog = await screen.findByRole('dialog', { name: 'Disable Notes' });
    await user.click(within(dialog).getByRole('button', { name: 'Disable Notes' }));

    const refusal = await within(dialog).findByRole('alert');
    expect(refusal).toHaveTextContent('acme-extras is active.');
    expect(refusal).toHaveTextContent('Disable it first.');
  });

  it('uninstalls only once the id is typed, saying that its data is kept', async () => {
    let called = false;
    server.use(
      http.post('*/api/v1/admin/plugins/acme-notes/uninstall', () => {
        called = true;
        return new HttpResponse(null, { status: 204 });
      }),
    );
    const { user } = setup(plugin());
    await tab(user, 'Actions');
    await user.click(screen.getByRole('button', { name: 'Uninstall…' }));

    const dialog = await screen.findByRole('dialog', { name: 'Uninstall Notes' });
    expect(within(dialog).getByText(/Its data is kept/)).toBeInTheDocument();
    const confirm = within(dialog).getByRole('button', { name: 'Uninstall Notes' });
    await holdButton(confirm);

    await waitFor(() => expect(called).toBe(true));
    expect(await screen.findByText('Uninstalled plugin Notes')).toBeInTheDocument();
  });

  it('retries a failed plugin, naming the version', async () => {
    const { user } = setup(plugin({ status: 'failed', failure: 'boom' }));
    await tab(user, 'Actions');
    await user.click(screen.getByRole('button', { name: 'Retry…' }));

    const dialog = await screen.findByRole('dialog', { name: 'Start Notes again' });
    expect(within(dialog).getByText('Starts Notes 1.0.0 again for everyone.')).toBeInTheDocument();
    expect(within(dialog).getByRole('button', { name: 'Retry' })).toBeEnabled();
  });

  it('enables a disabled plugin', async () => {
    const { user } = setup(plugin({ status: 'disabled' }));
    await tab(user, 'Actions');
    await user.click(screen.getByRole('button', { name: 'Enable…' }));

    const dialog = await screen.findByRole('dialog', { name: 'Start Notes again' });
    expect(within(dialog).getByRole('button', { name: 'Enable' })).toBeEnabled();
  });

  it('rolls back, saying that nothing is lost', async () => {
    let called = false;
    server.use(
      http.post('*/api/v1/admin/plugins/acme-notes/rollback', () => {
        called = true;
        return new HttpResponse(null, { status: 204 });
      }),
    );
    const { user } = setup(plugin({ rollbackAvailable: true }));
    await tab(user, 'Actions');
    await user.click(screen.getByRole('button', { name: 'Roll back to the previous version…' }));

    const dialog = await screen.findByRole('dialog', { name: 'Roll back Notes' });
    expect(within(dialog).getByText(/Reactivates the version that ran before 1\.0\.0/)).toBeInTheDocument();
    await user.click(within(dialog).getByRole('button', { name: 'Roll back' }));
    await waitFor(() => expect(called).toBe(true));
  });

  it('lets Escape close the confirmation first, then the drawer', async () => {
    const { user, onClose } = setup(plugin());
    await tab(user, 'Actions');
    await user.click(screen.getByRole('button', { name: 'Disable…' }));
    await screen.findByRole('dialog', { name: 'Disable Notes' });

    await user.keyboard('{Escape}');
    await waitFor(() => expect(screen.queryByRole('dialog', { name: 'Disable Notes' })).not.toBeInTheDocument(), {
      timeout: 5000,
    });
    expect(onClose).not.toHaveBeenCalled();

    await user.keyboard('{Escape}');
    await waitFor(() => expect(onClose).toHaveBeenCalled(), { timeout: 5000 });
  });

  describe('purge', () => {
    const uninstalled = () => plugin({ status: 'uninstalled' });

    it('sums what it deletes and says when there is nothing of a kind', async () => {
      server.use(
        purgePlan({
          schema: 'plugin_acme_notes',
          tables: [
            { name: 'a', estimatedRows: 100, bytes: 1024 },
            { name: 'b', estimatedRows: -1, bytes: 1024 },
          ],
          grants: 1,
          settings: 0,
          artifacts: 0,
        }),
      );
      const { user } = setup(uninstalled());
      await tab(user, 'Actions');
      await user.click(screen.getByRole('button', { name: 'Purge its data…' }));

      const dialog = await screen.findByRole('dialog', { name: "Purge Notes's data" });
      expect(await within(dialog).findByText(/2 tables, about 100 rows, 2 KB/)).toBeInTheDocument();
      expect(within(dialog).getByText('1 role grant of its permissions')).toBeInTheDocument();
      expect(within(dialog).getByText('no saved settings')).toBeInTheDocument();
      expect(within(dialog).getByText('no stored jars')).toBeInTheDocument();
    });

    it('says a purge with no tables and no grants deletes none', async () => {
      server.use(purgePlan({ schema: 'plugin_acme_notes', tables: [], grants: 0, settings: 3, artifacts: 1 }));
      const { user } = setup(uninstalled());
      await tab(user, 'Actions');
      await user.click(screen.getByRole('button', { name: 'Purge its data…' }));

      const dialog = await screen.findByRole('dialog', { name: "Purge Notes's data" });
      expect(await within(dialog).findByText(/no tables, about 0 rows/)).toBeInTheDocument();
      expect(within(dialog).getByText('no role grants of its permissions')).toBeInTheDocument();
      expect(within(dialog).getByText('3 saved settings')).toBeInTheDocument();
      expect(within(dialog).getByText('1 stored jar')).toBeInTheDocument();
    });

    it('says when the estimate is unavailable', async () => {
      server.use(purgePlan({ title: 'Error', detail: 'no statistics' }, 500));
      const { user } = setup(uninstalled());
      await tab(user, 'Actions');
      await user.click(screen.getByRole('button', { name: 'Purge its data…' }));

      const dialog = await screen.findByRole('dialog', { name: "Purge Notes's data" });
      expect(await within(dialog).findByText('The estimate is unavailable: no statistics')).toBeInTheDocument();
    });

    it('deletes after the id is typed, then closes the drawer', async () => {
      let purged = false;
      server.use(
        http.post('*/api/v1/admin/plugins/acme-notes/purge', ({ request }) => {
          if (new URL(request.url).searchParams.get('dryRun') === 'false') purged = true;
          return HttpResponse.json({ schema: 's', tables: [], grants: 0, settings: 0, artifacts: 0 });
        }),
      );
      const { user, onClose } = setup(uninstalled());
      await tab(user, 'Actions');
      await user.click(screen.getByRole('button', { name: 'Purge its data…' }));

      const dialog = await screen.findByRole('dialog', { name: "Purge Notes's data" });
      await holdButton(within(dialog).getByRole('button', { name: 'Delete its data permanently' }));

      await waitFor(() => expect(purged).toBe(true));
      await waitFor(() => expect(onClose).toHaveBeenCalled());
    });
  });
});
