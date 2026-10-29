# ADR-0220: Metric samples are stored at a heartbeat

- **Status**: accepted
- **Date**: 2026-10-29
- **Deciders**: Mahdi Amirabdollahi

## Context

[ADR-0006](0006-metrics-in-postgres.md) keeps metric history in Postgres, and [ADR-0033](0033-metric-read-model.md) reads it in buckets no finer than the tier-B scrape (15 seconds), because a finer bucket would invent precision that was never collected. `MetricSampleWriter` inserts up to six rows per subscription on every tier-B tick. At about 4,000 Kafka subscriptions that is on the order of 45 GB a day. The counters change on every tick while traffic is moving, so skipping an unchanged value does not shrink that case. Rate and slow-consumer alerts read the same table over a window of two tier-B intervals and treat a series with fewer than two points as absent, not as a zero rate.

## Decision

We will store a metric sample at a heartbeat of 60 seconds, and never faster. The writer remembers the last persisted value and time in memory, per cluster, node, subject, and metric, and persists the latest value when a heartbeat has elapsed, whether or not the value changed. The heartbeat is a constant shared with the chart query, not a setting. A restart writes one sample per live series, then the gate holds.

The chart bucket floor moves from the 15-second scrape to this heartbeat. A request for a finer bucket is widened, and the response says so, as ADR-0033 already requires for its own floor. Rate and slow-consumer alerts read the table over the longer of two tier-B intervals and two heartbeats, so a flat series still has two points and a zero rate stays zero.

This supersedes only the bucket-floor clause of ADR-0033 (the floor is the 15-second scrape). The rest of that ADR stands: buckets are computed in Postgres, a counter is a rate, and a counter reset reads as zero.

## Consequences

- An hour of samples grows with the number of subscriptions, the number of metrics, and the heartbeat, not with the number of messages and not with the 15-second scrape.
- A chart cannot show a bucket finer than a minute. The response echoes the width it used.
- A quiet series is judged for a rate alert on about two minutes of samples. The live grids still update on the scrape.
- The in-memory gate is lost on restart, so the first tick after a restart writes a burst of one row per series.

## Alternatives considered

- **Write on every change, and heartbeat only while the value is unchanged.** Leaves the traffic case at one row per scrape. That is the case that filled the disk.
- **Keep the 15-second samples and shorten retention.** Bounds the table in time, which it already is, and does not change the per-day growth.
- **Judge rates from an in-memory cache so alerts stay at 15 seconds.** The replica that does not scrape would not see the cache. The table is the memory the replicas share.
