# system-kafka Specification

## Purpose
Defines what Rahdar offers only for Kafka clusters: consumer groups and their lag, offset resets, topic configuration, ACLs, hanging transactions and the brokers with their controller quorum, under the cluster's Kafka namespace in the UI, the API and the MCP server.

## Requirements

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

### Requirement: Hanging transactions are found on demand and aborted per partition
A Kafka cluster's transactions screen SHALL offer a Find action, for the cluster or one topic, that lists the transactions open on a partition but unknown to the coordinator (KIP-664), each with its partition, producer, the records it holds back and its age, and SHALL let an operator with the transaction abort permission abort one after a preview that states the records released and that the producer is fenced. Finding SHALL never run on its own, since it reads every partition in scope. The abort SHALL be a held confirmation, pinned to the previewed state, go through the approval gate, and be audited.

#### Scenario: Finding hanging transactions of a topic
- **WHEN** an operator asks to find the hanging transactions of one topic
- **THEN** each hanging transaction on its partitions is listed with the records it holds back, and none is listed for a cluster that has none

### Requirement: Brokers and the controller quorum are shown
The brokers screen SHALL list each broker with its rack, advertised address, whether Rahdar reaches it, partition leadership count and under-replicated and offline partitions, and SHALL show the controller quorum with its leader, voters, their lag and whether it has a majority. An unreachable broker SHALL be stated in words, with the rest still shown. On a ZooKeeper-mode cluster the quorum SHALL be shown as unavailable with the reason.

#### Scenario: A broker is down
- **WHEN** one broker of three is unreachable
- **THEN** it is shown as unreachable, its partitions' under-replication is counted, and the quorum shows whether it still has a majority

#### Scenario: A ZooKeeper-mode cluster
- **WHEN** an operator opens the brokers screen of a ZooKeeper-mode cluster
- **THEN** the brokers are listed, and the quorum says the controller is kept in ZooKeeper and cannot be read

### Requirement: Broker configuration is viewed and changed safely

The system SHALL show each Kafka broker's configuration and the cluster-wide default, each value with its source,
marking the settings that take effect only after a restart. A change to a dynamic setting SHALL be previewed as a
diff, confirmed, applied with the preview's etag, held by the approval gate and audited. A restart-only setting SHALL
be refused with the reason, and a secret setting SHALL be refused.

#### Scenario: Changing the cluster default

- **WHEN** an operator previews and confirms a new value for a dynamic setting on the cluster default
- **THEN** the setting is applied, audited, and read back with the cluster as its source

#### Scenario: A restart-only setting

- **WHEN** an operator tries to change a setting that only changes on a restart
- **THEN** the change is refused with a 409 that says the broker must be restarted with the new value

### Requirement: A Kafka subscriptions page uses the group-on-topic label

On a Kafka cluster the subscriptions page SHALL be headed "Groups on topics". Its columns SHALL be the group, the topic, the lag and the members. It SHALL NOT offer a routing type, durability, a paused state, a ring size, or a working create. The create control SHALL stay visible and disabled, because a group on a topic cannot be created by hand. An empty page SHALL say that a group appears only after a committed offset or a live assignment, and that a group with neither stays on Consumer groups.

`POST /subscriptions` for a Kafka kind, and delete, pause, resume, update and reset-counters of a Kafka kind, SHALL NOT run the Artemis queue lifecycle. A Kafka kind the driver cannot create SHALL be refused `unsupported-on-kind`. Delete of a group on a topic SHALL NOT delete an Artemis queue and SHALL NOT remove the group's members. Purge, offset reset, and delete of an empty group on the group page SHALL stay.

#### Scenario: The subscriptions page of a Kafka cluster

- **WHEN** an operator opens the subscriptions page of a Kafka cluster that has no group on a topic
- **THEN** the heading is "Groups on topics", the columns are group, topic, lag and members, create is visible and disabled, and the page says a group appears only after a committed offset or a live assignment

#### Scenario: Creating a subscription is refused

- **WHEN** a caller posts a Kafka kind to the shared subscription create route
- **THEN** the request is refused as unsupported on that kind and no queue is created

#### Scenario: Deleting a group on a topic does not remove members

- **WHEN** an operator deletes a group-on-topic row
- **THEN** no queue is deleted and the group's members stay; an empty group is still deleted from the group's own page

