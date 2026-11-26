## ADDED Requirements

### Requirement: Schema registry uses its own permissions

Reading a schema registry SHALL require `schema:read`. Registering a schema, changing compatibility and deleting a subject SHALL require `schema:write`. Neither permission SHALL be implied by `destination:read`, `destination:update` or `message:read`. Decoding a record the caller is already allowed to read SHALL NOT require `schema:write`. An operator without `schema:write` SHALL see registration, compatibility and delete disabled, naming the permission, and the API and the MCP tool SHALL refuse the call. A role MAY hold `schema:read` without `schema:write`.

Both permissions SHALL be catalogue entries at cluster scope, with a description. Viewer SHALL hold `schema:read` and SHALL NOT hold `schema:write`. Operator SHALL hold `schema:write`. These permissions cover the registered schema registry. They do not cover schema inference or display-time masking.

#### Scenario: A reader cannot register

- **WHEN** a role holds `schema:read` and does not hold `schema:write`, and a caller with that role previews a registration
- **THEN** the preview is refused, nothing is written, and the refusal names `schema:write`

#### Scenario: Topic update is not schema write

- **WHEN** a role holds `destination:update` and does not hold `schema:write`, and a caller with that role deletes a subject
- **THEN** the delete is refused and the subject remains

#### Scenario: Reading a message does not need schema write

- **WHEN** a caller with `message:read` and without `schema:write` browses a value the registry can decode
- **THEN** the decoded record is returned
