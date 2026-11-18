## Purpose
A ksqlDB query and execute screen for a cluster that has a ksqlDB endpoint, with a statement that writes previewed, confirmed, gated and audited.

## ADDED Requirements

### Requirement: ksqlDB is a screen when the cluster has an endpoint
When a cluster has a ksqlDB endpoint, the system SHALL offer a screen to query and to execute. When it does not, that screen SHALL stay visible and disabled, with the system's reason.

#### Scenario: An endpoint is present
- **WHEN** an operator opens ksqlDB on a cluster that has an endpoint
- **THEN** they can query and they can execute

#### Scenario: No endpoint
- **WHEN** a cluster has no ksqlDB endpoint
- **THEN** the screen stays visible and disabled, with the system's reason

### Requirement: A query does not write
A query SHALL return results and SHALL NOT write.

#### Scenario: Running a query
- **WHEN** an operator runs a query
- **THEN** results are shown and nothing is written

### Requirement: A statement that writes is previewed, confirmed, gated and audited
Executing a statement that writes SHALL show a preview that changes nothing, refuse a stale preview, confirm with a press-and-hold, pass the approval gate where it is on, and write an audit event with before and after.

#### Scenario: A preview writes nothing
- **WHEN** an operator previews a statement that writes
- **THEN** nothing is written

#### Scenario: A stale preview is refused
- **WHEN** the operator applies a stale preview
- **THEN** the statement is refused and nothing is written

#### Scenario: The gate is on
- **WHEN** the operator confirms with a press-and-hold and the approval gate is on
- **THEN** the statement runs only after approval, and the audit event records before and after

#### Scenario: The gate is off
- **WHEN** the operator confirms with a press-and-hold and the approval gate is off
- **THEN** the statement runs and the audit event records before and after

### Requirement: Execute is its own permission
Execute SHALL be its own permission, so a role can stay read-only. Without it, execute SHALL stay visible and disabled, with the reason, and the API SHALL refuse a statement that writes.

#### Scenario: A read-only role
- **WHEN** an operator whose role cannot execute opens ksqlDB
- **THEN** execute stays visible and disabled, with the reason, and the API refuses a statement that writes

### Requirement: MCP uses the same permissions, previews and audit
MCP tools for ksqlDB SHALL use the same permissions, previews and audit trail as the screen.

#### Scenario: An assistant executes a write
- **WHEN** an assistant executes a statement that writes and then confirms
- **THEN** the tool first returns a preview that writes nothing, and the statement runs only with the execute permission, the approval gate where it is on, and an audit event with before and after

#### Scenario: A read-only token
- **WHEN** an assistant without the execute permission calls the tool for a statement that writes
- **THEN** the call is refused and nothing is written
