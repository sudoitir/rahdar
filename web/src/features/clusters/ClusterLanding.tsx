import { Navigate, useParams } from '@tanstack/react-router';

import { useFeatures } from '../../kernel/features.ts';
import { navGroups } from '../../kernel/registry.ts';
import { useClusterKinds } from '../../kernel/shell/useClusterSystem.ts';
import { LoadingState } from '../../ui/LoadingState.tsx';

/**
 * A cluster's own address: it opens the first view its system offers (Observe comes first), so an Artemis cluster
 * lands on its topology and a Kafka cluster on its brokers, never on a view that means nothing for it. It waits for
 * the cluster to say which system it runs.
 */
export function ClusterLanding() {
  const { clusterId } = useParams({ strict: false }) as { clusterId: string };
  const cluster = useClusterKinds(clusterId);
  const features = useFeatures();
  if (!cluster) return <LoadingState label="Opening the cluster" blockSize="24rem" />;
  const first = navGroups(
    features,
    cluster.system?.id,
    cluster.kinds?.map((k) => k.resourceClass),
  )[0]?.items[0];
  return <Navigate to={`/clusters/${clusterId}/${first?.path ?? 'settings'}`} replace />;
}
