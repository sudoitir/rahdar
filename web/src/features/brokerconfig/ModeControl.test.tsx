import { describe, expect, it, vi } from 'vitest';
import { http, HttpResponse } from 'msw';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

import { renderWithProviders } from '../../test/render.tsx';
import { server } from '../../test/setup.ts';
import { notify } from '../../ui/notify.ts';
import { declaration } from './fixtures.ts';
import { ModeControl } from './ModeControl.tsx';

const PATCH = '*/api/v1/clusters/c1/artemis/config/mode';
async function openPopover(canWrite: boolean) {
  const user = userEvent.setup();
  renderWithProviders(<ModeControl declaration={declaration()} canWrite={canWrite} />);
  await user.click(screen.getByRole('button', { name: /Managed by Rahdar.*Change apply mode/ }));
  const popover = await screen.findByRole('dialog');
  return { user, popover };
}

describe('ModeControl', () => {
  // brokerconfig-1: a read-only operator could submit and only learned from a server 403.
  it('disables Save and every field without the permission, and says why beside them', async () => {
    const saved = vi.fn();
    server.use(http.patch(PATCH, () => HttpResponse.json(saved())));
    const { popover } = await openPopover(false);

    expect(within(popover).getByRole('combobox', { name: 'Apply mode' })).toBeDisabled();
    expect(within(popover).getByRole('switch', { name: /Report undeclared items/ })).toBeDisabled();
    expect(within(popover).getByRole('button', { name: 'Save' })).toBeDisabled();
    expect(within(popover).getByText(/needs the "Edit declared configuration" permission/)).toBeInTheDocument();
    expect(saved).not.toHaveBeenCalled();
  });

  it('saves the mode with the permission, announces it and closes', async () => {
    let body: unknown;
    server.use(
      http.patch(PATCH, async ({ request }) => {
        body = await request.json();
        return HttpResponse.json(declaration({ reportUndeclared: true }));
      }),
    );
    const succeeded = vi.spyOn(notify, 'succeeded');
    const { user, popover } = await openPopover(true);

    await user.click(within(popover).getByRole('switch', { name: /Report undeclared items/ }));
    await user.click(within(popover).getByRole('button', { name: 'Save' }));

    await waitFor(() => expect(body).toMatchObject({ applyMode: 'RAHDAR_MANAGED', reportUndeclared: true }));
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());
    expect(succeeded).toHaveBeenCalledWith(
      expect.objectContaining({ subject: expect.stringContaining('Managed by Rahdar') }),
    );
    succeeded.mockRestore();
  });

  it('states a failed save with its cause, leaves the popover open and keeps Save usable', async () => {
    server.use(
      http.patch(PATCH, () =>
        HttpResponse.json({ title: 'Down', detail: 'The configuration store is not answering.' }, { status: 503 }),
      ),
    );
    const { user, popover } = await openPopover(true);

    await user.click(within(popover).getByRole('button', { name: 'Save' }));

    const alert = await within(popover).findByRole('alert');
    expect(alert).toHaveTextContent('The configuration store is not answering.');
    expect(within(popover).getByRole('button', { name: 'Save' })).toBeEnabled();
  });
});
