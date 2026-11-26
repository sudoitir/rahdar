import { describe, expect, it } from 'vitest';
import { http, HttpResponse } from 'msw';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

import { holdButton } from '../../test/hold.ts';
import { renderAppAt } from '../../test/render.tsx';
import { server } from '../../test/setup.ts';
import { mockKafkaCluster } from '../../test/kafkaCluster.ts';
import { prettySchema } from './schemasApi.ts';

const SUBJECT = {
  name: 'orders-value',
  type: 'AVRO',
  compatibility: 'BACKWARD',
  compatibilityNote: null,
  schemaText: '{"type":"record","name":"Order"}',
  versions: [
    { id: 7, version: 1, type: 'AVRO', schema: '{}', deleted: false },
    { id: 8, version: 2, type: 'AVRO', schema: '{}', deleted: true },
  ],
};

const PREVIEW = {
  etag: 'e1',
  subject: 'orders-value',
  action: 'delete',
  before: ['version=2'],
  after: [],
  compatibilityOutcome: null,
  checkOnApply: false,
  alreadyRegistered: false,
  existingVersionId: null,
  note: 'Nothing has been written.',
  statement: 'Nothing has been written.',
  writesNothing: true,
};

function serve() {
  mockKafkaCluster();
  server.use(http.get('*/api/v1/clusters/k1/kafka/schemas/orders-value', () => HttpResponse.json(SUBJECT)));
}

describe('a schema subject', () => {
  it('shows the type, compatibility, versions and the schema text', async () => {
    serve();
    renderAppAt('/clusters/k1/kafka/schemas/orders-value');

    expect(await screen.findByRole('heading', { name: 'orders-value' })).toBeInTheDocument();
    expect(await screen.findByText('AVRO')).toBeInTheDocument();
    expect(screen.getByText('BACKWARD')).toBeInTheDocument();
    expect(screen.getByText('1, 2 (deleted)')).toBeInTheDocument();
    expect(screen.getByRole('region', { name: 'Schema of orders-value' }).textContent).toBe(
      prettySchema(SUBJECT.schemaText),
    );
  });

  it('soft-deletes only after a hold, and says it cannot be undone from here', async () => {
    const calls: { method: string; url: string; match?: string }[] = [];
    serve();
    server.use(
      http.post('*/api/v1/clusters/k1/kafka/schemas/orders-value\\:delete-preview', async ({ request }) => {
        calls.push({ method: 'preview', url: request.url });
        return HttpResponse.json(PREVIEW);
      }),
      http.delete('*/api/v1/clusters/k1/kafka/schemas/orders-value', ({ request }) => {
        calls.push({ method: 'delete', url: request.url, match: request.headers.get('If-Match') ?? undefined });
        return new HttpResponse(null, { status: 204 });
      }),
    );
    const user = userEvent.setup();
    renderAppAt('/clusters/k1/kafka/schemas/orders-value');

    await user.click(await screen.findByRole('button', { name: 'Delete subject' }));
    const dialog = await screen.findByRole('dialog', { name: 'Delete subject orders-value' });
    expect(dialog).toHaveTextContent('hides subject orders-value from the default list');
    expect(dialog).toHaveTextContent('cannot be undone from here');
    expect(dialog).toHaveTextContent('Nothing has been written.');

    const hold = screen.getByRole('button', { name: 'Delete subject orders-value' });
    await user.click(hold);
    expect(calls.map((call) => call.method)).toEqual(['preview']);

    await holdButton(hold);
    await waitFor(() => expect(calls.map((call) => call.method)).toEqual(['preview', 'delete']));
    expect(calls[1].url).toContain('permanent=false');
    expect(calls[1].match).toBe('e1');
  });

  it('says a permanent delete cannot be restored', async () => {
    serve();
    server.use(
      http.post('*/api/v1/clusters/k1/kafka/schemas/orders-value\\:delete-preview', () =>
        HttpResponse.json({
          ...PREVIEW,
          note: 'This soft-deletes the subject and then deletes the schema. The schemas cannot be restored. Nothing has been written.',
        }),
      ),
    );
    const user = userEvent.setup();
    renderAppAt('/clusters/k1/kafka/schemas/orders-value');

    await user.click(await screen.findByRole('button', { name: 'Permanently delete' }));
    const dialog = await screen.findByRole('dialog', { name: 'Permanently delete subject orders-value' });
    expect(dialog).toHaveTextContent('The schemas cannot be restored.');
  });
});

describe('a subject compatibility change', () => {
  it('does not offer BACKWARD when the subject has no level of its own', async () => {
    mockKafkaCluster();
    server.use(
      http.get('*/api/v1/clusters/k1/kafka/schemas/orders-value', () =>
        HttpResponse.json({ ...SUBJECT, compatibility: null, compatibilityNote: 'Registry default' }),
      ),
    );
    const user = userEvent.setup();
    renderAppAt('/clusters/k1/kafka/schemas/orders-value');

    expect(await screen.findByText('Registry default')).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Set compatibility' }));
    const dialog = await screen.findByRole('dialog', { name: 'Set compatibility of orders-value' });
    expect(within(dialog).getByRole('combobox', { name: 'Compatibility' })).not.toHaveTextContent('BACKWARD');
    await user.click(within(dialog).getByRole('button', { name: 'Preview compatibility' }));
    expect(await within(dialog).findByText('Choose a compatibility level.')).toBeInTheDocument();
  });

  it('does not let a slow delete preview open the level review', async () => {
    let release: () => void = () => {};
    const held = new Promise<void>((resolve) => {
      release = resolve;
    });
    serve();
    server.use(
      http.post('*/api/v1/clusters/k1/kafka/schemas/orders-value\\:delete-preview', async () => {
        await held;
        return HttpResponse.json(PREVIEW);
      }),
    );
    const user = userEvent.setup();
    renderAppAt('/clusters/k1/kafka/schemas/orders-value');

    await user.click(await screen.findByRole('button', { name: 'Delete subject' }));
    expect(screen.getByRole('button', { name: 'Set compatibility' })).toBeDisabled();
    expect(screen.queryByRole('dialog', { name: 'Set compatibility of orders-value' })).not.toBeInTheDocument();

    release();
    expect(await screen.findByRole('dialog', { name: 'Delete subject orders-value' })).toBeInTheDocument();
    expect(screen.queryByRole('dialog', { name: 'Set compatibility of orders-value' })).not.toBeInTheDocument();
  });
});

describe('prettySchema', () => {
  it('pretty-prints JSON and leaves other text alone', () => {
    expect(prettySchema('{"type":"record","name":"Order"}')).toBe('{\n  "type": "record",\n  "name": "Order"\n}');
    expect(prettySchema('syntax = "proto3";')).toBe('syntax = "proto3";');
  });
});
