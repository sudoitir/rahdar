import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { http, HttpResponse } from 'msw';
import { Notifications, notifications } from '@mantine/notifications';
import { act, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

import { renderWithProviders } from '../../test/render.tsx';
import { server } from '../../test/setup.ts';
import { RulesPanel } from './RulesPanel.tsx';
import { paged } from '../../kernel/api/paging.ts';
import { holdButton } from '../../test/hold.ts';

function rule(over: Record<string, unknown> = {}) {
  return {
    id: 'r1',
    clusterId: 'c1',
    name: 'Deep queue',
    kind: 'METRIC_THRESHOLD',
    metric: 'openbroker.backlog',
    comparator: 'GT',
    threshold: 1000,
    stateCondition: null,
    forSeconds: 60,
    severity: 'WARNING',
    scope: null,
    enabled: true,
    channelIds: [],
    sourceAvailable: true,
    createdAt: '2026-01-01T00:00:00Z',
    updatedAt: '2026-01-01T00:00:00Z',
    ...over,
  };
}

function mockMe(permissions: string[]) {
  server.use(
    http.get('*/api/v1/auth/me', () =>
      HttpResponse.json({
        id: 'u1',
        username: 'ops',
        mustChangePassword: false,
        grants: [{ scopeType: 'GLOBAL', scopeId: null, permissions }],
      }),
    ),
  );
}

function renderRules() {
  return renderWithProviders(
    <>
      <Notifications />
      <RulesPanel clusterId="c1" />
    </>,
  );
}

describe('RulesPanel', () => {
  afterEach(() => act(() => notifications.clean()));

  beforeEach(() => {
    mockMe(['alert:read', 'alert:write']);
    server.use(http.get('*/api/v1/clusters/c1/alerts/plugin-metrics', () => HttpResponse.json(paged([]))));
  });

  it('offers the metrics running plugins publish and describes the chosen one', async () => {
    server.use(
      http.get('*/api/v1/clusters/c1/alerts/rules', () => HttpResponse.json(paged([]))),
      http.get('*/api/v1/channels', () => HttpResponse.json(paged([]))),
      http.get('*/api/v1/clusters/c1/alerts/plugin-metrics', () =>
        HttpResponse.json(
          paged([
            {
              metric: 'acme-notes:edits',
              plugin: 'acme-notes',
              description: 'Edits per note.',
              unit: 'count',
              subject: 'note',
            },
          ]),
        ),
      ),
    );
    const user = userEvent.setup();
    renderRules();

    await screen.findByText(/No rules yet/);
    await user.click(screen.getByRole('combobox', { name: 'Metric' }));
    await user.click(await screen.findByText('acme-notes:edits (count, per note)'));

    expect(screen.getByText(/Edits per note\. Published by the acme-notes plugin/)).toBeInTheDocument();
  });

  it('says when a rule watches a metric whose plugin is not running', async () => {
    server.use(
      http.get('*/api/v1/clusters/c1/alerts/rules', () =>
        HttpResponse.json(paged([rule({ metric: 'acme-notes:edits', sourceAvailable: false })])),
      ),
      http.get('*/api/v1/channels', () => HttpResponse.json(paged([]))),
    );
    renderRules();

    expect(await screen.findByText(/Source unavailable/)).toBeInTheDocument();
  });

  it('lists existing rules and shows the threshold condition', async () => {
    server.use(
      http.get('*/api/v1/clusters/c1/alerts/rules', () => HttpResponse.json(paged([rule()]))),
      http.get('*/api/v1/channels', () => HttpResponse.json(paged([]))),
    );
    renderRules();

    expect(await screen.findByText('Deep queue')).toBeInTheDocument();
    expect(screen.getByText('openbroker.backlog > 1000')).toBeInTheDocument();
  });

  it('shows an empty state with no rules', async () => {
    server.use(
      http.get('*/api/v1/clusters/c1/alerts/rules', () => HttpResponse.json(paged([]))),
      http.get('*/api/v1/channels', () => HttpResponse.json(paged([]))),
    );
    renderRules();

    expect(await screen.findByText(/No rules yet/)).toBeInTheDocument();
  });

  it('switching the rule kind to state hides the metric fields and shows the state-condition select', async () => {
    server.use(
      http.get('*/api/v1/clusters/c1/alerts/rules', () => HttpResponse.json(paged([]))),
      http.get('*/api/v1/channels', () => HttpResponse.json(paged([]))),
    );
    const user = userEvent.setup();
    renderRules();

    await screen.findByText(/No rules yet/);
    expect(screen.getByRole('combobox', { name: 'Metric' })).toBeInTheDocument();

    await user.click(screen.getByRole('combobox', { name: 'Kind' }));
    await user.click(await screen.findByText('Cluster state'));

    expect(screen.queryByRole('combobox', { name: 'Metric' })).not.toBeInTheDocument();
    expect(screen.getByRole('combobox', { name: 'State condition' })).toBeInTheDocument();
  });

  it('creates a threshold rule from the form', async () => {
    let created = false;
    server.use(
      http.get('*/api/v1/clusters/c1/alerts/rules', () =>
        HttpResponse.json(paged(created ? [rule({ name: 'Deep queue' })] : [])),
      ),
      http.get('*/api/v1/channels', () => HttpResponse.json(paged([]))),
      http.post('*/api/v1/clusters/c1/alerts/rules', () => {
        created = true;
        return HttpResponse.json(rule(), { status: 201 });
      }),
    );
    const user = userEvent.setup();
    renderRules();

    await screen.findByText(/No rules yet/);
    await user.type(screen.getByLabelText('Name'), 'Deep queue');
    await user.click(screen.getByRole('combobox', { name: 'Metric' }));
    await user.click(await screen.findByText('openbroker.backlog — messages waiting (gauge)'));
    await user.clear(screen.getByLabelText('Threshold'));
    await user.type(screen.getByLabelText('Threshold'), '1000');
    await user.click(screen.getByRole('button', { name: 'Add rule' }));

    expect(await screen.findByText('Deep queue')).toBeInTheDocument();
  });

  it('marks an installation rule and keeps a cluster rule unmarked', async () => {
    server.use(
      http.get('*/api/v1/clusters/c1/alerts/rules', () =>
        HttpResponse.json(
          paged([
            rule(),
            rule({
              id: 'r2',
              clusterId: null,
              name: 'Quota watch',
              kind: 'STATE',
              metric: null,
              comparator: null,
              threshold: null,
              stateCondition: 'STORAGE_QUOTA',
            }),
          ]),
        ),
      ),
      http.get('*/api/v1/channels', () => HttpResponse.json(paged([]))),
    );
    renderRules();

    expect(await screen.findByText('Quota watch')).toBeInTheDocument();
    expect(screen.getAllByText('Installation')).toHaveLength(1);
  });

  it('edits an installation rule on the storage conditions only', async () => {
    server.use(
      http.get('*/api/v1/clusters/c1/alerts/rules', () =>
        HttpResponse.json(
          paged([
            rule({
              id: 'r2',
              clusterId: null,
              name: 'Quota watch',
              kind: 'STATE',
              metric: null,
              comparator: null,
              threshold: null,
              stateCondition: 'STORAGE_QUOTA',
            }),
          ]),
        ),
      ),
      http.get('*/api/v1/channels', () => HttpResponse.json(paged([]))),
    );
    const user = userEvent.setup();
    renderRules();

    await user.click(await screen.findByRole('button', { name: 'Edit Quota watch' }));
    await user.click(screen.getByRole('combobox', { name: 'State condition' }));

    expect(await screen.findByText('storage health')).toBeInTheDocument();
    expect(screen.queryByText('node down')).not.toBeInTheDocument();
    expect(screen.getByRole('combobox', { name: 'Kind' })).toBeDisabled();
  });

  it('keeps every rule control visible but disabled, with the reason, for a reader', async () => {
    mockMe(['alert:read']);
    server.use(
      http.get('*/api/v1/clusters/c1/alerts/rules', () => HttpResponse.json(paged([rule()]))),
      http.get('*/api/v1/channels', () => HttpResponse.json(paged([]))),
    );
    renderRules();

    await screen.findByText('Deep queue');
    expect(await screen.findByText(/that needs the/)).toHaveTextContent('alert:write');
    expect(screen.getByRole('button', { name: 'Add rule' })).toBeDisabled();
    expect(screen.getByRole('switch', { name: 'Disable Deep queue' })).toBeDisabled();
    expect(screen.getByRole('button', { name: 'Edit Deep queue' })).toBeDisabled();
    expect(screen.getByRole('button', { name: 'Delete Deep queue' })).toBeDisabled();
  });

  it('says the rules could not be loaded instead of saying there are none', async () => {
    server.use(
      http.get('*/api/v1/clusters/c1/alerts/rules', () =>
        HttpResponse.json({ title: 'Unavailable', detail: 'The database is down' }, { status: 503 }),
      ),
      http.get('*/api/v1/channels', () => HttpResponse.json(paged([]))),
    );
    renderRules();

    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent('The database is down');
    expect(within(alert).getByRole('button', { name: 'Retry' })).toBeInTheDocument();
    expect(screen.queryByText('No rules yet')).not.toBeInTheDocument();
  });

  it('says what is wrong beside a field, on blur and on submit, and focuses the first one', async () => {
    server.use(
      http.get('*/api/v1/clusters/c1/alerts/rules', () => HttpResponse.json(paged([]))),
      http.get('*/api/v1/channels', () => HttpResponse.json(paged([]))),
    );
    const user = userEvent.setup();
    renderRules();

    await screen.findByText('No rules yet');
    await user.click(screen.getByLabelText('Name'));
    await user.tab();
    expect(await screen.findByText(/Enter a name/)).toBeInTheDocument();

    // The submit control is never disabled: activating it names every missing field.
    const add = screen.getByRole('button', { name: 'Add rule' });
    expect(add).toBeEnabled();
    await user.click(add);
    expect(await screen.findByText('Choose the metric the rule watches.')).toBeInTheDocument();
    expect(screen.getByText('Enter the value that makes the rule fire.')).toBeInTheDocument();
    expect(screen.getByLabelText('Name')).toHaveFocus();
  });

  it('says a rule was switched off, and says why and what next when it was not', async () => {
    let reply: Response = HttpResponse.json(rule({ enabled: false }));
    server.use(
      http.get('*/api/v1/clusters/c1/alerts/rules', () => HttpResponse.json(paged([rule()]))),
      http.get('*/api/v1/channels', () => HttpResponse.json(paged([]))),
      http.put('*/api/v1/clusters/c1/alerts/rules/r1', () => reply),
    );
    const user = userEvent.setup();
    renderRules();

    await user.click(await screen.findByRole('switch', { name: 'Disable Deep queue' }));
    expect(await screen.findByText('Disabled rule "Deep queue"')).toBeInTheDocument();

    reply = HttpResponse.json({ title: 'Conflict', detail: 'The rule was changed elsewhere.' }, { status: 409 });
    await user.click(screen.getByRole('switch', { name: 'Disable Deep queue' }));
    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent('Could not disable rule "Deep queue"');
    expect(alert).toHaveTextContent('The rule was changed elsewhere. The switch shows what is stored; try again.');
  });

  it('deletes a rule only after its name is typed, stating what stops', async () => {
    let deleted = false;
    server.use(
      http.get('*/api/v1/clusters/c1/alerts/rules', () =>
        HttpResponse.json(paged(deleted ? [] : [rule({ channelIds: ['ch1', 'ch2'] })])),
      ),
      http.get('*/api/v1/channels', () => HttpResponse.json(paged([]))),
      http.delete('*/api/v1/clusters/c1/alerts/rules/r1', () => {
        deleted = true;
        return new HttpResponse(null, { status: 204 });
      }),
    );
    const user = userEvent.setup();
    renderRules();

    const trigger = await screen.findByRole('button', { name: 'Delete Deep queue' });
    await user.click(trigger);
    const dialog = await screen.findByRole('dialog', { name: 'Delete rule' });
    expect(dialog).toHaveTextContent('stops being evaluated, so it no longer fires or notifies its 2 channels');

    await user.keyboard('{Escape}');
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
    await waitFor(() => expect(trigger).toHaveFocus());

    await user.click(trigger);
    const again = await screen.findByRole('dialog');
    await holdButton(within(again).getByRole('button', { name: 'Delete rule' }));

    expect(await screen.findByText('Deleted rule "Deep queue"')).toBeInTheDocument();
    expect(await screen.findByText('No rules yet')).toBeInTheDocument();
  });

  it('says why a delete failed and keeps the confirmation open', async () => {
    server.use(
      http.get('*/api/v1/clusters/c1/alerts/rules', () => HttpResponse.json(paged([rule()]))),
      http.get('*/api/v1/channels', () => HttpResponse.json(paged([]))),
      http.delete('*/api/v1/clusters/c1/alerts/rules/r1', () =>
        HttpResponse.json({ title: 'Locked', detail: 'The rule is in use.' }, { status: 409 }),
      ),
    );
    const user = userEvent.setup();
    renderRules();

    await user.click(await screen.findByRole('button', { name: 'Delete Deep queue' }));
    const dialog = await screen.findByRole('dialog');
    await holdButton(within(dialog).getByRole('button', { name: 'Delete rule' }));

    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent('Could not delete rule "Deep queue"');
    expect(alert).toHaveTextContent('The rule is in use. It is still listed; try again.');
    expect(screen.getByRole('dialog')).toBeInTheDocument();
  });
});
