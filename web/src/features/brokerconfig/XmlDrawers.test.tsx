import { describe, expect, it, vi } from 'vitest';
import { http, HttpResponse } from 'msw';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

import { renderWithProviders } from '../../test/render.tsx';
import { server } from '../../test/setup.ts';
import { notify } from '../../ui/notify.ts';
import type { ConfigDeclarationView } from './api.ts';
import { declaration } from './fixtures.ts';
import { AdoptDrawer, ExportXmlDrawer, ImportXmlDrawer } from './XmlDrawers.tsx';

const EMPTY = { version: 1, addresses: [], addressSettings: [], securitySettings: [], diverts: [], bridges: [] };

function adoptReplies(onRead: () => void) {
  server.use(
    http.post('*/api/v1/clusters/c1/artemis/config/adopt', () => {
      onRead();
      return HttpResponse.json({ document: EMPTY, notes: [], disagreements: [], closes: [] });
    }),
  );
}

function Harness({ opened, d = declaration() }: Readonly<{ opened: boolean; d?: ConfigDeclarationView }>) {
  return <AdoptDrawer declaration={d} opened={opened} onClose={() => {}} />;
}

describe('AdoptDrawer', () => {
  it('reads the live nodes once when it opens, not on every render, and again after it was closed', async () => {
    const read = vi.fn();
    adoptReplies(read);
    const { rerender } = renderWithProviders(<Harness opened />);

    expect(await screen.findByText(/Addresses: 0 recognised/)).toBeInTheDocument();
    rerender(<Harness opened d={declaration({ note: 'edited elsewhere' })} />);
    await new Promise((r) => setTimeout(r, 50));
    expect(read).toHaveBeenCalledTimes(1);

    rerender(<Harness opened={false} />);
    rerender(<Harness opened />);
    await waitFor(() => expect(read).toHaveBeenCalledTimes(2));
  });

  it('states why the read failed and offers it again, and holds the save back until something was read', async () => {
    let attempts = 0;
    server.use(
      http.post('*/api/v1/clusters/c1/artemis/config/adopt', () => {
        attempts += 1;
        return attempts === 1
          ? HttpResponse.json({ title: 'Down', detail: 'No live node answered.' }, { status: 503 })
          : HttpResponse.json({ document: EMPTY, notes: [], disagreements: [], closes: [] });
      }),
    );
    const user = userEvent.setup();
    renderWithProviders(<Harness opened />);

    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent('No live node answered.');
    expect(screen.getByRole('button', { name: 'Save as revision 4' })).toBeDisabled();

    await user.click(within(alert).getByRole('button', { name: 'Retry' }));
    expect(await screen.findByText(/Addresses: 0 recognised/)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Save as revision 4' })).toBeEnabled();
  });
});

describe('ImportXmlDrawer', () => {
  it('lists the errors of a pasted document as fields and holds the save until they are fixed', async () => {
    server.use(
      http.post('*/api/v1/clusters/c1/artemis/config/import-xml', () =>
        HttpResponse.json({
          document: EMPTY,
          unsupported: [],
          errors: [{ field: 'address-setting#', message: 'max-size-bytes is not a number' }],
        }),
      ),
    );
    const user = userEvent.setup();
    renderWithProviders(<ImportXmlDrawer declaration={declaration()} opened onClose={() => {}} />);

    await user.type(await screen.findByRole('textbox', { name: /broker.xml, or any part of it/ }), '<core/>');
    await user.click(screen.getByRole('button', { name: 'Preview import' }));

    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent('1 error in the XML');
    expect(within(alert).getByText('address-setting#')).toBeInTheDocument();
    expect(within(alert).getByText(/max-size-bytes is not a number/)).toBeInTheDocument();
  });

  it('says in words, not only in a hover title, why saving previews first', async () => {
    renderWithProviders(<ImportXmlDrawer declaration={declaration()} opened onClose={() => {}} />);

    expect(
      await screen.findByText(/Saving previews first; a document with errors cannot be saved/),
    ).toBeInTheDocument();
  });
});

describe('ExportXmlDrawer', () => {
  it('shows the fragment, and announces the copy', async () => {
    server.use(
      http.get('*/api/v1/clusters/c1/artemis/config/export-xml', () => HttpResponse.text('<core><addresses/></core>')),
    );
    const succeeded = vi.spyOn(notify, 'succeeded');
    const user = userEvent.setup();
    renderWithProviders(<ExportXmlDrawer declaration={declaration()} opened onClose={() => {}} />);

    const fragment = await screen.findByRole('region', { name: 'broker.xml fragment of revision 3' });
    expect(fragment).toHaveAttribute('tabindex', '0');
    expect(fragment).toHaveTextContent('<core><addresses/></core>');
    await user.click(await screen.findByRole('button', { name: 'Copy broker.xml fragment' }));

    expect(succeeded).toHaveBeenCalledWith(expect.objectContaining({ subject: 'the broker.xml fragment' }));
    succeeded.mockRestore();
  });

  it('states a failed render with its cause and offers it again', async () => {
    server.use(
      http.get('*/api/v1/clusters/c1/artemis/config/export-xml', () =>
        HttpResponse.json({ title: 'Down', detail: 'The renderer is not answering.' }, { status: 503 }),
      ),
    );
    renderWithProviders(<ExportXmlDrawer declaration={declaration()} opened onClose={() => {}} />);

    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent('The renderer is not answering.');
    expect(within(alert).getByRole('button', { name: 'Retry' })).toBeEnabled();
  });
});
