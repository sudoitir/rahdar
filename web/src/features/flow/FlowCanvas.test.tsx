import { describe, expect, it, vi } from 'vitest';
import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

import { renderWithProviders } from '../../test/render.tsx';
import type { FlowGraphView } from './api.ts';

const layout = vi.hoisted(() => ({
  state: { positions: {}, pending: true, error: null as string | null, retry: vi.fn() },
}));
vi.mock('./useFlowLayout.ts', () => ({ useFlowLayout: () => layout.state }));

const { FlowCanvas } = await import('./FlowCanvas.tsx');

const graph = {
  nodes: [{ id: 'subscription:orders', kind: 'SUBSCRIPTION', label: 'orders', faults: [] }],
  edges: [],
  measuring: false,
  sampleIntervalSeconds: 15,
} as FlowGraphView;

function show() {
  return renderWithProviders(
    <FlowCanvas clusterId="c1" graph={graph} selectedId={null} onSelect={() => {}} paused={false} />,
  );
}

describe('FlowCanvas', () => {
  it('says it is laying the graph out, as a labelled busy status, until there are positions', () => {
    layout.state = { positions: {}, pending: true, error: null, retry: vi.fn() };
    show();

    const status = screen.getByRole('status');
    expect(status).toHaveTextContent('Laying out the graph');
    expect(status).toHaveAttribute('aria-busy', 'true');
  });

  it('states why the graph could not be laid out, points at the table, and lays it out again on request', async () => {
    const retry = vi.fn();
    layout.state = { positions: {}, pending: true, error: 'ELK ran out of memory', retry };
    show();

    expect(
      screen.getByText(/The graph could not be laid out\. The table still lists every shown path\./),
    ).toBeInTheDocument();
    expect(screen.getByRole('alert')).toHaveTextContent('ELK ran out of memory');
    await userEvent.click(screen.getByRole('button', { name: 'Retry' }));
    expect(retry).toHaveBeenCalledTimes(1);
  });
});
