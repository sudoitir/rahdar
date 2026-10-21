import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import { onlineManager, QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { screen } from '@testing-library/react';
import {
  createMemoryHistory,
  createRootRoute,
  createRoute,
  createRouter,
  RouterProvider,
} from '@tanstack/react-router';
import type { ReactElement } from 'react';

import { keys as authKeys } from '../../kernel/auth/api.ts';
import { axeViolations, contentWidth, Frame, renderThemed, SCHEMES, settle } from '../../test/browser.tsx';
import { keys } from './api.ts';
import { C, diff, outlier } from './configDiffFixtures.ts';
import { ConfigDiffView } from './ConfigDiffView.tsx';
import { ConfigurationView } from './ConfigurationView.tsx';
import { CATALOGUE, cluster, declaration, NODE_A, NODE_B } from './fixtures.ts';

/**
 * The Configuration and Config diff pages, laid out in a real browser at the narrowest window the console
 * is designed for (1280 px, beside the expanded navigation), in both colour schemes. Real layout is what
 * jsdom cannot see: a link told apart from its text by colour alone (`link-in-text-block`), a scroll box a
 * keyboard cannot reach (`scrollable-region-focusable`), a table wider than its page.
 */
// No network: every query is answered from the cache the test seeds, and nothing is fetched.
beforeAll(() => onlineManager.setOnline(false));
afterAll(() => onlineManager.setOnline(true));

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
        urlSource: null,
        urlProblem: null,
        coreUrlManual: false,
        manageable: true,
      })),
    },
  ],
};

/** Three nodes, one acceptor URI long enough to be shortened in its cell. */
const DIFF = diff({
  sections: [
    {
      section: 'broker',
      label: 'Broker',
      keys: [
        outlier('/AcceptorUri', `tcp://${'broker-1.example.internal:61616?'.repeat(6)}`, C, 'tcp://broker-3:61616'),
        ...diff().sections[0].keys,
      ],
    },
    diff().sections[1],
  ],
});

