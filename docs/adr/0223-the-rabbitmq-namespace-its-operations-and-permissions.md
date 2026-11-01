# ADR-0223: The RabbitMQ namespace, its operations and its permissions

- **Status**: accepted
- **Date**: 2026-11-01
- **Deciders**: Mahdi Amirabdollahi

## Context

RabbitMQ brings screens no other system has (nodes with alarms and partitions, virtual hosts, bindings,
policies, permissions, shovels and federation links), and two things into the shared model: three queue types
with different verbs, and a guard that cannot tell a recreated resource apart, because its management API exposes
no incarnation id. Its driver (OpenBroker 0.9.0) manages through the management HTTP API and moves messages over
AMQP 0-9-1, and offers browse only on streams.

## Decision

- **Namespace.** `system/rabbitmq` and the web's `features/rabbitmq`, under `/clusters/{c}/rabbitmq/…`
  (ADR-0207). It reads and writes the broker only through the SDK. It keeps two tables: `rabbitmq_connection`,
  the cluster's non-secret connection settings (management URLs, AMQP endpoints, user name, TLS bundle, options),
  and `rabbitmq_health`, the verdict the scrape last formed, written only by the scrape.
- **Identity.** A cluster is claimed by its `cluster_name`, the only cluster identity the management API has. Two
  registrations of one name are refused, naming the holder; a renamed cluster turns health critical until its
  connection is checked and saved again.
- **Operations.** Virtual-host, binding, policy and permission changes, and deleting a shovel or federation link,
  are gated operations with an `Effect` whose etag is the driver's: a preview, a held confirmation, the approval
  gate, and an audit with before and after. The etag's basis is always `CONFIGURATION`, and the confirmation and
  the held request say so: a resource deleted and recreated with the same configuration before the change runs
  is not told apart.
- **Shared model.** Exchanges are destinations; classic, quorum and stream queues are subscriptions. A stream is
  read as a one-partition log, and its backlog is unavailable (a stream keeps its messages until retention
  removes them; its size is on its panel). Classic and quorum backlogs are estimates, because the management
  statistics are sampled.
- **Permissions**, named by role (ADR-0209): `namespace:read|write|delete` and `policy:read|write`;
  `access-rule:read|write` move from the Kafka module to the shared resource permissions, since two systems use
  them. Operators hold `namespace:read|write` and `policy:read|write`; `namespace:delete` (it destroys every queue
  in the virtual host) and `access-rule:write` stay with administrators.
- **Not offered.** Creating shovels, federation upstreams and users: they carry secrets the SDK cannot yet accept
  write-only. MCP offers RabbitMQ reads only.

## Consequences

- A RabbitMQ estate is on the same screens as Artemis and Kafka, with its own screens beside them.
- Team access patterns match a queue or exchange name in every virtual host of a cluster, until patterns gain a
  virtual-host dimension.
- A cluster rename needs an operator's confirmation, which is the price of having no stable id.

## Alternatives considered

- **Claim nothing for identity**: two registrations of one cluster would double its alerts and its audit trail.
- **A RabbitMQ-only delete and purge**: the shared buttons would be broken or hidden, and approval policies on
  `subscription.delete` would not hold for RabbitMQ (see ADR-0224).
