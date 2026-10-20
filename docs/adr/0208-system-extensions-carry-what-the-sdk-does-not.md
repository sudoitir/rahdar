# ADR-0208: System extensions carry what the SDK does not

- **Status**: accepted
- **Date**: 2026-10-20
- **Deciders**: Mahdi Amirabdollahi

## Context

ADR-0203 moved every data path onto OpenBroker and left some shared features setting up Artemis
machinery through `system.artemis`: message capture and plugin taps, transfer staging and its
acceptance probe, flow's route sampling, and queue, address and divert lifecycle. Lifecycle is plain
SDK work (`Operations` on a kind), but a tap, a staging area, an acceptance check and a route sample
are Rahdar compositions that each system builds differently.

## Decision

- Rahdar's per-system interfaces live in `platform.broker` and extend `SystemExtension`, which names its
  system: `MessageTaps`, `TransferStaging`, `TransferAcceptance`, `FlowSampler`, and the existing
  subscription counters, message expiry and dead-letter destinations. Each system implements them in
  `system.<id>`; `SystemExtensions` picks the one for a cluster's system.
- Lifecycle runs on `Operations.preview` and `execute`, and routing reads on `Resources`, with no extension.
- Only `system.artemis`, `app`, broker configuration and setup review reach `system.artemis`;
  `BoundaryRulesTest` enforces it.
- A cluster whose system has no implementation answers the feature as unavailable, with the reason.

## Consequences

- A new system adds implementations, not branches in shared features.
- Wire traffic is unchanged for the moved code; lifecycle on the SDK adds per-node reads before it acts.

## Alternatives considered

- **Taps and staging in OpenBroker**: they are Rahdar's product policy (names, ring sizes, provenance),
  not a broker vocabulary every SDK user needs.
