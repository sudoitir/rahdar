# Architecture

Rahdar is one Spring Boot service, a React SPA it serves, and a PostgreSQL
database. It talks to many Artemis clusters over their standard management
endpoints.

Inside, it is a **modular monolith** (ADR-0069): a small kernel that defines
contracts, platform modules every feature builds on, and feature modules that plug
into both. The same module ids name the backend package, the frontend folder, the
startup toggle and the manifest entry.

## Modules

```
                 ┌─────────────────────────── features ────────────────────────────┐
                 │ queues  resources  messages  routing  metrics  alerting  events │
                 │ rr  sql  brokerconfig  triage   apitokens  identity-local  -oidc │
                 └───────────────┬───────────────────────────────┬─────────────────┘
                                 │ api / events / SPI beans      │
                 ┌───────────────▼────────── platform ───────────▼─────────────────┐
                 │ broker (OpenBroker gateway)  clusters (registration, topology,  │
                 │ BrokerCommands)  scrape (tiers, snapshots)  mcp (server)         │
                 └───────────────┬─────────────────────────────────────────────────┘
                 ┌───────────────▼─────────── kernel ──────────────────────────────┐
                 │ core  plugin  security  audit  settings  jobs  stream            │
                 └─────────────────────────────────────────────────────────────────┘
```

| Layer | Module | Owns |
|---|---|---|
| kernel | `core` | branding, clock, problem-JSON errors, OpenAPI, SPA routing |
| | `plugin` | `FeatureDescriptor`, `@FeatureModule`, the feature registry, `GET /api/v1/manifest`, `404 feature-disabled` |
| | `security` | principals, grants, `@perm`, `ClusterAccessGuard`, the one filter chain, users and roles, the identity provider SPI |
| | `audit` | `AuditService` and `audit_event` |
| | `settings` | the runtime settings registry (`rahdar_setting`) and the JDBC bootstrap property source |
| | `jobs` | `ScheduledJob`, the one scheduler, job status and its health |
| | `stream` | `SseHub`, `GET /api/v1/stream`, the topic registry |
| platform | `broker` | the OpenBroker gateway (`BrokerGateway`: one `ClusterConnection` per cluster), `RahdarCallInterceptor` with `NodeCallLimiter`, notifications, messages, the transfer relay |
| | `clusters` | registration, nodes, credentials, TLS, environments, discovery, HA and split-brain, serving nodes, the capability ledger, `BrokerCommands` |
| | `scrape` | the tiered scrape, `subscription_snapshot`, `metric_sample` |
| | `mcp` | the MCP server, the tool catalogue, help, resources and runbook prompts (optional) |
| system | `artemis` | what only Artemis has: the one use of the SDK's `ArtemisManagement` extension, MBean operations, the capability probe and its `broker.xml` remedies (ADR-0203) |
| feature | everything else | one module per capability, each with its own tables, endpoints, jobs, topics and tools |

The generated module graph and one canvas per module are in `docs/modules/` in the repository,
written by `DocumentationTest` from the same model `ModularityTest` verifies.

### Composition and toggles

Composition is at build time. `app/RahdarFeatures` is the one list of backend
modules: each module's descriptor, and an `@Import` of each feature's
`@FeatureModule` configuration. The application scans only `kernel` and `platform`.
The frontend's `web/src/app/features.ts` is the matching list.

A feature is loaded while `rahdar.features.<id>.enabled` is not `false`
(`RAHDAR_FEATURES_<ID>_ENABLED`). A disabled feature contributes no beans,
endpoints, jobs, topics or MCP tools; its API paths answer `404` with problem type
`feature-disabled`; its screens explain that it is off and name the property. Kernel
and platform modules are required, and a feature that `requires` another cannot start
without it. Disabled modules still migrate, so turning one back on is a restart.

`GET /api/v1/manifest` reports every installed module, whether it is enabled, the
property that enables it, and the permission catalogue. It describes and never
authorizes.

### The extension contract

A module declares its static facts once, in a `FeatureDescriptor` (ADR-0070): id,
title, kind, `requires`, permissions, setting keys, stream topics, MCP catalogue
entries and API path prefixes. Behaviour is contributed as beans of kernel and
platform types:

| Contribution | Type |
|---|---|
| Runtime settings | `SettingsContribution` |
| Scheduled work | `ScheduledJob` |
| Broker notifications | `BrokerEventSink` |
| Checks during cluster registration | `RegistrationCheckContributor` |
| Alert conditions from another module | `AlertSignalSource` |
| Captured messages | `CaptureBus.Listener` |
| Sign-in | `CredentialIdentityProvider`, `RedirectIdentityProvider`, `BearerIdentityProvider` |
| Health | Spring `HealthIndicator` |
| Cross-module notification | a record published as an application event, such as `ClusterRegistered` or `ScrapeTierCompleted` |

Endpoints and MCP tools stay ordinary Spring and Spring AI annotations. A module's
tables are its own Liquibase changelog (below).

### Dependency rules

Each module's `package-info.java` declares `@ApplicationModule(allowedDependencies)`;
`ModularityTest` fails on a cycle, an undeclared dependency, or a reach into another
module's `internal` package. `BoundaryRulesTest` adds what Modulith does not see:

- features use only method security from Spring Security; only the security kernel
  and redirect sign-in see `HttpSecurity`;
- entities and repositories live in the owning module's `internal.persistence`;
- no Artemis, Jolokia or JMS type appears anywhere, the SDK's driver SPI is never used, and
  only `system.artemis` knows the SDK's Artemis driver (ADR-0203);
- only the stream kernel and the SQL tail hold an `SseEmitter`;
- scheduling stays in the jobs kernel and the scrape tiers;
- features never inject the container or enable framework features.

Every feature also has an `@ApplicationModuleTest` that starts it with its direct
dependencies only, and `FeatureToggleTest` starts Rahdar once with each optional
feature disabled.

A kernel module never imports a platform or feature type. Upward needs are inverted:
the scrape publishes `ScrapeTierCompleted` for alerting, clusters publishes
`ClusterRegistered` for the built-in alert rules, the security kernel walks the scope
hierarchy through `ScopeHierarchy` (implemented by clusters), and the broker reads
connection settings through `ConnectionSettingsSource`.

### Frontend

```
web/src/
  kernel/    contract (feature.ts), manifest, slots, nav groups, routing roots,
             api (request, paging, polling, generated schema), stream, auth, time, shell
  ui/        shared presentational components: the page parts (Page, PageHeader, Section, EmptyState,
             ErrorState…), table/ (the one DataTable), ConfirmDialog, NodeOutcomeSummary
  features/  one folder per module id: feature.ts, api.ts, views, tests; index.ts for public exports
  app/       the composition root: features.ts and router.ts
  test/      render helpers, the MSW server, manifest fixtures
```

Every page is built from the shared page parts in `ui/` and every table is the one `DataTable`
(ADR-0163, ADR-0160); a plugin uses the same parts through the SDK, whose exports are frozen per
release.

