# ADR-0226: A Kafka kind does not run the Artemis queue lifecycle

- **Status**: accepted
- **Date**: 2026-11-13
- **Deciders**: Mahdi Amirabdollahi
- **Amends**: [ADR-0225](0225-kafka-topic-and-group-lifecycle.md) for a group on a topic

## Context

The shared subscription route was written for an Artemis queue. On a Kafka cluster that same route still offered a queue form, and create, pause, resume, update, reset-counters and delete of a group on a topic (`kafka.group-topic`) called the queue lifecycle. A group on a topic is a subscription in the permission model, and the driver can purge it and seek it, but it cannot be created by hand. Deleting the row through the queue lifecycle would remove an Artemis queue, or, if the Kafka delete were used with member removal, kick the group's members. Empty-group delete already lives on the group page and does not remove members. Topic create, topic delete, a partition increase, and that empty-group delete are the four actions [ADR-0225](0225-kafka-topic-and-group-lifecycle.md) keeps.

## Decision

- The shared subscription route refuses a group on a topic. Create, pause, resume, update, reset-counters, and delete of `kafka.group-topic` return `unsupported-on-kind` and do not call `QueueLifecycleService`. A group-on-topic delete does not remove members.
- Topic create, topic delete, a partition increase, and empty-group delete stay on the ADR-0225 paths. Those paths still must not call `QueueLifecycleService`.
- The subscriptions page on a Kafka cluster is headed by the driver's plural, "Groups on topics", with columns for the group, the topic, the lag and the members. The create control stays visible and disabled. Purge, offset reset, and delete of an empty group on the group page stay.
- When a cluster has one destination kind, and that cluster is not Artemis or RabbitMQ, the destinations page and its create control use the kind label. On Kafka that is "Topics" and "New topic". The topic form stays the Kafka create slot.
- Permissions stay the role words that already exist: `subscription:create`, `subscription:delete`, `subscription:pause`, `subscription:update`, `subscription:purge` and `subscription:seek`. No new permission names.

## Consequences

- An operator cannot create a group on a topic, and cannot pause, resume, update, reset its counters, or delete one from the subscriptions row. A group appears after a committed offset or a live assignment; a group with neither stays on Consumer groups.
- A caller that posts `kafka.group-topic` to `POST /subscriptions`, or that deletes, pauses, resumes, updates, or resets counters on one, is refused before any queue is written.
- Topic create, topic delete, a partition increase, and empty-group delete keep the preview, the confirmation, and the audit ADR-0225 already requires, and they do not go through the queue lifecycle.
- Artemis and RabbitMQ keep the destination labels they already show.

## Alternatives considered

- **Refuse every Kafka kind on the shared routes, including topic delete and empty-group delete.** Those actions are the ones ADR-0225 keeps. Returning `unsupported-on-kind` for them would close the paths the operator already confirms.
- **Create and delete a group on a topic through the driver, without removing members.** The driver refuses create, and delete of one topic's offsets is not what the row was doing. Empty-group delete is already on the group page.
- **A new permission for the Kafka row.** The actions are still create, delete, pause and update of a subscription. New names would split policies that already say who may do those things.
