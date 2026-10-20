import { describe, expect, it } from 'vitest';
import { holdButton } from '../../test/hold.ts';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';

import { renderWithProviders } from '../../test/render.tsx';
import { server } from '../../test/setup.ts';
import type { PluginPlanView } from './api.ts';
import { info, me, plan } from './fixtures.ts';
import { InstallDialog } from './InstallDialog.tsx';
import { PlanReview } from './PlanReview.tsx';

const SHA = 'b'.repeat(64);

/** A plan whose publisher status is overridden. */
function trustPlan(over: Partial<PluginPlanView> = {}, trust: Partial<PluginPlanView['trust']> = {}): PluginPlanView {
  const base = plan(over);
  return { ...base, trust: { ...base.trust, ...trust } };
}

const onTrust = () => undefined;

describe('PlanReview', () => {
  it('states who made a new plugin, which Rahdar versions it supports, and every capability as a sentence', () => {
    renderWithProviders(
      <PlanReview
        plan={plan({
          pendingChangesets: [{ id: '0001', author: 'acme', reversible: true }],
          info: info({
            requires: ['acme-core', 'acme-audit'],
            contributions: {
              ui: true,
              permissions: [
                { action: 'notes:read', description: null },
                { action: 'notes:write', description: 'Write notes' },
              ],
              settingKeys: ['notes.limit'],
              streamTopics: ['notes'],
              mcpTools: [
                { name: 'notes_search', posture: 'read', description: null },
                { name: 'notes_delete', posture: 'write', description: null },
                { name: 'notes_purge', posture: 'destructive', description: null },
              ],
              identityProviders: [],
              auditReceive: false,
              auditRetentionHold: false,
            },
          }),
        })}
      />,
    );

    expect(screen.getByText('Notes 1.0.0')).toBeInTheDocument();
    expect(screen.getByText('by Acme · Apache-2.0 · supports Rahdar 2026.01.0 and later')).toBeInTheDocument();
    expect(screen.getByText('Shared notes on subscriptions.')).toBeInTheDocument();
    expect(screen.getByText(/Add screens to Rahdar, which act with the rights of whoever views them/)).toBeVisible();
    expect(screen.getByText(/Run code inside Rahdar/)).toBeVisible();
    expect(screen.getByText('Give the assistant 1 read-only tool: notes_search.')).toBeVisible();
    expect(
      screen.getByText(/Give the assistant 2 tools that change things: notes_delete, notes_purge\./),
    ).toBeVisible();
    expect(
      screen.getByText('Define 2 permissions you can grant through roles: notes:read, notes:write (Write notes).'),
    ).toBeVisible();
    expect(screen.getByText('Keep 1 setting of its own.')).toBeVisible();
    expect(screen.getByText('Send live updates to open screens.')).toBeVisible();
    expect(screen.getByText('Create its own database schema with 1 change.')).toBeVisible();
    expect(screen.getByText('Depend on acme-core, acme-audit.')).toBeVisible();
    // A fresh install has nothing to compare against.
    expect(screen.queryByText('What changes')).not.toBeInTheDocument();
  });

  it('says a plugin that needs a license needs one, and a plugin that does not says nothing of it', () => {
    const { unmount } = renderWithProviders(<PlanReview plan={plan({ info: info({ requiresLicense: true }) })} />);
    expect(screen.getByText(/Need a license file, which you upload under its License tab/)).toBeVisible();
    unmount();

    renderWithProviders(<PlanReview plan={plan()} />);
    expect(screen.queryByText(/license file/)).not.toBeInTheDocument();
  });

  it('lists only what a plugin with no screens, tools or schema actually asks for', () => {
    renderWithProviders(
      <PlanReview
        plan={plan({
          info: info({
            description: null,
            license: null,
            until: '2027.01.0',
            contributions: {
              ui: false,
              permissions: [],
              settingKeys: [],
              streamTopics: [],
              mcpTools: [],
              identityProviders: [],
              auditReceive: false,
              auditRetentionHold: false,
            },
          }),
        })}
      />,
    );

    expect(screen.getByText('by Acme · supports Rahdar 2026.01.0 to 2027.01.0')).toBeInTheDocument();
    expect(screen.queryByText(/Add screens to Rahdar/)).not.toBeInTheDocument();
    expect(screen.queryByText(/Give the assistant/)).not.toBeInTheDocument();
    expect(screen.queryByText(/permission/)).not.toBeInTheDocument();
    expect(screen.queryByText(/database schema/)).not.toBeInTheDocument();
    expect(screen.queryByText('Show the SQL')).not.toBeInTheDocument();
    expect(screen.getByText(/Run code inside Rahdar/)).toBeVisible();
  });

  it('shows an update against the installed version: what it adds, removes, and which roles lose a permission', () => {
    renderWithProviders(
      <PlanReview
        plan={plan({
          fromVersion: '0.9.0',
          toVersion: '1.0.0',
          pendingChangesets: [{ id: '0002', author: 'acme', reversible: true }],
          diff: {
            permissionsAdded: ['notes:share'],
            permissionsRemoved: ['notes:admin'],
            settingKeysAdded: ['notes.limit'],
            settingKeysRemoved: ['notes.old'],
            streamTopicsAdded: ['notes.live'],
            streamTopicsRemoved: ['notes.stale'],
            mcpToolsAdded: ['notes_search'],
            mcpToolsRemoved: ['notes_list'],
            identityProvidersAdded: [],
            identityProvidersRemoved: [],
            auditAdded: [],
            auditRemoved: [],
          },
          rolesLosingPermission: { 'notes:admin': 1, 'notes:old': 3, 'notes:kept': 0 },
          info: info({ changeNotes: 'Adds sharing.' }),
        })}
      />,
    );

    expect(screen.getByText(/\(installed: 0\.9\.0\)/)).toBeInTheDocument();
    for (const line of [
      'Adds permission notes:share',
      'Removes permission notes:admin',
      'Adds assistant tool notes_search',
      'Removes assistant tool notes_list',
      'Adds setting notes.limit',
      'Removes setting notes.old',
      'Adds live topic notes.live',
      'Removes live topic notes.stale',
    ]) {
      expect(screen.getByText(line)).toBeInTheDocument();
    }
    expect(
      screen.getByText('1 role grants notes:admin, which this version removes; those roles lose it.'),
    ).toBeVisible();
    expect(screen.getByText('3 roles grant notes:old, which this version removes; those roles lose it.')).toBeVisible();
    // A permission no role holds loses nothing, so it is not reported.
    expect(screen.queryByText(/notes:kept/)).not.toBeInTheDocument();
    expect(screen.getByText('Adds sharing.')).toBeInTheDocument();
    expect(screen.getByText('Change its own database schema with 1 change.')).toBeVisible();
  });

  it('does not present a reinstall of the same version as an update', () => {
    renderWithProviders(<PlanReview plan={plan({ fromVersion: '1.0.0', toVersion: '1.0.0' })} />);

    expect(screen.queryByText(/installed:/)).not.toBeInTheDocument();
    expect(screen.queryByText('What changes')).not.toBeInTheDocument();
  });

  it('warns about an irreversible schema change before anything is armed, and reveals the SQL on request', async () => {
    const user = userEvent.setup();
    renderWithProviders(
      <PlanReview
        plan={plan({
          activationClass: 'BRIEF_MAINTENANCE',
          reversible: false,
          pendingChangesets: [
            { id: '0001', author: 'acme', reversible: false },
            { id: '0002', author: 'acme', reversible: true },
          ],
          updateSql: 'CREATE TABLE note (id uuid);\n',
        })}
      />,
    );

    expect(screen.getByText(/Irreversible: 1 database change cannot be undone/)).toBeVisible();
    expect(screen.getByText(/Its database schema is created, then it starts/)).toBeVisible();
    const toggle = screen.getByRole('button', { name: 'Show the SQL' });
    expect(toggle).toHaveAttribute('aria-expanded', 'false');

    await user.click(toggle);
    expect(screen.getByRole('button', { name: 'Hide the SQL' })).toHaveAttribute('aria-expanded', 'true');
    await user.click(screen.getByRole('button', { name: 'Hide the SQL' }));
    expect(screen.getByRole('button', { name: 'Show the SQL' })).toHaveAttribute('aria-expanded', 'false');
  });

  it('says a reversible change can be rolled back by not warning about it', () => {
    renderWithProviders(
      <PlanReview plan={plan({ pendingChangesets: [{ id: '0001', author: 'acme', reversible: true }] })} />,
    );

    expect(screen.queryByText(/Irreversible/)).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Show the SQL' })).toBeInTheDocument();
  });

  it('blocks on a missing requirement, naming it, and lists the upload warnings', () => {
    const { rerender } = renderWithProviders(<PlanReview plan={plan({ missingRequires: ['acme-core'] })} />);

    expect(screen.getByText('It cannot be activated yet')).toBeInTheDocument();
    expect(screen.getByText(/It requires acme-core, which is not active\. Install or enable it first\./)).toBeVisible();
    expect(screen.queryByText('Worth knowing')).not.toBeInTheDocument();

    rerender(
      <PlanReview
        plan={plan({ missingRequires: ['acme-core', 'acme-audit'] })}
        warnings={[
          { code: 'old-api', message: 'Uses a deprecated API.', fix: 'Move to the new one.', severity: 'WARNING' },
        ]}
      />,
    );
    expect(screen.getByText(/which are not active\. Install or enable them first\./)).toBeVisible();
    expect(screen.getByText('Worth knowing')).toBeInTheDocument();
    expect(screen.getByText('Uses a deprecated API.')).toBeInTheDocument();
  });
});

