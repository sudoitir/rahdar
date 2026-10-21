import type { ResourceRef } from '../../kernel/api/resourceId.ts';
import { useMemo } from 'react';
import { Button, Group, Stack, Text } from '@mantine/core';
import { useNavigate } from '@tanstack/react-router';

import { useMetrics } from './api.ts';
import { serverNow } from '../../kernel/time/time.ts';
import { DepthChart } from './DepthChart.tsx';
import { rangeSpec } from './ranges.ts';
import { ThroughputChart } from './ThroughputChart.tsx';

/** The drawer always shows the last hour; a longer view is the metrics page's job. */
const DRAWER_RANGE = '1h' as const;

/** A subscription's last hour of depth and throughput in its detail drawer (`subscription.detail.panels`). */
export function SubscriptionHistoryPanels({
  clusterId,
  subscription,
  onClose,
}: Readonly<{
  clusterId: string;
  subscription: ResourceRef;
  onClose: () => void;
}>) {
  const subscriptionName = subscription.name;
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
      resource: subscription.id,
      from,
      to,
    },
    false,
  );
  const byName = (name: string) => metrics.data?.series.find((s) => s.metric === name);
  const syncId = `subscription-drawer-${subscriptionName}`;

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
              search: { subject: subscriptionName } as never,
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
