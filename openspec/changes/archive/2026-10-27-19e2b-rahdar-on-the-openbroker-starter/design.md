# Design: Rahdar on OpenBroker's Spring Boot starter

## Context
Rahdar opens one SDK connection per registered cluster, at runtime, in `BrokerGateway`. Today it builds every piece of `ConnectOptions` itself:
- `BrokerDriversConfiguration`: a `BrokerDrivers.load()` bean;
- `BrokerTls`: a `TlsProvider` over a named SSL bundle, with the per-cluster hostname flag and reload signal;
- `BrokerTelemetry`: `OpenTelemetryMessaging` wrapped so that no send writes into the message (ADR-0211), plus a link listener for `BrokerLinks`;
- `BrokerTimeouts`: a live `Timeouts` view of the Settings values (ADR-0010);
- the interceptor, the clock and identity context propagation.

OpenBroker `0.4.0`'s starter (ADRs 0026–0030 there) offers, for connections opened at runtime: a `BrokerDrivers` bean, `OpenBrokerConnectOptions.forConnection(name, system, tls)` and `linkListener(name, system)`, `SslBundleTls.forBundle(bundle, verifyHostname)`, ordered `ConnectOptionsCustomizer` beans, a Micrometer messaging observer, and `openbroker.tracing.inject-context`. It attaches neither health nor lifecycle to a connection it did not create. It derives connections from Spring Boot's Kafka and Artemis details unless `openbroker.spring-connections.enabled` is false.

## Decisions

### D1. Adopt what fits, keep what does not
| Piece | Decision | Why |
|---|---|---|
| Drivers | the starter's bean; `BrokerDriversConfiguration` removed | the same `BrokerDrivers.load()`, closed last |
| TLS | `SslBundleTls`; `BrokerTls` removed | the same behaviour, including the hostname flag and reload |
| Options | `OpenBrokerConnectOptions.forConnection(clusterId, system, tls)` | interceptor (`RahdarCallInterceptor` as the `CallInterceptor` bean), clock bean, observers |
| Observers | Micrometer metrics and OpenTelemetry spans from the starter; `WithoutPropagation` removed | `openbroker.tracing.inject-context: false` keeps ADR-0211's rule |
| Link listener | `BrokerTelemetry` joins `BrokerLinks`' listener with `linkListener(...)` | recoveries are counted by the starter's observers |
| Timeouts | kept: `BrokerTimeouts`, applied by a `ConnectOptionsCustomizer` | live Settings values; the starter's `openbroker.client.*` are fixed at start |
| Context propagation | kept as identity, by the same customizer | see D2 |
| Health, processors | kept | a managed broker must not decide Rahdar's readiness; plugin drains stop in `ShutdownPhases` |
| Configured or derived connections | off: `openbroker.spring-connections.enabled: false`, no `openbroker.connections` | clusters are registered in the product |

### D2. Broker callbacks never carry the caller's context
The starter propagates Micrometer's context snapshot into broker callbacks by default. In Rahdar, a plugin consumer or relay is often started from an operator's request; a snapshot taken then would carry that operator's security context and observation into a long-lived consumer thread. Rahdar keeps identity propagation, so a callback runs with no caller context.

### D3. Metrics through Micrometer
With Micrometer present, the starter records `messaging.client.*`, `messaging.process.duration` and `openbroker.messaging.link.recoveries` as meters tagged with the cluster id and system, and the OpenTelemetry observer records spans only. They go to Prometheus and OTLP with the rest of Rahdar's metrics (ADR-0147), under the names the OpenTelemetry observer used before.

### D4. ADR
ADR-0212: "The broker client is wired by OpenBroker's starter" (D1–D3). ADR-0211's rule that telemetry never alters a message stands.

## Risks
- **A cluster option a driver does not declare is now refused on connect.** Rahdar builds `ClusterSpec`s without options; a test covers it.
- **The clock bean now reaches the SDK.** It is the system UTC clock outside tests.
