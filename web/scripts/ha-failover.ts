/**
 * Failover of the reference HA deployment (deploy/compose/compose.ha.yaml with dev/compose.ha.test.yaml): two Rahdar
 * replicas behind HAProxy on one Postgres and one broker. Through the load balancer it registers the broker, holds
 * three event streams and a request loop (a read every 200 ms and a queue created and deleted every 500 ms), then:
 *
 *   1. SIGKILLs the replica that owns the cluster: requests fail for no more than a few seconds, the streams
 *      resume from their last event id, and scraping starts again within 25 s plus a margin;
 *   2. starts it again, and stops the other one gracefully: every stream is told to `reconnect`, not one request
 *      fails, and scraping carries on.
 *
 * Throughout, every persisted broker event between a stream's first and last id must have reached that stream.
 *
 *   COMPOSE='docker compose -p rahdar-ha-test --env-file deploy/compose/dev/ha/test.env \
 *            -f deploy/compose/compose.ha.yaml -f deploy/compose/dev/compose.ha.test.yaml' \
 *   RAHDAR=http://127.0.0.1:18080 node --experimental-strip-types scripts/ha-failover.ts
 *
 * COMPOSE is how this script starts, kills and reads the logs of the stack's containers. The one-time admin
 * password is read from rahdar-1's log unless ADMIN_PASSWORD is set; the stack is throwaway, so the script picks the
 * password the admin changes it to. `just ha-failover` and CI's image job run it.
 */
import { execFile } from 'node:child_process';
import { promisify } from 'node:util';
import { randomBytes } from 'node:crypto';

import { nextTotp } from './totp.ts';

const BASE = process.env.RAHDAR ?? 'http://127.0.0.1:18080';
const COMPOSE = (process.env.COMPOSE ?? '').split(/\s+/).filter(Boolean);
const BROKER = 'http://artemis-primary:8161/console/jolokia';

/** The spec: requests recover in seconds, and a dead owner's duties run again within 25 s. */
const MAX_FAILURE_WINDOW_MS = 5_000;
const SCRAPE_RESUMES_MS = 25_000 + 5_000;

const t0 = Date.now();
const sleep = (ms: number) => new Promise((resolve) => setTimeout(resolve, ms));
const clock = () => `${((Date.now() - t0) / 1000).toFixed(1).padStart(5)}s`;
const step = (message: string) => console.log(`${clock()} → ${message}`);

// ── the stack ────────────────────────────────────────────────────────────────

const run = promisify(execFile);

/** Asynchronous: the load and the streams keep running while a container is stopped. */
async function compose(...args: string[]): Promise<string> {
  if (COMPOSE.length === 0) throw new Error('set COMPOSE to the docker compose invocation of the stack');
  return (await run(COMPOSE[0]!, [...COMPOSE.slice(1), ...args], { maxBuffer: 64 * 1024 * 1024 })).stdout;
}

// ── the API, through the load balancer ───────────────────────────────────────

const cookies = new Map<string, string>();

function keep(response: Response) {
  for (const header of response.headers.getSetCookie()) {
    const [pair = ''] = header.split(';');
    const eq = pair.indexOf('=');
    if (eq > 0) cookies.set(pair.slice(0, eq).trim(), pair.slice(eq + 1).trim());
  }
}

const cookieHeader = () => [...cookies].map(([name, value]) => `${name}=${value}`).join('; ');

interface Result {
  status: number;
  body: any; // eslint-disable-line @typescript-eslint/no-explicit-any -- a script reading JSON it has just checked
}

/** One request. A request that got no answer at all is status 0, so a caller counts it like any other failure. */
async function call(method: string, path: string, body?: unknown, timeoutMs = 4_000): Promise<Result> {
  try {
    const response = await fetch(`${BASE}/api/v1${path}`, {
      method,
      headers: {
        cookie: cookieHeader(),
        'X-XSRF-TOKEN': cookies.get('XSRF-TOKEN') ?? '',
        ...(body === undefined ? {} : { 'content-type': 'application/json' }),
      },
      body: body === undefined ? undefined : JSON.stringify(body),
      signal: AbortSignal.timeout(timeoutMs),
    });
    keep(response);
    const text = await response.text();
    return { status: response.status, body: text ? JSON.parse(text) : undefined };
  } catch {
    return { status: 0, body: undefined };
  }
}

