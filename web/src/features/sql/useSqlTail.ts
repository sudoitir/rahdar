import { useCallback, useEffect, useRef, useState } from 'react';

import { ApiError, request as apiRequest } from '../../kernel/api/request.ts';
import { type SqlNodeOutcomeView, type SqlResultView, type SqlRowView, type SqlTailStatusView } from './api.ts';

/**
 * How long a newly arrived row is marked fresh, before the mark fades. Long enough to
 * catch the eye on a glance away from the screen, short enough that a busy tail is not
 * one solid block of highlight.
 */
const FRESH_MS = 4_000;

/**
 * The most rows a tail keeps in view. A tail runs for as long as an operator leaves
 * it running, so without a bound the list is a memory leak with a nice highlight on
 * it. The cap matches the server's `sql.max-rows` default, so a tail holds about
 * what a static query would return, and ADR-0056 applies: the view says when it has
 * started discarding rather than quietly shortening.
 */
const MAX_TAIL_ROWS = 2_000;

/** A row's identity is `(node, subscription, messageId)` — ids are node-local. */
export function rowKey(row: SqlRowView): string {
  return `${row.nodeId}/${row.subscription}/${row.messageId}`;
}

/**
 * What the console is doing.
 *
 * - `running` — the static query is fanning out; rows are arriving.
 * - `done` — it finished and nothing more is coming.
 * - `tailing` — it finished and the tail is polling.
 * - `failed` — the server refused or could not run it; `error` says why.
 * - `cancelled` — the operator stopped the static query part-way. The rows that had arrived are
 *   kept, and the query is not running. Stopping a tail is not a cancellation: the query had
 *   finished, so it reads as `done`.
 * - `disconnected` — the stream dropped. The query is *not* silently restarted:
 *   reconnecting would re-run a fan-out the operator did not ask for a second
 *   time, and would write a second audit record for one intent.
 *
 * A server that is shutting down sends `reconnect` first, which is the one exception: the
 * operator did not drop the stream, so the same query is run again on another replica (a new
 * ticket, a new stream, and the rows start over) instead of reporting a disconnect.
 */
export type RunStatus = 'idle' | 'running' | 'done' | 'tailing' | 'failed' | 'cancelled' | 'disconnected';

export interface SqlRun {
  rows: SqlRowView[];
  /** Rows that arrived in the last few seconds, for the live highlight. */
  freshKeys: ReadonlySet<string>;
  /** True once the tail has discarded rows to stay within its bound. */
  discarding: boolean;
  /** The finished static query: outcomes, bounds, notices and the plan that ran. */
  result: SqlResultView | null;
  /** Per-node outcomes as they land, before `result` exists. */
  progress: SqlNodeOutcomeView[];
  tail: SqlTailStatusView | null;
  error: ApiError | null;
  status: RunStatus;
  /** True while new rows are being held back rather than shown. */
  paused: boolean;
  /** How many rows have arrived while paused and are waiting to be shown. */
  buffered: number;
  /** Counts the runs started, and stays the same through a reconnect and a cancellation. 0 before the first. */
  runId: number;
  /** The SQL of the latest run, as it was sent. */
  sql: string;
  start: (sql: string, tail: boolean) => void;
  /**
   * Close the stream, which releases the run on the server. A static query still running is
   * `cancelled`; a tail is `done`. The rows that arrived stay.
   */
  cancel: () => void;
  /**
   * Hold new rows back without stopping the tail. The broker is still being read —
   * pausing the view and stopping the query are different acts, and conflating them
   * would create a gap in the feed that nothing announces.
   */
  pause: () => void;
  resume: () => void;
}

interface Request {
  sql: string;
  tail: boolean;
  /** Bumped on every Run, so running the same text again really re-runs it. */
  nonce: number;
}

/**
 * The console's one execution path: a stream per query (ADR-0058).
 *
 * <p>The static run and the tail arrive on the same connection, so progress,
 * per-node outcomes and cancellation are one mechanism rather than two that can
 * disagree. Closing the stream is what stops the query — the server's sink reports
 * itself cancelled and issues no further broker read — which is why the effect's
 * cleanup closes it rather than leaving it to be garbage collected.
 *
 * <p>A row event goes into a buffer that one animation frame writes with a single state update, so a
 * fan-out that delivers thousands of rows is one render per frame, not one copy of the list per row.
 * `done`, `failed`, a dropped stream and `cancel` write what is buffered before they set the status.
 *
 * <p>A refusal arrives as a `failed` frame carrying the same problem body the JSON
 * API would have returned, because an `EventSource` cannot read the body of a
 * non-200 and a refusal without its estimate is not actionable.
 */
