import { describe, expect, it, vi } from 'vitest';
import { http, HttpResponse } from 'msw';
import { Notifications } from '@mantine/notifications';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

import { holdButton } from '../../test/hold.ts';
import { renderWithProviders } from '../../test/render.tsx';
import { server } from '../../test/setup.ts';
import { TruncateDestinationDialog } from './TruncateDestination.tsx';
import { parseCut } from './truncate.ts';

describe('parseCut', () => {
  it('reads partition:offset pairs and an ISO time, and says what is wrong otherwise', () => {
    expect(parseCut('offsets', '0:120, 1=80')).toEqual({ before: { offsets: { '0': 120, '1': 80 } } });
    expect(parseCut('time', '2026-10-28T09:30:00Z')).toEqual({ before: { time: '2026-10-28T09:30:00Z' } });
    expect(parseCut('offsets', '0-120')).toHaveProperty('problem');
    expect(parseCut('offsets', ' ')).toHaveProperty('problem');
    expect(parseCut('time', 'yesterday')).toHaveProperty('problem');
  });
});

const DESTINATION = { id: 'kafka.topic/orders', name: 'orders' };

function open() {
  return renderWithProviders(
    <>
      <Notifications />
      <TruncateDestinationDialog clusterId="k1" destination={DESTINATION} opened onClose={() => undefined} />
    </>,
  );
}

describe('TruncateDestinationDialog', () => {
  it('previews the cut, names the groups that lose unread records, and confirms by holding with the count', async () => {
    const calls: { dryRun: boolean; body: unknown }[] = [];
    server.use(
      http.post('*/api/v1/clusters/k1/destinations/kafka.topic/orders\\:truncate', async ({ request }) => {
        const dryRun = new URL(request.url).searchParams.get('dryRun') === 'true';
        calls.push({ dryRun, body: await request.json() });
        return HttpResponse.json({ records: 1250, groups: [{ group: 'billing', records: 900 }], etag: 'e1' });
      }),
    );
    const user = userEvent.setup();
    open();
    const dialog = await screen.findByRole('dialog', { name: 'Truncate orders' });
    const confirm = within(dialog).getByRole('button', { name: 'Truncate' });
    expect(confirm).toBeDisabled();
    expect(within(dialog).getByText(/Preview the cut first/)).toBeInTheDocument();

    await user.type(within(dialog).getByRole('textbox', { name: /Delete records before/ }), '0:120');
    await user.click(within(dialog).getByRole('button', { name: 'Preview the cut' }));

    expect(await within(dialog).findByText('This removes 1,250 records.')).toBeInTheDocument();
    expect(within(dialog).getByRole('table', { name: 'Groups that lose unread records' })).toHaveTextContent('billing');
    const armed = within(dialog).getByRole('button', { name: 'Truncate 1,250 records' });
    await holdButton(armed);

    await waitFor(() => expect(calls.map((c) => c.dryRun)).toEqual([true, false]));
    expect(calls[1].body).toEqual({ before: { offsets: { '0': 120 } }, etag: 'e1' });
  });

  it('says the log changed when the preview is stale, keeps the dialog open and previews again', async () => {
    let previews = 0;
    server.use(
      http.post('*/api/v1/clusters/k1/destinations/kafka.topic/orders\\:truncate', ({ request }) => {
        if (new URL(request.url).searchParams.get('dryRun') === 'true') {
          previews += 1;
          return HttpResponse.json({ records: 10 * previews, groups: [], etag: `e${previews}` });
        }
        return HttpResponse.json({ title: 'Stale preview', type: 'stale-preview' }, { status: 412 });
      }),
    );
    const user = userEvent.setup();
    open();
    const dialog = await screen.findByRole('dialog', { name: 'Truncate orders' });
    await user.type(within(dialog).getByRole('textbox', { name: /Delete records before/ }), '0:120');
    await user.click(within(dialog).getByRole('button', { name: 'Preview the cut' }));
    await holdButton(await within(dialog).findByRole('button', { name: 'Truncate 10 records' }));

    expect(await within(dialog).findByText('The log changed since the preview')).toBeInTheDocument();
    expect(screen.getByRole('dialog', { name: 'Truncate orders' })).toBeInTheDocument();
    expect(within(dialog).getByRole('button', { name: 'Truncate' })).toBeDisabled();

    await user.click(within(dialog).getByRole('button', { name: 'Preview the cut' }));
    expect(await within(dialog).findByRole('button', { name: 'Truncate 20 records' })).toBeEnabled();
    expect(within(dialog).queryByText('The log changed since the preview')).not.toBeInTheDocument();
  });

  it('checks the cut beside its field before asking the system', async () => {
    const user = userEvent.setup();
    open();
    const dialog = await screen.findByRole('dialog', { name: 'Truncate orders' });
    await user.type(within(dialog).getByRole('textbox', { name: /Delete records before/ }), 'soon');
    await user.click(within(dialog).getByRole('button', { name: 'Preview the cut' }));
    expect(await within(dialog).findByText(/is not partition:offset/)).toBeInTheDocument();
  });

  it("shows the system's refusal, such as a compacted topic, and leaves the confirmation disabled", async () => {
    server.use(
      http.post('*/api/v1/clusters/k1/destinations/kafka.topic/orders\\:truncate', () =>
        HttpResponse.json(
          { title: 'Failed precondition', detail: 'orders is compacted: records cannot be truncated by offset.' },
          { status: 409 },
        ),
      ),
    );
    const user = userEvent.setup();
    open();
    const dialog = await screen.findByRole('dialog', { name: 'Truncate orders' });
    await user.type(within(dialog).getByRole('textbox', { name: /Delete records before/ }), '0:5');
    await user.click(within(dialog).getByRole('button', { name: 'Preview the cut' }));

    expect(await within(dialog).findByText('This cut is refused')).toBeInTheDocument();
    expect(within(dialog).getAllByText(/orders is compacted/).length).toBeGreaterThan(0);
    expect(within(dialog).getByRole('button', { name: 'Truncate' })).toBeDisabled();
    await vi.waitFor(() => expect(within(dialog).getByRole('button', { name: 'Preview the cut' })).toBeEnabled());
  });
});