async function expectStatus(result: Result, want: number, what: string) {
  if (result.status !== want)
    throw new Error(`${what}: expected ${want}, got ${result.status} ${JSON.stringify(result.body)}`);
  return result.body;
}

async function until<T>(what: string, timeoutMs: number, probe: () => Promise<T | undefined | false>): Promise<T> {
  const deadline = Date.now() + timeoutMs;
  for (;;) {
    const found = await probe();
    if (found) return found;
    if (Date.now() > deadline) throw new Error(`${what}: not within ${timeoutMs / 1000} s`);
    await sleep(250);
  }
}

// ── sign in: the ADMIN role's one-time password, then its authenticator app (ADR-0143) ────────────────────

async function signIn() {
  const password =
    process.env.ADMIN_PASSWORD ??
    (await until(
      "the one-time admin password in rahdar-1's log",
      60_000,
      async () => /password:\s+(\S+)/.exec(await compose('logs', 'rahdar-1'))?.[1],
    ));
  await call('GET', '/auth/me'); // issues the CSRF cookie
  const login = await call('POST', '/auth/login', { username: 'admin', password });
  await expectStatus(login, 200, 'sign in');
  if (login.body.me?.mustChangePassword) {
    const replacement = randomBytes(18).toString('base64url');
    await expectStatus(
      await call('POST', '/auth/password', { currentPassword: password, newPassword: replacement }),
      204,
      'change the one-time password',
    );
  }
  const started = await expectStatus(await call('POST', '/auth/mfa/totp'), 200, 'start two-step set-up');
  await expectStatus(
    await call('POST', '/auth/mfa/totp/confirm', { code: await nextTotp(started.secret) }),
    200,
    'confirm two-step set-up',
  );
}

// ── what the replicas report ─────────────────────────────────────────────────

interface Replica {
  host: string;
  state: string;
  ownedClusters: { id: string }[];
}

async function replicas(): Promise<Replica[]> {
  const health = await call('GET', '/system/health');
  return health.status === 200 ? health.body.replicas : [];
}

/** The host (the service name, see the overlay) of the live replica that owns the cluster. */
async function ownerOf(cluster: string): Promise<string | undefined> {
  return (await replicas()).find((r) => r.state === 'READY' && r.ownedClusters.some((c) => c.id === cluster))?.host;
}

/** The newest time any node of the cluster was scraped. */
async function scrapedAt(cluster: string): Promise<number> {
  const topology = await call('GET', `/clusters/${cluster}/topology`);
  if (topology.status !== 200) return 0;
  const times: number[] = topology.body.nodes.flatMap((n: { endpoints: { lastSeenAt: string | null }[] }) =>
    n.endpoints.map((e) => (e.lastSeenAt ? Date.parse(e.lastSeenAt) : 0)),
  );
  return Math.max(0, ...times);
}

// ── an event stream, the way the web client holds one ───────────────────────────────────────────────────

/** Longer than the 20 s keep-alive: a stream that is this quiet has been cut without a word. */
const SILENCE_MS = 30_000;

class Stream {
  readonly ids = new Set<number>();
  last: number | undefined;
  /** How many times it connected, was told `reconnect`, or was sent `resync`. */
  connects = 0;
  reconnects = 0;
  resyncs = 0;
  private stopped = false;
  private abort: AbortController | undefined;
  private done: Promise<void>;
  private cluster: string;
  private opened!: () => void;
  /** Resolves when the first connection is answered. */
  readonly open = new Promise<void>((resolve) => (this.opened = resolve));

  constructor(cluster: string) {
    this.cluster = cluster;
    this.done = this.loop();
  }

  async stop() {
    this.stopped = true;
    this.abort?.abort();
    await this.done;
  }

