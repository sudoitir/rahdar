import { useState } from 'react';
import { Button, List, Stack, Text } from '@mantine/core';
import { BarChart } from '@mantine/charts';

import { EmptyState } from '../../ui/EmptyState.tsx';
import { ErrorState } from '../../ui/ErrorState.tsx';
import { LoadingState } from '../../ui/LoadingState.tsx';
import { Section } from '../../ui/Section.tsx';
import { useRrStats } from './api.ts';

/** Coverage lines shown before the list is folded away — one per traced destination. */
const COVERAGE_VISIBLE = 6;

const CHART_HEIGHT = '17.5rem';

/**
 * p50/p95/p99 per traced destination. The sampling caveat and coverage estimate
 * sit next to the chart, not in a tooltip (request-reply-tracing spec —
 * latency is never shown without its coverage).
 */
export function LatencyPanel({ clusterId }: Readonly<{ clusterId: string }>) {
  const stats = useRrStats(clusterId);
  const [allCoverage, setAllCoverage] = useState(false);
  const destinations = stats.data?.destinations ?? [];

  if (stats.isError) {
    return (
      <Stack gap="sm">
        <Text size="sm">Latency could not be read, which is not the same as there being no traced flows.</Text>
        <ErrorState error={stats.error} onRetry={() => void stats.refetch()} />
      </Stack>
    );
  }

  if (stats.isPending) {
    return <LoadingState label="Loading latency" blockSize={CHART_HEIGHT} />;
  }

  if (destinations.length === 0) {
    return (
      <EmptyState
        kind="empty"
        title="No completed flows yet"
        description="Latency is the time between a traced request and its reply. It appears once at least one traced request has been answered."
      />
    );
  }

  // A percentile with no value is omitted, never coerced to zero: an absent
  // number reads as "instant", which is the most dangerous reading of a latency
  // chart there is.
  const data = destinations.map((a) => ({
    destination: a.destination,
    p50: a.p50Ms ?? undefined,
    p95: a.p95Ms ?? undefined,
    p99: a.p99Ms ?? undefined,
  }));
  const listed = allCoverage ? destinations : destinations.slice(0, COVERAGE_VISIBLE);

  return (
    <Stack gap="md">
      <Section
        variant="card"
        headingLevel={3}
        title="Sampled, not exhaustive"
        description="Latency is measured only on requests Rahdar happened to observe — a request that completes faster than the sample interval is never seen, which biases these numbers toward slower flows."
      >
        <List size="xs" spacing={2} aria-label="Coverage per destination">
          {listed.map((a) => (
            <List.Item key={a.destination}>
              {a.destination}:{' '}
              {a.coverageRatio == null
                ? 'coverage unknown'
                : `~${Math.round(a.coverageRatio * 100)}% of requests observed`}
            </List.Item>
          ))}
        </List>
        {destinations.length > COVERAGE_VISIBLE ? (
          <Button
            variant="subtle"
            size="xs"
            w="fit-content"
            aria-expanded={allCoverage}
            onClick={() => setAllCoverage((on) => !on)}
          >
            {allCoverage ? 'Show fewer' : `Show coverage for all ${destinations.length} destinations`}
          </Button>
        ) : null}
      </Section>
      <BarChart
        h={CHART_HEIGHT}
        data={data}
        dataKey="destination"
        // One hue, light to dark: p50/p95/p99 is an ordered magnitude, not three
        // identities, and the status colours it used to borrow said a slow tail
        // was an error before anyone had decided that it was.
        series={[
          { name: 'p50', color: 'var(--as-chart-seq-1)' },
          { name: 'p95', color: 'var(--as-chart-seq-2)' },
          { name: 'p99', color: 'var(--as-chart-seq-3)' },
        ]}
        valueFormatter={(v) => `${v}ms`}
        withLegend
      />
    </Stack>
  );
}
