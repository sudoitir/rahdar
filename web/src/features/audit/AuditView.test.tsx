import { beforeEach, describe, expect, it, vi } from 'vitest';
import { http, HttpResponse } from 'msw';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

import { renderWithProviders } from '../../test/render.tsx';
import { server } from '../../test/setup.ts';
import { paged } from '../../kernel/api/paging.ts';

let search: Record<string, unknown> = {};
const navigate = vi.fn();
vi.mock('@tanstack/react-router', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@tanstack/react-router')>()),
  useParams: () => ({ clusterId: 'c1' }),
  useSearch: () => search,
  useNavigate: () => navigate,
}));

const { AuditView } = await import('./AuditView.tsx');

function row(over: Record<string, unknown> = {}) {
  return {
    id: 5,
    ts: '2026-09-04T10:00:00.000Z',
    username: 'anonymous',
    sourceIp: '10.0.0.1',
    requestId: 'req-1',
    action: 'DELETE_MESSAGES',
    targetType: 'QUEUE',
    targetName: 'ORDERS',
    affectedCount: null,
    outcome: 'FAILURE',
    dryRun: false,
    params: '{"filter":"x"}',
    error: 'broker refused',
    nodeId: null,
    ...over,
  };
}

describe('AuditView', () => {
  beforeEach(() => {
    search = {};
    navigate.mockClear();
  });

  it('is one page named Audit log', async () => {
    server.use(
      http.get('*/api/v1/clusters/c1/audit', () =>
        HttpResponse.json({ data: [row()], count: 1, page: 1, pageSize: 100 }),
      ),
    );
    renderWithProviders(<AuditView />);

    expect(await screen.findByRole('heading', { level: 1, name: 'Audit log' })).toBeInTheDocument();
    expect(screen.getAllByRole('heading', { level: 1 })).toHaveLength(1);
  });

  it('says the list was read from a replica that may lag, and only then', async () => {
    let mayLag = true;
    server.use(
      http.get('*/api/v1/clusters/c1/audit', () =>
        HttpResponse.json({ data: [row()], count: 1, page: 1, pageSize: 100, hasNext: false, mayLag }),
      ),
    );
    const { unmount } = renderWithProviders(<AuditView />);

    expect(await screen.findByText('Read from a replica')).toBeInTheDocument();
    expect(screen.getByText(/can be a few seconds behind/)).toBeInTheDocument();
    unmount();

    mayLag = false;
    renderWithProviders(<AuditView />);
    expect(await screen.findByText('failure')).toBeInTheDocument();
    expect(screen.queryByText('Read from a replica')).not.toBeInTheDocument();
  });

  it('renders the outcome word for a failed row and a click puts it in the address', async () => {
    server.use(
      http.get('*/api/v1/clusters/c1/audit', () =>
        HttpResponse.json({ data: [row()], count: 1, page: 1, pageSize: 100 }),
      ),
    );
    const user = userEvent.setup();
    renderWithProviders(<AuditView />);

    expect(await screen.findByText('failure')).toBeInTheDocument();
    await user.click(await screen.findByRole('gridcell', { name: 'DELETE_MESSAGES' }));
    const update = navigate.mock.calls[0][0].search as (prev: object) => object;
    expect(update({})).toEqual({ event: 5 });
  });

  it('shows the params and error of the event the address names', async () => {
    search = { event: 5 };
    server.use(
      http.get('*/api/v1/clusters/c1/audit', () =>
        HttpResponse.json({ data: [row()], count: 1, page: 1, pageSize: 100 }),
      ),
    );
    renderWithProviders(<AuditView />);

    const dialog = await screen.findByRole('dialog', { name: 'DELETE_MESSAGES' });
    // The outcome is a word, the error its own term, and the request and source are facts beside them.
    expect(await within(dialog).findByText('broker refused')).toBeInTheDocument();
    expect(within(dialog).getByText('Error')).toBeInTheDocument();
    expect(within(dialog).getByText('failure')).toBeInTheDocument();
    expect(within(dialog).getByText('req-1')).toBeInTheDocument();
    expect(within(dialog).getByText('10.0.0.1')).toBeInTheDocument();
    expect(within(dialog).getByRole('region', { name: 'Audit event parameters' })).toHaveTextContent('"filter":"x"');
  });

  it('opens an event that is not on the loaded page by asking for it', async () => {
    search = { event: 9 };
    server.use(
      http.get('*/api/v1/clusters/c1/audit', () =>
        HttpResponse.json({ data: [row()], count: 1, page: 1, pageSize: 100 }),
      ),
      http.get('*/api/v1/clusters/c1/audit/9', () =>
        HttpResponse.json(row({ id: 9, action: 'PURGE_QUEUE', error: 'purge refused' })),
      ),
    );
    renderWithProviders(<AuditView />);

    expect(await screen.findByRole('dialog', { name: 'PURGE_QUEUE' })).toBeInTheDocument();
    expect(await screen.findByText('purge refused')).toBeInTheDocument();
  });

  it('says an unknown event no longer exists', async () => {
    search = { event: 404 };
    server.use(
      http.get('*/api/v1/clusters/c1/audit', () =>
        HttpResponse.json({ data: [row()], count: 1, page: 1, pageSize: 100 }),
      ),
      http.get('*/api/v1/clusters/c1/audit/404', () =>
        HttpResponse.json(
          { type: 'about:blank', title: 'Resource not found', status: 404, detail: 'audit event 404 does not exist.' },
          { status: 404 },
        ),
      ),
    );
    renderWithProviders(<AuditView />);

    expect(await screen.findByText('This audit event no longer exists')).toBeInTheDocument();
  });

  it('copies a link to the event from its row menu', async () => {
    server.use(
      http.get('*/api/v1/clusters/c1/audit', () =>
        HttpResponse.json({ data: [row()], count: 1, page: 1, pageSize: 100 }),
      ),
    );
    const user = userEvent.setup();
    renderWithProviders(<AuditView />);

    await user.click(await screen.findByRole('button', { name: /^Actions for DELETE_MESSAGES/ }));
    await user.click(await screen.findByRole('menuitem', { name: 'Copy link' }));

    expect(await navigator.clipboard.readText()).toBe(`${window.location.origin}/clusters/c1/audit?event=5`);
  });

  it('filters to the events of one run, says so, and offers the way back', async () => {
    search = { parentId: 7 };
    let asked: string | null = null;
    server.use(
      http.get('*/api/v1/clusters/c1/audit', ({ request }) => {
        asked = new URL(request.url).searchParams.get('parentId');
        return HttpResponse.json({ data: [row({ id: 8, parentId: 7 })], count: 1, page: 1, pageSize: 100 });
      }),
    );
    const user = userEvent.setup();
    renderWithProviders(<AuditView />);

    // The banner comes from the address, so it can show before the list is asked for; wait for the request itself.
    expect(await screen.findByText(/Showing the events that belong to audit event 7/)).toBeInTheDocument();
    await waitFor(() => expect(asked).toBe('7'));
    await user.click(screen.getByRole('button', { name: 'Show every event' }));
    expect(navigate).toHaveBeenCalled();
    search = {};
  });

  it('links a child event to the run it belongs to', async () => {
    search = { event: 8 };
    server.use(
      http.get('*/api/v1/clusters/c1/audit', () =>
        HttpResponse.json({ data: [row({ id: 8, parentId: 7 })], count: 1, page: 1, pageSize: 100 }),
      ),
    );
    const user = userEvent.setup();
    renderWithProviders(<AuditView />);

    expect(await screen.findByRole('dialog', { name: 'DELETE_MESSAGES' })).toBeInTheDocument();
    await user.click(
      await screen.findByRole('button', { name: 'Show the operation this belongs to, with all its parts' }),
    );
    const call = navigate.mock.calls.at(-1)![0] as { search: (p: object) => Record<string, unknown> };
    expect(call.search({ event: 8 })).toEqual({ parentId: 7, event: undefined, page: undefined });
  });
});

