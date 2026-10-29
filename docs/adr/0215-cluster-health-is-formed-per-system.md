# ADR-0215: Cluster health is formed per system, on one scale

- **Status**: accepted
- **Date**: 2026-10-28
- **Deciders**: Mahdi Amirabdollahi

## Context

`HaStateEvaluator` forms every cluster's verdict from Artemis HA pairs: live nodes, split brain, replication
behind. A Kafka cluster has no pairs; its health is its brokers serving, its partitions led and in sync, and
its controller quorum's majority, and a ZooKeeper-mode cluster's quorum cannot be read at all. The estate
must still compare clusters of both on one scale.

## Decision

A `ClusterHealthEvaluator` system extension (ADR-0208) forms the verdict from the driver's node conditions,
on the shared scale OK, DEGRADED and CRITICAL, with every cause in `notes`. Artemis wraps `HaStateEvaluator`
unchanged. Kafka is CRITICAL on offline partitions, a lost quorum majority or no broker reachable, and
DEGRADED on a fenced or unreachable broker, under-replicated partitions or a lagging voter. What cannot be
read is stated in a note and never counted as healthy.

## Consequences

- A new system brings its own evaluator; the estate needs no change.
- A ZooKeeper-mode Kafka cluster can be OK with a note that its quorum is not visible.

## Alternatives considered

- **One generic evaluator over conditions**: loses Artemis's split-brain rule, which is critical and
  system-specific.
