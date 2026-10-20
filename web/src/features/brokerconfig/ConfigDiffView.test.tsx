import { beforeEach, describe, expect, it, vi } from 'vitest';
import { http, HttpResponse } from 'msw';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

import { renderWithProviders } from '../../test/render.tsx';
import { server } from '../../test/setup.ts';
import type { ConfigDiffView as Diff } from './api.ts';
import { A, B, C, cleanDiff, diff, node, same } from './configDiffFixtures.ts';
import type { ConfigDiffSearch } from './feature.ts';

// The address is the view's state: the route's search is a variable the test sets, and every change the
// view makes to it is recorded. The round trip through a real router is the browser test's.
let search: ConfigDiffSearch = {};
const navigate = vi.fn();

vi.mock('@tanstack/react-router', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@tanstack/react-router')>()),
  useParams: () => ({ clusterId: 'c1' }),
  useSearch: () => search,
  useNavigate: () => navigate,
  Link: ({ to, children, className }: { to: string; children: React.ReactNode; className?: string }) => (
    <a href={to} className={className}>
      {children}
    </a>
  ),
}));

const { ConfigDiffView } = await import('./ConfigDiffView.tsx');

function serve(d: Diff | Response = diff()) {
  server.use(
    http.get('*/api/v1/clusters/c1/artemis/config-diff', () => (d instanceof Response ? d : HttpResponse.json(d))),
  );
}

/** What the last navigation asked the search to become, from the empty one. */
function lastSearch(): Record<string, unknown> {
  const call = navigate.mock.calls.at(-1)?.[0] as {
    search: (prev: Record<string, unknown>) => Record<string, unknown>;
  };
  return call.search({});
}

beforeEach(() => {
  search = {};
  navigate.mockReset();
});

