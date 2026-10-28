# ADR-0214: Kind labels come from the broker SDK

- **Status**: accepted
- **Date**: 2026-10-28
- **Deciders**: Mahdi Amirabdollahi

## Context

`ResourceIds.label` sentence-cased a kind id's local part, and the web built plurals in English. That reads
right for `artemis.queue` and wrong for `kafka.group-topic` ("Group-topic"), and it puts words about a
system into Rahdar. OpenBroker's kinds carry a `KindLabel` with a singular and a plural (its ADR on kinds
carrying a label).

## Decision

Every label Rahdar shows for a kind is the driver's `KindLabel`, sent as `{singular, plural}` in
`KindView` and in every view that names a kind. `ResourceIds.label`, `kindLabelOf` and the web's `plural()`
are removed.

## Consequences

- Kafka rows read "Group on topic", "Share group on topic"; a refusal for many says "Groups on topics".
- Views that carried `kindLabel` as a string carry an object: a breaking change to the JSON.

## Alternatives considered

- **A label table in Rahdar**: duplicates the driver and drifts from it.
