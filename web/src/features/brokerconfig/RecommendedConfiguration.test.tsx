import { describe, expect, it, vi } from 'vitest';
import { http, HttpResponse } from 'msw';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

import { renderWithProviders } from '../../test/render.tsx';
import { server } from '../../test/setup.ts';
import { notify } from '../../ui/notify.ts';
import type { ConfigRecommendationView, ConfigRecommendationsView } from './api.ts';
import { RecommendedConfiguration } from './RecommendedConfiguration.tsx';

const SETTING: ConfigRecommendationView = {
  capability: 'management-size-limit',
  title: 'Raise the management message size limit',
  rationale: 'Rahdar reads large attributes through management messages.',
  appliable: true,
  section: 'ADDRESS_SETTING',
  match: 'activemq.management#',
  values: { managementMessageAttributeSizeLimit: 262144, maxSizeBytes: 1024 },
  roles: {},
  accountRoles: [],
  accountRolesSource: null,
  keys: ['managementMessageAttributeSizeLimit'],
  manualSnippet: null,
};

const SECURITY: ConfigRecommendationView = {
  capability: 'management-permissions',
  title: 'Let the monitoring role manage',
  rationale: 'Management needs the manage permission.',
  appliable: true,
  section: 'SECURITY_SETTING',
  match: 'activemq.management#',
  values: {},
  roles: { manage: ['monitor'] },
  accountRoles: [],
  accountRolesSource: { kind: 'SECURITY_SETTINGS', reason: 'the broker has no user store to list' },
  keys: ['manage'],
  manualSnippet: null,
};

const MANUAL: ConfigRecommendationView = {
  capability: 'metrics-plugin',
  title: 'Install the metrics plugin',
  rationale: 'No management operation writes a plugin.',
  appliable: false,
  section: null,
  match: null,
  values: {},
  roles: {},
  accountRoles: [],
  accountRolesSource: null,
  keys: [],
  manualSnippet: '<metrics><plugin class-name="x"/></metrics>',
};

const view = (...recommendations: ConfigRecommendationView[]): ConfigRecommendationsView => ({
  seededFrom: 'broker-1',
  recommendations,
});

