# ADR-0026: Core protocol client — one subscription per live node, poll loop, Studio-driven reconnect

- **Status**: superseded by [ADR-0203](0203-rahdar-reaches-every-broker-through-openbroker.md)
- **Date**: 2026-09-04
- **Deciders**: Mahdi Amirabdollahi

## Context

Phase 4 adds the second broker channel ADR-0002 deferred: the Artemis Core
client (`artemis-jakarta-client`), consuming `activemq.notifications`.
Phase 0's `NotificationSpikeIT` captured how it behaves against the versions
this repo pins (client 2.56, broker 2.44) and `docs/broker-management-notes.md`
§7–§8 records the traps:

- A JMS `MessageListener` on the notification consumer **deadlocks against
  `close()`** with this client/broker pairing.
- The broker pushes its cluster topology to CORE clients and advertises the
  `<connector>` hosts (compose service names); a client that cannot resolve
  those logs `AMQ214033` and blocking calls fail with `AMQ219016`.
- `activemq.notifications` needs `consume` **and** `createNonDurableQueue` for a
  Core subscriber; `NotificationActiveMQServerPlugin` is off by default and is
  what emits connection/session/delivered/expired events.

Notifications are per broker, and HA state is polled, never configured
(non-negotiable #4): the set of subscriptions has to track "who is live now".

## Decision

We will run the Core client as follows.

- **One subscription per serving node.** `CoreSubscriptionManager.reconcile`
  runs at the end of every tier-A scrape, per cluster, on the scheduler's
  virtual-thread pool, never inside a transaction. The desired set is
  `endpoints.filter(live).filter(has a dialable Core URL)`; a node that stops
  serving has its subscription closed, a node that becomes live gets one — so a
  failover is *followed*.
- **Poll, never a `MessageListener`.** `CoreEventClient` owns a virtual thread
  running `consumer.receive(250)` in a loop. The constraint is a workaround, not
  a style choice; the comment must survive refactoring.
- **`useTopologyForLoadBalancing=false`, `initialConnectAttempts=1`,
  `reconnectAttempts=0`.** Studio drives its own reconnect with an exponential
  backoff (1s → 5m, jittered). A bad connect fails fast instead of wedging a
  caller against an unresolvable advertised host.
- **The subscription sits outside `NodeCallLimiter`.** The limiter is a
  per-second *call* bucket; a long-lived subscription is not a call. Connect
  *attempts* are bounded by the backoff instead.
- **The notification capability is a cached verdict, not a live probe.**
  `CapabilityProbe.assessNotifications` reads
  `CoreSubscriptionManager.verdictFor(clusterId)` — it opens no connection, so
  `GET /clusters/{id}` never pays a TCP handshake. The verdict is `AVAILABLE`
  (subscribed on ≥1 node), `UNAVAILABLE` with a kind-specific `broker.xml`
  snippet (permission refused → security-setting; no Core URL → acceptor), or
  `UNKNOWN` only until the first scrape cycle completes.
- **The CORE credential defaults to the Jolokia credential.**
  `BrokerConnections.coreSettingsFor` tries `kind='CORE'`, then
  `kind='JOLOKIA_BASIC'`, then anonymous. A real CORE row, when supplied at
  registration, is sealed separately (`SecretVault` AAD is `clusterId|CORE`).
- **The test suite boots a real broker.** `support/ArtemisIntegrationTest` runs
  a process-wide singleton `GenericContainer("apache/activemq-artemis:2.44.0")`
  with the dev fixture `broker.xml` mounted, same pattern as
  `PostgresIntegrationTest`. `NotificationSpikeIT` becomes `CoreEventClientTest`,
  no longer `@Disabled`. No new dependency.

## Consequences

- Failover works for notifications the same way it does for HA state — from
  observation, not config.
- An idle broker and one missing `NotificationActiveMQServerPlugin` are
  indistinguishable by event absence, so `AVAILABLE` still ships the plugin
  snippet and says why (non-negotiable #5).
- The cached verdict can lag a just-fixed `broker.xml` by up to one tier-A
  interval. Acceptable, and the reason text is phrased as "as of the last probe".
- The `MessageListener` prohibition is a landmine for a future contributor;
  ADR + code comment both call it out.
- A container per CI run for the Artemis-backed tests. They are named `*Test`
  (not `*IT`) so surefire runs them, matching this project's existing
  container-test convention.

## Alternatives considered

- **A dedicated Core scheduler** — a second component that has to learn which
  nodes are live, duplicating tier A.
- **Rely on the client library's reconnect** — it blocks callers and retries
  against the unresolvable advertised host.
- **Persist a capability row** — more schema and a staleness policy for a value
  the subscription manager already holds in memory.
