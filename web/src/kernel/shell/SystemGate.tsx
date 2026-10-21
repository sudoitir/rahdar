import type { ReactNode } from 'react';
import { useParams } from '@tanstack/react-router';

import { LoadingState } from '../../ui/LoadingState.tsx';
import { RouteNotFound } from './RouteNotFound.tsx';
import { useClusterSystem } from './useClusterSystem.ts';

/** The height a view holds while the cluster loads, so the page does not jump when the view arrives. */
const VIEW_BLOCK_SIZE = '24rem';

/**
 * A system-only view, or the not-found page on a cluster that runs another system: such a view has no
 * meaning there, and its API answers 404 as well, so the page says so rather than showing an empty screen.
 * It waits for the cluster, because a claim about the system is made only once it is known.
 */
export function SystemGate({ system, children }: Readonly<{ system: string; children: ReactNode }>) {
  const { clusterId } = useParams({ strict: false }) as { clusterId?: string };
  const actual = useClusterSystem(clusterId);
  if (actual === undefined) return <LoadingState label="Loading the page" blockSize={VIEW_BLOCK_SIZE} />;
  return actual === system ? <>{children}</> : <RouteNotFound />;
}
