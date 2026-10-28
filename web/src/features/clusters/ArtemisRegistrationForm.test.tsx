import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { http, HttpResponse } from 'msw';
import { Notifications, notifications } from '@mantine/notifications';
import { act, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

import { renderWithProviders } from '../../test/render.tsx';
import { holding } from '../../test/access.ts';
import { server } from '../../test/setup.ts';
import { paged } from '../../kernel/api/paging.ts';

const navigateSpy = vi.fn();
vi.mock('@tanstack/react-router', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@tanstack/react-router')>()),
  useNavigate: () => navigateSpy,
  // A plain anchor that stays on the page: the router is not under test, and the document cannot navigate.
  Link: ({ children, to, onClick, ...rest }: { children: React.ReactNode; to: string; onClick?: () => void }) => (
    <a
      href={to}
      {...rest}
      onClick={(event) => {
        event.preventDefault();
        onClick?.();
      }}
    >
      {children}
    </a>
  ),
}));

const { ArtemisRegistrationForm } = await import('./ArtemisRegistrationForm.tsx');

function preview() {
  return {
    system: 'artemis',
    capabilities: {
      managementRead: { status: 'AVAILABLE', reason: 'ok', brokerXmlSnippet: null },
      managementWrite: { status: 'AVAILABLE', reason: 'ok', brokerXmlSnippet: null },
      notifications: { status: 'AVAILABLE', reason: 'ok', brokerXmlSnippet: null },
      messageIo: { status: 'AVAILABLE', reason: 'ok', brokerXmlSnippet: null },
      slowConsumerDetection: { status: 'UNKNOWN', reason: 'not exposed', brokerXmlSnippet: '<x/>' },
      versionGates: [],
    },
    reachableSeeds: 1,
    discoveredNodes: 2,
    managementUrlPattern: 'http://{host}:8161/console/jolokia',
    nodes: [
      {
        name: 'broker-1',
        haRole: 'PRIMARY',
        artemisNodeId: 'node-a',
        version: '2.40.0',
        managementUrl: 'http://broker-1:8161/console/jolokia',
        urlSource: 'SEED',
        urlProblem: null,
        management: 'ACCEPTED',
        core: 'REJECTED',
      },
      {
        name: 'broker-2',
        haRole: 'BACKUP',
        artemisNodeId: 'node-a',
        version: '2.40.0',
        managementUrl: null,
        urlSource: null,
        urlProblem: 'OTHER_BROKER',
        management: 'ACCEPTED',
        core: 'NOT_TRIED',
      },
    ],
    adoption: {
      counts: { addresses: 3, addressSettings: 2, securitySettings: 1, diverts: 0 },
      disagreements: [] as string[],
      notes: [],
    },
    contributions: {
      brokerconfig: {
        seededFrom: 'broker-1',
        recommendations: [
          {
            capability: 'slowConsumerDetection',
            title: 'Let the broker detect slow consumers',
            rationale: 'The broker sees each consumer own delivery rate.',
            appliable: true,
            section: 'ADDRESS_SETTING',
            match: '#',
            values: { maxDeliveryAttempts: 7, slowConsumerThreshold: 1 },
            roles: {},
            keys: ['slowConsumerThreshold'],
            manualSnippet: null,
          },
        ],
      },
    },
    topology: {
      clusterId: 'preview',
      nodes: [
        {
          artemisNodeId: 'node-a',
          splitBrain: 'NONE',
          replicationBehind: false,
          endpoints: [
            {
              id: 'e1',
              name: 'broker-1',
              artemisNodeId: 'node-a',
              jolokiaUrl: 'http://broker-1:8161/console/jolokia',
              coreUrl: null,
              haRole: 'PRIMARY',
              state: 'STARTED',
              active: true,
              replicaSync: null,
              version: '2.40.0',
              versionSupport: 'SUPPORTED',
              lastError: null,
              lastSeenAt: null,
              urlSource: 'SEED',
              urlProblem: null,
              coreUrlManual: false,
              manageable: true,
            },
          ],
        },
      ],
    },
  };
}

