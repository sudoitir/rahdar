# Kafka and RabbitMQ as first-class systems

## Why

Kafka and RabbitMQ clusters register, scrape and browse, but the screens operators reach for every day are still
Artemis-only, or they speak as if every broker were Artemis:

- Flow and the SQL console are hidden on Kafka and RabbitMQ.
- Alerts seed Artemis HA rules that never fire on these clusters, and offer a slow-consumer template these systems
  cannot answer. Cluster degraded reads Artemis live/backup pairs, so it never fires on them either.
- Capability remedies always say `broker.xml`. A failure without native details reads as "This Artemis version
  is not supported". The product names only Artemis.
- CI runs every real-broker test against both ends of Artemis's range, but Kafka and RabbitMQ only against the
  default image, inside the generic shards.

## What Changes

- **Flow** maps Kafka consumer groups on their topics, and RabbitMQ consumers, bindings, shovels and federation
  links. Producers are stated as not observable on Kafka.
- **The SQL console** queries Kafka topics and RabbitMQ streams live. The WHERE clause is evaluated in Rahdar within
  the existing scan limits. A classic or quorum queue is refused, with the reason. The message index stays Artemis's
  and is offered only on Artemis clusters.
- **Alerts** seed Cluster degraded on Kafka and RabbitMQ, and judge it by the system's own health. Rules that read what
  only Artemis reports are refused there, and listed as disabled with the reason. A backlog template is offered in
  each system's words.
- **Wording:**
  - A failure names the system of the cluster it happened on.
  - A remedy is headed `broker.xml` only for Artemis.
  - The tagline and the trademark notice name all three systems.
- **CI:** real-broker tests run per system, against the oldest and newest supported image of each.

## Impact

- Specs: `alerting`, `flow-visualization`, `sql-console`, `operator-ui`.
- Code:
  - `feature/alerting`, `feature/flow`, `feature/sql`
  - `system/kafka`, `system/rabbitmq`
  - `platform/broker/web`
  - the web features `alerting`, `flow`, `sql`, `settings`
  - `.github/workflows/ci.yml`
- Depends on ADR-0142 (both ends of the range in CI) and ADR-0203 (one connection per cluster).
