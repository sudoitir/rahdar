## Purpose

Defines what Rahdar offers only for Kafka clusters: consumer groups and their lag, offset resets, topic configuration, ACLs, hanging transactions and the brokers with their controller quorum, under the cluster's Kafka namespace in the UI, the API and the MCP server.

## ADDED Requirements

### Requirement: Kafka screens live in the cluster's Kafka namespace
Kafka-only screens and API routes SHALL live under `/clusters/{cluster}/kafka/`. They SHALL appear in navigation only for a Kafka cluster, and another system's cluster SHALL answer them with not found without calling a broker. Every screen SHALL state loading, empty, filtered-empty, error, unavailable and no-permission in words, and SHALL say which broker did not answer when a result is partial.

#### Scenario: An Artemis cluster has no Kafka screens
- **WHEN** an operator opens `/clusters/{cluster}/kafka/groups` for an Artemis cluster
- **THEN** the not-found page is shown and no broker call is made

### Requirement: Consumer groups show their lag per partition
The groups screen SHALL list every group with its type (classic, consumer, share, streams), state, member count and total lag as an estimate, paged by cursor and searchable by id. A group's page SHALL show each topic's partitions with committed offset, end offset and lag, slowest first, and the members with their assignments. A partition with no committed offset SHALL show its lag as unavailable with the reason, never zero.

#### Scenario: A lagging group
- **WHEN** an operator opens a group that lags on two topics
- **THEN** the page shows each partition's lag, the totals are marked as estimates, and the slowest partitions are first

#### Scenario: No groups
- **WHEN** an operator opens the groups screen of a cluster with no groups
- **THEN** it explains that groups appear when applications consume, and shows no empty table

### Requirement: A group's read-only or deleted parts are stated
A topic the group still has offsets for but that no longer exists SHALL be shown as deleted. A streams group SHALL be shown read-only with the reason.

#### Scenario: A streams group
- **WHEN** an operator opens a streams group
- **THEN** its offsets are shown, and reset is disabled with the reason that the streams application owns them

#### Scenario: A deleted topic
- **WHEN** a group has committed offsets on a topic that was deleted
- **THEN** that topic is listed as deleted, with no end offset and no lag

### Requirement: Offsets are reset only on an empty group, after a preview
An operator with the subscription seek permission SHALL reset a consumer, classic or share group's offsets on one topic or all of its topics to the earliest, the latest, a time or a given offset. The preview SHALL show each partition's current and new offset, and the records that will be skipped or re-read. If the group has active members or is rebalancing, the preview SHALL name them and the reset SHALL be refused.

#### Scenario: Reset to a time
- **WHEN** an operator previews resetting an empty group to yesterday 09:00 and confirms
- **THEN** each partition's offset becomes the first at or after that time, and the audit records the offsets before and after

#### Scenario: Resetting an empty classic group
- **WHEN** an operator resets an empty classic group to the earliest offset
- **THEN** its committed offsets move to each partition's first offset, and the audit records them before and after

#### Scenario: Resetting a share group
- **WHEN** an operator resets an empty share group to the latest offset
- **THEN** its start offsets move to the end, and the audit records them before and after

#### Scenario: A group with members
- **WHEN** an operator previews a reset of a group with two running members
- **THEN** the preview names both members and the confirm action is disabled with the reason

### Requirement: A reset target outside the log is stated
A time with no later record SHALL move the partition to its end and say so; an offset outside a partition's range SHALL be refused naming the range.

#### Scenario: An offset out of range
- **WHEN** an operator previews a reset of partition 1 to offset 5000 and the partition holds offsets 0 to 4200
- **THEN** the preview refuses that partition, naming its range

### Requirement: An offset reset is guarded and audited
An offset reset SHALL run against its preview's state key, SHALL be a held destructive confirmation, SHALL go through the approval gate when a policy holds it, and SHALL be audited with the offsets before and after. An approved reset SHALL check the state key again when it runs.

#### Scenario: Offsets moved after the preview
- **WHEN** a group commits new offsets between an operator's preview and confirmation
- **THEN** the reset is refused as stale and the operator is offered a fresh preview

