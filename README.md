<div align="center">

<img src="web/public/favicon.svg" alt="" width="96" height="96">

# Rahdar

<sub><em>Rahdar</em> (Persian راهدار, "road-keeper") is <em>rah</em> (route) + <em>dar</em> (keeper): it keeps every route your messages travel open and safe.</sub>

**One console for every Apache ActiveMQ Artemis cluster you run.**

Live topology, every queue on every node in one table, message flow you can watch,
safe message operations, and SQL over your messages — all from a single instance.

<a href="https://rahdar.dev/"><img src="https://img.shields.io/badge/Play%20the%20story-Find%20the%20backed--up%20queue%20across%208%20brokers-0b7285?style=for-the-badge&labelColor=0b1418&logo=data:image/svg%2Bxml;base64,PHN2ZyB4bWxucz0iaHR0cDovL3d3dy53My5vcmcvMjAwMC9zdmciIHZpZXdCb3g9IjAgMCAyNDAgMjQwIiByb2xlPSJpbWciIGFyaWEtbGFiZWw9IlJhaGRhciI+PHBhdGggZmlsbD0iIzBjODU5OSIgZD0iTTAgOTAgQTkwIDkwIDAgMCAxIDkwIDAgSDI0MCBWNjAgSDE1MCBBOTAgOTAgMCAwIDAgNjAgMTUwIFYyNDAgSDAgWiBNMTA1IDI0MCBWMTUwIEE0NSA0NSAwIDAgMSAxNTAgMTA1IEgyNDAgVjE1MCBBOTAgOTAgMCAwIDEgMTUwIDI0MCBIMTA1IFoiLz48L3N2Zz4K" alt="Play the story: find the backed-up queue across 8 brokers" height="36"></a><br>
<sub>An interactive 3 a.m. incident · about 2 minutes · runs in your browser</sub>

**English** · [简体中文](README.zh.md) · [فارسی](README.fa.md)

