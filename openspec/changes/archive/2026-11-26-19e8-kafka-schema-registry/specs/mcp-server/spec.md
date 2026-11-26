## ADDED Requirements

### Requirement: Schema registry operations are MCP tools

The MCP server SHALL offer, for Kafka clusters only, tools to read subjects, versions and compatibility, and to preview and register a schema, preview and change compatibility, and preview and delete a subject. A mutating tool SHALL return its preview first, need `schema:write`, be held by the approval gate like the screen, send the preview's etag, and be audited with before and after. The preview SHALL change nothing. A stale preview SHALL be refused. A tool that returns a browsed record SHALL show the decoded record under the same rule as browse, and SHALL NOT require `schema:write` to decode a record the caller may already read. A tool SHALL NOT return registry credentials.

#### Scenario: An assistant registers a schema

- **WHEN** an assistant calls the registration tool
- **THEN** it first receives the preview, nothing is written, and the schema is registered only after confirmation and any approval the policy requires

#### Scenario: A read-only token

- **WHEN** an assistant whose token is read-only calls the delete tool
- **THEN** the call is refused and nothing is previewed or held

#### Scenario: A Kafka tool on an Artemis cluster

- **WHEN** an assistant calls the registration tool for an Artemis cluster
- **THEN** the call is refused as unsupported on that system

#### Scenario: Browse shows the record

- **WHEN** an assistant reads a value that the registry can decode
- **THEN** the tool result is the record

#### Scenario: A tool result does not carry the credential

- **WHEN** a schema tool fails and the registry's reason contains the credential
- **THEN** the tool result and the audit do not contain the credential
