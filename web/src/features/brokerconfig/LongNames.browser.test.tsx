import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import { onlineManager, QueryClient, QueryClientProvider } from '@tanstack/react-query';
import type { ReactElement } from 'react';
import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import {
  createMemoryHistory,
  createRootRoute,
  createRoute,
  createRouter,
  RouterProvider,
} from '@tanstack/react-router';

import { keys as authKeys } from '../../kernel/auth/api.ts';
import { contentWidth, Frame, renderThemed, SCHEMES, settle } from '../../test/browser.tsx';
import { keys } from './api.ts';
import { ConfigurationView } from './ConfigurationView.tsx';
import { ApplyResult } from './ApplyResult.tsx';
import { CATALOGUE, cluster, declaration, NODE_A, NODE_B, plan } from './fixtures.ts';

/**
 * A 200-character name, as a broker may supply, in every configuration table: it stays on one line, and
 * nothing beside it is broken into a word or a few characters per line. Real layout is what jsdom cannot see.
 */
beforeAll(() => onlineManager.setOnline(false));
afterAll(() => onlineManager.setOnline(true));

const LONG = `orders-${'q'.repeat(186)}-tail`;
const LONG_ROLE = `role-${'r'.repeat(190)}-end`;

const PAIR = {
  clusterId: 'c1',
  nodes: [
    {
      splitBrain: 'NONE',
      replicationBehind: false,
      endpoints: ['n-a', 'n-b'].map((id, i) => ({
        id,
        name: `broker-${i + 1}`,
        haRole: 'PRIMARY',
        state: 'UP',
        active: true,
        discovered: true,
        manualOverride: false,
        manageable: true,
      })),
    },
  ],
};

function longDeclaration() {
  const base = declaration();
  return declaration({
    document: {
      ...base.document,
      addresses: [
        {
          name: LONG,
          routingTypes: ['ANYCAST'],
          queues: [{ name: LONG, routingType: 'ANYCAST', durable: true }],
        },
      ],
      addressSettings: [{ match: `${LONG}.#`, values: { addressFullMessagePolicy: 'PAGE' } }],
      securitySettings: [{ match: `${LONG}.#`, permissions: { send: [LONG_ROLE], consume: [LONG_ROLE] } }],
      diverts: [
        { name: LONG, address: LONG, forwardingAddress: `${LONG}.audit`, exclusive: false, transformerProperties: {} },
      ],
      bridges: [],
    } as typeof base.document,
    nodes: [
      {
        ...NODE_A,
        state: 'DRIFTED',
        findings: [
          {
            kind: 'UNDECLARED',
            section: 'DIVERT',
            key: `${LONG}-stray`,
            detail: 'not declared',
            declared: {},
            observed: { address: LONG, 'forwarding-address': `${LONG}.spool` },
          },
          {
            kind: 'DIVERGENT',
            section: 'ADDRESS_SETTING',
            key: `${LONG}.#`,
            detail: 'differs',
            declared: { addressFullMessagePolicy: 'PAGE' },
            observed: { addressFullMessagePolicy: 'DROP' },
          },
        ],
      },
      NODE_B,
    ],
  });
}

function seeded(): QueryClient {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false, staleTime: Infinity, gcTime: Infinity } },
  });
  const config = keys.brokerConfig('c1');
  client.setQueryData(authKeys.me, {
    id: 'u1',
    username: 'admin',
    mustChangePassword: false,
    grants: [{ scopeType: 'GLOBAL', scopeId: null, permissions: ['*'] }],
  });
  client.setQueryData(['clusters', 'c1'], cluster());
  client.setQueryData(['clusters', 'c1', 'topology'], PAIR);
  client.setQueryData(config, longDeclaration());
  client.setQueryData([...config, 'catalogue'], CATALOGUE);
  client.setQueryData(
    [...config, 'revisions'],
    [3, 2].map((revision) => ({
      revision,
      createdAt: '2026-09-11T09:00:00Z',
      createdBy: `${LONG_ROLE}@example.test`,
      source: 'EDIT',
      note: revision === 2 ? 'first adoption of everything the brokers were running' : null,
      document: revision === 3 ? longDeclaration().document : declaration().document,
    })),
  );
  client.setQueryData(
    [...config, 'applies'],
    [
      {
        id: 2,
        startedAt: '2026-09-11T10:00:00Z',
        revisionId: 3,
        outcome: 'HALTED',
        summary: 'Halted on broker-2',
        actor: LONG_ROLE,
        dryRun: false,
        auditEventId: 9,
      },
    ],
  );
  client.setQueryData(['clusters', 'c1', 'dlq'], { settingsAvailable: true, addresses: [] });
  return client;
}

function page(search: string, view: () => ReactElement = ConfigurationView) {
  const root = createRootRoute();
  const route = createRoute({
    getParentRoute: () => root,
    path: '/clusters/$clusterId/artemis/configuration',
    component: view,
  });
  return createRouter({
    routeTree: root.addChildren([route]),
    history: createMemoryHistory({ initialEntries: [`/clusters/c1/artemis/configuration${search}`] }),
  });
}

/** The distinct lines a span of a text node is drawn on: one per distinct top among its client rects. */
function linesOf(node: Text, start: number, end: number): number {
  const range = document.createRange();
  range.setStart(node, start);
  range.setEnd(node, end);
  return new Set([...range.getClientRects()].filter((r) => r.width > 0).map((r) => Math.round(r.top))).size;
}

