# ADR-0211: Broker messaging runs on the SDK's client, recovery is observed, and in doubt is an outcome

- **Status**: accepted
- **Date**: 2026-10-27
- **Deciders**: Mahdi Amirabdollahi
- **Change**: `openspec/changes/19e3-openbroker-messaging-client`
- **Amends**: [ADR-0112](0112-plugin-consumers-set-their-concurrency-on-a-thread-pool-of-their-own.md) D2 and D3, the slots and their prefetch
- **Builds on**: [ADR-0203](0203-rahdar-reaches-every-broker-through-openbroker.md)

## Context

Rahdar reached brokers through OpenBroker `0.1.0`, whose transport was incubating. Rahdar ran its own
receive loop for every plugin consumer (one receiver per unit of concurrency, each on its own thread), and
a lost connection ended the loop until the next reconcile pass restarted it. A send that lost its
connection before the broker confirmed it was reported as failed, which invites a resend that duplicates.

OpenBroker `0.3.0` (which includes `0.2.0`'s production messaging client) declares what every link
promises (`Guarantee`), runs handlers in lanes with flow control and ordering (`Transport.process`),
reopens a lost link on its node or on the member of its HA group now serving it (`Recovery`), reports
every link state change (`LinkListener`), marks an unanswered send or commit `Dispatch.IN_DOUBT`, and
watches messaging operations through an observer, with an OpenTelemetry one in
`openbroker-opentelemetry`. Each later driver (Kafka first) brings its own behaviour behind the same
contract, so Rahdar code written against the contract works there too.

## Decision

1. **Plugin consumers are SDK processors.** One processor per registration and serving node, with the
   registration's concurrency as lanes, `FlowControl.none()` (one unsettled message per lane, nothing
   read ahead), `OrderingScope.GROUP` and `RetryPolicy.system()`. Plugin `ACCEPT` is `Accept`; `RELEASE`
   is an uncounted `Release`; `REJECT` and a handler that throws are a counted `Release`
   (`deliveryFailed`), so the broker redelivers within its own `max-delivery-attempts` and then
   dead-letters. `Reject` is not used: on Artemis it dead-letters at once, which the plugin contract does
   not allow. A processor the SDK gives up on (`FAILED`) is dropped, and the next reconcile pass starts it
   again. A stop asked from inside a handler closes the processor on another thread, so it never waits on
   the handler that asked.
2. **Relay and capture declare the transacted guarantee.** They open `Guarantee.TRANSACTED` receivers. A
   relay keeps its own transacted sender on the target node, because a receiver's joined sender lives on
   the source node's session and cannot reach another node or cluster; its existing
   "record before the target commit, acknowledge after" order handles a target commit left in doubt. The
   SDK sends every transacted message durable and refuses an explicitly non-durable one, so a relayed
   message is sent with its durability unset: a non-durable message arrives durable, which is the safe
   direction (it survives a broker restart it would not have). A
   capture whose commit a recovery aborted, or left in doubt, carries on: its rows are stored, and the
   writer's redelivery guard keeps the broker's redelivery from storing them twice.
3. **Recovery is observed, not re-implemented.** The gateway watches each cluster's transport.
   `BrokerLinks` records every link's state, node, serving node and cause as the `brokerLinks` health
   contributor (and so in the support bundle), as the `rahdar.broker.links{state}` and
   `rahdar.broker.link.transitions{state}` metrics, and in the log. Owners name their links
   (`plugin <id>/<key>`, `capture <name>`, `relay <from> to <to>`).
4. **In doubt is an outcome.** A send that fails with `Dispatch.IN_DOUBT` is neither a success nor a
   failure. The REST send answers `202` with an `InDoubtView`, the MCP send tool says "In doubt.", a
   plugin's `send` throws `MessageInDoubtException`, and the audit records the outcome `IN_DOUBT`.
5. **Telemetry comes from the SDK's observer, without propagation.** `ConnectOptions.withObserver` gets
   `OpenTelemetryMessaging` over Rahdar's own `OpenTelemetry` bean, wrapped so it never writes into a
   message's properties. Rahdar moves, retries, relays and transfers messages other producers wrote; they
   must arrive as they left, and a `traceparent` written over the producer's would also cut the
   producer's own trace.

## Consequences

- A broker blip no longer stops plugin delivery or capture for a reconcile interval; handlers may see a
  message twice after one, which the plugin guide states.
- Plugin consumers, relays and captures behave the same on every system a driver supports, through the
  SDK's contract, with nothing written for Artemis.
- An operator never sees a send reported as done or failed when nobody knows which.
- The audit has a fifth outcome. Audit receivers and SIEM exports see `IN_DOUBT`.
- A processor's lanes reach the link listener as links named `<processor id>-<lane>`, which is the
  Artemis driver's naming and not part of the SDK contract. `BrokerLinks` names lanes after their
  processor by that convention. An SDK `LinkEvent` that names its processor would remove the
  assumption; that belongs in OpenBroker.
- Trace context does not travel with messages Rahdar sends. A consumer cannot link its span to the send
  that Rahdar made, which is the price of leaving messages untouched.
- No Rahdar path receives a `resent` receipt today: one-shot sends fail rather than resend, and the
  relay's sender is transacted. A future at-least-once sender reports one as a possible duplicate.

## Alternatives considered

- **Keep Rahdar's receive loop on top of SDK receivers.** It would duplicate lanes, flow control and
  recovery that the SDK owns and tests under its chaos TCK.
- **Map `REJECT` to `Reject`.** It dead-letters on the first rejection on Artemis, which breaks the
  plugin contract's "redelivered up to the broker's attempt limit".
- **A managed retry policy for plugins.** It would replace the broker's `max-delivery-attempts`, which
  the plugin contract names as the limit.
- **Answer an in-doubt send as a 5xx problem.** It reads as failed and invites a resend that duplicates;
  a `200` would overstate it. `202` with an explicit `inDoubt` says exactly what is known.
- **Propagate trace context on fresh sends only.** The observer is set per connection and cannot tell a
  fresh send from a move, so it would also stamp moved messages.
- **A diagnostics screen for links.** The health section of the support bundle and the actuator carry the
  same facts today; a screen can follow when Kafka's rebalances make links a daily concern.
