import { afterEach, describe, expect, it, vi } from 'vitest';
import { act, screen, waitFor, within } from '@testing-library/react';
import { Notifications, notifications } from '@mantine/notifications';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';

import { renderWithProviders } from '../../test/render.tsx';
import { server } from '../../test/setup.ts';
import type { TransferRunView as Run } from './api.ts';
import { clusterHandlers, END, meHandler, problem, run } from './fixtures.ts';
import { holdButton } from '../../test/hold.ts';

vi.mock('@tanstack/react-router', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@tanstack/react-router')>()),
  useParams: () => ({ clusterId: 'c1', runId: 'r1' }),
  Link: ({ children, to, search }: { children: React.ReactNode; to: string; search?: Record<string, string> }) => (
    <a href={search ? `${to}?${new URLSearchParams(search)}` : to}>{children}</a>
  ),
}));

const { TransferRunView } = await import('./TransferRunView.tsx');

afterEach(() => act(() => notifications.clean()));

function show(over: Partial<Run>, permissions?: string[]) {
  server.use(
    meHandler(permissions),
    ...clusterHandlers(),
    http.get('*/api/v1/clusters/c1/transfers/runs/r1', () => HttpResponse.json(run(over))),
  );
  return renderWithProviders(
    <>
      <Notifications />
      <TransferRunView />
    </>,
  );
}

const state = () => screen.findByRole('status', { name: 'Transfer state' });

describe('TransferRunView states in words', () => {
  it.each<[string, Partial<Run>, string]>([
    ['previewed', { state: 'PREVIEWED' }, 'Previewed and never started. Nothing was moved or sent.'],
    ['running with nothing held', { state: 'RUNNING' }, 'Running.'],
    [
      'waiting for room, with a message held',
      { state: 'WAITING_FOR_CAPACITY', held: 1 },
      'Waiting for the target to have room, and continuing by itself once it does. 1 message held in staging on node-a.',
    ],
    [
      'returning',
      { state: 'RETURNING', held: 40 },
      'Returning the held messages to orders. 40 messages held in staging on node-a.',
    ],
    ['succeeded as a copy', { state: 'SUCCEEDED', mode: 'COPY' }, 'Succeeded: every selected message was copied.'],
    [
      'partial with one message left',
      { state: 'PARTIAL', notTransferred: 1 },
      'Partial: 1 selected message could not be taken, because it was being delivered',
    ],
    [
      'stopped with staging',
      { state: 'STOPPED', held: 5 },
      'Stopped. 5 messages held in staging on node-a. Resume it, or return the held messages to the source.',
    ],
    ['stopped without staging', { state: 'STOPPED', mode: 'COPY' }, 'Stopped. Resume it.'],
    [
      'interrupted with staging',
      { state: 'INTERRUPTED', held: 5 },
      'Interrupted: Rahdar stopped while it ran. 5 messages held in staging on node-a. Nothing is lost; resume it or return it.',
    ],
    [
      'interrupted without staging',
      { state: 'INTERRUPTED', sameNode: true },
      'Interrupted: Rahdar stopped while it ran. Nothing is lost; resume it.',
    ],
    [
      'failed without staging',
      { state: 'FAILED', mode: 'COPY' },
      'This run failed. Nothing held is lost; resume it once the cause is fixed.',
    ],
    ['returned in the singular', { state: 'RETURNED', returned: 1 }, 'Returned: 1 held message is back on orders.'],
    ['returned in the plural', { state: 'RETURNED', returned: 3 }, 'Returned: 3 held messages are back on orders.'],
  ])('says a run that is %s', async (_name, over, sentence) => {
    show(over);
    expect(await state()).toHaveTextContent(sentence);
  });

  it('is one page with a single h1 and its sections below it', async () => {
    show({ state: 'RUNNING', held: 5, notes: ['Messages keep their order.'] });

    expect(await screen.findByRole('heading', { level: 1, name: 'Move 1,200 messages' })).toBeInTheDocument();
    expect(screen.getAllByRole('heading', { level: 1 })).toHaveLength(1);
    for (const name of ['State', 'Where each end stands', 'Good to know']) {
      expect(screen.getByRole('heading', { level: 2, name })).toBeInTheDocument();
    }
  });

  it('holds the page header while the run loads and when it cannot be read, with a retry', async () => {
    let calls = 0;
    server.use(
      meHandler(),
      ...clusterHandlers(),
      http.get('*/api/v1/clusters/c1/transfers/runs/r1', () => {
        calls += 1;
        return calls === 1
          ? problem(503, 'upstream', 'Unavailable', 'The cluster did not answer.')
          : HttpResponse.json(run({ state: 'SUCCEEDED' }));
      }),
    );
    const user = userEvent.setup();
    renderWithProviders(<TransferRunView />);

    expect(screen.getByRole('heading', { level: 1, name: 'Transfer' })).toBeInTheDocument();
    expect(screen.getByText('Loading the transfer')).toBeInTheDocument();
    expect(await screen.findByRole('alert')).toHaveTextContent('The cluster did not answer.');
    expect(screen.getAllByRole('heading', { level: 1 })).toHaveLength(1);

    await user.click(screen.getByRole('button', { name: 'Retry' }));
    expect(await screen.findByRole('heading', { level: 1, name: 'Move 1,200 messages' })).toBeInTheDocument();
  });

  it('shows a loading placeholder, then explains why a run could not be loaded', async () => {
    server.use(
      meHandler(),
      ...clusterHandlers(),
      http.get('*/api/v1/clusters/c1/transfers/runs/r1', () =>
        problem(404, 'transfer-not-found', 'Not found', 'No transfer with that id, or you may not see it.'),
      ),
    );
    renderWithProviders(<TransferRunView />);

    expect(screen.queryByRole('status', { name: 'Transfer state' })).toBeNull();
    expect(await screen.findByText('Not found')).toBeInTheDocument();
    expect(screen.getByText('No transfer with that id, or you may not see it.')).toBeInTheDocument();
  });
});