  private async loop() {
    let backoff = 250;
    while (!this.stopped) {
      try {
        // Told to reconnect, it does at once; after a failure it waits, as the client does.
        const told = await this.once();
        backoff = 250;
        if (told) continue;
      } catch {
        // fall through to the backoff
      }
      if (this.stopped) return;
      await sleep(backoff);
      backoff = Math.min(backoff * 2, 2_000);
    }
  }

  /** One connection, carrying the last id it saw. Returns true when the replica asked it to reconnect. */
  private async once(): Promise<boolean> {
    const abort = (this.abort = new AbortController());
    let quiet = setTimeout(() => abort.abort(), SILENCE_MS);
    try {
      const since = this.last === undefined ? '' : `&lastEventId=${this.last}`;
      const response = await fetch(`${BASE}/api/v1/stream?clusterId=${this.cluster}&topics=events${since}`, {
        headers: { cookie: cookieHeader(), accept: 'text/event-stream' },
        signal: abort.signal,
      });
      if (!response.ok || !response.body) throw new Error(`stream answered ${response.status}`);
      this.connects++;
      this.opened();
      const decoder = new TextDecoder();
      let buffer = '';
      for await (const chunk of response.body) {
        clearTimeout(quiet);
        quiet = setTimeout(() => abort.abort(), SILENCE_MS);
        buffer += decoder.decode(chunk as Uint8Array, { stream: true });
        for (let end = buffer.indexOf('\n\n'); end >= 0; end = buffer.indexOf('\n\n')) {
          const frame = buffer.slice(0, end);
          buffer = buffer.slice(end + 2);
          if (this.accept(frame)) return true;
        }
      }
      return false;
    } finally {
      clearTimeout(quiet);
    }
  }

  /** Reads one frame; true for `reconnect`. */
  private accept(frame: string): boolean {
    let event = 'message';
    let id: string | undefined;
    for (const line of frame.split('\n')) {
      if (line.startsWith('event:')) event = line.slice(6).trim();
      else if (line.startsWith('id:')) id = line.slice(3).trim();
    }
    if (event === 'events' && id !== undefined) {
      this.ids.add(Number(id));
      this.last = Math.max(this.last ?? 0, Number(id));
    } else if (event === 'resync') {
      this.resyncs++;
    } else if (event === 'reconnect') {
      this.reconnects++;
      return true;
    }
    return false;
  }
}

// ── load through the balancer ────────────────────────────────────────────────

const failures: { at: number; what: string }[] = [];
const sent = { reads: 0, writes: 0 };
/** How long each read took, to say how slow the slowest was while a replica went away. */
const latencies: { at: number; ms: number }[] = [];

const slowestBetween = (from: number, to: number) =>
  Math.max(0, ...latencies.filter((l) => l.at >= from && l.at <= to).map((l) => l.ms));

function failedBetween(from: number, to: number) {
  return failures.filter((f) => f.at >= from && f.at <= to);
}

/** A read every 200 ms. */
async function readLoop(cluster: string, running: () => boolean) {
  while (running()) {
    const started = Date.now();
    sent.reads++;
    const result = await call('GET', `/clusters/${cluster}/topology`);
    latencies.push({ at: started, ms: Date.now() - started });
    if (result.status !== 200) failures.push({ at: started, what: `GET topology: ${result.status}` });
    await sleep(Math.max(0, 200 - (Date.now() - started)));
  }
}

/** A queue created and deleted every 500 ms: the broker then tells Rahdar, and Rahdar its streams. */
async function writeLoop(cluster: string, running: () => boolean) {
  for (let i = 0; running(); i++) {
    const started = Date.now();
    const name = `ha.failover.${started}.${i}`;
    sent.writes++;
    const created = await call('POST', `/clusters/${cluster}/subscriptions`, {
      kind: 'artemis.queue',
      name,
      destination: `artemis.address/${encodeURIComponent(name)}`,
      attributes: { routingType: 'ANYCAST' },
    });
    if (created.status !== 200) failures.push({ at: started, what: `create queue: ${created.status}` });
    else {
      const deleted = await call('DELETE', `/clusters/${cluster}/resources/artemis.queue/${encodeURIComponent(name)}`);
      if (deleted.status !== 200) failures.push({ at: Date.now(), what: `delete queue: ${deleted.status}` });
    }
    await sleep(Math.max(0, 500 - (Date.now() - started)));
  }
}

