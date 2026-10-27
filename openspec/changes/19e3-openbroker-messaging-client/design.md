# Design: Rahdar on OpenBroker's messaging client

## Context

Rahdar used `openbroker-api`, `openbroker-support` and `openbroker-artemis` at `0.1.0`. This change raises
them to **`0.3.0`**, not `0.2.0` as first planned: `0.3.0` is on Central, its delta over `0.2.0` is small
(`SystemRetry`, `DeduplicationScope`, `RetryPolicy`, sealed `Credentials`, `KindLabel`), and the Kafka work
(19e4) needs it, so one migration replaces two. `openbroker-opentelemetry` is added at the same version.

| Change from `0.1.0` | Rahdar call sites |
|---|---|
| `ReceiveOptions` becomes `ReceiverOptions` | `PluginDrains`, `CaptureConsumer`, `BrokerRelay`, `BrokerMessages` |
| `withPrefetch` is gone; flow control is `FlowControl` | `PluginDrains` |
| `asTransacted` becomes `withGuarantee(Guarantee.TRANSACTED)` | `CaptureConsumer`, `BrokerRelay` |
| `new SenderOptions(boolean)` is removed | `BrokerRelay` |
| `Credentials` is sealed (`usernamePassword`, `None`) | `BrokerGateway`, test fixtures |
| `Kind` gains a `KindLabel` | test fixtures |
| `ConnectOptions.withObserver`, `Transport.watch`, `Transport.process` | `BrokerGateway`, `BrokerMessages` |

Facts checked against the `v0.3.0` sources:
- `Disposition.Reject` on Artemis dead-letters at once; a counted retry is `Release(empty, deliveryFailed=true)`.
- Processor lanes open synchronously, so a refused consumer (`MAX_CONSUMERS_REACHED`) fails `process()`.
- Under `RetryPolicy.system()` a handler that throws is settled as a counted release.
- A processor's lanes are links of their own, named `<processor id>-<lane>` by the Artemis driver.
- One-shot `Messages.send` fails rather than resends, so no Rahdar path gets a `resent` receipt.

## Goals / Non-Goals

**Goals:** every broker link on the SDK's declared guarantees; plugin consumers on SDK processors; recovery
visible; an unconfirmed send reported as in doubt; messaging traced without altering messages.

**Non-Goals:** a new system; a change to the plugin contract's existing types; a retry-policy UI; a
diagnostics screen for links.

## Decisions (ADR-0211)

### D1. Plugin consumers are SDK processors
`PluginDrains` opens one processor per registration and node through `BrokerMessages.process`: lanes =
concurrency, `FlowControl.none()`, `OrderingScope.GROUP` always (the contract keeps groups in order at any
concurrency), `RetryPolicy.system()`, a 30 s stop grace.

| Plugin outcome | SDK disposition |
|---|---|
| `ACCEPT` | `Accept` |
| `RELEASE` | `Release(empty, false)` |
| `REJECT`, or the handler throws | `Release(empty, true)` |
| none (the plugin is stopping) | `Release(empty, false)` |
| any outcome of a tap | `Accept` |

A processor in `FAILED` is dropped by `isRunning`, so the next pass restarts it. A stop asked from inside
a handler closes on another virtual thread.
- **Rejected:** Rahdar's loop over receivers (duplicates lanes, flow control and recovery); `REJECT →
  Reject` (dead-letters at once); managed retry (replaces the broker's attempt limit the contract names).

### D2. Relay and capture declare the transacted guarantee
Both open `Guarantee.TRANSACTED` receivers. The relay keeps a separate transacted sender on the target:
a joined sender lives on the source node's session and cannot reach another node or cluster. A target
commit in doubt is already settled by `TransferRunner`'s `beforeTargetCommit` record. Capture treats an
aborted or in-doubt commit as stored-and-redelivered, and skips a stale settle.

### D3. Recovery is observed
`BrokerGateway` watches each cluster's transport with `BrokerTelemetry.listener`, which feeds `BrokerLinks`
(and OpenTelemetry's recovery counter). `BrokerLinks` keeps each live link, the last 100 changes, gauges
`rahdar.broker.links{state}`, a counter `rahdar.broker.link.transitions{state}`, and is the `brokerLinks`
health contributor (DEGRADED while a link is not open). Owners name links through `BrokerGateway.label`;
a lane takes its processor's name.

### D4. In doubt is an outcome
`BrokerMessages.sendTo` turns a `BrokerException` with `Dispatch.IN_DOUBT` into `SendInDoubtException`.

| Surface | In doubt |
|---|---|
| REST `POST …/messages` | `202` with `InDoubtView { inDoubt: true, detail, node }` |
| Console send dialog | `notify.inDoubt`: "Not confirmed: sending …", assertive, stays; the form keeps its values |
| MCP send tool | `affected` 0 and a message starting "In doubt." |
| Plugin `PluginMessaging.send` | throws `MessageInDoubtException` (`@PluginApi`, additive) |
| Audit | outcome `IN_DOUBT` (baseline check constraint), "in doubt" on the screen and in the filter |

### D5. Telemetry from the SDK's observer, without propagation
`BrokerTelemetry` wraps `OpenTelemetryMessaging.of(openTelemetry)` so the carrier is dropped: Rahdar never
writes a property into a message. Without an `OpenTelemetry` bean it is `MessagingObserver.none()`.

## Risks / Trade-offs

- [Plugin consumer timing changes] → the plugin messaging real-broker tests run unchanged and pass.
- [Lane naming is the driver's, not the SDK contract's] → `BrokerLinks` relies on it only to name lanes; an
  SDK `LinkEvent` naming its processor would remove it (OpenBroker follow-up).
- [The SDK follows a failover while the reconciler moves the drain] → a brief restart, logged.
- [No trace context in sent messages] → a consumer cannot link to Rahdar's send span; messages stay intact.
