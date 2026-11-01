import { describe, expect, it } from 'vitest';
import { screen } from '@testing-library/react';

import { renderWithProviders } from '../test/render.tsx';
import { IdentityBasisNote } from './IdentityBasisNote.tsx';

describe('IdentityBasisNote', () => {
  it('says a recreated resource is not told apart when the basis is the configuration', () => {
    renderWithProviders(<IdentityBasisNote basis="CONFIGURATION" />);

    expect(screen.getByText('A recreated resource is not told apart')).toBeInTheDocument();
    expect(
      screen.getByText(
        /deleted and created again with the same configuration before this runs, this applies to the new one/,
      ),
    ).toBeInTheDocument();
  });

  it.each(['INCARNATION', undefined, null] as const)('says nothing for the basis %s', (basis) => {
    renderWithProviders(<IdentityBasisNote basis={basis} />);

    expect(screen.queryByText('A recreated resource is not told apart')).not.toBeInTheDocument();
  });
});
