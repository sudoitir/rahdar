## How to run this change
Run it in the session that delivers OpenBroker's Spring Boot starter, once OpenBroker `0.4.0` is on Maven Central:

1. Work in a git worktree on a new branch off `origin/main` (`.claude/rules/00-workflow.md`), and remove it after the merge.
2. Read this proposal, `design.md`, the `core-transport` and `telemetry-export` capabilities, ADR-0010, ADR-0147 and ADR-0211, and OpenBroker's starter ADRs (0026 to 0030, in its repository).
3. `/opsx:apply` inline. Run the narrow tests the change touches; CI runs the rest.
4. PR, merge on green CI and an empty Sonar list, `/opsx:archive`.

## Why
Rahdar wires the broker SDK by hand: it loads the drivers, builds every connection's options, adapts Spring SSL bundles into the SDK's TLS, and wraps the OpenTelemetry observer so it never writes into a message. OpenBroker now ships a Spring Boot starter that provides exactly these building blocks for applications that open connections at runtime. Using it removes Rahdar's copy, and gives broker messaging metrics through Micrometer, where the rest of Rahdar's metrics already go (ADR-0147).

## What Changes
- Rahdar builds against OpenBroker `0.4.0` and its Spring Boot starter.
- The starter provides the drivers, each cluster's connect options (interceptor, clock, observers, defaults) and SSL-bundle TLS. Rahdar's own versions are removed.
- Broker messaging metrics are Micrometer meters tagged with the cluster, exported with the rest of Rahdar's metrics; spans stay in OpenTelemetry. Trace context is still never written into a message, now through the starter's setting.
- What stays Rahdar's, because the starter's version does not fit a product that manages other people's brokers:
  - the live broker timeouts, which operators change in Settings without reconnecting (ADR-0010);
  - running broker callbacks without the caller's thread context, so a plugin consumer started from an operator's request never runs with that operator's identity;
  - cluster health and the plugin consumers' shutdown order.
- Clusters stay registered in the product: the starter creates no connection of its own, even when `spring.kafka.*` or `spring.artemis.*` is set.
- Connecting refuses cluster options the driver does not declare (OpenBroker `0.4.0`); Rahdar passes none.

## Capabilities
### Modified Capabilities
- `core-transport`: messaging metrics are recorded once per cluster, and broker callbacks never carry the caller's context.

## Out of scope
- Moving cluster registration into configuration.
- The starter's health indicators and processor lifecycle.

## Depends on
- 19e2-openbroker-spring-boot (OpenBroker `0.4.0` with the starter, on Maven Central)
- 19e3-openbroker-messaging-client

## Execution
**Inline**: a wiring change in `platform/broker`.

## Impact
`pom.xml` (OpenBroker `0.4.0`, the starter), `platform/broker` (`BrokerDriversConfiguration`, `BrokerTls` and the observer wrapper removed; `BrokerGateway` and `BrokerTelemetry` simplified), `application.yml`, one ADR.
