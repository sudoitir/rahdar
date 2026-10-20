# scrape-scheduling Specification

## Purpose
Defines how Rahdar periodically reads state from every manageable broker
node without becoming a load problem: a tiered schedule, one batched request per
node per tick, a per-node cap on management calls, and how the polled results
are cached and aged out.

## Requirements

### Requirement: Scraping is tiered by cost and volatility

The system SHALL poll each manageable node on three independent schedules: a
fast tier for HA and topology state, a medium tier for the busiest queues, and a
slow tier that sweeps every queue. Each tier's interval SHALL be configurable.
The fast tier's interval SHALL be short enough that corroborated split-brain is
detectable within roughly two of its cycles. Completion of the fast tier for a
cluster SHALL trigger evaluation of that cluster's state-condition alert rules;
completion of the medium and slow tiers' per-cluster fan-out SHALL trigger
evaluation of that cluster's metric-threshold alert rules. Alert evaluation
SHALL NOT delay or block the scrape tick it is triggered from.

In addition, the system SHALL poll a cluster's client activity — producers and
consumers — on a demand-driven schedule
that runs only while that cluster's flow is observed. Its interval SHALL be
configurable with a lower bound, it SHALL obey the same one-request-per-node and
per-node ceiling rules as the tiers, and it SHALL NOT run at all for a cluster
nobody is observing.

#### Scenario: Fast tier reads HA and topology

- **WHEN** the fast tier runs for a manageable node
- **THEN** Rahdar reads that node's HA attributes and network topology in one
  request and updates the node's live/replication state

#### Scenario: Slow tier eventually covers every queue

- **WHEN** a node has more queues than fit in one page
- **THEN** one slow-tier tick reads every page of that node, one after the other,
  so the whole queue set is refreshed within one slow-tier interval

#### Scenario: A slow-tier walk that fails part way removes nothing

- **WHEN** a slow-tier walk fails or is interrupted before its last page
- **THEN** no cache row is removed for that node, and the next tick walks it again
  from the first page

#### Scenario: Medium tier refreshes active queues sooner

- **WHEN** a queue had consumers or a non-zero depth at its last reading
- **THEN** that queue is re-read on the medium tier without waiting for the full
  slow-tier sweep

#### Scenario: Fast tier completion evaluates state-condition rules

- **WHEN** the fast tier finishes a cluster's tick
- **THEN** that cluster's state-condition alert rules (split-brain, node down,
  replication behind, cluster degraded) are evaluated against the state just
  persisted

#### Scenario: Medium and slow tier completion evaluates metric-threshold rules

- **WHEN** the medium or slow tier finishes persisting a cluster's queue snapshots
  or metric samples for a tick
- **THEN** that cluster's metric-threshold alert rules are evaluated against the
  state just persisted

#### Scenario: Client activity is polled only while observed

- **WHEN** a cluster's flow stops being observed and its observation lease expires
- **THEN** the client-activity schedule issues no further requests to that
  cluster's nodes until it is observed again

### Requirement: One batched request per node per tick

For every scrape tick, the system SHALL issue exactly one Jolokia bulk POST per
node — an array of read and exec entries — never one request per attribute,
operation, or queue. The slow tier is the one exception: it SHALL issue one POST per
page of a node's queue list, each through the node's management-call ceiling.

#### Scenario: Fast tick is a single POST

- **WHEN** the fast tier needs HA attributes plus `listNetworkTopology()` for a node
- **THEN** Rahdar sends one POST whose body is a JSON array of those entries and
  reads each entry's own status from the aligned response array

#### Scenario: A failed entry does not abort the tick

- **WHEN** one entry in a batched response has a non-200 status
- **THEN** Rahdar consumes the successful entries and records the failure without
  discarding the tick or skipping other nodes

### Requirement: Management calls are rate-limited per node

The system SHALL enforce a configurable ceiling on management requests per second to each
individual node. The ceiling SHALL be applied to every HTTP request Rahdar issues to that
node, whatever the origin:
- a scrape tick;
- an operator-initiated operation such as a message browse or mutation;
- cluster registration and rediscovery;
- a capability probe;
- a multi-step command;
- capture installation or removal.

The ceiling SHALL be applied through the broker SDK's call interceptor, which the driver
asks for a permit before every request, so no request reaches a node without one.

A single request that carries many operations SHALL count against the ceiling in proportion
to the operations it carries, so batching can never be used to exceed it. The default
ceiling SHALL be conservative so that Rahdar is never the reason a broker is overloaded.

#### Scenario: Bursts are shaped, not dropped

- **WHEN** Rahdar would exceed a node's per-second ceiling
- **THEN** the excess calls wait until capacity is available rather than failing

#### Scenario: One slow node does not stall the others

- **WHEN** one node is rate-limited or slow to respond
- **THEN** other nodes and other clusters continue to be scraped on schedule

#### Scenario: Operator-initiated calls share the ceiling