/** A declaration with something on every row: drifted, with a finding, and a divert and a bridge declared. */
function drifted() {
  const base = declaration();
  return declaration({
    document: {
      ...base.document,
      diverts: [
        { name: 'audit-copy', address: 'orders.request', forwardingAddress: 'orders.audit', exclusive: false },
      ] as typeof base.document.diverts,
    },
    nodes: [
      {
        ...NODE_A,
        state: 'DRIFTED',
        findings: [
          {
            kind: 'DIVERGENT',
            section: 'ADDRESS_SETTING',
            key: 'orders.#',
            detail: 'differs',
            declared: { addressFullMessagePolicy: 'PAGE' },
            observed: { addressFullMessagePolicy: 'DROP' },
          },
          {
            kind: 'UNDECLARED',
            section: 'DIVERT',
            key: 'stray-copy',
            detail: 'not declared',
            declared: {},
            observed: { address: 'orders.request', 'forwarding-address': 'orders.spool' },
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
  client.setQueryData(config, drifted());
  client.setQueryData([...config, 'catalogue'], CATALOGUE);
  client.setQueryData(
    [...config, 'revisions'],
    [3, 2, 1].map((revision) => ({
      revision,
      createdAt: '2026-09-11T09:00:00Z',
      createdBy: 'admin',
      source: revision === 1 ? 'ADOPTED_FROM_CLUSTER' : 'EDIT',
      note: revision === 1 ? 'first adoption of everything the brokers were running' : null,
      document: declaration().document,
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
        actor: 'ops',
        dryRun: false,
        auditEventId: 9,
      },
    ],
  );
  client.setQueryData(['clusters', 'c1', 'dlq'], { settingsAvailable: true, addresses: [] });
  client.setQueryData([...config, 'recommendations'], {
    seededFrom: 'broker-1',
    recommendations: [
      {
        capability: 'metrics-plugin',
        title: 'Install the metrics plugin',
        rationale: 'No management operation writes a plugin, so Rahdar cannot apply it.',
        appliable: false,
        section: null,
        match: null,
        values: {},
        roles: {},
        keys: [],
        manualSnippet: `<metrics>\n  <plugin class-name="${'org.example.metrics.'.repeat(8)}Plugin"/>\n</metrics>`,
      },
    ],
  });
  client.setQueryData(['clusters', 'c1', 'config-diff'], DIFF);
  return client;
}

function page(view: () => ReactElement, path: string, search: string) {
  const root = createRootRoute();
  const route = createRoute({ getParentRoute: () => root, path, component: view });
  return createRouter({
    routeTree: root.addChildren([route]),
    history: createMemoryHistory({ initialEntries: [`${path.replace('$clusterId', 'c1')}${search}`] }),
  });
}

/** Boxes that scroll sideways and that a keyboard cannot reach: a long line of code is one that can, a table is not. */
function unreachableScrollers(root: Element): Element[] {
  return [...root.querySelectorAll('*')].filter((el) => {
    const { overflowX } = getComputedStyle(el);
    const scrolls = (overflowX === 'auto' || overflowX === 'scroll') && el.scrollWidth > el.clientWidth + 1;
    return scrolls && !el.hasAttribute('tabindex');
  });
}

const PAGES = [
  {
    name: 'Configuration, addresses and queues',
    view: ConfigurationView,
    path: '/clusters/$clusterId/artemis/configuration',
    search: '',
    ready: 'Addresses and queues',
  },
  {
    name: 'Configuration, address settings',
    view: ConfigurationView,
    path: '/clusters/$clusterId/artemis/configuration',
    search: '?tab=addressSettings',
    ready: 'Address settings',
  },
  {
    name: 'Configuration, live nodes',
    view: ConfigurationView,
    path: '/clusters/$clusterId/artemis/configuration',
    search: '?tab=nodes',
    ready: 'Nodes',
  },
  {
    name: 'Configuration, history',
    view: ConfigurationView,
    path: '/clusters/$clusterId/artemis/configuration',
    search: '?tab=history',
    ready: 'Revisions',
  },
  {
    name: 'Configuration, recommended',
    view: ConfigurationView,
    path: '/clusters/$clusterId/artemis/configuration',
    search: '?tab=recommended',
    ready: 'These still need a broker.xml edit',
  },
  {
    name: 'Config diff',
    view: ConfigDiffView,
    path: '/clusters/$clusterId/artemis/config-diff',
    search: '',
    ready: 'Configuration keys',
    role: 'grid',
  },
] as const;

describe.each(PAGES)('$name', ({ view, path, search, ready, ...rest }) => {
  const role = 'role' in rest ? rest.role : 'heading';
  describe.each(SCHEMES)('in the %s scheme at 1280 px', (scheme) => {
    it('has no accessibility violations, fits its window and scrolls nothing sideways', async () => {
      const width = contentWidth(1280);
      const { container } = renderThemed(
        <QueryClientProvider client={seeded()}>
          <Frame width={width} height={3200}>
            <RouterProvider router={page(view, path, search)} />
          </Frame>
        </QueryClientProvider>,
        scheme,
      );
      await (role === 'grid'
        ? screen.findByRole('grid', { name: ready })
        : screen.findByRole('heading', { level: 2, name: ready }));
      await settle(() => `${container.querySelectorAll('table').length}:${container.scrollHeight}`);

      expect(await axeViolations(container)).toEqual([]);
      // The page itself never scrolls sideways at the narrowest window the console is laid out for.
      expect(container.scrollWidth).toBeLessThanOrEqual(container.clientWidth + 1);
      // Where a box does scroll, a keyboard can reach it; no table has to.
      expect(unreachableScrollers(container)).toEqual([]);
    });
  });
});

describe('inline links in the nodes section', () => {
  it.each(SCHEMES)(
    'are told apart from their text by an underline, not by colour alone, in the %s scheme',
    async (scheme) => {
      renderThemed(
        <QueryClientProvider client={seeded()}>
          <Frame width={contentWidth(1280)} height={3200}>
            <RouterProvider
              router={page(ConfigurationView, '/clusters/$clusterId/artemis/configuration', '?tab=nodes')}
            />
          </Frame>
        </QueryClientProvider>,
        scheme,
      );

      const link = await screen.findByRole('link', { name: 'Config diff' });
      expect(getComputedStyle(link).textDecorationLine).toBe('underline');
      // The tables are the console's own: every one is named for what it lists.
      for (const table of screen.getAllByRole('table')) expect(table).toHaveAccessibleName();
    },
  );
});

describe('at 200% zoom, where a long line of broker.xml no longer fits', () => {
  it.each(SCHEMES)('keeps the fragment reachable from the keyboard in the %s scheme', async (scheme) => {
    const { container } = renderThemed(
      <QueryClientProvider client={seeded()}>
        <Frame width={560} height={3200}>
          <RouterProvider
            router={page(ConfigurationView, '/clusters/$clusterId/artemis/configuration', '?tab=recommended')}
          />
        </Frame>
      </QueryClientProvider>,
      scheme,
    );

    const fragment = await screen.findByRole('region', { name: 'broker.xml for Install the metrics plugin' });
    await settle(() => `${container.scrollHeight}`);
    // The line overflows its block, so the block itself takes focus.
    expect(fragment.scrollWidth).toBeGreaterThan(fragment.clientWidth);
    expect(fragment).toHaveAttribute('tabindex', '0');
    expect(await axeViolations(container)).toEqual([]);
  });
});