### Requirement: A Kafka destinations page uses the topic label

When a cluster has one destination kind, the destinations page and its create control SHALL use that kind's label. On a Kafka cluster that heading is "Topics" and the control reads "New topic". The Kafka create form SHALL remain the topic form. Artemis and RabbitMQ SHALL keep the labels they already show.

#### Scenario: New topic

- **WHEN** an operator opens the destinations page of a Kafka cluster
- **THEN** the heading is "Topics" and the create control reads "New topic" and opens the topic form

### Requirement: An operator can create a topic from the destinations screen

On a Kafka cluster, an operator with `destination:create` SHALL create a topic from the shared destinations screen. The form SHALL ask for the name, the partition count, the replication factor, and the settings the topic kind declares (`cleanup.policy`, `retention.ms`, `retention.bytes`, `max.message.bytes`, `min.insync.replicas`). The preview SHALL state the topic that will be created. Confirm SHALL be a held confirmation, SHALL go through the approval gate when a policy holds it, and SHALL be audited with the topic that was created. An operator without the permission SHALL see the action disabled, naming the permission, and the API SHALL refuse it. Creating a topic SHALL be offered only on a Kafka cluster.

#### Scenario: Creating orders

- **WHEN** an operator previews a topic `orders` with 6 partitions and replication factor 3 and confirms
- **THEN** the topic exists with those values, and the audit records the create

#### Scenario: Without the permission

- **WHEN** an operator without `destination:create` opens the destinations screen of a Kafka cluster
- **THEN** create is disabled and names the missing permission

### Requirement: An operator can delete a topic after a preview

An operator with `destination:delete` SHALL delete a topic after a preview that names every group still holding offsets on it and the record estimate. Confirm SHALL be a held confirmation, SHALL go through the approval gate when a policy holds it, SHALL send the preview's etag, and SHALL be audited with the groups and the estimate. A stale etag SHALL be refused and the operator SHALL be offered a fresh preview. An approved delete SHALL check the etag again when it runs. An internal topic SHALL keep the configuration screen's read-only treatment: delete is disabled with the reason that the cluster manages internal topics.

#### Scenario: Deleting a topic two groups read

- **WHEN** an operator previews deletion of a topic that `billing` and `search` still have offsets on, and confirms
- **THEN** the preview names both groups and the record estimate, the topic is deleted, and the audit records both groups and the estimate

#### Scenario: A group the preview did not name appears

- **WHEN** a group that the preview did not name commits offsets on the topic, or the record estimate changes, between the preview and the confirmation
- **THEN** the delete is refused as stale and the operator is offered a fresh preview

### Requirement: An operator can add partitions after a preview

An operator with `destination:update` SHALL raise a topic's partition count after the broker-validated preview. The preview SHALL show the current count and the new count, and SHALL say that records already written stay on their partitions and that a key can land on a new partition afterwards. Confirm SHALL be a held confirmation, SHALL go through the approval gate when a policy holds it, SHALL send the preview's etag, and SHALL be audited with both counts. A lower count SHALL leave confirm disabled with the reason. The form SHALL NOT ask for a replica assignment.

#### Scenario: Adding partitions

- **WHEN** an operator previews raising a topic from 6 partitions to 12 and confirms
- **THEN** the topic has 12 partitions and the audit records 6 and 12

#### Scenario: A lower count

- **WHEN** an operator enters a partition count below the topic's current count
- **THEN** confirm is disabled and the reason names both counts

### Requirement: An operator can delete an empty group after a preview

An operator with `subscription:delete` SHALL delete a group that has no members, after a preview of its state. A group with members, or a group that is rebalancing, SHALL name those members and SHALL leave confirm disabled. A streams group SHALL leave delete disabled with the reason the streams application owns it. The caller SHALL NOT remove members in order to delete the group. Confirm SHALL be a held confirmation, SHALL go through the approval gate when a policy holds it, SHALL send the preview's etag, and SHALL be audited. Where share groups are not available, delete of a share group SHALL stay disabled with that capability's reason.

#### Scenario: Deleting an empty group

- **WHEN** an operator previews deletion of an empty group and confirms
- **THEN** the group is gone and the audit records its id and state

#### Scenario: Members are running

- **WHEN** an operator previews deletion of a group with two members
- **THEN** the preview names both members and confirm is disabled
