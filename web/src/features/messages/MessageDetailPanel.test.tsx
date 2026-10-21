import { describe, expect, it, vi } from 'vitest';
import { http, HttpResponse } from 'msw';
import { screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

import { renderWithProviders } from '../../test/render.tsx';
import { server } from '../../test/setup.ts';
import { MessageDetailPanel } from './MessageDetailPanel.tsx';
import * as downloads from '../../ui/download.ts';

function detail(over: Record<string, unknown> = {}) {
  return {
    position: '146',
    type: 3,
    durable: true,
    priority: 0,
    timestamp: 1788501365987,
    expiration: 0,
    size: 8184,
    groupId: null,
    correlationId: null,
    userId: null,
    body: 'PPPPP',
    bodyEncoding: 'TEXT',
    contentType: null,
    bodyTruncated: false,
    observedLimitBytes: null,
    node: '11111111-1111-1111-1111-111111111111',
    stringProperties: { orderId: 'BIG-1' },
    intProperties: {},
    longProperties: {},
    doubleProperties: {},
    booleanProperties: {},
    redactions: [],
    withheld: [],
    ...over,
  };
}

function mockDetail(body: Record<string, unknown>) {
  server.use(http.get('*/api/v1/clusters/:c/resources/artemis.queue/:q/messages/:id', () => HttpResponse.json(body)));
}

describe('MessageDetailPanel', () => {
  const base = {
    clusterId: 'c1',
    subscriptionName: 'PHASE3.SRC',
    onClose: () => {},
  };

  it('labels a masked property in words and explains a withheld body instead of dumping it', async () => {
    mockDetail(
      detail({
        stringProperties: { contact: '[redacted email]' },
        body: null,
        bodyEncoding: 'BASE64',
        redactions: [
          { location: 'PROPERTY', path: 'contact', dataClass: 'EMAIL', label: 'email', action: 'REDACT', clear: false },
        ],
        withheld: [
          { location: 'BODY', reason: 'Binary body cannot be classified, so it is withheld.', settingKey: null },
        ],
      }),
    );
    renderWithProviders(<MessageDetailPanel {...base} messageId="146" />);

    expect(await screen.findByText('[redacted email]')).toBeInTheDocument();
    expect(screen.getByText('Masked: email')).toBeInTheDocument();
    expect(screen.getByText('Body withheld')).toBeInTheDocument();
    expect(screen.getByText('Binary body cannot be classified, so it is withheld.')).toBeInTheDocument();
    expect(screen.queryByText(/00000000/)).not.toBeInTheDocument();
  });

  it('shows the truncation banner with the broker.xml snippet when bodyTruncated', async () => {
    mockDetail(detail({ bodyTruncated: true, observedLimitBytes: 256 }));
    renderWithProviders(<MessageDetailPanel {...base} messageId="146" />);

    expect(await screen.findByText('This message is truncated')).toBeInTheDocument();
    expect(screen.getAllByText(/management-message-attribute-size-limit/).length).toBeGreaterThan(0);
    expect(screen.getByText(/connect the Core client/)).toBeInTheDocument();
  });

  it('dumps a binary body as bytes, never as text', async () => {
    mockDetail(detail({ bodyEncoding: 'BASE64', body: 'AQIDBA==' }));
    renderWithProviders(<MessageDetailPanel {...base} messageId="146" />);

    expect(await screen.findByText('binary')).toBeInTheDocument();
    // A hex + ASCII dump of the four bytes, not a TextDecoder'd rendering of them.
    expect(screen.getByText(/00000000 01 02 03 04/)).toBeInTheDocument();
  });

  it('names a recognised binary container rather than calling it plain binary', async () => {
    // gzip magic bytes 1f 8b, base64-encoded.
    mockDetail(detail({ bodyEncoding: 'BASE64', body: 'H4sIAAAAAAAA' }));
    renderWithProviders(<MessageDetailPanel {...base} messageId="146" />);

    expect(await screen.findByText('binary · gzip')).toBeInTheDocument();
  });

  it('a truncated JSON body reports truncation, not a malformed payload', async () => {
    mockDetail(detail({ bodyTruncated: true, body: '{"orders":[{"id":1},{"id":2' }));
    renderWithProviders(<MessageDetailPanel {...base} messageId="146" />);

    expect(await screen.findByText(/the broker truncated this body/i)).toBeInTheDocument();
  });

  it('formats a JSON body and offers the raw view', async () => {
    mockDetail(detail({ body: '{"b":2,"a":1}' }));
    renderWithProviders(<MessageDetailPanel {...base} messageId="146" />);

    expect(await screen.findByText('JSON')).toBeInTheDocument();
    expect(screen.getByText('Formatted')).toBeInTheDocument();
    expect(screen.getByText('Raw')).toBeInTheDocument();
  });

  it('does not show the truncation banner for a whole message', async () => {
    mockDetail(detail({ bodyTruncated: false }));
    renderWithProviders(<MessageDetailPanel {...base} messageId="146" />);

    expect(await screen.findByText('String properties')).toBeInTheDocument();
    expect(screen.queryByText('This message is truncated')).not.toBeInTheDocument();
  });

  it('shows JSON a producer sent as bytes as JSON, without saying anything about bytes', async () => {
    mockDetail(detail({ type: 4, body: '{"status":"FAILED"}', bodyCompression: 'gzip' }));
    renderWithProviders(<MessageDetailPanel {...base} messageId="146" />);

    expect(await screen.findByText('JSON · gzip')).toBeInTheDocument();
    expect(screen.getByText('bytes')).toBeInTheDocument();
    expect(screen.queryByText(/decod|Shown as bytes|Core client returned/i)).not.toBeInTheDocument();
  });

  it('browses a JSON body as a tree and copies a field path for the SQL console', async () => {
    const user = userEvent.setup();
    mockDetail(detail({ body: '{"order":{"id":4471,"lines":[{"sku":"acme-1"}]},"status":"FAILED"}' }));
    renderWithProviders(<MessageDetailPanel {...base} messageId="146" />);

    await user.click(await screen.findByRole('radio', { name: 'Tree' }));
    const tree = screen.getByRole('tree', { name: 'Message body' });
    expect(within(tree).getByText('{2}')).toBeInTheDocument();
    expect(within(tree).queryByText('id')).not.toBeInTheDocument();

    await user.click(within(tree).getByText('order'));
    expect(within(tree).getByText('id')).toBeInTheDocument();

    await user.click(within(tree).getByText('id'));
    expect(screen.getByText("body->>'order.id'")).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Copy path' })).toBeInTheDocument();
  });

  it('filters the tree by key or value and opens the path to each match', async () => {
    const user = userEvent.setup();
    mockDetail(detail({ body: '{"order":{"lines":[{"sku":"acme-1"}]},"status":"FAILED"}' }));
    renderWithProviders(<MessageDetailPanel {...base} messageId="146" />);

    await user.click(await screen.findByRole('radio', { name: 'Tree' }));
    await user.type(screen.getByLabelText('Filter keys and values'), 'acme');

    const tree = screen.getByRole('tree', { name: 'Message body' });
    expect(within(tree).getByText('"acme-1"')).toBeInTheDocument();
    expect(within(tree).queryByText('status')).not.toBeInTheDocument();
  });

  it('renders keys and values as text, never as markup', async () => {
    const user = userEvent.setup();
    mockDetail(detail({ body: '{"<img src=x onerror=alert(1)>":"<b>bold</b>"}' }));
    renderWithProviders(<MessageDetailPanel {...base} messageId="146" />);

    await user.click(await screen.findByRole('radio', { name: 'Tree' }));
    expect(screen.getByText('<img src=x onerror=alert(1)>')).toBeInTheDocument();
    expect(screen.getByText('"<b>bold</b>"')).toBeInTheDocument();
    expect(document.querySelector('img[src="x"]')).toBeNull();
  });

  it('offers no tree for a body that is not JSON', async () => {
    mockDetail(detail({ body: '<a><b/></a>' }));
    renderWithProviders(<MessageDetailPanel {...base} messageId="146" />);

    expect(await screen.findByRole('radio', { name: 'Formatted' })).toBeInTheDocument();
    expect(screen.queryByRole('radio', { name: 'Tree' })).not.toBeInTheDocument();
  });

  it('downloads a binary body as its bytes, not as base64', async () => {
    const user = userEvent.setup();
    const spy = vi.spyOn(downloads, 'download').mockImplementation(() => {});
    mockDetail(detail({ bodyEncoding: 'BASE64', body: 'AQIDBA==' }));
    renderWithProviders(<MessageDetailPanel {...base} messageId="146" />);

    await user.click(await screen.findByRole('button', { name: 'Download' }));

    const [name, blob] = spy.mock.calls[0];
    expect(name).toBe('message-146.bin');
    expect(new Uint8Array(await (blob as Blob).arrayBuffer())).toEqual(new Uint8Array([1, 2, 3, 4]));
    spy.mockRestore();
  });

  it('states what the message is and its headers as terms and values, and its properties by type under their own headings', async () => {
    mockDetail(
      detail({
        groupId: 'g-1',
        stringProperties: { orderId: 'BIG-1' },
        intProperties: { retries: 3 },
      }),
    );
    renderWithProviders(<MessageDetailPanel {...base} messageId="146" />);

    expect(await screen.findByRole('heading', { level: 3, name: 'Headers' })).toBeInTheDocument();
    const headers = screen.getByRole('group', { name: 'Message headers' });
    expect(within(headers).getByText('durable')).toBeInTheDocument();
    expect(within(headers).getByText('8184 bytes')).toBeInTheDocument();
    expect(within(headers).getByText('never')).toBeInTheDocument();
    expect(within(headers).getByText('g-1')).toBeInTheDocument();

    const strings = screen.getByRole('group', { name: 'String properties' });
    expect(within(strings).getByText('orderId')).toBeInTheDocument();
    expect(within(strings).getByText('BIG-1')).toBeInTheDocument();
    expect(within(screen.getByRole('group', { name: 'Integer properties' })).getByText('retries')).toBeInTheDocument();
    expect(screen.queryByRole('heading', { name: 'Long properties' })).not.toBeInTheDocument();
    expect(screen.getByRole('heading', { level: 3, name: 'Body' })).toBeInTheDocument();
  });

  it('holds the drawer open at its final size with a labelled status while the message loads', async () => {
    server.use(
      http.get('*/api/v1/clusters/:c/resources/artemis.queue/:q/messages/:id', async () => {
        await new Promise(() => {});
      }),
    );
    renderWithProviders(<MessageDetailPanel {...base} messageId="146" />);

    const dialog = await screen.findByRole('dialog', { name: 'Message 146' });
    expect(await within(dialog).findByRole('status')).toHaveTextContent('Loading the message');
  });

  it('states why the message could not be read, with a retry that reads it again', async () => {
    let attempts = 0;
    server.use(
      http.get('*/api/v1/clusters/:c/resources/artemis.queue/:q/messages/:id', () => {
        attempts += 1;
        return attempts === 1
          ? HttpResponse.json({ title: 'Cluster unreachable', detail: 'No node answered.' }, { status: 502 })
          : HttpResponse.json(detail());
      }),
    );
    const user = userEvent.setup();
    renderWithProviders(<MessageDetailPanel {...base} messageId="146" />);

    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent('Rahdar failed to complete the request');
    await user.click(within(alert).getByRole('button', { name: 'Retry' }));

    expect(await screen.findByRole('heading', { level: 3, name: 'Headers' })).toBeInTheDocument();
    expect(attempts).toBe(2);
  });
});