describe('PlanReview publisher', () => {
  it('names a trusted publisher in words, with a copyable fingerprint', () => {
    renderWithProviders(<PlanReview plan={plan()} trust={{ canInstall: true, onTrust }} />);
    expect(screen.getByText('Verified')).toBeInTheDocument();
    expect(screen.getByText('AB:CD:EF')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Copy fingerprint' })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Trust this key…' })).toBeNull();
  });

  it('offers to trust the key only when it is untrusted', () => {
    const untrusted = trustPlan({}, { status: 'UNTRUSTED', keyName: null, allowed: false });
    const first = renderWithProviders(<PlanReview plan={untrusted} trust={{ canInstall: true, onTrust }} />);
    expect(screen.getByText('Untrusted key')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Trust this key…' })).toBeEnabled();
    first.unmount();

    const second = renderWithProviders(<PlanReview plan={untrusted} trust={{ canInstall: false, onTrust }} />);
    expect(screen.getByRole('button', { name: 'Trust this key…' })).toBeDisabled();
    expect(screen.getByText('Only someone who can install plugins can trust a key.')).toBeInTheDocument();
    second.unmount();

    renderWithProviders(
      <PlanReview
        plan={trustPlan({}, { status: 'UNSIGNED', fingerprint: null, subject: null, keyName: null, allowed: false })}
        trust={{ canInstall: true, onTrust }}
      />,
    );
    expect(screen.getByText('Unsigned')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Trust this key…' })).toBeNull();
    expect(screen.getByText(/An installer can allow unverified plugins under Trusted keys/)).toBeInTheDocument();
  });

  it('shows a changed signer old to new and emphasises an added permission', () => {
    renderWithProviders(
      <PlanReview
        plan={trustPlan(
          { fromVersion: '0.9.0', diff: { ...plan().diff, permissionsAdded: ['acme-notes:write'] } },
          { signerChanged: true, previousFingerprint: '11:22' },
        )}
      />,
    );
    expect(screen.getByText(/Signer changed:/)).toHaveTextContent('Signer changed: 11:22 → AB:CD:EF');
    expect(screen.getByText('Adds permission acme-notes:write')).toBeInTheDocument();
  });

  it('says that an allowed unsigned plugin is unverified', () => {
    renderWithProviders(
      <PlanReview plan={trustPlan({}, { status: 'UNSIGNED', fingerprint: null, subject: null, allowed: true })} />,
    );
    expect(screen.getByText(/Unverified: it can be installed only because an installer allowed/)).toBeInTheDocument();
  });
});

