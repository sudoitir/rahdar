## ADDED Requirements

### Requirement: A topic shows its partitions

A Kafka topic SHALL have a partition table. Each row SHALL show the partition, the leader, the replicas in order, the in-sync replicas, the offline replicas, the log start, the log end, and the size in bytes. The preferred leader is the first replica. A leader that is not the preferred leader SHALL be stated as a condition, not reordered. Eligible leader replicas SHALL be shown where the cluster keeps them, and absent with the existing reason where it does not. Local log start and the tiered offset SHALL follow the driver's capability: unavailable with the reason, or "not tiered" when the topic is not tiered. Size SHALL be labeled as bytes on the broker, not as a record count. A broker that did not answer SHALL be named, and the rest of the table SHALL still show.

#### Scenario: A partition whose leader is not preferred

- **WHEN** an operator opens a topic whose partition 0 is led by its second replica
- **THEN** the row shows the replicas in their real order and states that the preferred leader is not leading

#### Scenario: A topic that is not tiered

- **WHEN** an operator opens the partitions of a topic that is not tiered, on a cluster that can report the tiered offset
- **THEN** the tiered offset says the topic is not tiered, and the local log start matches the log start

### Requirement: The replication screen lists partitions that need attention

The Kafka namespace SHALL include a replication screen that lists partitions which are under-replicated, have an offline replica, or are not led by their preferred leader. The screen SHALL state loading, empty, filtered-empty, error and no-permission in words. An empty cluster SHALL explain that every partition is in sync and on its preferred leader, and SHALL NOT show an empty table. A broker that did not answer the log-directory read SHALL be named on the screen.

#### Scenario: One under-replicated partition

- **WHEN** an operator opens the replication screen and one partition has a replica out of sync
- **THEN** that partition is listed with its leader, replicas and in-sync set, and partitions that are healthy are not listed

#### Scenario: Nothing is wrong

- **WHEN** an operator opens the replication screen of a cluster whose every partition is in sync and on its preferred leader
- **THEN** the screen says so, and shows no table

### Requirement: Election and reassignment are previewed, held and audited

An operator with `destination:update` SHALL elect preferred leaders for a chosen set of partitions, and SHALL reassign a partition's replicas, after a preview. The operator MAY accept a rack-aware suggestion. The suggestion SHALL be labeled a suggestion, SHALL be fully visible in the preview, and SHALL NOT be applied except by confirming that preview. The form the operator confirms is the replica set that is sent. Confirm SHALL be a held confirmation, SHALL go through the approval gate when a policy holds it, SHALL send the preview's etag, and SHALL be audited with the replicas or leaders before and after. A stale etag SHALL be refused and the operator SHALL be offered a fresh preview. An approved operation SHALL check the etag again when it runs.

An operator without `destination:update` SHALL see both actions disabled, naming the permission. Where the broker does not support the operation, the action SHALL stay visible and disabled with the broker's reason. Unclean election SHALL NOT appear as an action.

Reassignments in progress SHALL be listed, and cancelling one SHALL be its own preview with the same confirmation, gate and audit. The preview SHALL state whether the throttle will be cleared or restored when the move finishes.

#### Scenario: Electing the preferred leader

- **WHEN** an operator previews a preferred election for the partitions that are not on their preferred leader, and confirms
- **THEN** those partitions are led by their first replica, and the audit records the leaders before and after

#### Scenario: A suggestion is not applied by itself

- **WHEN** an operator asks for a rack-aware suggestion and does not confirm the preview
- **THEN** no replica moves, and the suggestion remains only on the preview

#### Scenario: Below min.insync.replicas

- **WHEN** an operator previews a replica set whose replication factor is below the topic's `min.insync.replicas`
- **THEN** confirm is disabled and the reason names both numbers

#### Scenario: Without the permission

- **WHEN** an operator without `destination:update` opens the replication screen
- **THEN** election and reassignment are disabled and name the missing permission
