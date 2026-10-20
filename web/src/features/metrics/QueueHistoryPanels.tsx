import { useMemo } from 'react';
import { Button, Group, Stack, Text } from '@mantine/core';
import { useNavigate } from '@tanstack/react-router';

import { useMetrics } from './api.ts';
import { subscriptionIdOf } from '../../kernel/api/resourceId.ts';
import { serverNow } from '../../kernel/time/time.ts';
import { DepthChart } from './DepthChart.tsx';
import { rangeSpec } from './ranges.ts';
import { ThroughputChart } from './ThroughputChart.tsx';

/** The drawer always shows the last hour; a longer view is the metrics page's job. */
const DRAWER_RANGE = '1h' as const;

/** A queue's last hour of depth and throughput in its detail drawer (`queue.detail.panels`). */
export function QueueHistoryPanels({
  clusterId,
  queueName,
  onClose,
}: Readonly<{
  clusterId: string;
  queueName: string;
  onClose: () => void;
}>) {
  const navigate = useNavigate();
  // The drawer's own window is a fixed hour, quantized to the same bucket the
  // metrics view uses so the two agree about where a bucket starts (ADR-0055).
  const spec = rangeSpec(DRAWER_RANGE);
  const { from, to, fromMs, toMs } = useMemo(() => {
    const end = Math.floor(serverNow() / spec.stepMs) * spec.stepMs;
    const start = end - spec.windowMs;
    return {
      from: new Date(start).toISOString(),
      to: new Date(end).toISOString(),
      fromMs: start,
      toMs: end,
    };
  }, [spec.stepMs, spec.windowMs]);
  const metrics = useMetrics(
    clusterId,
    {
      metrics: ['openbroker.backlog', 'openbroker.enqueued', 'openbroker.acknowledged'],
      resource: subscriptionIdOf(queueName),
      from,
      to,
    },
    false,
    true,
  );
  const byName = (name: string) => metrics.data?.series.find((s) => s.metric === name);
  const syncId = `queue-drawer-${queueName}`;

  return (
    <>
      <Group justify="flex-end">
        {/* Navigated rather than linked: the untyped router cannot type a search
            reducer on `Link`, and the whole point here is to carry `?subject=` across. */}
        <Button
          size="xs"
          variant="subtle"
          onClick={() => {
            onClose();
            void navigate({
              to: `/clusters/${clusterId}/metrics`,
              search: { subject: queueName } as never,
            });
          }}
        >
          History
        </Button>
      </Group>
      <Stack gap="xs">
        <Text size="xs" fw={600} c="dimmed">
          Depth · last hour
        </Text>
        <DepthChart
          series={byName('openbroker.backlog')}
          range={DRAWER_RANGE}
          from={fromMs}
          to={toMs}
          syncId={syncId}
        />
      </Stack>
      <Stack gap="xs">
        <Text size="xs" fw={600} c="dimmed">
          Throughput · last hour
        </Text>
        <ThroughputChart
          added={byName('openbroker.enqueued')}
          acked={byName('openbroker.acknowledged')}
          range={DRAWER_RANGE}
          from={fromMs}
          to={toMs}
          syncId={syncId}
        />
      </Stack>
    </>
  );
}
