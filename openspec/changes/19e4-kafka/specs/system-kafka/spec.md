## Purpose

Defines what Rahdar offers only for Kafka clusters: consumer groups and their lag, offset resets, topic configuration, ACLs, hanging transactions and the controller quorum, under the cluster's Kafka namespace in the UI, the API and the MCP server.

## ADDED Requirements

### Requirement: Kafka screens live in the cluster's Kafka namespace
Kafka-only screens and API routes SHALL live under `/clusters/{cluster}/kafka/`. They SHALL appear in navigation only for a Kafka cluster, and another system's cluster SHALL answer them with not found.

#### Scenario: An Artemis cluster has no Kafka screens
- **WHEN** an operator opens `/clusters/{cluster}/kafka/groups` for an Artemis cluster
- **THEN** the not-found page is shown and no broker call is made

### Requirement: Consumer groups show their lag per partition
The groups screen SHALL list every group with its type (classic, consumer, share, streams), state, member count and total lag as an estimate. A group's page SHALL show each topic's partitions with committed offset, end offset and lag, and the members with their assignments. A partition with no committed offset SHALL show its lag as unavailable with the reason, never zero.

#### Scenario: A lagging group
- **WHEN** an operator opens a group that lags on two topics
- **THEN** the page shows each partition's lag, the totals are marked as estimates, and the slowest partitions are first

#### Scenario: No groups
- **WHEN** an operator opens the groups screen of a cluster with no groups
- **THEN** it explains that groups appear when applications consume, and shows no empty table

### Requirement: Offsets are reset only on an empty group, after a preview
An operator with the subscription write permission SHALL reset a consumer group's or share group's offsets on one topic or all of its topics to the earliest, the latest, a time or a given offset. The preview SHALL show each partition's current and new offset, and the records that will be skipped or re-read. If the group has active members, the preview SHALL name them and the reset SHALL be refused.

#### Scenario: Reset to a time
- **WHEN** an operator previews resetting an empty group to yesterday 09:00 and confirms
- **THEN** each partition's offset becomes the first at or after that time, and the audit records the offsets before and after

#### Scenario: Resetting a share group
- **WHEN** an operator resets an empty share group to the latest offset
- **THEN** its start offsets move to the end, and the audit records them before and after

#### Scenario: A group with members
- **WHEN** an operator previews a reset of a group with two running members
- **THEN** the preview names both members and the confirm action is disabled with the reason

### Requirement: An offset reset is guarded and audited
An offset reset SHALL run against its preview's state key, SHALL go through the approval gate when a policy holds it, and SHALL be audited with the offsets before and after.

#### Scenario: Offsets moved after the preview
- **WHEN** a group commits new offsets between an operator's preview and confirmation
- **THEN** the reset is refused as stale and the operator is offered a fresh preview

### Requirement: Topic configuration is shown with its source
A topic's configuration screen SHALL list every setting with its value and source (topic override, broker default, static default), and SHALL let an operator with the destination write permission change or remove an override after a preview of the change. Sensitive values SHALL never be shown.

#### Scenario: Changing retention
- **WHEN** an operator sets a topic's retention override and confirms the preview
- **THEN** the setting shows the new value with the source "topic override", and the change is audited

### Requirement: ACLs are listed and managed
The ACLs screen SHALL list ACLs by principal, resource and operation, and SHALL let an operator with the access write permission add or remove one after a preview. A removal that would leave a principal with no access to a topic it reads SHALL say so in the preview.

#### Scenario: Removing an ACL
- **WHEN** an operator previews removing the only read ACL of a principal on a topic
- **THEN** the preview says that the principal will lose read access to that topic

### Requirement: Hanging transactions can be aborted
The transactions screen SHALL list transactional ids with their state and age, highlight transactions open longer than the transaction timeout, and SHALL let an operator abort a hanging one after a preview. The abort SHALL be audited.

#### Scenario: A hanging transaction
- **WHEN** a transaction has been open longer than its timeout
- **THEN** it is highlighted with its age and the partitions it blocks, and an abort is offered

### Requirement: Brokers and the controller quorum are shown
The brokers screen SHALL list each broker with its rack, partition leadership count and under-replicated and offline partitions, and SHALL show the controller quorum with its leader, voters and their lag. An unreachable broker SHALL be stated in words, with the rest still shown.

#### Scenario: A broker is down
- **WHEN** one broker of three is unreachable
- **THEN** it is shown as unreachable, its partitions' under-replication is counted, and the quorum shows whether it still has a majority
