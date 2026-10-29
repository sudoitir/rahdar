## Context

See proposal.md for why this change exists. `19e4-kafka` merged the Kafka screens and left the scale run, the screenshot sweep, and the design review on this branch, plus six findings: S2, S3, B1, A10, E11, and the UI still rendering an unknown depth as 0.

`MetricSampleWriter` inserts up to six `metric_sample` rows per subscription on every tier-B tick (15s). At about 4,000 subscriptions that is the ~45 GB/day of finding S3. Counters change on every tick under traffic, so "write only when the value changes" does not shrink that case. `broker_event` is bounded in time (72 hours) and not in rows; commit `56a57bb9` on `claude/19e4b-s2-start` trims a store that passes its row quota and is cherry-picked here. System scrapes already run side by side (`df042283`). An unreadable resource is already not-found (`ClusterAccessGuard`); Kafka routes lack the per-route test. Chart buckets are clamped in `MetricQueryService` to a 15s floor (`MIN_STEP`), which is the clause of ADR-0033 this change supersedes.

## Goals / Non-Goals

**Goals:**

- Metric history grows with subscriptions and a 60s heartbeat, not with message traffic or the 15s scrape.
- Broker events cannot grow past a row quota. The broker-events store starts at twenty million rows.
- The scale scripts' budgets hold on a seeded Kafka cluster, and the figures are written into the sizing guide and the Kafka guide.
- One unreachable cluster does not stall the others, and a tier-B tick reads subscriptions once.
- An unknown depth is unavailable, never 0. An existing Kafka resource the caller may not read is not found.
- The known live-run defects (purge `NaN`, purge wording, bulk purge, browse position, abort crash) are fixed before the sweep. The sweep and the design review then close what they find.

**Non-Goals:**

- A new Kafka feature, a configurable heartbeat, an OpenBroker change, or a second in-memory rate cache for alerts.
- Rewriting ADR-0033 beyond its bucket-floor clause. ADR-0216, ADR-0218, and ADR-0219 stay as they are.

## Decisions

### The heartbeat is both the floor and the ceiling

`MetricSampleWriter` remembers the last persisted value and time in memory, per cluster, node, subject, and metric. It persists the latest value when 60 seconds have elapsed since the last stored point, whether or not the value changed, and it does not persist more often than that. Sixty seconds is a constant on the writer, not a setting. A restart writes one burst (nothing is remembered), then settles. The replica that already scrapes is the one that writes.

That caps a series at one row per minute: at most `subscriptions × metrics × 60` rows per hour when every counter moves, and the same when nothing moves. Plugin samples use the same gate.

Rejected: write on every change and heartbeat only when the value is unchanged. Counters change every 15s tick, so the 45 GB/day case stays. Rejected: an in-memory rate cache so alerts keep a 15s window. The other replica would not see it, and `metric_sample` is already the shared memory.

### Charts and alerts follow the heartbeat

`MetricQueryService.MIN_STEP` becomes 60 seconds, the same constant the writer uses (one shared duration, not two copies that can drift). A request for a finer bucket is widened and the response says so, as ADR-0033 already requires. `RateCondition` and `SlowConsumerCondition` read `metric_sample` over `max(2 × tier B, 2 × heartbeat)`, so a flat series still has two points and a zero acknowledgement rate stays zero rather than absent. A quiet series is therefore judged on about two minutes of samples. Live grids keep reading the 15s scrape, not the sample table.

### Broker events gain a row cap by taking the parked commit

Cherry-pick `56a57bb9`. Housekeeping trims the oldest rows of a store that has passed its row quota, in bounded batches, audited as one `TRIM_STORE`. The broker-events quota default is `20_000`, which the Data page already shows in thousands of rows (twenty million). A quota of 0 still means no cap. Today's partition is trimmed by deleting its oldest rows; it is not dropped while it is being written. An older day's partition is dropped first.

### Not-found stays the authorization rule

A Kafka topic or subscription the caller may not read answers not-found, including when the resource exists. A caller who can read it but lacks the mutation permission still gets 403. The live note that wanted 403 for a missing `destination:read` contradicted the authorization spec; the spec wins, and the tests lock it. Cluster-scoped lists stay on the existing cluster not-found behaviour.

### Unknown depth uses the existing word

Shared backlog cells already say a lag is unavailable. The SQL subscription grid still coerces null with `?? 0`. It uses the same "unavailable" wording. The task's "Unknown" is the accuracy, not a new label.

### Known flow defects are fixed before the sweep

The 19e4 live run already failed four flows: the purge dialog shows `NaN` and does not say that no record is deleted; a bulk purge left one group's lag unchanged; browse showed the group position as undefined; aborting a transaction crashed the page. Those are fixed, with tests, before a sweep is spent on them. Hanging-transaction copy describes an open-past-timeout transaction only if the review still finds it misleading. Destructive fixes keep the preview, the reach in words, the hold, the approval gate, and the audit.

### The scale run measures the fixed writer

The causes of S2 and S3 are already known, so the seed runs after those fixes rather than once to reproduce 45 GB/day and again after. One seed (`scripts/perf/kafka-load-seed.sh`) and one measure (`scripts/perf/kafka-measure.sh`) on an isolated stack. An hour of traffic records storage growth and the SSE size of one scrape tick. Re-measure only a budget that misses. SSE fails the run if one tick grows with the number of subscriptions; the stream is a signal and the client refetches. Figures go in `docs/sizing.md` (machine, version, date, profile) and the operator ceilings in `site/src/guide/kafka.md`.

### Sweep, then one review

The sweep covers Kafka, the shared screens on Kafka, and registration, at 1440 and 1920, light and dark, plus 1280 and 200% zoom. States are default, loading, error, empty, filtered-empty, forbidden, and one unreachable-broker scene. Findings are logged under this change's `qa/`, fixed at a token, then a shared part, then the page. An independent review then reads the captures and the running app for registration, offset reset, truncate, ACL change, transaction abort, hanging transactions, browse, and bulk purge. One fix round, then recapture. A finding that is a new Kafka feature is recorded and not built here.

## Risks / Trade-offs

- [Quiet-series alerts wait about two minutes] → Live screens stay on the 15s scrape. The window is `max(2 × tier B, 2 × heartbeat)`, so a busy series is not judged later than a quiet one; both use the stored points.
- [A restart forgets the heartbeat gate and writes one burst] → The burst is one row per live series, then the gate holds. Acceptable next to a day of 15s samples.
- [The seed (about 2 million records, several JVMs) can fill Docker or `/tmp`] → Lower `PARALLEL`. Stop the stack when the measure is done. Check `df` before the sweep.
- [Two replicas both scraping would each write] → Unchanged from today: the replica that scrapes is the one that writes. No new leader protocol.
- [Cherry-pick of `56a57bb9` may not apply cleanly] → Resolve it in the lifecycle types only; do not redesign the trim.

## Migration Plan

No schema change. Deploying the writer changes what is inserted from then on; rows already stored stay until retention. The broker-events quota default applies where the setting is still the packaged default; an operator who set a quota keeps it. Rollback is the previous build: inserts return to every tick, and trimming stops. Nothing in the database has to be rewritten.

## Open Questions

None. The heartbeat length, the not-found rule, and the "unavailable" wording are decided above.
