import { beforeEach, describe, expect, it } from 'vitest';
import { http, HttpResponse } from 'msw';
import { screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

import { renderWithProviders } from '../../test/render.tsx';
import { holding } from '../../test/access.ts';
import { server } from '../../test/setup.ts';
import { EnvironmentsPanel } from './EnvironmentsPanel.tsx';
import { paged } from '../../kernel/api/paging.ts';
import { holdButton } from '../../test/hold.ts';

function env(over: Record<string, unknown> = {}) {
  return { id: 'e1', name: 'production', colour: '#e03131', sortOrder: 0, ...over };
}

describe('EnvironmentsPanel', () => {
  beforeEach(() => server.use(holding('environment:read')));

  it('lists existing environments', async () => {
    server.use(http.get('*/api/v1/environments', () => HttpResponse.json(paged([env()]))));
    renderWithProviders(<EnvironmentsPanel />);

    expect(await screen.findByText('production')).toBeInTheDocument();
    expect(screen.getByText(/^1 environment\./)).toBeInTheDocument();
  });

  it('says the permission is missing instead of loading forever', async () => {
    server.use(holding('cluster:read'));
    renderWithProviders(<EnvironmentsPanel />);

    expect(await screen.findByText(/does not include the environment:read permission/)).toBeInTheDocument();
  });

  it('shows a count for zero environments', async () => {
    server.use(http.get('*/api/v1/environments', () => HttpResponse.json(paged([]))));
    renderWithProviders(<EnvironmentsPanel />);

    expect(await screen.findByText(/^0 environments\./)).toBeInTheDocument();
  });

  it('creates an environment from the form', async () => {
    let created = false;
    server.use(
      http.get('*/api/v1/environments', () => HttpResponse.json(paged(created ? [env()] : []))),
      http.post('*/api/v1/environments', () => {
        created = true;
        return HttpResponse.json(env(), { status: 201 });
      }),
    );
    const user = userEvent.setup();
    renderWithProviders(<EnvironmentsPanel />);

    await screen.findByText(/^0 environments\./);
    await user.click(screen.getByRole('button', { name: 'New environment' }));
    await user.type(await screen.findByLabelText(/Name/), 'production');
    await user.click(screen.getByRole('button', { name: 'Save' }));

    expect(await screen.findByText('production')).toBeInTheDocument();
  });

  it('deletes an environment', async () => {
    let deleted = false;
    server.use(
      http.get('*/api/v1/environments', () => HttpResponse.json(paged(deleted ? [] : [env()]))),
      http.delete('*/api/v1/environments/e1', () => {
        deleted = true;
        return new HttpResponse(null, { status: 204 });
      }),
    );
    const user = userEvent.setup();
    renderWithProviders(<EnvironmentsPanel />);

    await screen.findByText('production');
    await user.click(screen.getByRole('button', { name: 'Delete production' }));
    const dialog = await screen.findByRole('dialog', { name: 'Delete environment' });
    expect(dialog).toHaveTextContent('Its clusters stay registered');
    const confirm = within(dialog).getByRole('button', { name: 'Delete environment' });
    await holdButton(confirm);

    expect(await screen.findByText(/^0 environments\./)).toBeInTheDocument();
  });

  it('teaches what an environment is when there is none, with the way to create one', async () => {
    server.use(http.get('*/api/v1/environments', () => HttpResponse.json(paged([]))));
    renderWithProviders(<EnvironmentsPanel />);

    expect(await screen.findByText('No environments yet')).toBeInTheDocument();
    expect(screen.getAllByRole('button', { name: 'New environment' })).toHaveLength(2);
  });

  it('says why the environments could not be read, with a retry', async () => {
    server.use(
      http.get('*/api/v1/environments', () =>
        HttpResponse.json({ title: 'Error', detail: 'The store is down.' }, { status: 500 }),
      ),
    );
    renderWithProviders(<EnvironmentsPanel />);

    expect(await screen.findByText('The store is down.')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Retry' })).toBeInTheDocument();
  });

  it('asks for a name beside the field, focuses it, sends nothing, and starts with no colour', async () => {
    let sent = 0;
    server.use(
      http.get('*/api/v1/environments', () => HttpResponse.json(paged([]))),
      http.post('*/api/v1/environments', () => {
        sent += 1;
        return HttpResponse.json(env(), { status: 201 });
      }),
    );
    const user = userEvent.setup();
    renderWithProviders(<EnvironmentsPanel />);

    await user.click((await screen.findAllByRole('button', { name: 'New environment' }))[0]);
    const dialog = await screen.findByRole('dialog', { name: 'New environment' });
    expect(within(dialog).getByLabelText('Colour')).toHaveValue('');
    await user.click(within(dialog).getByRole('button', { name: 'Save' }));

    expect(await within(dialog).findByText('Give the environment a name.')).toBeInTheDocument();
    expect(within(dialog).getByLabelText(/Name/)).toHaveFocus();
    expect(sent).toBe(0);
  });

  it('states why a refused save failed and keeps the form open', async () => {
    server.use(
      http.get('*/api/v1/environments', () => HttpResponse.json(paged([]))),
      http.post('*/api/v1/environments', () =>
        HttpResponse.json({ title: 'Conflict', detail: 'An environment named production exists.' }, { status: 409 }),
      ),
    );
    const user = userEvent.setup();
    renderWithProviders(<EnvironmentsPanel />);

    await user.click((await screen.findAllByRole('button', { name: 'New environment' }))[0]);
    const dialog = await screen.findByRole('dialog', { name: 'New environment' });
    await user.type(within(dialog).getByLabelText(/Name/), 'production');
    await user.click(within(dialog).getByRole('button', { name: 'Save' }));

    expect(await within(dialog).findByText('An environment named production exists.')).toBeInTheDocument();
  });
});
