import { describe, expect, it } from 'vitest';
import { http, HttpResponse } from 'msw';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

import { holdButton } from '../../test/hold.ts';
import { renderAppAt } from '../../test/render.tsx';
import { server } from '../../test/setup.ts';
import { mockKafkaCluster } from '../../test/kafkaCluster.ts';
import { KAFKA_KINDS, KAFKA_SYSTEM } from '../../test/wire.ts';

const SUBJECT = { name: 'orders-value', compatibility: 'BACKWARD', compatibilityNote: null, versions: 2 };

function cluster(findings: { capability: string; status: string; reason: string }[] = []) {
  return {
    id: 'k1',
    name: 'events',
    description: null,
    system: KAFKA_SYSTEM,
    kinds: KAFKA_KINDS,
    topology: { clusterId: 'k1', nodes: [] },
    capabilities: {
      managementRead: { status: 'AVAILABLE', reason: 'ok' },
      managementWrite: { status: 'AVAILABLE', reason: 'ok' },
      notifications: { status: 'AVAILABLE', reason: 'ok' },
      messageIo: { status: 'AVAILABLE', reason: 'ok' },
      slowConsumerDetection: { status: 'AVAILABLE', reason: 'ok' },
      versionGates: [],
      findings,
    },
    health: {
      clusterId: 'k1',
      level: 'OK',
      liveEndpointNames: [],
      splitBrain: 'NONE',
      replicationBehind: false,
      notes: [],
    },
  };
}

function serve(list: object, permissions: string[] = ['*']) {
  mockKafkaCluster(permissions);
  server.use(http.get('*/api/v1/clusters/k1/kafka/schemas', () => HttpResponse.json(list)));
}

describe('schema subjects', () => {
  it('stays up with no registry and shows the driver reason, not a sentence of our own', async () => {
    const reason = 'This driver does not speak a registry kind of glue.';
    mockKafkaCluster();
    server.use(
      http.get('*/api/v1/clusters/k1', () =>
        HttpResponse.json(cluster([{ capability: 'kafka.schema-registry', status: 'UNAVAILABLE', reason }])),
      ),
      http.get('*/api/v1/clusters/k1/kafka/schemas', () =>
        HttpResponse.json(
          {
            type: 'https://rahdar.dev/problems/schema-unavailable',
            title: 'The schema registry is unavailable',
            detail: reason,
          },
          { status: 409 },
        ),
      ),
    );
    renderAppAt('/clusters/k1/kafka/schemas');

    expect(await screen.findByText(reason)).toBeInTheDocument();
    expect(screen.queryByText('No schema registry is configured')).not.toBeInTheDocument();
    expect(screen.queryByText('No subjects')).not.toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Connection settings' })).toHaveAttribute(
      'href',
      '/clusters/k1/settings?tab=clusters-connection',
    );
    expect(await screen.findByRole('button', { name: 'Register a schema' })).toBeDisabled();
  });

  it('teaches what a subject is and offers Register when the list is empty', async () => {
    serve({ items: [], next: null });
    renderAppAt('/clusters/k1/kafka/schemas');

    expect(await screen.findByText('No subjects')).toBeInTheDocument();
    expect(screen.getByText(/named schema/)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Register a schema' })).toBeEnabled();
  });

  it('says a filter excluded every subject and clears it', async () => {
    mockKafkaCluster();
    server.use(
      http.get('*/api/v1/clusters/k1/kafka/schemas', ({ request }) => {
        const filter = new URL(request.url).searchParams.get('filter');
        return HttpResponse.json(filter ? { items: [], next: null } : { items: [SUBJECT], next: null });
      }),
    );
    const user = userEvent.setup();
    renderAppAt('/clusters/k1/kafka/schemas?q=missing');

    expect(await screen.findByText('No subject matches this filter')).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Clear filters' }));
    expect(await screen.findByRole('link', { name: 'orders-value' })).toBeInTheDocument();
  });

  it('shows a failure as an error, not as an empty list', async () => {
    mockKafkaCluster();
    server.use(
      http.get('*/api/v1/clusters/k1/kafka/schemas', () =>
        HttpResponse.json({ title: 'Failed', detail: 'The registry did not answer.' }, { status: 500 }),
      ),
    );
    renderAppAt('/clusters/k1/kafka/schemas');

    expect(await screen.findByText('Rahdar failed to complete the request')).toBeInTheDocument();
    expect(screen.getByText('The registry did not answer.')).toBeInTheDocument();
    expect(screen.queryByText('No subjects')).not.toBeInTheDocument();
    expect(screen.queryByText('0 subjects')).not.toBeInTheDocument();
  });

  it('keeps Register visible without schema:write and names that permission', async () => {
    serve({ items: [SUBJECT], next: null }, ['schema:read']);
    const user = userEvent.setup();
    renderAppAt('/clusters/k1/kafka/schemas');

    const why = await screen.findByRole('button', { name: 'Why registering a schema is unavailable' });
    expect(screen.getByRole('button', { name: 'Register a schema' })).toBeDisabled();
    await user.click(why);
    expect(await screen.findByText(/schema:write/)).toBeInTheDocument();
  });

  it('reviews the registry default and applies it with a click', async () => {
    const calls: { path: string; body: Record<string, unknown> }[] = [];
    serve({ items: [], next: null });
    server.use(
      http.post('*/api/v1/clusters/k1/kafka/schemas\\:default-compatibility-preview', async ({ request }) => {
        calls.push({ path: 'preview', body: (await request.json()) as Record<string, unknown> });
        return HttpResponse.json({
          etag: 'd1',
          subject: null,
          action: 'default-compatibility',
          before: ['compatibility=BACKWARD'],
          after: ['compatibility=FULL'],
          compatibilityOutcome: null,
          checkOnApply: false,
          alreadyRegistered: false,
          existingVersionId: null,
          note: null,
          statement: 'Nothing has been written.',
          writesNothing: true,
        });
      }),
      http.post('*/api/v1/clusters/k1/kafka/schemas\\:default-compatibility', async ({ request }) => {
        calls.push({ path: 'apply', body: (await request.json()) as Record<string, unknown> });
        return HttpResponse.json('FULL');
      }),
    );
    const user = userEvent.setup();
    renderAppAt('/clusters/k1/kafka/schemas');

    expect(await screen.findByText(/The review shows the current level/)).toBeInTheDocument();
    expect(screen.queryByText('Read in the review, before anything is written.')).not.toBeInTheDocument();
    await user.click(screen.getByRole('combobox', { name: 'New level' }));
    await user.click(await screen.findByRole('option', { name: 'FULL' }));
    await user.click(screen.getByRole('button', { name: 'Review registry default' }));

    const dialog = await screen.findByRole('dialog', { name: 'Set registry default compatibility' });
    expect(dialog).toHaveTextContent('compatibility=BACKWARD');
    expect(dialog).toHaveTextContent('Nothing has been written.');
    const hold = within(dialog).getByRole('button', { name: 'Set registry default compatibility' });
    await user.click(hold);
    expect(calls.map((call) => call.path)).toEqual(['preview']);
    await holdButton(hold);

    await waitFor(() => expect(calls.map((call) => call.path)).toEqual(['preview', 'apply']));
    expect(calls[1].body).toMatchObject({ level: 'FULL', etag: 'd1' });
  });
});
