# Sizing Rahdar

What one Rahdar deployment carries, measured. Every figure names the load profile, the machine, the Rahdar
version and the date it was measured on. The decision behind the test is
[ADR-0198](adr/0198-a-gatling-load-test-publishes-the-sizing-figures.md); the database is described in
[external-database.md](external-database.md).

## Reference machine and method

- **Machine:** 4 vCPU (AMD EPYC Rome) and 7.6 GB of memory, one disk, Linux, Docker. Postgres 18, both Rahdar
  replicas, HAProxy and every Artemis broker run on it together. A real deployment puts these on separate
  hosts, so the figures are conservative for Rahdar and Postgres and optimistic for the network.
- **Rahdar:** the shipped HA stack (`deploy/compose/compose.ha.yaml`): two replicas, each capped at 2 GB with a
  1 GiB heap, behind HAProxy on one Postgres with the shipped `postgresql.conf`. Version measured:
  `2026.10.47` plus the 58 commits of change 19 (`perf/19-performance-hardening` at `ae41c24a`), 2026-10-14.
- **Brokers:** Artemis 2.57, one container per node, 256 MB of heap (more where a run says so). Rahdar refuses
  to register one broker in two clusters, so the brokers are `clusters x nodes per cluster`.
- **Scrape cadence:** the load stack walks queues every 15 s instead of the default 5 minutes, so that queues
  seeded after start-up are known within a run. Its broker reads are more frequent than a default installation's,
  never less. See [the queue walk](#the-queue-walk).

### Profiles

Profiles live in `load/profiles/`. A figure below always names the profile and the sizes it changed.

| Profile | Clusters x nodes | Queues per node | Messages per node | UI users | Fast / stalled streams | Heavy users (exports / SQL) | Steady load |
| --- | --- | ---: | ---: | ---: | --- | --- | --- |
| `reference` | 3 x 2 | 1,000 | 30,000 | 10 | 20 / 5 | 3 / 3 | 300 s |
| dimension runs | as named | as named | as named | as named | 20 / 5 | 3 / 3 | 120 s, 20 s ramp |

All scenarios run at once, with a producer writing to the brokers throughout. The heavy users share one account
so that the per-user limit is reached: refusals with 429 are counted and are not failures.

### Service levels

A dimension's **ceiling** is the largest size tested at which all of these still hold:

1. Fewer than 1% of requests fail (a refusal with 429 is not a failure) and every sign-in succeeds.
2. Light UI reads (cluster list, topology, health, queue pages and search, queue metrics) have a 95th
   percentile under **1 s**. The goal is 500 ms; the reference run meets it (407 ms).
3. The connection pool has no queue: peak pending connections 2 or fewer.
4. Job lag stays under 5 s, and heap stays under 70% of its 1 GiB limit (700 MB).

Reads that aggregate over the whole cluster (`GET /clusters/{id}`, the address list, the cluster metric total)
are slower and are reported separately; they are below the 500 ms goal and are listed under
[findings](#findings-below-target).

## Results

Measured 2026-10-14 on the reference machine. `p95` columns are the worst 95th percentile of the requests in
the group, in milliseconds. "Pool" is the peak of `hikaricp_connections_pending` over both replicas.

| Run (changed from `reference`) | Light p95 | Aggregate p95 | Sign-in p95 | Failed | Pool | Job lag | Heap peak |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| `reference` (3 x 2, 1,000 queues, 10 UI users, 20 streams) | 407 | 2,322 | 1,144 | 0% | 0 | 1.3 s | 378 MB |
| **Queues per node**, 1 cluster x 2 nodes: 1,000 | 793 | 3,641 | 1,957 | 0.07% | 0 | 0.8 s | 400 MB |
| 3,000 | 690 | 3,131 | 552 | 0% | 0 | 0.4 s | 593 MB |
| 10,000 (broker heap 1 GB) | 19,044 | 25,802 | 8,396 | 0.45% | 0 | 5.8 s | 657 MB |
| **Messages per node:** 300,000 | 514 | 1,955 | 1,542 | 0% | 0 | 0.7 s | 309 MB |
| **Nodes per cluster**, 1 cluster: 3 | 1,235 | 2,772 | 1,908 | 0% | 0 | 1.2 s | 557 MB |
| 4 | 505 | 3,853 | 944 | 0% | 0 | 1.0 s | 547 MB |
| 6 (two runs: 6,205 / 1,097) | 1,097-6,205 | 3,946-16,011 | 1,117-5,143 | 0% | 0 | 0.5-5.4 s | 601-697 MB |
| **Clusters**, 1 node each: 6 | 247 | 1,943 | 751 | 0% | 0 | 0.5 s | 315 MB |
| **Fast stream subscribers:** 100 | 894 | 3,659 | 3,494 | 0% | 0 | 3.1 s | 391 MB |
| 300 in a 20 s ramp | 509 | 1,574 | 12,530 | 20% (sign-ins) | 46 | 8.5 s | 328 MB |
| **Concurrent UI users:** 30 | 1,039 | 4,811 | 3,111 | 0% | 0 | 2.0 s | 443 MB |
| 60, ramp over 60 s | 599 | 7,814 | 890 | 0.13% | 0 | 1.9 s | 460 MB |
| 100 in a 20 s ramp | 4,187 | 11,507 | 13,598 | 33% (sign-ins) | 47 | 26.7 s | 473 MB |

### Ceilings and what degrades first

| Dimension | Tested ceiling (service levels hold) | Beyond it, what degrades first |
| --- | --- | --- |
| **Clusters** | 6 clusters of 1 node. More was not tried: each needs its own broker and the machine's memory is the limit, not Rahdar's. | Not reached. |
| **Nodes per cluster** | 4 | Latency of every cluster-wide read. At 6 nodes the same load gave anything from 1.1 s to 6.2 s on light reads: the roll-up, the address list and the metric total all grow with nodes x queues, and the result is unstable on four vCPUs. |
| **Queues per node** | 3,000 (6,000 rows rolled up for a two-node cluster) | The queue and address lists, then the metric total (a skip scan of `uq_metric_sample` makes one index search per queue and node, 20,000 at 10,000 queues). At 10,000 per node light reads take 19 s, job lag reaches 5.8 s and heap 657 MB. |
| **Messages per node** | 300,000 per node, nothing changed. | Not reached. Rahdar reads counts, not messages, until a user opens a queue or runs a query. |
| **Concurrent fast event-stream subscribers** | 100 | Sign-in: 300 browsers opening within 20 s fail 20% of their sign-ins (pool pending 46, job lag 8.5 s). The streams that connected were not affected. |
| **Concurrent UI users** | 60 signed in over a minute; 30 over 20 s. | Sign-in first (see below), then every read: 100 users arriving within 20 s fail a third of their requests and job lag reaches 27 s. |
| **Sign-ins in a burst** | About 50 in 20 s (p95 3.1 s). | Password hashing and the session writes queue on four vCPUs; at 126 sign-ins in 20 s the p95 is 13.6 s and 29% fail, then their sessions' requests answer 401. Spread arrivals, or add vCPUs. |

A final run of `just load` with these targets in place (`reference`, 300 s) passed all of them: light p95 401 ms,
aggregate 1,715 ms, sign-in 1,066 ms, no failures, pool 0, job lag 1.3 s, heap 392 MB, export 356 ms, SQL 4,436 ms.

Heavy endpoints held: with 3 exporters and 3 SQL users sharing one account, the extra requests were refused
with 429 (6 to 29 exports and 3 to 84 queries per run, depending on how many the cap admitted), every other
one succeeded, and ordinary reads were unaffected. An audit export of the reference run's trail takes 443 ms at
the 95th percentile, a SQL query across 2,000 queues 5.4 s.

### Stalled event-stream subscribers

Thirty subscribers that open the stream and stop reading, with HAProxy and Rahdar's TCP send buffers cut to 4 KB
and queues changing throughout, were held for 150 to 420 seconds in four runs. Rahdar's memory stayed flat (heap
157 to 159 MB across a 7-minute run; peak 231 to 299 MB) and no thread or subscriber was left behind when the
test ended.

The server did not drop any of them, because it never had to: at this change rate a stream carries about 9 bytes
a second (41 KB reached 30 sockets in 150 s), so even a 4 KB buffer does not fill. The drop path of
[ADR-0197](adr/0197-slow-stream-subscribers-are-coalesced-then-dropped.md) (three overflows, or a write blocked
for 30 s) is exercised by Rahdar's own tests, not by this load. Frames that were coalesced in place are counted
in `rahdar.sse.dropped{reason="coalesced"}`, never `overflow` or `blocked`, in any run.

### The queue walk

The runs measured here read a node's queue list one page of 200 per tier-C tick, so a node with 1,000 queues took
25 minutes to be fully known at the default 5-minute interval and 10,000 over four hours. Change 19 fixes that
after these runs: each tier-C tick now reads every page of every node, one POST of 200 queues at a time through
the node's rate limiter (25 calls/s), so a node is fully known one tick after it is registered. Queues that are
busy are still re-read every 15 s by tier B. The interval is `rahdar.scrape.tier-c-interval`.

### Database

- Event flush: one writer at a time holds the `broker_event.write` lock; 200 batches of 500 rows, one commit
  each, wrote **27,800 to 47,300 rows/s** on the reference disk. Rahdar's own ceiling is the buffer
  (10,000 events) and a flush every second, which this clears 2.7 to 4.7 times over each second.
- Metric history without a node filter: the planner uses `uq_metric_sample` with one index search per
  `(queue, node)` pair (a skip scan): one queue over an hour 0.13 ms; every queue of a cluster for one metric
  35 to 61 ms; the cluster total of a gauge over an hour (22,744 samples, 509 searches) 386 ms at 3,000 queues
  and 859 ms at 10,000. No sequential scan of an hour's data was needed except the rate query's, which reads
  the day's partition once.

## Findings below target

> `GET /clusters/{id}` (finding 1 and the aggregate column) was measured before the capability cache landed on the
> session branch, which removes a live broker probe from every read. Those figures are stale and need a rerun.
> The runs also shared the machine with other containers and, for the first reference run, with CI test runs.

Measured on the reference profile and reported, not tuned.

1. **`GET /clusters/{id}`** has a 95th percentile of 2.3 s with 10 UI users (p50 over 1 s), against a goal of
   500 ms. It is the slowest read in every run, and grows with queues and nodes.
2. **The address list** (1.2 s) and **the cluster metric total** (0.7 to 1.0 s) are over the goal too.
3. **Sign-in under a burst:** 126 sign-ins in 20 s give a p95 of 13.6 s and fail 29%; the pool queues (47
   pending) and job lag reaches 27 s. A deployment that restarts and has all its browsers sign in again hits this.
4. **The SQL console** takes 4 to 5 s at the 95th percentile for a `SELECT *` over 2,000 queues with
   `LIMIT 100`, and 38 s at 20,000 queues.
5. **Queue discovery** of a large node took hours at the default tier-C interval; fixed since (see [the queue walk](#the-queue-walk)).
6. **Large clusters are unstable on 4 vCPUs:** the 6-node cluster gave 1.1 s and 6.2 s on light reads in two
   identical runs. Other containers on the host were running during the series (variance is the point).

## Hardware requirements derived from this

The README's minimum and recommended sizes come from these peaks. They were derived, not measured on a smaller
machine: a minimum is the observed peak with little headroom, a recommendation about twice that.

See the README. In short, from the measurements: one Rahdar replica used at most 30% of the machine's CPU and
less than 700 MB of heap (1.3 GiB resident in its 2 GiB container) at every size that met the service levels, and
Postgres stayed under 560 MB resident (512 MB of it `shared_buffers`) and peaked at one core.
The first resources to run out are CPU (sign-in bursts) and then the cluster-wide reads' latency, not memory
or database size.

## Kafka at scale

Measured 2026-10-30, on a different machine from the Artemis runs above. The Mac has 8 CPU and 16 GB of memory
(macOS 26.6.2). Docker Desktop gives the containers 6 CPU and 12 GB. The stack is one Rahdar replica, one
Postgres, one Kafka broker and one Artemis pair. Rahdar is `2026.10.65` at `506a77d0` (eight commits after that tag).

**Profile.** Kafka cluster `events`: 208 topics, 3,079 partitions, 311 consumer groups in the list, and 350
group-on-topic series. Traffic was 3,600,000 records of 100 bytes at 1,000 records a second on one topic
(`perf-t-0001`), from 2026-10-29 22:26 UTC to 23:26 UTC. The quiet hour is 2026-10-30 02:00–03:00 UTC, after
that producer had finished. The Artemis pair is the same stack's other cluster and is not part of the Kafka figures.

### Lists and detail

Thirty calls after a short warm-up, ten for the calls that read the broker live. A 95th percentile under 500 ms,
and a list asked for far more than its maximum returns at most 500 rows and at most 1 MiB.

| Call | p95 | max | bytes |
| --- | ---: | ---: | ---: |
| Estate subscriptions | 37 ms | 55 ms | 91,134 |
| Cluster subscriptions | 36 ms | 41 ms | 108,053 |
| Groups, first page of 100 | 143 ms | 147 ms | 29,542 |
| Groups, page of 500 | 147 ms | 148 ms | 92,031 |
| Groups, a later page | 142 ms | 167 ms | 29,467 |
| A group on 40 topics | 455 ms | 455 ms | 40,473 |
| Offset-reset preview, 50 partitions | 486 ms | 486 ms | 5,476 |
| Topic configuration | 29 ms | 29 ms | 5,236 |
| ACLs | 41 ms | 112 ms | 74,967 |
| Transactions | 32 ms | 78 ms | 1,236 |
| Brokers | 405 ms | 536 ms | 269 |
| Browse one partition, 50 records | 383 ms | 507 ms | 23,593 |
| Backlog breakdown | 139 ms | 139 ms | 1,155 |

Asked for 100,000 rows: the group list returned 311 (the whole list), the ACL list returned 500, and transactions
returned 4. Every body above is under 1 MiB.

### The scrape

Over 120 seconds: 23 tier-A cycles and 8 tier-B cycles. One pass covers every cluster this replica owns.

| | Measured | Budget |
| --- | ---: | ---: |
| Node reads per tier-A cycle | 1.00 | 1 |
| Partition reads per tier-A cycle | 0.17 | 1 |
| Subscription sweeps per tier-B cycle | 1.00 | 1 |
| Longest tier-A pass | 0.231957 s | 5 s |
| Longest tier-B pass | 0.592722 s | 15 s |

Partitions are one batched read at most every 30 seconds (the count of partitions with no leader), so a
5-second tier-A cycle sees that read about once every six cycles, never once per partition. A window taken in
the minute after this replica restarted swept subscriptions 1.14 times per tier-B cycle, because a replica
scrapes a cluster as soon as it takes ownership. The steady window above is one sweep.

### Storage

Six metrics on each of the 350 group-on-topic series: `openbroker.acknowledged`, `backlog`, `consumer.count`,
`enqueued`, `expired` and `in_flight`. One row per metric per series per minute.

| Hour | `metric_sample` rows on the Kafka cluster | Series |
| --- | ---: | ---: |
| Traffic (22:26–23:26 UTC) | 123,510 | 350 |
| Quiet, after the producer stopped (02:00–03:00 UTC) | 123,900 | 350 |

123,900 is 59 samples of each series. A clock hour holds 59 or 60 heartbeats, so nothing was stored faster than
the heartbeat, and the hour of traffic stored fewer rows, not more. The ceiling is 350 × 6 × 60 = 126,000 rows
an hour. The Artemis cluster in the same stack wrote 21,240 rows in the quiet hour (60 queues); those are not
in the Kafka count. `broker_event` gained nothing during the traffic hour. The table holds 114 rows, all of
them on the Artemis cluster and all written before that hour. The quota is 20,000,000 rows.

### One subscriptions tick

Past 100 names the stream sends a signal about the cluster, and the client refetches. The budget is 8,192
bytes. The steady 25-second read's longest frame was 18 bytes. The longest frame observed on this build, in
the window just after the replica took the cluster, was 549 bytes.

## Rerun it

```bash
just load-smoke        # a minute-long check of the test itself
just load              # the reference profile (about 15 minutes); needs Docker, Java 25 and about 6 GB free
LOAD_QUEUES=3000 LOAD_CLUSTERS=1 LOAD_NODES_PER_CLUSTER=2 LOAD_BROKER_HEAP=512 just load
```

`LOAD_JAR=target/rahdar-exec.jar` or `LOAD_IMAGE=…` measures a build you have already made; otherwise the
Dockerfile is built. The report is `load/target/report/summary.md` (and `.json`, `explain.txt`). The same runs
from the Actions tab: **Load test**. Sizes are in `load/profiles/*.env`; any `LOAD_*` variable overrides one.

### How a regression shows

The run compares the reference profile's figures with the targets below and prints
`REGRESSION: <figure> = <value>, target <op> <limit>` for each that is worse, then exits non-zero. Other profiles
measure the test, so their targets are not checked. When a change moves a figure, rerun, update the
measurements above and the targets below, and the README's hardware section in the same change.

<!-- targets:begin -->

| Figure | Op | Target |
| --- | --- | ---: |
| `ui_light_p95_ms` | <= | 1000 |
| `ui_aggregate_p95_ms` | <= | 4000 |
| `ui_read_failed_percent` | <= | 1 |
| `stream_failed_percent` | <= | 1 |
| `login_failed_percent` | <= | 1 |
| `pool_pending_max` | <= | 2 |
| `job_lag_max_s` | <= | 5 |
| `heap_peak_mb` | <= | 700 |
| `sql_p95_ms` | <= | 8000 |
| `export_p95_ms` | <= | 1500 |

<!-- targets:end -->
