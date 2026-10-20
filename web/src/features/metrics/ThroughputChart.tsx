import { CompositeChart } from '@mantine/charts';

import type { MetricSeries } from './api.ts';
import { useDisplayZone } from '../../kernel/time/timezone.ts';
import { CHART_HEIGHT } from '../../kernel/metrics/ChartPanel.tsx';
import {
  formatRate,
  gridProps,
  labelFormatter,
  mergeByTimestamp,
  timeAxisProps,
  yAxisProps,
} from '../../kernel/metrics/axis.ts';
import type { MetricRange } from './ranges.ts';

/**
 * `openbroker.enqueued` vs `openbroker.acknowledged`, both already rates (msg/s) from the API —
 * divergence between the two lines *is* the backlog signal, which is why they
 * share one axis and are never split into two panels.
 *
 * This is also the one chart where the two series are separate identities, so it
 * is the one that spends a colour.
 */
export function ThroughputChart({
  added,
  acked,
  range,
  from,
  to,
  syncId,
}: Readonly<{
  added: MetricSeries | undefined;
  acked: MetricSeries | undefined;
  range: MetricRange;
  from: number;
  to: number;
  syncId: string;
}>) {
  // Axis ticks and tooltips are formatted in the display zone (`app/timezone.ts`).
  useDisplayZone();
  const data = mergeByTimestamp([
    { name: 'added', series: added },
    { name: 'acked', series: acked },
  ]);

  return (
    <CompositeChart
      h={CHART_HEIGHT}
      data={data}
      dataKey="ts"
      withLegend
      connectNulls={false}
      withDots={false}
      composedChartProps={{ syncId }}
      valueFormatter={(v) => `${formatRate(v)} msg/s`}
      xAxisProps={timeAxisProps(range, from, to)}
      yAxisProps={yAxisProps()}
      gridProps={gridProps()}
      tooltipProps={{ labelFormatter: (label) => labelFormatter(range)(Number(label)) }}
      series={[
        { name: 'added', label: 'Added', color: 'var(--as-chart-1)', type: 'line' },
        { name: 'acked', label: 'Acked', color: 'var(--as-chart-2)', type: 'line' },
      ]}
      gridAxis="y"
    />
  );
}