A feature's `feature.ts` calls `defineFeature` with what it contributes: routes under
a kernel root, navigation entries in one of the kernel's fixed groups (observe,
messaging, resources, configuration, activity), palette groups, stream topic
handlers, and slot contributions. Slots are kernel-owned places another feature's
component appears: `shell.header`, `shell.navbar`, `home.empty`, `cluster.header`,
`cluster.registration.afterProbe`, `queue.detail.panels`, `queues.selection`,
`messages.selection`, `metrics.panels`, `flow.selection.panels`, `topology.node.marks`,
`settings.sections`, `routing.tabs`, `admin.tabs`, `account.sections`. Row menus and links
are slots too (ADR-0107): `<kind>.actions` for queue, address, connection, session,
consumer, producer, message, divert and client, each item naming its section (Open, Copy,
Operate, Destroy); and `<kind>.link` for queue, address, connection and session, which
plugins may not contribute. Two tab slots list their tabs under fixed, kernel-owned group
headings: `settings.sections` under `SETTINGS_GROUPS` (a plugin's always under Plugins), and
`admin.tabs` under `ADMIN_GROUPS` (Access, Installation, Governance, Support), where every
contribution, a plugin's included, names its group.

The shell reads the manifest and drops a disabled feature's navigation, slots and
topics; every feature's routes stay registered so a deep link reaches the page that
explains the feature is off. `eslint-plugin-boundaries` (ADR-0074) fails the lint when
the kernel imports a feature, or a feature imports another feature other than through
its `index.ts` along an allowed edge.

### Runtime plugins

A plugin is a third party's jar, uploaded in Admin → Plugins and stored in Postgres
(ADR-0099). It is not a module of this build and is not checked by `ModularityTest`;
it is contained at runtime instead:

- **Validation** (`kernel.plugin.internal.validation`) reads the jar's bytecode with
  `java.lang.classfile` and never loads it: an entry allowlist, zip-consistency and size
  limits, classes confined to its `basePackage`, namespaced ids, denied annotations and
  calls, and a Liquibase preflight that refuses changes running commands or Java.
- **Runtime** (`internal.runtime`): its own `URLClassLoader`, a child
  `GenericWebApplicationContext` whose parent holds only `@PluginApi` beans, its own
  schema `plugin_<id>`, Hikari pool and `EntityManagerFactory` (ADR-0101), and its own
  `DispatcherServlet` behind `PluginGateway` (`/api/v1/p/<id>/**`,
  `/api/v1/clusters/{id}/p/<id>/**`). `PluginBridge`s add and remove its
  contributions — permissions, settings, topics, jobs, MCP tools, core events — in
  copy-on-write registries.
- **Host** (`internal.host.PluginHost`) sequences plan, activate (Instant, Brief
  maintenance, Restart), disable, uninstall, rollback and purge, one at a time; starts
  plugins after `ApplicationReadyEvent`; trips safe mode after three unclean boots; and
  restarts Rahdar when a plugin needs it and a supervisor will start it again
  (`RahdarRestart`, ADR-0104).
- **Administration** (`feature/plugins`) puts the installer tier and step-up
  re-authentication (ADR-0103) in front of every change and audits each step.
- **UI** (ADR-0100): `web/src/kernel/plugins/boot.ts` loads each active plugin's Module
  Federation bundle before the router is built, validates it and wraps every
  contribution in an error boundary. The shared-library list is
  `web/packages/plugin-sdk/shared.js`, read by both the host and the plugin preset.

## Components and data flow

```
                    ┌─────────────────────────────────────────────┐
  Browser           │  React 19 SPA (served from the same jar)     │
  ──────────────────┤  Mantine 9 · TanStack Router/Query/Table     │
                    │  React Flow topology · Mantine charts        │
                    │  SSE client invalidates the Query cache      │
                    └───────────────┬─────────────────────────────┘
                          REST + SSE│  same origin, /api/v1
  MCP client                        │
  ──────────────────────────────────┤  JSON-RPC over HTTP, /mcp
   (Bearer as_…)                    │  (ADR-0045, ADR-0046)
                    ┌───────────────▼─────────────────────────────┐
                    │  Spring Boot 4.1 · Java 25                   │
                    │  kernel · platform · features (above)        │
                    └──────┬──────────────────────────────┬────────┘
                           │                              │
                   ┌───────▼────────┐          ┌──────────▼──────────────┐
                   │  PostgreSQL    │          │  Artemis clusters       │
                   │  config, users │          │  Jolokia :8161 (pull)   │
                   │  audit, metrics│          │  Core :61616 (push+IO)  │
                   └────────────────┘          └─────────────────────────┘
```

### Scrape path (pull)

A tiered scheduler emits work per node (ADR-0015). Cadences are runtime-tunable
via `rahdar_setting`; the values below are the defaults.

| Tier | Content | Interval |
|---|---|---|
| A | HA state (`Active`, `Started`, `ReplicaSync`, …) + per-cluster split-brain corroboration | 5s |
| B | the first `listQueues` page per node — a fast refresh of the busiest queues | 15s |
| C | every `listQueues` page per node in one tick, walking the whole set, then reaping removed queues | 5m |

Each tick is **one management call per node** through the broker SDK; for Artemis the
driver sends one Jolokia POST (and caches the resolved broker MBean name, so a tick does
not pay an extra `search`). Artemis 2.44's
`sortColumn` / `GREATER_THAN` options both 500 with an NPE
(`docs/broker-management-notes.md` §10), so there is no broker-sorted "hot page":
tier B is best-effort speed on page 1, tier C is the coverage guarantee.
`NodeCallLimiter` caps management calls per node. Network I/O runs on virtual
threads, one slow node never blocks its siblings, and it never runs inside a DB
transaction — each node's result is handed to a short `@Transactional` persist
step. Queue rows upsert into `subscription_snapshot` via a JDBC `INSERT … ON CONFLICT`
batch (ADR-0016, a scoped exception to ADR-0011); metric points append to the
partitioned `metric_sample`, and a reaper trims past the retention window. Six
metrics are recorded per queue per sweep, under the broker SDK's standard keys —
`openbroker.backlog`, `openbroker.consumer.count`, `openbroker.in_flight`,
`openbroker.enqueued`, `openbroker.acknowledged` and `openbroker.expired` — all
read off the subscription listing the sweep already fetched, so the set costs no
additional broker call (ADR-0089). When a
tier completes, `ScrapeTierCompleted` lets alerting evaluate its rules in the same
thread, so ordering is what it was before the modules split.

### Consumer health (derived read)

One queue's verdict — why a backlog is not draining — is an ordered ladder
evaluated in `feature/triage`: `INSUFFICIENT_DATA`, `PAUSED`, `NO_CONSUMERS`,
`BROKER_SLOW`, `STALLED`, `STARVED`, `FALLING_BEHIND`, `DRAINING`, `HEALTHY`,
first match wins (ADR-0089). It is derived from `subscription_snapshot` and windowed
`metric_sample` aggregates — a fixed number of queries per request whatever the
queue count, and no broker call. `deliveringCount` is what separates a stalled
consumer from a starved one; a `CONSUMER_SLOW` notification outranks any derived
verdict (ADR-0044).

The same evaluation serves the **Consumer health** screen under *Observe*, the
queue drawer's panel, `GET /api/v1/clusters/{id}/consumer-health`, the `diagnose`
MCP tool and the `consumerHealth` alert condition, so the console, an agent and an
alert cannot disagree about one queue. That condition lives in `feature/triage`
rather than `feature/alerting`, because triage already depends on alerting;
`AlertCondition` is discovered as a bean and handed an `AlertRuleSpec`, which is
what lets a module contribute a rule kind without alerting importing it.

### Cross-node aggregation (read)

A primary and its synced backup share one `NodeID` and are **one logical node**
(ADR-0017). The queue grid is built from `subscription_snapshot`, grouped by
`(address, queueName, routingType)`: each row carries a per-node cell, rolled-up
cluster totals, and `nodesPresent / nodesTotal`. A node whose last sweep is stale
keeps its last numbers, flagged — never dropped. The other five views
(addresses, consumers, sessions, connections, producers) are **live-through**:
one batched POST per serving node on demand, rows tagged with their logical node,
merged / filtered / sorted / paged in memory. A node that errors contributes
nothing; only when *every* node fails is the classified error surfaced (the
capability ledger + `broker.xml` advice).

### Event path (push)

`BrokerNotifications` keeps one notification stream from the broker SDK on every
*serving* node of a cluster (ADR-0026, ADR-0203). It reconciles the streams against the
live topology at the end of each tier-A scrape, so a failover is followed — a stream
moves to the survivor, not to a configured node. The SDK reconnects a stream with
backoff and reports what it may have missed as a gap; for Artemis the driver
subscribes to `activemq.notifications` over Core. Each event arrives in the SDK's
standard terms (`openbroker.consumer.attached`, with its destination, subscription and
consumer named under standard keys), becomes a `BrokerEvent`, and is handed to every
`BrokerEventSink`. The events feature's
buffered writer (ADR-0028) batch-inserts into `broker_event`; the buffer is bounded
and overflow increments a per-cluster `dropped` counter the events API surfaces. A
reaper trims past a retention window (default 72h, a `rahdar_setting`).

`NOTIFICATIONS` is not a fixed `UNKNOWN`: `CapabilityProbe` reads the cached
subscription verdict (`AVAILABLE` on ≥1 subscribed node; `UNAVAILABLE` with the
exact `broker.xml` security-setting or acceptor snippet when refused or
unreachable; `UNKNOWN` only until the first scrape). It opens no connection.

Message browse and send go through the broker SDK (`BrokerMessages`, ADR-0203), and
the driver picks the channel. The Artemis driver reads the first window of a queue
over the Core client (ADR-0029), which returns whole bodies and typed properties
without truncating, and a deeper page, or a node without Core, over Jolokia. By-id
and by-filter mutations go over management.

### Realtime to the browser

One SSE endpoint, `GET /api/v1/stream?clusterId=&topics=…`, multiplexes named
events on a Spring MVC `SseEmitter` (ADR-0018; ADR-0010 removed WebFlux). Each
module declares its topics in its descriptor; the endpoint drops a topic that is
unknown or belongs to a disabled feature. Signal topics (`topology`, `health`,
`queues`, `consumers`, `sessions`, `connections`, `alerts`, `rr`, `config`) carry
change *signals*, not data — the handler the owning frontend feature contributes
invalidates the matching TanStack Query keys. The `events` topic is the exception
(ADR-0027): it carries the `BrokerEvent` payload and an `id:` line (the
`broker_event.seq`), and a reconnecting client replays what it missed via
`Last-Event-ID` (bounded to 500). Notification-driven staleness of the resource
views is **coalesced to at most one signal per topic per second per cluster**
(`TopicCoalescer`), because each refetch costs one Jolokia call per node.

A cluster's layout opens one `EventSource` for the topics of every enabled feature.
It reconnects indefinitely with capped, jittered backoff and treats a missed
keep-alive as a failure (ADR-0052); the per-query poll keeps views updating while it
is down. The response carries `X-Accel-Buffering: no`: **proxies must not buffer this
stream**.

## State ownership

- **PostgreSQL** — clusters, nodes, credentials (AES-GCM), users, roles, audit,
  alert rules, request-reply expectations, operator settings (`rahdar_setting`),
  and the metrics cache.
- **URL** — navigable UI state (selected cluster, view, filter, sort, page). Routes
  are code-based TanStack Router routes composed from the features; every list view's
  `q` / `sort` / `page` lives in the query string.
- **In-memory** — the SSE subscriber registry, the split-brain corroboration
  ratchet and per-cluster refresh-cycle counter, Core pools and subscriptions, and
  job status. A restart re-derives all of it within about one tier-A cycle. For
  multi-instance HA (post-MVP) the scheduler takes a Postgres advisory lock per
  cluster: one instance scrapes a cluster, every instance serves reads and SSE.

## Broker transport and capabilities

See ADR-0002. `CapabilityProbe` classifies a connection into `MANAGEMENT_READ` /
`MANAGEMENT_WRITE` / `NOTIFICATIONS` / `MESSAGE_IO`; the UI gates features on the
result and shows the `broker.xml` needed to unlock the rest. An unestablished
capability is `UNKNOWN`, and unknown leaves a control enabled with the uncertainty
stated (ADR-0049 D5).

Rahdar reaches every broker through the OpenBroker SDK (ADR-0203). `platform.broker`'s
`BrokerGateway` opens one `ClusterConnection` per cluster with the cluster's system
(`cluster.system`), Rahdar's credentials and SSL bundles, and a `RahdarCallInterceptor`
that admits every management and control call through the per-node limiter, the call
guard and node call health, and records the `rahdar.broker.*` observations. Features use
the SDK's facets: resources, metrics under standard keys, operations with a preview and an
etag the approval gate pins, messages, the transport, and notifications. What only Artemis
has goes through `system.artemis`, the one module that unwraps the SDK's Artemis extension.
The driver chooses the channel per call: for Artemis, Jolokia for management and Core for
messages and notifications.

Oversized body and property values are truncated by the broker at
`management-message-attribute-size-limit` over Jolokia, and Rahdar discloses that
**per message** (a `bodyTruncated` flag and the `broker.xml` snippet that raises the
limit). The Core channel carries faithful bytes.

HA: never trust config for who is live. `Active` is polled on every node; two
`true` in a pair → critical split-brain alert. Failover is followed, not
configured.

## Broker writes

Every cluster-wide broker write goes through `BrokerCommands` in `platform.clusters`
(ADR-0071), which runs one sequence: the caller's permission on the cluster; one
target per logical node, liveness from the polled `Active`; the audit row before any
broker call; a per-node estimate so the bulk cap sees the whole blast radius; a dry
run that returns `WOULD_APPLY` per node and writes nothing; an audited refusal over
the cap without an override; a rate-limited fan-out where one node's failure never
aborts the others; the audit row finished with per-node detail; the topic signal
after commit. `AuditCoverageTest` holds every public mutating service method in a
feature to `BrokerCommands` or `AuditService`.

Message operations, node-scoped connection closes, the configuration apply and
capture reconciliation keep their own audited sequences, each for a reason recorded
in the change design: a message operation targets one node's queue, a close names the
node that issued the id, an apply is canary-first rather than a fan-out, and
reconciliation acts on drift.

## Request-reply tracing

Both patterns are handled by one correlator:

- **Shared reply queue + correlation id** — browse request and reply addresses,
  join on `JMSCorrelationID` / `_AMQ_CORRELATION_ID`, compute latency. No reply
  past the deadline → `TIMED_OUT`; reply with no request → `ORPHANED_REPLY`.
- **Temporary reply queues** — invisible to browsing; reconstructed from the
  event lifecycle (`openbroker.resource.created` → `openbroker.consumer.attached` → … →
  `openbroker.consumer.detached` / `openbroker.resource.deleted`):

  | Observed | State |
  |---|---|
  | temp queue + consumer + request enqueued | `AWAITING_REPLY` |
  | reply before consumer close | `COMPLETED` (latency recorded) |
  | temp queue removed, request still unacked | `ORPHANED` (requester died) |
  | no reply, deadline passed, consumer still attached | `TIMED_OUT` |
  | request acked by responder, no reply produced | `RESPONDER_DROPPED` |

Deadlines come from `_AMQ_EXPIRE` / `JMSExpiration`, else a per-address
`rr_expectation`. When the sql feature is enabled, a captured address feeds the
correlator every routed message through a `CaptureBus.Listener`; without it,
request-reply samples by browsing and runs on its own.

## The SQL Console, the message index and message capture

The console is one editor over three sources of the same shape (ADR-0058): a live
broker read, the persisted `message_index`, and a live tail of either. `SELECT ...
FROM broker."ORDER.#"` fans out to every serving node, pushes down what Artemis'
own filter syntax can express, and evaluates the rest in Rahdar. Every bound it
reaches — target cap, scan cap, row cap, timeout — is reported in words, because a
bounded result that does not say so reads as a complete one.

**Two ways a message reaches the index, and they make different claims.**

- **Sampled** (ADR-0060). A poll browses the queue against a `(timestamp, messageId)`
  high-water mark and records what it saw. Complete only for messages that sit still
  long enough to be seen. A queue that drains faster than the interval is invisible,
  which the console states permanently and non-dismissably on every sampled tail.
- **Captured** (ADR-0062). A non-exclusive divert copies the address into a
  Rahdar-owned queue, drained by a transacted receiver of the broker SDK. Complete for what the address
  routed, whether or not anything consumed it in between.

```
source address ──non-exclusive divert──► rahdar.capture.<instance>.<address>.<sub>
      (per node)                              ring-size N, address-full-policy=DROP,
                                              expiry-delay, non-durable
                                                       │
                                              transacted SDK receiver, committed after the store
                                                       │
                                                  CaptureBus
                                     ┌─────────────────┼─────────────────┐
                                 live tail      message_index     request-reply
```

The tap is bounded by construction, which is what makes it safe to leave running:
the ring drops the oldest message rather than growing, `DROP` keeps the broker from
paging or blocking on Rahdar's account, and `expiry-delay` with
`auto-create-expiry-resources=false` bounds it in age too. None of those objects
disappears on a broker restart (ADR-0065 — measured, not assumed), so **every
removal is an explicit act**: `CaptureReconciler` is the only thing that takes a tap
away.

That reconciler is the whole lifecycle. Desired state is the `CAPTURE` subscriptions
in Postgres, resolved through the same planner an operator's own query uses; actual
state is each live node's divert names filtered to this Rahdar's instance id. One
idempotent loop is simultaneously the install path, the crash-recovery path, the
failover path (a promoted backup carries no divert, and the next pass installs one)
and the removal path. It acts on drift only, through `NodeCallLimiter`, and takes a
per-cluster Postgres advisory lock so two instances of the same Rahdar cannot fight.

Two preflight refusals, both because the alternative fails silently: an **exclusive
divert** already on the source address would shadow ours and capture nothing, and a
capture queue Rahdar cannot **restrict with a `security-setting`** would be an
unguarded second copy of production payload. Both refuse with the `broker.xml` that
would change the answer.

Capture is address-scoped and says so. A divert copies at address routing, before
multicast fan-out, so for an address with several bound queues the index holds one
row and genuinely cannot say which subscriptions received it.

## Safety and audit

Every mutating endpoint accepts `?dryRun=true` and returns the affected count
without acting. Purge/delete are confirmed in the UI by press and hold (ADR-0186). Every mutation
writes an `audit_event` in the same transaction as the command — row created
before the broker call, updated with the outcome; a dry run is audited too
(`dry_run = true`). The actor is a real identity (ADR-0041): the authenticated
principal's username and `user_id`, with the token name folded in when the
caller authenticated via an API token (`"<owner> [token: <name>]"`), plus the
source IP and an `X-Request-Id` (or a generated UUID); scheduler-originated
rows are `system`. `audit_event` carries no foreign keys (ADR-0072): an audit row
keeps its cluster id and name after the cluster is removed.

**Bulk safety cap.** A destructive message operation whose dry-run count exceeds
`safety.bulk-cap` (a `rahdar_setting`, default 1000) is rejected with a `422`
(`bulk-cap-exceeded`, carrying `affectedCount` and `cap`) unless the caller passes
`?override=true` — which the UI reaches only behind the dry-run preview plus a
a held confirmation that names the action and count (ADR-0022, ADR-0186). A cap that lived only in the
browser would not be a cap.

**Broker configuration** (ADR-0067). A cluster's declared address settings,
security settings, diverts, bridges and queues live in the brokerconfig module's
tables, versioned on every save. The screen is one desired-vs-live view (ADR-0087)
whose tabs are Declared, History and Recommended. The routing builder (ADR-0090) is the
Routing screen's Builder tab, contributed by brokerconfig through the `routing.tabs` slot
(ADR-0094): it draws the declared and observed routing as an ELK-laid graph and edits the
same document the Declared tab does, authoring a revision rather than writing to a broker,
so both open the same editors and neither can drift from the other. Its apply is canary-first: a plan is computed per live node
from at most two batched reads, hazards are classified before any write and the High
ones must be acknowledged by id, then the canary node receives every step and is read
back before the next node is touched. The first failure halts the run, nothing is
rolled back, and re-running converges. A real run names the plan hash it previewed
and is refused (`409 plan-changed`) if the cluster moved; a Postgres advisory lock
refuses a concurrent apply. Rahdar removes only what it applied, never writes
`broker.xml` and never calls `reloadConfigurationFile`. Drift is evaluated on a
schedule and after every apply, and feeds alerting through `AlertSignalSource`.

Bridges are declared items like the rest (ADR-0091), applied by the same engine —
there is no second write path — with a change planned as a remove and a create because
the broker has no `updateBridge`. `BridgeControl` reports thirteen of the twenty-three
fields `createBridge` accepts, so verification and drift claim nothing about the other
ten, adoption skips bridges entirely, and a bridge's credential lives in the vault with
only a reference in the declaration (ADR-0092).

**DLQ view.** Dead-letter and expiry addresses are read from the broker's own
`getAddressSettingsAsJSON` — never guessed from names (ADR-0022, D8). If the settings
read fails the view says exactly that and infers nothing.

**Data governance** (ADR-0075). Message content passes one content policy, owned by the
required `platform.governance` module, wherever it leaves Rahdar or is stored. Rules
(header, property or JSON body path, optionally per address pattern) and checksum
detectors (card numbers, IBANs, emails, phones, bearer tokens and JWTs) classify values
into fixed data classes; credentials are dropped for everyone, other classes masked or
partially masked, and uninspectable content withheld with its reason. The choke points
are typed: browse, message detail and MCP (`MessageService`), the SQL console's rows,
tails and residual predicates (`SqlGovernance`), broker event props, and audit
parameters through the kernel's `AuditParamsFilter` SPI. `message:clear` shows
non-credential values in clear, marked as sensitive, and every clear response writes a
`VIEW_CLEAR` event with classes and counts. Stored copies — `message_index` rows and
`rr_event` payloads — hold the masked form, so full-text search never sees a sensitive
value; originals are sealed beside the row with `SecretVault` under a row-bound AAD and
opened only for clear access. Each stored row records its policy version; a rule change
applies on every read at once, and the `StoredContentRemasker` SPI lets each owning
module rewrite its own rows in bounded batches while `GET /governance/remask` reports
what is left. Detections in fields no rule covers are aggregated in memory and upserted
into `classification_finding` in batches.

## Identity

Sign-in is a sealed provider SPI in the security kernel (ADR-0073):
`CredentialIdentityProvider` (username and password — local accounts),
`RedirectIdentityProvider` (browser redirect — OIDC) and `BearerIdentityProvider`
(an `Authorization: Bearer` key — API tokens, which is how MCP authenticates). The
kernel builds the one filter chain from whichever providers are enabled, and the login
page is built from `GET /api/v1/auth/providers`. An external identity is provisioned
by `IdentityProvisioner`, keyed by provider and subject, and its groups are mapped to
roles per provider at every sign-in (`/api/v1/identity/providers/{providerId}/group-mappings`).
A directory provider would be one more module implementing the credential interface.

## Operational health

`/actuator/health/rahdar` groups three indicators, kept out of liveness and readiness
so a broker outage never restarts Rahdar: `jobs` (each scheduled job's last run,
degraded after three missed intervals; also `GET /api/v1/system/jobs`), `brokers`
(per node, the last management success or failure and the rate-limit wait; per
cluster, open Core connections) and `subscriptions` (per serving node, whether the
notification subscription is established, and why not). Shutdown is ordered by
`SmartLifecycle` phases: the stream closes first, then broker calls and the scrape,
then subscriptions and capture, then the Core pools.

## MCP surface

`POST /mcp` is a second inbound edge onto the same services (ADR-0045), mounted by
Spring AI's WebMVC starter as a stateless Streamable HTTP transport. The platform
module owns the server, `rahdar_help`, resources and runbook prompts; each feature
owns its tools, in its own `mcp` package, and lists them in its descriptor's catalogue.
A disabled feature's tools are absent from the server and the catalogue, and a runbook
prompt names the property that restores them. `rahdar.features.mcp.enabled=false`
removes the server altogether.

Everything above applies unchanged: `ClusterAccessGuard` and `@PreAuthorize` are the
enforcement, the bulk cap is the same `rahdar_setting`, and every mutation writes the
same `audit_event` under the key owner's identity with the key's name attached. Tool
bodies run on the servlet thread (`type: SYNC`), because permission and actor
resolution both read `ThreadLocal` state. Mutations add a model-facing gate: `dryRun`
defaults to true, and a real destructive run requires `confirm` to equal the subject's
name — separate from, and never satisfied by, the bulk-cap `override`.

## Persistence notes

Liquibase (ADR-0008), per module (ADR-0072). `db.changelog-master.xml` includes one
changelog per module, in dependency order; each module's `db/changelog/<layer>/<id>/`
holds its changesets, applied in file-name order. A module owns its tables:
`SchemaOwnershipTest` holds each table to one module, each entity to its owner's
package, and each foreign key to an allowed dependency edge. Rows another module keeps
about a cluster go with it through `ON DELETE CASCADE` along those edges.

Columns are ordered by alignment to cut row padding; high-churn tables carry
`autovacuum`/`fillfactor` storage parameters; `metric_sample` is range-partitioned with
BRIN on `ts`. Server tuning is in `deploy/postgres/postgresql.conf`.
