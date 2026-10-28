import '@testing-library/jest-dom/vitest';
import { afterAll, afterEach, beforeAll } from 'vitest';
import { cleanup, configure } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { setupServer } from 'msw/node';

import { accessHandler } from './access.ts';
import { manifestHandler } from './manifest.ts';
import { ARTEMIS_KINDS, LOADED_SYSTEMS } from './wire.ts';

const AVAILABLE = { status: 'AVAILABLE', reason: 'ok', brokerXmlSnippet: null };

/**
 * Shared MSW network mock (ADR-0024). Tests add per-case handlers with
 * `server.use(http.get(...))`; anything unhandled is a hard error so a missing
 * mock fails loudly instead of hanging on a real fetch. The defaults are the
 * manifest, with every feature enabled, since the shell reads it on every screen, and
 * `/me/access`, worked out from whatever a test mocks for `/auth/me`, an empty inbox for the header's bell, an
 * approval gate with break-glass off for the shell's banner, a cluster view that offers the Artemis kinds, and no approval requests for the Account page;
 * a test disables features with `server.use(manifestHandler([...]))`.
 */
export const server = setupServer(
  manifestHandler(),
  accessHandler,
  http.get('*/api/v1/inbox/count', () => HttpResponse.json({ unread: 0, capped: false })),
  http.get('*/api/v1/gate/status', () =>
    HttpResponse.json({ armed: false, providerId: null, attached: false, breakGlass: false }),
  ),
  // The cluster view, for the kinds a screen that holds only a name builds its ids from; a test that needs more of it
  // serves its own.
  http.get('*/api/v1/clusters/:id', ({ params }) =>
    HttpResponse.json({
      id: params.id,
      name: String(params.id),
      topology: { clusterId: params.id, nodes: [] },
      capabilities: {
        managementRead: AVAILABLE,
        managementWrite: AVAILABLE,
        notifications: AVAILABLE,
        messageIo: AVAILABLE,
      },
      health: {
        clusterId: params.id,
        level: 'OK',
        liveEndpointNames: [],
        splitBrain: 'NONE',
        replicationBehind: false,
        notes: [],
      },
      system: { id: 'artemis', name: 'Artemis' },
      kinds: ARTEMIS_KINDS,
    }),
  ),
  http.get('*/api/v1/systems', () => HttpResponse.json(LOADED_SYSTEMS)),
  http.get('*/api/v1/held-operations', () => HttpResponse.json({ items: [], next: null })),
);

// findBy* and waitFor give up after 1 s by default, and a screen behind a lazy route, a query and a
// virtualised grid takes longer than that to appear when the whole suite is running. The wait ends the moment
// the element exists, so this only lengthens the failure of a test that is wrong (which vitest's own timeout,
// vitest.config.ts, still bounds).
configure({ asyncUtilTimeout: 8_000 });

beforeAll(() => {
  server.listen({ onUnhandledFrame: 'error' });
  // A press-and-hold confirmation reads its length from this token; a test holds for a few milliseconds, not 1.5 s.
  document.documentElement.style.setProperty('--as-hold-duration', '40ms');
});
afterEach(() => {
  cleanup();
  server.resetHandlers();
});
afterAll(() => server.close());

// jsdom gaps that Mantine and @tanstack/react-virtual reach for. jsdom 30 has a
// matchMedia that throws "Not implemented", so replace it outright.
Object.defineProperty(window, 'matchMedia', {
  writable: true,
  configurable: true,
  value: (query: string) => ({
    matches: false,
    media: query,
    onchange: null,
    addEventListener: () => {},
    removeEventListener: () => {},
    addListener: () => {},
    removeListener: () => {},
    dispatchEvent: () => false,
  }),
});

// Fires the callback once on observe() with a non-zero rect — @tanstack/react-virtual
// needs at least one measurement or it renders no rows under jsdom.
class ResizeObserverStub {
  #cb: ResizeObserverCallback;
  constructor(cb: ResizeObserverCallback) {
    this.#cb = cb;
  }
  observe(target: Element) {
    this.#cb(
      [{ target, contentRect: target.getBoundingClientRect() } as ResizeObserverEntry],
      this as unknown as ResizeObserver,
    );
  }
  unobserve() {}
  disconnect() {}
}
window.ResizeObserver = ResizeObserverStub as unknown as typeof ResizeObserver;

window.HTMLElement.prototype.scrollIntoView ??= () => {};
// The router restores scroll on navigation; jsdom has no layout to scroll.
window.scrollTo = () => {};