function page(rows: object[], count = rows.length) {
  return http.get('*/api/v1/clusters/c1/audit', () => HttpResponse.json({ data: rows, count, page: 1, pageSize: 100 }));
}

function grants(permissions: string[]) {
  return http.get('*/api/v1/auth/me', () =>
    HttpResponse.json({
      id: 'u1',
      username: 'admin',
      mustChangePassword: false,
      grants: [{ scopeType: 'GLOBAL', scopeId: null, permissions }],
    }),
  );
}

/** What the last navigate() call does to the address it starts from. */
function nextSearch(prev: Record<string, unknown> = {}) {
  const call = navigate.mock.lastCall![0] as { search: (p: Record<string, unknown>) => Record<string, unknown> };
  return call.search(prev);
}

describe('AuditView rows and states', () => {
  beforeEach(() => {
    search = {};
    navigate.mockReset();
  });

  it('says each outcome in a word, marks a dry run, and shows a missing count or target as a dash', async () => {
    server.use(
      grants([]),
      page([
        row({
          id: 1,
          username: 'ann',
          outcome: 'SUCCESS',
          action: 'PURGE_QUEUE',
          dryRun: true,
          affectedCount: 12,
          targetName: 'ORDERS',
        }),
        row({ id: 2, outcome: 'PENDING', action: 'MOVE_MESSAGES', targetName: null, username: null }),
        row({
          id: 3,
          username: 'bob',
          outcome: 'REFUSED',
          action: 'ACCESS_REFUSED',
          affectedCount: 50,
          targetName: 'orders.in',
        }),
        row({ id: 4, username: 'cy', outcome: 'IN_DOUBT', action: 'SEND_MESSAGE', targetName: 'orders.out' }),
      ]),
    );
    renderWithProviders(<AuditView />);

    const grid = await screen.findByRole('grid', { name: 'Audit events' });
    expect(await within(grid).findByText('success')).toBeInTheDocument();
    expect(within(grid).getByText('pending')).toBeInTheDocument();
    expect(within(grid).getByText('refused')).toBeInTheDocument();
    // A send whose confirmation never came back is in doubt, never pending or a success.
    expect(within(grid).getByText('in doubt')).toBeInTheDocument();
    expect(within(grid).getByText('50')).toBeInTheDocument();
    // The target is shortened in the middle, so the whole value, dry-run mark included, is read from one place.
    expect(within(grid).getByText('ORDERS · dry run')).toBeInTheDocument();
    expect(within(grid).getByText('12')).toBeInTheDocument();
    expect(within(grid).getAllByText('—').length).toBeGreaterThanOrEqual(2);
    expect(within(grid).getByText('ann')).toBeInTheDocument();
    expect(within(grid).getByText('anonymous')).toBeInTheDocument();
    expect(screen.getByText('1–4 of 4 audit events')).toBeInTheDocument();
  });

  it('teaches what is recorded here when no event matches', async () => {
    server.use(grants([]), page([]));
    renderWithProviders(<AuditView />);

    expect(await screen.findByText('No audit events yet')).toBeInTheDocument();
    expect(screen.getByText(/records every message operation, purge and cluster change/)).toBeInTheDocument();
    // The empty state is beside the grid, never in it: the grid has only its header row.
    expect(screen.getByRole('grid', { name: 'Audit events' })).toHaveAttribute('aria-rowcount', '1');
  });

  it('says a filter excludes every event, and clearing it drops every filter from the address', async () => {
    search = { user: 'ann', outcome: 'FAILURE', parentId: 7 };
    server.use(grants([]), page([]));
    const user = userEvent.setup();
    renderWithProviders(<AuditView />);

    expect(await screen.findByText('No audit event matches these filters')).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Clear filters' }));
    expect(nextSearch({ user: 'ann', outcome: 'FAILURE', parentId: 7 })).toEqual({
      user: undefined,
      action: undefined,
      outcome: undefined,
      parentId: undefined,
      page: undefined,
    });
  });

  it('states why the log could not be read instead of showing it empty', async () => {
    server.use(
      grants([]),
      http.get('*/api/v1/clusters/c1/audit', () =>
        HttpResponse.json({ title: 'Audit unavailable', detail: 'The database did not answer.' }, { status: 500 }),
      ),
    );
    renderWithProviders(<AuditView />);

    expect(await screen.findByRole('alert')).toHaveTextContent('Rahdar failed to complete the request');
    expect(screen.getByRole('button', { name: 'Retry' })).toBeInTheDocument();
  });

  it('pages back and forth, leaving the page out of the address on the first', async () => {
    search = { page: 2 };
    server.use(grants([]), page([row()], 250));
    const user = userEvent.setup();
    renderWithProviders(<AuditView />);

    expect(await screen.findByText('101–200 of 250 audit events')).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Next' }));
    expect(nextSearch({ user: 'ann' })).toEqual({ user: 'ann', page: 3 });
    await user.click(screen.getByRole('button', { name: 'Previous' }));
    expect(nextSearch()).toEqual({ page: undefined });
  });
});

