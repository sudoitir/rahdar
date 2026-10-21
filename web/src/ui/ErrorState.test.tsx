import { describe, expect, it, vi } from 'vitest';
import { screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

import { renderWithProviders } from '../test/render.tsx';
import { ErrorState } from './ErrorState.tsx';

/** The shape of an `ApiError`, which this part reads without importing it. */
function apiError(status: number, problem: Record<string, unknown> = {}, extra: Record<string, unknown> = {}) {
  return { status, problem, ...extra };
}

describe('ErrorState', () => {
  it('is an alert', () => {
    renderWithProviders(<ErrorState error={apiError(404)} />);
    expect(screen.getByRole('alert')).toBeInTheDocument();
  });

  it('says Rahdar could not be reached for a network failure, and offers retry', async () => {
    const onRetry = vi.fn();
    renderWithProviders(<ErrorState error={new TypeError('Failed to fetch')} onRetry={onRetry} />);
    expect(screen.getByText('Rahdar could not be reached')).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: 'Retry' }));
    expect(onRetry).toHaveBeenCalledOnce();
  });

  it('does not call a render crash a network failure', async () => {
    const onRetry = vi.fn();
    renderWithProviders(
      <ErrorState error={new TypeError("Cannot read properties of undefined (reading 'name')")} onRetry={onRetry} />,
    );
    expect(screen.queryByText('Rahdar could not be reached')).not.toBeInTheDocument();
    expect(screen.getByText('An unexpected error occurred in the console')).toBeInTheDocument();
    expect(screen.getByText("Cannot read properties of undefined (reading 'name')")).toBeInTheDocument();
    expect(screen.getByText(/reload the page/)).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: 'Retry' }));
    expect(onRetry).toHaveBeenCalledOnce();
  });

  it('reads a thrown non-error value as an unexpected error', () => {
    renderWithProviders(<ErrorState error="boom" />);
    expect(screen.getByText('An unexpected error occurred in the console')).toBeInTheDocument();
  });

  it('treats status 0 as the network', () => {
    renderWithProviders(<ErrorState error={apiError(0)} />);
    expect(screen.getByText('Rahdar could not be reached')).toBeInTheDocument();
  });

  it('asks the operator to sign in again on 401', () => {
    renderWithProviders(<ErrorState error={apiError(401)} onRetry={() => undefined} />);
    expect(screen.getByText('You are signed out')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Sign in again' })).toHaveAttribute('href', '/login');
    expect(screen.queryByRole('button', { name: 'Retry' })).not.toBeInTheDocument();
  });

  it('names the missing permission on 403 when the problem says it', () => {
    renderWithProviders(<ErrorState error={apiError(403, { permission: 'subscription:delete' })} />);
    expect(screen.getByText('Your role does not include the subscription:delete permission.')).toBeInTheDocument();
    expect(screen.getByText('Ask an administrator to grant subscription:delete.')).toBeInTheDocument();
  });

  it('still explains a 403 that names no permission', () => {
    renderWithProviders(<ErrorState error={apiError(403)} />);
    expect(screen.getByText('Your role does not allow it.')).toBeInTheDocument();
  });

  it('says it was not found on 404, without retry', () => {
    renderWithProviders(<ErrorState error={apiError(404)} onRetry={() => undefined} />);
    expect(screen.getByText('Not found')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Retry' })).not.toBeInTheDocument();
  });

  it('shows the server detail for a 409 and offers a reload', () => {
    renderWithProviders(
      <ErrorState error={apiError(409, { detail: 'The queue still has consumers.' })} onRetry={() => undefined} />,
    );
    expect(screen.getByText('This conflicts with the current state')).toBeInTheDocument();
    expect(screen.getByText('The queue still has consumers.')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Retry' })).toBeInTheDocument();
  });

  it('lists the invalid fields on 422', () => {
    renderWithProviders(
      <ErrorState
        error={apiError(
          422,
          {},
          {
            fieldErrors: [
              { field: 'name', message: 'must not be blank' },
              { field: 'port', message: 'must be at most 65535' },
            ],
          },
        )}
      />,
    );
    const items = within(screen.getByRole('list')).getAllByRole('listitem');
    expect(items.map((item) => item.textContent)).toEqual(['name: must not be blank', 'port: must be at most 65535']);
  });

  it('reads a 422 with no field errors as the problem’s own title and detail', () => {
    renderWithProviders(
      <ErrorState error={apiError(422, { title: 'Query refused', detail: 'It would examine 2,000,000 messages.' })} />,
    );
    expect(screen.getByText('Query refused')).toBeInTheDocument();
    expect(screen.getByText('It would examine 2,000,000 messages.')).toBeInTheDocument();
    expect(screen.queryByText('Some values are not valid')).not.toBeInTheDocument();
    expect(screen.queryByRole('list')).not.toBeInTheDocument();
  });

  it('shows a string hint as the next step, and lets the caller’s own next step win', () => {
    const error = apiError(422, { title: 'Query refused', hint: 'Add a WHERE clause on the queue.' });
    const { unmount } = renderWithProviders(<ErrorState error={error} />);
    expect(screen.getByText('Add a WHERE clause on the queue.')).toBeInTheDocument();
    unmount();
    renderWithProviders(<ErrorState error={error} next="Narrow the query." />);
    expect(screen.getByText('Narrow the query.')).toBeInTheDocument();
    expect(screen.queryByText('Add a WHERE clause on the queue.')).not.toBeInTheDocument();
  });

  it('gives the wait on 429', () => {
    renderWithProviders(<ErrorState error={apiError(429, { retryAfter: 30 })} onRetry={() => undefined} />);
    expect(screen.getByText('Too many requests')).toBeInTheDocument();
    expect(screen.getByText('Wait 30 seconds, then retry.')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Retry' })).toBeInTheDocument();
  });

  it('says to wait a moment on 429 with no retry-after', () => {
    renderWithProviders(<ErrorState error={apiError(429)} />);
    expect(screen.getByText('Wait a moment, then retry.')).toBeInTheDocument();
  });

  it('quotes the request id on a server error', () => {
    renderWithProviders(<ErrorState error={apiError(500, { requestId: 'req-42' })} onRetry={() => undefined} />);
    expect(screen.getByText('Rahdar failed to complete the request')).toBeInTheDocument();
    expect(screen.getByText('Request id: req-42')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Retry' })).toBeInTheDocument();
  });

  it('keeps the server detail beside the request id on a server error', () => {
    renderWithProviders(<ErrorState error={apiError(500, { detail: 'The journal is full.', requestId: 'req-7' })} />);
    expect(screen.getByText('The journal is full.')).toBeInTheDocument();
    expect(screen.getByText('Request id: req-7')).toBeInTheDocument();
  });

  it.each([
    ['UNREACHABLE', 'The broker is unreachable', 502, true],
    ['THROTTLED', 'Rahdar is throttling calls to this broker', 503, true],
    ['CREDENTIALS_REJECTED', 'The broker rejected the credentials', 422, false],
    ['NOT_ARTEMIS', 'There is no Artemis broker at this agent', 422, false],
    ['WRONG_PATH', 'There is no Jolokia agent at this address', 422, false],
    ['TLS_FAILED', 'The TLS handshake with the broker failed', 502, false],
    ['BAD_RESPONSE', 'The broker sent an unexpected response', 502, true],
    ['UNSUPPORTED_VERSION', 'This Artemis version is not supported', 422, false],
  ])('reads the broker error kind %s', (kind, title, status, retryable) => {
    renderWithProviders(
      <ErrorState error={apiError(status, {}, { brokerErrorKind: kind })} onRetry={() => undefined} />,
    );
    expect(screen.getByText(title)).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Retry' }) !== null).toBe(retryable);
  });

  it('keeps what the broker said under the cause its error kind maps to', () => {
    renderWithProviders(
      <ErrorState
        error={apiError(502, { detail: 'Connection refused: artemis-1:8161' }, { brokerErrorKind: 'UNREACHABLE' })}
      />,
    );
    expect(screen.getByText('The broker is unreachable')).toBeInTheDocument();
    expect(screen.getByText('Nothing answered at the broker address.')).toBeInTheDocument();
    expect(screen.getByText('Connection refused: artemis-1:8161')).toBeInTheDocument();
    expect(screen.getByText(/Check that the broker is running/)).toBeInTheDocument();
  });

  it('names the account the broker rejected', () => {
    renderWithProviders(
      <ErrorState
        error={apiError(422, { account: 'CORE' }, { brokerErrorKind: 'CREDENTIALS_REJECTED' })}
        onRetry={() => undefined}
      />,
    );
    expect(screen.getByText('The broker refused the Core account Rahdar holds for it.')).toBeInTheDocument();
    expect(screen.getByText(/Connection settings/)).toBeInTheDocument();
  });

  it('replaces the next step with the one it is given', () => {
    renderWithProviders(<ErrorState error={apiError(404)} next="Choose the queue again from the Queues list." />);
    expect(screen.getByText('Choose the queue again from the Queues list.')).toBeInTheDocument();
    expect(screen.queryByText(/Check the address/)).not.toBeInTheDocument();
  });

  it('adds extra next-step controls beside Retry, and without it', async () => {
    const onRetry = vi.fn();
    const { unmount } = renderWithProviders(
      <ErrorState error={apiError(500)} onRetry={onRetry} actions={<button type="button">Open settings</button>} />,
    );
    expect(screen.getByRole('button', { name: 'Retry' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Open settings' })).toBeInTheDocument();
    unmount();
    renderWithProviders(<ErrorState error={apiError(403)} actions={<button type="button">Open settings</button>} />);
    expect(screen.getByRole('button', { name: 'Open settings' })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Retry' })).not.toBeInTheDocument();
  });

  it('shows the problem title and detail for any other refusal', () => {
    renderWithProviders(
      <ErrorState error={apiError(400, { detail: 'Bad paging cursor.' }, { title: 'Bad request' })} />,
    );
    expect(screen.getByText('Bad request')).toBeInTheDocument();
    expect(screen.getByText('Bad paging cursor.')).toBeInTheDocument();
  });

  it('offers no retry without a handler', () => {
    renderWithProviders(<ErrorState error={apiError(500)} />);
    expect(screen.queryByRole('button')).not.toBeInTheDocument();
  });

  it('has an inline variant', () => {
    renderWithProviders(<ErrorState error={apiError(404)} variant="inline" />);
    expect(screen.getByRole('alert')).toHaveAttribute('data-variant', 'inline');
  });

  it('shows an operation held for approval as sent, not as a failure, with a link to the request', () => {
    const held = { name: 'OperationHeldError', heldOperation: { id: 'h-9', summary: 'Delete queue "orders"' } };
    renderWithProviders(<ErrorState error={held} />);
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
    const status = screen.getByRole('status');
    expect(status).toHaveTextContent('Sent for approval');
    expect(status).toHaveTextContent('Delete queue "orders" waits for a second person.');
    expect(within(status).getByRole('link', { name: 'View request' })).toHaveAttribute('href', '/approvals/h-9');
  });

  it.each([
    ['operation-denied', 403, 'The approval policy denied this', 'Outside the change window.'],
    ['approval-unavailable', 503, 'Approvals are unavailable', 'Outside the change window.'],
    ['approver-quorum', 409, 'Too few approvers', 'This would leave one approver.'],
  ])('reads the gate refusal %s by its type, saying nothing was changed', (slug, status, title, detail) => {
    renderWithProviders(
      <ErrorState error={apiError(status, { detail }, { type: `https://rahdar.dev/problems/${slug}` })} />,
    );
    const alert = screen.getByRole('alert');
    expect(alert).toHaveTextContent(title);
    expect(alert).toHaveTextContent(detail);
    expect(alert).toHaveTextContent('Nothing was changed.');
  });
});
