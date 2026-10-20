import { describe, expect, it, vi } from 'vitest';
import { http, HttpResponse } from 'msw';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

import { renderWithProviders } from '../../test/render.tsx';
import { server } from '../../test/setup.ts';
import type { ConfigDeclarationView } from './api.ts';
import { AdoptionSuggestion } from './AdoptionSuggestion.tsx';
import { declaration } from './fixtures.ts';

const EMPTY = { version: 1, addresses: [], addressSettings: [], securitySettings: [], diverts: [], bridges: [] };
const UNDECLARED = declaration({ declared: false, revision: 0, document: EMPTY });

function Harness({ d, canWrite = true }: Readonly<{ d: ConfigDeclarationView; canWrite?: boolean }>) {
  return (
    <AdoptionSuggestion declaration={d} onAdopt={() => {}} canWrite={canWrite} blockedReason="Needs permission." />
  );
}

describe('AdoptionSuggestion', () => {
  it('reads the live nodes once on arrival, as a card under its own heading, and not again on a re-render', async () => {
    const read = vi.fn();
    server.use(
      http.post('*/api/v1/clusters/c1/artemis/config/adopt', () => {
        read();
        return HttpResponse.json({ document: EMPTY, notes: [], disagreements: [], closes: [] });
      }),
    );
    const { rerender } = renderWithProviders(<Harness d={UNDECLARED} />);

    expect(await screen.findByText('0 entries would be declared')).toBeInTheDocument();
    // The offer is for a cluster registered without the adoption, and says so.
    expect(screen.getByText(/This cluster was registered without adopting its configuration/)).toBeInTheDocument();
    expect(
      screen.getByRole('heading', { level: 2, name: 'Adopt what this cluster runs as revision 1' }),
    ).toBeInTheDocument();
    rerender(<Harness d={{ ...UNDECLARED, note: 'touched' }} />);
    await new Promise((r) => setTimeout(r, 50));
    expect(read).toHaveBeenCalledTimes(1);
  });

  it('says the reason Rahdar does not adopt on its own from a control that takes focus', async () => {
    server.use(
      http.post('*/api/v1/clusters/c1/artemis/config/adopt', () =>
        HttpResponse.json({ document: EMPTY, notes: [], disagreements: [], closes: [] }),
      ),
    );
    const user = userEvent.setup();
    renderWithProviders(<Harness d={UNDECLARED} />);

    await user.click(await screen.findByRole('button', { name: /Why does Rahdar not do this for me/ }));
    expect(
      await screen.findByText(/Rahdar adopts only when an operator says so: when registering a cluster, or here/),
    ).toBeInTheDocument();
  });

  it('keeps the adopt control visible and states why it is unavailable without the permission', async () => {
    server.use(
      http.post('*/api/v1/clusters/c1/artemis/config/adopt', () =>
        HttpResponse.json({ document: EMPTY, notes: [], disagreements: [], closes: [] }),
      ),
    );
    renderWithProviders(<Harness d={UNDECLARED} canWrite={false} />);

    expect(await screen.findByRole('button', { name: 'Review and adopt as revision 1' })).toBeDisabled();
    expect(screen.getByText('Needs permission.')).toBeInTheDocument();
  });

  it('says a failed read is not the same as nothing to adopt, and reads again on retry', async () => {
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
    renderWithProviders(<Harness d={UNDECLARED} />);

    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent('No live node answered.');
    expect(screen.getByText(/not the same as there being nothing to adopt/)).toBeInTheDocument();
    await user.click(within(alert).getByRole('button', { name: 'Retry' }));
    await waitFor(() => expect(screen.queryByRole('alert')).toBeNull());
  });

  it('shows nothing when no node is live, so there is nothing to read', () => {
    const { container } = renderWithProviders(
      <Harness d={{ ...UNDECLARED, nodes: UNDECLARED.nodes.map((n) => ({ ...n, live: false })) }} />,
    );
    expect(container.querySelector('section')).toBeNull();
  });
});
