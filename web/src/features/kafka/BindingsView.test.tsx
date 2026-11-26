import { describe, expect, it } from 'vitest';
import { http, HttpResponse } from 'msw';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

import { holdButton } from '../../test/hold.ts';
import { renderAppAt } from '../../test/render.tsx';
import { server } from '../../test/setup.ts';
import { mockKafkaCluster } from '../../test/kafkaCluster.ts';

const PREVIEW = {
  etag: 'b1',
  subject: 'orders-value',
  action: 'binding',
  before: [],
  after: ['serde=avro'],
  compatibilityOutcome: null,
  checkOnApply: false,
  alreadyRegistered: false,
  existingVersionId: null,
  note: null,
  statement: 'Nothing has been written.',
  writesNothing: true,
};

function serve(items: object[] = [], permissions: string[] = ['*']) {
  mockKafkaCluster(permissions);
  server.use(
    http.get('http://localhost:3000/api/v1/clusters/k1/kafka/schemas/bindings', () => HttpResponse.json({ items })),
  );
}

describe('schema bindings', () => {
  it("shows the registry's own reason when it refuses the listing, not a bare error", async () => {
    mockKafkaCluster(['*']);
    server.use(
      http.get('http://localhost:3000/api/v1/clusters/k1/kafka/schemas/bindings', () =>
        HttpResponse.json(
          {
            type: 'https://rahdar.dev/problems/schema-unavailable',
            title: 'The schema registry is unavailable',
            status: 409,
            detail: 'confluent has no bindings endpoint.',
          },
          { status: 409, headers: { 'Content-Type': 'application/problem+json' } },
        ),
      ),
    );
    renderAppAt('/clusters/k1/kafka/schemas?tab=bindings');

    expect(await screen.findByText('The schema registry is unavailable')).toBeInTheDocument();
    expect(screen.getByText('confluent has no bindings endpoint.')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Retry' })).not.toBeInTheDocument();
    expect(screen.queryByText('No bindings')).not.toBeInTheDocument();
  });

  it('lists a topic side and its serde', async () => {
    serve([{ topic: 'orders', side: 'value', serde: 'avro' }]);
    renderAppAt('/clusters/k1/kafka/schemas?tab=bindings');

    const table = await screen.findByRole('table', { name: 'Schema bindings' });
    expect(await within(table).findByRole('rowheader', { name: 'orders' })).toBeInTheDocument();
    expect(within(table).getByRole('cell', { name: 'Value' })).toBeInTheDocument();
    expect(within(table).getByRole('cell', { name: 'Avro' })).toBeInTheDocument();
  });

  it('saves only after a hold, and a click does nothing', async () => {
    const calls: { path: string; body: Record<string, unknown> }[] = [];
    serve();
    server.use(
      http.post('*/api/v1/clusters/k1/kafka/schemas/bindings\\:preview', async ({ request }) => {
        calls.push({ path: 'preview', body: (await request.json()) as Record<string, unknown> });
        return HttpResponse.json(PREVIEW);
      }),
      http.put('*/api/v1/clusters/k1/kafka/schemas/bindings', async ({ request }) => {
        calls.push({ path: 'apply', body: (await request.json()) as Record<string, unknown> });
        return HttpResponse.json({ topic: 'orders', side: 'value', serde: 'avro' });
      }),
    );
    const user = userEvent.setup();
    renderAppAt('/clusters/k1/kafka/schemas?tab=bindings');

    await user.type(await screen.findByRole('textbox', { name: 'Topic' }), 'orders');
    await user.click(screen.getByRole('button', { name: 'Save binding' }));
    const dialog = await screen.findByRole('dialog', { name: 'Save binding for orders value' });
    expect(dialog).toHaveTextContent('Nothing has been written.');

    const hold = screen.getByRole('button', { name: 'Save binding for orders value' });
    await user.click(hold);
    expect(calls.map((call) => call.path)).toEqual(['preview']);

    await holdButton(hold);
    await waitFor(() => expect(calls.map((call) => call.path)).toEqual(['preview', 'apply']));
    expect(calls[1].body).toMatchObject({ topic: 'orders', side: 'value', serde: 'avro', etag: 'b1' });
  });

  it('offers Avro, Protobuf and JSON Schema, and no custom name', async () => {
    serve();
    const user = userEvent.setup();
    renderAppAt('/clusters/k1/kafka/schemas?tab=bindings');

    await user.click(await screen.findByRole('combobox', { name: 'Serde' }));
    expect(await screen.findByRole('option', { name: 'Avro' })).toBeInTheDocument();
    expect(screen.getByRole('option', { name: 'Protobuf' })).toBeInTheDocument();
    expect(screen.getByRole('option', { name: 'JSON Schema' })).toBeInTheDocument();
    expect(screen.queryByRole('option', { name: 'A named serde' })).not.toBeInTheDocument();
    expect(screen.queryByRole('textbox', { name: 'Serde name' })).not.toBeInTheDocument();
  });

  it('keeps Save visible without schema:write and names that permission', async () => {
    serve([], ['schema:read']);
    const user = userEvent.setup();
    renderAppAt('/clusters/k1/kafka/schemas?tab=bindings');

    const why = await screen.findByRole('button', { name: 'Why saving a binding is unavailable' });
    expect(screen.getByRole('button', { name: 'Save binding' })).toBeDisabled();
    await user.click(why);
    expect(await screen.findByText(/schema:write/)).toBeInTheDocument();
  });
});