describe('TransferRunView figures', () => {
  it('names the run, both ends by cluster, who started it and when, and the cap override', async () => {
    show({
      startedAt: '2026-09-21T10:01:00Z',
      overrideCap: true,
      target: END('c2', 'n2', 'node-b', 'orders-copy'),
      estimate: 1,
    });

    expect(await screen.findByRole('heading', { name: 'Move 1 message' })).toBeInTheDocument();
    expect(screen.getByText('From orders on node-a (primary) to orders-copy on node-b (dr-site).')).toBeInTheDocument();
    expect(
      screen.getByText(/By admin, started .+, selecting messages up to .+, with the safety cap overridden/),
    ).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'All transfers' })).toHaveAttribute('href', '/clusters/c1/transfers');
  });

  it('says how many messages it will move is unknown when the preview could not count, and names a cluster it cannot look up', async () => {
    show({ estimate: null, target: END('c9', 'n2', 'node-b', 'orders') });

    expect(await screen.findByRole('heading', { name: 'Move messages' })).toBeInTheDocument();
    expect(screen.getByText(/to orders on node-b \(another cluster\)\./)).toBeInTheDocument();
    expect(screen.getByText(/By admin, selecting messages up to/)).toBeInTheDocument();
    expect(screen.queryByText(/, started /)).toBeNull();
    // An unknown size is stated, never shown as zero, and no progress bar pretends otherwise.
    expect(screen.getByText('Unavailable')).toBeInTheDocument();
    expect(screen.getByText(/How far along this is cannot be shown/)).toBeInTheDocument();
    expect(screen.queryByRole('progressbar')).toBeNull();
  });

  it('shows the pipeline with what did not go through, what expired and what came back', async () => {
    show({
      state: 'PARTIAL',
      estimate: 1000,
      held: 100,
      delivered: 800,
      notTransferred: 50,
      expired: 30,
      returned: 20,
    });

    await screen.findByRole('progressbar', { name: 'Messages delivered' });
    for (const [term, figure] of [
      ['Selected', '1,000'],
      ['Held in staging', '100'],
      ['Delivered', '800'],
      ['Not transferred', '50'],
      ['Expired while held', '30'],
      ['Returned to source', '20'],
    ]) {
      expect(screen.getByText(term).nextElementSibling).toHaveTextContent(figure);
    }
    expect(screen.getByRole('progressbar', { name: 'Messages delivered' })).toBeInTheDocument();
  });

  it('leaves out the stages that have nothing in them, and says staging is not used for a copy', async () => {
    show({ mode: 'COPY', state: 'SUCCEEDED', delivered: 5, estimate: 5 });

    expect(await screen.findByText('Held in staging')).toBeInTheDocument();
    expect(screen.getByText('not used')).toBeInTheDocument();
    expect(screen.queryByText('Not transferred')).toBeNull();
    expect(screen.queryByText('Expired while held')).toBeNull();
    expect(screen.queryByText('Returned to source')).toBeNull();
  });

  it('fills the bar for a run that selected nothing', async () => {
    show({ estimate: 0, state: 'SUCCEEDED' });
    expect(await screen.findByRole('progressbar', { name: 'Messages delivered' })).toHaveAttribute(
      'aria-valuenow',
      '100',
    );
  });

  it('says what became of the messages at each end, by mode and staging', async () => {
    const first = show({ mode: 'MOVE', delivered: 10, staged: 12 });
    expect(await screen.findByText('taken off into staging')).toBeInTheDocument();
    first.unmount();

    const second = show({ mode: 'MOVE', sameNode: true, delivered: 10, staged: 0 });
    expect(await screen.findByText('moved by the broker')).toBeInTheDocument();
    second.unmount();

    show({ mode: 'COPY', delivered: 10 });
    expect(await screen.findByText('read, left in place')).toBeInTheDocument();
    expect(screen.getByText(/node-a, target \(dr-site\)/)).toBeInTheDocument();
  });

  it('gives the pace of a run in flight, and that it has none yet before a rate is known', async () => {
    const first = show({ state: 'RUNNING', estimate: 1000, delivered: 100, messagesPerSecond: 10 });
    // 900 left at 10 a second is 90 seconds, which is read in minutes.
    expect(await screen.findByText('10 messages a second, about 2 minutes left.')).toBeInTheDocument();
    first.unmount();

    const second = show({ state: 'RUNNING', estimate: null, delivered: 100, messagesPerSecond: 12.34 });
    expect(await screen.findByText('12.3 messages a second.')).toBeInTheDocument();
    second.unmount();

    show({ state: 'RUNNING', messagesPerSecond: null });
    expect(await screen.findByText('No rate yet.')).toBeInTheDocument();
  });

  it('gives the pace a finished run went at, without inventing one when it moved nothing or has no times', async () => {
    const first = show({ state: 'SUCCEEDED', delivered: 0, estimate: 0 });
    expect(await screen.findByText('0 messages moved.')).toBeInTheDocument();
    first.unmount();

    const second = show({ state: 'SUCCEEDED', delivered: 1, startedAt: null, finishedAt: null });
    expect(await screen.findByText('1 message moved.')).toBeInTheDocument();
    second.unmount();

    show({
      state: 'SUCCEEDED',
      delivered: 3,
      startedAt: '2026-09-21T10:00:00Z',
      finishedAt: '2026-09-21T10:00:00Z',
    });
    expect(await screen.findByText('3 messages moved.')).toBeInTheDocument();
  });

  it('gives the pace a finished run went at, from its own start and finish', async () => {
    show({
      state: 'SUCCEEDED',
      delivered: 1200,
      estimate: 1200,
      startedAt: '2026-09-21T10:00:00Z',
      finishedAt: '2026-09-21T10:00:30Z',
    });
    expect(await screen.findByText('1,200 messages moved in 30 seconds, 40 a second.')).toBeInTheDocument();
  });

  it('marks staged messages that nothing is moving as needing attention, but not while it runs', async () => {
    const first = show({ state: 'STOPPED', held: 5 });
    expect((await screen.findByText('Held in staging')).closest('[data-tone]')).toHaveAttribute('data-tone', 'warning');
    first.unmount();

    show({ state: 'RUNNING', held: 5 });
    expect((await screen.findByText('Held in staging')).closest('[data-tone]')).toBeNull();
  });

  it('shows the cause and the snippet that fixes it, and every note', async () => {
    show({
      state: 'FAILED',
      lastError: 'node-b refused the batch: its destination is full.',
      errorSnippet: '<address-full-policy>PAGE</address-full-policy>',
      notes: ['Messages keep their order within a subscription.', 'Scheduled messages are not taken.'],
    });

    expect(await screen.findByText(/Add this to/)).toBeInTheDocument();
    expect(screen.getByText('<address-full-policy>PAGE</address-full-policy>')).toBeInTheDocument();
    expect(screen.getByRole('heading', { name: 'Good to know' })).toBeInTheDocument();
    expect(screen.getByText('Messages keep their order within a subscription.')).toBeInTheDocument();
    expect(screen.getByText('Scheduled messages are not taken.')).toBeInTheDocument();
    // The failing node's row carries the same reason in the summary.
    const summary = screen.getAllByRole('status').find((el) => el.textContent?.includes('Failed'));
    expect(summary).toHaveTextContent('node-b refused the batch: its destination is full.');
  });

  it('says a failure with no snippet has no config to add', async () => {
    show({ state: 'FAILED', lastError: 'boom', errorSnippet: null });
    await screen.findAllByText('boom');
    expect(screen.queryByText(/Add this to/)).toBeNull();
    expect(screen.queryByRole('heading', { name: 'Good to know' })).toBeNull();
  });

  it('links the audit trail on each cluster when it was recorded, and not when it was not', async () => {
    const first = show({ auditEventId: 7, targetAuditEventId: 8, target: END('c2', 'n2', 'node-b', 'orders') });
    expect(await screen.findByRole('link', { name: 'Audit trail' })).toHaveAttribute(
      'href',
      '/clusters/c1/audit?parentId=7',
    );
    expect(screen.getByRole('link', { name: /Target.s audit trail/ })).toHaveAttribute(
      'href',
      '/clusters/c2/audit?parentId=8',
    );
    first.unmount();

    show({ auditEventId: null, targetAuditEventId: null });
    await screen.findByRole('heading', { name: /Move/ });
    expect(screen.queryByRole('link', { name: 'Audit trail' })).toBeNull();
    expect(screen.queryByRole('link', { name: /Target.s audit trail/ })).toBeNull();
  });
});

