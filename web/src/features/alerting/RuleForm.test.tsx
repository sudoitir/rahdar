import { describe, expect, it, vi } from 'vitest';
import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

import { renderWithProviders } from '../../test/render.tsx';
import { RuleForm } from './RuleForm.tsx';

function form(system: string | undefined) {
  renderWithProviders(<RuleForm channels={[]} submitting={false} onSubmit={vi.fn()} system={system} />);
}

describe('RuleForm on each system', () => {
  it('marks the Artemis-only conditions on a Kafka cluster and says what to watch instead', async () => {
    form('kafka');
    expect(screen.getByText(/unavailable on Kafka\. Watch cluster degraded/)).toBeInTheDocument();
    await userEvent.click(screen.getByRole('combobox', { name: 'Kind' }));
    await userEvent.click(screen.getByRole('option', { name: 'Cluster state' }));
    await userEvent.click(screen.getByRole('combobox', { name: 'State condition' }));
    expect(screen.getByRole('option', { name: 'split brain (Artemis only)' })).toHaveAttribute(
      'data-combobox-disabled',
    );
    expect(screen.getByRole('option', { name: 'cluster degraded' })).not.toHaveAttribute('data-combobox-disabled');
  });

  it('offers the consumer-lag template on Kafka instead of the slow-consumer one', async () => {
    form('kafka');
    expect(screen.queryByRole('button', { name: /slow-consumer/ })).not.toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: 'Start from the consumer-lag template' }));
    expect(screen.getByRole('textbox', { name: 'Name' })).toHaveValue('Consumer lag');
    expect(screen.getByText(/a consumer group’s lag on a topic/)).toBeInTheDocument();
  });

  it('offers the queue-depth template on RabbitMQ', async () => {
    form('rabbitmq');
    await userEvent.click(screen.getByRole('button', { name: 'Start from the queue-depth template' }));
    expect(screen.getByRole('textbox', { name: 'Name' })).toHaveValue('Queue depth');
  });

  it('keeps every choice on Artemis, and while the system is still loading', async () => {
    for (const system of ['artemis', undefined]) {
      const { unmount } = renderWithProviders(
        <RuleForm channels={[]} submitting={false} onSubmit={vi.fn()} system={system} />,
      );
      expect(screen.queryByText(/Artemis only|unavailable on/)).not.toBeInTheDocument();
      expect(screen.getByRole('button', { name: 'Start from the slow-consumer template' })).toBeInTheDocument();
      unmount();
    }
  });
});
