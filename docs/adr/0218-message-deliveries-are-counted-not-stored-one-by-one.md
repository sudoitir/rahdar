# ADR-0218: Message deliveries are counted, not stored one by one

- **Status**: accepted
- **Date**: 2026-10-29
- **Deciders**: Mahdi Amirabdollahi

## Context

[ADR-0026](0026-core-client-connection-model.md) retains every
notification as a `broker_event` row, and [ADR-0028](0028-broker-event-persistence.md) writes
them in batches. A message delivery is the one notification sent per message. On the live QA stack two hours
of Artemis demo traffic wrote 1,123,903 `openbroker.message.delivered` rows (1.1 GB) while every other type
stayed under 1,000. Retention is time-only (72 hours, hourly purge), so the table is bounded in time and not
in rows: at that rate it holds tens of millions of rows. The only reader of delivered notifications is
request-reply tracing, which takes them live from the notification stream and never reads stored rows.

## Decision

We will not store a delivery as a row of its own. `BrokerEventWriter` counts delivered notifications in
memory per cluster, node, destination and subscription, and each flush writes one `openbroker.message.delivered`
summary row per key that saw deliveries, with `count`, `from` and `to` in its data. Rows grow with the active
destinations and the flush interval, never with traffic. Every other type is still stored verbatim, and the
live stream consumers (request-reply tracing) still receive each notification.

This supersedes the "retained verbatim" clause of ADR-0026 for this one type.

## Consequences

- The Events page shows a delivered row as a count over an interval, not a message.
- A burst of N deliveries is O(keys) rows. A restart loses the counts of at most one flush interval.
- A type that is chatty per message in future is handled the same way, not stored per message.
