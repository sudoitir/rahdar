import { beforeEach, describe, expect, it } from 'vitest';
import { http, HttpResponse } from 'msw';
import { screen } from '@testing-library/react';
import { createRoute } from '@tanstack/react-router';

import { paged } from '../api/paging.ts';
import { renderAppAt } from '../../test/render.tsx';
import { server } from '../../test/setup.ts';
import { CONTRACT, defineFeature, type RahdarFeature } from '../feature.ts';
import { FeatureProvider } from '../FeatureProvider.tsx';
import { rootRoute } from '../routing/roots.ts';
import { HomeView } from './HomeView.tsx';

const route = createRoute({
  getParentRoute: () => rootRoute,
  path: 'home-under-test',
  component: () => (
    <FeatureProvider features={features}>
      <HomeView />
    </FeatureProvider>
  ),
});

const features: RahdarFeature[] = [
  defineFeature({
    contract: CONTRACT,
    id: 'clusters',
    routes: { root: [route] },
    slots: { 'home.estate': [{ id: 'prompt', order: 10, Component: () => <p>Register your first cluster</p> }] },
  }),
];

describe('the landing page', () => {
  beforeEach(() => {
    server.use(
      http.get('*/api/v1/auth/me', () =>
        HttpResponse.json({ id: 'u1', username: 'test-user', mustChangePassword: false, grants: [] }),
      ),
      http.get('*/api/v1/clusters', () => HttpResponse.json(paged([]))),
      http.get('*/api/v1/environments', () => HttpResponse.json(paged([]))),
      http.get('*/api/v1/alerts/firing', () => HttpResponse.json(paged([]))),
    );
  });

  it('is one page with a single h1, and shows what the features contribute for it', async () => {
    renderAppAt('/home-under-test', features);

    expect(await screen.findByRole('heading', { level: 1, name: 'All clusters' })).toBeInTheDocument();
    expect(screen.getAllByRole('heading', { level: 1 })).toHaveLength(1);
    expect(screen.getByText('Register your first cluster')).toBeInTheDocument();
  });
});
