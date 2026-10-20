# ADR-0031: Pooled Core connections via `pooled-jms`, superseding connect-per-call

- **Status**: superseded by [ADR-0203](0203-rahdar-reaches-every-broker-through-openbroker.md)
- **Date**: 2026-09-04
- **Deciders**: Mahdi Amirabdollahi

## Context

`CoreMessageTransport.open()` (ADR-0029) built an `ActiveMQConnectionFactory`,
opened a `Connection`, started it, created a `Session`, and tore all three down
again — per call. That is correct for an operator clicking Browse: rare enough
that connection setup cost is invisible. Phase 5's `RrSampler` calls the same
transport every few seconds for every traced address, on every enabled
expectation. Connect-and-tear-down at that cadence is real load on top of what
Phase 2's Jolokia tiers and Phase 4's notification subscriptions already put on
each node.

## Decision

**Pool Core connections per `(clusterId, coreUrl)`** in a new
`broker/core/CorePool`, using `org.messaginghub:pooled-jms`'s
`JmsPoolConnectionFactory` wrapped around the existing
`CoreConnectionFactory.build(...)` delegate. `CorePool.borrow(...)` returns a
`PooledSession` (a pooled `Connection` plus a fresh `AUTO_ACKNOWLEDGE`
`Session`) whose `close()` returns the connection to the pool rather than
tearing down the socket. `CoreMessageTransport` and `RrSampler` both borrow
from the same pool instead of building their own factories.

`pooled-jms` is added versionless — Spring Boot 4.1.0's dependency-management
BOM pins it (3.2.2), the same pattern every other dependency in this project
follows. No hand-rolled pool: connection pooling with idle eviction and
concurrent-session handling is exactly the kind of infrastructure code ADR-0002
already said to prefer a maintained library for over writing it in-house.

**Extends `core-transport`'s existing release requirement.** The
`core-transport` spec already requires Core connections to close on cluster
removal and on application shutdown; that requirement now targets the pool.
`CorePool.forget(clusterId)` is wired into `ClusterService.delete` alongside
the existing `CoreSubscriptionManager.forget` call, and `@PreDestroy` stops
every pool on shutdown.

## Consequences

- The sampler's per-tick cost is a session borrow, not a TCP handshake and JMS
  handshake; the notification subscription and the sampler no longer compete
  for connection-setup cost on a busy cluster.
- `CoreMessageTransport`'s public behaviour (browse/send fidelity, deep-page
  fallback to Jolokia) is unchanged — only how it obtains its session.
- One more component's lifecycle to release on cluster removal; already wired
  into the existing removal path, not a new one.

## Alternatives considered

- **Keep connect-per-call, rely on the sample interval being long enough** —
  rejected: pushes a real cost onto every tick indefinitely rather than paying
  it once per pool lifetime, and does not scale as more addresses are traced.
- **Hand-write a connection cache** (a `Map<String, Connection>` with manual
  eviction) — rejected per ADR-0002's preference for a maintained library;
  `pooled-jms` already handles concurrent session limits and stale-connection
  eviction correctly, which a first cut of hand-rolled pooling routinely gets
  wrong.
