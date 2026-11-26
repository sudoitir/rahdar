## Context

See the proposal for why. `kafka.partition` already carries leader, replicas, ISR, eligible-leader replicas when that feature is finalized, log start, log end, and the leader's byte size. `describeLogDirs` is already called, and only for the leaders of the page. The console never lists partitions. `alterPartitionReassignments` and `electLeaders` are not in the driver.

Both operations have been in the Admin API since Kafka 2.4 (KIP-455, KIP-460), so the 2.8 floor has them in ZooKeeper mode and in KRaft. There is no version gate. `UnsupportedVersionException` disables the action with the broker's reason. Eligible leader replicas stay on the capability ADR-0031 already declares.

`OffsetSpec.earliestLocal` and `OffsetSpec.latestTiered` need ListOffsets v9 (KIP-1005). Brokers support that from 3.9. Older brokers reject it. When tiered storage is off, the local start equals the earliest offset and the tiered offset is unknown.

## Goals / Non-Goals

**Goals:**

- An operator can see placement, sync and size without a broker count standing in for the partition.
- A replica move and a preferred election are previewed, etagged, held and audited.
- A throttle this operation introduced does not outlive the move, and a throttle someone else set is put back.

**Non-Goals:**

- Unclean election, a balancer, or partition-count changes.
- A setup-review acceptance workflow. The replication screen is the list of partitions that need attention.

## Decisions

**One log-directory read per page.** Extend the existing `describeLogDirs` call from "brokers that lead this page" to "brokers that hold any replica on this page". ReplicaInfo supplies size and offset lag. A broker that fails is a partial result: its id and the error are on the page, and the other replicas still render. Size is bytes. The UI says "on this broker", not "records".

Alternative: a size per topic rolled up on a timer. Rejected as the only view. The incident question is which partition on which disk. A rollup can come later from the same read.

**Tiered offsets are probed, not version-gated.** The driver tries the local and tiered offset specs. `UnsupportedVersionException` or an equivalent refusal marks `kafka.tiered-offsets` unavailable with that reason, remembered for the connection the way other feature flags are. A successful call on a topic that is not tiered reports local start equal to log start and tiered offset unknown, reason "not tiered". The columns are hidden only when the capability is unavailable; they are filled with that reason when the topic is not tiered. ELR columns use the attributes already present. No second detection path.

**Preferred leader is the first replica.** The table does not sort replicas to put the leader first. A mismatch is a condition on the row. Election uses `ElectionType.PREFERRED` only. There is no unclean verb, no button, and no MCP argument. The preview splits the set into "would change" and "already preferred". The etag is the leaders and the replica sets of the partitions in the set. Partitions already preferred are a no-op in the result, not an error.

**The operator's replica set is the operation. The suggestion is text in the form.** The suggestion spreads the current replication factor across racks already reported on brokers, keeps the current leader in the set when it can, and never drops a replica unless the operator deletes one. It is labeled "suggestion" and copied into the form. Confirm sends the form. Closing the form sends nothing.

Alternative: apply the suggestion when the operator asks for it. Rejected. A suggestion that moves data is a preview, or it is not in this product.

**Throttle is the replication throttle, remembered until the move ends.** The preview shows `leader.replication.throttled.rate` and `follower.replication.throttled.rate` for the brokers in the set, and the topic's throttled-replica lists. The caller sets the rate. Estimated duration is bytes-to-copy divided by that rate, labeled an estimate, because the cluster does not promise a pace. Bytes-to-copy is the sum of the sizes of replicas that would be added.

Before apply, the driver reads the current throttle values. The operation result, and a row Rahdar stores, keep the previous values and which keys this operation added. When `listPartitionReassignments` no longer contains the partition, Rahdar restores those previous values (an absent value deletes the override). The row is `kafka_reassignment` (cluster, topic, partition, operation id, previous throttle). It is written in the same transaction as the audit of the apply, and deleted when the restore succeeds. A restart continues the restore on the normal scrape. A throttle key this operation did not change is not in the row.

The preview says which of those two outcomes the operator will get: "this throttle is removed when the move finishes" or "the existing throttle is put back".

Alternative: leave the throttle until the operator clears it. Rejected. A forgotten throttle is how a cluster stays slow after one move. Alternative: clear every replication throttle when any move finishes. Rejected. That deletes an operator's standing throttle.

**min.insync.replicas is read from the topic config the driver already returns.** A new replication factor below that value is `FAILED_PRECONDITION` in the preview, naming both numbers, with no `alterPartitionReassignments` call. Adding replicas (raising the factor) is the same operation as removing them.

**Etag.** Replicas in order, ISR, leader, and the reassignment id if one is in progress. Apply re-reads it. A second move of the same partition is stale, not queued. Cancel is a preview of the in-progress reassignment with the same etag.

**Screens.** The topic page gains the partition table. The Kafka namespace gains Replication, next to Brokers. It lists only partitions that are under-replicated, offline, or not on the preferred leader. Empty means a sentence, not an empty grid. Reads stay on the existing resource list. The screen does not poll faster than the scrape; opening it and the scrape are the refreshes. The restore of a throttle runs from the scrape, which already walks clusters.

**Permissions.** `destination:read` for both tables. `destination:update` for election, reassignment and cancel. Same pattern as a topic setting change.

**MCP.** Added requirement, not an edit of the existing Kafka tools paragraph, so it does not collide with 19e6.

**ZooKeeper mode.** Election and reassignment work. The quorum panel is unchanged: the controller is kept in ZooKeeper and cannot be read.

## Risks / Trade-offs

- [A restore runs twice and deletes a throttle the operator set after the move] → The row stores the previous value and the keys this operation added. Restore writes that previous value once, then deletes the row. A throttle changed by someone else after the move is only at risk for keys this operation added; the preview told the operator those keys would be removed. Keys it did not add are untouched.
- [describeLogDirs on every replica is heavier than the leader-only read] → One call for the brokers of the page, which is the bound the driver already uses for leaders. The replication screen asks for the partitions that are under-replicated or offline first, not every partition of a large cluster, unless the operator opens one topic.
- [ListOffsets v9 on a 2.8 broker is an error in the log] → The probe is once per connection, and the refusal becomes the capability reason. Later reads do not retry the unsupported spec.
- [A preferred election during a reassignment fights the move] → The etag includes an in-progress reassignment. The election is refused as stale while one is in progress for that partition.

## Migration Plan

One Liquibase changeset in Rahdar, `kafka_reassignment`, created empty. Rollback drops that table. In-flight throttles recorded there are restored by the build that wrote them; a rollback that drops the table leaves those throttles in place, which the operator can clear on the broker configuration screen. No OpenBroker schema. No change to existing topic or group rows.

## Open Questions

None.
