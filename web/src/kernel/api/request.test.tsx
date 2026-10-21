import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { http, HttpResponse } from 'msw';
import { renderHook, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import type { ReactNode } from 'react';

import { server } from '../../test/setup.ts';
import { useMe } from '../auth/api.ts';
import {
  ACTIVITY_HEADER,
  ACTIVITY_WINDOW_MS,
  ApiError,
  HELD_HEADER,
  lifecycleQuery,
  OperationHeldError,
  request,
} from './request.ts';

/**
 * `request<T>()`'s 401 handling (identity-and-sessions spec, task 6.7) — the
 * one place a session expiry is detected, for every hook that goes through
 * it, not just `useMe`.
 */
describe('request() 401 handling', () => {
  const originalLocation = window.location;

  beforeEach(() => {
    // jsdom's `location.assign` throws "not implemented" — stub the whole
    // object so the redirect can actually be observed.
    Object.defineProperty(window, 'location', {
      configurable: true,
      value: { ...originalLocation, pathname: '/', assign: vi.fn() },
    });
  });

  afterEach(() => {
    Object.defineProperty(window, 'location', { configurable: true, value: originalLocation });
  });

  function wrapper({ children }: { children: ReactNode }) {
    const qc = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0 } } });
    return <QueryClientProvider client={qc}>{children}</QueryClientProvider>;
  }

  it('redirects to /login on a 401 response', async () => {
    server.use(http.get('*/api/v1/auth/me', () => HttpResponse.json({}, { status: 401 })));

    const { result } = renderHook(() => useMe(), { wrapper });

    await waitFor(() => expect(result.current.isError).toBe(true));
    expect(window.location.assign).toHaveBeenCalledWith('/login');
  });

  it('does not redirect again when already on /login', async () => {
    window.location.pathname = '/login';
    server.use(http.get('*/api/v1/auth/me', () => HttpResponse.json({}, { status: 401 })));

    const { result } = renderHook(() => useMe(), { wrapper });

    await waitFor(() => expect(result.current.isError).toBe(true));
    expect(window.location.assign).not.toHaveBeenCalled();
  });

  it('keeps the session when a step-up code is wrong', async () => {
    server.use(
      http.post('*/api/v1/auth/second-factor', () =>
        HttpResponse.json(
          { type: 'https://rahdar.dev/problems/second-factor-invalid', title: 'Code not accepted' },
          { status: 401 },
        ),
      ),
    );

    await expect(request('/auth/second-factor', { method: 'POST' })).rejects.toThrow('Code not accepted');

    expect(window.location.assign).not.toHaveBeenCalled();
  });

  // Last in this block: once a page has seen a sign-in confirmed, a later 401 always says the session ended.
  it('says the session ended when the page had been signed in', async () => {
    server.use(
      http.get('*/api/v1/auth/me', () => HttpResponse.json({ id: 'u1' }), { once: true }),
      http.get('*/api/v1/probe', () => HttpResponse.json({}, { status: 401 })),
    );
    await request('/auth/me');

    await expect(request('/probe')).rejects.toThrow();

    expect(window.location.assign).toHaveBeenCalledWith('/login?reason=ended');
  });
});

/**
 * A real run says so. An absent `dryRun` leaves the choice to the server's default,
 * and a real Apply that the server read as a preview wrote nothing and said nothing.
 */
describe('lifecycleQuery()', () => {
  it('sends dryRun=false for a real run', () => {
    expect(lifecycleQuery(false)).toBe('?dryRun=false');
  });

  it('sends dryRun=true for a preview, with the override when set', () => {
    expect(lifecycleQuery(true, true)).toBe('?dryRun=true&override=true');
  });

  it('sends nothing when the caller did not choose', () => {
    expect(lifecycleQuery()).toBe('');
  });
});

/**
 * A caller's own headers add to the defaults, never replace them. Replacing them
 * dropped the CSRF header, so the XML import (which sets its content type) was
 * refused with a 403 while every JSON POST worked.
 */
describe('request() headers', () => {
  it('keeps the CSRF header when the caller sets its own content type', async () => {
    document.cookie = 'XSRF-TOKEN=tok-123';
    let seen: Headers | undefined;
    server.use(
      http.post('*/api/v1/probe', ({ request: req }) => {
        seen = req.headers;
        return HttpResponse.json({});
      }),
    );

    await request('/probe', { method: 'POST', headers: { 'content-type': 'application/xml' }, body: '<core/>' });

    expect(seen?.get('x-xsrf-token')).toBe('tok-123');
    expect(seen?.get('content-type')).toBe('application/xml');
  });
});

