# ADR-0224: A cluster read as one is changed as one

- **Status**: accepted
- **Date**: 2026-11-01
- **Deciders**: Mahdi Amirabdollahi
- **Amends**: [ADR-0084](0084-queue-delete-removes-dependent-diverts-and-disconnects-consumers-on-request.md) for clusters read as one

## Context

The shared create, delete, purge and bulk paths were written for Artemis: they resolve a queue by name and run
once per node over its management URL (ADR-0084). A Kafka or RabbitMQ cluster has no management URL on its nodes;
its driver changes the cluster as a whole, by resource id. Through the shared routes such a cluster was refused
with "no node with a management URL", although its driver declares the verbs, and a RabbitMQ queue is named by its
virtual host and name, which a bare name cannot carry. `PagedListService` already reads such a cluster as one.

## Decision

- A cluster is **read as one** when none of its nodes has a management URL (`ClusterKinds.readAsOne`), the rule
  the paged lists already use. Kafka and RabbitMQ clusters are; Artemis clusters are not.
- On such a cluster the shared delete, purge and bulk paths take the resource id they already resolve and run the
  driver's whole-cluster operation (`WholeClusterOperations`): the dry run is the driver's preview (its estimate
  and its etag basis); the real run audits, previews again, compares the etag and executes, refused as stale if
  it changed since the preview.
- The gate types stay `subscription.delete`, `subscription.purge` and `destination.delete`, and their parameters
  carry the id, so an approval policy written for them holds on every system.

## Consequences

- Kafka topics and RabbitMQ queues and exchanges are deleted and purged from the shared screens.
- **Breaking**: the gated parameters gain `id`, and a held request from before the change cannot be approved;
  it is refused as changed and asked again.
- Artemis keeps its per-node fan-out and its partial outcomes.

## Alternatives considered

- **System-only routes for delete and purge**: duplicates the shared screens and lets approval policies silently
  not apply.
- **Teach the per-node path about vhosts**: a node fan-out has no meaning where the driver changes the cluster.
