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

const { RabbitMqRegistrationForm } = await import('./RabbitMqRegistrationForm.tsx');

function check(over: Partial<ConnectionCheck> = {}): ConnectionCheck {
  return {
    system: 'rabbitmq',
    version: { text: '4.1.0', mode: undefined, exact: true },
    supported: true,
    clusterId: 'rabbit-prod',
    alreadyRegistered: null,
    nodes: [
      { id: 'rabbit@r1', address: 'r1:5672', functions: ['BROKER'], outcome: 'OK', cause: null, message: null },
      { id: 'rabbit@r2', address: 'r2:5672', functions: ['BROKER'], outcome: 'OK', cause: null, message: null },
    ],
    quorum: null,
    access: [],
    failure: null,
    contributions: {},
    endpoints: [
      { plane: 'MANAGEMENT', address: 'http://r1:15672', outcome: 'OK' },
      { plane: 'DATA', address: 'amqp://r1:5672', outcome: 'OK' },
    ],
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
        : HttpResponse.json({ id: 'c9', name: 'r1' }, { status: 201 });
    }),
  );
}

beforeEach(() => {
  bodies = [];
  navigateSpy.mockClear();
});

async function fillAndCheck(user: ReturnType<typeof userEvent.setup>, urls = 'http://r1') {
  await user.type(screen.getByRole('textbox', { name: /Management URLs/ }), urls);
  await user.type(screen.getByLabelText('User name'), 'guest');
  await user.type(screen.getByLabelText('Password'), 'secret');
  await user.click(screen.getByRole('button', { name: 'Check connection' }));
}

