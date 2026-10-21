# Design: Rahdar on OpenBroker's messaging client

## Context

Rahdar uses `openbroker-api`, `openbroker-support` and `openbroker-artemis` at `0.1.0`. OpenBroker `0.2.0` breaks the transport API in these places:

| Change in `0.2.0` | Rahdar call sites |
|---|---|
| `ReceiveOptions` becomes `ReceiverOptions` | `PluginDrains`, `CaptureConsumer`, `BrokerRelay`, `BrokerMessages` |
| `withPrefetch` is gone (flow control is `FlowControl`) | `PluginDrains` |
| `asTransacted` becomes `consume().withGuarantee(Guarantee.TRANSACTED)` | `CaptureConsumer`, `BrokerRelay` |
| `new SenderOptions(boolean)` is removed | `BrokerRelay` |
| `SendReceipt` gains `resent` | the send paths in `BrokerMessages` and `MessageService` |
| `Transport`, `Sender` and `Receiver` gain methods | Rahdar's test doubles, if any |

`PluginValidator` keeps OpenBroker types out of plugins, so the plugin contract is Rahdar's own (ADR-0203, ADR-0210).

## Goals / Non-Goals

**Goals:**
- Every broker link on the SDK's declared guarantees.
- Plugin consumers on SDK processors instead of Rahdar's loop.
- Recovery made visible.

**Non-Goals:**
- A new system.
- A change to the plugin contract.
- A retry-policy UI.

## Decisions

### D1. Plugin consumers become SDK processors
`PluginDrains` opens one processor per serving node, with these settings:
- lanes = the registration's concurrency;
- `OrderingScope.GROUP` when the registration orders by group, `NONE` otherwise;
- an unsettled bound of one message per lane.

The plugin's acknowledge, reject and release map to the SDK's dispositions. The retry mode is the system's, so Artemis keeps its own delivery-attempt limit and dead-letter address, as the plugin contract states.
- **Rejected: keeping Rahdar's loop on top of a `Receiver`.** It would duplicate lanes and flow control that the SDK now owns and tests under chaos.

### D2. Relay and capture are transacted by guarantee
`BrokerRelay` and `CaptureConsumer` open receivers with `Guarantee.TRANSACTED` and use the receiver's joined sender for the target. This keeps the existing "commit on target before acknowledging on source" order.

### D3. Recovery is observed, not re-implemented
Rahdar registers a link listener per transport. It records state changes with their serving node in the diagnostics store and as metrics. In-doubt receipts reach callers as an in-doubt outcome (a 202-style "unknown" in the message send API, and the audit outcome `IN_DOUBT`).

### D4. Telemetry from the SDK's observer
When telemetry export is on, `ConnectOptions.withObserver` uses `openbroker-opentelemetry` with Rahdar's `OpenTelemetry` instance, replacing any hand-made spans around sends and receives.

### D5. ADR
One ADR: "Broker messaging runs on the SDK's client, and recovery is observed".

## Risks / Trade-offs

- [Behaviour of plugin consumers changes subtly (prefetch, redelivery timing)] → The existing plugin messaging tests stay as they are and must pass unchanged. A difference is a defect, not a test edit.
- [japicmp flags the plugin contract] → No `@PluginApi` type changes. If one must, `Contract.VERSION` is bumped and the commit says so.
