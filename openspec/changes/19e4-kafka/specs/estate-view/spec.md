## ADDED Requirements

### Requirement: Native labels come from the broker SDK
Every shared screen SHALL show a resource's kind with the label its system's driver declares, singular or plural as the sentence needs, and SHALL never derive a label from a kind id.

#### Scenario: Kafka's words
- **WHEN** an operator opens the subscriptions table with a Kafka cluster in the estate
- **THEN** Kafka rows show "Group on topic" or "Share group on topic", and Artemis rows show "Queue"

### Requirement: A partitioned backlog is an estimate with its breakdown
Where a subscription's backlog is an estimate, the estate view and the subscription's page SHALL mark it as an estimate with its reason, and SHALL offer the per-partition breakdown the driver reports.

#### Scenario: Lag in the estate
- **WHEN** a Kafka group lags on a topic with six partitions
- **THEN** its row shows the total lag marked as an estimate, and its page shows each partition's share
