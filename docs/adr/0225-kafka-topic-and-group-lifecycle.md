# ADR-0225: Kafka topic and group lifecycle is one preview, one refusal, one audit

- **Status**: accepted
- **Date**: 2026-11-12
- **Deciders**: Mahdi Amirabdollahi

## Context

An operator on call creates a topic, deletes one, raises its partition count, or deletes an empty consumer group. Those four actions are reachable from the destinations screen, the group screen, the HTTP commands, and MCP. A second door that applies under a freshly read etag, or that removes group members to force a delete, would let the same action mean two different things. The driver already previews and refuses (an internal topic, a shrink, a replica assignment, a group that still has members). Rahdar has to keep the operator's confirmation, the approval gate, the bulk safety cap, and the audit on top of that preview.

## Decision

- The four actions call one service. The console, the `/clusters/{id}/kafka/` commands, MCP (`dryRun` defaults to true), and the shared destination and subscription routes all use it. The shared routes cannot apply a Kafka topic or group under an etag they just read, and a group delete on that path has no way to remove members.
- Permissions stay the ones that already exist: `destination:create`, `destination:delete`, `destination:update`, and `subscription:delete`. The gate ids are `destination.create`, `kafka.topic.delete`, `destination.increase-partitions`, and `kafka.group.delete`. `destination.delete` is already the address-delete operation, and two gates cannot share a name.
- A preview changes nothing. Apply, including an approval that is released later, checks the preview's etag again. Deleting a topic also checks the groups and the record estimate the operator was shown. A mismatch changes nothing.
- The record estimate is what the bulk safety cap counts. Over the cap, the action is refused unless the operator overrides it, and that override is in the audit with the named groups, the estimate, and both partition counts.
- A Kafka command returns one outcome, named `whole cluster`. Artemis keeps its per-node outcomes.
- The create form asks for the name, the partition count, the replication factor, and the five settings the topic kind already declares. Confirm is the existing press-and-hold. A control the operator cannot use stays visible and says why.

## Consequences

- Internal topics, a shrink, a replica assignment, and a group with members are refused before the broker is asked to create, delete, or add partitions. The driver owns those refusals.
- There is no new permission, no new scrape, and no new stored table.
- A read-only token is refused before a preview or a hold.

## Alternatives considered

- **Let the shared whole-cluster delete apply under its own fresh etag.** It would delete a topic the operator had not confirmed, and it would not name the groups the preview showed.
- **A new permission for each Kafka action.** The actions are create, delete, and update of a destination, and delete of a subscription. New names would split policies that already say who may do those things.