describe('TransferRunView commands', () => {
  it('stops a running transfer after saying nothing is lost, and closes the dialog', async () => {
    let stopped = 0;
    server.use(
      http.post('*/api/v1/clusters/c1/transfers/runs/r1/stop', () => {
        stopped += 1;
        return HttpResponse.json(run({ state: 'STOPPED' }));
      }),
    );
    show({ state: 'RUNNING', held: 5 });
    const user = userEvent.setup();

    await user.click(await screen.findByRole('button', { name: 'Stop' }));
    const dialog = await screen.findByRole('dialog', { name: 'Stop this transfer?' });
    expect(dialog).toHaveTextContent('held messages stay in staging, and the run can be resumed or returned later.');

    await user.click(within(dialog).getByRole('button', { name: 'Stop' }));
    await waitFor(() => expect(stopped).toBe(1));
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());
    expect(await screen.findByText('Stopped this transfer')).toBeInTheDocument();
  });

  it('keeps running when the stop dialog is dismissed, and words a copy without staging', async () => {
    show({ state: 'RUNNING', mode: 'COPY' });
    const user = userEvent.setup();

    await user.click(await screen.findByRole('button', { name: 'Stop' }));
    const dialog = await screen.findByRole('dialog', { name: 'Stop this transfer?' });
    expect(dialog).toHaveTextContent('Nothing is lost: the run can be resumed later.');
    expect(dialog).not.toHaveTextContent('staging');

    await user.click(within(dialog).getByRole('button', { name: 'Cancel' }));
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());
  });

  it('offers no stop while it is returning, and no command once it is over', async () => {
    const first = show({ state: 'RETURNING', held: 4 });
    await state();
    expect(screen.queryByRole('button', { name: 'Stop' })).toBeNull();
    first.unmount();

    show({ state: 'SUCCEEDED' });
    await state();
    expect(screen.queryByRole('button', { name: /Stop|Resume|Return to source/ })).toBeNull();
  });

  it('resumes a stopped transfer', async () => {
    let resumed = 0;
    server.use(
      http.post('*/api/v1/clusters/c1/transfers/runs/r1/resume', () => {
        resumed += 1;
        return HttpResponse.json(run({ state: 'RUNNING' }));
      }),
    );
    show({ state: 'STOPPED', resumable: true });
    const user = userEvent.setup();

    await user.click(await screen.findByRole('button', { name: 'Resume' }));
    await waitFor(() => expect(resumed).toBe(1));
    expect(await screen.findByText('Resumed this transfer')).toBeInTheDocument();
  });

  it('says a command that failed did nothing further, with its cause', async () => {
    server.use(
      http.post('*/api/v1/clusters/c1/transfers/runs/r1/resume', () =>
        problem(409, 'transfer-state', 'Conflict', 'The run is already running.'),
      ),
    );
    show({ state: 'STOPPED', resumable: true });
    const user = userEvent.setup();

    await user.click(await screen.findByRole('button', { name: 'Resume' }));
    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent('Could not resume this transfer');
    expect(alert).toHaveTextContent(
      'The run is already running. The run is as shown on this page; nothing further was done.',
    );
  });

  it('keeps the return dialog open and armed only by the typed source name, then closes it once sent', async () => {
    server.use(
      http.post('*/api/v1/clusters/c1/transfers/runs/r1/return', () => HttpResponse.json(run({ state: 'RETURNED' }))),
    );
    show({ state: 'STOPPED', held: 1, returnable: true, target: END('c2', 'n2', 'node-b', 'orders') });
    const user = userEvent.setup();

    await user.click(await screen.findByRole('button', { name: 'Return to source…' }));
    const dialog = await screen.findByRole('dialog', { name: 'Return the held messages to the source?' });
    expect(dialog).toHaveTextContent('Every message held in staging, 1 message now, goes back on orders on node-a');
    expect(dialog).toHaveTextContent('Messages already delivered to orders stay there.');
    await holdButton(within(dialog).getByRole('button', { name: 'Return 1 message' }));
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());
    expect(await screen.findByText('Returned the held messages to the source')).toBeInTheDocument();
  });
});