describe('AuditView filters', () => {
  beforeEach(() => {
    search = {};
    navigate.mockReset();
  });

  it('commits a typed user to the address once typing pauses, for someone who cannot list users', async () => {
    server.use(grants([]), page([row()]));
    const user = userEvent.setup();
    renderWithProviders(<AuditView />);

    const field = await screen.findByRole('textbox', { name: 'Filter by user' });
    await user.type(field, 'ann');
    expect(field).toHaveFocus();

    await waitFor(() => expect(navigate).toHaveBeenCalledTimes(1));
    expect(nextSearch({ page: 4 })).toEqual({ page: undefined, user: 'ann' });
  });

  it('offers the known users instead of a text box to someone who administers users', async () => {
    server.use(
      grants(['user:admin']),
      http.get('*/api/v1/users', () =>
        HttpResponse.json(
          paged([
            { id: 'u1', username: 'ann' },
            { id: 'u2', username: 'bob' },
          ]),
        ),
      ),
      page([row()]),
    );
    const user = userEvent.setup();
    renderWithProviders(<AuditView />);

    await user.click(await screen.findByPlaceholderText('Any user'));
    await user.click(await screen.findByRole('option', { name: 'bob' }));

    expect(nextSearch({ page: 2 })).toEqual({ page: undefined, user: 'bob' });
    expect(screen.queryByRole('textbox', { name: 'Filter by user' })).not.toBeInTheDocument();
  });

  it('commits an action and an outcome as soon as they are chosen, showing the ones already in the address', async () => {
    search = { action: 'PURGE_QUEUE', outcome: 'FAILURE' };
    server.use(grants([]), page([row()]));
    const user = userEvent.setup();
    renderWithProviders(<AuditView />);

    // In words on screen; the recorded value in the address.
    const action = await screen.findByPlaceholderText('Any action');
    expect(action).toHaveValue('Purge subscription');
    await user.click(action);
    await user.click(await screen.findByRole('option', { name: 'Move messages' }));
    expect(nextSearch()).toEqual({ action: 'MOVE_MESSAGES', page: undefined });

    const outcome = screen.getByPlaceholderText('Any outcome');
    expect(outcome).toHaveValue('Failure');
    await user.click(outcome);
    await user.click(await screen.findByRole('option', { name: 'Success' }));
    expect(nextSearch()).toEqual({ outcome: 'SUCCESS', page: undefined });
  });

  it('narrows to a time range from a preset, by the instant it starts', async () => {
    server.use(grants([]), page([row()]));
    const user = userEvent.setup();
    renderWithProviders(<AuditView />);

    await user.click(await screen.findByPlaceholderText('Any time'));
    const before = Date.now();
    await user.click(await screen.findByRole('option', { name: 'Last 24 hours' }));
    const next = nextSearch() as { from: string; to: undefined };
    expect(next.to).toBeUndefined();
    expect(before - Date.parse(next.from)).toBeGreaterThan(24 * 3_600_000 - 60_000);
    expect(before - Date.parse(next.from)).toBeLessThan(24 * 3_600_000 + 60_000);
  });

  it('names a range from a shared link as it is, and sends it with the query', async () => {
    search = { from: '2026-09-01T00:00:00Z' };
    const asked: string[] = [];
    server.use(
      grants([]),
      http.get('*/api/v1/clusters/c1/audit', ({ request }) => {
        asked.push(new URL(request.url).searchParams.get('from') ?? '');
        return HttpResponse.json({ data: [row()], count: 1, page: 1, pageSize: 100 });
      }),
    );
    renderWithProviders(<AuditView />);

    const time = (await screen.findByPlaceholderText('Any time')) as HTMLInputElement;
    expect(time.value).toMatch(/^Since 2026-0[89]-/);
    await waitFor(() => expect(asked).toContain('2026-09-01T00:00:00Z'));
  });
});

