## MODIFIED Requirements

### Requirement: Built-in critical rules are seeded per cluster and remain ordinary rules

When a cluster is registered, the system SHALL seed the state-condition rules the cluster's system can answer:
split-brain, clock skew, node-down and replication-behind for an Artemis cluster, and cluster-degraded for a Kafka
or RabbitMQ cluster. These seeded rules SHALL behave as ordinary rules (editable, routable to channels, and
disableable), not as unconditional checks outside the rule model.

#### Scenario: A newly registered cluster has built-in rules

- **WHEN** an Artemis cluster is registered
- **THEN** split-brain, clock-skew, node-down and replication-behind rules exist for it, unbound to any channel
  and enabled by default

#### Scenario: A newly registered Kafka cluster is watched through its own health

- **WHEN** a Kafka or RabbitMQ cluster is registered
- **THEN** one cluster-degraded rule exists for it, and no Artemis HA rule

#### Scenario: A built-in rule can be silenced

- **WHEN** an operator disables the seeded replication-behind rule for a cluster
- **THEN** it no longer evaluates or fires for that cluster until re-enabled

## ADDED Requirements

### Requirement: Cluster degraded reads the system's own health

The cluster-degraded condition SHALL read the health the cluster's system judges, the same verdict the cluster
screen shows.

#### Scenario: Offline partitions fire cluster degraded

- **WHEN** a Kafka cluster's health is degraded or critical
- **THEN** its cluster-degraded rule becomes active

### Requirement: An Artemis-only rule is refused on another system

A rule on a condition or metric only Artemis reports SHALL be refused on a Kafka or RabbitMQ cluster with
`unsupported-on-system`, naming the system and what to watch instead. The rule form SHALL list those choices
disabled with the reason, and SHALL offer them while the system is unknown.

#### Scenario: A split-brain rule on a Kafka cluster

- **WHEN** an operator saves a split-brain rule on a Kafka cluster
- **THEN** the request is refused with a 409 that names Kafka and cluster-degraded
