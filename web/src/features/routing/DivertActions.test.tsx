import { describe, expect, it } from 'vitest';
import { holdButton } from '../../test/hold.ts';
import { http, HttpResponse } from 'msw';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

import { renderWithProviders } from '../../test/render.tsx';
import { server } from '../../test/setup.ts';
import type { CapabilityView, RouteView } from './api.ts';
import { CreateDivertAction, DeleteDivertAction } from './DivertActions.tsx';

const AVAILABLE: CapabilityView = { status: 'AVAILABLE', reason: 'ok', brokerXmlSnippet: null };

function clusterHandler() {
  return http.get('*/api/v1/clusters/c1', () =>
    HttpResponse.json({
      id: 'c1',
      name: 'c1',
      description: null,
      topology: { nodes: [] },
      capabilities: {
        managementRead: AVAILABLE,
        managementWrite: AVAILABLE,
        notifications: AVAILABLE,
        messageIo: AVAILABLE,
        slowConsumerDetection: AVAILABLE,
        versionGates: [],
      },
      health: { level: 'OK', reasons: [] },
      environmentId: null,
    }),
  );
}

function meHandler(permissions: string[] = ['*']) {
  return http.get('*/api/v1/auth/me', () =>
    HttpResponse.json({
      id: 'u1',
      username: 'admin',
      mustChangePassword: false,
      grants: [{ scopeType: 'GLOBAL', scopeId: null, permissions }],
    }),
  );
}

function outcome(dryRun: boolean) {
  return {
    dryRun,
    cap: 0,
    overCap: false,
    totalAffected: 0,
    anyFailed: false,
    nodes: [
      {
        nodeId: 'n1',
        nodeName: 'node-a',
        status: dryRun ? 'WOULD_APPLY' : 'APPLIED',
        affected: null,
        error: null,
      },
    ],
  };
}

const BROKER_XML = '<diverts>\n  <divert name="audit-copy">\n  </divert>\n</diverts>\n';

const DIVERT: RouteView = {
  id: 'artemis.divert/audit-copy',
  kind: 'artemis.divert',
  kindLabel: { singular: 'Divert', plural: 'Diverts' },
  name: 'audit-copy',
  routeClass: 'FORWARD',
  source: 'artemis.address/ORDER.IN',
  target: 'artemis.address/AUDIT.IN',
  nativeAttributes: { exclusive: 'false' },
  owner: 'OPERATOR',
  nodesPresent: 1,
  nodesTotal: 1,
  perNode: [{ nodeId: 'n1', nodeName: 'node-a' }],
};

describe('creating a divert', () => {
  it('states the configuration drift and shows the broker.xml before the create is offered', async () => {
    server.use(
      clusterHandler(),
      meHandler(),
      http.post('*/api/v1/clusters/c1/artemis/diverts', () =>
        HttpResponse.json({ outcome: outcome(true), brokerXml: BROKER_XML }),
      ),
    );
    const user = userEvent.setup();
    renderWithProviders(<CreateDivertAction clusterId="c1" />);

    await user.click(await screen.findByRole('button', { name: 'Create divert' }));
    await user.type(await screen.findByRole('textbox', { name: /Name/ }), 'audit-copy');
    await user.type(screen.getByRole('textbox', { name: /Divert messages from/ }), 'ORDER.IN');
    await user.type(screen.getByRole('textbox', { name: /Divert messages to/ }), 'AUDIT.IN');
    await user.click(screen.getByRole('button', { name: 'Preview' }));

    // The consequence is stated with the action, not behind a dismissible notice,
    // and it is the drift consequence — not a claim that the divert is temporary.
    expect(await screen.findByText(/stays on the broker across restarts/)).toBeInTheDocument();
    expect(screen.queryByText(/lost when the broker restarts/)).not.toBeInTheDocument();
    expect(screen.getByText(/Add this to broker.xml/)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Copy' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Create on every live node' })).toBeInTheDocument();
  });
});

