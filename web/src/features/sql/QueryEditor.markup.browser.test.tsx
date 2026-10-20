import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { userEvent } from 'vitest/browser';
import { render, screen, waitFor } from '@testing-library/react';
import { MantineProvider } from '@mantine/core';

import { QueryEditor } from './QueryEditor.tsx';

// The browser project is served with Rahdar's Trusted Types directives (vitest.config.ts). SQL with markup
// in a literal must go in and come out unchanged without the page's policy refusing anything (ADR-0168).
let violations: SecurityPolicyViolationEvent[];
const onViolation = (event: SecurityPolicyViolationEvent) => violations.push(event);

beforeEach(() => {
  violations = [];
  document.addEventListener('securitypolicyviolation', onViolation);
});
afterEach(async () => {
  await new Promise((resolve) => setTimeout(resolve));
  document.removeEventListener('securitypolicyviolation', onViolation);
});

describe('QueryEditor under Trusted Types', () => {
  it('takes SQL with markup in a literal and hands it back unchanged', async () => {
    const onChange = vi.fn();
    const { container } = render(
      <MantineProvider>
        <QueryEditor
          value=""
          onChange={onChange}
          onRun={vi.fn()}
          onCancel={vi.fn()}
          onMaximise={vi.fn()}
          onEscape={vi.fn()}
          subscriptions={['orders.<new>&co']}
        />
      </MantineProvider>,
    );

    await userEvent.click(screen.getByRole('textbox', { name: 'Query' }));
    await userEvent.keyboard("select * from subscriptions where name = '<b>&amp;</b>'");
    await waitFor(() => expect(onChange).toHaveBeenCalled());
    expect(onChange.mock.lastCall?.[0]).toContain("'<b>&amp;</b>'");
    expect(container.querySelector('b, script, img')).toBeNull();
    expect(violations).toEqual([]);
  });
});