/** The refusal for brokers a registered cluster already holds; `visible` is whether the caller may see it. */
function alreadyRegistered(visible = true) {
  return HttpResponse.json(
    {
      type: 'https://rahdar.dev/problems/cluster-already-registered',
      title: 'These brokers are already registered',
      status: 409,
      detail: visible
        ? 'These brokers are already registered as the cluster "prod-emea" (node artemis-primary:61616). Open that cluster instead, or remove it before registering them again. If this is a cloned or restored broker, it carries the same node ID; give it a fresh journal so it gets its own.'
        : 'These brokers already belong to a registered cluster you do not have access to. Ask someone who can see it to share it with you, or to remove it. If this is a cloned or restored broker, it carries the same node ID; give it a fresh journal so it gets its own.',
      ...(visible
        ? { existingClusterId: 'c1', existingClusterName: 'prod-emea', overlappingNodes: ['artemis-primary:61616'] }
        : {}),
    },
    { status: 409, headers: { 'Content-Type': 'application/problem+json' } },
  );
}

beforeEach(() =>
  server.use(
    holding('environment:read'),
    http.get('*/api/v1/environments', () =>
      HttpResponse.json(paged([{ id: 'e1', name: 'Production', colour: null, sortOrder: 1 }])),
    ),
  ),
);

afterEach(() => act(() => notifications.clean()));

