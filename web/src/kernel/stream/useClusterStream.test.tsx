import { useState, type ReactNode } from 'react';
import { act, renderHook } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { manifestHandler } from '../../test/manifest.ts';
import { EventSourceStub, server } from '../../test/setup.ts';
import { CONTRACT, defineFeature, type TopicHandler } from '../feature.ts';
import { FeatureProvider } from '../FeatureProvider.tsx';
import { useClusterStream, useStreamStatus } from './useClusterStream.ts';

/** Fail the newest stub the way an `EventSource` does: error, then closed. */
async function failCurrent() {
  const es = EventSourceStub.instances.at(-1);
  await act(async () => {
    es?.onerror?.();
  });
}

/** Let the jittered backoff timer fire, whatever delay it picked (capped at 30s). */
async function runBackoff() {
  await act(async () => {
    await vi.advanceTimersByTimeAsync(31_000);
  });
}

describe('useClusterStream', () => {
  function Wrapper({ children }: { children: ReactNode }) {
    const [qc] = useState(() => new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0 } } }));
    return (
      <QueryClientProvider client={qc}>
        <FeatureProvider features={[]}>{children}</FeatureProvider>
      </QueryClientProvider>
    );
  }

  beforeEach(() => {
    vi.useFakeTimers();
    EventSourceStub.reset();
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  it('keeps reconnecting past the failure count that used to make it give up', async () => {
    // It stopped at two consecutive failures and handed over to polling — which on
    // the screens that never poll meant handing over to nothing (ADR-0052).
    const { result } = renderHook(() => useClusterStream('c1', ['topology']), { wrapper: Wrapper });
    await act(async () => {
      await vi.advanceTimersByTimeAsync(0);
    });

    for (let attempt = 0; attempt < 4; attempt += 1) {
      const before = EventSourceStub.instances.length;
      await failCurrent();
      // The state is reported rather than swallowed, which is what the header reads.
      expect(result.current).toBe('reconnecting');
      await runBackoff();
      expect(EventSourceStub.instances.length).toBeGreaterThan(before);
    }
  });

  it('reports live again once the server comes back, with no reload', async () => {
    const { result } = renderHook(() => useClusterStream('c1', ['topology']), { wrapper: Wrapper });
    await act(async () => {
      await vi.advanceTimersByTimeAsync(0);
    });
    expect(result.current).toBe('live');

    await failCurrent();
    expect(result.current).toBe('reconnecting');

    await runBackoff();
    expect(result.current).toBe('live');
  });

  it('reconnects when the stream falls silent, even with no error', async () => {
    // An intermediary that drops the connection without a clean close fires no
    // error at all. Missing the keep-alive is the only way to notice.
    renderHook(() => useClusterStream('c1', ['topology']), { wrapper: Wrapper });
    await act(async () => {
      await vi.advanceTimersByTimeAsync(0);
    });
    expect(EventSourceStub.instances).toHaveLength(1);

    await act(async () => {
      await vi.advanceTimersByTimeAsync(46_000);
    });
    await runBackoff();

    expect(EventSourceStub.instances.length).toBeGreaterThan(1);
  });

  it('a keep-alive frame counts as life, so a busy-but-quiet stream is left alone', async () => {
    renderHook(() => useClusterStream('c1', ['topology']), { wrapper: Wrapper });
    await act(async () => {
      await vi.advanceTimersByTimeAsync(0);
    });

    // Beat well inside the silence window, repeatedly, past where it would expire.
    for (let beat = 0; beat < 5; beat += 1) {
      await act(async () => {
        await vi.advanceTimersByTimeAsync(15_000);
        EventSourceStub.emit('ping', Date.now());
      });
    }

    expect(EventSourceStub.instances).toHaveLength(1);
  });
});

describe('useClusterStream shared status', () => {
  function Wrapper({ children }: { children: ReactNode }) {
    const [qc] = useState(() => new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0 } } }));
    return (
      <QueryClientProvider client={qc}>
        <FeatureProvider features={[]}>{children}</FeatureProvider>
      </QueryClientProvider>
    );
  }

  beforeEach(() => {
    vi.useFakeTimers();
    EventSourceStub.reset();
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  it("keeps the cluster's status when a second stream on the same view unmounts", async () => {
    const header = renderHook(() => useStreamStatus(), { wrapper: Wrapper });
    const cluster = renderHook(() => useClusterStream('c1', ['topology']), { wrapper: Wrapper });
    const feed = renderHook(() => useClusterStream('c1', ['events']), { wrapper: Wrapper });
    await act(async () => {
      await vi.advanceTimersByTimeAsync(0);
    });
    expect(header.result.current).toBe('live');

    feed.unmount();

    expect(header.result.current).toBe('live');
    cluster.unmount();
    expect(header.result.current).toBeNull();
  });

  it('reports the worst of the mounted streams', async () => {
    const header = renderHook(() => useStreamStatus(), { wrapper: Wrapper });
    const cluster = renderHook(() => useClusterStream('c1', ['topology']), { wrapper: Wrapper });
    const feed = renderHook(() => useClusterStream('c1', ['events']), { wrapper: Wrapper });
    await act(async () => {
      await vi.advanceTimersByTimeAsync(0);
    });

    await act(async () => {
      EventSourceStub.instances[1]?.onerror?.();
    });
    expect(header.result.current).toBe('reconnecting');

    feed.unmount();
    expect(header.result.current).toBe('live');
    cluster.unmount();
  });
});

