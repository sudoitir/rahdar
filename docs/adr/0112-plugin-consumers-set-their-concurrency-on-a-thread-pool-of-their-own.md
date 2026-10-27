# ADR-0112: Plugin consumers set their concurrency, take no prefetch, and run on a thread pool of their own

- **Status**: accepted; D5 amended by [ADR-0203](0203-rahdar-reaches-every-broker-through-openbroker.md); D2 and D3 amended by [ADR-0211](0211-broker-messaging-runs-on-the-sdk-client-and-recovery-is-observed.md)
- **Date**: 2026-09-24
- **Deciders**: Mahdi Amirabdollahi
- **Change**: `openspec/changes/plugin-consumer-concurrency`
- **Amends**: [ADR-0111](0111-plugin-scoped-beans-and-plugin-messaging.md) — a registration states its concurrency, and plugin handlers no longer share the Core client's global threads
- **Builds on**: [ADR-0102](0102-the-plugin-api-is-published-to-central-and-npm.md)

## Context

ADR-0111 gives each plugin registration one `CLIENT_ACKNOWLEDGE` consumer per serving node. Every Core consumer Studio opens prefetches 64 KiB. A handler that calls a slow service therefore processes one message at a time, while the prefetched messages wait in Studio where no other consumer can take them.

The handler runs on a delivery thread of the Core client's global pool. Capture drains and operator sessions share that pool, which has `availableProcessors × 8` threads by default. Enough blocked plugin handlers can take all of them and stall capture on every cluster.

The broker already knows how to do the rest. A consumer with `consumerWindowSize=0` receives its next message only after it has taken the last one, so unsettled messages stay on the queue. Message grouping (`JMSXGroupID`) hands each group to one consumer. A Core connection factory with `useGlobalPools=false` gets its own thread pool, sized by `threadPoolMaxSize` (at least 2).

## Decision

1. **Concurrency is part of a registration.** `RegistrationSpec` gains `int concurrency`: 1 to 32 for `CONSUME`, exactly 1 for `TAP`. Any other value is refused with the reason, and the column carries the same check. `MessageRegistration` reports it. Registering a key again with another concurrency restarts its drains with the new count.
2. **One slot per unit of concurrency.** Studio opens `concurrency` sessions per serving node, each with one consumer. A drain starts all of its slots or none, and stops them together.
3. **Consumers take no prefetch.** Plugin consumers use `consumerWindowSize=0`, so at most `concurrency` messages per node are delivered and unsettled. The rest stay on the queue, and a slow plugin is handed fewer messages. Taps keep the bounded window, because their single consumer has to keep pace with a ring that drops.
4. **Order comes from the broker.** Messages of one group are handed to one consumer, which takes the next only after it settles the last. Studio documents this and adds nothing of its own. Order across a broker cluster's nodes needs the broker's grouping handler.
5. **Plugin handlers have threads of their own.** The plugin pools' connection factories set `useGlobalPools=false` and `threadPoolMaxSize` to `artemis-studio.plugins.messaging.max-threads` (64 by default, at least 2). There is one pool for taps and one for consumers on each node. Capture, operator sessions and every other Core client in Studio keep the global pool. Stopping a plugin pool closes its factory, which releases its threads.
6. **The contract version becomes 2.** Adding a record component changes the canonical constructors of two `@PluginApi` records, so the change is binary-incompatible. `Contract.VERSION` and the UI's `CONTRACT` become 2. The job that publishes the plugin API compares `Contract.VERSION` at the release with the previous release, and skips japicmp only when it was raised, which is the "deliberate break" path ADR-0102 names.

## Consequences

- A plugin gets parallelism and back-pressure with no executor or buffer of its own, and an unsettled message is never hidden from the queue's other consumers.
- A plugin that blocks costs its own registration and threads from the plugin pool. It cannot delay capture or an operator's browse or send.
- No prefetch costs one round trip per message. That is small next to the work of a handler that needs concurrency.
- `max-threads` bounds each of the two plugin pools on a node. When more handlers block than the pool has threads, the rest wait for a plugin thread.
- **BREAKING:** plugins built for contract 1 are refused until they are rebuilt against this release and pass `concurrency` (1 keeps today's behaviour).
- Any later change to a `@PluginApi` record's components is also a break, and goes through the same contract bump.

## Alternatives considered

- **A Studio-side executor fanning out from one consumer.** It buffers messages outside the broker, loses group affinity, and duplicates what the broker already does.
- **A second, overloaded constructor that keeps the old one.** It avoids the break, but leaves a constructor that silently picks a concurrency, and the project does not keep compatibility shims.
- **Keeping the global pool and raising its size.** It is JVM-wide, so capture is still starved, only later.
- **One plugin pool for taps and consumers.** The window is a connection-factory setting, so taps would lose their prefetch too.
