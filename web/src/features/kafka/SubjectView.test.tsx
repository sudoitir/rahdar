import { describe, expect, it } from 'vitest';
import { http, HttpResponse } from 'msw';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

import { holdButton } from '../../test/hold.ts';
import { renderAppAt } from '../../test/render.tsx';
import { server } from '../../test/setup.ts';
import { mockKafkaCluster } from '../../test/kafkaCluster.ts';
import { KAFKA_KINDS, KAFKA_SYSTEM } from '../../test/wire.ts';
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

/** Mantine keeps a closed dialog mounted, so the one just opened is the last match. */
function last<T>(found: readonly T[]): T {
  return found[found.length - 1];
}

async function namedDialog(name: string) {
  return last(await screen.findAllByRole('dialog', { name }));
}

function clusterWith(findings: { capability: string; status: string; reason: string }[]) {
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

const LEVEL = {
  ...PREVIEW,
  action: 'compatibility',
  before: ['compatibility=BACKWARD'],
  after: ['compatibility=FULL'],
};

describe('a subject the operator cannot change', () => {
  it('keeps the actions visible without schema:write and names that permission', async () => {
    mockKafkaCluster(['schema:read']);
    server.use(http.get('*/api/v1/clusters/k1/kafka/schemas/orders-value', () => HttpResponse.json(SUBJECT)));
    const user = userEvent.setup();
    renderAppAt('/clusters/k1/kafka/schemas/orders-value');

    const why = await screen.findByRole('button', { name: 'Why setting compatibility is unavailable' });
    expect(screen.getByRole('button', { name: 'Set compatibility' })).toBeDisabled();
    expect(screen.getByRole('button', { name: 'Delete subject' })).toBeDisabled();
    expect(screen.getByRole('button', { name: 'Permanently delete' })).toBeDisabled();
    await user.click(why);
    expect(await screen.findByText(/schema:write/)).toBeInTheDocument();
  });

  it("keeps the subject and disables a change the registry does not offer, in the registry's words", async () => {
    const reason = 'This registry does not offer compatibility changes.';
    mockKafkaCluster();
    server.use(
      http.get('*/api/v1/clusters/k1', () =>
        HttpResponse.json(clusterWith([{ capability: 'kafka.schema-registry', status: 'UNAVAILABLE', reason }])),
      ),
      http.get('*/api/v1/clusters/k1/kafka/schemas/orders-value', () => HttpResponse.json(SUBJECT)),
    );
    const user = userEvent.setup();
    renderAppAt('/clusters/k1/kafka/schemas/orders-value');

    expect(await screen.findByText(reason)).toBeInTheDocument();
    expect(await screen.findByText('1, 2 (deleted)')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Connection settings' })).toHaveAttribute(
      'href',
      '/clusters/k1/settings?tab=clusters-connection',
    );
    expect(screen.getByRole('button', { name: 'Set compatibility' })).toBeDisabled();
    await user.click(screen.getByRole('button', { name: 'Why setting compatibility is unavailable' }));
    expect(await screen.findByText(reason)).toBeInTheDocument();
  });

  it('shows a failure to read the subject as an error, and a retry reads it again', async () => {
    let fail = true;
    mockKafkaCluster();
    server.use(
      http.get('*/api/v1/clusters/k1/kafka/schemas/orders-value', () =>
        fail
          ? HttpResponse.json({ title: 'Failed', detail: 'The registry did not answer.' }, { status: 500 })
          : HttpResponse.json(SUBJECT),
      ),
    );
    const user = userEvent.setup();
    renderAppAt('/clusters/k1/kafka/schemas/orders-value');

    expect(await screen.findByText('The registry did not answer.')).toBeInTheDocument();
    expect(screen.queryByText('1, 2 (deleted)')).not.toBeInTheDocument();
    fail = false;
    await user.click(screen.getByRole('button', { name: 'Retry' }));
    expect(await screen.findByText('1, 2 (deleted)')).toBeInTheDocument();
  });
});

describe('setting a subject compatibility', () => {
  it('applies the previewed level only after a hold, and a dismissal writes nothing', async () => {
    const calls: string[] = [];
    serve();
    server.use(
      http.post('*/api/v1/clusters/k1/kafka/schemas/orders-value\\:compatibility-preview', async ({ request }) => {
        calls.push(`preview:${((await request.json()) as { level: string }).level}`);
        return HttpResponse.json(LEVEL);
      }),
      http.post('*/api/v1/clusters/k1/kafka/schemas/orders-value\\:compatibility', async ({ request }) => {
        calls.push(`apply:${((await request.json()) as { level: string }).level}`);
        return HttpResponse.json('FULL');
      }),
    );
    const user = userEvent.setup();
    renderAppAt('/clusters/k1/kafka/schemas/orders-value');

    await user.click(await screen.findByRole('button', { name: 'Set compatibility' }));
    const choose = await namedDialog('Set compatibility of orders-value');
    await user.click(within(choose).getByRole('combobox', { name: 'Compatibility' }));
    await user.click(await screen.findByRole('option', { name: 'FULL' }));
    await user.click(within(choose).getByRole('button', { name: 'Preview compatibility' }));

    await waitFor(() =>
      expect(last(screen.getAllByRole('dialog', { name: 'Set compatibility of orders-value' }))).toHaveTextContent(
        'Nothing has been written.',
      ),
    );
    const review = last(screen.getAllByRole('dialog', { name: 'Set compatibility of orders-value' }));
    expect(calls).toEqual(['preview:FULL']);
    await holdButton(within(review).getByRole('button', { name: 'Set compatibility of orders-value' }));
    expect(await within(review).findByText('Compatibility is FULL.')).toBeInTheDocument();
    expect(calls).toEqual(['preview:FULL', 'apply:FULL']);
    await user.click(within(review).getByRole('button', { name: 'Close' }));
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
  });

  it('says the registry moved and offers a fresh preview, which reads the level again', async () => {
    let previews = 0;
    serve();
    server.use(
      http.post('*/api/v1/clusters/k1/kafka/schemas/orders-value\\:compatibility-preview', () => {
        previews += 1;
        return HttpResponse.json(LEVEL);
      }),
      http.post('*/api/v1/clusters/k1/kafka/schemas/orders-value\\:compatibility', () =>
        HttpResponse.json({ title: 'Precondition failed', detail: 'A version was registered.' }, { status: 412 }),
      ),
    );
    const user = userEvent.setup();
    renderAppAt('/clusters/k1/kafka/schemas/orders-value');

    await user.click(await screen.findByRole('button', { name: 'Set compatibility' }));
    const choose = await namedDialog('Set compatibility of orders-value');
    await user.click(within(choose).getByRole('combobox', { name: 'Compatibility' }));
    await user.click(await screen.findByRole('option', { name: 'FULL' }));
    await user.click(within(choose).getByRole('button', { name: 'Preview compatibility' }));
    await waitFor(() =>
      expect(last(screen.getAllByRole('dialog', { name: 'Set compatibility of orders-value' }))).toHaveTextContent(
        'Nothing has been written.',
      ),
    );
    const review = last(screen.getAllByRole('dialog', { name: 'Set compatibility of orders-value' }));
    await holdButton(within(review).getByRole('button', { name: 'Set compatibility of orders-value' }));

    expect(await within(review).findByText(/The registry moved since this preview/)).toBeInTheDocument();
    expect(within(review).getByRole('button', { name: 'Set compatibility of orders-value' })).toBeDisabled();
    await user.click(within(review).getByRole('button', { name: 'Preview again' }));
    await waitFor(() => expect(previews).toBe(2));
    expect(within(review).queryByText(/The registry moved since this preview/)).not.toBeInTheDocument();
  });

  it('states why a delete was refused and writes nothing', async () => {
    serve();
    server.use(
      http.post('*/api/v1/clusters/k1/kafka/schemas/orders-value\\:delete-preview', () => HttpResponse.json(PREVIEW)),
      http.delete('*/api/v1/clusters/k1/kafka/schemas/orders-value', () =>
        HttpResponse.json({ title: 'Failed', detail: 'The registry refused the delete.' }, { status: 500 }),
      ),
    );
    const user = userEvent.setup();
    renderAppAt('/clusters/k1/kafka/schemas/orders-value');

    await user.click(await screen.findByRole('button', { name: 'Delete subject' }));
    const dialog = await screen.findByRole('dialog', { name: 'Delete subject orders-value' });
    await holdButton(within(dialog).getByRole('button', { name: 'Delete subject orders-value' }));
    expect(await within(dialog).findByText('The registry refused the delete.')).toBeInTheDocument();
    expect(within(dialog).queryByText('Deleted subject orders-value.')).not.toBeInTheDocument();
  });

  it('offers a fresh delete preview when the subject changed since', async () => {
    let previews = 0;
    serve();
    server.use(
      http.post('*/api/v1/clusters/k1/kafka/schemas/orders-value\\:delete-preview', () => {
        previews += 1;
        return HttpResponse.json(PREVIEW);
      }),
      http.delete('*/api/v1/clusters/k1/kafka/schemas/orders-value', () =>
        HttpResponse.json({ title: 'Precondition failed', detail: 'It changed.' }, { status: 412 }),
      ),
    );
    const user = userEvent.setup();
    renderAppAt('/clusters/k1/kafka/schemas/orders-value');

    await user.click(await screen.findByRole('button', { name: 'Delete subject' }));
    const dialog = await screen.findByRole('dialog', { name: 'Delete subject orders-value' });
    await holdButton(within(dialog).getByRole('button', { name: 'Delete subject orders-value' }));
    expect(await within(dialog).findByText(/The registry moved since this preview/)).toBeInTheDocument();
    await user.click(within(dialog).getByRole('button', { name: 'Preview again' }));
    await waitFor(() => expect(previews).toBe(2));
  });
});
describe('prettySchema', () => {
  it('pretty-prints JSON and leaves other text alone', () => {
    expect(prettySchema('{"type":"record","name":"Order"}')).toBe('{\n  "type": "record",\n  "name": "Order"\n}');
    expect(prettySchema('syntax = "proto3";')).toBe('syntax = "proto3";');
  });
});
