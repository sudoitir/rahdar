import { beforeEach, describe, expect, it, vi } from 'vitest';
import { http, HttpResponse } from 'msw';
import { Notifications } from '@mantine/notifications';
import { screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

import { renderWithProviders } from '../../test/render.tsx';
import { server } from '../../test/setup.ts';
import type { ConnectionCheck, RegisterClusterRequest } from './api.ts';

const navigateSpy = vi.fn();
vi.mock('@tanstack/react-router', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@tanstack/react-router')>()),
  useNavigate: () => navigateSpy,
  Link: ({ children, to, ...rest }: { children: React.ReactNode; to: string }) => (
    <a href={to} {...rest}>
      {children}
    </a>
  ),
}));

const { KafkaRegistrationForm } = await import('./KafkaRegistrationForm.tsx');

function check(over: Partial<ConnectionCheck> = {}): ConnectionCheck {
  return {
    system: 'kafka',
    version: { text: 'at least 3.5', mode: 'ZooKeeper', exact: false },
    supported: true,
    clusterId: 'Mk3OEYBSD34fcwNTJENDM2Qk',
    alreadyRegistered: null,
    nodes: [
      { id: '1', address: 'k1:9092', functions: ['BROKER'], outcome: 'OK', cause: null, message: null },
      { id: '2', address: 'k2:9092', functions: ['BROKER'], outcome: 'OK', cause: null, message: null },
    ],
    quorum: { available: false, reason: 'the cluster runs ZooKeeper' },
    access: [
      { capability: 'Describe the cluster', status: 'AVAILABLE', reason: 'The account may describe the cluster.' },
      {
        capability: 'Read consumer group offsets',
        status: 'UNAVAILABLE',
        reason: 'The account may not describe groups',
        remedy: {
          text: 'Grant Describe on groups',
          snippet: 'kafka-acls.sh --add --operation Describe --group *',
          mediaType: 'text/x-shellscript',
        },
      },
    ],
    failure: null,
    contributions: {},
    ...over,
  };
}

let bodies: RegisterClusterRequest[];
function answer(dryRun: ConnectionCheck) {
  server.use(
    http.post('*/api/v1/clusters', async ({ request }) => {
      bodies.push((await request.json()) as RegisterClusterRequest);
      return new URL(request.url).searchParams.get('dryRun') === 'true'
        ? HttpResponse.json(dryRun)
        : HttpResponse.json({ id: 'c9', name: 'events' }, { status: 201 });
    }),
  );
}

beforeEach(() => {
  bodies = [];
  navigateSpy.mockClear();
});

async function fillAndCheck(user: ReturnType<typeof userEvent.setup>, servers = 'k1:9092') {
  await user.type(screen.getByRole('textbox', { name: /Bootstrap servers/ }), servers);
  await user.click(screen.getByRole('button', { name: 'Check connection' }));
}

