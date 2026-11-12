import { screen } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { renderWithProviders } from '../../test/render.tsx';
import { GroupsView } from './GroupsView.tsx';

const search = vi.hoisted(() => ({ q: undefined as string | undefined, type: undefined as string | undefined, state: undefined as string | undefined }));
const query = vi.hoisted(() => ({
  pending: false,
  error: false,
  items: [] as { id: string }[],
}));

vi.mock('@tanstack/react-router', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@tanstack/react-router')>();
  return {
    ...actual,
    useParams: () => ({ clusterId: 'k1' }),
    useSearch: () => search,
    useNavigate: () => () => undefined,
  };
});

vi.mock('./api.ts', () => ({
  useGroups: () => ({
    data: { pages: [{ items: query.items }] },
    isPending: query.pending,
    isError: query.error,
    error: query.error ? new Error('The cluster could not be reached.') : null,
    hasNextPage: false,
    isFetchingNextPage: false,
    fetchNextPage: () => undefined,
    refetch: () => undefined,
  }),
}));

function scheme(value: 'light' | 'dark') {
  document.documentElement.setAttribute('data-mantine-color-scheme', value);
}

beforeEach(() => {
  search.q = undefined;
  search.type = undefined;
  search.state = undefined;
  query.pending = false;
  query.error = false;
  query.items = [];
});

describe('group list states', () => {
  it.each(['light', 'dark'] as const)('shows loading, empty, and filtered-empty in %s', (color) => {
    scheme(color);
    query.pending = true;
    const { rerender } = renderWithProviders(<GroupsView />);
    expect(screen.getByText('Loading Consumer groups')).toBeInTheDocument();

    query.pending = false;
    rerender(<GroupsView />);
    expect(screen.getByText('No consumer groups yet')).toBeInTheDocument();

    search.q = 'missing';
    rerender(<GroupsView />);
    expect(screen.getByText('No group matches this filter')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Clear filters' })).toBeInTheDocument();
  });

  it.each(['light', 'dark'] as const)('shows an error in %s', (color) => {
    scheme(color);
    query.error = true;
    renderWithProviders(<GroupsView />);
    expect(screen.getByText('The cluster could not be reached.')).toBeInTheDocument();
  });
});
