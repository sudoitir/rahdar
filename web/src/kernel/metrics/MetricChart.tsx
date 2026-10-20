import { LineChart } from '@mantine/charts';
import type { MetricRange } from '../time/ranges.ts';
import { useDisplayZone } from '../time/timezone.ts';
import { CHART_HEIGHT, ChartPanel } from './ChartPanel.tsx';
import {
  formatCount,
  formatRate,
  gridProps,
  labelFormatter,
  mergeByTimestamp,
  timeAxisProps,
  yAxisProps,
} from './axis.ts';
import { usePluginSeries } from './pluginSeries.ts';

const PERCENT = new Intl.NumberFormat(undefined, { style: 'percent', maximumFractionDigits: 1 });

const UNITS: Record<string, { label: string; format: (v: number) => string }> = {
  count: { label: 'count', format: formatCount },
  per_second: { label: 'per second', format: formatRate },
  ms: { label: 'ms', format: formatRate },
  ratio: { label: 'share', format: (v) => PERCENT.format(v) },
};

/**
 * Rahdar's time-proportional chart of one plugin metric for one subject (ADR-0055, ADR-0113):
 * a titled panel that tells loading, failure and an empty window apart, over a live range.
 */
export function MetricChart({
  clusterId,
  metric,
  subject,
  title,
  range = '1h',
  emptyLabel = 'No samples yet. Metrics are sampled on every subscription scrape, about every 15 seconds.',
}: Readonly<{
  clusterId: string;
  metric: string;
  subject: string;
  title: string;
  range?: MetricRange;
  emptyLabel?: string;
}>) {
  useDisplayZone();
  const result = usePluginSeries(clusterId, metric, subject, range);
  const series = result.data?.series[0];
  const unit = UNITS[series?.unit ?? 'count'] ?? UNITS.count;
  const data = mergeByTimestamp([{ name: 'value', series }]);
  const error = result.isError ? result.error : null;
  const isPending = result.isPending && !result.data;

  return (
    <ChartPanel
      title={title}
      unit={unit.label}
      isPending={isPending}
      error={error}
      isEmpty={!error && !isPending && data.length === 0}
      emptyLabel={emptyLabel}
    >
      <LineChart
        h={CHART_HEIGHT}
        data={data}
        dataKey="ts"
        connectNulls={false}
        withDots={false}
        valueFormatter={unit.format}
        xAxisProps={timeAxisProps(range, Date.parse(result.window.from), Date.parse(result.window.to))}
        yAxisProps={yAxisProps()}
        gridProps={gridProps()}
        tooltipProps={{ labelFormatter: (label) => labelFormatter(range)(Number(label)) }}
        series={[{ name: 'value', label: title, color: 'var(--as-chart-1)' }]}
        gridAxis="y"
      />
    </ChartPanel>
  );
}
