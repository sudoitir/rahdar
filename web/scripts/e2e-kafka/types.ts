import type { BrowserContext, Page } from '@playwright/test';

export interface Check {
  ok: boolean;
  what: string;
}
export interface Flow {
  id: string;
  title: string;
  /** Statuses this flow provokes on purpose (a refusal), so they are not counted as failed requests. */
  expectedStatus?: number[];
  run: (t: Toolkit) => Promise<void>;
}
export interface Toolkit {
  page: Page;
  context: BrowserContext;
  cluster: string;
  check: (ok: boolean, what: string) => void;
  note: (text: string) => void;
  api: (path: string) => Promise<unknown>;
  /** A mutating call straight to the API, for set-up and clean-up (the double-submit token is echoed). */
  send: (
    method: 'POST' | 'DELETE' | 'PUT' | 'PATCH',
    path: string,
    body?: unknown,
  ) => Promise<{ status: number; body: unknown }>;
  /** Presses and holds the confirmation until it fills, as a person does. */
  hold: (name: string | RegExp) => Promise<void>;
  open: (path: string) => Promise<void>;
}
