import { afterEach, describe, expect, it, vi } from 'vitest';
import { http, HttpResponse } from 'msw';
import { Notifications, notifications } from '@mantine/notifications';
import { act, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

import { renderWithProviders } from '../../test/render.tsx';
import { server } from '../../test/setup.ts';
import { ChannelEditor, TestOutcome } from './ChannelEditor.tsx';
import type { NotificationChannelView } from './api.ts';

function channel(over: Partial<NotificationChannelView> = {}): NotificationChannelView {
  return {
    id: 'ch1',
    name: 'ops-hook',
    kind: 'WEBHOOK',
    config: '{"url":"https://alerts.example.com/hook"}',
    enabled: true,
    hasSecret: true,
    boundRuleCount: 0,
    health: null,
    ...over,
  } as NotificationChannelView;
}

/** Records the JSON body of the request a handler receives. */
function capture(method: 'post' | 'put', path: string, response: unknown = channel(), status = 200) {
  const seen: { body?: Record<string, unknown> } = {};
  server.use(
    http[method](path, async ({ request }) => {
      seen.body = (await request.json()) as Record<string, unknown>;
      return HttpResponse.json(response as Record<string, unknown>, { status });
    }),
  );
  return seen;
}

function setup(existing: NotificationChannelView | null = null) {
  const onClose = vi.fn();
  const user = userEvent.setup();
  renderWithProviders(
    <>
      <Notifications />
      <ChannelEditor opened channel={existing} onClose={onClose} />
    </>,
  );
  return { user, onClose };
}

async function dialog() {
  return within(await screen.findByRole('dialog'));
}

/** Fills a field in one paste, which is far cheaper than typing a long value key by key. */
async function fill(user: ReturnType<typeof userEvent.setup>, field: HTMLElement, text: string) {
  await user.click(field);
  await user.paste(text);
}

async function chooseKind(user: ReturnType<typeof userEvent.setup>, label: string) {
  const d = await dialog();
  await user.click(d.getByRole('combobox', { name: /^Kind/ }));
  await user.click(await screen.findByRole('option', { name: label }));
}

afterEach(() => act(() => notifications.clean()));

describe('ChannelEditor: adding', () => {
  it('renders nothing while closed', () => {
    renderWithProviders(<ChannelEditor opened={false} channel={null} onClose={() => {}} />);
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
  });

  it('describes the chosen kind and asks for its secret by name', async () => {
    const { user } = setup();
    const d = await dialog();
    expect(d.getByText(/A Slack incoming webhook/)).toBeInTheDocument();

    await chooseKind(user, 'PagerDuty');
    expect(d.getByText(/PagerDuty Events API v2/)).toBeInTheDocument();
    expect(d.getByLabelText(/^Routing key/)).toBeInTheDocument();
  });

  it('shows the required name error on blur and clears it once typed into', async () => {
    const { user } = setup();
    const d = await dialog();
    await user.click(d.getByLabelText(/^Name/));
    await user.tab();
    expect(await d.findByText('A name is required.')).toBeInTheDocument();
  });

  it('creates a signed webhook with a generated secret, sending the trimmed config', async () => {
    const seen = capture('post', '*/api/v1/channels', channel(), 201);
    const { user, onClose } = setup();
    await chooseKind(user, 'Signed webhook');
    const d = await dialog();

    await user.type(d.getByLabelText(/^Name/), '  hook  ');
    await user.type(d.getByLabelText(/^Receiver URL/), ' https://alerts.example.com/h ');
    await user.click(d.getByRole('button', { name: 'Generate a random signing secret' }));
    await user.click(d.getByRole('button', { name: 'Add channel' }));

    expect(await screen.findByText('Added channel "hook"')).toBeInTheDocument();
    expect(onClose).toHaveBeenCalled();
    expect(seen.body).toMatchObject({
      name: 'hook',
      kind: 'WEBHOOK',
      config: '{"url":"https://alerts.example.com/h"}',
      enabled: true,
    });
    expect(String(seen.body?.secret)).toMatch(/^whsec_/);
  });

  it('blocks a save with an invalid URL and secret, focusing the first invalid field', async () => {
    const { user, onClose } = setup();
    await chooseKind(user, 'Signed webhook');
    const d = await dialog();

    await user.type(d.getByLabelText(/^Name/), 'hook');
    await user.type(d.getByLabelText(/^Receiver URL/), 'ftp://nope');
    await user.type(d.getByLabelText(/^Signing secret/), '***');
    await user.click(d.getByRole('button', { name: 'Add channel' }));

    expect(await d.findByText('An http or https URL with a host.')).toBeInTheDocument();
    expect(d.getByText('Must be base64, optionally prefixed whsec_.')).toBeInTheDocument();
    expect(d.getByLabelText(/^Receiver URL/)).toHaveFocus();
    expect(onClose).not.toHaveBeenCalled();
  });

  it('offers the PagerDuty regions and asks for a URL only for another receiver', async () => {
    const seen = capture('post', '*/api/v1/channels', channel({ kind: 'PAGERDUTY' }), 201);
    const { user } = setup();
    await chooseKind(user, 'PagerDuty');
    const d = await dialog();
    expect(d.queryByLabelText(/^Events API v2 URL/)).not.toBeInTheDocument();

    await user.click(d.getByRole('combobox', { name: /^Endpoint/ }));
    await user.click(await screen.findByRole('option', { name: 'Another PagerDuty-compatible receiver' }));
    await fill(user, d.getByLabelText(/^Name/), 'pd');
    await user.type(d.getByLabelText(/^Routing key/), 'k'.repeat(32));
    await user.click(d.getByRole('button', { name: 'Add channel' }));
    expect(await d.findByText('An http or https URL with a host.')).toBeInTheDocument();
    expect(d.getByLabelText(/^Events API v2 URL/)).toHaveFocus();

    await fill(user, d.getByLabelText(/^Events API v2 URL/), 'https://oncall.example.com/v2/enqueue');
    await user.click(d.getByRole('button', { name: 'Add channel' }));
    expect(await screen.findByText('Added channel "pd"')).toBeInTheDocument();
    expect(seen.body).toMatchObject({ kind: 'PAGERDUTY', config: '{"url":"https://oncall.example.com/v2/enqueue"}' });
  }, 20_000);

  it('creates an email channel: follows the security choice with the port and warns about no TLS', async () => {
    const seen = capture('post', '*/api/v1/channels', channel({ kind: 'EMAIL' }), 201);
    const { user } = setup();
    await chooseKind(user, 'Email (SMTP)');
    const d = await dialog();
    const port = d.getByLabelText(/^Port/);
    expect(port).toHaveValue('587');

    await user.click(d.getByRole('radio', { name: 'TLS' }));
    expect(port).toHaveValue('465');
    await user.click(d.getByRole('radio', { name: 'STARTTLS' }));
    expect(port).toHaveValue('587');
    expect(d.queryByText(/Without TLS the password/)).not.toBeInTheDocument();
    await user.click(d.getByRole('radio', { name: 'None' }));
    expect(d.getByText(/Without TLS the password/)).toBeInTheDocument();
    expect(port).toHaveValue('587');
    await user.click(d.getByRole('radio', { name: 'TLS' }));
    await user.clear(port);
    await user.type(port, '2525');
    await user.click(d.getByRole('radio', { name: 'STARTTLS' }));
    expect(port).toHaveValue('2525');

    await fill(user, d.getByLabelText(/^Name/), 'mail');
    await fill(user, d.getByLabelText(/^SMTP server/), 'smtp.example.com');
    await fill(user, d.getByLabelText(/^From/), 'artemis@example.com');
    await fill(user, d.getByLabelText(/^Recipients/), 'a@example.com, b@example.com');
    expect(d.getByLabelText(/^Subject prefix/)).toHaveValue('[Rahdar]');
    await user.clear(d.getByLabelText(/^Subject prefix/));
    await fill(user, d.getByLabelText(/^Subject prefix/), '[Ops]');
    await user.click(d.getByRole('button', { name: 'Add channel' }));

    expect(await screen.findByText('Added channel "mail"')).toBeInTheDocument();
    expect(seen.body).toMatchObject({ kind: 'EMAIL', enabled: true });
    expect(JSON.parse(String(seen.body?.config))).toEqual({
      host: 'smtp.example.com',
      port: 2525,
      security: 'STARTTLS',
      username: null,
      from: 'artemis@example.com',
      to: ['a@example.com', 'b@example.com'],
      subjectPrefix: '[Ops]',
    });
    expect(seen.body?.secret).toBeUndefined();
  }, 20_000);

  it('validates every email field on save and validates each on blur', async () => {
    const { user } = setup();
    await chooseKind(user, 'Email (SMTP)');
    const d = await dialog();

    await fill(user, d.getByLabelText(/^Name/), 'mail');
    await user.click(d.getByRole('button', { name: 'Add channel' }));
    expect(await d.findByText('The SMTP server is required.')).toBeInTheDocument();
    expect(d.getByText('A valid sender address.')).toBeInTheDocument();
    expect(d.getByText('At least one recipient.')).toBeInTheDocument();
    expect(d.getByLabelText(/^SMTP server/)).toHaveFocus();

    const port = d.getByLabelText(/^Port/);
    await user.clear(port);
    await fill(user, port, '99999');
    await user.tab();
    expect(await d.findByText('A port between 1 and 65535.')).toBeInTheDocument();
    await user.type(port, '{Backspace}');
    expect(d.queryByText('A port between 1 and 65535.')).not.toBeInTheDocument();
  }, 20_000);

  it('clears the errors when the kind changes', async () => {
    const { user } = setup();
    const d = await dialog();
    await user.click(d.getByRole('button', { name: 'Add channel' }));
    expect(await d.findByText('A name is required.')).toBeInTheDocument();

    await chooseKind(user, 'Microsoft Teams');
    expect(d.queryByText('A name is required.')).not.toBeInTheDocument();
  });

  it('lands a server rejection that names a field beside that field', async () => {
    server.use(
      http.post('*/api/v1/channels', () =>
        HttpResponse.json({ title: 'Invalid', detail: 'secret: is not a reachable webhook' }, { status: 400 }),
      ),
    );
    const { user, onClose } = setup();
    const d = await dialog();
    await user.type(d.getByLabelText(/^Name/), 'ops');
    await user.type(d.getByLabelText(/^Webhook URL/), 'https://hooks.slack.com/services/x');
    await user.click(d.getByRole('button', { name: 'Add channel' }));

    expect(await d.findByText('is not a reachable webhook')).toBeInTheDocument();
    expect(d.getByLabelText(/^Webhook URL/)).toHaveFocus();
    expect(onClose).not.toHaveBeenCalled();
  });

  it('shows any other rejection as a not-saved alert', async () => {
    server.use(
      http.post('*/api/v1/channels', () =>
        HttpResponse.json({ title: 'Conflict', detail: 'A channel named ops already exists.' }, { status: 409 }),
      ),
    );
    const { user } = setup();
    const d = await dialog();
    await user.type(d.getByLabelText(/^Name/), 'ops');
    await user.type(d.getByLabelText(/^Webhook URL/), 'https://hooks.slack.com/services/x');
    await user.click(d.getByRole('button', { name: 'Add channel' }));

    const alert = await d.findByRole('alert');
    expect(alert).toHaveTextContent('This conflicts with the current state');
    expect(alert).toHaveTextContent('A channel named ops already exists.');
  });

  it('saves a disabled channel when the switch is turned off', async () => {
    const seen = capture('post', '*/api/v1/channels', channel({ kind: 'SLACK' }), 201);
    const { user } = setup();
    const d = await dialog();
    await user.click(d.getByRole('switch', { name: 'Enabled — bound rules deliver here' }));
    expect(d.getByRole('switch', { name: 'Disabled — bound rules skip this channel' })).not.toBeChecked();

    await user.type(d.getByLabelText(/^Name/), 'quiet');
    await user.type(d.getByLabelText(/^Webhook URL/), 'https://hooks.slack.com/services/x');
    await user.click(d.getByRole('button', { name: 'Add channel' }));
    expect(await screen.findByText(/^Added channel/)).toBeInTheDocument();
    expect(seen.body).toMatchObject({ enabled: false, secret: 'https://hooks.slack.com/services/x', config: '{}' });
  });

  it('closes without saving on Cancel', async () => {
    const { user, onClose } = setup();
    const d = await dialog();
    await user.click(d.getByRole('button', { name: 'Cancel' }));
    expect(onClose).toHaveBeenCalled();
  });
});

describe('ChannelEditor: editing', () => {
  it('shows the kind as fixed text and fills the form from the channel without its secret', async () => {
    setup(channel());
    const d = await dialog();
    expect(screen.getByRole('dialog', { name: 'Edit ops-hook' })).toBeInTheDocument();
    expect(d.queryByRole('combobox', { name: /^Kind/ })).not.toBeInTheDocument();
    expect(d.getByText(/a channel’s kind cannot change/)).toBeInTheDocument();
    expect(d.getByLabelText(/^Name/)).toHaveValue('ops-hook');
    expect(d.getByLabelText(/^Receiver URL/)).toHaveValue('https://alerts.example.com/hook');
    expect(d.getByLabelText(/^Signing secret/)).toHaveValue('');
    expect(d.getByLabelText(/^Signing secret/)).toHaveAttribute('placeholder', '•••••••• (stored — unchanged)');
    expect(d.getByText(/A secret is stored; leave blank to keep it/)).toBeInTheDocument();
  });

  it('saves without a secret, keeping the stored one', async () => {
    const seen = capture('put', '*/api/v1/channels/ch1');
    const { user, onClose } = setup(channel());
    const d = await dialog();
    await user.clear(d.getByLabelText(/^Name/));
    await user.type(d.getByLabelText(/^Name/), 'renamed');
    await user.click(d.getByRole('button', { name: 'Save' }));

    expect(await screen.findByText('Saved channel "renamed"')).toBeInTheDocument();
    expect(onClose).toHaveBeenCalled();
    expect(seen.body).toMatchObject({ name: 'renamed', kind: 'WEBHOOK', enabled: true });
    expect(seen.body?.secret).toBeUndefined();
  });

  it('requires a secret when the channel has none stored', async () => {
    const { user, onClose } = setup(channel({ hasSecret: false }));
    const d = await dialog();
    expect(d.queryByText(/A secret is stored/)).not.toBeInTheDocument();
    await user.click(d.getByRole('button', { name: 'Save' }));

    expect(await d.findByText('Signing secret is required.')).toBeInTheDocument();
    expect(onClose).not.toHaveBeenCalled();
  });

  it('starts an email channel from its stored config', async () => {
    setup(
      channel({
        kind: 'EMAIL',
        config: JSON.stringify({
          host: 'smtp.example.com',
          port: 465,
          security: 'TLS',
          username: 'bot',
          from: 'a@example.com',
          to: ['b@example.com', 'c@example.com'],
          subjectPrefix: '[Ops]',
        }),
        hasSecret: false,
      }),
    );
    const d = await dialog();
    expect(d.getByLabelText(/^SMTP server/)).toHaveValue('smtp.example.com');
    expect(d.getByLabelText(/^Port/)).toHaveValue('465');
    expect(d.getByRole('radio', { name: 'TLS' })).toBeChecked();
    expect(d.getByLabelText(/^Username/)).toHaveValue('bot');
    expect(d.getByLabelText(/^Recipients/)).toHaveValue('b@example.com, c@example.com');
    expect(d.getByLabelText(/^SMTP password/)).not.toBeRequired();
  });
});

describe('ChannelEditor: sending a test', () => {
  it('validates everything but the name first, then tests the unsaved configuration', async () => {
    const seen = capture('post', '*/api/v1/channels/test', { delivered: true, durationMs: 12, permanent: false });
    const { user } = setup();
    await chooseKind(user, 'Microsoft Teams');
    const d = await dialog();

    await user.click(d.getByRole('button', { name: 'Send a test' }));
    expect(await d.findByText('Workflow webhook URL is required.')).toBeInTheDocument();
    expect(d.queryByText('A name is required.')).not.toBeInTheDocument();
    expect(seen.body).toBeUndefined();

    await user.type(d.getByLabelText(/^Workflow webhook URL/), 'https://example.logic.azure.com/x');
    await user.click(d.getByRole('button', { name: 'Send a test' }));
    expect(await d.findByText('Test delivered in 12 ms')).toBeInTheDocument();
    expect(d.getByText(/Test notification from Rahdar/)).toBeInTheDocument();
    expect(seen.body).toMatchObject({
      channelId: null,
      kind: 'TEAMS',
      config: '{}',
      secret: 'https://example.logic.azure.com/x',
    });
  });

  it('tests a saved channel by its id and uses the stored secret', async () => {
    const seen = capture('post', '*/api/v1/channels/test', { delivered: true, durationMs: 5, permanent: false });
    const { user } = setup(channel());
    const d = await dialog();
    await user.click(d.getByRole('button', { name: 'Send a test' }));

    expect(await d.findByText('Test delivered in 5 ms')).toBeInTheDocument();
    expect(seen.body).toMatchObject({ channelId: 'ch1', kind: 'WEBHOOK' });
    expect(seen.body?.secret).toBeUndefined();
  });

  it('forgets a result once the form changes', async () => {
    capture('post', '*/api/v1/channels/test', { delivered: true, durationMs: 5, permanent: false });
    const { user } = setup(channel());
    const d = await dialog();
    await user.click(d.getByRole('button', { name: 'Send a test' }));
    await d.findByText('Test delivered in 5 ms');

    await user.type(d.getByLabelText(/^Signing secret/), 'x');
    expect(d.queryByText('Test delivered in 5 ms')).not.toBeInTheDocument();
  });

  it('shows a rejected test as a not-saved alert', async () => {
    server.use(
      http.post('*/api/v1/channels/test', () =>
        HttpResponse.json({ title: 'Bad', detail: 'The receiver could not be resolved.' }, { status: 502 }),
      ),
    );
    const { user } = setup(channel());
    const d = await dialog();
    await user.click(d.getByRole('button', { name: 'Send a test' }));

    expect(await d.findByRole('alert')).toHaveTextContent('The receiver could not be resolved.');
  });
});

describe('TestOutcome', () => {
  it('says a PagerDuty test opens and resolves an incident', () => {
    renderWithProviders(<TestOutcome kind="PAGERDUTY" result={{ delivered: true, durationMs: 9, permanent: false }} />);
    expect(screen.getByText('Test delivered in 9 ms')).toBeInTheDocument();
    expect(screen.getByText(/accepted a test incident and its resolution/)).toBeInTheDocument();
  });

  it('gives a temporary failure a retry note and a missing reason a default', () => {
    renderWithProviders(<TestOutcome kind="SLACK" result={{ delivered: false, durationMs: 9, permanent: false }} />);
    expect(screen.getByText('The receiver gave no reason.')).toBeInTheDocument();
    expect(screen.getByText(/This may be temporary/)).toBeInTheDocument();
  });

  it('tells a permanent failure that retrying will not help', () => {
    renderWithProviders(
      <TestOutcome kind="SLACK" result={{ delivered: false, durationMs: 9, permanent: true, error: 'HTTP 404' }} />,
    );
    expect(screen.getByText('HTTP 404')).toBeInTheDocument();
    expect(screen.getByText(/Retrying will not help/)).toBeInTheDocument();
  });
});
