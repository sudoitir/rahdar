import { describe, expect, it, vi } from 'vitest';
import { http, HttpResponse } from 'msw';
import { screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

import { renderWithProviders } from '../../test/render.tsx';
import { server } from '../../test/setup.ts';
import type { ConfigApplyHistoryView, ConfigDocumentView, ConfigRevisionView } from './api.ts';
import { declaration, halted, plan } from './fixtures.ts';
import { paged } from '../../kernel/api/paging.ts';

vi.mock('@tanstack/react-router', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@tanstack/react-router')>()),
  Link: ({ children, to, ...rest }: { children: React.ReactNode; to: string }) => (
    <a href={to} {...rest}>
      {children}
    </a>
  ),
}));

const { HistoryTab } = await import('./HistoryTab.tsx');

const EMPTY: ConfigDocumentView = {
  version: 1,
  addresses: [],
  addressSettings: [],
  securitySettings: [],
  diverts: [],
  bridges: [],
};

const revision = (n: number, over: Partial<ConfigRevisionView> = {}): ConfigRevisionView => ({
  revision: n,
  createdAt: '2026-09-11T09:00:00Z',
  createdBy: 'admin',
  source: 'EDIT',
  note: null,
  document: declaration().document,
  ...over,
});

const apply = (id: number, over: Partial<ConfigApplyHistoryView> = {}): ConfigApplyHistoryView => ({
  id,
  startedAt: '2026-09-11T10:00:00Z',
  revisionId: 3,
  outcome: 'APPLIED',
  summary: 'Applied to 2 nodes.',
  actor: 'admin',
  dryRun: false,
  auditEventId: 10,
  ...over,
});

function serve(revisions: ConfigRevisionView[], applies: ConfigApplyHistoryView[]) {
  server.use(
    http.get('*/api/v1/clusters/c1/artemis/config/revisions', () => HttpResponse.json(paged(revisions))),
    http.get('*/api/v1/clusters/c1/artemis/config/applies', () => HttpResponse.json(paged(applies))),
  );
}