describe('ArtemisRegistrationForm', () => {
  it('says what the check found once it passes', async () => {
    server.use(http.post('*/api/v1/clusters', () => HttpResponse.json(preview())));
    const user = userEvent.setup();
    renderWithProviders(<ArtemisRegistrationForm />);

    await user.type(screen.getByLabelText(/Broker management URL/), 'broker-1');
    await user.click(screen.getByRole('button', { name: 'Check connection' }));

    expect(await screen.findByText(/^Connected\. Found \d+ nodes?\.$/)).toBeInTheDocument();
  });

  it('tells the operator that one management URL is enough', () => {
    renderWithProviders(<ArtemisRegistrationForm />);
    expect(screen.getByLabelText(/Broker management URL/)).toHaveAccessibleDescription(
      /^One is enough: Rahdar finds the rest of the cluster from it\./,
    );
  });

  it('asks for a new check once the URLs are edited after a check', async () => {
    server.use(http.post('*/api/v1/clusters', () => HttpResponse.json(preview())));
    const user = userEvent.setup();
    renderWithProviders(<ArtemisRegistrationForm />);

    await user.type(screen.getByLabelText(/Broker management URL/), 'broker-1');
    await user.click(screen.getByRole('button', { name: 'Check connection' }));
    await screen.findByText(/^Connected\./);

    await user.click(screen.getByRole('button', { name: 'Add another seed' }));
    await user.type(screen.getByLabelText(/Another management URL/), 'broker-2');
    expect(await screen.findByText(/details changed since the last check/)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Register cluster' })).toBeDisabled();
  });

  it('warns before registering a broker newer than Rahdar has tested, and still lets it register', async () => {
    const untested = preview();
    untested.topology.nodes[0].endpoints[0].version = '2.60.0';
    untested.topology.nodes[0].endpoints[0].versionSupport = 'NEWER_THAN_TESTED';
    server.use(http.post('*/api/v1/clusters', () => HttpResponse.json(untested)));
    const user = userEvent.setup();
    renderWithProviders(<ArtemisRegistrationForm />);

    await user.type(screen.getByLabelText(/Broker management URL/), 'broker-1');
    await user.click(screen.getByRole('button', { name: 'Check connection' }));

    expect(await screen.findByText('Newer Artemis than Rahdar has tested')).toBeInTheDocument();
    expect(screen.getByText(/broker-1 runs Artemis 2\.60\.0/)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Register cluster' })).toBeEnabled();
  });

  it('states why a broker older than the supported minimum is refused', async () => {
    server.use(
      http.post('*/api/v1/clusters', () =>
        HttpResponse.json(
          {
            type: 'https://rahdar.dev/problems/broker-unsupported-version',
            title: 'Artemis version not supported',
            status: 422,
            detail:
              'The broker at http://broker-1:8161/console/jolokia runs Artemis 2.31.2. Rahdar supports Artemis 2.33.0 and later; upgrade the broker to register it.',
            brokerErrorKind: 'UNSUPPORTED_VERSION',
          },
          { status: 422, headers: { 'Content-Type': 'application/problem+json' } },
        ),
      ),
    );
    const user = userEvent.setup();
    renderWithProviders(<ArtemisRegistrationForm />);

    await user.type(screen.getByLabelText(/Broker management URL/), 'broker-1');
    await user.click(screen.getByRole('button', { name: 'Check connection' }));

    expect(await screen.findByText(/Rahdar supports Artemis 2\.33\.0 and later/)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Register cluster' })).toBeDisabled();
    // Nothing was edited, so the hint must not claim the details changed.
    expect(screen.getByText('The check failed. Fix what it reports above, then check again.')).toBeInTheDocument();
  });

  it('says which cluster already has the brokers, links to it and will not register them again', async () => {
    server.use(http.post('*/api/v1/clusters', () => alreadyRegistered()));
    const user = userEvent.setup();
    renderWithProviders(<ArtemisRegistrationForm />);

    await user.type(screen.getByLabelText(/Broker management URL/), 'broker-1');
    await user.click(screen.getByRole('button', { name: 'Check connection' }));

    const notice = await screen.findByRole('alert');
    expect(notice).toHaveTextContent('These brokers are already registered');
    expect(notice).toHaveTextContent(/as the cluster "prod-emea" \(node artemis-primary:61616\)/);
    // A clone carries its original's node ID, so the notice says how to tell them apart.
    expect(notice).toHaveTextContent(/cloned or restored broker.*give it a fresh journal/);
    const open = screen.getByRole('link', { name: 'Open prod-emea' });
    expect(open).toHaveAttribute('href', '/clusters/c1');
    expect(screen.getByRole('button', { name: 'Register cluster' })).toBeDisabled();
    expect(
      screen.getByText('These brokers are registered already, so there is nothing to register.'),
    ).toBeInTheDocument();
  });

  it('keeps registration refused when another registration of the same brokers won the race', async () => {
    server.use(
      http.post('*/api/v1/clusters', ({ request }) =>
        new URL(request.url).searchParams.get('dryRun') === 'true' ? HttpResponse.json(preview()) : alreadyRegistered(),
      ),
    );
    const user = userEvent.setup();
    renderWithProviders(<ArtemisRegistrationForm />);

    await user.type(screen.getByLabelText(/Broker management URL/), 'broker-1');
    await user.click(screen.getByRole('button', { name: 'Check connection' }));
    await screen.findByText(/^Connected\./);
    await user.click(screen.getByRole('button', { name: 'Register cluster' }));

    expect(await screen.findByRole('link', { name: 'Open prod-emea' })).toHaveAttribute('href', '/clusters/c1');
    expect(screen.getByRole('button', { name: 'Register cluster' })).toBeDisabled();
    expect(navigateSpy).not.toHaveBeenCalled();
  });

  it('names no cluster the operator cannot see', async () => {
    server.use(http.post('*/api/v1/clusters', () => alreadyRegistered(false)));
    const user = userEvent.setup();
    renderWithProviders(<ArtemisRegistrationForm />);

    await user.type(screen.getByLabelText(/Broker management URL/), 'broker-1');
    await user.click(screen.getByRole('button', { name: 'Check connection' }));

    expect(await screen.findByRole('alert')).toHaveTextContent(/a registered cluster you do not have access to/);
    expect(screen.queryByRole('link')).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Register cluster' })).toBeDisabled();
  });

  it('lists an operation the brokers are too old for with the release it needs', async () => {
    const gated = preview();
    (gated.capabilities.versionGates as unknown[]).push({
      feature: 'X',
      label: 'Doing X',
      requiredVersion: '2.60.0',
      status: 'UNAVAILABLE',
      reason: 'Doing X needs Artemis 2.60.0 or later. This cluster runs broker-1 (2.40.0).',
      brokerXmlSnippet: null,
      nodes: [{ nodeId: 'e1', nodeName: 'broker-1', version: '2.40.0', supported: false }],
    });
    server.use(http.post('*/api/v1/clusters', () => HttpResponse.json(gated)));
    const user = userEvent.setup();
    renderWithProviders(<ArtemisRegistrationForm />);

    await user.type(screen.getByLabelText(/Broker management URL/), 'broker-1');
    await user.click(screen.getByRole('button', { name: 'Check connection' }));

    expect(await screen.findByRole('button', { name: /Doing X\s*Needs version 2\.60\.0/ })).toBeInTheDocument();
  });

  it('will not register until the connection has been checked, and says so', async () => {
    const user = userEvent.setup();
    renderWithProviders(<ArtemisRegistrationForm />);

    await user.type(screen.getByLabelText(/Broker management URL/), 'broker-1');

    expect(screen.getByRole('button', { name: 'Register cluster' })).toBeDisabled();
    // Disabled without a reason is the thing the form must never do.
    expect(screen.getByText('Check the connection first.')).toBeInTheDocument();
  });

  it('enables registration once the check passes', async () => {
    server.use(http.post('*/api/v1/clusters', () => HttpResponse.json(preview())));
    const user = userEvent.setup();
    renderWithProviders(<ArtemisRegistrationForm />);

    await user.type(screen.getByLabelText(/Broker management URL/), 'broker-1');
    await user.click(screen.getByRole('button', { name: 'Check connection' }));
    await screen.findByText(/^Connected\./);

    expect(screen.getByRole('button', { name: 'Register cluster' })).toBeEnabled();
  });

  it('re-blocks registration when a credential changes after a passing check', async () => {
    server.use(http.post('*/api/v1/clusters', () => HttpResponse.json(preview())));
    const user = userEvent.setup();
    renderWithProviders(<ArtemisRegistrationForm />);

    await user.type(screen.getByLabelText(/Broker management URL/), 'broker-1');
    await user.click(screen.getByRole('button', { name: 'Check connection' }));
    await screen.findByText(/^Connected\./);

    // A check that survived a password edit would vouch for credentials it never
    // saw — which is exactly how a wrong Core account reached a registered cluster.
    await user.type(screen.getByLabelText('Username'), 'artemis');
    await user.type(screen.getByLabelText('Password'), 'secret');

    expect(screen.getByRole('button', { name: 'Register cluster' })).toBeDisabled();
    expect(
      await screen.findByText('Check the connection again — the details changed since the last check.'),
    ).toBeInTheDocument();
  });

  it('answers an empty press with the message beside the field, focuses it and sends nothing', async () => {
    let sent = 0;
    server.use(
      http.post('*/api/v1/clusters', () => {
        sent += 1;
        return HttpResponse.json(preview());
      }),
    );
    const user = userEvent.setup();
    renderWithProviders(<ArtemisRegistrationForm />);

    const check = screen.getByRole('button', { name: 'Check connection' });
    expect(check).toBeEnabled();
    expect(screen.getByText('Fill in the fields marked above, then check the connection.')).toBeInTheDocument();
    await user.click(check);

    expect(await screen.findByText('Add at least one management URL.')).toBeInTheDocument();
    expect(screen.getByLabelText(/Broker management URL/)).toHaveFocus();
    expect(sent).toBe(0);
  });

  it('focuses the first invalid field of several, in page order', async () => {
    const user = userEvent.setup();
    renderWithProviders(<ArtemisRegistrationForm />);

    await user.type(screen.getByLabelText(/Broker management URL/), 'broker-1');
    await user.type(screen.getByLabelText('Username'), 'artemis');
    await user.click(screen.getByRole('button', { name: 'Check connection' }));

    expect(await screen.findByText('Enter the password for this username, or clear the username.')).toBeInTheDocument();
    expect(screen.getByLabelText('Password')).toHaveFocus();
  });

  it('clears the message on the username as soon as the password is filled in, with no retyping', async () => {
    const user = userEvent.setup();
    renderWithProviders(<ArtemisRegistrationForm />);

    await user.type(screen.getByLabelText('Username'), 'artemis');
    await user.tab();
    // Leaving the username is not yet a mistake: the password it belongs with has not been reached.
    await user.type(screen.getByLabelText('Password'), 'secret');
    await user.tab();

    expect(screen.queryByText(/for this (username|password)/)).not.toBeInTheDocument();
    expect(screen.getByLabelText('Username')).toBeValid();
  });

  it('moves the message to the member that is missing when one is cleared again', async () => {
    const user = userEvent.setup();
    renderWithProviders(<ArtemisRegistrationForm />);

    await user.type(screen.getByLabelText('Username'), 'artemis');
    await user.type(screen.getByLabelText('Password'), 'secret');
    await user.clear(screen.getByLabelText('Username'));
    await user.tab();
    expect(await screen.findByText('Enter the username for this password, or clear the password.')).toBeInTheDocument();

    await user.type(screen.getByLabelText('Username'), 'artemis');
    await waitFor(() => expect(screen.queryByText(/for this (username|password)/)).not.toBeInTheDocument());
  });

  it('registers a checked cluster, announces it by name and opens its topology', async () => {
    server.use(
      http.post('*/api/v1/clusters', ({ request }) =>
        new URL(request.url).searchParams.get('dryRun') === 'true'
          ? HttpResponse.json(preview())
          : HttpResponse.json({ id: 'c1', name: 'prod-eu' }, { status: 201 }),
      ),
    );
    const user = userEvent.setup();
    renderWithProviders(
      <>
        <Notifications />
        <ArtemisRegistrationForm />
      </>,
    );

    await user.type(screen.getByLabelText(/Broker management URL/), 'broker-1');
    await user.click(screen.getByRole('button', { name: 'Check connection' }));
    await screen.findByText(/^Connected\./);
    await user.click(screen.getByRole('button', { name: 'Register cluster' }));

    expect(await screen.findByText('Registered cluster prod-eu')).toBeInTheDocument();
    expect(navigateSpy).toHaveBeenCalledWith({ to: '/clusters/c1/topology' });
  });

  it('lists each node with what the management and the Core account each did there', async () => {
    server.use(http.post('*/api/v1/clusters', () => HttpResponse.json(preview())));
    const user = userEvent.setup();
    renderWithProviders(<ArtemisRegistrationForm />);

    await user.type(screen.getByLabelText(/Broker management URL/), 'broker-1');
    await user.click(screen.getByRole('button', { name: 'Check connection' }));

    const table = await screen.findByRole('table', { name: 'Nodes found by the check' });
    const primary = within(table).getByRole('row', { name: /broker-1/ });
    expect(primary).toHaveTextContent('Accepted');
    expect(primary).toHaveTextContent('Rejected');
    expect(primary).toHaveTextContent('(from the registered seed address)');
    const backup = within(table).getByRole('row', { name: /broker-2/ });
    expect(backup).toHaveTextContent('None: a different broker answered at the address the pattern gives');
    expect(backup).toHaveTextContent('Not tried');
  });

  it('prefills the management URL pattern from the first seed and sends it', async () => {
    let body: Record<string, unknown> = {};
    server.use(
      http.post('*/api/v1/clusters', async ({ request }) => {
        body = (await request.json()) as Record<string, unknown>;
        return HttpResponse.json(preview());
      }),
    );
    const user = userEvent.setup();
    renderWithProviders(<ArtemisRegistrationForm />);

    await user.type(screen.getByLabelText(/Broker management URL/), 'broker-1');
    await user.click(screen.getByRole('button', { name: /Advanced/ }));
    expect(screen.getByLabelText('Management URL pattern')).toHaveValue('http://{host}:8161/console/jolokia');
    await user.click(screen.getByRole('button', { name: 'Check connection' }));
    await screen.findByText(/^Connected\./);

    expect(body.system).toBe('artemis');
    expect(body.connection).toMatchObject({
      system: 'artemis',
      seedUrls: ['http://broker-1:8161/console/jolokia'],
      managementUrlPattern: 'http://{host}:8161/console/jolokia',
    });
  });

  it('keeps every host typed in one box, shows what each became and sends them all', async () => {
    let body: { connection: { seedUrls: string[] } } | undefined;
    server.use(
      http.post('*/api/v1/clusters', async ({ request }) => {
        body = (await request.json()) as typeof body;
        return HttpResponse.json(preview());
      }),
    );
    const user = userEvent.setup();
    renderWithProviders(<ArtemisRegistrationForm />);

    await user.type(screen.getByLabelText(/Broker management URL/), 'broker-1, broker-2');
    expect(screen.getByText(/http:\/\/broker-2:8161\/console\/jolokia/)).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Check connection' }));
    await screen.findByText(/^Connected\./);

    expect(body?.connection.seedUrls).toEqual([
      'http://broker-1:8161/console/jolokia',
      'http://broker-2:8161/console/jolokia',
    ]);
  });

  it('refuses the second host of a box when it cannot be read, not only the first', async () => {
    const user = userEvent.setup();
    renderWithProviders(<ArtemisRegistrationForm />);

    await user.type(screen.getByLabelText(/Broker management URL/), 'broker-1 http://');
    await user.click(screen.getByRole('button', { name: 'Check connection' }));

    expect(await screen.findByText(/Couldn't make sense of: http:\/\//)).toBeInTheDocument();
  });

  it('offers to adopt the running configuration, on when the nodes agree, with the counts', async () => {
    server.use(http.post('*/api/v1/clusters', () => HttpResponse.json(preview())));
    const user = userEvent.setup();
    renderWithProviders(<ArtemisRegistrationForm />);

    await user.type(screen.getByLabelText(/Broker management URL/), 'broker-1');
    await user.click(screen.getByRole('button', { name: 'Check connection' }));

    const adopt = await screen.findByRole('switch', { name: /Adopt what the brokers run/ });
    expect(adopt).toBeChecked();
    expect(adopt).toHaveAccessibleDescription(/3 addresses, 2 address settings, 1 security setting, 0 diverts/);
  });

  it('leaves the adoption off and lists the disagreements when the nodes disagree', async () => {
    const disagreeing = preview();
    disagreeing.adoption.disagreements = ["Address settings for orders differ between a and b; a's were kept."];
    server.use(http.post('*/api/v1/clusters', () => HttpResponse.json(disagreeing)));
    const user = userEvent.setup();
    renderWithProviders(<ArtemisRegistrationForm />);

    await user.type(screen.getByLabelText(/Broker management URL/), 'broker-1');
    await user.click(screen.getByRole('button', { name: 'Check connection' }));

    expect(await screen.findByRole('switch', { name: /Adopt what the brokers run/ })).not.toBeChecked();
    expect(screen.getByText(/Address settings for orders differ between a and b/)).toBeInTheDocument();
  });

  it('registers with the adoption the operator chose', async () => {
    const bodies: Record<string, unknown>[] = [];
    server.use(
      http.post('*/api/v1/clusters', async ({ request }) => {
        bodies.push((await request.json()) as Record<string, unknown>);
        return new URL(request.url).searchParams.get('dryRun') === 'true'
          ? HttpResponse.json(preview())
          : HttpResponse.json({ id: 'c1', name: 'prod-eu' }, { status: 201 });
      }),
    );
    const user = userEvent.setup();
    renderWithProviders(<ArtemisRegistrationForm />);

    await user.type(screen.getByLabelText(/Broker management URL/), 'broker-1');
    await user.click(screen.getByRole('button', { name: 'Check connection' }));
    await user.click(await screen.findByRole('switch', { name: /Adopt what the brokers run/ }));
    await user.click(screen.getByRole('button', { name: 'Register cluster' }));

    await waitFor(() => expect(bodies).toHaveLength(2));
    expect(bodies[0].adopt).toBe(false);
    expect(bodies[1].adopt).toBe(false);
  });

  it('sends the adoption on when the nodes agree and the operator leaves it', async () => {
    const bodies: Record<string, unknown>[] = [];
    server.use(
      http.post('*/api/v1/clusters', async ({ request }) => {
        bodies.push((await request.json()) as Record<string, unknown>);
        return new URL(request.url).searchParams.get('dryRun') === 'true'
          ? HttpResponse.json(preview())
          : HttpResponse.json({ id: 'c1', name: 'prod-eu' }, { status: 201 });
      }),
    );
    const user = userEvent.setup();
    renderWithProviders(<ArtemisRegistrationForm />);

    await user.type(screen.getByLabelText(/Broker management URL/), 'broker-1');
    await user.click(screen.getByRole('button', { name: 'Check connection' }));
    await screen.findByRole('switch', { name: /Adopt what the brokers run/ });
    await user.click(screen.getByRole('button', { name: 'Register cluster' }));

    await waitFor(() => expect(bodies).toHaveLength(2));
    expect(bodies[1].adopt).toBe(true);
  });

  it('refuses an account typed into a management URL and says where it goes', async () => {
    let sent = 0;
    server.use(
      http.post('*/api/v1/clusters', () => {
        sent += 1;
        return HttpResponse.json(preview());
      }),
    );
    const user = userEvent.setup();
    renderWithProviders(<ArtemisRegistrationForm />);

    await user.type(screen.getByLabelText(/Broker management URL/), 'http://admin:secret@broker-1:8161');
    await user.click(screen.getByRole('button', { name: 'Check connection' }));

    expect(
      await screen.findByText('Put the account in the Management account fields, not in the URL.'),
    ).toBeInTheDocument();
    expect(sent).toBe(0);
  });

  it('refuses a pattern that could name another host', async () => {
    const user = userEvent.setup();
    renderWithProviders(<ArtemisRegistrationForm />);

    await user.type(screen.getByLabelText(/Broker management URL/), 'broker-1');
    await user.click(screen.getByRole('button', { name: /Advanced/ }));
    const pattern = screen.getByLabelText('Management URL pattern');
    await user.clear(pattern);
    await user.type(pattern, 'http://evil/?h={{host}');
    await user.click(screen.getByRole('button', { name: 'Check connection' }));

    expect(await screen.findByText(/with \{host\} as the whole host/)).toBeInTheDocument();
  });

  it('adds and removes another seed', async () => {
    const user = userEvent.setup();
    renderWithProviders(<ArtemisRegistrationForm />);

    await user.click(screen.getByRole('button', { name: 'Add another seed' }));
    expect(screen.getByLabelText(/Another management URL/)).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Remove management URL 2' }));
    expect(screen.queryByLabelText(/Another management URL/)).not.toBeInTheDocument();
  });
});
