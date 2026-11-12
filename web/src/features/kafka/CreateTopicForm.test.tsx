import { describe, expect, it } from 'vitest';
import { screen } from '@testing-library/react';

import { renderWithProviders } from '../../test/render.tsx';
import { CreateTopicForm } from './CreateTopicForm.tsx';

function renderForm(scheme: 'light' | 'dark', permissionReason: string | null = null) {
  document.documentElement.setAttribute('data-mantine-color-scheme', scheme);
  return renderWithProviders(
    <CreateTopicForm
      clusterId="k1"
      opened
      onClose={() => undefined}
      brokersReached={3}
      permissionReason={permissionReason}
    />,
  );
}

describe('creating a topic', () => {
  it.each(['light', 'dark'] as const)('asks only for the name, the counts and the five settings in %s', (scheme) => {
    renderForm(scheme);
    for (const label of [
      'Name',
      'Partition count',
      'Replication factor',
      'cleanup.policy',
      'retention.ms',
      'retention.bytes',
      'max.message.bytes',
      'min.insync.replicas',
    ]) {
      expect(screen.getAllByLabelText(label).length).toBeGreaterThan(0);
    }
    expect(screen.getByRole('button', { name: 'Create topic' })).toBeInTheDocument();
    expect(screen.getByText(/This cluster reached 3 brokers/)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Preview topic' })).toBeInTheDocument();
  });

  it('keeps create visible and says why the permission is missing', () => {
    renderForm('light', 'You do not have the destination:create permission.');
    expect(screen.getByRole('button', { name: 'Create topic' })).toBeDisabled();
    expect(screen.getByText('You do not have the destination:create permission.')).toBeInTheDocument();
  });
});
