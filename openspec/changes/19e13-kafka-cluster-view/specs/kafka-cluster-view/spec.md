## Purpose
A read-only graph of one Kafka cluster, with the same facts in words: brokers and the quorum, topics and partitions, replica placement, and under-replicated and offline partitions.

## ADDED Requirements

### Requirement: One Kafka cluster is a read-only graph
The system SHALL show one Kafka cluster as a read-only graph, read the way the Artemis topology is read, with the same facts in words. It SHALL show brokers and the controller or quorum, topics and partitions, replica placement, and under-replicated and offline partitions. Brokers and the quorum are what 19e4 shipped. Placement and under-replication are what 19e7 exposes.

#### Scenario: The graph and the words agree
- **WHEN** an operator opens the graph of a cluster
- **THEN** they see the brokers, the quorum, the topics and partitions, where replicas sit, and the under-replicated and offline partitions, and the same facts in words

### Requirement: The graph does not trace a message and it is not Flow
The graph SHALL NOT trace a message and SHALL NOT be Flow. Nothing on it SHALL write.

#### Scenario: No trace, no Flow and no write
- **WHEN** an operator uses the graph
- **THEN** it shows no message path, it is not the Flow view, and no cluster state changes

### Requirement: A part the cluster cannot show stays visible and disabled
A part the cluster cannot show SHALL stay visible and disabled, with the system's reason.

#### Scenario: Placement cannot be read
- **WHEN** the cluster cannot show replica placement
- **THEN** that part stays visible and disabled, with the system's reason, and the rest of the graph remains

### Requirement: MCP reads the graph with the screen's permission
An MCP tool that reads the graph SHALL use the same permission as the screen.

#### Scenario: The caller may open the screen
- **WHEN** an assistant with the screen's permission reads the graph
- **THEN** the tool returns the same facts

#### Scenario: The caller may not
- **WHEN** an assistant without the screen's permission calls the tool
- **THEN** the call is refused
