# ADR-0219: Cluster registration is per system, on its own page

- **Status**: accepted
- **Date**: 2026-10-28
- **Deciders**: Mahdi Amirabdollahi

## Context

Registration was built for Artemis: a modal asked for Jolokia seed URLs, a management account and a Core
account, and the gateway assumed every cluster ran Artemis. A Kafka cluster is reached through bootstrap
servers with a security protocol and a SASL mechanism, and its most common failure is invisible to a
single-endpoint check: the bootstrap server answers, but a broker advertises an address Rahdar cannot reach.
The result of a check (every broker, its reach, the version, what the account may do, what the cluster
lacks) does not fit a modal, and an operator cannot link a colleague to one.

## Decision

- The registration request carries the cluster's `system` and a `connection` shaped by it, a sealed union
  (`artemis`: seed URLs and accounts; `kafka`: bootstrap servers, protocol, mechanism, credentials, TLS
  bundle). A system Rahdar does not load, or a connection for another system, is `400 unsupported-system`.
- The cluster's system picks its driver end to end: the gateway asks the system's `ConnectionOptions`
  extension for connect options, and `GET /systems` lists the loaded drivers for clients.
- Registration is the page `/clusters/new?system=<id>`, outside a cluster: choose the system, enter its
  connection, check it, then name and register. Every entry point links to it.
- The check persists nothing and reports every node with its advertised address and reach, the version (a
  lower bound where the system cannot say more), the quorum, the access probe and the capabilities the
  cluster lacks; a failure names its cause and the next action. Register waits for a passing check on the
  exact inputs. A partly reached cluster may be registered after a warning.

## Consequences

- A new driver appears in the system choice with no UI work beyond its connection form.
- The registration JSON is a breaking change for API clients: they send `system` and `connection`.
- Seed and bootstrap fields parse every token, which also fixes an Artemis form that kept only the first.

## Alternatives considered

- **Overload the Jolokia form**: a Kafka cluster is not a list of seed URLs; every field would need a
  system condition.
- **A modal wizard**: too cramped for per-broker results and not linkable.
