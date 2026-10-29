# ADR-0111: Plugins get beans scoped to them, and message through registrations Studio converges

- **Status**: accepted; amended by [ADR-0213](0213-a-registration-change-is-converged-under-the-cluster-lock.md): a registration change is converged before the call returns
- **Date**: 2026-09-24
- **Deciders**: Mahdi Amirabdollahi
- **Change**: `openspec/changes/plugin-messaging-and-secrets`
- **Builds on**: [ADR-0009](0009-secret-vaulting-and-broker-tls.md), [ADR-0062](0062-message-capture-is-a-divert-into-a-ring-bounded-queue.md), [ADR-0077](0077-capture-acknowledges-only-what-it-stored.md), [ADR-0079](0079-capture-objects-are-scoped-per-instance.md), [ADR-0099](0099-runtime-plugins-are-child-contexts-installed-from-the-ui.md)

## Context

A runtime plugin may not start threads (ADR-0099), so it cannot run a broker client of its own. It can reach only `@PluginApi` beans, which are singletons shared by every plugin: none of them can tell which plugin is calling. A plugin that wants to react to messages, send one, or keep a credential has nowhere to go.

Studio already solves the hard half for itself. Message capture installs a bounded, drop-oldest copy of a queue's traffic that never touches production consumers or producers. It names its objects after the Studio instance, converges them under a per-cluster advisory lock, and sweeps orphans at startup. `SecretVault` seals credentials with AES-GCM and binds each ciphertext to its row.

## Decision

1. **Scoped beans.** The host asks every `PluginScopedBeans` bean for the objects it binds to a plugin id and registers them in that plugin's context before it refreshes. A scoped object is a host object that knows its plugin's id and holds nothing of the plugin's. A plugin cannot name another plugin, because nothing it can call takes a plugin id.
2. **`PluginSecrets`** is scoped. Values are sealed by `SecretVault` with the AAD `plugin|<id>|<name>`. Writes and deletes are audited by name only, and purge deletes the rows. No interface returns a value.
3. **`PluginMessaging`** is scoped, and **declarative**. A plugin registers what it wants (a tap or a consumer on a queue, acting for a user) under a key of its own. Studio stores the registration, and a reconciler makes it true on every serving node, as capture's does. Deliveries go to the plugin's `PluginMessageHandler` bean through `runInPlugin`, on Studio's threads.
   - A **tap** is capture's mechanism under its own reserved prefix `artemis-studio.plugin.`: a non-exclusive divert into a non-durable, ring- and byte-bounded `DROP` queue, restricted to Studio's broker role.
   - A **consumer** is a `CLIENT_ACKNOWLEDGE` Core consumer on the queue. Anything but an accept ends in redelivery.
4. **Registrations act for a user**, whose grants are re-read on every pass: `message:read` for a tap, plus `queue:purge` for a consumer, and `message:send` for a send. Losing one suspends the registration with its reason. A disabled or uninstalled plugin's registrations stop and leave nothing on the broker, and purge deletes them.

## Consequences

- A plugin can automate on messages without a thread, a client or a credential store of its own. Crash recovery, failover, multi-instance and cleanup behave exactly as capture's do, because they are the same loop.
- The scoped-bean seam is general. Future per-plugin facilities use it instead of adding plugin-id parameters to shared beans.
- A registration takes effect on the next pass, not synchronously. The API reports per-node state rather than pretending otherwise.
- Taps need `artemis-studio.capture.broker-role`, as capture does.
- A blocking handler holds one Core session. Bounding it is the plugin's job.

## Alternatives considered

- **A plugin-id argument on shared beans.** Any plugin could pass another's id.
- **Letting plugins run their own clients on Studio-provided executors.** Studio would still have to own cleanup, failover and multi-instance behaviour, which is everything hard, while also exposing threads that pin classloaders.
- **Generalising `CaptureTap` in place.** Capture's names and settings match are parsed by the capture reconciler, which would treat plugin taps as its own orphans. A separate prefix keeps the two independent.
