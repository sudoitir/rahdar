## ADDED Requirements

### Requirement: Replication operations are MCP tools

The MCP server SHALL offer, for Kafka clusters only, tools to list a topic's partitions, list the partitions that are under-replicated, offline or not on their preferred leader, preview and elect preferred leaders, preview and reassign replicas, list reassignments in progress, and preview and cancel a reassignment. A mutating tool SHALL return its preview first, need `destination:update`, be held by the approval gate like the screen, send the preview's etag, and be audited. A tool SHALL NOT offer an unclean election. A tool SHALL NOT apply a suggestion except as the replica set in a confirmed preview.

#### Scenario: An assistant reassigns a partition

- **WHEN** an assistant calls the reassignment tool
- **THEN** it first receives the preview with the current replicas, the new replicas, the byte estimate and the throttle, and the move runs only after confirmation and any approval the policy requires

#### Scenario: A read-only token

- **WHEN** an assistant whose token is read-only calls the election tool
- **THEN** the call is refused and nothing is previewed or held

#### Scenario: A Kafka tool on an Artemis cluster

- **WHEN** an assistant calls the reassignment tool for an Artemis cluster
- **THEN** the call is refused as unsupported on that system