describe('TransferRunView permissions', () => {
  it('keeps a command visible but disabled, with the reason, without the source permission', async () => {
    show({ state: 'RUNNING' }, ['cluster:read']);

    const explain = await screen.findByRole('button', { name: 'Why stopping this transfer is unavailable' });
    expect(screen.getByRole('button', { name: 'Stop' })).toBeDisabled();
    await userEvent.setup().click(explain);
    expect(await screen.findByText(/Move or retry messages/)).toBeInTheDocument();
  });

  it('asks a copy only for the right to browse the source, and the target for the right to send', async () => {
    show({ state: 'STOPPED', mode: 'COPY', resumable: true, target: END('c2', 'n2', 'node-b', 'orders') }, [
      'message:read',
    ]);

    const explain = await screen.findByRole('button', { name: 'Why resuming this transfer is unavailable' });
    expect(screen.getByRole('button', { name: 'Resume' })).toBeDisabled();
    await userEvent.setup().click(explain);
    expect(await screen.findByText(/Send messages/)).toBeInTheDocument();
  });

  it('lets the return proceed on the right to move alone, while resuming also needs the right to send', async () => {
    show({ state: 'STOPPED', held: 3, returnable: true, resumable: true }, ['message:move']);

    expect(await screen.findByRole('button', { name: 'Return to source…' })).toBeEnabled();
    // Each cluster's rights arrive on their own, and the control is offered until they do.
    expect(
      await screen.findByRole('button', { name: 'Why resuming this transfer is unavailable' }),
    ).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Why returning these messages is unavailable' })).toBeNull();
    expect(screen.getByRole('button', { name: 'Resume' })).toBeDisabled();
  });
});
