# ADR-0212: The broker client is wired by OpenBroker's starter

- **Status**: accepted
- **Date**: 2026-10-27
- **Deciders**: Mahdi Amirabdollahi
- **Change**: `openspec/changes/19e2b-rahdar-on-the-openbroker-starter`
- **Builds on**: [ADR-0203](0203-rahdar-reaches-every-broker-through-openbroker.md), [ADR-0211](0211-broker-messaging-runs-on-the-sdk-client-and-recovery-is-observed.md)

## Context

Rahdar opens one OpenBroker connection per registered cluster, at runtime, and built every part of
its options by hand: the drivers, SSL-bundle TLS with reload, the interceptor, the clock, and an
OpenTelemetry observer wrapped so that it never writes into a message. OpenBroker `0.4.0` ships a
Spring Boot starter that provides these building blocks for applications that open connections at
runtime, records messaging metrics through Micrometer, and can trace sends without writing into the
message. Its other parts (connections from configuration or from Spring's Kafka and Artemis
settings, health per connection, a processor lifecycle) are made for applications whose brokers are
their own dependencies, not for a product that manages other people's brokers.

## Decision

Rahdar takes from the starter the drivers, each cluster's connect options, SSL-bundle TLS, the link
listener and the observers, with `openbroker.tracing.inject-context: false`, and removes its own
versions. Messaging metrics become Micrometer meters tagged with the cluster, and spans stay in
OpenTelemetry. Rahdar keeps, through one `ConnectOptionsCustomizer`:

- the live broker timeouts, which Settings changes without reconnecting (ADR-0010);
- identity context propagation, so a broker callback never runs with the context of the thread that
  started it, such as an operator's request that started a plugin consumer.

Rahdar turns off connections derived from Spring's settings and declares none in configuration;
cluster health and the plugin consumers' shutdown order stay Rahdar's.

## Consequences

- Less wiring code in `platform/broker`, and the SDK's own defaults and fixes arrive with each
  OpenBroker release.
- Messaging metrics reach Prometheus and OTLP with the rest of Rahdar's metrics (ADR-0147), under the
  names the OpenTelemetry observer used before.
- Rahdar depends on the starter's API for runtime connections; a change there is an OpenBroker
  release note to follow.
- A Spring `spring.kafka.*` or `spring.artemis.*` property never creates a hidden broker connection.

## Alternatives considered

- Keep the hand-built wiring: a second copy of what the SDK now ships, maintained apart.
- Adopt the whole starter, with clusters in configuration: clusters are registered in the product,
  and a managed broker must not decide Rahdar's readiness.
- The starter's Micrometer context propagation: it would carry an operator's identity into
  long-lived consumer threads.
