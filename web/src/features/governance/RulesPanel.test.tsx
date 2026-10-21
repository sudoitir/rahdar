import { afterEach, describe, expect, it } from 'vitest';
import { http, HttpResponse } from 'msw';
import { Notifications, notifications } from '@mantine/notifications';
import { act, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

import { renderWithProviders } from '../../test/render.tsx';
import { server } from '../../test/setup.ts';
import { RulesPanel } from './RulesPanel.tsx';
import { paged } from '../../kernel/api/paging.ts';
import { holdButton } from '../../test/hold.ts';

const BUILT_IN = {
  id: 'b1',
  destinationPattern: null,
  target: 'PROPERTY',
  selector: 'authorization',
  dataClass: 'CREDENTIAL',
  dataClassLabel: 'credential',
  action: null,
  defaultAction: 'DROP',
  builtin: true,
  enabled: true,
  exception: false,
  updatedAt: '2026-09-14T00:00:00Z',
};

const CUSTOM = {
  ...BUILT_IN,
  id: 'c1',
  destinationPattern: 'orders.#',
  selector: 'customerEmail',
  dataClass: 'EMAIL',
  dataClassLabel: 'email',
  defaultAction: 'REDACT',
  builtin: false,
};

function mockApis(permissions: string[]) {
  server.use(
    http.get('*/api/v1/auth/me', () =>
      HttpResponse.json({
        id: 'u1',
        username: 'ada',
        mustChangePassword: false,
        grants: [{ scopeType: 'GLOBAL', scopeId: null, permissions }],
      }),
    ),
    http.get('*/api/v1/clusters', () => HttpResponse.json(paged([]))),
    http.get('*/api/v1/governance/rules', () => HttpResponse.json(paged([BUILT_IN, CUSTOM]))),
    http.get('*/api/v1/governance/remask', () =>
      HttpResponse.json({ version: 3, rowsUnderEarlierVersion: 1200, capped: false }),
    ),
  );
}

function renderRules() {
  return renderWithProviders(
    <>
      <Notifications />
      <RulesPanel />
    </>,
  );
}

describe('RulesPanel', () => {
  afterEach(() => act(() => notifications.clean()));

  it('lists rules and states that a built-in rule can be disabled but not deleted', async () => {
    mockApis(['governance:read', 'governance:write']);
    renderRules();

    expect(await screen.findByText('authorization')).toBeInTheDocument();
    expect(screen.getByText('Can be disabled, not deleted.')).toBeInTheDocument();
    expect(screen.getByText('Drop (default)')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Delete the rule for authorization' })).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Delete the rule for customerEmail' })).toBeEnabled();
    expect(screen.getByRole('switch', { name: 'Enabled: authorization' })).toBeEnabled();
    expect(
      await screen.findByText(/1,200 stored messages are still masked under an earlier policy/),
    ).toBeInTheDocument();
  });

  it('keeps change controls visible but disabled, with the reason, for a read-only user', async () => {
    mockApis(['governance:read']);
    renderRules();

    expect(
      await screen.findByText('Changing masking rules needs the governance:write permission.'),
    ).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'New rule' })).toBeDisabled();
    expect(screen.getByRole('switch', { name: 'Enabled: customerEmail' })).toBeDisabled();
  });

  it('validates the name on blur and posts the rule', async () => {
    mockApis(['governance:write']);
    let posted: Record<string, unknown> | null = null;
    server.use(
      http.post('*/api/v1/governance/rules', async ({ request }) => {
        posted = (await request.json()) as Record<string, unknown>;
        return HttpResponse.json({ ...CUSTOM, id: 'n1', selector: 'customerName' }, { status: 201 });
      }),
    );
    const user = userEvent.setup();
    renderRules();

    await user.click(await screen.findByRole('button', { name: 'New rule' }));
    const name = await screen.findByRole('textbox', { name: /Name/ });
    await user.click(name);
    await user.tab();
    expect(await screen.findByText(/Enter the name the rule matches/)).toBeInTheDocument();

    await user.type(name, 'customerName');
    await user.type(screen.getByRole('textbox', { name: /Destinations/ }), 'orders.#');
    await user.click(screen.getByRole('button', { name: 'Save rule' }));

    await waitFor(() => expect(posted).not.toBeNull());
    expect(posted).toMatchObject({ selector: 'customerName', destinationPattern: 'orders.#', target: 'PROPERTY' });
    expect(await screen.findByText('Saved the rule for customerName')).toBeInTheDocument();
  });

  it('focuses the first invalid field on a rejected save', async () => {
    mockApis(['governance:write']);
    const user = userEvent.setup();
    renderRules();

    await user.click(await screen.findByRole('button', { name: 'New rule' }));
    await user.click(await screen.findByRole('button', { name: 'Save rule' }));

    expect(screen.getByRole('textbox', { name: /Name/ })).toHaveFocus();
  });

  it('arms delete only on the typed name, and escape dismisses the dialog from the keyboard', async () => {
    mockApis(['governance:write']);
    const user = userEvent.setup();
    renderRules();

    const trigger = await screen.findByRole('button', { name: 'Delete the rule for customerEmail' });
    await user.click(trigger);
    const dialog = await screen.findByRole('dialog', { name: 'Delete the rule for customerEmail' });
    expect(dialog).toHaveTextContent('Values this rule masks become visible');

    expect(screen.getByRole('button', { name: 'Delete rule' })).toBeEnabled();

    await user.keyboard('{Escape}');
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
    await waitFor(() => expect(trigger).toHaveFocus());
  });

  it('says the rules could not be loaded, with a retry, instead of showing an empty policy', async () => {
    mockApis(['governance:write']);
    server.use(
      http.get('*/api/v1/governance/rules', () =>
        HttpResponse.json({ title: 'Unavailable', detail: 'The database is down' }, { status: 503 }),
      ),
    );
    renderRules();

    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent('The database is down');
    expect(within(alert).getByRole('button', { name: 'Retry' })).toBeInTheDocument();
    expect(screen.queryByText('No masking rules')).not.toBeInTheDocument();
  });

  it('says a rule was switched off, and why and what next when it was not', async () => {
    mockApis(['governance:write']);
    let reply: Response = HttpResponse.json({ ...CUSTOM, enabled: false });
    server.use(http.put('*/api/v1/governance/rules/c1', () => reply));
    const user = userEvent.setup();
    renderRules();

    await user.click(await screen.findByRole('switch', { name: 'Enabled: customerEmail' }));
    expect(await screen.findByText('Disabled the rule for customerEmail')).toBeInTheDocument();

    reply = HttpResponse.json({ title: 'Conflict', detail: 'The policy changed elsewhere.' }, { status: 409 });
    await user.click(screen.getByRole('switch', { name: 'Enabled: customerEmail' }));
    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent('Could not disable the rule for customerEmail');
    expect(alert).toHaveTextContent('The policy changed elsewhere. The switch shows what is stored; try again.');
  });

  it('keeps the form open and says why a rule was not saved', async () => {
    mockApis(['governance:write']);
    server.use(
      http.post('*/api/v1/governance/rules', () =>
        HttpResponse.json({ title: 'Conflict', detail: 'A rule for customerName already exists.' }, { status: 409 }),
      ),
    );
    const user = userEvent.setup();
    renderRules();

    await user.click(await screen.findByRole('button', { name: 'New rule' }));
    await user.type(await screen.findByRole('textbox', { name: /Name/ }), 'customerName');
    await user.click(screen.getByRole('button', { name: 'Save rule' }));

    const dialog = await screen.findByRole('dialog', { name: 'New masking rule' });
    expect(await within(dialog).findByRole('alert')).toHaveTextContent('A rule for customerName already exists.');
  });

  it('says a rule was deleted, and why and what next when it was not', async () => {
    mockApis(['governance:write']);
    let reply: Response = new HttpResponse(null, { status: 204 });
    server.use(http.delete('*/api/v1/governance/rules/c1', () => reply));
    const user = userEvent.setup();
    renderRules();

    await user.click(await screen.findByRole('button', { name: 'Delete the rule for customerEmail' }));
    let dialog = await screen.findByRole('dialog');
    reply = HttpResponse.json({ title: 'Locked', detail: 'The policy is being re-masked.' }, { status: 409 });
    await holdButton(within(dialog).getByRole('button', { name: 'Delete rule' }));

    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent('Could not delete the rule for customerEmail');
    expect(alert).toHaveTextContent('The policy is being re-masked. It is still listed; try again.');
    expect(screen.getByRole('dialog')).toBeInTheDocument();

    reply = new HttpResponse(null, { status: 204 });
    dialog = screen.getByRole('dialog');
    await holdButton(within(dialog).getByRole('button', { name: 'Delete rule' }));
    expect(await screen.findByText('Deleted the rule for customerEmail')).toBeInTheDocument();
  });
});
