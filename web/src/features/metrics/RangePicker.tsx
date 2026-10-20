import { SegmentedControl } from '@mantine/core';
import { useNavigate, useSearch } from '@tanstack/react-router';

import { METRIC_RANGES, type MetricRange } from './ranges.ts';

const LABEL: Record<MetricRange, string> = {
  '15m': '15m',
  '1h': '1h',
  '6h': '6h',
  '24h': '24h',
  '7d': '7d',
};

/** Writes the chosen window to the URL (non-negotiable #9) — shareable, bookmarkable. */
export function RangePicker() {
  const search = useSearch({ strict: false }) as { range?: MetricRange };
  const navigate = useNavigate();
  const current = search.range ?? '1h';

  return (
    <SegmentedControl
      size="xs"
      value={current}
      // Merged into the existing search rather than replacing it: choosing a range
      // must not silently drop a subscription scope, and picking a relative range is how
      // an operator leaves an absolute one.
      onChange={(value) =>
        navigate({
          to: '.',
          search: (prev: Record<string, unknown>) => ({
            ...prev,
            range: value as MetricRange,
            from: undefined,
            to: undefined,
          }),
        })
      }
      data={METRIC_RANGES.map((r) => ({ label: LABEL[r], value: r }))}
    />
  );
}
