# Kafka partition replication

Archived 2026-11-27. The product part of this change is merged; the requirements it added now live in
`system-kafka` and `mcp-server`. The broker-driver part of the same effort is a separate change in the
broker SDK, which this repository consumes as a released dependency.

## Why

During an incident the operator needs the partition, not the broker count: which leader, which replicas are in sync, which replica is offline, whether the preferred leader is leading, and how many bytes sit on each disk. The driver already reads most of that and the console does not show it. Moving a partition's replicas, or handing leadership back to the preferred replica, is what Lenses and Redpanda Console are used for, and Kafka's Admin API has supported both since 2.4, on ZooKeeper and on KRaft. Rahdar correctly refuses to move a single record. It should offer the operation the log actually has, previewed and approval-gated.

## What Changes

- A topic's partitions are a table: leader, replicas in order, in-sync replicas, offline replicas, eligible leaders where the cluster keeps them, log start, log end, size, and whether the preferred leader is leading.
- A cluster replication screen lists partitions that are under-replicated, offline, or not on the preferred leader. A broker that does not answer is named; the others still show.
- Per-replica size and replica offset lag come from `describeLogDirs` for every replica. Size is bytes on that broker, not a record count.
- Where the broker supports it, local log start and the tiered offset are shown. Where it does not, or the topic is not tiered, the columns say so.
- Preferred-leader election for a chosen set, previewed. The broker is asked for `ElectionType.PREFERRED` only.
- Reassignment of a replica set the operator confirms, including a rack-aware suggestion that never applies by itself. The preview shows bytes to move as an estimate, the throttle, and refuses a replication factor below `min.insync.replicas`. In-progress reassignments can be cancelled through their own preview. Throttle entries this operation added are removed when it finishes. A throttle that was already set is left in place.

## Capabilities

### New Capabilities

- None.

### Modified Capabilities

- `broker-sdk-kafka`: Per-replica log directory fields, tiered offsets as a capability, and previewed reassignment and preferred-leader election. Applied in OpenBroker.
- `system-kafka`: The partition table, the replication screen, and the two operations. Applied in Rahdar.
- `mcp-server`: Tools for the same reads and operations, preview first. Applied in Rahdar.

## Impact

- OpenBroker: `openbroker-kafka` partition reads and two new operations, then a maintainer release to Central.
- Rahdar: Kafka partition and replication screens, the approval gate, audit, and MCP. `openbroker.version` moves to the release that contains them.
- Permissions already in the catalogue: `destination:read` for the tables, `destination:update` for election and reassignment. No new permission names.

## Out of scope

- Unclean leader election. It can drop acknowledged records. It is not a verb and not a button.
- A balancer that mutates on its own (Cruise Control, Confluent self-balancing). The suggestion is a proposal; the preview is the decision.
- Changing a topic's partition count (`19e6-kafka-topic-lifecycle`).
- The Artemis setup-review acceptance workflow. Kafka findings of that kind are a later change. This change states under-replication, offline replicas and a preferred-leader mismatch on the replication screen.
- Quotas, except the replication throttle Kafka already uses for a move.
- Importing a Cruise Control plan.

## Depends on

- `19e4-kafka` (brokers, partitions and the driver). Merged.
- `19e6-kafka-topic-lifecycle` for run order only, so two OpenBroker releases are not in flight together. There is no code dependency.

## Execution

**Subagent-driven, OpenBroker then Rahdar.** One implementer for the driver, one for the Rahdar screens after the release. A reviewer checks reassignment, throttle cleanup and the approval gate before the Rahdar PR.
