## MODIFIED Requirements

### Requirement: The cluster view shows brokers and the controller quorum

This replaces "Brokers and the controller quorum are shown". The Cluster view, the first Observe view of a Kafka cluster and what the cluster opens on, SHALL show each broker with its rack, advertised address, whether Rahdar reaches it, the partitions it leads, the replicas it holds and the replicas of those that are out of sync or offline, and SHALL show the controller quorum with its mode, its leader, its voters, their lag and whether it has a majority. An unreachable broker SHALL be stated in words, with the rest still shown. On a ZooKeeper-mode cluster the quorum SHALL be shown as unavailable with the reason, and SHALL NOT be counted as healthy. On a KRaft cluster older than 4.0 the view SHALL state that a fenced broker is not listed before Kafka 4.0, so an absent broker is not read as healthy. The brokers SHALL also be readable as a table, and each broker SHALL link to its own configuration.

#### Scenario: A broker is down

- **WHEN** one broker of three is unreachable
- **THEN** it is shown as unreachable with the cluster's reason, the partitions whose replicas it holds are counted as under-replicated or offline, and the quorum shows whether it still has a majority

#### Scenario: A ZooKeeper-mode cluster

- **WHEN** an operator opens the cluster view of a ZooKeeper-mode cluster
- **THEN** the brokers are shown with their co-replication edges, and the quorum says the controller is kept in ZooKeeper and cannot be read, and is not counted as healthy

#### Scenario: A KRaft cluster before 4.0

- **WHEN** a broker of a KRaft cluster older than 4.0 is fenced
- **THEN** the view states that a fenced broker is not reported before Kafka 4.0, so its absence is not read as healthy

#### Scenario: A broker's own settings

- **WHEN** an operator follows a broker from the cluster view to its configuration
- **THEN** that broker's settings open, read the same way they were before, and the way back returns to the cluster view

