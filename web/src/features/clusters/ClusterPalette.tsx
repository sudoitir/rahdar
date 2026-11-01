import { useEffect } from 'react';
import type { SpotlightActionGroupData } from '@mantine/spotlight';
import { useNavigate } from '@tanstack/react-router';

import { sameViewOn, useCurrentView } from '../../kernel/nav/currentView.ts';
import { useClusters } from './api.ts';

/**
 * The command palette's Clusters group: every registered cluster, opening on the view the operator is
 * on (ADR-0109), or on its topology from outside any cluster.
 */
export function ClusterPalette({ report }: { report: (groups: SpotlightActionGroupData[]) => void }) {
  const clusters = useClusters();
  const navigate = useNavigate();
  const view = useCurrentView();

  useEffect(() => {
    report([
      {
        group: 'Clusters',
        actions: (clusters.data ?? []).map((c) => {
          const nodes = `${c.nodeCount} node${c.nodeCount === 1 ? '' : 's'}`;
          const sameView = view?.item ? sameViewOn(c.id, view, c.system?.id) : `/clusters/${c.id}`;
          const keepsView = sameView !== `/clusters/${c.id}`;
          return {
            id: `cluster-${c.id}`,
            label: c.name,
            description:
              view?.item && keepsView && view.clusterId !== c.id
                ? `${view.item.label} on this cluster · ${nodes}`
                : nodes,
            onClick: () => navigate({ to: sameView }),
          };
        }),
      },
    ]);
  }, [clusters.data, navigate, report, view]);

  return null;
}