describe('the divert form', () => {
  it('checks a field on blur, with the message beside it', async () => {
    server.use(clusterHandler(), meHandler());
    const user = userEvent.setup();
    renderWithProviders(<CreateDivertAction clusterId="c1" />);

    await user.click(await screen.findByRole('button', { name: 'Create divert' }));
    await user.type(await screen.findByRole('textbox', { name: /Name/ }), 'bad,name');
    await user.tab();
    expect(await screen.findByText(/cannot contain whitespace or any of/)).toBeInTheDocument();

    await user.type(screen.getByRole('textbox', { name: /Divert messages from/ }), 'ORDER.IN');
    await user.type(screen.getByRole('textbox', { name: /Divert messages to/ }), 'ORDER.IN');
    await user.tab();
    expect(await screen.findByText(/cannot forward to the address it reads from/)).toBeInTheDocument();
  });

  it('puts a server field error on its field and focuses it', async () => {
    server.use(
      clusterHandler(),
      meHandler(),
      http.post('*/api/v1/clusters/c1/artemis/diverts', () =>
        HttpResponse.json(
          {
            type: 'validation',
            title: 'Invalid request',
            detail: 'One or more fields are invalid.',
            errors: [
              { field: 'routingName', message: 'ignored' },
              { field: 'forwardingAddressDistinct', message: 'Forwards to itself.' },
            ],
          },
          { status: 400 },
        ),
      ),
    );
    const user = userEvent.setup();
    renderWithProviders(<CreateDivertAction clusterId="c1" />);

    await user.click(await screen.findByRole('button', { name: 'Create divert' }));
    await user.type(await screen.findByRole('textbox', { name: /Name/ }), 'copy');
    await user.type(screen.getByRole('textbox', { name: /Divert messages from/ }), 'A');
    await user.type(screen.getByRole('textbox', { name: /Divert messages to/ }), 'B');
    await user.click(screen.getByRole('button', { name: 'Preview' }));

    expect(await screen.findByText('Forwards to itself.')).toBeInTheDocument();
    await waitFor(() => expect(screen.getByRole('textbox', { name: /Divert messages to/ })).toHaveFocus());
  });

  it('freezes what was previewed and refuses to offer a create every node refuses', async () => {
    server.use(
      clusterHandler(),
      meHandler(),
      http.post('*/api/v1/clusters/c1/artemis/diverts', () =>
        HttpResponse.json({
          outcome: {
            ...outcome(true),
            nodes: [
              {
                nodeId: 'n1',
                nodeName: 'node-a',
                status: 'FAILED',
                affected: null,
                error: 'This divert would complete a cycle of diverts: A → B → A.',
              },
            ],
          },
          brokerXml: BROKER_XML,
        }),
      ),
    );
    const user = userEvent.setup();
    renderWithProviders(<CreateDivertAction clusterId="c1" />);

    await user.click(await screen.findByRole('button', { name: 'Create divert' }));
    await user.type(await screen.findByRole('textbox', { name: /Name/ }), 'ba');
    await user.type(screen.getByRole('textbox', { name: /Divert messages from/ }), 'B');
    await user.type(screen.getByRole('textbox', { name: /Divert messages to/ }), 'A');
    await user.click(screen.getByRole('button', { name: 'Preview' }));

    expect(await screen.findByText(/complete a cycle of diverts/)).toBeInTheDocument();
    expect(screen.getByText(/Every node refuses it/)).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Create on every live node' })).not.toBeInTheDocument();
    expect(screen.getByRole('textbox', { name: /Name/ })).toHaveAttribute('readonly');
    await user.click(screen.getByRole('button', { name: 'Edit' }));
    expect(screen.getByRole('textbox', { name: /Name/ })).not.toHaveAttribute('readonly');
  });
});

describe('the divert dialog on the keyboard', () => {
  it('opens from the keyboard, dismisses with Escape and returns focus to the trigger', async () => {
    server.use(clusterHandler(), meHandler());
    const user = userEvent.setup();
    renderWithProviders(<CreateDivertAction clusterId="c1" />);

    const trigger = await screen.findByRole('button', { name: 'Create divert' });
    trigger.focus();
    await user.keyboard('{Enter}');
    const dialog = await screen.findByRole('dialog');
    await waitFor(() => expect(dialog).toContainElement(document.activeElement as HTMLElement | null));

    await user.keyboard('{Escape}');
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
    await waitFor(() => expect(trigger).toHaveFocus());
  });
});