// ── the checks ───────────────────────────────────────────────────────────────

const problems: string[] = [];

function check(ok: boolean, passed: string, failed: string) {
  console.log(`${clock()}   ${ok ? '✓' : '✗'} ${ok ? passed : failed}`);
  if (!ok) problems.push(failed);
}

/** Every persisted event between a stream's first and last id reached it. */
async function checkNoGap(cluster: string, streams: Stream[]) {
  const persisted: number[] = [];
  for (let page = 1; ; page++) {
    const result = await call('GET', `/clusters/${cluster}/events?size=500&page=${page}`, undefined, 15_000);
    await expectStatus(result, 200, 'list the events');
    persisted.push(...result.body.data.map((e: { seq: number }) => e.seq));
    if (!result.body.hasNext) break;
  }
  streams.forEach((stream, i) => {
    const ids = [...stream.ids].sort((a, b) => a - b);
    const missed = persisted.filter((seq) => seq >= ids[0]! && seq <= ids[ids.length - 1]! && !stream.ids.has(seq));
    check(
      ids.length > 0 && missed.length === 0,
      `stream ${i + 1} has every one of its ${stream.ids.size} events (${ids[0]}…${ids[ids.length - 1]}), connected ${stream.connects} times`,
      `stream ${i + 1} missed ${missed.length} of the persisted events: ${missed.slice(0, 10).join(', ')}`,
    );
  });
}

