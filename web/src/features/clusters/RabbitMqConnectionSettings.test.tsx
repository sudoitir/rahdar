import { afterEach, describe, expect, it } from 'vitest';
import { http, HttpResponse } from 'msw';
import { Notifications, notifications } from '@mantine/notifications';
import { act, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

import { holdButton } from '../../test/hold.ts';
import { renderWithProviders } from '../../test/render.tsx';
import { server } from '../../test/setup.ts';
import { RABBITMQ_SYSTEM } from '../../test/wire.ts';
import { ConnectionSection } from './ClusterSettings.tsx';

const CLUSTER = '33333333-3333-3333-3333-333333333333';

const CHECK = {
  system: 'rabbitmq',
  version: { text: '4.1.0', exact: true },
  supported: true,
  clusterId: 'rabbit-prod',
  alreadyRegistered: { id: CLUSTER, name: 'events' },
  nodes: [{ id: 'rabbit@r1', address: 'r1:5672', functions: ['BROKER'], outcome: 'OK', cause: null, message: null }],
  access: [],
  failure: null,
  endpoints: [{ plane: 'MANAGEMENT', address: 'http://r1:15672', outcome: 'OK' }],
};

function rabbitCluster() {
  server.use(
    http.get('*/api/v1/clusters/:id', () =>
      HttpResponse.json({
        id: CLUSTER,
        name: 'events',
        description: null,
        system: RABBITMQ_SYSTEM,
        connection: {
          seedUrls: [],
          rabbitmq: {
            managementUrls: ['http://r1:15672'],
            amqpEndpoints: [],
            username: 'guest',
            tokenAuth: false,
            secretStored: true,
            tlsBundle: null,
            eventVhost: '/',
            watchIntervalSeconds: 10,
          },
        },
      }),
    ),
  );
  const sent: { dryRun: boolean; body: { connection: Record<string, unknown> } }[] = [];
  server.use(
    http.patch('*/api/v1/clusters/:id', async ({ request }) => {
      const dryRun = new URL(request.url).searchParams.get('dryRun') === 'true';
      sent.push({ dryRun, body: (await request.json()) as never });
      return dryRun ? HttpResponse.json(CHECK) : HttpResponse.json({ id: CLUSTER, name: 'events', connection: {} });
    }),
  );
  return sent;
}

function open() {
  const user = userEvent.setup();
  renderWithProviders(
    <>
      <Notifications />
      <ConnectionSection clusterId={CLUSTER} />
    </>,
  );
  return user;
}

afterEach(() => act(() => notifications.clean()));

describe('ConnectionSection of a RabbitMQ cluster', () => {
  it('shows the stored connection with the RabbitMQ fields and never a secret', async () => {
    rabbitCluster();
    open();

    expect(await screen.findByRole('textbox', { name: /Management URLs/ })).toHaveValue('http://r1:15672');
    expect(screen.getByLabelText('User name')).toHaveValue('guest');
    expect(screen.getByLabelText('Password')).toHaveValue('');
    expect(screen.getByText(/the management URLs, the AMQP endpoints/)).toBeInTheDocument();
    expect(screen.queryByRole('textbox', { name: /Bootstrap servers/ })).not.toBeInTheDocument();
    expect(screen.queryByLabelText(/Broker management URL/)).not.toBeInTheDocument();
  });

  it('asks for the password again when a management URL is added, and says why', async () => {
    rabbitCluster();
    const user = open();
    const urls = await screen.findByRole('textbox', { name: /Management URLs/ });

    await user.type(urls, ' http://r2');
    await user.click(screen.getByRole('button', { name: 'Check connection' }));

    expect(await screen.findByText('Enter the password again: the connection changed.')).toBeInTheDocument();
  });

  it('checks a rotated password, then saves it with the secret and no stored one in the request', async () => {
    const sent = rabbitCluster();
    const user = open();
    await user.type(await screen.findByLabelText('Password'), 'new-secret');
    expect(screen.getByRole('button', { name: 'Save connection' })).toBeDisabled();
    expect(screen.getByText('Check the connection before saving.')).toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: 'Check connection' }));
    await screen.findByText('RabbitMQ 4.1.0');
    await user.click(screen.getByRole('button', { name: 'Save connection' }));
    const dialog = await screen.findByRole('dialog', { name: 'Save the connection' });
    await holdButton(within(dialog).getByRole('button', { name: 'Save connection' }));

    await waitFor(() => expect(sent.map((s) => s.dryRun)).toEqual([true, false]));
    expect(sent[1].body.connection).toMatchObject({
      system: 'rabbitmq',
      managementUrls: ['http://r1:15672'],
      credentials: { username: 'guest', password: 'new-secret' },
    });
  });

  it('saves a renamed cluster without the secret, which the server keeps, and without a confirmation', async () => {
    const sent = rabbitCluster();
    const user = open();
    const name = await screen.findByLabelText('Name');
    await user.clear(name);
    await user.type(name, 'events-2');
    await user.click(screen.getByRole('button', { name: 'Check connection' }));
    await screen.findByText('RabbitMQ 4.1.0');
    await user.click(screen.getByRole('button', { name: 'Save connection' }));

    await waitFor(() => expect(sent.map((s) => s.dryRun)).toEqual([true, false]));
    expect(sent[1].body.connection).toEqual({
      system: 'rabbitmq',
      managementUrls: ['http://r1:15672'],
      amqpEndpoints: [],
      credentials: { username: 'guest' },
      tlsBundle: '',
      eventVhost: '/',
      watchIntervalSeconds: 10,
    });
  });
});