describe('InstallDialog trust', () => {
  it('keeps Activate disabled, with the reason, until the acknowledgement is ticked, then sends it', async () => {
    let query = 'not called';
    server.use(
      me(),
      http.get(`*/api/v1/admin/plugins/uploads/${SHA}`, () =>
        HttpResponse.json(
          trustPlan(
            { fromVersion: '0.9.0', acknowledgements: ['permissions-added', 'signer-changed'] },
            { signerChanged: true, previousFingerprint: '11:22' },
          ),
        ),
      ),
      http.post('*/api/v1/admin/plugins/uploads/:sha/activate', ({ request }) => {
        query = new URL(request.url).search;
        return HttpResponse.json(plan(), { status: 202 });
      }),
    );
    const user = userEvent.setup();
    renderWithProviders(<InstallDialog source={{ kind: 'resume', sha: SHA }} canInstall onClose={() => undefined} />);

    const activate = await screen.findByRole('button', { name: 'Update Notes to 1.0.0' });
    expect(activate).toBeDisabled();
    expect(screen.getByText(/It is signed by a different key than the installed version/)).toBeInTheDocument();
    expect(screen.getByText('Tick the confirmation above to activate.')).toBeInTheDocument();

    await user.click(screen.getByRole('checkbox', { name: 'I have read this and want to continue' }));
    expect(activate).toBeEnabled();
    await holdButton(activate);
    await waitFor(() => expect(query).toBe('?acknowledge=true'));
  });

  it('says a sign-in plugin will receive passwords, and needs the confirmation', async () => {
    const signIn = trustPlan({
      acknowledgements: ['signin-added'],
      diff: { ...plan().diff, identityProvidersAdded: ['acme-notes:corp'] },
      info: info({
        contributions: {
          ...info().contributions,
          identityProviders: [{ id: 'acme-notes:corp', label: 'Corporate directory' }],
        },
      }),
    });
    server.use(
      me(),
      http.get(`*/api/v1/admin/plugins/uploads/${SHA}`, () => HttpResponse.json(signIn)),
    );
    renderWithProviders(<InstallDialog source={{ kind: 'resume', sha: SHA }} canInstall onClose={() => undefined} />);

    expect(
      await screen.findByText('This plugin will receive the passwords users type to sign in with Corporate directory.'),
    ).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Install Notes 1.0.0' })).toBeDisabled();
  });

  it('lists a sign-in among what the plugin can do and what an update adds', () => {
    renderWithProviders(
      <PlanReview
        plan={plan({
          fromVersion: '0.9.0',
          diff: { ...plan().diff, identityProvidersAdded: ['acme-notes:corp'] },
          info: info({
            contributions: {
              ...info().contributions,
              identityProviders: [{ id: 'acme-notes:corp', label: 'Corporate directory' }],
            },
          }),
        })}
      />,
    );

    expect(
      screen.getByText(/Receive the passwords users type to sign in with Corporate directory/),
    ).toBeInTheDocument();
    expect(screen.getByText('Adds sign-in acme-notes:corp')).toBeInTheDocument();
  });

  it('says a plugin with audit access receives events, and needs the confirmation', async () => {
    const audit = trustPlan({
      acknowledgements: ['audit-access-added'],
      diff: { ...plan().diff, auditAdded: ['receive', 'retentionHold'] },
      info: info({ contributions: { ...info().contributions, auditReceive: true, auditRetentionHold: true } }),
    });
    server.use(
      me(),
      http.get(`*/api/v1/admin/plugins/uploads/${SHA}`, () => HttpResponse.json(audit)),
    );
    renderWithProviders(<InstallDialog source={{ kind: 'resume', sha: SHA }} canInstall onClose={() => undefined} />);

    expect(
      await screen.findByText(
        'This plugin will receive every audit event, unfiltered and hold audit records back from retention.',
      ),
    ).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Install Notes 1.0.0' })).toBeDisabled();
  });

  it('lists audit access among what the plugin can do', () => {
    renderWithProviders(
      <PlanReview
        plan={plan({
          info: info({ contributions: { ...info().contributions, auditReceive: true, auditRetentionHold: true } }),
        })}
      />,
    );

    expect(screen.getByText('Receives every audit event, unfiltered')).toBeInTheDocument();
    expect(screen.getByText('Can hold audit records back from retention')).toBeInTheDocument();
  });

  it('lists audit access an update removes', () => {
    renderWithProviders(
      <PlanReview plan={plan({ fromVersion: '0.9.0', diff: { ...plan().diff, auditRemoved: ['retentionHold'] } })} />,
    );

    expect(screen.getByText('Removes audit access hold audit records back from retention')).toBeInTheDocument();
  });

  it('trusts the key from the upload, then re-plans so Continue opens', async () => {
    let body: unknown = null;
    let trusted = false;
    server.use(
      me(),
      http.get(`*/api/v1/admin/plugins/uploads/${SHA}`, () =>
        HttpResponse.json(trusted ? plan() : trustPlan({}, { status: 'UNTRUSTED', keyName: null, allowed: false })),
      ),
      http.post('*/api/v1/admin/plugins/keys', async ({ request }) => {
        body = await request.json();
        trusted = true;
        return HttpResponse.json(
          {
            fingerprint: 'AB:CD:EF',
            name: 'Acme',
            subject: 'CN=Acme',
            addedAt: new Date().toISOString(),
            addedBy: 'ops',
          },
          { status: 201 },
        );
      }),
    );
    const user = userEvent.setup();
    renderWithProviders(<InstallDialog source={{ kind: 'resume', sha: SHA }} canInstall onClose={() => undefined} />);

    expect(await screen.findByRole('button', { name: 'Continue' })).toBeDisabled();
    expect(screen.getByText('Continue is unavailable until its publisher is trusted.')).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Trust this key…' }));

    const dialog = await screen.findByRole('dialog', { name: "Trust this publisher's key" });
    expect(within(dialog).getByText('AB:CD:EF')).toBeInTheDocument();
    expect(within(dialog).getByText('Certificate subject: CN=Acme')).toBeInTheDocument();
    await user.click(within(dialog).getByRole('button', { name: 'Trust this key' }));
    expect(within(dialog).getByText('Give the key a name, such as the publisher.')).toBeInTheDocument();
    expect(within(dialog).getByText('Confirm you compared the fingerprint.')).toBeInTheDocument();
    expect(body).toBeNull();

    await user.type(within(dialog).getByLabelText('Key name'), 'Acme');
    await user.click(within(dialog).getByRole('checkbox', { name: /I compared this fingerprint/ }));
    await user.click(within(dialog).getByRole('button', { name: 'Trust this key' }));

    await waitFor(() => expect(body).toEqual({ name: 'Acme', upload: SHA }));
    await waitFor(() => expect(screen.getByRole('button', { name: 'Continue' })).toBeEnabled());
    expect(screen.getByText('Verified')).toBeInTheDocument();
  });
});