async function main() {
  step('wait for the load balancer to route to a ready replica');
  await until('a ready replica behind the load balancer', 300_000, async () => {
    try {
      return (await fetch(`${BASE}/readyz`, { signal: AbortSignal.timeout(2_000) })).ok;
    } catch {
      return false;
    }
  });

  step('sign in through the load balancer');
  await signIn();
  await until(
    'both replicas ready',
    120_000,
    async () => (await replicas()).filter((r) => r.state === 'READY').length === 2,
  );

  step('register the broker');
  const registered = await expectStatus(
    await call(
      'POST',
      '/clusters',
      {
        seedUrls: [BROKER],
        name: 'ha-failover',
        credentials: { username: 'artemis', password: 'artemis' },
        coreCredentials: { username: 'artemis', password: 'artemis' },
      },
      60_000,
    ),
    201,
    'register the broker',
  );
  const cluster: string = registered.id;
  const owner = await until('an owner for the cluster', 30_000, () => ownerOf(cluster));
  const scraped = await scrapedAt(cluster);
  await until('the owner to scrape it', 30_000, async () => (await scrapedAt(cluster)) > scraped);
  console.log(`${clock()}   cluster ${cluster} is owned and scraped by ${owner}`);

  let running = true;
  // The balancer is leastconn, so streams opened while nothing else is in flight land on both replicas, and
  // the kill below always cuts one. Started with the load running, all three can land on the survivor.
  const streams = [new Stream(cluster), new Stream(cluster), new Stream(cluster)];
  await until('every stream to open', 30_000, async () =>
    Promise.race([Promise.all(streams.map((s) => s.open)).then(() => true), sleep(1_000).then(() => false)]),
  );
  const loops = [readLoop(cluster, () => running), writeLoop(cluster, () => running)];
  await until('events on every stream', 60_000, async () => streams.every((s) => s.ids.size >= 5));
  console.log(`${clock()}   ${streams.length} streams open, events flowing`);

  // ── 1. the owner dies ──
  const victim = owner;
  const survivor = victim === 'rahdar-1' ? 'rahdar-2' : 'rahdar-1';
  step(`SIGKILL ${victim}, the owner of the cluster`);
  const before = Object.fromEntries(streams.map((s, i) => [i, s.last ?? 0]));
  const killed = Date.now();
  await run('docker', ['kill', (await compose('ps', '-q', victim)).trim()]);

  await until(`${survivor} to take the cluster over`, 40_000, async () => (await ownerOf(cluster)) === survivor);
  console.log(`${clock()}   ${survivor} owns it after ${((Date.now() - killed) / 1000).toFixed(1)} s`);
  await until('scraping to resume', SCRAPE_RESUMES_MS, async () => (await scrapedAt(cluster)) > killed);
  const resumed = Date.now() - killed;
  await until('every stream to get events again', 30_000, async () =>
    streams.every((s, i) => (s.last ?? 0) > before[i]!),
  );
  await sleep(2_000);

  const hit = failedBetween(killed, Date.now());
  const window = hit.length === 0 ? 0 : hit[hit.length - 1]!.at - hit[0]!.at;
  check(
    window <= MAX_FAILURE_WINDOW_MS && hit.every((f) => f.at >= killed),
    `${hit.length} of ${sent.reads + sent.writes} requests failed, within ${(window / 1000).toFixed(1)} s of each other`,
    `requests failed over ${(window / 1000).toFixed(1)} s (limit ${MAX_FAILURE_WINDOW_MS / 1000} s): ${hit
      .map((f) => f.what)
      .slice(0, 5)
      .join('; ')}`,
  );
  check(
    streams.some((s) => s.connects > 1),
    `a stream reconnected with its last event id (${streams.map((s) => s.connects).join(', ')} connects)`,
    'no stream had to reconnect: the kill did not reach a stream',
  );
  console.log(`${clock()}   scraping resumed ${(resumed / 1000).toFixed(1)} s after the kill`);

  // ── 2. a graceful stop ──
  step(`start ${victim} again`);
  await compose('start', victim);
  await until(`${victim} to be ready`, 180_000, async () =>
    (await replicas()).some((r) => r.host === victim && r.state === 'READY'),
  );
  // The load balancer takes a replica back after two good checks, one a second.
  await sleep(4_000);
  const quiet = Date.now();
  const sentBefore = { ...sent };

  step(`stop ${survivor} gracefully`);
  const reconnects = streams.map((s) => s.reconnects);
  const stopped = Date.now();
  await compose('stop', survivor);
  console.log(`${clock()}   ${survivor} stopped after ${((Date.now() - stopped) / 1000).toFixed(1)} s`);
  await until(`${victim} to own the cluster`, 30_000, async () => (await ownerOf(cluster)) === victim);
  const scrapedBefore = await scrapedAt(cluster);
  await until('scraping to go on', 30_000, async () => (await scrapedAt(cluster)) > scrapedBefore);
  const eventsAfter = streams.map((s) => s.last ?? 0);
  await until('events to reach every stream again', 30_000, async () =>
    streams.every((s, i) => (s.last ?? 0) > eventsAfter[i]!),
  );
  await sleep(2_000);

  const dropped = failedBetween(quiet, Date.now());
  check(
    dropped.length === 0,
    `no request failed while the replica stopped (${sent.reads - sentBefore.reads} reads and ${sent.writes - sentBefore.writes} writes sent, the slowest read took ${slowestBetween(quiet, Date.now())} ms)`,
    `${dropped.length} requests failed during the graceful stop: ${dropped
      .map((f) => `${new Date(f.at).toISOString().slice(11, 23)} ${f.what}`)
      .slice(0, 8)
      .join('; ')}`,
  );
  check(
    streams.every((s, i) => s.reconnects > reconnects[i]!),
    `every stream was told to reconnect (${streams.map((s) => s.reconnects).join(', ')})`,
    `a stream was not told to reconnect (${streams.map((s, i) => s.reconnects - reconnects[i]!).join(', ')})`,
  );

  running = false;
  await Promise.all(loops);
  await sleep(1_500);
  step('every persisted event reached every stream');
  await checkNoGap(cluster, streams);
  await Promise.all(streams.map((s) => s.stop()));
}

main()
  .then(() => {
    if (problems.length > 0) {
      console.error(`✗ ${problems.length} checks failed`);
      process.exit(1);
    }
    console.log(`✓ failover passed in ${((Date.now() - t0) / 1000).toFixed(0)} s`);
    process.exit(0);
  })
  .catch((e) => {
    console.error(`✗ ${e instanceof Error ? e.message : String(e)}`);
    process.exit(1);
  });