#### Scenario: Approved after the group changed
- **WHEN** a held reset is approved after the group's offsets moved
- **THEN** it is refused as stale when it runs, and the requester is told to preview again

#### Scenario: Without the permission
- **WHEN** an operator without the subscription seek permission opens a group
- **THEN** reset is shown disabled naming the missing permission, and the API refuses it

### Requirement: Topic configuration is shown with its source
A topic's configuration screen SHALL list every setting with its value, its source (topic override, broker default, static default, default), its default and whether it is read-only, with sizes and durations in readable units. Sensitive values SHALL never be sent to the browser. An internal topic's configuration SHALL be read-only with the reason.

#### Scenario: A sensitive setting
- **WHEN** a topic has a sensitive setting
- **THEN** the screen says it is set and sensitive, and its value is in no answer

#### Scenario: An internal topic
- **WHEN** an operator opens the configuration of `__consumer_offsets`
- **THEN** every setting is read-only with the reason that the cluster manages internal topics

### Requirement: A topic override is changed after a validated preview
An operator with the destination update permission SHALL change or remove an override after a preview that the broker validates without applying, and removing an override SHALL show the value the setting falls back to. The change SHALL be audited with before and after.

#### Scenario: Changing retention
- **WHEN** an operator sets a topic's retention override and confirms the preview
- **THEN** the setting shows the new value with the source "topic override", and the change is audited with before and after

#### Scenario: An invalid value
- **WHEN** an operator previews a retention of "-5 days"
- **THEN** the preview shows the broker's validation message on that setting and nothing is applied

### Requirement: ACLs are listed and managed
The ACLs screen SHALL list ACLs by principal, resource and operation, filtered by principal and resource, and SHALL let an operator with the access-rule write permission add or remove one after a preview. The preview SHALL compare each affected principal's effective access before and after, SHALL say when a removal leaves a principal unable to do what it did, and SHALL note where a deny rule wins. Where the cluster has no authorizer, the screen SHALL say ACLs are unavailable with the reason.

#### Scenario: Removing an ACL
- **WHEN** an operator previews removing the only read ACL of a principal on a topic
- **THEN** the preview says that the principal will lose read access to that topic

#### Scenario: No authorizer
- **WHEN** an operator opens the ACLs screen of a cluster with no authorizer
- **THEN** it explains that the cluster has no authorizer configured, so it enforces no ACLs, and offers no add action

### Requirement: Hanging transactions can be aborted
The transactions screen SHALL list open transactional ids with their state, age and timeout as information, saying that the coordinator aborts a transaction past its timeout by itself, and SHALL flag in words one still open after its timeout and the coordinator's cleanup, with the partitions it blocks. It SHALL let an operator with the transaction abort permission abort such a transaction, and no other (a refusal that says why), after a preview that says the producer is fenced and the blocked partitions are released. The abort SHALL be a held confirmation, go through the approval gate, and be audited.

#### Scenario: A hanging transaction
- **WHEN** a transaction has been open longer than its timeout
- **THEN** it is marked as hanging with its age and the partitions it blocks, and an abort is offered

### Requirement: Brokers and the controller quorum are shown
The brokers screen SHALL list each broker with its rack, advertised address, whether Rahdar reaches it, partition leadership count and under-replicated and offline partitions, and SHALL show the controller quorum with its leader, voters, their lag and whether it has a majority. An unreachable broker SHALL be stated in words, with the rest still shown. On a ZooKeeper-mode cluster the quorum SHALL be shown as unavailable with the reason.

#### Scenario: A broker is down
- **WHEN** one broker of three is unreachable
- **THEN** it is shown as unreachable, its partitions' under-replication is counted, and the quorum shows whether it still has a majority

#### Scenario: A ZooKeeper-mode cluster
- **WHEN** an operator opens the brokers screen of a ZooKeeper-mode cluster
- **THEN** the brokers are listed, and the quorum says the controller is kept in ZooKeeper and cannot be read
