import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { http, HttpResponse } from 'msw';

import { server } from '../../test/setup.ts';

/**
 * The offset estimator is module state, so each case re-imports the module to get
 * a clean one rather than depending on the order the cases run in.
 */
async function fresh() {
  vi.resetModules();
  return import('./time.ts');
}

/**
 * Drive the round trip. The monotonic clock stands still and moves on only when the server answers, by
 * that probe's round trip, so `syncServerTime`'s before and after readings are exactly one round trip
 * apart however many other callers (the mock server itself) read `performance.now()` in between.
 */
let mono = 0;
let roundTrips: number[] = [];
function withRoundTrips(...rttsMs: number[]) {
  mono = 0;
  roundTrips = rttsMs;
  vi.spyOn(performance, 'now').mockImplementation(() => mono);
}

/** Serve a clock that is `aheadMs` ahead of the browser's, answering after the next round trip. */
function serverAheadBy(aheadMs: number) {
  server.use(
    http.get('/api/v1/time', () => {
      mono += roundTrips.shift() ?? 0;
      return HttpResponse.json({ nowMs: Date.now() + aheadMs });
    }),
  );
}

describe('server time', () => {
  afterEach(() => vi.restoreAllMocks());

  it('is the browser clock until the first probe answers', async () => {
    const { serverNow } = await fresh();
    expect(Math.abs(serverNow() - Date.now())).toBeLessThan(50);
  });

  it('corrects for a browser clock that is behind the server', async () => {
    withRoundTrips(10);
    serverAheadBy(60_000);
    const { serverNow, syncServerTime } = await fresh();

    await syncServerTime();

    expect(serverNow() - Date.now()).toBeGreaterThan(59_000);
    expect(serverNow() - Date.now()).toBeLessThan(61_000);
  });

  it('normalises a browser-stamped instant onto the same timeline', async () => {
    withRoundTrips(10);
    serverAheadBy(60_000);
    const { toServerMs, syncServerTime } = await fresh();

    await syncServerTime();

    // A TanStack `dataUpdatedAt` from four seconds ago must still read as four
    // seconds old once both sides are on Rahdar's clock.
    const stamped = Date.now() - 4_000;
    expect(toServerMs(stamped)).toBeCloseTo(stamped + 60_000, -2);
  });

  it('ignores an offset smaller than the round trip it was measured through', async () => {
    // 400ms round trip means a ±200ms error bar; a 50ms reading is inside it, and
    // correcting by less than the error bar is noise dressed as precision.
    withRoundTrips(400);
    serverAheadBy(50);
    const { browserOffsetMs, syncServerTime } = await fresh();

    await syncServerTime();

    // Asserted on the offset itself, not by comparing two separate `Date.now()`
    // reads — those can differ by a millisecond and would make this flaky.
    expect(browserOffsetMs()).toBe(0);
  });

  it('refuses to learn from a reading far slower than the best round trip', async () => {
    withRoundTrips(10, 900);
    const { serverNow, syncServerTime } = await fresh();

    serverAheadBy(60_000);
    await syncServerTime();
    const learned = serverNow() - Date.now();

    // A subscriptiond response claiming a wildly different time must not move the answer.
    serverAheadBy(600_000);
    await syncServerTime();

    expect(serverNow() - Date.now()).toBeCloseTo(learned, -3);
  });

  it('leaves the estimate standing when the probe fails', async () => {
    withRoundTrips(10, 10);
    const { serverNow, syncServerTime } = await fresh();

    serverAheadBy(60_000);
    await syncServerTime();

    server.use(http.get('/api/v1/time', () => HttpResponse.error()));
    await syncServerTime();

    // Reverting to the browser's clock on a blip would be worse than keeping a
    // slightly stale correction.
    expect(serverNow() - Date.now()).toBeGreaterThan(59_000);
  });

  it('treats the keep-alive as a drift detector, never as a measurement', async () => {
    withRoundTrips(10);
    let probes = 0;
    server.use(
      http.get('/api/v1/time', () => {
        probes += 1;
        return HttpResponse.json({ nowMs: Date.now() });
      }),
    );
    const { offerPing, browserOffsetMs } = await fresh();

    // A ping agreeing with us costs nothing.
    offerPing(String(Date.now()));
    expect(probes).toBe(0);

    // One disagreeing asks for a real probe — and does not itself set the offset.
    offerPing(String(Date.now() + 600_000));
    expect(browserOffsetMs()).toBe(0);
    await vi.waitFor(() => expect(probes).toBe(1));
  });

  it('ignores a malformed keep-alive payload', async () => {
    const { offerPing } = await fresh();
    expect(() => offerPing('not-a-number')).not.toThrow();
    expect(() => offerPing(undefined)).not.toThrow();
  });
});

describe('elapsedLabel', () => {
  it('floors rather than rounds, so an age is never overstated', async () => {
    const { elapsedLabel } = await fresh();
    expect(elapsedLabel(59_900)).toBe('59s');
    expect(elapsedLabel(119_000)).toBe('1m');
    expect(elapsedLabel(3 * 3_600_000 + 59 * 60_000)).toBe('3h');
    expect(elapsedLabel(47 * 3_600_000)).toBe('1d');
  });

  it('never reports a negative age', async () => {
    const { elapsedLabel } = await fresh();
    expect(elapsedLabel(-5_000)).toBe('0s');
  });
});

describe('absoluteLabel', () => {
  let absoluteLabel: (v: string | number | null | undefined) => string;

  beforeEach(async () => {
    // The default display zone follows the browser, so these cases pin UTC to
    // assert the format rather than whichever zone the test machine sits in.
    // Zone behaviour itself is covered in `timezone.test.ts`.
    window.localStorage.setItem('as:display:timezone', 'UTC');
    ({ absoluteLabel } = await fresh());
  });

  afterEach(() => window.localStorage.clear());

  it('renders both wire shapes the same way', () => {
    expect(absoluteLabel('2026-09-07T10:15:30.000Z')).toBe('2026-09-07 10:15:30Z');
    expect(absoluteLabel(Date.parse('2026-09-07T10:15:30.000Z'))).toBe('2026-09-07 10:15:30Z');
  });

  it('says nothing rather than 1970 when there is no timestamp', () => {
    // A broker that set no message timestamp sends 0, which is not a time.
    expect(absoluteLabel(0)).toBe('—');
    expect(absoluteLabel(null)).toBe('—');
    expect(absoluteLabel(undefined)).toBe('—');
    expect(absoluteLabel('nonsense')).toBe('—');
  });
});