describe('RabbitMqRegistrationForm', () => {
  it('shows every URL typed as its own entry, the port defaulted, and no bootstrap wording', async () => {
    const user = userEvent.setup();
    const { container } = renderWithProviders(<RabbitMqRegistrationForm />);
    expect(container.querySelector('[class*="serverPills"]')).not.toBeNull();
    await user.type(
      screen.getByRole('textbox', { name: /Management URLs/ }),
      'http://r1, https://r2{enter}http://r3:8080/x',
    );
    const entries = screen.getByRole('group', { name: 'Management URLs entered' });
    expect(
      within(entries)
        .getAllByText(/^https?:/)
        .map((e) => e.textContent),
    ).toEqual(['http://r1:15672', 'https://r2:15671', 'http://r3:8080/x']);
    expect(document.body.textContent).not.toMatch(/[Bb]ootstrap/);
  });

  it('refuses a URL with an account, beside its field, and sends nothing', async () => {
    const user = userEvent.setup();
    answer(check());
    renderWithProviders(<RabbitMqRegistrationForm />);
    await fillAndCheck(user, 'http://guest:pw@r1');
    expect(await screen.findByText(/Remove the account from http:\/\/guest:pw@r1/)).toBeInTheDocument();
    expect(bodies).toHaveLength(0);
    expect(screen.getByRole('textbox', { name: /Management URLs/ })).toHaveFocus();
  });

  it('asks for a token instead of the account when the credential is a token, and warns about plain http', async () => {
    const user = userEvent.setup();
    renderWithProviders(<RabbitMqRegistrationForm />);
    await user.type(screen.getByRole('textbox', { name: /Management URLs/ }), 'http://r1');
    expect(screen.getByText('The credentials travel unencrypted')).toBeInTheDocument();
    expect(screen.queryByLabelText('TLS bundle')).not.toBeInTheDocument();
    await user.click(screen.getByRole('combobox', { name: 'Credentials' }));
    await user.click(await screen.findByRole('option', { name: 'Bearer token' }));
    expect(screen.getByLabelText('Token')).toBeInTheDocument();
    expect(screen.queryByLabelText('User name')).not.toBeInTheDocument();
  });

  it('offers the TLS bundle only for an encrypted address', async () => {
    const user = userEvent.setup();
    renderWithProviders(<RabbitMqRegistrationForm />);
    await user.type(screen.getByRole('textbox', { name: /Management URLs/ }), 'https://r1');
    expect(screen.getByLabelText('TLS bundle')).toBeInTheDocument();
    expect(screen.getByText(/Host name verification is always on/)).toBeInTheDocument();
  });

  it('puts the missing member of the account pair on its field', async () => {
    const user = userEvent.setup();
    renderWithProviders(<RabbitMqRegistrationForm />);
    await user.type(screen.getByLabelText('User name'), 'alice');
    await user.tab();
    expect(screen.queryByText(/for this (user name|password)/)).not.toBeInTheDocument();
    await user.type(screen.getByLabelText('Password'), 'x');
    await user.clear(screen.getByLabelText('Password'));
    await user.tab();
    expect(
      await screen.findByText('Enter the password for this user name, or clear the user name.'),
    ).toBeInTheDocument();
  });

  it('reports what the check found: version, cluster name, every node and every endpoint', async () => {
    const user = userEvent.setup();
    answer(check());
    renderWithProviders(<RabbitMqRegistrationForm />);
    await fillAndCheck(user);

    expect(await screen.findByText('RabbitMQ 4.1.0')).toBeInTheDocument();
    expect(screen.getByText('Cluster name')).toBeInTheDocument();
    expect(screen.queryByText('Cluster id')).not.toBeInTheDocument();
    expect(screen.getByText('rabbit@r1')).toBeInTheDocument();
    expect(screen.queryByText(/Broker /)).not.toBeInTheDocument();
    const endpoints = screen.getByRole('table', { name: 'Endpoints tried by the check' });
    expect(within(endpoints).getByText('http://r1:15672')).toBeInTheDocument();
    expect(within(endpoints).getByText('amqp://r1:5672')).toBeInTheDocument();
    expect(within(endpoints).getAllByText('Reached')).toHaveLength(2);
    expect(bodies[0]?.connection).toMatchObject({
      system: 'rabbitmq',
      managementUrls: ['http://r1:15672'],
      credentials: { username: 'guest', password: 'secret' },
    });
  });

  it('names an endpoint that was not reached while the others answered, and still lets the operator register', async () => {
    const user = userEvent.setup();
    answer(
      check({
        endpoints: [
          { plane: 'MANAGEMENT', address: 'http://r1:15672', outcome: 'OK' },
          {
            plane: 'MANAGEMENT',
            address: 'http://r2:15672',
            outcome: 'UNREACHABLE',
            cause: 'TIMEOUT',
            message: 'no reply',
          },
          { plane: 'DATA', address: 'amqp://r1:5672', outcome: 'OK' },
        ],
        nodes: [
          { id: 'rabbit@r1', address: 'r1:5672', functions: ['BROKER'], outcome: 'OK', cause: null, message: null },
          {
            id: 'rabbit@r2',
            address: 'r2:5672',
            functions: ['BROKER'],
            outcome: 'UNREACHABLE',
            cause: 'TIMEOUT',
            message: null,
          },
        ],
      }),
    );
    renderWithProviders(<RabbitMqRegistrationForm />);
    await fillAndCheck(user, 'http://r1 http://r2');

    expect(await screen.findByText('Some endpoints were not reached')).toBeInTheDocument();
    expect(screen.getByText('The connection timed out: no reply')).toBeInTheDocument();
    expect(screen.getByText('Some nodes were not reached')).toBeInTheDocument();
    expect(document.body.textContent).not.toMatch(/Kafka|advertised listeners/);
    expect(screen.getByText('1 node was not reached, so the cluster will be shown degraded.')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Register cluster' })).toBeEnabled();
  });

  it.each([
    ['WRONG_ENDPOINT', 'A management URL does not answer as a RabbitMQ management API'],
    ['DATA_UNREACHABLE', 'No AMQP endpoint accepts a connection'],
    ['AUTHENTICATION', 'Authentication failed'],
  ] as const)('names the cause %s and the next action, and keeps Register disabled', async (cause, title) => {
    const user = userEvent.setup();
    answer(
      check({
        failure: { cause, message: 'It went wrong here.', next: 'Do this next.' },
        nodes: [],
        endpoints: [{ plane: 'DATA', address: 'amqp://r1:5672', outcome: 'UNREACHABLE', cause: 'REFUSED' }],
      }),
    );
    renderWithProviders(<RabbitMqRegistrationForm />);
    await fillAndCheck(user);

    expect(await screen.findByText(title)).toBeInTheDocument();
    expect(screen.getByText('It went wrong here.')).toBeInTheDocument();
    expect(screen.getByText('Next: Do this next.')).toBeInTheDocument();
    expect(screen.getByText('The connection was refused')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Register cluster' })).toBeDisabled();
  });

  it('keeps Register disabled until a check passed on these exact inputs', async () => {
    const user = userEvent.setup();
    answer(check());
    renderWithProviders(<RabbitMqRegistrationForm />);
    expect(screen.getByRole('button', { name: 'Register cluster' })).toBeDisabled();
    expect(screen.getByText('Fill in the fields marked above, then check the connection.')).toBeInTheDocument();
    await fillAndCheck(user);
    await screen.findByText('RabbitMQ 4.1.0');
    expect(screen.getByRole('button', { name: 'Register cluster' })).toBeEnabled();
    await user.type(screen.getByRole('textbox', { name: /Management URLs/ }), ' http://r9');
    expect(screen.getByRole('button', { name: 'Register cluster' })).toBeDisabled();
    expect(
      screen.getByText('Check the connection again: the details changed since the last check.'),
    ).toBeInTheDocument();
  });

  it('registers a checked cluster, announces it and opens it', async () => {
    const user = userEvent.setup();
    answer(check());
    renderWithProviders(
      <>
        <Notifications />
        <RabbitMqRegistrationForm />
      </>,
    );
    await fillAndCheck(user);
    await screen.findByText('RabbitMQ 4.1.0');
    await user.type(screen.getByLabelText('Name'), 'events');
    await user.click(screen.getByRole('button', { name: 'Register cluster' }));

    expect(await screen.findByText('Registered cluster r1')).toBeInTheDocument();
    expect(navigateSpy).toHaveBeenCalledWith({ to: '/clusters/c9' });
    expect(bodies.at(-1)).toMatchObject({
      system: 'rabbitmq',
      name: 'events',
      connection: { system: 'rabbitmq', managementUrls: ['http://r1:15672'] },
    });
  });
});
