import { afterEach, describe, expect, it } from 'vitest';
import { http, HttpResponse } from 'msw';
import { Notifications, notifications } from '@mantine/notifications';
import { act, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

import { renderWithProviders } from '../../test/render.tsx';
import { server } from '../../test/setup.ts';
import { ARTEMIS_SYSTEM } from '../../test/wire.ts';
import { CapabilitiesSection, ConnectionSection } from './ClusterSettings.tsx';
import { holdButton } from '../../test/hold.ts';

const CLUSTER = '11111111-1111-1111-1111-111111111111';

interface Sent {
  dryRun: boolean;
  body: Record<string, unknown>;
}

function cluster(connection: object | null = {}) {
  server.use(
    http.get('*/api/v1/clusters/:id', () =>
      HttpResponse.json({
        id: CLUSTER,
        name: 'prod-eu',
        description: null,
        system: ARTEMIS_SYSTEM,
        connection:
          connection === null
            ? null
            : {
                managementUrlPattern: 'http://{host}:8161/console/jolokia',
                seedUrls: ['http://broker-1:8161/console/jolokia'],
                tlsBundle: null,
                managementUsername: 'artemis',
                coreUsername: null,
                ...connection,
              },
      }),
    ),
  );
}

const ROWS = [
  {
    id: 'broker-1',
    address: 'http://broker-1:8161/console/jolokia',
    functions: [],
    outcome: 'OK',
    artemis: {
      nativeNodeId: 'n1',
      version: '2.40.0',
      haRole: 'PRIMARY',
      urlSource: 'SEED',
      management: 'ACCEPTED',
      core: 'ACCEPTED',
    },
  },
];

/** A node that answered, with the Core account as given. */
const withCore = (core: string) => [{ ...ROWS[0], artemis: { ...ROWS[0].artemis, core } }];

/** The PATCH endpoint: every call recorded, a dry run answering with `rows` and a save with the cluster. */
function patching(rows: object[] = ROWS) {
  const sent: Sent[] = [];
  server.use(
    http.patch('*/api/v1/clusters/:id', async ({ request }) => {
      const dryRun = new URL(request.url).searchParams.get('dryRun') === 'true';
      sent.push({ dryRun, body: (await request.json()) as Record<string, unknown> });
      return dryRun
        ? HttpResponse.json({
            system: 'artemis',
            version: { text: '2.40.0', exact: true },
            supported: true,
            nodes: rows,
            access: [],
            managementUrlPattern: 'http://{host}:8161/console/jolokia',
            contributions: {},
          })
        : HttpResponse.json({ id: CLUSTER, name: 'prod-eu', connection: {} });
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

describe('ConnectionSection', () => {
  it('holds its place while the cluster loads, and says why it cannot be read', async () => {
    server.use(
      http.get('*/api/v1/clusters/:id', () =>
        HttpResponse.json({ title: 'Error', detail: 'The cluster store is down.' }, { status: 500 }),
      ),
    );
    renderWithProviders(<ConnectionSection clusterId={CLUSTER} />);

    expect(screen.getByText('Loading the cluster')).toBeInTheDocument();
    expect(await screen.findByText('The cluster store is down.')).toBeInTheDocument();
  });

  it('shows how the cluster is connected now and never a password', async () => {
    cluster();
    open();

    expect(await screen.findByLabelText('Name')).toHaveValue('prod-eu');
    expect(screen.getByLabelText(/Broker management URL/)).toHaveValue('http://broker-1:8161/console/jolokia');
    expect(screen.getByLabelText('Username')).toHaveValue('artemis');
    expect(screen.getByLabelText('Password')).toHaveValue('');
    expect(screen.getByRole('switch', { name: /Use a separate Core account/ })).not.toBeChecked();
  });

  it('explains why saving is not offered until the connection has been checked', async () => {
    cluster();
    open();

    expect(await screen.findByRole('button', { name: 'Save connection' })).toBeDisabled();
    expect(screen.getByText('Check the connection before saving.')).toBeInTheDocument();
  });

  it('checks node by node first, saves nothing, then offers the save', async () => {
    cluster();
    const sent = patching();
    const user = open();

    await user.click(await screen.findByRole('button', { name: 'Check connection' }));

    const table = await screen.findByRole('table', { name: 'Nodes found by the check' });
    expect(within(table).getByRole('row', { name: /broker-1/ })).toHaveTextContent('Accepted');
    expect(sent).toHaveLength(1);
    expect(sent[0].dryRun).toBe(true);
    expect(screen.getByRole('button', { name: 'Save connection' })).toBeEnabled();
  });

  it('saves a change that touches no account without asking for the cluster name', async () => {
    cluster();
    const sent = patching();
    const user = open();

    const name = await screen.findByLabelText('Name');
    await user.clear(name);
    await user.type(name, 'prod-emea');
    await user.click(screen.getByRole('button', { name: 'Check connection' }));
    await screen.findByRole('table', { name: 'Nodes found by the check' });
    await user.click(screen.getByRole('button', { name: 'Save connection' }));

    await waitFor(() => expect(sent.map((s) => s.dryRun)).toEqual([true, false]));
    expect(sent[1].body.name).toBe('prod-emea');
    expect(screen.queryByRole('dialog')).toBeNull();
    expect(await screen.findByText('Saved the connection of prod-eu')).toBeInTheDocument();
  });

  it('asks for the cluster name before an account changes, and sends the new password', async () => {
    cluster();
    const sent = patching();
    const user = open();

    await user.type(await screen.findByLabelText('Password'), 'new-secret');
    await user.click(screen.getByRole('button', { name: 'Check connection' }));
    await screen.findByRole('table', { name: 'Nodes found by the check' });
    await user.click(screen.getByRole('button', { name: 'Save connection' }));

    const dialog = await screen.findByRole('dialog', { name: 'Save the connection' });
    const confirm = within(dialog).getByRole('button', { name: 'Save connection' });
    await holdButton(confirm);

    await waitFor(() => expect(sent.map((s) => s.dryRun)).toEqual([true, false]));
    expect((sent[1].body.connection as Record<string, unknown>).management).toEqual({
      username: 'artemis',
      password: 'new-secret',
    });
  });

  it('sends an empty password to keep the stored one, and a null Core account to fall back', async () => {
    cluster({ coreUsername: 'core-user' });
    const sent = patching();
    const user = open();

    await user.click(await screen.findByRole('switch', { name: /Use a separate Core account/ }));
    await user.click(screen.getByRole('button', { name: 'Check connection' }));
    await screen.findByRole('table', { name: 'Nodes found by the check' });

    expect((sent[0].body.connection as Record<string, unknown>).management).toEqual({
      username: 'artemis',
      password: '',
    });
    expect((sent[0].body.connection as Record<string, unknown>).core).toBeNull();
  });

  it('will not offer the save while a broker rejects an account, and says which', async () => {
    cluster();
    patching(withCore('REJECTED'));
    const user = open();

    await user.click(await screen.findByRole('button', { name: 'Check connection' }));

    expect(await screen.findByText('broker-1 rejected the Core account')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Save connection' })).toBeDisabled();
    expect(screen.getByText('A broker rejected an account. Correct it, then check again.')).toBeInTheDocument();
  });

  it('asks for a new check once a value changes after one', async () => {
    cluster();
    patching();
    const user = open();

    await user.click(await screen.findByRole('button', { name: 'Check connection' }));
    await screen.findByRole('table', { name: 'Nodes found by the check' });
    await user.type(screen.getByLabelText('Description'), 'EU production');

    expect(screen.getByRole('button', { name: 'Save connection' })).toBeDisabled();
    expect(await screen.findByText('Check again: the values changed since the last check.')).toBeInTheDocument();
  });

  it('asks for the password again when the pattern changes, and sends nothing until it is given', async () => {
    cluster();
    const sent = patching();
    const user = open();

    const pattern = await screen.findByLabelText('Management URL pattern');
    await user.clear(pattern);
    await user.type(pattern, 'http://{{host}:9161/jolokia');
    await user.click(screen.getByRole('button', { name: 'Check connection' }));

    expect(await screen.findByText('Enter the management password again to check new hosts.')).toBeInTheDocument();
    expect(screen.getByLabelText('Password')).toHaveFocus();
    expect(sent).toHaveLength(0);

    await user.type(screen.getByLabelText('Password'), 'old-secret');
    await user.click(screen.getByRole('button', { name: 'Check connection' }));
    await screen.findByRole('table', { name: 'Nodes found by the check' });
    expect((sent[0].body.connection as Record<string, unknown>).management).toEqual({
      username: 'artemis',
      password: 'old-secret',
    });
  });

  it('asks for the password again for a seed on a new host, but not for one the cluster already uses', async () => {
    cluster();
    patching();
    const user = open();

    await user.click(await screen.findByRole('button', { name: 'Add another seed' }));
    await user.type(screen.getByLabelText(/Another management URL/), 'http://other.example:8161/console/jolokia');
    await user.click(screen.getByRole('button', { name: 'Check connection' }));
    expect(await screen.findByText('Enter the management password again to check new hosts.')).toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: 'Remove management URL 2' }));
    await user.click(screen.getByRole('button', { name: 'Check connection' }));
    expect(await screen.findByRole('table', { name: 'Nodes found by the check' })).toBeInTheDocument();
  });

  it('asks for the Core password again when the Core account is renamed', async () => {
    cluster({ coreUsername: 'core-user' });
    patching();
    const user = open();

    const name = await screen.findByLabelText('Core username');
    await user.clear(name);
    await user.type(name, 'someone-else');
    await user.click(screen.getByRole('button', { name: 'Check connection' }));

    expect(await screen.findByText('Enter the Core password again to check new hosts.')).toBeInTheDocument();
  });

  it('shows the answer of the server when it refuses an edit', async () => {
    cluster();
    server.use(
      http.patch('*/api/v1/clusters/:id', () =>
        HttpResponse.json(
          { title: 'Bad Request', detail: 'Enter the management password again to check new hosts.' },
          { status: 400 },
        ),
      ),
    );
    const user = open();

    await user.click(await screen.findByRole('button', { name: 'Check connection' }));

    expect(await screen.findByText('Enter the management password again to check new hosts.')).toBeInTheDocument();
  });

  it('says the connection is not shown to someone who sees the cluster only through a team', async () => {
    cluster(null);
    open();

    expect(await screen.findByText(/only through a team/)).toBeInTheDocument();
  });
});

describe('CapabilitiesSection', () => {
  it('holds its place while the cluster loads', () => {
    server.use(http.get('*/api/v1/clusters/:id', () => new Promise(() => undefined)));
    renderWithProviders(<CapabilitiesSection clusterId={CLUSTER} />);

    expect(screen.getByText('Loading the cluster')).toBeInTheDocument();
  });
});
