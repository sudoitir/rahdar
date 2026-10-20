import { destinationIdentity } from '../../test/wire.ts';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { http, HttpResponse } from 'msw';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

import { renderWithProviders } from '../../test/render.tsx';
import { server } from '../../test/setup.ts';
import { notify } from '../../ui/notify.ts';
import { baseHandlers, declaration, NODE_A, NODE_B } from './fixtures.ts';

const search: Record<string, unknown> = {};
vi.mock('@tanstack/react-router', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@tanstack/react-router')>()),
  useParams: () => ({ clusterId: 'c1' }),
  useSearch: () => search,
  useNavigate: () => (opts: { search?: (prev: Record<string, unknown>) => Record<string, unknown> }) => {
    if (opts.search) Object.assign(search, opts.search(search));
  },
  Link: ({ children, to, ...rest }: { children: React.ReactNode; to: string }) => (
    <a href={to} {...rest}>
      {children}
    </a>
  ),
}));

const { ConfigurationView } = await import('./ConfigurationView.tsx');

describe('ConfigurationView', () => {
  // The router mock's search object is shared across cases; a leftover tab from
  // one test would otherwise decide which view the next one renders.
  beforeEach(() => {
    for (const key of Object.keys(search)) delete search[key];
  });

  it('teaches what a declaration is when nothing is declared', async () => {
    server.use(
      ...baseHandlers(
        declaration({
          declared: false,
          revision: 0,
          document: { version: 1, addresses: [], addressSettings: [], securitySettings: [], diverts: [], bridges: [] },
          nodes: [],
        }),
      ),
    );
    renderWithProviders(<ConfigurationView />);

    expect(await screen.findByText(/Declare what this cluster should run/)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Adopt from cluster' })).toBeEnabled();
    // The apply control is visible and explains itself — never hidden.
    expect(screen.getByRole('button', { name: 'Why review and apply is unavailable' })).toBeInTheDocument();
  });

  it('has no routing builder section: the builder is on the Routing screen (ADR-0094)', async () => {
    server.use(...baseHandlers());
    renderWithProviders(<ConfigurationView />);

    const list = await screen.findByRole('navigation', { name: 'Configuration sections' });
    expect(
      within(list)
        .getAllByRole('link')
        .map((t) => t.textContent),
    ).toEqual([
      expect.stringMatching(/^Addresses and queues/),
      expect.stringMatching(/^Address settings/),
      expect.stringMatching(/^Security settings/),
      expect.stringMatching(/^Diverts/),
      expect.stringMatching(/^Bridges/),
      'Live nodes',
      'History',
      'Recommended',
    ]);
    expect(within(list).queryByRole('link', { name: /routing/i })).toBeNull();
  });

  it('disables apply with the reason when the cluster is managed outside Rahdar', async () => {
    server.use(...baseHandlers(declaration({ applyMode: 'CONFIG_MANAGED' })));
    const user = userEvent.setup();
    renderWithProviders(<ConfigurationView />);

    expect(await screen.findByText(/Managed outside Rahdar/)).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Why review and apply is unavailable' }));
    expect(await screen.findByText(/owned by configuration management/)).toBeInTheDocument();
    // The primary action flipped to the fragment.
    expect(screen.getByRole('button', { name: 'Copy broker.xml fragment' })).toBeEnabled();
  });

  it('opens the address-setting editor on the house form pattern: blur validation and focus on the first invalid field', async () => {
    server.use(...baseHandlers());
    const user = userEvent.setup();
    search.tab = 'addressSettings';
    renderWithProviders(<ConfigurationView />);

    await user.click(await screen.findByRole('button', { name: 'Add address setting' }));
    const dialog = await screen.findByRole('dialog', { name: 'New address setting' });
    const match = within(dialog).getByRole('textbox', { name: /match pattern/i });
    await user.click(match);
    await user.tab();
    expect(await within(dialog).findByText(/A match pattern is required/)).toBeInTheDocument();

    await user.click(within(dialog).getByRole('button', { name: /Save as revision 4/ }));
    expect(match).toHaveFocus();
    expect(within(dialog).getByText('Fix the fields above to continue.')).toBeInTheDocument();
  });

  it('names a queue that is missing on a node, even when the queue is not named after its address', async () => {
    server.use(
      ...baseHandlers(
        declaration({
          document: {
            version: 1,
            addresses: [
              {
                name: 'orders.request',
                routingTypes: ['MULTICAST'],
                queues: [{ name: 'orders.audit', routingType: 'MULTICAST', durable: true }],
              },
            ],
            addressSettings: [],
            securitySettings: [],
            diverts: [],
            bridges: [],
          },
          nodes: [
            NODE_A,
            {
              ...NODE_B,
              state: 'DRIFTED',
              findings: [
                {
                  kind: 'MISSING',
                  section: 'QUEUE',
                  key: 'orders.audit',
                  detail: 'Create queue orders.audit on orders.request',
                  declared: { name: 'orders.audit', 'max-consumers': 3 },
                  observed: {},
                },
              ],
            },
          ],
        }),
      ),
    );
    renderWithProviders(<ConfigurationView />);

    // The queue hangs off the address's row, so the row must carry the queue's
    // drift and name it — an address "in sync" while one of its queues is gone
    // is the reading this screen must never produce.
    const row = (await screen.findByRole('button', { name: 'Apply this address orders.request' })).closest('tr')!;
    expect(within(row).getByText(/queue orders\.audit missing on broker-2/)).toBeInTheDocument();
    expect(within(row).queryByText(/in sync on 2\/2/)).not.toBeInTheDocument();
  });

  it('keeps the open editor in the URL, so it can be linked and restored', async () => {
    server.use(...baseHandlers());
    search.tab = 'addressSettings';
    search.item = 'orders.#';
    renderWithProviders(<ConfigurationView />);

    expect(await screen.findByRole('dialog', { name: /Address setting orders/ })).toBeInTheDocument();
  });

  it('writes the open editor to the URL when a row is edited, and clears it on close', async () => {
    server.use(...baseHandlers());
    const user = userEvent.setup();
    renderWithProviders(<ConfigurationView />);

    await user.click(await screen.findByRole('button', { name: 'Edit address orders.request' }));
    await waitFor(() => expect(search).toMatchObject({ tab: 'addresses', item: 'orders.request' }));
  });

  it('states how far the revision has got, and names the node a row differs on', async () => {
    server.use(...baseHandlers());
    search.tab = 'addressSettings';
    const { unmount } = renderWithProviders(<ConfigurationView />);
    expect(await screen.findByText('Revision 3 — applied to 2 of 2 live nodes')).toBeInTheDocument();
    expect(screen.getAllByText(/in sync on 2\/2/).length).toBeGreaterThanOrEqual(1);
    unmount();

    server.use(
      ...baseHandlers(
        declaration({
          nodes: [
            NODE_A,
            {
              ...NODE_B,
              state: 'DRIFTED',
              findings: [
                {
                  kind: 'DIVERGENT',
                  section: 'ADDRESS_SETTING',
                  key: 'orders.#',
                  detail: 'Replace address setting orders.#',
                  declared: { addressFullMessagePolicy: 'PAGE' },
                  observed: { addressFullMessagePolicy: 'DROP' },
                },
              ],
            },
          ],
        }),
      ),
    );
    renderWithProviders(<ConfigurationView />);
    // The item's own row carries what differs and where — no second tab.
    const row = (await screen.findByRole('button', { name: 'Apply this address setting orders.#' })).closest('tr')!;
    expect(within(row).getByText(/differs on broker-2/)).toBeInTheDocument();
    // The key is shown by its broker.xml element name, from the catalogue, and
    // declared and observed are one row, so the two never fall out of line.
    expect(within(row).getAllByText('address-full-policy').length).toBeGreaterThanOrEqual(1);
    expect(within(row).getByText('DROP').closest('dd')).toHaveTextContent('PAGE → DROP');
  });

  it('says a missing item is missing once, without reprinting every key as "declared → —"', async () => {
    // A missing item differs in every key, and two findings on one node (the
    // address and the queue of the same name) used to name that node twice.
    const missing = (section: 'ADDRESS' | 'QUEUE') => ({
      kind: 'MISSING' as const,
      section,
      key: 'orders.request',
      detail: `Create ${section.toLowerCase()} orders.request`,
      declared: { name: 'orders.request', routingType: 'ANYCAST', durable: true, maxConsumers: -1 },
      observed: {},
    });
    server.use(
      ...baseHandlers(
        declaration({
          nodes: [NODE_A, { ...NODE_B, state: 'DRIFTED', findings: [missing('ADDRESS'), missing('QUEUE')] }],
        }),
      ),
    );
    renderWithProviders(<ConfigurationView />);

    const row = (await screen.findByRole('button', { name: 'Apply this address orders.request' })).closest('tr')!;
    const state = within(row).getByText(/missing on/);
    expect(state.textContent).toBe('missing on broker-2');
    // None of the declared keys are reprinted against an em dash.
    expect(within(row).queryByText(/maxConsumers/)).not.toBeInTheDocument();
    expect(within(row).queryByText(/→ —/)).not.toBeInTheDocument();
  });

  it('lists what an import cannot carry instead of dropping it', async () => {
    server.use(
      ...baseHandlers(),
      http.post('*/api/v1/clusters/c1/artemis/config/import-xml', () =>
        HttpResponse.json({
          document: {
            version: 1,
            addresses: [],
            addressSettings: [{ match: 'orders.#', values: {} }],
            securitySettings: [],
            diverts: [],
            bridges: [],
          },
          unsupported: [
            { path: 'core/global-max-size', reason: 'a static setting; it cannot be applied over the management API' },
          ],
          errors: [],
        }),
      ),
    );
    const user = userEvent.setup();
    renderWithProviders(<ConfigurationView />);

    await user.click(await screen.findByRole('button', { name: 'Import XML' }));
    const dialog = await screen.findByRole('dialog', { name: 'Import broker.xml' });
    await user.type(within(dialog).getByRole('textbox'), '<core></core>');
    await user.click(within(dialog).getByRole('button', { name: 'Preview import' }));

    await waitFor(() => expect(within(dialog).getByText('Not applied (1)')).toBeInTheDocument());
    expect(within(dialog).getByText('core/global-max-size')).toBeInTheDocument();
    expect(within(dialog).getByText(/cannot be applied over the management API/)).toBeInTheDocument();
  });

  it('loads a broker.xml file into the import, and previews exactly what it holds', async () => {
    let posted = '';
    server.use(
      ...baseHandlers(),
      http.post('*/api/v1/clusters/c1/artemis/config/import-xml', async ({ request }) => {
        posted = await request.text();
        return HttpResponse.json({
          document: {
            version: 1,
            addresses: [],
            addressSettings: [{ match: 'x', values: {} }],
            securitySettings: [],
            diverts: [],
            bridges: [],
          },
          unsupported: [],
          errors: [],
        });
      }),
    );
    const user = userEvent.setup();
    renderWithProviders(<ConfigurationView />);

    await user.click(await screen.findByRole('button', { name: 'Import XML' }));
    const dialog = await screen.findByRole('dialog', { name: 'Import broker.xml' });
    const xml = '<address-setting match="x"><max-delivery-attempts>2</max-delivery-attempts></address-setting>';
    await user.upload(
      within(dialog).getByLabelText('broker.xml file'),
      new File([xml], 'broker.xml', { type: 'application/xml' }),
    );

    await waitFor(() => expect(within(dialog).getByRole('textbox')).toHaveValue(xml));
    expect(within(dialog).getByText('Loaded broker.xml')).toBeInTheDocument();
    await user.click(within(dialog).getByRole('button', { name: 'Preview import' }));
    await waitFor(() => expect(posted).toBe(xml));
  });

  it('recommends the settings the probe found, seeded from the node, and declares the chosen ones', async () => {
    const declared = vi.fn();
    server.use(
      ...baseHandlers(),
      http.get('*/api/v1/clusters/c1/artemis/config/recommendations', () =>
        HttpResponse.json({
          seededFrom: 'broker-1',
          recommendations: [
            {
              capability: 'slowConsumerDetection',
              title: 'Let the broker detect slow consumers',
              rationale: 'The broker sees each consumer’s own delivery rate.',
              appliable: true,
              section: 'ADDRESS_SETTING',
              match: '#',
              values: { maxDeliveryAttempts: 7, slowConsumerThreshold: 1 },
              roles: {},
              keys: ['slowConsumerThreshold'],
              manualSnippet: null,
            },
            {
              capability: 'notifications',
              title: 'Emit connection and session events',
              rationale: 'NotificationActiveMQServerPlugin is a broker-plugin.',
              appliable: false,
              section: null,
              match: null,
              values: {},
              roles: {},
              keys: [],
              manualSnippet: '<broker-plugins/>',
            },
          ],
        }),
      ),
      http.post('*/api/v1/clusters/c1/artemis/config/recommendations/declare', async ({ request }) => {
        declared((await request.json()) as unknown);
        return HttpResponse.json(declaration(), { status: 201 });
      }),
    );
    search.tab = 'recommended';
    const user = userEvent.setup();
    renderWithProviders(<ConfigurationView />);

    // The whole entry is reachable, not only the key being changed: a runtime write
    // replaces the entry, so the keys it keeps are part of what is confirmed.
    expect(await screen.findAllByText('slowConsumerThreshold')).not.toHaveLength(0);
    await user.click(screen.getByRole('button', { name: 'Show the 1 key it keeps as they are' }));
    expect(await screen.findByText('maxDeliveryAttempts')).toBeInTheDocument();
    expect(screen.getByText(/as broker-1 runs it today/)).toBeInTheDocument();

    // A gap Rahdar cannot close is named with its snippet, never omitted.
    expect(screen.getByText('Emit connection and session events')).toBeInTheDocument();
    expect(screen.getByText(/These still need a broker.xml edit/)).toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: /Declare & review the plan/ }));
    await waitFor(() => expect(declared).toHaveBeenCalled());
    expect(declared.mock.calls[0][0]).toMatchObject({ capabilities: ['slowConsumerDetection'] });
  });

  it('will not declare a security setting that would grant nobody anything', async () => {
    server.use(
      ...baseHandlers(),
      http.get('*/api/v1/clusters/c1/artemis/config/recommendations', () =>
        HttpResponse.json({
          seededFrom: null,
          recommendations: [
            {
              capability: 'notifications',
              title: 'Let Rahdar subscribe to broker notifications',
              rationale: 'A Core subscriber needs three permissions.',
              appliable: true,
              section: 'SECURITY_SETTING',
              match: 'activemq.notifications',
              values: {},
              roles: { consume: [], createNonDurableQueue: [] },
              keys: ['consume'],
              manualSnippet: null,
            },
          ],
        }),
      ),
    );
    search.tab = 'recommended';
    renderWithProviders(<ConfigurationView />);

    expect(await screen.findByText(/At least one role, or this grants nobody anything/)).toBeInTheDocument();
    // Disabled with the reason beside it, never hidden.
    expect(screen.getByRole('button', { name: /Declare & review the plan/ })).toBeDisabled();
    expect(screen.getByText(/would grant nobody anything/)).toBeInTheDocument();
    // No node could be read, so the panel says the entries are unseeded.
    expect(screen.getByText(/No node could be read/)).toBeInTheDocument();
  });

  it('says why an in-sync node agrees, and says so when nothing recorded it', async () => {
    server.use(
      ...baseHandlers(
        declaration({
          nodes: [
            { ...NODE_A, state: 'IN_SYNC', basis: 'ADOPTED', basisRef: 3 },
            { ...NODE_B, state: 'IN_SYNC', basis: null, basisRef: null },
          ],
        }),
      ),
    );
    const user = userEvent.setup();
    search.tab = 'nodes';
    renderWithProviders(<ConfigurationView />);

    // Adoption and a verified apply both read "in sync"; only one of them means
    // Rahdar wrote anything, so the difference is on the screen.
    expect(
      await screen.findByRole('link', { name: /Adopted as revision 3; no broker was written/ }),
    ).toBeInTheDocument();
    expect(screen.getByText('No record of why it agrees.')).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Evaluate now' }));
  });

  it('makes an adoption that closes drift name what it erases and type the cluster to confirm', async () => {
    server.use(
      ...baseHandlers(),
      http.post('*/api/v1/clusters/c1/artemis/config/adopt', () =>
        HttpResponse.json({
          document: {
            version: 1,
            addresses: [],
            addressSettings: [{ match: 'orders.#', values: {} }],
            securitySettings: [],
            diverts: [],
            bridges: [],
          },
          notes: ['1 open drift finding(s) will be closed by adopting this document, and no broker will be written'],
          disagreements: [],
          closes: [
            {
              nodeId: 'n-b',
              nodeName: 'broker-2',
              finding: {
                kind: 'DIVERGENT',
                section: 'ADDRESS_SETTING',
                key: 'orders.#',
                detail: 'maxSizeBytes differs from the declaration',
                declared: {},
                observed: {},
              },
            },
          ],
        }),
      ),
    );
    const user = userEvent.setup();
    renderWithProviders(<ConfigurationView />);

    await user.click(await screen.findByRole('button', { name: 'Adopt from cluster' }));
    const dialog = await screen.findByRole('dialog', { name: 'Adopt from cluster' });

    expect(await within(dialog).findByText(/Closes 1 open drift finding with zero broker writes/)).toBeInTheDocument();
    expect(within(dialog).getByText(/broker-2: maxSizeBytes differs/)).toBeInTheDocument();

    // Saving is armed only by the cluster's name: the effect looks like an apply
    // and the meaning is its opposite.
    const save = within(dialog).getByRole('button', { name: 'Save as revision 4' });
    expect(save).toBeEnabled();
  });

  it("offers address-setting templates built from the cluster's own DLQ, and saves nothing until asked", async () => {
    server.use(
      ...baseHandlers(),
      http.get('*/api/v1/clusters/c1/dead-letters', () =>
        HttpResponse.json({
          settingsAvailable: true,
          destinations: [
            { ...destinationIdentity('ORDERS.DLQ'), purpose: 'dead-letter', subscriptions: [] },
            { ...destinationIdentity('ORDERS.EXPIRY'), purpose: 'expiry', subscriptions: [] },
          ],
        }),
      ),
    );
    const user = userEvent.setup();
    search.tab = 'addressSettings';
    renderWithProviders(<ConfigurationView />);

    await user.click(await screen.findByRole('button', { name: 'Add address setting' }));
    const dialog = await screen.findByRole('dialog', { name: 'New address setting' });

    // The names come from the cluster, never invented: a prefilled DLQ that does
    // not exist declares a policy that routes nowhere.
    await user.click(await within(dialog).findByRole('checkbox', { name: 'Retry, then dead-letter' }));
    expect(within(dialog).getByRole('textbox', { name: /dead-letter-address/ })).toHaveValue('ORDERS.DLQ');
    // The other keys of the template land too; only the ones this fixture's
    // catalogue knows are rendered as fields.
    expect(
      within(dialog).getByText(/Fills the fields below from this cluster's own ORDERS.DLQ and ORDERS.EXPIRY/),
    ).toBeInTheDocument();
  });

  it('suggests adopting the live nodes as revision 1, in counts, and adopts nothing on its own', async () => {
    const saved = vi.fn();
    server.use(
      ...baseHandlers(
        declaration({
          declared: false,
          revision: 0,
          document: { version: 1, addresses: [], addressSettings: [], securitySettings: [], diverts: [], bridges: [] },
        }),
      ),
      http.post('*/api/v1/clusters/c1/artemis/config/adopt', () =>
        HttpResponse.json({
          document: {
            version: 1,
            addresses: [{ name: 'orders.request', routingTypes: ['ANYCAST'], queues: [] }],
            addressSettings: [{ match: '#', values: { maxSizeBytes: 1 } }],
            securitySettings: [],
            diverts: [],
          },
          notes: [],
          disagreements: ['address setting # differs between broker-1 and broker-2'],
          closes: [],
        }),
      ),
      http.put('*/api/v1/clusters/c1/artemis/config', async ({ request }) => {
        saved(await request.json());
        return HttpResponse.json(declaration(), { status: 201 });
      }),
    );
    renderWithProviders(<ConfigurationView />);

    expect(await screen.findByText('2 entries would be declared')).toBeInTheDocument();
    expect(screen.getByText(/Addresses: 1 recognised — 1 added/)).toBeInTheDocument();
    // A disagreement is named on the card, not discovered after opening the drawer.
    expect(screen.getByText(/differs between broker-1 and broker-2/)).toBeInTheDocument();
    // The reason auto-adoption is refused is on the screen, keyboard-reachable.
    expect(screen.getByRole('button', { name: /Why does Rahdar not do this for me/ })).toBeInTheDocument();
    // Previewing is a read. Nothing was saved by rendering the suggestion.
    expect(saved).not.toHaveBeenCalled();
  });

  it('reads the drift evaluation as an age against the configured cadence', async () => {
    const fourMinutesAgo = new Date(Date.now() - 4 * 60_000).toISOString();
    server.use(
      ...baseHandlers(
        declaration({
          driftIntervalSeconds: 300,
          nodes: [
            { ...NODE_A, evaluatedAt: fourMinutesAgo },
            { ...NODE_B, evaluatedAt: fourMinutesAgo },
          ],
        }),
      ),
    );
    renderWithProviders(<ConfigurationView />);

    // An age, not a wall-clock stamp: four minutes back, against a five-minute pass.
    expect(await screen.findByText(/nodes evaluated 4m ago, about every 5m/)).toBeInTheDocument();
  });

  it('is one page: a single h1, the open section as its h2 and a group named by its section', async () => {
    server.use(...baseHandlers());
    renderWithProviders(<ConfigurationView />);

    expect(await screen.findByRole('heading', { level: 1, name: 'Configuration' })).toBeInTheDocument();
    expect(screen.getAllByRole('heading', { level: 1 })).toHaveLength(1);
    expect(await screen.findByRole('heading', { level: 2, name: 'Addresses and queues' })).toBeInTheDocument();
    expect(await screen.findByRole('group', { name: 'Addresses and queues' })).toBeInTheDocument();
  });

  it('holds the page and says what is loading while the declaration loads', async () => {
    server.use(
      http.get('*/api/v1/clusters/c1/artemis/config', async () => {
        await new Promise((r) => setTimeout(r, 200));
        return HttpResponse.json(declaration());
      }),
      ...baseHandlers(),
    );
    renderWithProviders(<ConfigurationView />);

    expect(screen.getByRole('heading', { level: 1, name: 'Configuration' })).toBeInTheDocument();
    expect(screen.getByRole('status')).toHaveTextContent('Loading the declared configuration');
    expect(await screen.findByRole('heading', { level: 2, name: 'Addresses and queues' })).toBeInTheDocument();
  });

  it('states why the declaration could not be read and reads it again on retry', async () => {
    let attempts = 0;
    server.use(
      http.get('*/api/v1/clusters/c1/artemis/config', () => {
        attempts += 1;
        return attempts === 1
          ? HttpResponse.json({ title: 'Down', detail: 'The configuration store is not answering.' }, { status: 503 })
          : HttpResponse.json(declaration());
      }),
      ...baseHandlers(),
    );
    const user = userEvent.setup();
    renderWithProviders(<ConfigurationView />);

    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent('The configuration store is not answering.');
    // The page keeps its heading while its content failed.
    expect(screen.getByRole('heading', { level: 1, name: 'Configuration' })).toBeInTheDocument();

    await user.click(within(alert).getByRole('button', { name: 'Retry' }));
    expect(await screen.findByRole('heading', { level: 2, name: 'Addresses and queues' })).toBeInTheDocument();
  });

  it('keeps Evaluate now and Export visible when nothing is declared, each explaining itself', async () => {
    server.use(
      ...baseHandlers(
        declaration({
          declared: false,
          revision: 0,
          document: { version: 1, addresses: [], addressSettings: [], securitySettings: [], diverts: [], bridges: [] },
          nodes: [],
        }),
      ),
    );
    const user = userEvent.setup();
    renderWithProviders(<ConfigurationView />);

    expect(await screen.findByRole('button', { name: 'Evaluate now' })).toBeDisabled();
    expect(screen.getByRole('button', { name: 'Export XML' })).toBeDisabled();
    // The reason is on a control that takes focus, never a hover title.
    await user.click(screen.getByRole('button', { name: 'Why exporting the declaration is unavailable' }));
    expect(await screen.findByText('Nothing is declared yet.')).toBeInTheDocument();
  });

  it('busies Evaluate now while it runs, announces that it finished, and states a failure with a retry', async () => {
    let calls = 0;
    server.use(
      ...baseHandlers(),
      http.post('*/api/v1/clusters/c1/artemis/config/drift/evaluate', () => {
        calls += 1;
        return calls === 1
          ? HttpResponse.json({ title: 'Down', detail: 'No node answered the evaluation.' }, { status: 503 })
          : HttpResponse.json({});
      }),
    );
    const succeeded = vi.spyOn(notify, 'succeeded');
    const user = userEvent.setup();
    renderWithProviders(<ConfigurationView />);

    await user.click(await screen.findByRole('button', { name: 'Evaluate now' }));
    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent('No node answered the evaluation.');
    expect(succeeded).not.toHaveBeenCalled();

    await user.click(within(alert).getByRole('button', { name: 'Retry' }));
    await waitFor(() =>
      expect(succeeded).toHaveBeenCalledWith(
        expect.objectContaining({ action: expect.objectContaining({ past: 'Evaluated' }) }),
      ),
    );
    expect(screen.queryByRole('alert')).toBeNull();
    succeeded.mockRestore();
  });

  it('says why the recommendations could not be assessed, and that this is not the same as nothing to recommend', async () => {
    server.use(
      ...baseHandlers(),
      http.get('*/api/v1/clusters/c1/artemis/config/recommendations', () =>
        HttpResponse.json({ title: 'Down', detail: 'No node could be read.' }, { status: 503 }),
      ),
    );
    search.tab = 'recommended';
    renderWithProviders(<ConfigurationView />);

    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent('No node could be read.');
    expect(within(alert).getByRole('button', { name: 'Retry' })).toBeEnabled();
    expect(screen.getByText(/this is not the same as having nothing to recommend/)).toBeInTheDocument();
  });
});