- **WHEN** an operator browses or mutates messages on a node while that node is
  being scraped
- **THEN** both the scrape and the operator call are counted against the same
  per-node per-second ceiling

#### Scenario: A multi-request command counts every request

- **WHEN** a command issues several management requests to one node, such as installing a capture tap
- **THEN** each of those requests is counted against that node's ceiling, not only the first

#### Scenario: Registration and capability probes are counted

- **WHEN** a cluster is registered, rediscovered, or has its capabilities probed
- **THEN** every management request that issues to a node is counted against that node's ceiling

#### Scenario: A large batch cannot exceed the ceiling

- **WHEN** an operation carries more operations in one request than the ceiling allows per second
- **THEN** it is spread across as many seconds as the ceiling requires

### Requirement: Network I/O is outside database transactions

The system SHALL perform every broker HTTP call outside any open database
transaction. Persisting a tick's results SHALL happen in a separate short
transaction after the broker responses have been received and parsed.

#### Scenario: A hung broker holds no database transaction

- **WHEN** a node's Jolokia endpoint stops responding until the read timeout
- **THEN** no database transaction is held open for the duration of that call

### Requirement: The scrape owns the split-brain cycle counter

The system SHALL maintain the monotonic refresh-cycle counter and the
one-cycle split-brain corroboration state per cluster, advanced only by the
scrape schedule. Reading the topology or health view SHALL NOT advance either.

#### Scenario: Cycle advances once per fast tick per cluster

- **WHEN** the fast tier runs for a cluster
- **THEN** that cluster's cycle counter increments once for the tick, independent
  of other clusters

#### Scenario: Reading health does not corroborate

- **WHEN** the health view for a cluster is requested repeatedly between fast ticks
- **THEN** the split-brain status does not change until the next fast tick

### Requirement: The latest queue state is cached and stale rows are removed

The system SHALL keep a latest-state-per-queue cache, updated by upsert on every
sweep, and SHALL remove cache rows for queues that a node no longer reports once
a full sweep of that node has completed. The cache is disposable — losing it
SHALL only cost a re-scrape, never correctness.

#### Scenario: A deleted queue leaves the cache

- **WHEN** a queue that was present in the cache is absent from a completed sweep
  of its node
- **THEN** its cache row is removed after that sweep

#### Scenario: A queue count change is an update, not a churn

- **WHEN** a queue's counters change between sweeps
- **THEN** its existing cache row is updated in place, keyed by node and queue name

### Requirement: Metric samples are written and retained for a bounded window

The system SHALL append subscription counter samples to the metric history on the
medium and slow tiers, under the broker SDK's standard metric keys
(`openbroker.backlog`, `openbroker.consumer.count`, `openbroker.in_flight`,
`openbroker.enqueued`, `openbroker.acknowledged`, `openbroker.expired`), and SHALL
delete history older than a configurable retention window. The default retention
SHALL be seven days. History SHALL be retained in daily partitions with UTC bounds and
no default partition, so that trimming expired history is a partition drop rather than
a row-by-row delete. A sample for a subscription, node and time that is already stored
SHALL be ignored, not stored twice, so that a repeated scrape does not duplicate it.

#### Scenario: Old samples are trimmed

- **WHEN** a partition's contents are entirely older than the configured
  retention window
- **THEN** that partition is dropped and its samples are no longer queryable

#### Scenario: A sample is scraped twice

- **WHEN** the same sample is written a second time, for instance after a retry
- **THEN** the second write is ignored and the history holds one row

#### Scenario: A sample has no partition

- **WHEN** a sample's day has no partition
- **THEN** the partition is created and the write retried once, as for any partitioned history

#### Scenario: Samples written before partitioning still age out

- **WHEN** samples sit in a partition that is only partly older than the
  retention window
- **THEN** they remain until the whole partition is older than the window, and
  are then dropped with it

#### Scenario: Samples use the standard keys

- **WHEN** a subscription's message count is sampled
- **THEN** it is stored and served by the metrics API under `openbroker.backlog`

### Requirement: A node failure is isolated and recorded

The system SHALL catch any failure scraping one node, record it against that
node without propagating it, and continue scraping the remaining nodes.

#### Scenario: One unreachable node

- **WHEN** a node is unreachable during a tick
- **THEN** its last error is recorded, its previous cached data is retained, and
  every other node in every cluster is still scraped

### Requirement: Scrape cadence changes apply without a restart

The system SHALL re-read each scrape tier's configured interval before scheduling
that tier's next run, so that changing a tier interval through the settings API
takes effect on the following cycle without restarting Rahdar.

#### Scenario: A shortened interval speeds up the next cycle

- **WHEN** an operator shortens the fast-tier interval through the settings API
- **THEN** the fast tier's next run is scheduled at the new interval with no
  restart

#### Scenario: A lengthened interval slows the next cycle

- **WHEN** an operator lengthens the slow-tier interval
- **THEN** the slow tier's subsequent runs use the new interval
