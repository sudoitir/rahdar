import { describe, expect, it } from 'vitest';
import { http, HttpResponse } from 'msw';
import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

import { holdButton } from '../../test/hold.ts';
import { renderAppAt } from '../../test/render.tsx';
import { server } from '../../test/setup.ts';
import { mockKafkaCluster } from '../../test/kafkaCluster.ts';

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
    expect(screen.getByText('{"type":"record","name":"Order"}')).toBeInTheDocument();
  });

  it('soft-deletes only after a hold, and says the subject can be restored', async () => {
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
