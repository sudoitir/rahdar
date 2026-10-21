import { describe, expect, it } from 'vitest';
import { screen } from '@testing-library/react';

import { axeViolations, Frame, renderThemed, SCHEMES } from '../test/browser.tsx';
import { ErrorState } from './ErrorState.tsx';

const forbidden = { status: 403, problem: { permission: 'subscription:purge' } };
const invalid = { status: 422, fieldErrors: [{ field: 'name', message: 'must not be blank' }] };
const broker = {
  status: 502,
  brokerErrorKind: 'UNREACHABLE',
  problem: { detail: 'Connection refused: artemis-1:8161' },
};
const server = { status: 500, problem: { requestId: 'req-7f3a', detail: 'The broker timed out.' } };

describe('ErrorState', () => {
  describe.each(SCHEMES)('in the %s scheme', (scheme) => {
    it.each([
      ['a forbidden request', forbidden],
      ['an invalid one', invalid],
      ['a server failure', server],
      ['a broker failure with its detail', broker],
    ])('has no accessibility violations for %s', async (_, error) => {
      const { container } = renderThemed(
        <Frame width={960}>
          <ErrorState error={error} onRetry={() => {}} actions={<button type="button">Open cluster settings</button>} />
        </Frame>,
        scheme,
      );
      expect(await axeViolations(container)).toEqual([]);
    });

    it('has no accessibility violations inline', async () => {
      const { container } = renderThemed(
        <Frame width={960}>
          <ErrorState error={server} variant="inline" />
        </Frame>,
        scheme,
      );
      expect(await axeViolations(container)).toEqual([]);
    });
  });

  it('fills its row as a panel with the danger edge on the inline start, and stacks cause, fields and next step', () => {
    renderThemed(
      <Frame width={960}>
        <ErrorState error={invalid} onRetry={() => {}} />
      </Frame>,
      'light',
    );
    const alert = screen.getByRole('alert');
    const style = getComputedStyle(alert);
    expect(alert.getBoundingClientRect().width).toBeCloseTo(960, 0);
    expect(Number.parseFloat(style.borderInlineStartWidth)).toBe(4);
    const title = screen.getByText('Some values are not valid').getBoundingClientRect();
    const field = screen.getByText(/must not be blank/).getBoundingClientRect();
    const next = screen.getByText(/Correct the fields listed here/).getBoundingClientRect();
    expect(field.top).toBeGreaterThanOrEqual(title.bottom);
    expect(next.top).toBeGreaterThanOrEqual(field.bottom);
  });

  it('holds the height it is given, so it can replace a loading frame of that size without moving the page', () => {
    renderThemed(
      <Frame width={960}>
        <ErrorState error={server} blockSize="20rem" />
      </Frame>,
      'light',
    );
    expect(screen.getByRole('alert').getBoundingClientRect().height).toBeGreaterThanOrEqual(20 * 16);
  });

  it('wraps on one line inside a section when inline, so it does not take a panel of height', () => {
    renderThemed(
      <Frame width={960}>
        <ErrorState error={server} variant="inline" />
      </Frame>,
      'light',
    );
    const alert = screen.getByRole('alert');
    expect(getComputedStyle(alert).flexDirection).toBe('row');
    const title = screen.getByText('Rahdar failed to complete the request').getBoundingClientRect();
    const cause = screen.getByText('The broker timed out.').getBoundingClientRect();
    expect(Math.abs(title.top - cause.top)).toBeLessThan(title.height);
  });
});
