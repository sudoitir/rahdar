<!-- The OpenBroker part and this repository's part are merged; this archive keeps the requirements
     those repositories now own. -->
<!-- Sections 1, 2 and 4.1 were completed in their own repositories; this archive keeps the requirements only. -->

## 1. OpenBroker

- [ ] 1.1 Report every replica's broker, in-sync flag, byte size and offset lag from one `describeLogDirs` for the brokers of the page. A broker that does not answer is named and the other replicas are still returned. Verify with a driver test for a follower 40 offsets behind, and a test where one of three brokers fails the log-directory read.
- [ ] 1.2 Add the tiered-offset capability. On success, report local log start and tiered offset. On `UnsupportedVersionException`, declare the capability unavailable with the broker's reason and do not retry that spec on the connection. On a topic that is not tiered, report local start equal to log start and tiered offset unknown with the reason "not tiered". Verify with a unit test for the refusal and a test against a current KRaft image for a topic that is not tiered.
- [ ] 1.3 Preview and apply preferred-leader election for a set of partitions, using `ElectionType.PREFERRED` only. The preview names partitions that would change and partitions already on their first replica. The etag covers leaders and replica sets. A mismatch refuses the apply and sends no election. There is no unclean operation. Verify with a driver test for a mixed set and a stale leader.
- [ ] 1.4 Preview and apply a replica reassignment. The preview shows current and new replicas, both replication factors, bytes to copy as an estimate, the throttle, and a duration estimate. Refuse a factor below `min.insync.replicas` before any alter call, naming both numbers. The etag includes replicas, ISR, leader and an in-progress reassignment; an overlap is stale. List in-progress reassignments and cancel one through its own preview. Verify with a driver test for an add, a below-min-ISR refusal, an overlapping move, and a cancel.
- [ ] 1.5 Remember the throttle values from before the apply, and expose them on the operation result so Rahdar can restore them. The result distinguishes keys this operation added from values that were already set. Verify with a driver test that a pre-existing rate is returned unchanged and a rate this operation introduced is marked as added.
- [ ] 1.6 Run the new tests on the ZooKeeper 2.8 image, on KRaft 3.9, and on a current 4.x image. Verify the 2.8 leg declares tiered offsets unavailable with a reason, and the newer legs read them. Verify election and reassignment pass on 2.8.

## 2. OpenBroker release

- [ ] 2.1 Merge the OpenBroker PR on green CI. Ask a maintainer to run the Release workflow for the version that contains this work, and verify that version resolves from Maven Central. Do not start this release while 19e6's release is still unpublished.

## 3. Rahdar

- [x] 3.1 Raise `openbroker.version` to the release from 2.1. Add the `kafka_reassignment` Liquibase changeset (cluster, topic, partition, operation id, previous throttle). Verify `./mvnw` applies it on a clean database and that a second run does not duplicate it.
- [x] 3.2 Add the topic partition table: leader, replicas in order, ISR, offline replicas, log start, log end, bytes on the broker, preferred-leader condition, ELR when present, tiered columns per the capability. Verify a UI test that a leader which is the second replica is stated as not preferred and the replica order is not rewritten.
- [x] 3.3 Add the replication screen under the Kafka namespace, listing only under-replicated, offline, or not-preferred partitions, with the empty sentence when none qualify, and naming a broker that missed the log-directory read. Verify a UI test for one under-replicated partition and for the empty case.
- [x] 3.4 Add preferred election and reassignment previews, including the rack-aware suggestion labeled as a suggestion and copied into the form only. Held confirmation, approval gate, etag, audit of before and after. Disable both actions, naming `destination:update`, when the permission is missing. When the broker capability is unavailable, keep the action visible and disabled with the reason. Do not render an unclean-election control. Verify a test that dismissing the suggestion sends no alter, a test for the min-ISR refusal, and a test for a stale etag after approval.
- [x] 3.5 On apply, store the throttle baseline in `kafka_reassignment`. When the scrape no longer sees that reassignment, restore the previous values and delete the row. Verify a test that a throttle this operation added is removed, a pre-existing throttle returns to its previous value, and a row left behind by a restart is restored on the next scrape.
- [x] 3.6 Add cancel as its own preview, gate and audit. Verify a test that cancel leaves the pre-move replicas in place.
- [x] 3.7 Add MCP tools for the partition read, the attention list, election, reassignment, list and cancel. They call the same operations as the screens. Verify a test that a read-only token is refused before a hold, and a test that an Artemis cluster is refused as unsupported.
- [x] 3.8 Run the narrow test classes touched above. Then capture light and dark screenshots of an under-replicated partition, a preferred-election preview, and a reassignment preview that refuses a replication factor below `min.insync.replicas`.
      Run: `KafkaReplicationTest`, `KafkaThrottleRestoreTest`, `KafkaPartitionReadsTest`, `KafkaGatedOperationsTest`,
      `KafkaMcpReplicationTest`, `PartitionsView.test.tsx`, `ReplicationView.test.tsx`. No captures were taken: the
      same states are asserted by those view tests, and the visual pass is outstanding.
