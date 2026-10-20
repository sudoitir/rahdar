import { describe, expect, it, vi } from 'vitest';
import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

import { ApiError } from '../api/request.ts';
import { renderWithProviders } from '../../test/render.tsx';
import { RouteError } from './RouteError.tsx';

describe('RouteError', () => {
  it('is the page: one top-level heading naming the failure', () => {
    renderWithProviders(<RouteError error={new Error('boom')} />);
    expect(screen.getAllByRole('heading', { level: 1 })).toHaveLength(1);
    expect(screen.getByRole('heading', { level: 1, name: 'This view failed to load' })).toBeInTheDocument();
  });

  it('names the cause and the next step of a refused request, and retries through the router', async () => {
    const reset = vi.fn();
    renderWithProviders(
      <RouteError error={new ApiError(403, { title: 'Forbidden', permission: 'subscriptions:read' })} reset={reset} />,
    );
    expect(screen.getByRole('alert')).toHaveTextContent(
      'Your role does not include the subscriptions:read permission.',
    );
    // A missing permission is not helped by trying again, so no retry is offered.
    expect(screen.queryByRole('button', { name: 'Retry' })).not.toBeInTheDocument();

    const again = vi.fn();
    renderWithProviders(<RouteError error={new ApiError(503, { title: 'Unavailable' })} reset={again} />);
    await userEvent.click(screen.getByRole('button', { name: 'Retry' }));
    expect(again).toHaveBeenCalledOnce();
  });

  it('shows the message of a view that crashed and offers to try again', async () => {
    const reset = vi.fn();
    renderWithProviders(<RouteError error={new Error('Cannot read properties of undefined.')} reset={reset} />);
    expect(screen.getByRole('region')).toHaveTextContent('Cannot read properties of undefined. Try again');
    await userEvent.click(screen.getByRole('button', { name: 'Try again' }));
    expect(reset).toHaveBeenCalledOnce();
  });

  it('reads whatever was thrown, even a string', () => {
    renderWithProviders(<RouteError error="plain text" />);
    expect(screen.getByRole('region')).toHaveTextContent('plain text');
  });
});
