import { describe, expect, it, vi } from 'vitest';
import { http, HttpResponse } from 'msw';
import { screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

import { renderWithProviders } from '../../test/render.tsx';
import { server } from '../../test/setup.ts';
import { FlowDetail } from './FlowDetail.tsx';

function flow(over: Record<string, unknown> = {}) {
  return {
    id: 'f1',
    clusterId: 'c1',
    nodeId: null,
    requestDestination: 'orders.request',
    replyDestination: null,
    replyKind: 'SHARED',
    state: 'AWAITING_REPLY',
    correlationId: null,
    requestedAt: '2026-09-04T10:00:00.000Z',
    deadlineAt: null,
    repliedAt: null,
    latencyMs: null,
    latencySource: 'OBSERVED',
    events: [],
    ...over,
  };
}

function event(seq: number, kind: string, detail: Record<string, unknown> | null) {
  return { seq, ts: `2026-09-04T10:00:0${seq}.000Z`, kind, nodeId: null, detail };
}

function serve(body: Record<string, unknown>) {
  server.use(http.get('*/api/v1/clusters/c1/rr/flows/f1', () => HttpResponse.json(body)));
}

function open(flowId: string | null = 'f1', onClose = () => {}) {
  return renderWithProviders(<FlowDetail clusterId="c1" flowId={flowId} onClose={onClose} />);
}

describe('FlowDetail', () => {
  it('renders nothing while no flow is selected', () => {
    open(null);
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
  });

  it('names the request destination in the title and states the flow, its reply subscription and its facts', async () => {
    serve(
      flow({
        state: 'COMPLETED',
        replyKind: 'TEMPORARY',
        correlationId: 'corr-1',
        replyDestination: 'reply.tmp',
        deadlineAt: '2026-09-04T10:00:30.000Z',
        repliedAt: '2026-09-04T10:00:02.000Z',
      }),
    );
    open();

    const dialog = await screen.findByRole('dialog', { name: 'Flow on orders.request' });
    expect(within(dialog).getByText('completed')).toBeInTheDocument();
    expect(within(dialog).getByText('temp reply subscription')).toBeInTheDocument();
    for (const label of ['Requested at', 'Replied at', 'Deadline', 'Correlation id', 'Reply destination']) {
      expect(within(dialog).getByText(label)).toBeInTheDocument();
    }
    expect(within(dialog).getByText('corr-1')).toBeInTheDocument();
    expect(within(dialog).getByText('reply.tmp')).toBeInTheDocument();
    expect(within(dialog).queryByText('Latency')).not.toBeInTheDocument();
    expect(within(dialog).queryByText('Clock skew')).not.toBeInTheDocument();
  });

  it('omits the optional facts a still-open flow does not have, and names a shared reply subscription', async () => {
    serve(flow());
    open();

    const dialog = await screen.findByRole('dialog', { name: 'Flow on orders.request' });
    expect(within(dialog).getByText('awaiting reply')).toBeInTheDocument();
    expect(within(dialog).getByText('shared reply subscription')).toBeInTheDocument();
    expect(within(dialog).getByText('Requested at')).toBeInTheDocument();
    for (const label of ['Replied at', 'Deadline', 'Correlation id', 'Reply destination']) {
      expect(within(dialog).queryByText(label)).not.toBeInTheDocument();
    }
    expect(within(dialog).getByText('No events recorded for this flow.')).toBeInTheDocument();
  });

  it('gives an observed latency its error bar and says the clock cannot resolve less', async () => {
    serve(flow({ latencyMs: 250, latencySource: 'OBSERVED', latencyBoundMs: 100 }));
    open();

    expect(await screen.findByText(/250ms\s*± 100ms/)).toBeInTheDocument();
    expect(screen.getByText(/cannot resolve anything shorter/)).toBeInTheDocument();
  });

  it('gives a latency from the messages own timestamps no error bar', async () => {
    serve(flow({ latencyMs: 42, latencySource: 'MESSAGE_TIMESTAMPS', latencyBoundMs: 100 }));
    open();

    expect(await screen.findByText('42ms')).toBeInTheDocument();
    expect(screen.getByText(/messages’ own timestamps/)).toBeInTheDocument();
    expect(screen.queryByText(/±/)).not.toBeInTheDocument();
  });

  it('gives an observed latency without a bound no error bar either', async () => {
    serve(flow({ latencyMs: 7, latencySource: 'OBSERVED', latencyBoundMs: null }));
    open();

    expect(await screen.findByText('7ms')).toBeInTheDocument();
  });

  it('states which clocks run ahead of Rahdar, separating both sides', async () => {
    serve(flow({ requestSkewMs: 1500, replySkewMs: 300 }));
    open();

    expect(await screen.findByText('Clock skew')).toBeInTheDocument();
    expect(
      screen.getByText(
        'the request claimed to be produced 1500ms in the future; the reply claimed to be produced 300ms in the future',
      ),
    ).toBeInTheDocument();
  });

  it('states a single skewed side alone', async () => {
    serve(flow({ replySkewMs: 300 }));
    open();
    expect(await screen.findByText('the reply claimed to be produced 300ms in the future')).toBeInTheDocument();
  });

  it('states a skewed request alone', async () => {
    serve(flow({ requestSkewMs: 1500 }));
    open();
    expect(await screen.findByText('the request claimed to be produced 1500ms in the future')).toBeInTheDocument();
  });

  describe('timeline', () => {
    it('lists each event with its kind, and shows a detail without a payload as JSON', async () => {
      serve(flow({ events: [event(1, 'REQUEST_SEEN', null), event(2, 'REPLY_SEEN', { subscription: 'reply.q' })] }));
      open();

      expect(await screen.findByText('REQUEST_SEEN')).toBeInTheDocument();
      expect(screen.getByText('REPLY_SEEN')).toBeInTheDocument();
      expect(screen.getByText(/"subscription": "reply\.q"/)).toBeInTheDocument();
    });

    it('gives the reason a payload was omitted', async () => {
      serve(flow({ events: [event(1, 'REQUEST_SEEN', { payloadOmitted: 'capture is switched off' })] }));
      open();

      expect(await screen.findByText('capture is switched off')).toBeInTheDocument();
    });

    it('shows a captured payload with its marks, its withheld notice and the capture limit', async () => {
      serve(
        flow({
          events: [
            event(1, 'REQUEST_SEEN', {
              bodyPreview: '{"card":"****"}',
              truncated: true,
              redactions: [
                {
                  location: 'BODY',
                  path: '$.card',
                  dataClass: 'PAN',
                  label: 'card number',
                  action: 'MASK',
                  clear: false,
                },
              ],
              withheld: [{ location: 'BODY', reason: 'Payload capture is restricted.', settingKey: 'rr.capture' }],
            }),
          ],
        }),
      );
      open();

      expect(await screen.findByText('Masked: card number')).toBeInTheDocument();
      expect(screen.getByText('Body withheld')).toBeInTheDocument();
      expect(screen.getByText('Payload capture is restricted.')).toBeInTheDocument();
      expect(screen.getByText(/"card":"\*\*\*\*"/)).toBeInTheDocument();
      expect(screen.getByText('Cut to the payload capture limit.')).toBeInTheDocument();
    });

    it('shows a preview with neither marks nor a cut notice plainly, and says when there is no preview text', async () => {
      serve(
        flow({
          events: [
            event(1, 'REQUEST_SEEN', { bodyPreview: 'hello', redactions: 'nope', withheld: 'nope' }),
            event(2, 'REPLY_SEEN', { bodyPreview: 5 }),
          ],
        }),
      );
      open();

      expect(await screen.findByText('hello')).toBeInTheDocument();
      expect(screen.getByText('(no preview)')).toBeInTheDocument();
      expect(screen.queryByText('Cut to the payload capture limit.')).not.toBeInTheDocument();
      expect(screen.queryByText(/withheld/i)).not.toBeInTheDocument();
    });
  });

  it('shows the failure the API reports in place of the flow', async () => {
    server.use(
      http.get('*/api/v1/clusters/c1/rr/flows/f1', () =>
        HttpResponse.json({ title: 'Flow not found', detail: 'No flow f1 in this cluster.' }, { status: 404 }),
      ),
    );
    open();

    const alert = await screen.findByRole('alert');
    expect(within(alert).getByText('Not found')).toBeInTheDocument();
    expect(within(alert).getByText('No flow f1 in this cluster.')).toBeInTheDocument();
    expect(within(alert).getByText(/go back to the list and choose it again/)).toBeInTheDocument();
    expect(screen.getByRole('dialog', { name: 'Flow' })).toBeInTheDocument();
  });

  it('offers a retry when the flow could not be read and trying again can help', async () => {
    server.use(
      http.get('*/api/v1/clusters/c1/rr/flows/f1', () => HttpResponse.json({ title: 'Down' }, { status: 503 })),
    );
    open();

    const alert = await screen.findByRole('alert');
    expect(within(alert).getByText('Rahdar failed to complete the request')).toBeInTheDocument();
    expect(within(alert).getByRole('button', { name: 'Retry' })).toBeInTheDocument();
  });

  it('states a failed flow in words, and a healthy one without colour', async () => {
    serve(flow({ state: 'TIMED_OUT' }));
    open();

    const dialog = await screen.findByRole('dialog', { name: 'Flow on orders.request' });
    expect(within(dialog).getByText('timed out')).toBeInTheDocument();
  });

  it('lists the flow’s facts as terms and values, not as a table', async () => {
    serve(flow({ correlationId: 'corr-1' }));
    open();

    const dialog = await screen.findByRole('dialog', { name: 'Flow on orders.request' });
    expect(within(dialog).queryByRole('table')).not.toBeInTheDocument();
    expect(within(dialog).getByRole('group', { name: 'Flow facts' })).toBeInTheDocument();
    expect(within(dialog).getByRole('heading', { level: 3, name: 'Timeline' })).toBeInTheDocument();
  });

  it('closes on Escape', async () => {
    serve(flow());
    const onClose = vi.fn();
    const user = userEvent.setup();
    open('f1', onClose);

    await screen.findByRole('dialog', { name: 'Flow on orders.request' });
    await user.keyboard('{Escape}');
    expect(onClose).toHaveBeenCalled();
  });
});
