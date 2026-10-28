import { beforeEach, describe, expect, it } from 'vitest';
import { http, HttpResponse } from 'msw';
import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

import { paged } from '../../kernel/api/paging.ts';
import { renderAppAt } from '../../test/render.tsx';
import { server } from '../../test/setup.ts';
import { LOADED_SYSTEMS } from '../../test/wire.ts';

describe('RegisterClusterPage', () => {
  beforeEach(() => {
    window.localStorage.clear();
    server.use(
      http.get('*/api/v1/auth/me', () =>
        HttpResponse.json({
          id: 'u1',
          username: 'operator',
          mustChangePassword: false,
          grants: [{ scopeType: 'GLOBAL', scopeId: null, permissions: ['*'] }],
        }),
      ),
      http.get('*/api/v1/clusters', () => HttpResponse.json(paged([]))),
      http.get('*/api/v1/environments', () => HttpResponse.json(paged([]))),
      http.get('*/api/v1/alerts/firing', () => HttpResponse.json(paged([]))),
    );
  });

  it('is a page of its own, outside any cluster, whose address names the system', async () => {
    const { router } = renderAppAt('/clusters/new?system=kafka');

    expect(await screen.findByRole('heading', { name: 'Register a cluster', level: 1 })).toBeInTheDocument();
    expect(await screen.findByRole('textbox', { name: /Bootstrap servers/ })).toBeInTheDocument();
    expect(screen.queryByLabelText(/Broker management URL/)).not.toBeInTheDocument();
    expect(screen.getByRole('radio', { name: /Apache Kafka/ })).toBeChecked();
    expect(router.state.location.pathname).toBe('/clusters/new');
    // The way back, not a sidebar for a cluster that is not open.
    expect(screen.getByRole('navigation', { name: 'Where you are' })).toBeInTheDocument();
  });

  it('shows the Artemis form for Artemis, with no bootstrap field', async () => {
    renderAppAt('/clusters/new?system=artemis');

    expect(await screen.findByLabelText(/Broker management URL/)).toBeInTheDocument();
    expect(screen.queryByRole('textbox', { name: /Bootstrap servers/ })).not.toBeInTheDocument();
  });

  it('changes the form and the address when another system is chosen, by keyboard', async () => {
    const user = userEvent.setup();
    const { router } = renderAppAt('/clusters/new?system=artemis');
    await screen.findByLabelText(/Broker management URL/);

    await user.click(screen.getByRole('radio', { name: /Apache Kafka/ }));

    expect(await screen.findByRole('textbox', { name: /Bootstrap servers/ })).toBeInTheDocument();
    expect(router.state.location.search).toEqual({ system: 'kafka' });
  });

  it('asks to choose a system when the address names none and several are loaded', async () => {
    renderAppAt('/clusters/new');

    expect(await screen.findByText('Choose the system to see what its connection needs.')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Check connection' })).not.toBeInTheDocument();
  });

  it('opens the only loaded system without a choice', async () => {
    server.use(http.get('*/api/v1/systems', () => HttpResponse.json([LOADED_SYSTEMS[1]])));
    renderAppAt('/clusters/new');

    expect(await screen.findByRole('textbox', { name: /Bootstrap servers/ })).toBeInTheDocument();
  });

  it('names the systems Rahdar supports when the address names one it does not load', async () => {
    renderAppAt('/clusters/new?system=rabbitmq');

    expect(await screen.findByText('That system is not loaded')).toBeInTheDocument();
    expect(screen.getByText(/Rahdar has no driver for "rabbitmq"/)).toHaveTextContent('Apache Kafka');
  });

  it('says why the systems could not be read, and retries', async () => {
    let failing = true;
    server.use(
      http.get('*/api/v1/systems', () =>
        failing
          ? HttpResponse.json({ title: 'Error', detail: 'The driver registry is down.' }, { status: 500 })
          : HttpResponse.json(LOADED_SYSTEMS),
      ),
    );
    const user = userEvent.setup();
    renderAppAt('/clusters/new?system=kafka');

    expect(await screen.findByText(/The driver registry is down/)).toBeInTheDocument();
    failing = false;
    await user.click(screen.getByRole('button', { name: /Try again|Retry/ }));
    expect(await screen.findByRole('textbox', { name: /Bootstrap servers/ })).toBeInTheDocument();
  });

  it('is not mistaken for a cluster called "new"', async () => {
    renderAppAt('/clusters/new');
    await screen.findByRole('heading', { name: 'Register a cluster', level: 1 });
    expect(screen.queryByRole('button', { name: /Switch cluster/ })).not.toBeInTheDocument();
  });
});
