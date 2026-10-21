import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { screen } from '@testing-library/react';
import {
  createMemoryHistory,
  createRootRoute,
  createRoute,
  createRouter,
  RouterProvider,
} from '@tanstack/react-router';
import type { ReactElement } from 'react';

import { paged } from '../../kernel/api/paging.ts';
import { accessFor } from '../../test/accessSummary.ts';
import { contentWidth, Frame, renderThemed, settle } from '../../test/browser.tsx';
import { C, diff, outlier } from './configDiffFixtures.ts';
import { ConfigDiffView } from './ConfigDiffView.tsx';
import { ConfigurationView } from './ConfigurationView.tsx';
import { CATALOGUE, cluster, declaration, NODE_A, NODE_B } from './fixtures.ts';

/**
 * A page that swaps its loading state for its content must not move what stays: the sweep holds every
 * route to 0.01 of layout shift from navigation start, and the swap counts. Here the data arrives after
 * a delay, as it does from the server, and the shift of the page's own parts is measured around it.
 */
const ME = {
  id: 'u1',
  username: 'admin',
  mustChangePassword: false,
  grants: [{ scopeType: 'GLOBAL', scopeId: null, permissions: ['*'] }],
};

/** Three nodes: a value that drifts, a setting one node lacks, and a long value. */
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

const TOPOLOGY = {
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

const EMPTY_DOCUMENT = {
  version: 1,
  addresses: [],
  addressSettings: [],
  securitySettings: [],
  diverts: [],
  bridges: [],
};

/** Whether the cluster has no declaration yet, which is the page that suggests adopting what the nodes run. */
let undeclared = false;

function bodyFor(path: string): unknown {
  const drifted = undeclared
    ? declaration({ declared: false, revision: 0, document: EMPTY_DOCUMENT })
    : declaration({ nodes: [{ ...NODE_A, state: 'DRIFTED', findings: [] }, NODE_B] });
  const routes: [RegExp, unknown][] = [
    [/\/auth\/me$/, ME],
    [/\/me\/access$/, accessFor(ME.grants)],
    [/\/clusters\/c1$/, cluster()],
    [/\/clusters\/c1\/topology$/, TOPOLOGY],
    [/\/config\/catalogue$/, CATALOGUE],
    [
      /\/config\/revisions/,
      paged([
        {
          revision: 3,
          createdAt: '2026-09-11T09:00:00Z',
          createdBy: 'admin',
          source: 'EDIT',
          note: null,
          document: drifted.document,
        },
      ]),
    ],
    [/\/config\/applies/, paged([])],
    [/\/config$/, drifted],
    [/\/config-diff/, DIFF],
    [/\/dead-letters$/, { settingsAvailable: true, destinations: [] }],
    [
      /\/config\/recommendations$/,
      {
        seededFrom: 'broker-1',
        recommendations: [
          {
            capability: 'management-size-limit',
            title: 'Raise the management message size limit',
            rationale: 'Rahdar reads large attributes through management messages.',
            appliable: true,
            section: 'ADDRESS_SETTING',
            match: 'activemq.management#',
            values: { managementMessageAttributeSizeLimit: 262144, maxSizeBytes: 1024 },
            roles: {},
            keys: ['managementMessageAttributeSizeLimit'],
            manualSnippet: null,
          },
          {
            capability: 'metrics-plugin',
            title: 'Install the metrics plugin',
            rationale: 'No management operation writes a plugin.',
            appliable: false,
            section: null,
            match: null,
            values: {},
            roles: {},
            keys: [],
            manualSnippet: '<metrics><plugin class-name="x"/></metrics>',
          },
        ],
      },
    ],
    [
      /\/config\/adopt$/,
      {
        document: {
          ...EMPTY_DOCUMENT,
          addresses: [{ name: 'orders.request', routingTypes: ['ANYCAST'], queues: [] }],
          addressSettings: [{ match: '#', values: { maxSizeBytes: 1 } }],
        },
        notes: [],
        disagreements: [],
        closes: [],
      },
    ],
  ];
  return routes.find(([pattern]) => pattern.test(path))?.[1] ?? {};
}

beforeEach(() => {
  vi.stubGlobal('fetch', async (input: RequestInfo | URL) => {
    const path = new URL(String(input), location.origin).pathname;
    await new Promise((resolve) => setTimeout(resolve, 150));
    return new Response(JSON.stringify(bodyFor(path)), { headers: { 'content-type': 'application/json' } });
  });
});
afterEach(() => {
  vi.unstubAllGlobals();
  undeclared = false;
});

function page(view: () => ReactElement, path: string, search: string) {
  const root = createRootRoute();
  const route = createRoute({ getParentRoute: () => root, path, component: view });
  return createRouter({
    routeTree: root.addChildren([route]),
    history: createMemoryHistory({ initialEntries: [`${path.replace('$clusterId', 'c1')}${search}`] }),
  });
}

/** The layout shift the page adds between mounting and settling, as the browser reports it. */
function watchShift(): () => number {
  let total = 0;
  const observer = new PerformanceObserver((list) => {
    for (const entry of list.getEntries() as unknown as { value: number; hadRecentInput: boolean }[]) {
      if (!entry.hadRecentInput) total += entry.value;
    }
  });
  observer.observe({ type: 'layout-shift', buffered: false });
  return () => {
    observer.takeRecords();
    observer.disconnect();
    return total;
  };
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

/** The window the console is laid out for, and the same window at 200% zoom, where the page wraps. */
const WIDTHS = [
  { name: '1280 px', width: () => contentWidth(1280) },
  { name: '200% zoom', width: () => 560 },
] as const;

describe.each(PAGES.flatMap((p) => WIDTHS.map((w) => ({ ...p, ...w, title: `${p.name} at ${w.name}` }))))(
  '$title',
  ({ view, path, search, ready, width, ...rest }) => {
    const role = 'role' in rest ? rest.role : 'heading';
    // The page that suggests adopting what the nodes run reads them after it is drawn; what it adds must
    // not push the empty state and the tabs below it down the page.
    it('suggests an adoption without moving the empty state under it', async () => {
      if (path.includes('config-diff') || search) return;
      undeclared = true;
      const done = watchShift();
      const { container } = renderThemed(
        <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
          <Frame width={width()} height={3200}>
            <RouterProvider router={page(view, path, search)} />
          </Frame>
        </QueryClientProvider>,
        'light',
      );
      await screen.findByText('Declare what this cluster should run', undefined, { timeout: 5000 });
      await screen.findByText('2 entries would be declared', undefined, { timeout: 5000 });
      await settle(() => `${container.scrollHeight}`);
      await new Promise((resolve) => setTimeout(resolve, 500));

      expect(done()).toBeLessThanOrEqual(0.01);
    });

    it('swaps loading for content without moving what is already on the page', async () => {
      const done = watchShift();
      const { container } = renderThemed(
        <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
          <Frame width={width()} height={3200}>
            <RouterProvider router={page(view, path, search)} />
          </Frame>
        </QueryClientProvider>,
        'light',
      );
      await screen.findByRole('heading', { level: 1 });
      await (role === 'grid'
        ? screen.findByRole('grid', { name: ready }, { timeout: 5000 })
        : screen.findByRole('heading', { level: 2, name: ready }, { timeout: 5000 }));
      await settle(() => `${container.scrollHeight}:${container.querySelectorAll('table').length}`);
      await new Promise((resolve) => setTimeout(resolve, 500));

      expect(done()).toBeLessThanOrEqual(0.01);
    });
  },
);
