# ADR-0216: The Kafka namespace, its operations and its permissions

- **Status**: accepted
- **Date**: 2026-10-28
- **Deciders**: Mahdi Amirabdollahi

## Context

Kafka needs screens no other system has (groups and lag, offset resets, topic configuration, ACLs,
transactions, the controller quorum) and brings two things into the shared model: a backlog that is an
estimate, and truncation of a log. Each Kafka mutation is irreversible in effect, and some, such as a reset
or a truncate, affect applications the operator does not see.

## Decision

- **Namespace.** `system/kafka` and the web's `features/kafka`, under `/clusters/{c}/kafka/…` (ADR-0207).
  It reads and writes the broker only through the SDK, and keeps one table, `kafka_connection`, for a cluster's non-secret connection settings.
- **Operations.** Offset reset, configuration change, ACL change and transaction abort are gated
  operations with an `Effect` whose etag is the state key: a preview, a held confirmation, the approval
  gate, and an audit with before and after. An approved request checks its etag again when it runs.
- **Shared model.** A subscription's backlog carries its accuracy (exact, estimate, unavailable) with a
  reason and a per-partition breakdown, and is never shown as zero when unknown. Truncating a destination
  is a shared operation on any kind with the TRUNCATE verb.
- **Permissions**, named by role (ADR-0209): `subscription:seek`, `destination:truncate`,
  `destination:update`, `access-rule:read`, `access-rule:write`, `transaction:read`, `transaction:abort`.
  The operator role holds all but `access-rule:write`; the viewer the read permissions.

## Consequences

- Seeking is a distinct authority from purging: a role can rewind a group without emptying a queue.
- Any later system with a log (Pulsar, JetStream) reuses truncate and the backlog accuracy unchanged.
- `totalMessageCount` is replaced by `backlog`: a breaking change to the JSON.

## Alternatives considered

- **`subscription:write` and similar broad permissions**: would bundle a reversible pause with an
  irreversible reset.
- **A Kafka-only truncate**: the operation is a log's, not Kafka's.
