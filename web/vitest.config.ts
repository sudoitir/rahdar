import { configDefaults, defineConfig } from 'vitest/config';
import { playwright } from '@vitest/browser-playwright';
import react from '@vitejs/plugin-react';

import { TRUSTED_TYPES_DIRECTIVES } from './src/test/trustedTypesPolicy.ts';

// Standalone from vite.config.ts: the app config carries a dev-server proxy we
// don't want here, and the test run needs the jsdom environment + setup file.
// Same @vitejs/plugin-react transform, so component behaviour matches the build.
//
// Two projects (ADR-0165). `unit` is jsdom, for behaviour (ADR-0024). `browser` runs the
// `*.browser.test.*` files in Chromium, where layout, CSS modules, fonts and contrast are real.
const BROWSER_TESTS = '**/*.browser.test.{ts,tsx}';

export default defineConfig({
  plugins: [react()],
  test: {
    globals: true,
    // Vitest's 5 s per test is sized for a unit test. These render Mantine, TanStack Router and, for the
    // screens that use them, a virtualised grid or a diagram in jsdom, and a full run puts every core to
    // work at once: a test that takes 2 s alone takes 6 s beside 20 others. A timeout is only the bound on
    // a test that is stuck, so it is set where a slow machine cannot trip it (a stuck one still fails).
    testTimeout: 20_000,
    hookTimeout: 20_000,
    projects: [
      {
        extends: true,
        test: {
          name: 'unit',
          environment: 'jsdom',
          setupFiles: ['./src/test/setup.ts'],
          css: false,
          include: ['src/**/*.test.{ts,tsx}', 'scripts/**/*.test.ts', 'packages/*/src/**/*.test.ts'],
          exclude: [...configDefaults.exclude, BROWSER_TESTS],
        },
      },
      {
        extends: true,
        // Named up front: Vite otherwise finds these while the first test file loads, reloads the page
        // on finding them, and Vitest fails the run for a reload it did not plan.
        optimizeDeps: {
          include: [
            '@codemirror/autocomplete',
            '@codemirror/commands',
            '@codemirror/lang-sql',
            '@codemirror/language',
            '@codemirror/state',
            '@codemirror/view',
            '@lezer/highlight',
            '@mantine/charts',
            '@mantine/code-highlight',
            '@mantine/core',
            '@mantine/hooks',
            '@mantine/notifications',
            '@mantine/spotlight',
            '@module-federation/runtime',
            '@tabler/icons-react',
            '@tanstack/react-query',
            '@tanstack/react-router',
            '@tanstack/react-virtual',
            '@xyflow/react',
            'axe-core',
            'dayjs',
            'dayjs/plugin/timezone',
            'dayjs/plugin/utc',
            'dompurify',
            'elkjs/lib/elk-api.js',
            'elkjs/lib/elk.bundled.js',
            'react-dom/client',
            'shiki/core',
            'shiki/engine/oniguruma',
            'shiki/wasm',
            '@shikijs/langs/json',
            '@shikijs/langs/properties',
            '@shikijs/langs/sql',
            '@shikijs/langs/xml',
            '@shikijs/langs/yaml',
            'uqr',
          ],
        },
        // Every browser test runs with Trusted Types enforced, as Rahdar's pages do (ADR-0168).
        server: { headers: { 'Content-Security-Policy': TRUSTED_TYPES_DIRECTIVES } },
        test: {
          name: 'browser',
          setupFiles: ['./src/test/browser-setup.ts'],
          css: true,
          include: [`src/${BROWSER_TESTS}`],
          browser: {
            enabled: true,
            headless: true,
            // An import map is a script. The tester page enforces Trusted Types, which refuse a
            // string assigned to a script, so mocks stay on request interception.
            importMapMocks: false,
            provider: playwright(),
            // The widest window the console is laid out for (ADR-0164); a test sets its own container width.
            instances: [{ browser: 'chromium', viewport: { width: 1920, height: 1080 } }],
          },
        },
      },
    ],
    // `npm run test:coverage` (ADR-0128): V8 coverage of the app's own sources, reported as text,
    // HTML, a JSON summary that CI puts in the job summary, and lcov for SonarQube Cloud. No threshold.
    coverage: {
      provider: 'v8',
      include: ['src/**/*.{ts,tsx}'],
      exclude: ['src/**/*.test.{ts,tsx}', 'src/test/**', 'src/kernel/api/schema.d.ts', 'src/**/*.d.ts'],
      reporter: ['text-summary', 'html', 'json-summary', 'lcov'],
      reportsDirectory: 'coverage',
    },
  },
});