describe('KafkaRegistrationForm', () => {
  it('shows every server typed in one field as its own entry, the port defaulted', async () => {
    const user = userEvent.setup();
    renderWithProviders(<KafkaRegistrationForm />);
    await user.type(screen.getByRole('textbox', { name: /Bootstrap servers/ }), 'k1:9092, k2{enter}k3:9093');
    const entries = screen.getByRole('group', { name: 'Servers entered' });
    expect(
      within(entries)
        .getAllByText(/^k\d:\d+$/)
        .map((e) => e.textContent),
    ).toEqual(['k1:9092', 'k2:9092', 'k3:9093']);
  });

  it('refuses a server written with a scheme, beside its field, and sends nothing', async () => {
    const user = userEvent.setup();
    answer(check());
    renderWithProviders(<KafkaRegistrationForm />);
    await fillAndCheck(user, 'PLAINTEXT://k1:9092');
    expect(await screen.findByText(/Remove the scheme/)).toBeInTheDocument();
    expect(bodies).toHaveLength(0);
    expect(screen.getByRole('textbox', { name: /Bootstrap servers/ })).toHaveFocus();
  });

  it('asks for a SASL mechanism only on a SASL protocol, and states that host name verification is on over TLS', async () => {
    const user = userEvent.setup();
    renderWithProviders(<KafkaRegistrationForm />);
    expect(screen.queryByRole('combobox', { name: 'SASL mechanism' })).not.toBeInTheDocument();
    await user.click(screen.getByRole('combobox', { name: 'Security protocol' }));
    await user.click(await screen.findByRole('option', { name: 'SASL over TLS' }));
    expect(screen.getByRole('combobox', { name: 'SASL mechanism' })).toBeInTheDocument();
    expect(screen.getByLabelText('TLS bundle')).toBeInTheDocument();
    expect(screen.getByText(/Host name verification is always on/)).toBeInTheDocument();
  });

  it('warns about SASL over plaintext and still lets the check run', async () => {
    const user = userEvent.setup();
    answer(check());
    renderWithProviders(<KafkaRegistrationForm />);
    await user.click(screen.getByRole('combobox', { name: 'Security protocol' }));
    await user.click(await screen.findByRole('option', { name: 'SASL over plaintext' }));
    expect(screen.getByText('The credentials travel unencrypted')).toBeInTheDocument();
    await user.click(screen.getByRole('combobox', { name: 'SASL mechanism' }));
    await user.click(await screen.findByRole('option', { name: 'SCRAM-SHA-512' }));
    await user.type(screen.getByRole('textbox', { name: /Bootstrap servers/ }), 'k1');
    await user.type(screen.getByLabelText('Username'), 'u');
    await user.type(screen.getByLabelText('Password'), 'p');
    await user.click(screen.getByRole('button', { name: 'Check connection' }));
    await screen.findByText(/Apache Kafka at least 3\.5/);
    expect(bodies[0]?.connection).toMatchObject({
      system: 'kafka',
      bootstrapServers: ['k1:9092'],
      securityProtocol: 'SASL_PLAINTEXT',
      saslMechanism: 'SCRAM-SHA-512',
      credentials: { username: 'u', password: 'p' },
    });
  });

  async function chooseScram(user: ReturnType<typeof userEvent.setup>) {
    await user.click(screen.getByRole('combobox', { name: 'Security protocol' }));
    await user.click(await screen.findByRole('option', { name: 'SASL over TLS' }));
    await user.click(screen.getByRole('combobox', { name: 'SASL mechanism' }));
    await user.click(await screen.findByRole('option', { name: 'PLAIN' }));
  }

  it('shows no message on a complete pair, and puts it on the member that is missing when one is cleared', async () => {
    const user = userEvent.setup();
    renderWithProviders(<KafkaRegistrationForm />);
    await chooseScram(user);
    const username = screen.getByLabelText('Username');
    const password = screen.getByLabelText('Password');

    await user.type(username, 'alice');
    await user.tab();
    // Leaving the username is not yet a mistake: the password it belongs with has not been reached.
    expect(screen.queryByText(/for this (username|password)/)).not.toBeInTheDocument();
    await user.type(password, 'secret');
    await user.tab();
    expect(screen.queryByText(/for this (username|password)/)).not.toBeInTheDocument();

    await user.clear(password);
    await user.tab();
    expect(await screen.findByText('Enter the password for this username, or clear the username.')).toBeInTheDocument();
    await user.type(password, 'again');
    expect(screen.queryByText(/for this (username|password)/)).not.toBeInTheDocument();

    await user.clear(username);
    await user.tab();
    expect(await screen.findByText('Enter the username for this password, or clear the password.')).toBeInTheDocument();
    await user.type(username, 'bob');
    expect(screen.queryByText(/for this (username|password)/)).not.toBeInTheDocument();
  });

  it('reports what the check found: version bound and mode, every broker, the quorum and what is lacking', async () => {
    const user = userEvent.setup();
    answer(check());
    renderWithProviders(<KafkaRegistrationForm />);
    await fillAndCheck(user);

    expect(await screen.findByText('Apache Kafka at least 3.5')).toBeInTheDocument();
    expect(screen.getByText('ZooKeeper')).toBeInTheDocument();
    expect(screen.getByText('Unavailable: the cluster runs ZooKeeper')).toBeInTheDocument();
    expect(screen.getAllByText('Reached')).toHaveLength(2);
    expect(screen.getByText('k2:9092')).toBeInTheDocument();
    expect(screen.getByText(/Read consumer group offsets/)).toBeInTheDocument();
    expect(screen.getByText(/The account may not describe groups/)).toBeInTheDocument();
    expect(screen.getByText('Grant Describe on groups')).toBeInTheDocument();
    expect(screen.getByText(/kafka-acls\.sh --add --operation Describe/)).toBeInTheDocument();
  });

  it('keeps Register disabled, with its reason, until a check passed on these exact inputs', async () => {
    const user = userEvent.setup();
    answer(check());
    renderWithProviders(<KafkaRegistrationForm />);
    expect(screen.getByRole('button', { name: 'Register cluster' })).toBeDisabled();
    expect(screen.getByText('Fill in the fields marked above, then check the connection.')).toBeInTheDocument();

    await fillAndCheck(user);
    await screen.findByText('Apache Kafka at least 3.5');
    expect(screen.getByRole('button', { name: 'Register cluster' })).toBeEnabled();

    await user.type(screen.getByRole('textbox', { name: /Bootstrap servers/ }), ' k9');
    expect(screen.getByRole('button', { name: 'Register cluster' })).toBeDisabled();
    expect(
      screen.getByText('Check the connection again: the details changed since the last check.'),
    ).toBeInTheDocument();
  });

  it.each([
    ['DNS', 'The name does not resolve'],
    ['REFUSED', 'The connection was refused'],
    ['TIMEOUT', 'The connection timed out'],
    ['TLS', 'The certificate is not trusted, or does not match the host name'],
    ['AUTHENTICATION', 'Authentication failed'],
    ['AUTHORIZATION', 'The account may not describe the cluster'],
    ['UNSUPPORTED_VERSION', 'This version is not supported'],
  ] as const)('names the cause %s and the next action, and keeps Register disabled', async (cause, title) => {
    const user = userEvent.setup();
    answer(check({ failure: { cause, message: 'It went wrong here.', next: 'Do this next.' }, nodes: [] }));
    renderWithProviders(<KafkaRegistrationForm />);
    await fillAndCheck(user);

    expect(await screen.findByText(title)).toBeInTheDocument();
    expect(screen.getByText('It went wrong here.')).toBeInTheDocument();
    expect(screen.getByText('Next: Do this next.')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Register cluster' })).toBeDisabled();
  });

  it('names a broker whose advertised address cannot be reached and still lets the operator register', async () => {
    const user = userEvent.setup();
    answer(
      check({
        nodes: [
          { id: '1', address: 'k1:9092', functions: ['BROKER'], outcome: 'OK', cause: null, message: null },
          {
            id: '2',
            address: 'internal-k2:9092',
            functions: ['BROKER'],
            outcome: 'UNREACHABLE',
            cause: 'DNS',
            message: 'unknown host',
          },
        ],
      }),
    );
    renderWithProviders(<KafkaRegistrationForm />);
    await fillAndCheck(user);

    expect(await screen.findByText('Some nodes were not reached')).toBeInTheDocument();
    expect(screen.getAllByText(/Broker 2 \(internal-k2:9092\)/).length).toBeGreaterThan(0);
    expect(screen.getByText(/advertised listeners must be reachable from Rahdar/)).toBeInTheDocument();
    expect(screen.getByText('1 broker was not reached, so the cluster will be shown degraded.')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Register cluster' })).toBeEnabled();
  });

  it('links to the cluster that is registered already and refuses to register it again', async () => {
    const user = userEvent.setup();
    answer(check({ alreadyRegistered: { id: 'c1', name: 'prod-events' } }));
    renderWithProviders(<KafkaRegistrationForm />);
    await fillAndCheck(user);

    expect(await screen.findByText('This cluster is already registered')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Open prod-events' })).toHaveAttribute('href', '/clusters/c1');
    expect(screen.getByRole('button', { name: 'Register cluster' })).toBeDisabled();
    expect(
      screen.getByText('This cluster is registered already, so there is nothing to register.'),
    ).toBeInTheDocument();
  });

  it('registers a checked cluster, announces it and opens its topology', async () => {
    const user = userEvent.setup();
    answer(check());
    renderWithProviders(
      <>
        <Notifications />
        <KafkaRegistrationForm />
      </>,
    );
    await fillAndCheck(user, 'k1:9092 k2:9092');
    await screen.findByText('Apache Kafka at least 3.5');
    await user.type(screen.getByLabelText('Name'), 'events');
    await user.click(screen.getByRole('button', { name: 'Register cluster' }));

    expect(await screen.findByText('Registered cluster events')).toBeInTheDocument();
    expect(navigateSpy).toHaveBeenCalledWith({ to: '/clusters/c9/topology' });
    expect(bodies.at(-1)).toMatchObject({
      system: 'kafka',
      name: 'events',
      connection: { system: 'kafka', bootstrapServers: ['k1:9092', 'k2:9092'], securityProtocol: 'PLAINTEXT' },
    });
  });
});
