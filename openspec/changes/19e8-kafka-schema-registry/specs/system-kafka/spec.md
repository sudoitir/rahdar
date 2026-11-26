## ADDED Requirements

### Requirement: The registry screen stays visible when no registry is registered

The Kafka namespace SHALL include a registry screen. An operator with `schema:read` SHALL see subjects, versions, schema type and compatibility. Avro, Protobuf and JSON Schema SHALL be shown as those types. A custom serde SHALL be a named binding on a topic's key or value. A cluster with no registry SHALL keep the screen visible and SHALL keep registration, compatibility and delete disabled, with the system's reason. An operation the registry does not offer SHALL stay visible and disabled with that registry's reason. The screen SHALL state loading, empty, filtered-empty, error and no-permission in words.

#### Scenario: No registry

- **WHEN** an operator opens the registry screen of a cluster with no registry
- **THEN** the screen is visible, registration, compatibility and delete are disabled, and the reason says the cluster has no registry

#### Scenario: An operation the registry does not offer

- **WHEN** an operator opens a registry that does not offer one operation the screen shows
- **THEN** that operation is disabled with the registry's reason, and the subjects it does return are listed

### Requirement: A schema change is previewed, held and audited

An operator with `schema:write` SHALL register a schema, change compatibility and delete a subject only after a preview. The preview SHALL change nothing, SHALL show before and after, and SHALL say that nothing has been written. Confirm SHALL be a press-and-hold confirmation, SHALL go through the approval gate when a policy holds it, SHALL send the preview's etag, and SHALL be audited with before and after. A stale etag SHALL be refused and the operator SHALL be offered a fresh preview. An approved operation SHALL check the etag again when it runs. A permanent delete, where the registry distinguishes one, SHALL be its own preview and SHALL say the schemas cannot be restored.

An operator without `schema:write` SHALL see the actions disabled, naming the permission. Registry credentials SHALL NOT appear on the screen, in the audit or in an API response.

#### Scenario: Registering a schema

- **WHEN** an operator previews a schema registration and does not confirm
- **THEN** the subject is unchanged

#### Scenario: The registration form says what is missing

- **WHEN** an operator submits a registration with no schema text, while the schema text is closed behind its disclosure
- **THEN** the disclosure opens, the message appears on the schema field, and focus moves to it

#### Scenario: Confirming a compatibility change

- **WHEN** an operator previews a compatibility change, confirms with press-and-hold, and a policy does not hold it
- **THEN** the subject's compatibility is the new level, and the audit records the level before and after

#### Scenario: The preview went stale

- **WHEN** a new version is registered between the preview and the confirmation
- **THEN** the apply is refused as stale and the operator is offered a fresh preview

#### Scenario: Without the permission

- **WHEN** an operator without `schema:write` opens the registry screen
- **THEN** registration, compatibility and delete are disabled and name the missing permission

#### Scenario: A class-name serde is refused

- **WHEN** an operator saves a binding whose serde is a class name
- **THEN** the binding is refused, nothing is stored, and the reason says a serde is a name this installation runs

#### Scenario: The credential is absent from the problem and the audit

- **WHEN** the registry refuses a call and the refusal text contains the registry credential
- **THEN** the problem body and the audit do not contain the credential

#### Scenario: A permanent delete preview writes nothing

- **WHEN** an operator previews a permanent delete and does not confirm
- **THEN** the subject is unchanged, and the preview says it soft-deletes the subject and then deletes the schema, that the schemas cannot be restored, and that nothing has been written

#### Scenario: A click on the hold does not apply

- **WHEN** an operator presses the confirm control and releases it before the hold completes
- **THEN** nothing is written

#### Scenario: A stale etag after approval writes nothing

- **WHEN** an approved apply runs and the registry has moved since the preview
- **THEN** the apply is refused and nothing is written

### Requirement: Browse and SQL show the record

Browse and a SQL result for a Kafka topic SHALL show a decoded key or value as the record when the registry can decode it. A key or value that cannot be decoded SHALL stay visible with the reason. The produce screen is not this requirement. A record produced through the driver with a subject and a serde SHALL read back as that record.

#### Scenario: Browse shows the record

- **WHEN** an operator browses a value whose schema id the registry has
- **THEN** the page shows the decoded record, not the wire bytes

#### Scenario: SQL shows the same record

- **WHEN** an operator queries that value in the SQL console
- **THEN** the result shows the decoded record

#### Scenario: Nothing matches

- **WHEN** an operator browses a value the registry cannot decode
- **THEN** the value is shown undecoded with the reason

#### Scenario: A tombstone is no-schema-id

- **WHEN** an operator browses a tombstone
- **THEN** the decoded field is `no-schema-id` and is not an empty record
