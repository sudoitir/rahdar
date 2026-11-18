## Purpose
A read-only Streams topology when one is reported: sub-topologies, processors, state stores and the topics they use.

## ADDED Requirements

### Requirement: A reported topology is shown read-only
When a Streams topology is reported, the system SHALL show it read-only: its sub-topologies, processors, state stores and the topics they use. Nothing on it SHALL write.

#### Scenario: A topology is reported
- **WHEN** an application reports a topology
- **THEN** the operator sees its sub-topologies, processors, state stores and the topics they use, and nothing is written

### Requirement: The topology is not the cluster graph or a message's path
The topology SHALL NOT be the cluster graph and SHALL NOT be a message's path.

#### Scenario: Opening the topology
- **WHEN** an operator opens the topology
- **THEN** it is not the cluster graph and it does not show a message's path

### Requirement: No topology stays visible and disabled
When no topology is reported, the screen SHALL stay visible and disabled, with the system's reason.

#### Scenario: Nothing is reported
- **WHEN** no topology is reported
- **THEN** the screen stays visible and disabled, with the system's reason

### Requirement: MCP reads the topology with the screen's permission
An MCP tool that reads the topology SHALL use the same permission as the screen.

#### Scenario: Without the permission
- **WHEN** an assistant without the screen's permission calls the tool
- **THEN** the call is refused

#### Scenario: With the permission
- **WHEN** an assistant with the screen's permission reads a reported topology
- **THEN** the tool returns the same sub-topologies, processors, state stores and topics
