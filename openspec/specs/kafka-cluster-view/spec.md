# kafka-cluster-view Specification

## Purpose

A read-only graph of one Kafka cluster, with the same facts in words: brokers and the quorum, replica placement, and the partitions that are under-replicated or offline.

## Requirements

### Requirement: One Kafka cluster is a read-only graph

The system SHALL show one Kafka cluster as a read-only graph, read the way the Artemis topology is read, with the same facts in words. The graph SHALL draw one box per node that serves clients and one box per controller-only node, and one edge between two brokers that hold replicas of the same partition. An edge's label SHALL be the number of partitions the two brokers share, its thickness that number, and it SHALL be dashed when at least one of those shared replicas is out of sync. Above 200 co-replication pairs the graph SHALL draw the 200 with the most shared partitions and state how many pairs it left out. The quorum leader SHALL be marked by its role in words, never by colour alone.

The view SHALL state, in words, the brokers reached of the brokers known, the controllers, the topics, the partitions, the partitions under-replicated, the partitions with no leader, the partitions not led by their preferred leader, and whether the controller quorum has a majority. The same brokers SHALL be readable as a table, and the chosen form SHALL live in the address, as the topology's does. A chosen broker SHALL show its rack, its address, whether the cluster reached it or why not, the partitions it leads, the replicas it holds, and the replicas out of sync, and SHALL link to that broker's configuration. A partition that needs attention SHALL link to the replication view.

The whole view SHALL come from one read of the cluster, so the graph and the words state the same snapshot.

#### Scenario: The graph and the words agree

- **WHEN** an operator opens the cluster view of a three-broker cluster
- **THEN** the graph draws three boxes joined by an edge for each pair that shares a partition, the table lists the same three brokers with what each leads and holds, and both state the same partition totals

#### Scenario: A broker is down

- **WHEN** one broker of three is unreachable
- **THEN** its box says it is not reached, with the cluster's reason, the partitions whose replicas it holds are counted as under-replicated or offline, the edges to it are dashed where a shared replica is out of sync, and the quorum still says whether it has a majority

#### Scenario: ZooKeeper mode

- **WHEN** an operator opens the cluster view of a ZooKeeper-mode cluster
- **THEN** the brokers and their co-replication edges are drawn, and the quorum section says the controller is kept in ZooKeeper and cannot be read, and is not counted as healthy

#### Scenario: KRaft before 4.0

- **WHEN** an operator opens the cluster view of a KRaft cluster older than 4.0
- **THEN** the quorum is shown with its leader and voters, and the view states that a fenced broker is not listed before Kafka 4.0, so an absent broker is not read as healthy

#### Scenario: Dedicated controllers

- **WHEN** the cluster runs controllers that serve no clients
- **THEN** each is drawn as a controller box, is not counted among the brokers, and has no co-replication edge

### Requirement: The graph does not trace a message and it is not Flow

The graph SHALL NOT trace a message and SHALL NOT be the Flow view. Nothing on the cluster view SHALL write: it SHALL offer no action, no menu and no control that changes cluster state, including replica reassignment.

#### Scenario: No trace, no Flow and no write

- **WHEN** an operator uses the cluster view
- **THEN** it shows no message path, it is not Flow, no box offers an action, and no cluster state changes

### Requirement: A part the cluster cannot show stays visible and disabled

A part of the cluster the system cannot read SHALL stay visible and SHALL be disabled with the system's reason, and SHALL never be counted as healthy. A figure the system does not know SHALL be absent with its reason, never zero.

#### Scenario: The quorum cannot be read

- **WHEN** the cluster's controller quorum cannot be read
- **THEN** the quorum section stays on the page and says why, and it is not counted as a majority

#### Scenario: Fenced brokers are not reported

- **WHEN** the cluster is a KRaft cluster older than 4.0 and a broker is fenced
- **THEN** the view says that a fenced broker is not reported before Kafka 4.0, so the broker's absence is not read as healthy

#### Scenario: An incomplete snapshot says at least

- **WHEN** the cluster holds more topics than the driver describes at once, so the read covers only the first ones
- **THEN** the topics and partitions are stated as at least their figures, and the view states that it is incomplete and why

### Requirement: Topic names need the destination permission and MCP reads the graph with the screen's permission

The aggregate SHALL require `cluster:read`. The partitions that need attention SHALL state their counts to a caller with `cluster:read`, and their topic names SHALL be shown only to a caller who also holds `destination:read`; without it the names SHALL be withheld with a note saying so, and the counts SHALL stay.

An MCP tool that reads the cluster SHALL require the same `cluster:read` and SHALL return the same facts as the screen, held to the same bound as the screen's read.

#### Scenario: A caller reads the cluster but not its topics

- **WHEN** a caller with `cluster:read` and without `destination:read` reads the cluster
- **THEN** the counts of the partitions that need attention are shown, the topic names are withheld, and a note says the names need `destination:read`

#### Scenario: The caller may open the screen

- **WHEN** an assistant with `cluster:read` calls the cluster tool
- **THEN** the tool returns the brokers, the quorum, the co-replication edges and the same totals as the screen

#### Scenario: The caller may not

- **WHEN** an assistant without `cluster:read` calls the cluster tool
- **THEN** the call is refused and nothing is read from the cluster
