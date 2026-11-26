# Schema registry

## Why

An operator managing a Kafka cluster cannot see or change the schemas records are written with, and a browsed value is wire bytes rather than the record.

## What changes

- A cluster names its schema registry. Subjects, versions and compatibility are listed. Registering, changing compatibility and deleting are previewed, held and audited.
- `schema:read` and `schema:write` are their own cluster permissions. `destination:update` does not grant write.
- A browsed record shows the decoded record, with the wire bytes behind a disclosure. A body rule masks the decoded field too.
- Bindings say which serde decodes a topic's key or value. They are stored here and passed to the driver per call.

## Capabilities

### New Capabilities

- None. The deltas extend `system-kafka`, `authorization` and `mcp-server`.

### Modified Capabilities

- `system-kafka`: the registry screen, preview and apply, and decoded browse.
- `authorization`: `schema:read` and `schema:write`.
- `mcp-server`: schema tools, held like the screen, with no credential in the result.

## Impact

The API gains `/clusters/{id}/kafka/schemas`. A browsed message gains a decoded field. Recreate the database; this release is before the first stable one.