// jsdom has no CSS Font Loading API; Mantine's autosizing Textarea
// (react-textarea-autosize) reaches for `document.fonts.addEventListener` to
// re-measure once a web font finishes loading.
if (!document.fonts) {
  Object.defineProperty(document, 'fonts', {
    configurable: true,
    value: { addEventListener: () => {}, removeEventListener: () => {} },
  });
}

// jsdom has no EventSource. This stub records every open instance and lets a test
// push a named frame with `EventSourceStub.emit('events', data)`.
type Listener = (e: MessageEvent) => void;
class EventSourceStub {
  static instances: EventSourceStub[] = [];
  url: string;
  readyState = 1;
  onopen: (() => void) | null = null;
  onerror: (() => void) | null = null;
  listeners = new Map<string, Set<Listener>>();
  constructor(url: string) {
    this.url = url;
    EventSourceStub.instances.push(this);
    queueMicrotask(() => this.onopen?.());
  }
  addEventListener(type: string, cb: Listener) {
    let set = this.listeners.get(type);
    if (!set) {
      set = new Set();
      this.listeners.set(type, set);
    }
    set.add(cb);
  }
  removeEventListener(type: string, cb: Listener) {
    this.listeners.get(type)?.delete(cb);
  }
  close() {
    this.readyState = 2;
  }
  /** Test helper: deliver a named frame to every open stub (or one, by index), with an SSE id if given. */
  static emit(type: string, data: unknown, index?: number, lastEventId = '') {
    const targets = index === undefined ? EventSourceStub.instances : [EventSourceStub.instances[index]];
    for (const es of targets) {
      const frame = {
        data: typeof data === 'string' ? data : JSON.stringify(data),
        lastEventId,
      } as MessageEvent;
      es?.listeners.get(type)?.forEach((cb) => cb(frame));
    }
  }
  static reset() {
    EventSourceStub.instances = [];
  }
}
window.EventSource = EventSourceStub as unknown as typeof EventSource;
afterEach(() => EventSourceStub.reset());
export { EventSourceStub };

// @tanstack/react-virtual sizes the scroll element from offsetWidth/offsetHeight,
// which jsdom always reports as 0 — leaving the virtualizer with no rows. Give
// every element a viewport-sized box so getVirtualItems() yields rows.
for (const [prop, value] of [
  ['offsetWidth', 1000],
  ['offsetHeight', 800],
] as const) {
  Object.defineProperty(window.HTMLElement.prototype, prop, {
    configurable: true,
    get() {
      return value;
    },
  });
}

// jsdom's getBoundingClientRect always reports a zero-size box. Recharts'
// ResponsiveContainer (via @mantine/charts) sizes itself from the
// ResizeObserver entry's contentRect, not offsetWidth/offsetHeight, so a
// chart renders nothing under jsdom without this — matching the same
// viewport-sized stub above.
window.HTMLElement.prototype.getBoundingClientRect = () =>
  ({
    width: 1000,
    height: 800,
    top: 0,
    left: 0,
    right: 1000,
    bottom: 800,
    x: 0,
    y: 0,
    toJSON() {},
  }) as DOMRect;

// jsdom has no CSSOM View matrix API. React Flow (via d3-zoom) constructs a
// DOMMatrixReadOnly from the pane's transform to read the current scale; without
// this, any test that renders a topology canvas throws
// "window.DOMMatrixReadOnly is not a constructor".
class DOMMatrixReadOnlyStub {
  m22: number;
  constructor(transform?: string) {
    const scale = transform?.match(/scale\(([\d.]+)\)/)?.[1];
    this.m22 = scale === undefined ? 1 : Number(scale);
  }
}
window.DOMMatrixReadOnly ??= DOMMatrixReadOnlyStub as unknown as typeof DOMMatrixReadOnly;

// jsdom's Range implements neither getClientRects nor getBoundingClientRect, and
// CodeMirror measures its own text with a Range on first layout. Without these
// the SQL Console editor throws inside a requestAnimationFrame callback, which
// surfaces as an unhandled exception rather than a test failure.
Range.prototype.getClientRects ??= () =>
  ({ length: 0, item: () => null, [Symbol.iterator]: function* () {} }) as unknown as DOMRectList;
Range.prototype.getBoundingClientRect ??= () =>
  ({ width: 0, height: 0, top: 0, left: 0, right: 0, bottom: 0, x: 0, y: 0, toJSON() {} }) as DOMRect;
