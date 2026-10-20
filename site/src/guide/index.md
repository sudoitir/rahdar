---
title: What Rahdar is
description: Cluster-wide management and observability for Apache ActiveMQ Artemis — what it does, what it deliberately does not, and how it treats your brokers.
---

# What Rahdar is

Apache ActiveMQ Artemis ships with a console that manages **one broker at a
time** and has no idea a cluster exists. That is fine until the question you
have spans nodes — which it always does, because the interesting questions are
"which node is live", "where is the depth", and "where did that message go".

Rahdar is the other thing: **one instance across many clusters**, with
the cluster as the unit of everything.

It works against your **existing** brokers. There is no `broker.xml` rewrite
beyond enabling the management endpoints you almost certainly already run, and
nothing here starts a broker.

## What it does

| | |
|---|---|
| **Topology** | Live/backup pairs, replication state, shared-NodeID grouping, on one canvas. HA role is polled from every node on every cycle — never inferred from configuration. Two live in a pair is a critical split-brain alert. |
| **Cross-node resources** | Subscriptions, destinations, consumers, channels, connections and producers across every node in one virtualised table, attributed per node, updating over SSE. |
| **Message operations** | Browse, send, move, retry, expire, delete, purge — with a dry run that answers with the affected count without acting, a server-enforced bulk cap, and per-node outcome reporting. |
| **[SQL Console](/guide/sql-console)** | A restricted read-only SQL dialect over the messages in a cluster, including the body, with an optional retention-bounded index and a live tail. |
| **Request-reply tracing** | Requests correlated to their replies across addresses and nodes, with latency and timeout statistics against expectations you declare. |
| **Metrics and alerting** | Depth, throughput and consumer series in partitioned Postgres, with charts and rules that fire on what they measure. |
| **Governance** | Authentication on every endpoint, a dynamic role/permission model scoped global → environment → cluster, API tokens, optional OIDC/SSO, and an audit event for every mutating call. |
| **[MCP server](/guide/mcp)** | The same capabilities exposed to an assistant, under the same grants and the same audit trail. |

## Four rules it is built on

These are not aspirations; they are constraints the code is held to.

**Broker-friendly by construction.** Reads are batched — one Jolokia POST per
node, never one per queue — and tiered by how fast a thing actually changes,
behind a per-node rate limiter. Rahdar must never be the reason a broker falls
over.

**Safe by default.** Every destructive operation takes `?dryRun=true` and
returns the affected count without acting. Purge and delete are confirmed by
pressing and holding a button that names the action and its count. The audit event is written in
the same transaction as the command, *before* the broker call, and updated with
the outcome — so a crash mid-operation leaves a record that it was attempted.

**Never trust config for HA state.** Who is live is a question for the live
nodes, asked on every poll.

**Honest capability gating.** When a feature is unavailable because the
connection lacks a capability, the UI says so and shows the exact `broker.xml`
snippet that would enable it. Nothing is silently missing, and a capability that
has not been established *yet* leaves the control enabled with the uncertainty
stated — an absence of evidence is not evidence of absence.

## What it deliberately is not

- **Not a broker installer or configurer.** It manages brokers you run.
- **Not a message-queue client.** The SQL Console is `SELECT`-only and the live
  tail is a poll, never a consume; neither can mutate anything.
- **Not JMX.** Jolokia HTTP is the primary transport, with the Artemis Core
  client as the second channel for notifications and faithful message I/O.

## Screenshots

| Cluster topology | Cross-node queues |
|---|---|
| ![Live/backup topology with replication and shared-NodeID axis](/img/topology.png) | ![Every queue across every node in one virtualised grid](/img/queues.png) |

## Next

- [Quickstart](/guide/quickstart) — running in about a minute
- [Configuration](/guide/configuration) — the environment it needs
- [Architecture](/reference/architecture) and the [decision records](/reference/adr/)
