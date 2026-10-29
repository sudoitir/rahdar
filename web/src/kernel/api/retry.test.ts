import { describe, expect, it } from 'vitest';

import { ApiError } from './request.ts';
import { shouldRetry } from './retry.ts';

const api = (status: number) => new ApiError(status, { title: 'Refused' });

describe('shouldRetry', () => {
  it('tries a failed network request again, up to three times', () => {
    expect(shouldRetry(0, new TypeError('Failed to fetch'))).toBe(true);
    expect(shouldRetry(2, new TypeError('Failed to fetch'))).toBe(true);
    expect(shouldRetry(3, new TypeError('Failed to fetch'))).toBe(false);
  });

  it('tries a timeout and a rate limit again', () => {
    for (const status of [408, 429]) expect(shouldRetry(0, api(status))).toBe(true);
  });

  it('does not try an answer again, whether a refusal or a server error that states its cause', () => {
    for (const status of [400, 401, 403, 404, 409, 422, 500, 502, 503]) expect(shouldRetry(0, api(status))).toBe(false);
  });
});