describe('useClusterStream resuming and refetching', () => {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0 } } });
  const invalidate = vi.spyOn(qc, 'invalidateQueries');
  const wrapper = ({ children }: { children: ReactNode }) => (
    <QueryClientProvider client={qc}>
      <FeatureProvider features={[]}>{children}</FeatureProvider>
    </QueryClientProvider>
  );

  beforeEach(() => {
    vi.useFakeTimers();
    EventSourceStub.reset();
    invalidate.mockClear();
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  async function open(topics = ['events']) {
    const hook = renderHook(() => useClusterStream('c1', topics), { wrapper });
    await act(async () => {
      await vi.advanceTimersByTimeAsync(0);
    });
    return hook;
  }

  it('presents the last event id it saw on every new connection', async () => {
    await open();
    expect(EventSourceStub.instances[0]?.url).not.toContain('lastEventId');

    await act(async () => {
      EventSourceStub.emit('events', { seq: 41 }, undefined, '41');
      EventSourceStub.emit('events', { seq: 42 }, undefined, '42');
    });
    await failCurrent();
    await runBackoff();

    expect(EventSourceStub.instances[1]?.url).toContain('&lastEventId=42');
  });

  it('reconnects at once when the server says so, without counting a failure or refetching', async () => {
    const { result } = await open();
    await act(async () => {
      EventSourceStub.emit('events', { seq: 7 }, undefined, '7');
      EventSourceStub.emit('reconnect', Date.now(), 0);
    });

    // No timer has advanced: there was no backoff.
    expect(EventSourceStub.instances).toHaveLength(2);
    expect(EventSourceStub.instances[0]?.readyState).toBe(2);
    expect(EventSourceStub.instances[1]?.url).toContain('&lastEventId=7');
    expect(result.current).toBe('live');
    expect(invalidate).not.toHaveBeenCalled();
  });

  it('refetches the cluster on a resync', async () => {
    await open();

    await act(async () => {
      EventSourceStub.emit('resync', Date.now());
    });

    expect(invalidate).toHaveBeenCalledWith({ queryKey: ['clusters', 'c1'] });
  });

  it('refetches the cluster once after it reconnects from a failure, not on the first connect', async () => {
    await open();
    expect(invalidate).not.toHaveBeenCalled();

    await failCurrent();
    await runBackoff();

    expect(invalidate).toHaveBeenCalledTimes(1);
    expect(invalidate).toHaveBeenCalledWith({ queryKey: ['clusters', 'c1'] });
  });

  it('opens no stream for a view that asks for no topics', async () => {
    await open([]);

    expect(EventSourceStub.instances).toHaveLength(0);
  });
});

describe('useClusterStream topic dispatch', () => {
  it("hands each frame to the handler its feature contributes, and none to a disabled feature's", async () => {
    server.use(manifestHandler(['rr']));
    const queues = vi.fn<TopicHandler>();
    const rr = vi.fn<TopicHandler>();
    const features = [
      defineFeature({ contract: CONTRACT, id: 'resources', streamTopics: { queues } }),
      defineFeature({ contract: CONTRACT, id: 'rr', streamTopics: { rr } }),
    ];
    const qc = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0 } } });
    const wrapper = ({ children }: { children: ReactNode }) => (
      <QueryClientProvider client={qc}>
        <FeatureProvider features={features}>{children}</FeatureProvider>
      </QueryClientProvider>
    );
    renderHook(() => useClusterStream('c1', ['queues', 'rr']), { wrapper });
    // The manifest disabling rr has to arrive before the frames do.
    await vi.waitFor(() => expect(qc.getQueryData(['manifest'])).toBeDefined());

    await act(async () => {
      EventSourceStub.emit('queues', { topic: 'queues', clusterId: 'c1' });
      EventSourceStub.emit('rr', { topic: 'rr', clusterId: 'c1' });
    });

    expect(queues).toHaveBeenCalledWith(expect.objectContaining({ clusterId: 'c1' }));
    expect(rr).not.toHaveBeenCalled();
  });
});