describe('AuditView event detail', () => {
  beforeEach(() => {
    search = {};
    navigate.mockReset();
  });

  it('shows a dry run with no target, params, error or source as the facts it has, without empty blocks', async () => {
    search = { event: 5 };
    server.use(
      grants([]),
      page([
        row({
          dryRun: true,
          targetName: null,
          username: null,
          params: null,
          error: null,
          requestId: null,
          sourceIp: null,
          parentId: null,
        }),
      ]),
    );
    renderWithProviders(<AuditView />);

    const dialog = await screen.findByRole('dialog', { name: 'DELETE_MESSAGES' });
    expect(within(dialog).getByText('anonymous')).toBeInTheDocument();
    expect(within(dialog).getByText('no target')).toBeInTheDocument();
    expect(within(dialog).getByText('Dry run: nothing was changed.')).toBeInTheDocument();
    // The request and the source are unknown: each term says so, instead of a blank.
    expect(within(dialog).getAllByText('—')).toHaveLength(2);
    expect(within(dialog).queryByText('Error')).not.toBeInTheDocument();
    expect(within(dialog).queryByRole('region', { name: 'Audit event parameters' })).not.toBeInTheDocument();
    expect(within(dialog).queryByText(/Show the operation this belongs to/)).not.toBeInTheDocument();
    expect(within(dialog).queryByText(/Show the event for each subscription/)).not.toBeInTheDocument();
  });

  it('offers the events of each subscription for a bulk run, and closing the drawer drops the event from the address', async () => {
    search = { event: 7 };
    server.use(grants([]), page([row({ id: 7, action: 'bulk.delete', targetName: 'cluster' })]));
    const user = userEvent.setup();
    renderWithProviders(<AuditView />);

    await user.click(await screen.findByRole('button', { name: 'Show the event for each subscription in this run' }));
    expect(nextSearch({ event: 7 })).toEqual({ parentId: 7, event: undefined, page: undefined });

    await user.keyboard('{Escape}');
    await waitFor(() => expect(navigate.mock.calls.length).toBeGreaterThan(1));
    expect(nextSearch({ event: 7, parentId: 7 })).toEqual({ event: undefined, parentId: 7 });
  });

  it('states why an event that is not on the page could not be loaded', async () => {
    search = { event: 9 };
    server.use(
      grants([]),
      page([row()]),
      http.get('*/api/v1/clusters/c1/audit/9', () =>
        HttpResponse.json({ title: 'Audit unavailable', detail: 'The database did not answer.' }, { status: 500 }),
      ),
    );
    renderWithProviders(<AuditView />);

    const dialog = await screen.findByRole('dialog', { name: 'Audit event' });
    expect(await within(dialog).findByRole('alert')).toHaveTextContent('Rahdar failed to complete the request');
  });

  it('shows a placeholder while an event that is not on the page loads', async () => {
    search = { event: 9 };
    server.use(
      grants([]),
      page([row()]),
      http.get('*/api/v1/clusters/c1/audit/9', async () => {
        await new Promise(() => {});
      }),
    );
    renderWithProviders(<AuditView />);

    const dialog = await screen.findByRole('dialog', { name: 'Audit event' });
    expect(within(dialog).queryByText(/no longer exists/)).not.toBeInTheDocument();
    expect(within(dialog).queryByRole('alert')).not.toBeInTheDocument();
  });

  it('clears the run filter with the one action that says so', async () => {
    search = { parentId: 7 };
    server.use(grants([]), page([row({ id: 8, parentId: 7 })]));
    const user = userEvent.setup();
    renderWithProviders(<AuditView />);

    await user.click(await screen.findByRole('button', { name: 'Show every event' }));

    expect(nextSearch({ parentId: 7, user: 'ann' })).toEqual({ user: 'ann', parentId: undefined, page: undefined });
  });
});

