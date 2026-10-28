import { afterEach, describe, expect, it } from 'vitest';
import { http, HttpResponse } from 'msw';
import { Notifications, notifications } from '@mantine/notifications';
import { act, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

import { holdButton } from '../../test/hold.ts';
import { renderWithProviders } from '../../test/render.tsx';
import { server } from '../../test/setup.ts';
import { KAFKA_SYSTEM } from '../../test/wire.ts';
import { CapabilitiesSection, ConnectionSection } from './ClusterSettings.tsx';

const CLUSTER = '22222222-2222-2222-2222-222222222222';

const CHECK = {
  system: 'kafka',
  version: { text: '3.7.0', mode: 'KRaft', exact: true },
  supported: true,
  clusterId: 'abc',
  alreadyRegistered: { id: CLUSTER, name: 'events' },
  nodes: [{ id: '1', address: 'k1:9092', functions: ['BROKER'], outcome: 'OK', cause: null, message: null }],
  quorum: { available: true, leader: '1', voters: 1 },
  access: [],
  failure: null,
};

function kafkaCluster() {
  server.use(
    http.get('*/api/v1/clusters/:id', () =>
      HttpResponse.json({
        id: CLUSTER,
        name: 'events',
        description: null,
        system: KAFKA_SYSTEM,
        connection: {
          system: 'kafka',
          bootstrapServers: ['k1:9092'],
          securityProtocol: 'SASL_SSL',
          saslMechanism: 'SCRAM-SHA-512',
          credentials: { username: 'rahdar' },
          tlsBundle: null,
          clientId: null,
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

describe('ConnectionSection of a Kafka cluster', () => {
  it('shows the stored connection with the Kafka fields and never a secret', async () => {
    kafkaCluster();
    open();

    expect(await screen.findByRole('textbox', { name: /Bootstrap servers/ })).toHaveValue('k1:9092');
    expect(screen.getByRole('combobox', { name: 'Security protocol' })).toHaveValue('SASL over TLS');
    expect(screen.getByLabelText('Username')).toHaveValue('rahdar');
    expect(screen.getByLabelText('Password')).toHaveValue('');
    expect(screen.queryByLabelText(/Broker management URL/)).not.toBeInTheDocument();
  });

  it('asks for the password again when a new server is added, and says why', async () => {
    kafkaCluster();
    const user = open();
    const servers = await screen.findByRole('textbox', { name: /Bootstrap servers/ });

    await user.type(servers, ' k2');
    await user.click(screen.getByRole('button', { name: 'Check connection' }));

    expect(await screen.findByText('Enter the password again: the connection changed.')).toBeInTheDocument();
  });

  it('checks a rotated password, then saves it with the secret and no stored one in the request', async () => {
    const sent = kafkaCluster();
    const user = open();
    await user.type(await screen.findByLabelText('Password'), 'new-secret');
    expect(screen.getByRole('button', { name: 'Save connection' })).toBeDisabled();
    expect(screen.getByText('Check the connection before saving.')).toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: 'Check connection' }));
    // The cluster being edited is not "already registered" to itself.
    await screen.findByText('Apache Kafka 3.7.0');
    await user.click(screen.getByRole('button', { name: 'Save connection' }));
    const dialog = await screen.findByRole('dialog', { name: 'Save the connection' });
    await holdButton(within(dialog).getByRole('button', { name: 'Save connection' }));

    await waitFor(() => expect(sent.map((s) => s.dryRun)).toEqual([true, false]));
    expect(sent[1].body.connection).toMatchObject({
      system: 'kafka',
      bootstrapServers: ['k1:9092'],
      credentials: { username: 'rahdar', password: 'new-secret' },
    });
  });
});

describe('CapabilitiesSection of a Kafka cluster', () => {
  it('speaks of the connection, not of Jolokia or broker.xml', async () => {
    kafkaCluster();
    server.use(
      http.get('*/api/v1/clusters/:id', () =>
        HttpResponse.json({
          id: CLUSTER,
          name: 'events',
          system: KAFKA_SYSTEM,
          capabilities: {
            managementRead: { status: 'AVAILABLE', reason: 'The account may describe the cluster.' },
            managementWrite: { status: 'AVAILABLE', reason: 'ok' },
            notifications: { status: 'UNAVAILABLE', reason: 'Kafka has no broker events.' },
            messageIo: { status: 'AVAILABLE', reason: 'ok' },
            slowConsumerDetection: { status: 'AVAILABLE', reason: 'ok' },
            versionGates: [],
          },
        }),
      ),
    );
    renderWithProviders(<CapabilitiesSection clusterId={CLUSTER} />);
    expect(await screen.findByText('Live events')).toBeInTheDocument();
    expect(document.body.textContent).not.toMatch(/Jolokia|broker\.xml/);
  });
});