describe('deleting a divert', () => {
  it('is not offered for a divert message capture owns', async () => {
    server.use(clusterHandler(), meHandler());
    renderWithProviders(<DeleteDivertAction clusterId="c1" divert={{ ...DIVERT, owner: 'MESSAGE_CAPTURE' }} />);
    expect(await screen.findByText('Owned by message capture')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /^Delete divert/ })).not.toBeInTheDocument();
  });

  it('previews per node and arms only on the divert name typed exactly', async () => {
    server.use(
      clusterHandler(),
      meHandler(),
      http.delete('*/api/v1/clusters/c1/artemis/diverts/audit-copy', ({ request }) =>
        HttpResponse.json(outcome(new URL(request.url).searchParams.get('dryRun') === 'true')),
      ),
    );
    const user = userEvent.setup();
    renderWithProviders(<DeleteDivertAction clusterId="c1" divert={DIVERT} />);

    await user.click(await screen.findByRole('button', { name: /^Delete divert/ }));
    expect(await screen.findByText(/AUDIT.IN stops receiving a copy/)).toBeInTheDocument();

    const confirm = await screen.findByRole('button', { name: 'Delete on every live node' });
    await waitFor(() => expect(confirm).toBeEnabled());
  });

  it('can be dismissed with the keyboard, returning focus to the trigger', async () => {
    server.use(
      clusterHandler(),
      meHandler(),
      http.delete('*/api/v1/clusters/c1/artemis/diverts/audit-copy', () => HttpResponse.json(outcome(true))),
    );
    const user = userEvent.setup();
    renderWithProviders(<DeleteDivertAction clusterId="c1" divert={DIVERT} />);

    const trigger = await screen.findByRole('button', { name: /^Delete divert/ });
    await user.click(trigger);
    const dialog = await screen.findByRole('dialog');
    await waitFor(() => expect(dialog).toContainElement(document.activeElement as HTMLElement | null));

    await user.keyboard('{Escape}');
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
    await waitFor(() => expect(trigger).toHaveFocus());
  });
});

describe('a divert delete that could not be previewed', () => {
  it('offers no delete, and says nothing was deleted', async () => {
    server.use(
      clusterHandler(),
      meHandler(),
      http.delete('*/api/v1/clusters/c1/artemis/diverts/audit-copy', () =>
        HttpResponse.json({ title: 'Preview failed', detail: 'node-a did not answer.' }, { status: 502 }),
      ),
    );
    const user = userEvent.setup();
    renderWithProviders(<DeleteDivertAction clusterId="c1" divert={DIVERT} />);

    await user.click(await screen.findByRole('button', { name: /^Delete divert/ }));
    const dialog = await screen.findByRole('dialog');

    expect(await within(dialog).findByText('node-a did not answer.')).toBeInTheDocument();
    expect(within(dialog).getByText('The preview could not be taken.')).toBeInTheDocument();
    expect(within(dialog).getAllByText(/Nothing was deleted/).length).toBeGreaterThan(0);
    expect(within(dialog).queryByRole('textbox')).not.toBeInTheDocument();
    // The delete is visible and cannot be armed; the reason is what the button is described by.
    const remove = within(dialog).getByRole('button', { name: 'Delete on every live node' });
    expect(remove).toBeDisabled();
    expect(remove).toHaveAccessibleDescription(/Nothing was deleted/);
  });

  it('shows the per-node result of a delete that ran, in the dialog that armed it', async () => {
    server.use(
      clusterHandler(),
      meHandler(),
      http.delete('*/api/v1/clusters/c1/artemis/diverts/audit-copy', ({ request }) =>
        HttpResponse.json(outcome(new URL(request.url).searchParams.get('dryRun') === 'true')),
      ),
    );
    const user = userEvent.setup();
    renderWithProviders(<DeleteDivertAction clusterId="c1" divert={DIVERT} />);

    await user.click(await screen.findByRole('button', { name: /^Delete divert/ }));
    const confirm = await screen.findByRole('button', { name: 'Delete on every live node' });
    await waitFor(() => expect(confirm).toBeEnabled());
    await holdButton(confirm);

    expect(await screen.findByText('Applied to all 1 nodes')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Close' })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Delete on every live node' })).not.toBeInTheDocument();
  });
});
