# ADR-0033: Metric read model — server-side `date_bin` bucketing, gauge vs. counter

- **Status**: accepted. The bucket-floor clause (the floor is the 15-second scrape) is superseded by [ADR-0220](0220-metric-samples-are-stored-at-a-heartbeat.md).
- **Date**: 2026-09-05
- **Deciders**: Mahdi Amirabdollahi

## Context

`metric_sample` (ADR-0006) has been written to since Phase 2 (`MetricSampleWriter`
appends 4 samples per queue on every tier-B/C scrape tick) but had no reader:
no JPA entity, no repository, no query API. Phase 6 needs to turn that raw
append-only table into charts — cluster-wide depth, throughput, and consumer
count, plus the same per queue — without a query blowing up on a busy cluster's
history, and without a bad chart lying about a broker restart.

Two of the four sampled metrics are not the same kind of number:
`messageCount`/`consumerCount` are point-in-time gauges; `messagesAdded`/
`messagesAcked` are broker-lifetime monotonic counters. Charting a counter the
same way as a gauge (average the raw values) produces a number with no
meaning — the average of a strictly increasing series over a window says
nothing about the rate of change within it.

## Decision

**Bucket in Postgres with `date_bin`, not in the JVM or the browser.** A gauge
bucket is `avg(value)` with `max(value)` reported alongside as a peak. A
counter bucket is a rate: `GREATEST(max(value) - min(value), 0) / bucket_seconds`,
computed **per subject first, then summed** — collapsing several queues'
independent counters into one cluster-wide `max`/`min` pair would produce a
number that means nothing (one queue rising while another's higher absolute
value dominates the aggregate). `GREATEST(..., 0)` is the broker-restart
guard: a counter reset must read as zero throughput for that bucket, never a
large negative spike.

**The requested bucket width is server-clamped, never trusted as-is.** A
request is widened so no query can return more than ~500 points regardless of
range, and the bucket floor is the tier-B scrape interval (15s) — asking for
finer buckets than the cadence Studio actually samples at would invent
precision that was never collected. The response always echoes the bucket
width it actually used and marks itself when it differs from the request; the
UI renders the echoed value, never the requested one.

**A metric's gauge-vs-counter kind is a fixed, compile-time lookup by name**
(`MetricQueryService`'s `GAUGE_METRICS`/`RATE_METRICS` sets), not a stored
column. `MetricSampleWriter` never writes the same metric name as both kinds,
so a per-row flag would be redundant state to keep in sync for no benefit.

**No canvas charting library.** The real fix for a chart with tens of
thousands of points is not a faster renderer (uPlot, visx, ECharts) — it is
never sending tens of thousands of points. Bucketing in Postgres caps every
response at a size `@mantine/charts` (Recharts under the hood) already renders
comfortably, and keeps the existing theme-token inheritance and accessibility
`@mantine/charts` provides for free. Introducing a second charting stack would
duplicate the theming work ADR-0005 already settled and buy nothing the
bucketing doesn't already solve.

## Consequences

- A dashboard query is bounded regardless of how long a cluster has been
  running or how busy its queues are — the point cap and the retention window
  (ADR-0006) together set the worst case.
- Every chart consuming this API must render a sampling gap as a visible gap
  (`connectNulls={false}`), not an interpolated line — a cold queue is only
  swept on the 5-minute tier and a smoothed-over gap would misrepresent that.
- Request-reply latency has no persisted history (ADR-0032) and is not served
  by this endpoint; its chart is captioned as a live window, not history.
- Adding a fifth sampled metric later means adding one name to the correct
  `GAUGE_METRICS`/`RATE_METRICS` set — get the classification wrong and the
  chart silently means something different than its label says, so this is a
  one-line change that still deserves a second look at review time.

## Alternatives considered

- **Fetch raw rows, downsample in Java or the browser** — rejected: pushes
  the same aggregation work onto every request instead of once, in the
  database, over an index (`ix_metric_sample_lookup`, the `ts` BRIN) built for
  exactly this access pattern.
- **A `kind` column on `metric_sample`** — rejected: the kind is a property of
  the metric *name*, which `MetricSampleWriter` already fixes at the call
  site; a per-row column would just be a second place that classification
  could drift out of sync with the writer.
- **uPlot / visx / ECharts for scale** — rejected: solves a rendering problem
  this design doesn't have once the query is bucketed, at the cost of a new
  dependency, a second theming surface, and rebuilding the accessibility work
  `@mantine/charts` already does.
