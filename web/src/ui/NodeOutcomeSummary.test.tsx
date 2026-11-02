import { describe, expect, it } from 'vitest';
import { screen } from '@testing-library/react';

import { renderWithProviders } from '../test/render.tsx';
import type { components } from '../kernel/api/schema.d.ts';
import { NodeOutcomeSummary } from './NodeOutcomeSummary.tsx';

type LifecycleOutcomeView = components['schemas']['LifecycleOutcomeView'];
type NodeOutcomeView = components['schemas']['NodeOutcomeView'];

function node(over: Partial<NodeOutcomeView> = {}): NodeOutcomeView {
  return {
    nodeId: over.nodeId ?? 'n1',
    nodeName: over.nodeName ?? 'node-a',
    status: over.status ?? 'APPLIED',
    affected: over.affected ?? null,
    error: over.error ?? null,
  };
}

function outcome(over: Partial<LifecycleOutcomeView> = {}): LifecycleOutcomeView {
  return {
    dryRun: false,
    cap: 1000,
    identityBasis: 'INCARNATION',
    overCap: false,
    partial: false,
    totalAffected: 0,
    nodes: [node()],
    ...over,
  };
}

describe('NodeOutcomeSummary', () => {
  it('states a complete application before any row is read', () => {
    renderWithProviders(
      <NodeOutcomeSummary
        outcome={outcome({ nodes: [node({ nodeId: 'a' }), node({ nodeId: 'b', nodeName: 'node-b' })] })}
      />,
    );
    expect(screen.getByText('Applied to all 2 nodes')).toBeInTheDocument();
  });

  it('distinguishes a partial application from a complete one in the headline', () => {
    renderWithProviders(
      <NodeOutcomeSummary
        outcome={outcome({
          partial: true,
          nodes: [
            node({ nodeId: 'a', status: 'APPLIED' }),
            node({ nodeId: 'b', nodeName: 'node-b', status: 'FAILED', error: 'connection refused' }),
          ],
        })}
      />,
    );
    expect(screen.getByText('Applied to some nodes and not others')).toBeInTheDocument();
    // The failure names its cause rather than only marking the row red.
    expect(screen.getByText('connection refused')).toBeInTheDocument();
  });

  it('carries every node state in text, not only in colour', () => {
    renderWithProviders(
      <NodeOutcomeSummary
        outcome={outcome({
          nodes: [
            node({ nodeId: 'a', status: 'APPLIED' }),
            node({ nodeId: 'b', nodeName: 'node-b', status: 'ALREADY' }),
            node({ nodeId: 'c', nodeName: 'node-c', status: 'SKIPPED_NOT_LIVE' }),
          ],
        })}
      />,
    );
    expect(screen.getByText('applied')).toBeInTheDocument();
    expect(screen.getByText('already in this state')).toBeInTheDocument();
    expect(screen.getByText('skipped — not live')).toBeInTheDocument();
  });

  it('reports a preview as a preview, naming the nodes that would be skipped', () => {
    renderWithProviders(
      <NodeOutcomeSummary
        outcome={outcome({
          dryRun: true,
          nodes: [
            node({ nodeId: 'a', status: 'WOULD_APPLY' }),
            node({ nodeId: 'b', nodeName: 'node-b', status: 'SKIPPED_NOT_LIVE' }),
          ],
        })}
      />,
    );
    expect(screen.getByText('Would apply to 1 of 2 nodes, 1 not live and will be skipped')).toBeInTheDocument();
  });

  it('shows the per-node message counts only for a destructive command', () => {
    const destructive = outcome({
      dryRun: true,
      totalAffected: 42,
      nodes: [node({ nodeId: 'a', status: 'WOULD_APPLY', affected: 42 })],
    });

    const { unmount } = renderWithProviders(<NodeOutcomeSummary outcome={destructive} destructive />);
    expect(screen.getByText('would destroy 42 messages')).toBeInTheDocument();
    unmount();

    renderWithProviders(<NodeOutcomeSummary outcome={destructive} />);
    expect(screen.queryByText(/would destroy/)).not.toBeInTheDocument();
  });
});

describe('NodeOutcomeSummary announcement', () => {
  it('is a polite live region, so a screen reader hears the outcome', () => {
    renderWithProviders(<NodeOutcomeSummary outcome={outcome()} />);

    const region = screen.getByRole('status');
    expect(region).toHaveAttribute('aria-live', 'polite');
    expect(region).toHaveTextContent(/Applied to all 1 nodes/);
  });
});