/** The shortened names on the page: each is one line tall, whatever its text, and says so in full on demand. */
function shortenedNames(root: Element): HTMLElement[] {
  return [...root.querySelectorAll<HTMLElement>('[data-clip]')];
}

/**
 * The words in the page, 8 characters or more, that are broken across lines: a name or a value wrapping
 * character by character. The visually hidden whole copy of a shortened name is skipped, because it is
 * not drawn.
 */
function brokenTokens(root: Element): string[] {
  const walker = document.createTreeWalker(root, NodeFilter.SHOW_TEXT);
  const broken: string[] = [];
  for (let n = walker.nextNode(); n; n = walker.nextNode()) {
    const text = n as Text;
    if (text.parentElement?.closest('[data-whole]')) continue;
    for (const token of text.data.matchAll(/\S{8,}/g)) {
      if (linesOf(text, token.index, token.index + token[0].length) > 1) broken.push(token[0].slice(0, 40));
    }
  }
  return broken;
}

describe.each([
  { name: 'addresses and queues', search: '', ready: 'Addresses and queues' },
  { name: 'history', search: '?tab=history', ready: 'Revisions' },
])('Configuration, $name, with 200-character names', ({ search, ready }) => {
  it.each(SCHEMES)('shows every name on one line in the %s scheme', async (scheme) => {
    const { container } = renderThemed(
      <QueryClientProvider client={seeded()}>
        <Frame width={contentWidth(1280)} height={3200}>
          <RouterProvider router={page(search)} />
        </Frame>
      </QueryClientProvider>,
      scheme,
    );
    await screen.findByRole('heading', { level: 2, name: ready });
    await settle(() => `${container.querySelectorAll('table').length}:${container.scrollHeight}`);

    expect(brokenTokens(container)).toEqual([]);
    expect(container.scrollWidth).toBeLessThanOrEqual(container.clientWidth + 1);
    if (search === '') {
      const names = shortenedNames(container);
      expect(names.length).toBeGreaterThan(0);
      for (const name of names) {
        const lineHeight = Number.parseFloat(getComputedStyle(name).lineHeight);
        expect(name.getBoundingClientRect().height).toBeLessThan(lineHeight * 1.5);
      }
      // The full name is on demand: the cell's title says it, and the whole name is there for a reader.
      expect(container.querySelector(`[title="${LONG}"]`)).not.toBeNull();
    }
  });
});

describe('Configuration history, comparing a revision whose items have 200-character names', () => {
  it.each(SCHEMES)('keeps the comparison on one line per name in the %s scheme', async (scheme) => {
    const { container } = renderThemed(
      <QueryClientProvider client={seeded()}>
        <Frame width={contentWidth(1280)} height={3200}>
          <RouterProvider router={page('?tab=history')} />
        </Frame>
      </QueryClientProvider>,
      scheme,
    );
    await userEvent.click(await screen.findByRole('button', { name: 'Compare with current' }));
    await screen.findByRole('table', { name: /Revision 2 compared with revision 3/i });
    await settle(() => `${container.querySelectorAll('table').length}:${container.scrollHeight}`);

    expect(brokenTokens(container)).toEqual([]);
  });
});

describe('the editor drawer of an address with a 200-character name', () => {
  it.each(SCHEMES)('keeps its title on one line in the %s scheme', async (scheme) => {
    renderThemed(
      <QueryClientProvider client={seeded()}>
        <Frame width={contentWidth(1280)} height={3200}>
          <RouterProvider router={page('')} />
        </Frame>
      </QueryClientProvider>,
      scheme,
    );
    await userEvent.click(await screen.findByRole('button', { name: `Edit address ${LONG}` }));
    const dialog = await screen.findByRole('dialog');
    await settle(() => `${dialog.getBoundingClientRect().width}`);

    expect(brokenTokens(dialog)).toEqual([]);
    expect(dialog.scrollWidth).toBeLessThanOrEqual(dialog.clientWidth + 1);
  });
});

describe('the steps of a plan over 200-character names', () => {
  it.each(SCHEMES)('keeps every key and value on one line in the %s scheme', async (scheme) => {
    const outcome = plan();
    const longStep = (step: (typeof outcome.plan.nodes)[number]['steps'][number]) => ({
      ...step,
      key: LONG,
      before: { 'forwarding-address': `${LONG}.old` },
      after: { 'forwarding-address': `${LONG}.new`, address: LONG },
    });
    const long = {
      ...outcome,
      plan: {
        ...outcome.plan,
        nodes: outcome.plan.nodes.map((n) => ({ ...n, steps: n.steps.map(longStep) })),
      },
      nodes: outcome.nodes.map((n) => ({ ...n, steps: n.steps.map((s) => ({ ...s, key: LONG })) })),
    };
    const { container } = renderThemed(
      <QueryClientProvider client={seeded()}>
        <Frame width={760} height={2400}>
          <RouterProvider
            router={page('', () => (
              <ApplyResult outcome={long} clusterId="c1" />
            ))}
          />
        </Frame>
      </QueryClientProvider>,
      scheme,
    );
    await waitFor(() => expect(container.querySelectorAll('table').length).toBeGreaterThan(1));
    await settle(() => `${container.querySelectorAll('table').length}:${container.scrollHeight}`);

    expect(brokenTokens(container)).toEqual([]);
  });
});
