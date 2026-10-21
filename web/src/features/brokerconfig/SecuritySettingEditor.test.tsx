import { describe, expect, it, vi } from 'vitest';
import { screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';

import { renderWithProviders } from '../../test/render.tsx';
import { server } from '../../test/setup.ts';
import type { ConfigSecuritySettingView } from './api.ts';
import { CATALOGUE, declaration } from './fixtures.ts';
import { SecuritySettingEditor } from './SecuritySettingEditor.tsx';

const DECLARED = declaration();
const ORDERS = DECLARED.document.securitySettings[0]!;

function captureSave() {
  const saved = vi.fn();
  server.use(
    http.put('*/api/v1/clusters/c1/artemis/config', async ({ request }) => {
      saved(await request.json());
      return HttpResponse.json(DECLARED);
    }),
  );
  return saved;
}

function open(item: ConfigSecuritySettingView | null) {
  const onClose = vi.fn();
  renderWithProviders(
    <SecuritySettingEditor declaration={DECLARED} catalogue={CATALOGUE} item={item} opened onClose={onClose} />,
  );
  return { onClose, user: userEvent.setup() };
}

const SAVE = 'Save as revision 4';

describe('SecuritySettingEditor', () => {
  it('shows each role as its own group of permissions named in words, not a table to scroll across', async () => {
    open(ORDERS);

    const role = await screen.findByRole('region', { name: 'app-role' });
    expect(screen.queryByRole('table')).toBeNull();
    const group = within(role).getByRole('group', { name: 'What app-role may do' });
    expect(within(group).getByRole('checkbox', { name: 'send messages' })).toBeChecked();
    expect(within(group).getByRole('checkbox', { name: 'consume messages' })).toBeChecked();
    expect(within(group).getByRole('checkbox', { name: 'browse messages' })).not.toBeChecked();
    // What the role holds is also said as one sentence, so a reader need not scan the boxes.
    expect(within(role).getByText('may send messages, consume messages')).toBeInTheDocument();
  });

  it('saves the permissions as ticked, keeping the roles it did not touch', async () => {
    const saved = captureSave();
    const { user } = open(ORDERS);

    const group = await screen.findByRole('group', { name: 'What app-role may do' });
    await user.click(within(group).getByRole('checkbox', { name: 'consume messages' }));
    await user.click(within(group).getByRole('checkbox', { name: 'browse messages' }));
    await user.click(screen.getByRole('button', { name: SAVE }));

    await vi.waitFor(() => expect(saved).toHaveBeenCalledTimes(1));
    const body = saved.mock.calls[0][0];
    expect(body.document.securitySettings[0]).toEqual({
      match: 'orders.#',
      permissions: { send: ['app-role'], browse: ['app-role'] },
    });
  });

  it('adds a role, which starts with nothing and says so, and removes it again', async () => {
    const { user } = open(ORDERS);

    await user.type(await screen.findByRole('textbox', { name: /Add a role/ }), 'ops-role');
    await user.click(screen.getByRole('button', { name: 'Add role' }));

    const added = await screen.findByRole('region', { name: 'ops-role' });
    expect(within(added).getByText('may do nothing here')).toBeInTheDocument();

    await user.click(within(added).getByRole('button', { name: 'Remove role ops-role' }));
    expect(screen.queryByRole('region', { name: 'ops-role' })).toBeNull();
  });

  it('refuses a match with no roles, saying why beside the role field and focusing it', async () => {
    const saved = captureSave();
    const { user } = open({ match: 'orders.audit', permissions: {} });

    await user.click(await screen.findByRole('button', { name: SAVE }));

    expect(await screen.findByText(/Add at least one role/)).toBeInTheDocument();
    expect(screen.getByRole('textbox', { name: /Add a role/ })).toHaveFocus();
    expect(saved).not.toHaveBeenCalled();
  });
});
