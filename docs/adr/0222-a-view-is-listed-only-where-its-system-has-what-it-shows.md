# ADR-0222: A view is listed only where its system has what it shows

- **Status**: accepted
- **Date**: 2026-11-01
- **Deciders**: Mahdi Amirabdollahi

## Context

Rahdar runs more than one broker system. Several views and row actions were written for Artemis and
listed for every cluster: the topology (management and Core URLs, HA pairs), routes, request-reply
tracing, dead letters, the SQL console, flow, transfers, and a Connections page. On a Kafka cluster,
which is reached by bootstrap servers and has no management URL, each of them showed Artemis words
("Management URL: None set", "Not polled: no management URL"), an empty page, or a link to a page that
answers not-found. An operator reads a missing or empty page as a product that cannot do the job, and
a page that cannot work as a lie.

## Decision

- A navigation entry names the system it is for (`system`), as before, or the resource class it lists
  (`resourceClass`). The rail, the command palette and the `g` shortcuts list it only on a cluster whose
  system is that one, or whose kinds include a kind of that class. Until the cluster has said what it
  has, such an entry is not claimed.
- A slot contribution may name a `system` too, in any cluster-scoped slot. Row actions, selection bars
  and metric panels for one system are not offered on another (`useClusterSlot`).
- A route for one system answers not-found on a cluster of another (`SystemGate`), as the API does.
- A cluster's own address opens the first view its system offers, so an Artemis cluster lands on its
  topology and a Kafka cluster on its brokers.
- A capability the system lacks and the operator could add stays in the view that needs it, disabled
  with its reason (ADR-0216). This decision is only about views that mean nothing for the system.

## Consequences

- A new system adds no entries to the views of another, and a new Artemis-only view must say so.
- The topology and the transfers are now Artemis-only. A Kafka cluster's brokers are on its Brokers view.
- A view that applies to every system but has nothing to show on one keeps its empty state; it is not hidden.

## Alternatives considered

- **Hide by a per-view capability probe**: it needs a round trip per view and cannot tell "this system
  has no such thing" from "this cluster is down".
- **Make the Artemis views system-aware**: the topology would need a second vocabulary for Kafka that
  the Brokers view already provides.
