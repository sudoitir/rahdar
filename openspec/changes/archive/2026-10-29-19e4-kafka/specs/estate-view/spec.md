## ADDED Requirements

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
