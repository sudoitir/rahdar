## ADDED Requirements

### Requirement: Produce and inspect are MCP tools
For a Kafka cluster, MCP tools SHALL produce a record and SHALL inspect a browse, a live tail and an export. They SHALL use the same permissions, previews and audit trail as the console. A produce tool SHALL return a preview that changes nothing before it writes, and SHALL refuse a stale preview.

#### Scenario: An assistant produces
- **WHEN** an assistant calls the produce tool and then confirms
- **THEN** the tool first returns a preview that writes nothing, and the record is written only with the produce permission, the approval gate where it is on, and an audit event with before and after

#### Scenario: A stale preview
- **WHEN** an assistant applies a stale preview
- **THEN** the produce is refused and no record is written

#### Scenario: A read-only token
- **WHEN** an assistant whose token cannot produce calls the produce tool
- **THEN** the call is refused and no record is written

#### Scenario: An assistant inspects
- **WHEN** an assistant browses, live-tails or exports through a tool
- **THEN** the result uses the screen's permission, includes headers, applies the filter expression, and joins no group
