# ADR-0203: Rahdar reaches every broker through the OpenBroker SDK

- **Status**: accepted; supersedes [ADR-0026](0026-core-client-connection-model.md), [ADR-0031](0031-pooled-core-connections.md) and [ADR-0098](0098-per-cluster-core-tls-through-an-sslcontextfactory.md); amends [ADR-0112](0112-plugin-consumers-set-their-concurrency-on-a-thread-pool-of-their-own.md) D5
- **Date**: 2026-10-20
- **Deciders**: Mahdi Amirabdollahi

## Context

Rahdar managed Apache ActiveMQ Artemis only, and Artemis reached into every layer:

- the Artemis client was imported by 18 classes in `platform/broker`;
- 13 modules sent raw MBean operation strings through a Jolokia client;
- Rahdar ran its own Core connection pool (`pooled-jms`, ADR-0031), its own notification
  subscriptions (ADR-0026) and its own `SSLContextFactory` for per-cluster TLS (ADR-0098).

Supporting a second broker this way would mean a second copy of each of those layers. ADR-0201
named the broker SDK OpenBroker. It is now a separate public project (`io.github.sudoitir:openbroker-*`
on Maven Central): a framework-free Java API with one vocabulary for every broker (resources by class
and kind, standard attributes and metric keys, operations with previews and etags, a message model, a
transport and notifications), a driver SPI, a TCK, and an Artemis driver as its first system.

## Decision

Rahdar reaches every broker through the OpenBroker API, and only through it.

- **One gateway.** `platform/broker` holds one `BrokerDrivers` and one `ClusterConnection` per
  cluster, opened with the cluster's own system (`cluster.system`). It supplies the SDK with Rahdar's
  credentials (`CredentialSource`), SSL bundles (`TlsProvider`) and a `CallInterceptor` that keeps the
  per-node limiter, the call guard, node call health, the clock offset and the `rahdar.broker.*`
  observations with their names and tags unchanged. A cluster deleted closes its connection.
- **Features use facets.** Resources and scrape, metrics (under the SDK's standard metric keys),
  queue lifecycle, browse, send and bulk message actions, triage, notifications (broker events),
  request-reply and flow sampling, capture and the SQL console, plugin messaging and the transfer
  relay all call SDK facets. The approval gate pins a queue or message action to the SDK's preview
  etag, which names the nodes that hold the resource and the incarnation each holds.
- **Artemis stays in one module.** `system/artemis` is the only code that imports the SDK's
  Artemis extension (`ArtemisManagement`, through `ClusterConnection.unwrap`). Features that exist
  only for Artemis (broker configuration, setup review, diverts and bridges, expiry and counter reset)
  call it; shared features never do.
- **No broker client in Rahdar.** Architecture rules hold that `org.apache.activemq..`,
  `org.jolokia..` and `jakarta.jms..` appear nowhere in Rahdar's main code, that the SDK's driver
  SPI is never used, and that only `system/artemis` knows the Artemis driver. The plugin validator
  refuses plugin packages under the SDK or any installed driver's client packages.
- **Released versions only.** Rahdar pins `openbroker.version` to a release; CI refuses a
  `-SNAPSHOT`. A fix the driver needs is a patch release of OpenBroker first.
- **Plugin delivery (amends ADR-0112 D5).** A plugin consumer's slots each drain an SDK receiver with
  no prefetch on a virtual thread of their own; the bounded platform thread pool and its setting
  `rahdar.plugins.messaging.max-threads` are gone. A registration's `concurrency` still bounds the
  slots, so ADR-0112's other decisions hold.
- **Capture (ADR-0077's rule, new means).** A capture drain reads through a transacted SDK receiver
  and commits only after the index stored the batch, so it still acknowledges only what it stored.

## Consequences

- A second broker is a second driver in OpenBroker plus, where it has features of its own, a
  `system/<id>` module in Rahdar. Shared features, the schema and the REST surface need no change for it.
- The SDK's driver chooses the channel for a call (Jolokia or Core for Artemis). Message and SQL
  responses no longer report which channel served them (`transport`, `servedBy` are removed).
- Broker events use the SDK's standard event types (`openbroker.consumer.attached`, …) or a
  system's namespaced native type.
- Rahdar's release depends on an OpenBroker release: a driver bug blocks Rahdar until a patch
  release is on Central. Dependabot raises the SDK's modules together.
- Rahdar no longer owns a Core pool, notification subscriptions or a TLS factory; their tests moved
  to OpenBroker's TCK and driver tests. Rahdar's wire recordings (`scripts/wire-compare.sh`) compare
  the broker traffic of its integration tests before and after the move.
- The REST API, MCP tools, permission identifiers and plugin contract still use Artemis's nouns
  (queue, address, divert). Renaming them is roadmap change 19d.

## Alternatives considered

- **Keep the Artemis code in Rahdar behind an internal interface.** Cheaper now, but every new
  broker would still be written inside Rahdar, and no other tool could use the drivers.
- **One Rahdar repository with the SDK as a Maven module.** Simpler to build, but the SDK's release
  cadence, compatibility promise and audience differ from Rahdar's, and a reactor would let Rahdar
  depend on unreleased SDK code.
- **Generic messaging APIs (JMS, AMQP 1.0) as the abstraction.** They cover sending and receiving
  only; management, metrics, HA state and topology, which are most of Rahdar, have no standard.