describe('AuditView export', () => {
  beforeEach(() => {
    search = {};
    navigate.mockClear();
  });

  it('offers each format as a download link carrying the filters in the address', async () => {
    search = { user: 'ann', outcome: 'FAILURE', from: '2026-09-04T00:00:00.000Z', page: 3, event: 5 };
    server.use(grants(['audit:export']), page([row()]));
    const user = userEvent.setup();
    renderWithProviders(<AuditView />);

    await user.click(await screen.findByRole('button', { name: 'Export' }));

    for (const [label, format] of [
      ['CSV', 'csv'],
      ['JSON', 'json'],
      ['NDJSON', 'ndjson'],
    ]) {
      const link = await screen.findByRole('menuitem', { name: label });
      const url = new URL(link.getAttribute('href') ?? '', 'http://rahdar');
      expect(url.pathname).toBe('/api/v1/clusters/c1/audit/export');
      expect(Object.fromEntries(url.searchParams)).toEqual({
        format,
        user: 'ann',
        outcome: 'FAILURE',
        from: '2026-09-04T00:00:00.000Z',
      });
      expect(link).toHaveAttribute('download');
    }
  });

  it('is disabled with its reason for someone who may not export', async () => {
    server.use(grants(['cluster:read']), page([row()]));
    const user = userEvent.setup();
    renderWithProviders(<AuditView />);

    await waitFor(() => expect(screen.getByRole('button', { name: 'Export' })).toBeDisabled());
    await user.click(screen.getByRole('button', { name: 'Why exporting the audit log is unavailable' }));

    expect(await screen.findByText(/Export the audit trail/)).toBeInTheDocument();
  });
});