describe('ConfigDiffView', () => {
  it('opens on the drift: the keys that drift, in one sentence and one table, and nothing else', async () => {
    serve();
    renderWithProviders(<ConfigDiffView />);

    expect(await screen.findByText(/2 keys drift on 1 node\./)).toHaveTextContent('1 expected difference set aside.');
    expect(screen.getByRole('radio', { name: 'Drift' })).toBeChecked();

    const table = await screen.findByRole('grid', { name: 'Configuration keys' });
    expect(within(table).getByRole('row', { name: /\/MaxDiskUsage/ })).toBeInTheDocument();
    expect(within(table).getByRole('row', { name: /\/orders\.#\/maxSizeBytes/ })).toBeInTheDocument();
    // Expected, unclassified and agreeing keys are one switch away, not in the list.
    expect(within(table).queryByRole('row', { name: /\/Name/ })).toBeNull();
    expect(within(table).queryByRole('row', { name: /\/TotalMessageCount/ })).toBeNull();
    expect(within(table).queryByRole('row', { name: /\/JournalType/ })).toBeNull();
  });

  it('states the majority and each outlier in words, with its value or that it is missing', async () => {
    serve();
    renderWithProviders(<ConfigDiffView />);

    const differs = await screen.findByRole('row', { name: /\/MaxDiskUsage/ });
    expect(differs).toHaveTextContent('90');
    expect(differs).toHaveTextContent('broker-3 differs: 80');
    expect(within(differs).getByText('different')).toBeInTheDocument();

    const missing = screen.getByRole('row', { name: /\/orders\.#\/maxSizeBytes/ });
    expect(missing).toHaveTextContent('1024');
    expect(missing).toHaveTextContent('broker-3 missing');
    expect(within(missing).getByText('missing on some')).toBeInTheDocument();
  });

  it('says there is no majority and lists every value with its nodes', async () => {
    search = { view: 'expected' };
    serve();
    renderWithProviders(<ConfigDiffView />);

    const row = await screen.findByRole('row', { name: /\/Name/ });
    expect(row).toHaveTextContent('no majority');
    expect(row).toHaveTextContent('1 on broker-1 · 2 on broker-2 · 3 on broker-3');
    expect(within(row).getByText('expected')).toBeInTheDocument();
    expect(screen.getByRole('radio', { name: 'Expected' })).toBeChecked();
  });

  it('lists every key, agreeing and unclassified ones included, under All keys', async () => {
    search = { view: 'all' };
    serve();
    renderWithProviders(<ConfigDiffView />);

    const unclassified = await screen.findByRole('row', { name: /\/TotalMessageCount/ });
    expect(within(unclassified).getByText('unclassified')).toBeInTheDocument();
    const agreeing = screen.getByRole('row', { name: /\/JournalType/ });
    expect(within(agreeing).getByText('same')).toBeInTheDocument();
  });

  it('writes the chosen view into the address, and omits the default', async () => {
    serve();
    const user = userEvent.setup();
    renderWithProviders(<ConfigDiffView />);

    await screen.findByRole('row', { name: /\/MaxDiskUsage/ });
    await user.click(screen.getByRole('radio', { name: 'All keys' }));
    expect(lastSearch()).toEqual({ view: 'all' });
  });

  it('leaves the view out of the address when it returns to drift', async () => {
    search = { view: 'all' };
    serve();
    const user = userEvent.setup();
    renderWithProviders(<ConfigDiffView />);

    await screen.findByRole('row', { name: /\/JournalType/ });
    await user.click(screen.getByRole('radio', { name: 'Drift' }));
    expect(lastSearch()).toEqual({ view: undefined });
  });

  it('says a cluster with no drift is clean, counting the expected differences it set aside, and lists no rows', async () => {
    serve(cleanDiff());
    renderWithProviders(<ConfigDiffView />);

    expect(await screen.findByText(/No key drifts across the 3 nodes compared\./)).toHaveTextContent(
      '1 expected difference set aside.',
    );
    expect(screen.getByText('Nothing drifts')).toBeInTheDocument();
    expect(screen.queryByRole('row', { name: /\/Name/ })).toBeNull();
  });

  it('says a search that matches nothing is filtered, not clean, and clears it', async () => {
    search = { q: 'zzz' };
    serve();
    const user = userEvent.setup();
    renderWithProviders(<ConfigDiffView />);

    expect(await screen.findByText('No key matches')).toBeInTheDocument();
    expect(screen.queryByText('Nothing drifts')).toBeNull();
    await user.click(screen.getByRole('button', { name: 'Clear filters' }));
    expect(lastSearch()).toEqual({ q: undefined, nodes: undefined });
    expect(screen.getByRole('textbox', { name: 'Search keys and values' })).toHaveValue('');
  });

  it('searches the keys and the values, and keeps the text in the address', async () => {
    serve();
    const user = userEvent.setup();
    renderWithProviders(<ConfigDiffView />);

    await screen.findByRole('row', { name: /\/MaxDiskUsage/ });
    await user.type(screen.getByRole('textbox', { name: 'Search keys and values' }), '1024');

    expect(await screen.findByRole('row', { name: /\/orders\.#\/maxSizeBytes/ })).toBeInTheDocument();
    expect(screen.queryByRole('row', { name: /\/MaxDiskUsage/ })).toBeNull();
    await waitFor(() => expect(lastSearch()).toEqual({ q: '1024' }));
  });

  it('keeps the keys that differ on the chosen node, and writes the node into the address', async () => {
    const user = userEvent.setup();
    serve(
      diff({
        sections: [
          {
            section: 'broker',
            label: 'Broker',
            keys: [diff().sections[0].keys[0], { ...diff().sections[0].keys[0], key: '/OtherDrift' }],
          },
        ],
      }),
    );
    search = { nodes: ['n-a'] };
    const { unmount } = renderWithProviders(<ConfigDiffView />);
    // broker-1 holds the majority on every key, so it differs on none.
    expect(await screen.findByText('No key matches')).toBeInTheDocument();
    unmount();

    search = { nodes: ['n-c'] };
    renderWithProviders(<ConfigDiffView />);
    expect(await screen.findByRole('row', { name: /\/MaxDiskUsage/ })).toBeInTheDocument();
    expect(screen.getByRole('row', { name: /\/OtherDrift/ })).toBeInTheDocument();

    await user.click(screen.getByRole('combobox', { name: 'Differs on node' }));
    await user.click(await screen.findByRole('option', { name: 'broker-1' }));
    expect(lastSearch()).toEqual({ nodes: ['n-c', 'n-a'] });
  });

  it('names a node that did not answer, with its reason, and says the others were compared without it', async () => {
    serve(
      diff({
        nodes: [
          node(A),
          node(B),
          node(C, { available: false, unavailableKind: 'UNREACHABLE', unavailableReason: 'connection refused' }),
        ],
        sections: [{ section: 'broker', label: 'Broker', keys: [same('/JournalType', 'NIO')] }],
        summary: { driftKeys: 0, driftNodes: 0, expectedKeys: 0 },
      }),
    );
    renderWithProviders(<ConfigDiffView />);

    expect(await screen.findByText('broker-3 unavailable')).toBeInTheDocument();
    expect(screen.getByText('connection refused')).toBeInTheDocument();
    expect(screen.getByText(/No key drifts across the 2 nodes compared\./)).toHaveTextContent(
      'broker-3 did not answer, so it was not compared.',
    );
    // A clean result over fewer nodes is not a clean cluster: the empty table says so.
    expect(screen.getByText('Nothing listed for the nodes that answered')).toBeInTheDocument();
    expect(screen.queryByText('Nothing drifts')).toBeNull();
  });

  it('states why no comparison was made when fewer than two nodes answered, naming each node and reason', async () => {
    serve(
      diff({
        comparable: false,
        sections: [],
        nodes: [
          node(A),
          node(B, {
            available: false,
            unavailableKind: 'CREDENTIALS_REJECTED',
            unavailableReason: 'credentials rejected',
          }),
          node(C, { available: false, unavailableKind: 'UNREACHABLE', unavailableReason: 'connection refused' }),
        ],
        notes: ['Fewer than two nodes answered, so no comparison could be made.'],
      }),
    );
    renderWithProviders(<ConfigDiffView />);

    expect(await screen.findByText('No comparison shown')).toBeInTheDocument();
    expect(screen.getByText('Fewer than two nodes answered, so no comparison could be made.')).toBeInTheDocument();
    expect(screen.getByText(/credentials rejected/)).toBeInTheDocument();
    const unreachable = screen.getByRole('list', { name: 'Nodes that could not be reached' });
    expect(within(unreachable).getAllByRole('listitem')).toHaveLength(2);
    expect(screen.queryByRole('grid')).toBeNull();
  });

  it('shows the notes the comparison states, such as the address-setting cap', async () => {
    serve(diff({ notes: ['Compared 25 of 41 address settings (the default match "#" is always included).'] }));
    renderWithProviders(<ConfigDiffView />);

    expect(await screen.findByText(/Compared 25 of 41 address settings/)).toBeInTheDocument();
  });

  it('links to the declared configuration and says how the two differ', async () => {
    serve();
    renderWithProviders(<ConfigDiffView />);

    const link = await screen.findByRole('link', { name: 'declared configuration' });
    expect(link).toHaveAttribute('href', '/clusters/c1/artemis/configuration');
    expect(link.parentElement).toHaveTextContent('compares the nodes with each other');
    expect(link.parentElement).toHaveTextContent('every live node against what you declared');
  });

  it('states why the comparison failed when the request fails, and offers it again', async () => {
    serve(
      HttpResponse.json({ title: 'Cluster unreachable', detail: 'No node answered the comparison.' }, { status: 502 }),
    );
    renderWithProviders(<ConfigDiffView />);

    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent('Rahdar failed to complete the request');
    expect(within(alert).getByText('No node answered the comparison.')).toBeInTheDocument();
    expect(within(alert).getByRole('button', { name: 'Retry' })).toBeEnabled();
  });

  it('is one page with a single h1', async () => {
    serve();
    renderWithProviders(<ConfigDiffView />);

    expect(await screen.findByRole('heading', { level: 1, name: 'Config diff' })).toBeInTheDocument();
    expect(screen.getAllByRole('heading', { level: 1 })).toHaveLength(1);
  });
});