/**
 * Only what a person caused counts towards the idle timeout (ADR-0145): a request carries the
 * activity header while the pointer or keyboard was used in the last minute, and never otherwise,
 * so a tab that polls on its own goes idle.
 */
describe('request() activity header', () => {
  let now = 1_000_000;

  beforeEach(() => {
    vi.spyOn(performance, 'now').mockImplementation(() => now);
  });
  afterEach(() => vi.restoreAllMocks());

  async function sentHeader(method = 'GET'): Promise<string | null | undefined> {
    let seen: Headers | undefined;
    server.use(
      http.all('*/api/v1/probe', ({ request: req }) => {
        seen = req.headers;
        return HttpResponse.json({});
      }),
    );
    await request('/probe', { method });
    return seen?.get(ACTIVITY_HEADER);
  }

  it('sends nothing before the person has done anything', async () => {
    expect(await sentHeader()).toBeNull();
  });

  it('sends it within a minute of a key press, and stops after', async () => {
    window.dispatchEvent(new KeyboardEvent('keydown', { key: 'a' }));

    now += ACTIVITY_WINDOW_MS - 1;
    expect(await sentHeader()).toBe('1');

    now += 2;
    expect(await sentHeader()).toBeNull();
  });

  it.each(['pointerdown', 'pointermove', 'wheel'])('counts a %s as the person being there', async (type) => {
    now += 10 * ACTIVITY_WINDOW_MS;
    expect(await sentHeader()).toBeNull();

    window.dispatchEvent(new Event(type));

    expect(await sentHeader()).toBe('1');
  });

  it('adds it to a change as well as to a read', async () => {
    window.dispatchEvent(new KeyboardEvent('keydown', { key: 'a' }));

    expect(await sentHeader('POST')).toBe('1');
  });
});

/** A gated operation held for approval answers 202 with the held header (design: "Responses"). */
describe('request() held operations', () => {
  const held = {
    outcome: 'held',
    heldOperation: {
      id: 'h-1',
      summary: 'Purge subscription "orders"',
      expiresAt: '2026-10-08T12:00:00Z',
      link: '/api/v1/held-operations/h-1',
    },
  };

  it('throws OperationHeldError for a 202 with the held header, so no success path runs', async () => {
    server.use(
      http.post('*/api/v1/purge', () => HttpResponse.json(held, { status: 202, headers: { [HELD_HEADER]: 'h-1' } })),
    );

    const error = await request('/purge', { method: 'POST' }).catch((e: unknown) => e);

    expect(error).toBeInstanceOf(OperationHeldError);
    expect((error as OperationHeldError).heldOperation).toEqual(held.heldOperation);
    expect((error as OperationHeldError).message).toBe('Sent for approval: Purge subscription "orders"');
  });

  it('names the request by the header even when the body lost it', async () => {
    server.use(
      http.post('*/api/v1/purge', () => HttpResponse.json({}, { status: 202, headers: { [HELD_HEADER]: 'h-2' } })),
    );

    const error = (await request('/purge', { method: 'POST' }).catch((e: unknown) => e)) as OperationHeldError;

    expect(error.heldOperation).toEqual({
      id: 'h-2',
      summary: 'The operation',
      expiresAt: '',
      link: '/api/v1/held-operations/h-2',
    });
  });

  it('returns the body of a 202 that carries no held header, as any accepted request', async () => {
    server.use(http.post('*/api/v1/jobs', () => HttpResponse.json({ jobId: 'j1' }, { status: 202 })));

    await expect(request('/jobs', { method: 'POST' })).resolves.toEqual({ jobId: 'j1' });
  });

  it('keeps a gate refusal an ApiError with its problem type', async () => {
    server.use(
      http.post('*/api/v1/purge', () =>
        HttpResponse.json(
          {
            type: 'https://rahdar.dev/problems/operation-denied',
            title: 'Denied',
            detail: 'Outside the window',
          },
          { status: 403 },
        ),
      ),
    );

    const error = (await request('/purge', { method: 'POST' }).catch((e: unknown) => e)) as ApiError;

    expect(error).toBeInstanceOf(ApiError);
    expect(error.type).toMatch(/operation-denied$/);
    expect(error.message).toBe('Outside the window');
  });
});
