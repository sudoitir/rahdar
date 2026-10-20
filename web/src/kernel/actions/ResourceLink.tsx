import type { ComponentType, ReactNode } from 'react';
import { useParams } from '@tanstack/react-router';

import { useSlot, type SlotName } from '../slots.ts';
import type { ActionTargets } from './types.ts';

/** The kinds another view can link to, each through the owning feature's `<kind>.link` slot. */
export type LinkKind = 'subscription' | 'destination' | 'connection' | 'channel';

type LinkSlot<K extends LinkKind> = `${K}.link` & SlotName;

/**
 * A resource's name, as a link to the resource itself (ADR-0107): the owning feature renders the
 * link, so a view naming a subscription never needs to know where subscriptions live. When that feature is
 * disabled the name is plain text — never a link that leads to a page explaining it is off.
 */
export function ResourceLink<K extends LinkKind>({
  kind,
  clusterId,
  target,
  children,
}: Readonly<{
  kind: K;
  /** Defaults to the cluster in the address. */
  clusterId?: string;
  target: ActionTargets[K];
  children: ReactNode;
}>) {
  const params = useParams({ strict: false }) as { clusterId?: string };
  const [first] = useSlot(`${kind}.link` as LinkSlot<K>);
  const cluster = clusterId ?? params.clusterId;
  if (!first || !cluster) return <>{children}</>;
  const Link = first.Component as ComponentType<object>;
  const props: object = { clusterId: cluster, target, children };
  return <Link {...props} />;
}
