import { describe, expect, it } from 'vitest';
import { screen } from '@testing-library/react';

import { ApiError } from '../api/request.ts';
import { renderWithProviders } from '../../test/render.tsx';
import { ChartPanel } from './ChartPanel.tsx';

function panel(over: Partial<Parameters<typeof ChartPanel>[0]> = {}) {
  return renderWithProviders(
    <ChartPanel
      title="Subscription depth"
      unit="messages"
      isPending={false}
      error={null}
      isEmpty={false}
      emptyLabel="No depth samples in this window."
      {...over}
    >
      <p>the plot</p>
    </ChartPanel>,
  );
}

describe('ChartPanel', () => {
  it('shows the plot, titled, when there is something to plot', () => {
    panel();
    expect(screen.getByText('Subscription depth')).toBeInTheDocument();
    expect(screen.getByText('messages')).toBeInTheDocument();
    expect(screen.getByText('the plot')).toBeInTheDocument();
  });

  it('shows the failure instead of an empty plot', () => {
    panel({ error: new ApiError(403, { title: 'Forbidden', permission: 'metrics:read' }) });
    expect(screen.getByRole('alert')).toHaveTextContent('Your role does not include the metrics:read permission.');
    expect(screen.queryByText('the plot')).not.toBeInTheDocument();
  });

  it('holds the plot’s place, named, while loading', () => {
    panel({ isPending: true });
    expect(screen.getByRole('status')).toHaveTextContent('Loading Subscription depth');
    expect(screen.queryByText('the plot')).not.toBeInTheDocument();
  });

  it('states an empty window in words', () => {
    panel({ isEmpty: true });
    expect(screen.getByRole('region')).toHaveTextContent('No depth samples in this window.');
    expect(screen.queryByText('the plot')).not.toBeInTheDocument();
  });
});
