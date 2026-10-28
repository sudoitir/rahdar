## ADDED Requirements

### Requirement: Kafka operations are MCP tools
The MCP server SHALL offer, for Kafka clusters only, tools to list groups with lag, describe a group, preview and reset offsets, read and change topic configuration, list ACLs and abort a hanging transaction. A mutating tool SHALL return its preview first, need the matching permission, be held by the approval gate like its UI counterpart, and be audited.

#### Scenario: An assistant resets offsets
- **WHEN** an assistant calls the reset tool for an empty group
- **THEN** it first receives the preview with the per-partition change, and the reset runs only after confirmation and any approval the policy requires

#### Scenario: A Kafka tool on an Artemis cluster
- **WHEN** an assistant calls a Kafka tool for an Artemis cluster
- **THEN** the call is refused as unsupported on that system

#### Scenario: A read-only token
- **WHEN** an assistant whose token is read-only calls the offset reset tool
- **THEN** the call is refused and nothing is previewed or held
