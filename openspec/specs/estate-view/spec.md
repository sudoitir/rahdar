# estate-view Specification

## Purpose
TBD - created by archiving change 19d-broker-neutral-surface. Update Purpose after archive.

## Requirements

### Requirement: An estate view across systems
The UI SHALL show clusters of every connected system side by side on its landing page, with comparable health, and a list of subscriptions across them. Shared screens SHALL use role nouns, with each row's native kind label. An action a system cannot perform SHALL appear disabled with the reason from the capability model, never hidden without explanation and never failing after the click. System-only screens SHALL appear only for clusters of that system.

#### Scenario: A mixed estate
- **WHEN** an operator opens the estate view with an Artemis cluster and a cluster of another system
- **THEN** both are listed with comparable health from their conditions, and the subscriptions table shows rows from both with their native kinds

#### Scenario: An action the system cannot perform
- **WHEN** an operator selects a message on a system that cannot move messages
- **THEN** the move action is disabled and says why

#### Scenario: A disabled action says why, in order
- **WHEN** an action is unavailable for more than one reason
- **THEN** the shown reason is the first of: the kind lacks the verb, a capability is unavailable, a permission is missing, and a missing permission names the permission

#### Scenario: An empty estate
- **WHEN** an operator with no registered cluster opens the estate view
- **THEN** it explains that a cluster is a set of brokers of one system and offers to register one

#### Scenario: A cluster that cannot be reached
- **WHEN** one cluster of the estate cannot be reached
- **THEN** its row states that in words and the rest of the estate is shown, and its subscriptions are noted as unavailable rather than the page failing

#### Scenario: A filter that matches nothing
- **WHEN** an operator filters the subscriptions so that none match
- **THEN** the table states that no subscriptions match the filter and offers to clear it, distinct from an estate that has none

#### Scenario: Only what the caller may read
- **WHEN** a team member opens the estate view
- **THEN** the clusters and subscriptions listed are only those the member may read

#### Scenario: A system-only screen is absent elsewhere
- **WHEN** an operator opens a cluster that is not Artemis
- **THEN** the Artemis-only screens (broker configuration, configuration diff, setup review) are absent from its navigation, and opening one by address shows not found

### Requirement: Native labels come from the broker SDK
Every screen SHALL show a resource's kind with the label its system's driver declares, singular or plural as the sentence needs, and SHALL never derive a label from a kind id.

#### Scenario: Kafka's words
- **WHEN** an operator opens the subscriptions table with a Kafka cluster in the estate
- **THEN** Kafka rows show "Group on topic" or "Share group on topic", and Artemis rows show "Queue"

#### Scenario: A plural from the driver
- **WHEN** an action is refused for every selected Kafka subscription
- **THEN** the reason names them with the driver's plural, "Groups on topics"

### Requirement: A backlog says whether it is exact, an estimate or unavailable
Every subscription's backlog SHALL carry its accuracy: exact, an estimate with its reason, or unavailable with its reason. An estimate SHALL be marked as one in words and to assistive technology, and the subscription SHALL offer the per-partition breakdown the driver reports, slowest first, with the sample's age. An unavailable backlog SHALL never be shown as zero.

#### Scenario: Lag in the estate
- **WHEN** a Kafka group lags on a topic with six partitions
- **THEN** its row shows the total lag marked as an estimate, and its breakdown shows each partition's committed offset, end offset and lag, slowest first

#### Scenario: No committed offset
- **WHEN** a group has committed no offset on one partition
- **THEN** that partition's lag reads unavailable with the reason, and the total says it leaves that partition out

### Requirement: Cluster health is formed per system on one scale
Each system's clusters SHALL get a health verdict from their driver's conditions on the same scale as every other system, with each cause stated in words. A Kafka cluster SHALL be critical when partitions are offline, the controller quorum has lost its majority or no broker is reachable, and degraded when a broker is fenced or unreachable, partitions are under-replicated or a quorum voter lags. Where the quorum cannot be read, the verdict SHALL say so and SHALL never present it as healthy.

#### Scenario: A broker is down
- **WHEN** one broker of a three-broker Kafka cluster stops
- **THEN** the cluster is degraded, the cause names the broker and the under-replicated partitions, and the rest of the cluster is still shown

#### Scenario: Offline partitions
- **WHEN** a partition has no leader
- **THEN** the cluster is critical and the cause names how many partitions are offline

#### Scenario: A ZooKeeper-mode cluster
- **WHEN** every broker of a ZooKeeper-mode cluster serves
- **THEN** the cluster is healthy, with a note that its controller quorum cannot be read
