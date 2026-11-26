# ADR-0231: Schema permissions are their own, and a decoded record is a field

- **Status**: accepted
- **Date**: 2026-11-26
- **Deciders**: Mahdi Amirabdollahi

## Context

A Kafka cluster can name a schema registry. Subjects, compatibility and deletion are not topic settings: `destination:update` must not grant them. Decoding a record the operator may already read is not a write.

The driver reaches the registry through `unwrap`. A binding is which serde decodes a topic's key or value. The driver stays stateless. Something in this product has to remember the binding and pass the name on each call.

A browsed value is wire bytes. Showing only those hides the record. Replacing the bytes hides the wire. A decoded field the body policy would mask must not leak beside a masked body.

## Decision

- `schema:read` and `schema:write` are cluster-scoped catalogue permissions. `schema:write` requires `schema:read`. Neither is implied by `destination:update` or `message:read`. Viewer holds read. Operator holds both. Decoding a record the caller may read does not require `schema:write`.
- A preview writes nothing and carries an etag. Apply, including an approval released later, checks that etag again. A stale etag writes nothing. A permanent delete is its own preview and says the schemas cannot be restored. Confirm is the existing press-and-hold, for a registration, a compatibility change and a delete. The audit records before and after, never a credential.
- A registry that is not configured, or that refuses an operation, is `schema-unavailable` (409) with the system and the registry's reason. The credential is not in that body.
- Bindings are stored here, one row per topic side, and the serde name is passed to the driver on each decode. A name is never a class name. The driver holds no per-topic state.
- A browsed message gains a decoded field: the record, or a reason. The key is decoded the same way, with the topic's key binding when one is set, and shown beside the record. A tombstone is `no-schema-id`, not an empty record. A truncated body is `truncated`. The wire bytes stay in the body. The record is what the page shows; the bytes sit behind a disclosure. The same fields are on the message the content policy reads, so a body rule masks them with the rest.

## Consequences

- An operator can see a registry without being able to change it, and cannot change it without a preview.
- One new table, and columns on `kafka_connection`. Recreate the database; this release is before the first stable one.
- A cluster with no registry keeps the screen, with the actions disabled and the reason stated.
- A live SQL read of a Kafka log shows the decoded record. A captured or indexed row stays undecoded: the tap drains a queue, not a topic side, and the index stores no decoded view. A later change that stores that view on the message index should revisit it. This change does not.
- There is no plugin-API change, so `Contract.VERSION` stays.

## Alternatives considered

- Reuse `destination:update`. It lost because changing a topic's settings and registering a schema are different blasts, and a role that may do one must be able to lack the other.
- Store the binding in the driver. It lost because the driver is stateless and the binding is an operator's audited choice.
- Replace the body with the decoded record. It lost because the wire bytes are what the registry framed, and a mask on the body must still apply to the record.
