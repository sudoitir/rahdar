import js from '@eslint/js';
import globals from 'globals';
import reactHooks from 'eslint-plugin-react-hooks';
import reactRefresh from 'eslint-plugin-react-refresh';
import tseslint from 'typescript-eslint';
import boundaries from 'eslint-plugin-boundaries';

// Module boundaries (ADR-0074). A feature may import another only through its `index.ts`, and only
// along these edges (design D5); every feature may use clusters, the platform they all work on.
const featureEdges = {
  apitokens: ['security'],
  audit: ['security'],
  brokerconfig: ['messages'],
  messages: ['resources'],
  rr: ['resources', 'sql'],
  sql: ['messages', 'resources'],
  transfer: ['resources'],
};

export default tseslint.config(
  { ignores: ['dist', 'node_modules', 'packages/*/dist', 'coverage', '.sweep', '.vitest'] },
  {
    files: ['src/**/*.{ts,tsx}'],
    plugins: { boundaries },
    settings: {
      'boundaries/elements': [
        { type: 'kernel', pattern: 'src/kernel' },
        { type: 'ui', pattern: 'src/ui' },
        { type: 'app', pattern: 'src/app' },
        // The plugin SDK (ADR-0100): a curated re-export of kernel and ui for plugin bundles.
        { type: 'sdk', pattern: 'src/sdk' },
        { type: 'test', pattern: 'src/test' },
        { type: 'feature', pattern: 'src/features/*', capture: ['id'] },
      ],
      'boundaries/files': [
        { pattern: 'src/features/*/index.ts', category: 'entry' },
        { pattern: 'src/{kernel,ui,features}/**/*.test.{ts,tsx}', category: 'test-file' },
        { pattern: 'src/kernel/api/schema.d.ts', category: 'schema' },
      ],
    },
    rules: {
      'boundaries/dependencies': [
        'error',
        {
          default: 'disallow',
          policies: [
            // A test beside its code uses the shared harness; every other edge it takes is
            // held to its element's own policy.
            { from: { file: { categories: 'test-file' } }, allow: { to: { element: { type: 'test' } } } },
            // Shared components name the generated DTOs they render, and nothing else of the app.
            {
              from: { element: { type: 'ui' } },
              allow: {
                to: [{ element: { type: 'ui' } }, { element: { type: 'kernel' }, file: { categories: 'schema' } }],
              },
            },
            { from: { element: { type: 'kernel' } }, allow: { to: { element: { type: ['kernel', 'ui'] } } } },
            { from: { element: { type: 'sdk' } }, allow: { to: { element: { type: ['kernel', 'ui'] } } } },
            { from: { element: { type: ['app', 'test'] } }, allow: { to: { element: { type: '*' } } } },
            {
              from: { element: { type: 'feature' } },
              allow: {
                to: [
                  { element: { type: ['kernel', 'ui'] } },
                  { element: { type: 'feature', captured: { id: '{{ from.element.captured.id }}' } } },
                  { element: { type: 'feature', captured: { id: 'clusters' } }, file: { categories: 'entry' } },
                ],
              },
            },
            ...Object.entries(featureEdges).map(([from, to]) => ({
              from: { element: { type: 'feature', captured: { id: from } } },
              allow: { to: { element: { type: 'feature', captured: { id: to } }, file: { categories: 'entry' } } },
            })),
          ],
        },
      ],
    },
  },
  {
    extends: [js.configs.recommended, ...tseslint.configs.recommended],
    files: ['**/*.{ts,tsx}'],
    languageOptions: {
      ecmaVersion: 2022,
      globals: globals.browser,
    },
    plugins: {
      'react-hooks': reactHooks,
      'react-refresh': reactRefresh,
    },
    rules: {
      // The hook rules only; v7's React Compiler rules are a separate adoption.
      'react-hooks/rules-of-hooks': 'error',
      'react-hooks/exhaustive-deps': 'warn',
      'react-refresh/only-export-components': ['warn', { allowConstantExport: true }],
    },
  },
  {
    // Nothing turns a string into markup or code (ADR-0168). Rahdar's CSP refuses these sinks at run
    // time (Trusted Types, no `eval`); this stops them at review. The one place HTML is rendered is the
    // code highlighter, inside Mantine, behind `ui/trustedTypes.ts`. A real exception is an inline
    // disable with its reason, never an allow-list here.
    files: ['**/*.{ts,tsx}'],
    rules: {
      'no-eval': 'error',
      'no-implied-eval': 'error',
      'no-new-func': 'error',
      'no-script-url': 'error',
      'no-restricted-syntax': [
        'error',
        {
          selector: "JSXAttribute[name.name='dangerouslySetInnerHTML']",
          message: 'Render text, or highlight code through CodeHighlight. Never set raw HTML.',
        },
        {
          selector: "Property[key.name='dangerouslySetInnerHTML']",
          message: 'Render text, or highlight code through CodeHighlight. Never set raw HTML.',
        },
        {
          selector: "AssignmentExpression[left.type='MemberExpression'][left.property.name=/^(innerHTML|outerHTML)$/]",
          message: 'Assigning HTML to an element is an XSS sink. Build elements, or render text.',
        },
        {
          selector: "CallExpression[callee.property.name='insertAdjacentHTML']",
          message: 'insertAdjacentHTML is an XSS sink. Build elements, or render text.',
        },
        {
          selector: "CallExpression[callee.object.name='document'][callee.property.name=/^write(ln)?$/]",
          message: 'document.write is an XSS sink.',
        },
      ],
    },
  },
  {
    // Test files and test helpers legitimately export non-components and use
    // Node globals (MSW server lifecycle, jsdom shims).
    files: ['src/test/**', '**/*.test.{ts,tsx}'],
    languageOptions: { globals: { ...globals.node } },
    // A test feeds `javascript:` addresses to the code that must refuse them.
    rules: { 'react-refresh/only-export-components': 'off', 'no-script-url': 'off' },
  },
);
