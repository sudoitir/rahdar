import { ApiError } from './request.ts';

/** How many times a failed query is tried again. */
const ATTEMPTS = 3;

/**
 * Whether a failed query is worth trying again: a network failure that never reached Rahdar, a timeout or a
 * rate limit may pass. Any answer Rahdar gave, a refusal, a missing run or a server error with its cause (a
 * broker that cannot be reached), says what is wrong and fails the same way for the next second, so trying it
 * again only delays the page that explains it; a screen that polls asks again on its own interval.
 */
export function shouldRetry(failureCount: number, error: unknown): boolean {
  if (failureCount >= ATTEMPTS) return false;
  if (!(error instanceof ApiError)) return true;
  return error.status === 408 || error.status === 429;
}
