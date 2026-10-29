import { describe, expect, it } from 'vitest';
import { screen } from '@testing-library/react';

import { renderWithProviders } from '../test/render.tsx';
import { FocusableScroll } from './FocusableScroll.tsx';

describe('FocusableScroll', () => {
  it('is a named region the keyboard can reach, so text-only content can be scrolled', () => {
    renderWithProviders(
      <FocusableScroll>
        <p>Only text, nothing to focus.</p>
      </FocusableScroll>,
    );
    const region = screen.getByRole('region', { name: 'Details, scrollable' });
    expect(region).toHaveAttribute('tabindex', '0');
    expect(region).toHaveTextContent('Only text, nothing to focus.');
  });
});