[![CI](https://github.com/sudoitir/rahdar/actions/workflows/ci.yml/badge.svg?branch=main)](https://github.com/sudoitir/rahdar/actions/workflows/ci.yml)
[![Latest release](https://img.shields.io/github/v/release/sudoitir/rahdar?include_prereleases&sort=semver&label=release)](https://github.com/sudoitir/rahdar/releases)
[![Docker pulls](https://img.shields.io/docker/pulls/sudoit1/rahdar?logo=docker&label=pulls)](https://hub.docker.com/r/sudoit1/rahdar)
[![OpenSSF Scorecard](https://api.scorecard.dev/projects/github.com/sudoitir/rahdar/badge)](https://scorecard.dev/viewer/?uri=github.com/sudoitir/rahdar)
[![Quality Gate](https://sonarcloud.io/api/project_badges/measure?project=sudoitir_rahdar&metric=alert_status)](https://sonarcloud.io/summary/new_code?id=sudoitir_rahdar)
[![Coverage](https://sonarcloud.io/api/project_badges/measure?project=sudoitir_rahdar&metric=coverage)](https://sonarcloud.io/summary/new_code?id=sudoitir_rahdar)
[![Maintainability](https://sonarcloud.io/api/project_badges/measure?project=sudoitir_rahdar&metric=sqale_rating)](https://sonarcloud.io/summary/new_code?id=sudoitir_rahdar)
[![Reliability](https://sonarcloud.io/api/project_badges/measure?project=sudoitir_rahdar&metric=reliability_rating)](https://sonarcloud.io/summary/new_code?id=sudoitir_rahdar)
[![Security](https://sonarcloud.io/api/project_badges/measure?project=sudoitir_rahdar&metric=security_rating)](https://sonarcloud.io/summary/new_code?id=sudoitir_rahdar)
[![Licence](https://img.shields.io/badge/licence-Apache--2.0-blue)](LICENSE)

[Docs](https://rahdar.dev/guide/) ·
[Quickstart](#quickstart) ·
[Flow](https://rahdar.dev/guide/flow) ·
[SQL Console](https://rahdar.dev/guide/sql-console) ·
[MCP](https://rahdar.dev/guide/mcp) ·
[Discussions](https://github.com/sudoitir/rahdar/discussions)

</div>

> [!WARNING]
> **Alpha.** Under active development and not yet feature-complete. Published images
> are pre-stable dev builds (`sudoit1/rahdar:dev`; there is no `:latest` yet).
> Expect breaking changes.

![Rahdar: topology, the cross-node queue grid, the dead-letter queue, message flow and the charts](docs/img/demo.gif)

## Quickstart

Rahdar and its Postgres, from the published image. Your brokers stay as they are: you
register them in the UI, and nothing here starts one.

```bash
base=https://raw.githubusercontent.com/sudoitir/rahdar/main/deploy/compose
mkdir -p rahdar/compose rahdar/postgres && cd rahdar/compose   # the compose file mounts ../postgres
curl -sO "$base/compose.prod.yaml"
for f in postgresql.conf 10-pg-stat-statements.sql; do curl -s "${base%/compose}/postgres/$f" -o "../postgres/$f"; done
curl -s "$base/.env.example" | sed \
  -e "s|^SECRET_KEY=.*|SECRET_KEY=$(openssl rand -base64 32)|" \
  -e "s|^DB_PASSWORD=.*|DB_PASSWORD=$(openssl rand -hex 24)|" > .env
docker compose -f compose.prod.yaml --env-file .env up -d
docker compose -f compose.prod.yaml logs rahdar | grep -A4 'Created administrator'
```

Open <http://localhost:8080> and sign in as `admin` with the password the last line
prints. It is shown once: you choose your own at the first sign-in, and set up two-step verification, which the
admin role requires.

<details>
<summary>From a clone with <code>just</code>, or a bare container against your own Postgres</summary>

```bash
git clone https://github.com/sudoitir/rahdar && cd rahdar
just up          # two replicas behind HAProxy, pinned to the latest release (just up-single: one replica)
```

```bash
docker run -p 8080:8080 \
  -e RAHDAR_DB_URL=jdbc:postgresql://db:5432/rahdar \
  -e RAHDAR_DB_USER=rahdar \
  -e RAHDAR_DB_PASSWORD=... \
  -e RAHDAR_SECRET_KEY="$(openssl rand -base64 32)" \
  sudoit1/rahdar:dev
```

</details>

Rahdar runs on a managed or highly available PostgreSQL 18 too, in a schema of your choosing and as a role that
is not a superuser: see [Running Rahdar on an external PostgreSQL](docs/external-database.md).

Every variable, what a reverse proxy needs for the SSE stream, and how to recover a lost
first login are in the [configuration guide](https://rahdar.dev/guide/configuration).

## Hardware requirements

Measured with a load test on a 4 vCPU / 8 GB machine, Postgres on the same host ([sizing guide](docs/sizing.md),
which also lists what each size carries and what degrades first). Sizes per host:

| | Minimum | Recommended |
| --- | --- | --- |
| **Rahdar node** (single instance, or each replica of a replicated deployment) | 2 vCPU, 2 GB | 4 vCPU, 4 GB |
| **PostgreSQL 18** (single instance, or the shared server of a replicated deployment) | 2 vCPU, 2 GB, SSD | 4 vCPU, 4 GB, SSD |

A replicated deployment runs two or more Rahdar nodes of that size on one Postgres of that size. The minimum is the observed peak (a Rahdar
replica 1.3 GiB and about 1.2 cores, Postgres 0.6 GB and one core) with little headroom, derived from the runs on the
reference machine rather than measured on a smaller one. It carries a few clusters of up to four nodes with around a thousand queues per node, a few dozen browsers and
about a hundred event-stream subscribers; the recommended size keeps interactive reads under a second
at 3,000 queues per node and absorbs a burst of sign-ins. CPU is what runs out first, not memory. Postgres
needs about 0.6 GB of its memory for the shipped configuration (`deploy/postgres/postgresql.conf`); how much
disk depends on how long you keep history. The server requirements and settings of an existing PostgreSQL are in
[Running Rahdar on an external PostgreSQL](docs/external-database.md).

## Why

It's 3 a.m. and `ORDERS.DLQ` is backing up somewhere in your cluster. The console that
ships with Artemis manages one broker at a time and has no idea a cluster exists, so you
open a tab per node, walk a JMX tree in each one, and read attributes until you find it.

Rahdar treats **the cluster as the unit of everything**. Every node of every
cluster is on one topology, with HA roles polled live, and every queue on every node is in
one table, sorted by depth, so the queue you were hunting for is the first row. One
instance serves as many clusters as you run, against the brokers you already have.

<a href="https://rahdar.dev/"><img src="docs/img/story.webp" width="1600" height="900" alt="The story opens at 03:07 with an alert: ORDERS.DLQ depth rising somewhere across 8 brokers in 2 clusters. Play it in your browser."></a>

**[▶ Play the story](https://rahdar.dev/)**: hunt for the queue yourself, one tab per broker, then watch
Rahdar find it on one screen.

## What it does

| Cluster topology | Cross-node queues |
|---|---|
| [![Live/backup topology with replication and a shared-NodeID axis](docs/img/topology.png)](docs/img/topology.png) | [![Every queue across every node in one virtualised grid](docs/img/queues.png)](docs/img/queues.png) |
| **Client and message flow** | **SQL Console** |
| [![Flow: moving dots carry each path's rate; hovering a queue keeps its whole path bright](docs/img/flow.gif)](docs/img/flow.gif) | [![The SQL Console: a query across every queue in the cluster, its cost classified before it runs, then a live tail](docs/img/sql-console.gif)](docs/img/sql-console.gif) |
| **Plugins** | **Settings** |
| [![Installing a plugin: what it will be able to do and the SQL of its database changes, reviewed before a held confirmation](docs/img/plugin-install.gif)](docs/img/plugin-install.gif) | [![Settings: display preferences, Rahdar's operational configuration, this cluster's and each plugin's sections](docs/img/settings.png)](docs/img/settings.png) |

- **Topology** — live/backup pairs and their replication state. The HA role is
  polled from every node on every cycle and never read from configuration; two live
  nodes in one pair raise a split-brain alert.
- **[Flow](https://rahdar.dev/guide/flow)** — which
  application sends to which address, how that address routes through diverts,
  bridges and cluster hops into queues, and who consumes them, at what rate. Faults
  such as a backlog with no consumer are stated in words, and clients are sampled
  only while someone is watching.
- **[SQL Console](https://rahdar.dev/guide/sql-console)** —
  Artemis cannot tell you *where order 4471 went*. Its only server-side filter is a
  JMS selector: headers only, never the body, one queue on one node at a time.
  Rahdar can:

  ```sql
  SELECT * FROM "ORDER.*"
  WHERE body->>'orderId' = '4471'
  LIMIT 50
  ```

  The dialect is read-only `SELECT`, checked against a fixed column catalogue, so a
  query cannot change anything. Header predicates become a JMS selector and cost
  nothing; body predicates scan, and **the cost line under the editor tells you which before the
  query runs**. A query over the cost ceiling is refused with its estimate rather
  than quietly truncated. Add a live tail, or turn on
  [complete capture](https://rahdar.dev/guide/message-capture)
  for a queue: a broker-bounded copy that Rahdar drains, so a message consumed
  between two polls can still be found.
- **Cross-node resources** — queues, addresses, consumers, sessions, connections
  and producers in one virtualised table, each row attributed to its node and kept
  current over SSE.
- **Message operations** — browse, send, move, retry, expire, delete and purge.
  Every mutating call accepts `?dryRun=true`, bulk operations are capped on the
  server, and outcomes are reported per node.
- **Request-reply tracing** — requests matched to their replies across addresses
  and nodes, with latency and timeout statistics against the expectations you declare.
- **[Broker configuration](https://rahdar.dev/guide/broker-configuration)**
  — declare the address settings, security settings, diverts, bridges and queues a
  cluster should run, and compose the routing between them on a canvas. Apply them
  canary-first, with every hazard stated before anything is written, or export them as
  a `broker.xml` fragment, and see where each node has drifted. On a first run Rahdar
  offers the cluster's current state as revision 1; nothing is adopted without your
  say, and a bridge is never adopted because the broker reports only part of one.
- **[Setup review](https://rahdar.dev/guide/setup-review)** — each
  cluster's HA, clustering, durability and message-safety configuration checked against
  known mistakes: a single replication pair that cannot win a quorum vote, redistribution
  left at `-1`, a connector advertising `localhost`, no dead-letter address. Every finding
  carries the evidence per node and the `broker.xml` fix, and can be accepted as a known
  risk, with a reason, on the record.
- **[Alert delivery](https://rahdar.dev/guide/alert-delivery)** —
  Slack, Microsoft Teams, PagerDuty (incidents open and resolve with the alert), email and
  signed webhooks, each tested before it is saved, with a delivery log that says why a
  notification failed.
- **[Data governance](https://rahdar.dev/guide/data-governance)**
  — sensitive headers and properties are masked, PII is classified automatically,
  and redaction follows the viewer's role.
- **Governance** — authentication everywhere, roles and permissions scoped
  global → environment → cluster, API tokens, optional OIDC/SSO, and an audit trail
  that records every change and its outcome.
- **[MCP server](https://rahdar.dev/guide/mcp)** — the same
  capabilities for an AI assistant, under the same grants and the same audit trail.
- **[Plugins](https://rahdar.dev/guide/plugins)** — install a
  plugin's `.jar` from the UI: screens, API, assistant tools and data of its own, with
  everything it can do shown before you confirm, and no restart unless it needs one.
  Updates show what changes and roll back; a failing plugin never stops Rahdar. Start
  a plugin from [the template](examples/plugin-template).

## How it works

Four rules hold everywhere in the product:

- **Broker-friendly by construction.** Batched reads (one Jolokia POST per node,
  never one per queue), tiered polling and a per-node rate limiter. Rahdar must never
  be the reason a broker falls over.
- **Safe by default.** Every destructive call can dry-run first, and purge and
  delete ask you to type the resource's name.
- **HA state comes from the nodes, not from config.** Only the live nodes know who
  is live.
- **Honest capability gating.** An unavailable feature says so and shows the exact
  `broker.xml` that enables it. Nothing silently disappears.

Java 25 · Spring Boot 4.1 · PostgreSQL with Liquibase · React 19 + Vite + Mantine 9 ·
TanStack Router/Query/Table · React Flow · Jolokia over HTTP first, the Artemis Core
client second · SSE · one container image. Read the
[architecture](https://rahdar.dev/reference/architecture) and
[every recorded decision](https://rahdar.dev/reference/adr/).

## Contributing

Questions and ideas go to [Discussions](https://github.com/sudoitir/rahdar/discussions),
bugs to [issues](https://github.com/sudoitir/rahdar/issues/new/choose), and
vulnerabilities to [private reporting](https://github.com/sudoitir/rahdar/security/advisories/new)
([SECURITY.md](SECURITY.md)). To build it, you need JDK 25, Node 24, Docker and
[`just`](https://github.com/casey/just#packages); a dev container is included.

```bash
just dev-up          # Postgres + a real Artemis primary/backup pair + Rahdar, from source
just dev             # or: backend :8080 + Vite :5173 together, with live reload
just verify          # everything CI runs
```

`ADMIN_PASSWORD=… just demo` (the first run also needs `NEW_ADMIN_PASSWORD=…`, and later ones `ADMIN_TOTP_SECRET=…`) adds two more live/backup pairs and fills all six nodes
with realistic traffic: diverts, a bridge, cluster hops, a growing backlog with no
consumer, a real dead-letter backlog and one stopped node. The screenshots above are
recorded from that stack by `just shots` and `just demo-gif`; nothing is staged.

Every feature goes through **OpenSpec** and significant decisions are recorded as
[**ADRs**](docs/adr/). Start with [`CONTRIBUTING.md`](CONTRIBUTING.md).

## Releases

A merge that changes the application publishes a release, versioned with CalVer
`YYYY.MM.PATCH` and tagged three ways on Docker Hub: `2026.09.3` (immutable), `2026.09`
(that month) and `dev` (the latest). There is no `:latest` until the first stable
release. Each release attaches the runnable jar with its `.sha256`, and its notes are
generated from the commit messages ([releases](https://github.com/sudoitir/rahdar/releases), [changelog](https://rahdar.dev/reference/changelog/)). The plugin SDK
(npm) is published with the releases that change it; the plugin API and the Java client reach Maven Central
when a maintainer publishes a release there.

## Licence

[Apache-2.0](LICENSE), the same licence as Artemis itself.

Apache ActiveMQ and Apache ActiveMQ Artemis are trademarks of the Apache Software
Foundation. Rahdar is an independent project and is not produced by,
endorsed by, or affiliated with the Apache Software Foundation. References to
"Artemis" describe the broker this tool manages.

---

<div align="center">

If this saved you time, a ⭐ helps others find it.

</div>