describe('RecommendedConfiguration', () => {
  it('teaches that there is nothing to change, in place of an empty panel', () => {
    renderWithProviders(<RecommendedConfiguration clusterId="c1" recommendations={view()} />);

    expect(screen.getByText('Nothing to recommend')).toBeInTheDocument();
    expect(screen.getByText(/There is nothing Rahdar would change/)).toBeInTheDocument();
  });

  it('shows both halves as sections: what Rahdar can apply and what still needs broker.xml', () => {
    renderWithProviders(<RecommendedConfiguration clusterId="c1" recommendations={view(SETTING, MANUAL)} />);

    expect(screen.getByRole('heading', { level: 2, name: 'Rahdar can apply these' })).toBeInTheDocument();
    expect(screen.getByRole('heading', { level: 2, name: 'These still need a broker.xml edit' })).toBeInTheDocument();
    // The key this sets is shown; the keys it writes back as they are wait behind one disclosure.
    expect(screen.getByText('managementMessageAttributeSizeLimit', { selector: 'code' })).toBeInTheDocument();
    const kept = screen.getByRole('button', { name: 'Show the 1 key it keeps as they are' });
    expect(kept).toHaveAttribute('aria-expanded', 'false');
    expect(screen.getByText('Install the metrics plugin')).toBeInTheDocument();
    // The fragment scrolls when a line is long, so the block itself is a named stop for the keyboard.
    const fragment = screen.getByRole('region', { name: 'broker.xml for Install the metrics plugin' });
    expect(fragment).toHaveAttribute('tabindex', '0');
    expect(fragment).toHaveTextContent('<metrics><plugin class-name="x"/></metrics>');
  });

  it('declares the chosen settings, announces it and hands over to the review of the plan', async () => {
    let body: unknown;
    server.use(
      http.post('*/api/v1/clusters/c1/artemis/config/recommendations/declare', async ({ request }) => {
        body = await request.json();
        return HttpResponse.json({});
      }),
    );
    const succeeded = vi.spyOn(notify, 'succeeded');
    const onDeclared = vi.fn();
    const user = userEvent.setup();
    renderWithProviders(
      <RecommendedConfiguration clusterId="c1" recommendations={view(SETTING, SECURITY)} onDeclared={onDeclared} />,
    );

    await user.click(screen.getByRole('checkbox', { name: 'Raise the management message size limit' }));
    await user.click(screen.getByRole('button', { name: 'Declare & review the plan' }));

    await waitFor(() => expect(onDeclared).toHaveBeenCalledTimes(1));
    expect(body).toEqual({ capabilities: ['management-permissions'], roles: { 'activemq.management#': ['monitor'] } });
    expect(succeeded).toHaveBeenCalledWith(
      expect.objectContaining({ action: expect.objectContaining({ past: 'Declared' }) }),
    );
    succeeded.mockRestore();
  });

  it('keeps Declare visible, says why it cannot be used, and does not send', async () => {
    const user = userEvent.setup();
    renderWithProviders(
      <RecommendedConfiguration
        clusterId="c1"
        recommendations={view(SETTING)}
        disabledReason="Needs the permission on this cluster."
      />,
    );

    const declare = screen.getByRole('button', { name: 'Declare & review the plan' });
    expect(declare).toBeDisabled();
    expect(screen.getByText('Needs the permission on this cluster.')).toBeInTheDocument();
    await user.click(declare);
  });

  it('refuses to declare a security setting that would grant nobody anything, beside the field', async () => {
    renderWithProviders(
      <RecommendedConfiguration
        clusterId="c1"
        recommendations={view({ ...SECURITY, roles: {} })}
        disabledReason={undefined}
      />,
    );

    expect(screen.getByText('At least one role, or this grants nobody anything.')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Declare & review the plan' })).toBeDisabled();
  });

  it("says the roles were read from Rahdar's account, and which of them the broker already names", () => {
    const fromAccount: ConfigRecommendationView = {
      ...SECURITY,
      roles: { manage: ['amq'] },
      accountRoles: ['amq', 'ops'],
      accountRolesSource: { kind: 'BROKER_ACCOUNT', reason: null },
    };
    renderWithProviders(<RecommendedConfiguration clusterId="c1" recommendations={view(fromAccount)} />);

    expect(
      screen.getByText(
        "Read from Rahdar's broker account (amq, ops). Narrowed to the roles the broker already names for this address.",
      ),
    ).toBeInTheDocument();
    // Only the role the broker already names is prefilled, not every role the account holds.
    expect(screen.getByText('amq')).toBeInTheDocument();
    expect(screen.queryByText('ops')).toBeNull();
  });

  it("states why the account's roles were not used when the broker's own are prefilled", () => {
    renderWithProviders(<RecommendedConfiguration clusterId="c1" recommendations={view(SECURITY)} />);

    expect(
      screen.getByText(
        "Rahdar's account roles could not be read: the broker has no user store to list. These are the roles the broker names for this address.",
      ),
    ).toBeInTheDocument();
  });

  it('asks the operator to name a role when neither source has one', () => {
    const neither: ConfigRecommendationView = {
      ...SECURITY,
      roles: {},
      accountRolesSource: { kind: 'NONE', reason: 'the broker lists no roles for artemis' },
    };
    renderWithProviders(<RecommendedConfiguration clusterId="c1" recommendations={view(neither)} />);

    expect(screen.getByText(/the broker lists no roles for artemis\. The broker names no role/)).toBeInTheDocument();
    expect(screen.getByText('At least one role, or this grants nobody anything.')).toBeInTheDocument();
  });

  it('states a failed declaration with its cause and offers it again', async () => {
    let attempts = 0;
    server.use(
      http.post('*/api/v1/clusters/c1/artemis/config/recommendations/declare', () => {
        attempts += 1;
        return attempts === 1
          ? HttpResponse.json({ title: 'Down', detail: 'The configuration store is not answering.' }, { status: 503 })
          : HttpResponse.json({});
      }),
    );
    const onDeclared = vi.fn();
    const user = userEvent.setup();
    renderWithProviders(
      <RecommendedConfiguration clusterId="c1" recommendations={view(SETTING)} onDeclared={onDeclared} />,
    );

    await user.click(screen.getByRole('button', { name: 'Declare & review the plan' }));
    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent('The configuration store is not answering.');
    expect(onDeclared).not.toHaveBeenCalled();

    await user.click(within(alert).getByRole('button', { name: 'Retry' }));
    await waitFor(() => expect(onDeclared).toHaveBeenCalledTimes(1));
  });

  it('only previews before the cluster is registered: no declare control, the reason in words', () => {
    renderWithProviders(<RecommendedConfiguration recommendations={view(SETTING)} />);

    expect(screen.queryByRole('button', { name: 'Declare & review the plan' })).toBeNull();
    expect(screen.getByText(/Register the cluster first/)).toBeInTheDocument();
  });
});
