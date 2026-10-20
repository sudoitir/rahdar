import { CompositeChart } from '@mantine/charts';

import type { MetricSeries } from './api.ts';
import { useDisplayZone } from '../../kernel/time/timezone.ts';
import { CHART_HEIGHT } from '../../kernel/metrics/ChartPanel.tsx';
import {
  formatCount,
  gridProps,
  labelFormatter,
  mergeByTimestamp,
  timeAxisProps,
  yAxisProps,
} from '../../kernel/metrics/axis.ts';
import type { MetricRange } from './ranges.ts';

/**
 * Average subscription depth for each bucket, with the bucket's peak as an envelope
 * above it. With `total`, the cluster's depth at each bucket's end instead: the sum
 * of every subscription's latest sample, which has no bucket peak (ADR-0127).
 *
 * The two marks share one hue on purpose. Peak is not a second identity — it is
 * the same measurement's upper bound — so this is an envelope, not a categorical
 * pair, and giving it its own colour would both spend a colour on nothing and
 * invite the reader to compare two things that are one thing. Identity is carried
 * by mark shape (a filled band under a dashed line, against a solid line) and by
 * the legend labels, which is why the two neutrals sitting below the categorical
 * separation floor is not the defect it would be between real series.
 */
export function DepthChart({
  series,
  range,
  from,
  to,
  syncId,
  total = false,
}: Readonly<{
  series: MetricSeries | undefined;
  range: MetricRange;
  from: number;
  to: number;
  syncId: string;
  total?: boolean;
}>) {
  // Axis ticks and tooltips are formatted in the display zone (`app/timezone.ts`).
  useDisplayZone();
  const data = mergeByTimestamp([
    { name: 'depth', series },
    { name: 'peak', series, field: 'peak' },
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
      valueFormatter={formatCount}
      xAxisProps={timeAxisProps(range, from, to)}
      yAxisProps={yAxisProps()}
      gridProps={gridProps()}
      tooltipProps={{ labelFormatter: (label) => labelFormatter(range)(Number(label)) }}
      series={
        total
          ? [{ name: 'depth', label: 'Depth (total)', color: 'var(--as-chart-1)', type: 'line' }]
          : [
              {
                name: 'peak',
                label: 'Bucket peak',
                color: 'var(--as-chart-1)',
                type: 'area',
                strokeDasharray: '4 4',
              },
              { name: 'depth', label: 'Depth (avg)', color: 'var(--as-chart-1)', type: 'line' },
            ]
      }
      gridAxis="y"
    />
  );
}
