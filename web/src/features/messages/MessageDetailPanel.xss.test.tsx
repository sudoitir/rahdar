import { describe, expect, it } from 'vitest';
import { http, HttpResponse } from 'msw';
import { screen } from '@testing-library/react';

import { renderWithProviders } from '../../test/render.tsx';
import { server } from '../../test/setup.ts';
import { MessageDetailPanel } from './MessageDetailPanel.tsx';

const BODY = '<img src=x onerror=alert(1)><script>alert(1)</script> plain text';
const HEADER_NAME = '"><svg onload=alert(2)>';
const HEADER_VALUE = '<iframe srcdoc="<script>alert(3)</script>"></iframe>';

/** What an attacker who controls a message wants the page to become: markup, not text. */
const MARKUP = 'script, img, iframe, [onerror], [onload], [srcdoc]';

describe('a message that carries markup', () => {
  it('renders its body, and its headers as text, never as elements', async () => {
    server.use(
      http.get('*/api/v1/clusters/:c/resources/artemis.queue/:q/messages/:id', () =>
        HttpResponse.json({
          position: '146',
          type: 3,
          durable: true,
          priority: 0,
          timestamp: 1788501365987,
          expiration: 0,
          size: 80,
          groupId: null,
          correlationId: null,
          userId: null,
          body: BODY,
          bodyEncoding: 'TEXT',
          contentType: null,
          bodyTruncated: false,
          observedLimitBytes: null,
          node: '11111111-1111-1111-1111-111111111111',
          stringProperties: { [HEADER_NAME]: HEADER_VALUE },
          intProperties: {},
          longProperties: {},
          doubleProperties: {},
          booleanProperties: {},
          redactions: [],
          withheld: [],
        }),
      ),
    );

    const { container } = renderWithProviders(
      <MessageDetailPanel clusterId="c1" queueName="PHASE3.SRC" messageId="146" onClose={() => {}} />,
    );

    expect(await screen.findByText(HEADER_VALUE)).toBeInTheDocument();
    expect(screen.getByText(HEADER_NAME)).toBeInTheDocument();
    expect(container.ownerDocument.body.textContent).toContain(BODY);
    expect(container.ownerDocument.body.querySelector(MARKUP)).toBeNull();
  });
});
