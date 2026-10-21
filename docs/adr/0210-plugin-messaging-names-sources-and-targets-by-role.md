# ADR-0210: Plugin messaging names sources and targets by role, and is contract 17

- **Status**: accepted
- **Date**: 2026-10-20
- **Deciders**: Mahdi Amirabdollahi

## Context

The plugin messaging records named a `queue` to read and an `address` to send to, with `address::queue`
for a direct send, and the approval gate's `Effect` called its precondition a `stateKey`. ADR-0203 keeps
OpenBroker types out of `@PluginApi`.

## Decision

- `RegistrationSpec`, `MessageRegistration` and `PluginMessage` name their `source` as a `ResourceRef`
  (a subscription by name); a delivered message carries a `MessageRef` (node and position).
- `OutboundMessage.target` is a `ResourceRef`: a destination, or a subscription for a direct send where
  the system supports it. `address::queue` is gone.
- `Effect(count, unit, etag, detail)`, after AIP-154.
- The TypeScript SDK's action targets are keyed by role.
- `Contract.VERSION` is 17; a plugin built for 16 is refused.

## Consequences

- Every plugin rebuilds for 17 and renames its fields; behaviour and delivery guarantees are unchanged.

## Alternatives considered

- **OpenBroker's own `ResourceId` and `MessageRef` in the contract**: it would tie the plugin API to the
  SDK's release cadence, against ADR-0203.
