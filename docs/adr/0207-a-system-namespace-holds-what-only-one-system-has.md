# ADR-0207: A system namespace holds what only one system has

- **Status**: accepted
- **Date**: 2026-10-20
- **Deciders**: Mahdi Amirabdollahi

## Context

Some of Rahdar exists for Artemis alone: `broker.xml` configuration and its diff, setup review's rule
packs, divert creation, bridge credentials. With clusters of several systems (ADR-0203), those routes,
MCP tools and screens must not be offered for a cluster that cannot have them, and a shared screen must
never learn their words.

## Decision

- **REST.** A system's own routes live under `/clusters/{c}/{system}/…` (`/clusters/{c}/artemis/config`).
  One interceptor answers the standard not-found problem when the cluster runs another system, so a new
  system-only controller cannot forget the check.
- **Cluster views** carry `system {id, name}` and the cluster's `kinds` with their role, label and verbs,
  so a client knows what a cluster can do before it asks.
- **MCP.** A tool may name a system. It is offered only while the caller can read a cluster of that
  system, and a call naming a cluster of another system is a tool error that says which system it runs:
  `artemis_config`, `artemis_config_change`, `artemis_config_diff` and `setup_review`.
- **UI.** A navigation entry may name a system and is shown only for clusters of it; its routes render
  not-found on another system. A shared action a kind cannot perform is shown disabled with the reason
  ("Artemis addresses cannot be purged"), never hidden.

## Consequences

- Artemis-only work is visible as such in every URL and tool name.
- A new system's own features go in their own namespace without touching shared code.

## Alternatives considered

- **An annotation per controller**: works, but a forgotten annotation exposes a route silently.
- **Hiding system-only parts by capability**: capabilities say what a connection may do, not which
  product a cluster runs; the two would be confused.