describe('HistoryTab revisions', () => {
  it('lists each revision with who saved it and its source in words, marking the current one', async () => {
    serve(
      [
        revision(3),
        revision(2, { source: 'ADOPTED_FROM_CLUSTER', createdBy: 'ops', note: 'first adoption' }),
        revision(1, { note: null }),
      ],
      [],
    );
    renderWithProviders(<HistoryTab declaration={declaration()} />);

    const row2 = await screen.findByRole('row', { name: /adopted from cluster/ });
    expect(within(row2).getByText('ops')).toBeInTheDocument();
    expect(within(row2).getByText('first adoption')).toBeInTheDocument();
    expect(within(row2).getByRole('button', { name: 'Compare with current' })).toHaveAttribute(
      'aria-expanded',
      'false',
    );
    // The current revision is a statement, not a button: comparing it with itself is meaningless.
    expect(screen.getAllByRole('button', { name: 'Compare with current' })).toHaveLength(2);
    expect(screen.getByText('current')).toBeInTheDocument();
  });

  it('says nothing was applied when there is no apply history', async () => {
    serve([revision(3)], []);
    renderWithProviders(<HistoryTab declaration={declaration()} />);

    expect(await screen.findByText('Nothing has been applied or previewed yet.')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Details' })).toBeNull();
  });

  it('reads a comparison item by item: added, removed and changed rows, with the item named once per group', async () => {
    const earlier: ConfigDocumentView = {
      ...EMPTY,
      // Same address as the current revision, so it contributes no row.
      addresses: declaration().document.addresses,
      addressSettings: [{ match: 'orders.#', values: { addressFullMessagePolicy: 'DROP', deadLetterAddress: 'DLQ' } }],
      diverts: [
        { name: 'audit-copy', address: 'orders.request', forwardingAddress: 'orders.audit', exclusive: false },
      ] as ConfigDocumentView['diverts'],
    };
    serve([revision(3), revision(2, { document: earlier })], []);
    const user = userEvent.setup();
    renderWithProviders(<HistoryTab declaration={declaration()} />);

    const compare = await screen.findByRole('button', { name: 'Compare with current' });
    await user.click(compare);

    expect(screen.getByText('3 items differ between revision 2 and revision 3.')).toBeInTheDocument();
    expect(compare).toHaveAccessibleName('Hide comparison');
    expect(compare).toHaveAttribute('aria-expanded', 'true');

    // A divert only the earlier revision declared is one row stating so.
    const divertRow = screen.getByRole('row', { name: /divert audit-copy/ });
    expect(divertRow).toHaveTextContent('declared (3 keys)');
    expect(divertRow).toHaveTextContent('not declared');
    // A security setting only the current revision declares is the mirror image.
    const securityRow = screen.getByRole('row', { name: /security-setting orders\.#/ });
    expect(securityRow).toHaveTextContent('not declared');
    expect(securityRow).toHaveTextContent('declared (1 key)');
    // A key that changed lists both sides; a key one side lacks says "not declared".
    const policyRow = screen.getByRole('row', { name: /address-full-message-policy/ });
    expect(policyRow).toHaveTextContent('DROP');
    expect(policyRow).toHaveTextContent('PAGE');
    // The item name is written once for its group: the second key's row leaves it blank.
    const sizeRow = screen.getByRole('row', { name: /max-size-bytes/ });
    expect(sizeRow).not.toHaveTextContent('address-setting');
    expect(sizeRow).toHaveTextContent('not declared');
    // ... and the mirror: a key only the earlier revision declared.
    expect(screen.getByRole('row', { name: /dead-letter-address/ })).toHaveTextContent('DLQ');
  });

  it('collapses a comparison and states when two revisions declare the same thing', async () => {
    serve([revision(3), revision(2)], []);
    const user = userEvent.setup();
    renderWithProviders(<HistoryTab declaration={declaration()} />);

    const compare = await screen.findByRole('button', { name: 'Compare with current' });
    await user.click(compare);
    expect(screen.getByText('Revision 2 and revision 3 declare the same thing.')).toBeInTheDocument();
    // Nothing differs, so there is no table to read.
    expect(screen.queryByRole('columnheader', { name: 'Item' })).toBeNull();

    await user.click(compare);
    expect(screen.queryByText(/declare the same thing/)).toBeNull();
    expect(compare).toHaveAccessibleName('Compare with current');
  });

  it('counts one differing item in the singular', async () => {
    const earlier: ConfigDocumentView = { ...declaration().document, securitySettings: [] };
    serve([revision(3), revision(1, { document: earlier })], []);
    const user = userEvent.setup();
    renderWithProviders(<HistoryTab declaration={declaration()} />);

    await user.click(await screen.findByRole('button', { name: 'Compare with current' }));
    expect(screen.getByText(/^1 item differs? between revision 1 and revision 3\.$/)).toBeInTheDocument();
  });

  it('compares nothing while the current revision is missing from the list', async () => {
    serve([revision(2)], []);
    const user = userEvent.setup();
    renderWithProviders(<HistoryTab declaration={declaration()} />);

    await user.click(await screen.findByRole('button', { name: 'Compare with current' }));
    expect(screen.getByText('Revision 2 and revision 3 declare the same thing.')).toBeInTheDocument();
  });
});

describe('HistoryTab applies', () => {
  it('states each apply outcome in words and links only real applies to the audit log', async () => {
    serve(
      [revision(3)],
      [
        apply(4, { outcome: 'DRY_RUN', dryRun: true, auditEventId: null, summary: 'Would apply 2 steps.' }),
        apply(3, { outcome: 'HALTED', summary: null, actor: 'ops' }),
        apply(2, { outcome: 'FAILED' }),
        apply(1, { outcome: 'APPLIED' }),
      ],
    );
    renderWithProviders(<HistoryTab declaration={declaration()} />);

    const preview = await screen.findByRole('row', { name: /preview/ });
    expect(within(preview).getByText('Would apply 2 steps.')).toBeInTheDocument();
    expect(within(preview).queryByRole('link', { name: 'audit' })).toBeNull();
    expect(screen.getByRole('row', { name: /halted/ })).toHaveTextContent('ops');
    expect(screen.getByRole('row', { name: /failed/ })).toBeInTheDocument();
    const applied = screen.getByRole('row', { name: /applied/ });
    expect(within(applied).getByRole('link', { name: 'audit' })).toHaveAttribute(
      'href',
      '/clusters/c1/audit?action=APPLY_BROKER_CONFIG',
    );
    expect(screen.getAllByRole('link', { name: 'audit' })).toHaveLength(3);
  });

  it('opens an apply as the same result the operator confirmed, and closes it again', async () => {
    const detail = halted();
    server.use(
      http.get('*/api/v1/clusters/c1/artemis/config/applies/3', () =>
        HttpResponse.json({
          apply: apply(3, { outcome: 'HALTED', summary: detail.summary }),
          plan: detail.plan,
          nodes: detail.nodes,
        }),
      ),
    );
    serve([revision(3)], [apply(3, { outcome: 'HALTED', summary: detail.summary })]);
    const user = userEvent.setup();
    renderWithProviders(<HistoryTab declaration={declaration()} />);

    const details = await screen.findByRole('button', { name: 'Details' });
    await user.click(details);

    expect(await screen.findByText('Halted — applied to some nodes and not others')).toBeInTheDocument();
    expect(screen.getAllByText(/AMQ229001: invalid JSON/).length).toBeGreaterThan(0);
    expect(details).toHaveAccessibleName('Hide');
    expect(details).toHaveAttribute('aria-expanded', 'true');

    await user.click(details);
    expect(screen.queryByText('Halted — applied to some nodes and not others')).toBeNull();
    expect(details).toHaveAccessibleName('Details');
  });

  it('opens a previewed apply without a summary as an empty one', async () => {
    const p = plan();
    server.use(
      http.get('*/api/v1/clusters/c1/artemis/config/applies/9', () =>
        HttpResponse.json({
          apply: apply(9, { outcome: 'DRY_RUN', dryRun: true, summary: null, auditEventId: null }),
          plan: p.plan,
          nodes: p.nodes,
        }),
      ),
    );
    serve([revision(3)], [apply(9, { outcome: 'DRY_RUN', dryRun: true, summary: null, auditEventId: null })]);
    const user = userEvent.setup();
    renderWithProviders(<HistoryTab declaration={declaration()} />);

    await user.click(await screen.findByRole('button', { name: 'Details' }));
    expect(await screen.findByText(/Would apply 2 steps to 2 live nodes, canary first/)).toBeInTheDocument();
  });
});

describe('HistoryTab states', () => {
  it('names each table, and the revision number heads its row', async () => {
    serve([revision(3), revision(2)], [apply(1)]);
    renderWithProviders(<HistoryTab declaration={declaration()} />);

    const revisions = await screen.findByRole('table', { name: 'Revisions' });
    expect(await within(revisions).findByRole('rowheader', { name: '3' })).toBeInTheDocument();
    expect(screen.getByRole('heading', { level: 2, name: 'Applies' })).toBeInTheDocument();
    expect(screen.getByRole('table', { name: 'Applies' })).toBeInTheDocument();
  });

  it('says what a revision is and how the first one is saved when there is none', async () => {
    serve([], []);
    renderWithProviders(<HistoryTab declaration={declaration({ declared: false, revision: 0 })} />);

    expect(await screen.findByText('No revision has been saved yet')).toBeInTheDocument();
    expect(screen.getByText(/A revision is one saved copy of the declaration/)).toBeInTheDocument();
  });

  it('says what is loading, then draws both lists at once so the section below does not move', async () => {
    server.use(
      http.get('*/api/v1/clusters/c1/artemis/config/revisions', async () => {
        await new Promise((r) => setTimeout(r, 100));
        return HttpResponse.json(paged([revision(3)]));
      }),
      http.get('*/api/v1/clusters/c1/artemis/config/applies', () => HttpResponse.json(paged([apply(1)]))),
    );
    renderWithProviders(<HistoryTab declaration={declaration()} />);

    expect(screen.getByRole('status')).toHaveTextContent('Loading the history');
    // The applies came back first, and are held until the revisions are there too.
    expect(screen.queryByRole('table', { name: 'Applies' })).toBeNull();
    expect(await screen.findByRole('rowheader', { name: '3' })).toBeInTheDocument();
    expect(screen.getByRole('table', { name: 'Applies' })).toBeInTheDocument();
  });

  it('states why the revisions could not be listed and lists them again on retry', async () => {
    let attempts = 0;
    server.use(
      http.get('*/api/v1/clusters/c1/artemis/config/revisions', () => {
        attempts += 1;
        return attempts === 1
          ? HttpResponse.json({ title: 'Down', detail: 'The history is not answering.' }, { status: 503 })
          : HttpResponse.json(paged([revision(3)]));
      }),
      http.get('*/api/v1/clusters/c1/artemis/config/applies', () => HttpResponse.json(paged([]))),
    );
    const user = userEvent.setup();
    renderWithProviders(<HistoryTab declaration={declaration()} />);

    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent('The history is not answering.');
    await user.click(within(alert).getByRole('button', { name: 'Retry' }));
    expect(await screen.findByRole('rowheader', { name: '3' })).toBeInTheDocument();
  });

  it('says why an apply could not be opened instead of showing nothing', async () => {
    server.use(
      http.get('*/api/v1/clusters/c1/artemis/config/applies/3', () =>
        HttpResponse.json({ title: 'Gone', detail: 'That apply is no longer recorded.' }, { status: 404 }),
      ),
    );
    serve([revision(3)], [apply(3)]);
    const user = userEvent.setup();
    renderWithProviders(<HistoryTab declaration={declaration()} />);

    await user.click(await screen.findByRole('button', { name: 'Details' }));

    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent('That apply is no longer recorded.');
    expect(screen.getByRole('heading', { level: 3, name: 'Apply 3' })).toBeInTheDocument();
  });
});
