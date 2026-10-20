import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { http, HttpResponse } from 'msw';
import { act, renderHook } from '@testing-library/react';

import { EventSourceStub, server } from '../../test/setup.ts';
import type { SqlRowView } from './api.ts';
import { useSqlTail } from './useSqlTail.ts';

const row = (messageId: number): SqlRowView => ({
  nodeId: 'n1',
  nodeName: 'primary',
  subscription: 'artemis.queue/ORDER.IN',
  destination: 'artemis.address/ORDER.IN',
  messageId,
  messageType: 4,
  durable: true,
  priority: 4,
  timestamp: 0,
  expiration: 0,
  size: 10,
  body: 'x',
  bodyTruncated: false,
  source: 'LIVE',
  origin: 'SAMPLED',
});

const emit = (type: string, data: unknown) => act(() => EventSourceStub.emit(type, data));

/** Animation frames the test fires by hand, so a batch is whatever arrived between two of them. */
let frames: FrameRequestCallback[] = [];
const frame = () =>
  act(() => {
    frames.splice(0).forEach((callback) => callback(0));
  });

beforeEach(() => {
  frames = [];
  vi.spyOn(globalThis, 'requestAnimationFrame').mockImplementation((callback) => frames.push(callback));
  vi.spyOn(globalThis, 'cancelAnimationFrame').mockImplementation(() => undefined);
  server.use(http.post('*/api/v1/clusters/c1/sql/query', () => HttpResponse.json({ queryId: 'q-1' })));
});

afterEach(() => vi.restoreAllMocks());

/** Start a run and wait for its stream to open. */
async function started(tail = false, sql = 'SELECT * FROM "ORDER.IN"') {
  const hook = renderHook(() => useSqlTail('c1'));
  act(() => hook.result.current.start(sql, tail));
  await vi.waitFor(() => expect(EventSourceStub.instances).toHaveLength(1));
  return hook;
}

const done = { nodes: [], boundsReached: [], notices: [], partial: false };

describe('useSqlTail: batching', () => {
  it('writes the rows that arrived between two frames with one update, in arrival order', async () => {
    let renders = 0;
    const hook = renderHook(() => {
      renders += 1;
      return useSqlTail('c1');
    });
    act(() => hook.result.current.start('SELECT 1', false));
    await vi.waitFor(() => expect(EventSourceStub.instances).toHaveLength(1));
    const before = renders;

    emit('row', row(1));
    emit('row', row(2));
    emit('row', row(3));
    expect(hook.result.current.rows).toEqual([]);
    expect(renders).toBe(before);

    frame();
    expect(hook.result.current.rows.map((r) => r.messageId)).toEqual([1, 2, 3]);
    expect(renders).toBe(before + 1);
    expect(frames).toHaveLength(0);
  });

  it('writes what is buffered before it says the query is done, or failed, or dropped', async () => {
    const finished = await started();
    emit('row', row(1));
    emit('done', done);
    expect(finished.result.current.status).toBe('done');
    expect(finished.result.current.rows.map((r) => r.messageId)).toEqual([1]);
    finished.unmount();

    EventSourceStub.reset();
    const failed = await started();
    emit('row', row(2));
    emit('failed', { status: 500, title: 'Query failed' });
    expect(failed.result.current.status).toBe('failed');
    expect(failed.result.current.rows.map((r) => r.messageId)).toEqual([2]);
    failed.unmount();

    EventSourceStub.reset();
    const dropped = await started();
    emit('row', row(3));
    act(() => EventSourceStub.instances[0].onerror?.());
    expect(dropped.result.current.status).toBe('disconnected');
    expect(dropped.result.current.rows.map((r) => r.messageId)).toEqual([3]);
  });

  it('puts a tail’s newest rows first and marks each batch fresh at once', async () => {
    const hook = await started(true);
    emit('done', done);
    expect(hook.result.current.status).toBe('tailing');

    emit('row', row(10));
    emit('row', row(11));
    frame();
    emit('row', row(12));
    frame();

    expect(hook.result.current.rows.map((r) => r.messageId)).toEqual([12, 11, 10]);
    expect([...hook.result.current.freshKeys].sort()).toEqual([
      'n1/artemis.queue/ORDER.IN/10',
      'n1/artemis.queue/ORDER.IN/11',
      'n1/artemis.queue/ORDER.IN/12',
    ]);
  });

  it('keeps a paused tail’s buffer bounded, and shows the newest of it on resume', async () => {
    const hook = await started(true);
    emit('done', done);
    act(() => hook.result.current.pause());

    for (let i = 1; i <= 2_500; i++) emit('row', row(i));
    frame();

    expect(hook.result.current.rows).toEqual([]);
    expect(hook.result.current.buffered).toBe(2_000);

    act(() => hook.result.current.resume());
    expect(hook.result.current.rows).toHaveLength(2_000);
    expect(hook.result.current.rows[0].messageId).toBe(2_500);
    expect(hook.result.current.buffered).toBe(0);
  });
});

describe('useSqlTail: cancel and the run', () => {
  it('cancels a running query: the stream closes, the rows stay and the state says cancelled', async () => {
    const hook = await started();
    emit('row', row(1));

    act(() => hook.result.current.cancel());

    expect(hook.result.current.status).toBe('cancelled');
    expect(hook.result.current.rows.map((r) => r.messageId)).toEqual([1]);
    expect(EventSourceStub.instances[0].readyState).toBe(2);
  });

  it('stops a tail as done, since the query itself had finished', async () => {
    const hook = await started(true);
    emit('done', done);

    act(() => hook.result.current.cancel());

    expect(hook.result.current.status).toBe('done');
    expect(EventSourceStub.instances[0].readyState).toBe(2);
  });

  it('leaves any other state as it is', async () => {
    const hook = await started();
    emit('done', done);
    act(() => hook.result.current.cancel());
    expect(hook.result.current.status).toBe('done');

    const idle = renderHook(() => useSqlTail('c1'));
    act(() => idle.result.current.cancel());
    expect(idle.result.current.status).toBe('idle');
  });

  it('numbers the runs and keeps the SQL that ran, through a cancellation', async () => {
    const hook = renderHook(() => useSqlTail('c1'));
    expect(hook.result.current).toMatchObject({ runId: 0, sql: '' });

    act(() => hook.result.current.start('SELECT 1', false));
    await vi.waitFor(() => expect(EventSourceStub.instances).toHaveLength(1));
    expect(hook.result.current).toMatchObject({ runId: 1, sql: 'SELECT 1' });

    act(() => hook.result.current.cancel());
    expect(hook.result.current).toMatchObject({ runId: 1, sql: 'SELECT 1' });

    act(() => hook.result.current.start('SELECT 2', false));
    await vi.waitFor(() => expect(EventSourceStub.instances).toHaveLength(2));
    expect(hook.result.current).toMatchObject({ runId: 2, sql: 'SELECT 2' });
  });

  it('keeps the run number when the server asks for a reconnect', async () => {
    const hook = await started();
    emit('reconnect', {});
    await vi.waitFor(() => expect(EventSourceStub.instances).toHaveLength(2));
    expect(hook.result.current.runId).toBe(1);
  });
});