export function useSqlTail(clusterId: string): SqlRun {
  const [request, setRequest] = useState<Request | null>(null);
  const [rows, setRows] = useState<SqlRowView[]>([]);
  const [freshKeys, setFreshKeys] = useState<ReadonlySet<string>>(new Set());
  const [result, setResult] = useState<SqlResultView | null>(null);
  const [progress, setProgress] = useState<SqlNodeOutcomeView[]>([]);
  const [tail, setTail] = useState<SqlTailStatusView | null>(null);
  const [error, setError] = useState<ApiError | null>(null);
  const [status, setStatus] = useState<RunStatus>('idle');
  const [run, setRun] = useState({ runId: 0, sql: '' });

  // A Set, not an array: each entry removes itself when it fires, so a tail left
  // running for an hour does not accumulate one dead handle per row it delivered.
  const timers = useRef<Set<ReturnType<typeof setTimeout>>>(new Set());

  // Rows that arrived while the view was paused. A ref, not state: they are not
  // rendered until released, and re-rendering per buffered row is the cost pausing
  // exists to avoid. The count beside it is state, because it is on screen.
  const held = useRef<SqlRowView[]>([]);
  const pausedRef = useRef(false);
  const [paused, setPaused] = useState(false);
  const [buffered, setBuffered] = useState(0);

  // Rows that have arrived since the last animation frame, oldest first, and whether the stream has
  // reached its tail, which decides where they go.
  const arrived = useRef<SqlRowView[]>([]);
  const frame = useRef<number | null>(null);
  const tailing = useRef(false);

  // Writes the rows that have arrived with one state update, and marks them fresh with one timer.
  const flush = useCallback(() => {
    if (frame.current !== null) {
      cancelAnimationFrame(frame.current);
      frame.current = null;
    }
    const batch = arrived.current;
    if (batch.length === 0) return;
    arrived.current = [];
    if (!tailing.current) {
      setRows((prev) => [...prev, ...batch]);
      return;
    }
    // While tailing, the newest row goes to the top: an operator watching a live feed is watching
    // the head of it, not scrolling to find it.
    const newest = [...batch];
    newest.reverse();
    if (pausedRef.current) {
      // Bounded the same way the visible list is: a long pause must not become an unbounded buffer.
      held.current = [...newest, ...held.current].slice(0, MAX_TAIL_ROWS);
      setBuffered(held.current.length);
      return;
    }
    setRows((prev) => {
      const next = [...newest, ...prev];
      return next.length > MAX_TAIL_ROWS ? next.slice(0, MAX_TAIL_ROWS) : next;
    });
    const keys = newest.map(rowKey);
    setFreshKeys((prev) => new Set([...prev, ...keys]));
    const timer = setTimeout(() => {
      timers.current.delete(timer);
      setFreshKeys((prev) => {
        const next = new Set(prev);
        keys.forEach((key) => next.delete(key));
        return next;
      });
    }, FRESH_MS);
    timers.current.add(timer);
  }, []);

  const pause = useCallback(() => {
    pausedRef.current = true;
    setPaused(true);
  }, []);

  const resume = useCallback(() => {
    pausedRef.current = false;
    setPaused(false);
    const release = held.current;
    held.current = [];
    setBuffered(0);
    if (release.length > 0) {
      setRows((prev) => {
        const next = [...release, ...prev];
        return next.length > MAX_TAIL_ROWS ? next.slice(0, MAX_TAIL_ROWS) : next;
      });
    }
  }, []);

  const start = useCallback((sql: string, wantsTail: boolean) => {
    setRun((prev) => ({ runId: prev.runId + 1, sql }));
    setRequest((prev) => ({
      sql,
      tail: wantsTail,
      nonce: (prev?.nonce ?? 0) + 1,
    }));
  }, []);

  const cancel = useCallback(() => {
    // Cancelling does not discard what arrived. A static query cancelled part-way has a partial
    // result the operator asked to keep, and one whose rows are already being read is not idle (7.2).
    flush();
    setRequest(null);
    // The query itself finished when only the tail was stopped, so that reads as done.
    setStatus((prev) => {
      if (prev === 'running') return 'cancelled';
      return prev === 'tailing' ? 'done' : prev;
    });
  }, [flush]);

  useEffect(() => {
    if (!request) return;

    setRows([]);
    setFreshKeys(new Set());
    setResult(null);
    setProgress([]);
    setTail(null);
    setError(null);
    setStatus('running');
    held.current = [];
    arrived.current = [];
    tailing.current = false;
    pausedRef.current = false;
    setPaused(false);
    setBuffered(0);

    // True once the server has said its piece. The close that follows is then the
    // end of a finished stream, not a dropped one.
    let settled = false;

    // The query text is posted and the stream is opened by reference (ADR-0064): an
    // EventSource can only issue a GET, and a URL carrying body predicates is a URL
    // written into every proxy access log on the path.
    let source: EventSource | null = null;
    let abandoned = false;

    const parse = <T>(e: Event): T | null => {
      try {
        return JSON.parse((e as MessageEvent).data) as T;
      } catch {
        return null;
      }
    };

    const listen = (stream: EventSource) => {
      stream.addEventListener('row', (e) => {
        const row = parse<SqlRowView>(e);
        if (!row) return;
        arrived.current.push(row);
        // A tail that arrives faster than frames are drawn (a hidden tab draws none) still stays bounded.
        if (tailing.current && arrived.current.length > MAX_TAIL_ROWS) arrived.current.shift();
        frame.current ??= requestAnimationFrame(() => {
          frame.current = null;
          flush();
        });
      });

      stream.addEventListener('node', (e) => {
        const node = parse<SqlNodeOutcomeView>(e);
        if (node) setProgress((prev) => [...prev, node]);
      });

      stream.addEventListener('done', (e) => {
        const done = parse<SqlResultView>(e);
        if (done) setResult(done);
        flush();
        if (request.tail) {
          tailing.current = true;
          setStatus('tailing');
        } else {
          settled = true;
          setStatus('done');
        }
      });

      stream.addEventListener('tail', (e) => {
        const status = parse<SqlTailStatusView>(e);
        if (status) setTail(status);
      });

      stream.addEventListener('failed', (e) => {
        // A refusal the server sent. Deliberately not named `error`: an EventSource
        // dispatches its own connection failures under that name, and the two must
        // not be confused for one another.
        const problem = parse<Record<string, unknown>>(e);
        if (!problem) return;
        flush();
        settled = true;
        setError(new ApiError(typeof problem.status === 'number' ? problem.status : 500, problem));
        setStatus('failed');
      });

      stream.addEventListener('reconnect', () => {
        // The ticket was single use and the run lives on this replica, so run it again.
        if (settled) return;
        stream.close();
        setRequest((prev) => (prev ? { ...prev, nonce: prev.nonce + 1 } : prev));
      });

      stream.onerror = () => {
        stream.close();
        flush();
        if (!settled) setStatus('disconnected');
      };
    };

    void (async () => {
      try {
        const ticket = await apiRequest<{ queryId: string }>(`/clusters/${clusterId}/sql/query`, {
          method: 'POST',
          body: JSON.stringify({ sql: request.sql, tail: request.tail }),
        });
        if (abandoned) return;
        source = new EventSource(
          `/api/v1/clusters/${clusterId}/sql/stream?queryId=${encodeURIComponent(ticket.queryId)}`,
        );
        listen(source);
      } catch (e) {
        if (abandoned) return;
        settled = true;
        setError(e instanceof ApiError ? e : new ApiError(500, { detail: String(e) }));
        setStatus('failed');
      }
    })();

    // Captured here rather than read in the cleanup: the lint rule is right in
    // general, and the Set is created once for the hook's life, so the capture is
    // exact rather than merely convenient.
    const pending = timers.current;
    return () => {
      abandoned = true;
      source?.close();
      if (frame.current !== null) cancelAnimationFrame(frame.current);
      frame.current = null;
      pending.forEach(clearTimeout);
      pending.clear();
    };
  }, [clusterId, request, flush]);

  // Derived, not tracked: the list is trimmed to exactly the cap, so holding the cap
  // while tailing is the same fact as having discarded something. One less piece of
  // state to reset on the next run.
  const discarding = status === 'tailing' && rows.length >= MAX_TAIL_ROWS;

  return {
    rows,
    freshKeys,
    discarding,
    result,
    progress,
    tail,
    error,
    status,
    paused,
    buffered,
    runId: run.runId,
    sql: run.sql,
    start,
    cancel,
    pause,
    resume,
  };
}
