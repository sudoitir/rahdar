# Design: Kafka in Rahdar

## Context

Rahdar is broker-neutral: shared API by role (ADR-0206), a system namespace (ADR-0207), system
extensions (ADR-0208), role-named permissions (ADR-0209) and plugin messaging (ADR-0210). Today it
runs only Artemis, and three places still assume it:

- `BrokerGateway.SYSTEM = "artemis"` builds every connection's options;
- `RegisterClusterRequest` is a Jolokia seed-URL request with no system;
- `HaStateEvaluator` is the only health verdict, shaped by Artemis HA pairs.

Labels come from `ResourceIds.label`, which sentence-cases a kind id; the subscription view has a
message count and no notion of an estimate; browse pages by offset per node; plugin consumers name a
queue; shared web components carry Artemis literals.

OpenBroker `0.6.0` supplies the Kafka driver for **Kafka 3.0 and later, in ZooKeeper and KRaft mode**:
- kinds `kafka.topic`, `kafka.partition`, `kafka.group`, `kafka.group-topic`,
  `kafka.share-group-topic`, `kafka.acl`, `kafka.transactional-id`, each with a `KindLabel`
  (singular, plural);
- lag as an estimate, `kafka.lag_unavailable` where no offset is committed;
- purge and seek on an empty consumer, share or classic group, refused with `FAILED_PRECONDITION`
  naming members otherwise; streams groups read-only with `kafka.read_only_reason`;
- truncate with per-group reach, refused on compacted topics; browse from an offset or a time with
  no group and no filter; per-record move, copy and delete unavailable (`LOG_IS_APPEND_ONLY`);
- capabilities per feature: quorum on ZooKeeper mode, share groups, consumer-protocol groups and
  streams groups are declared unavailable with a reason where the cluster lacks them;
- `verify` refuses a cluster below 3.0 naming what it found, reaches every broker on its advertised
  address and names each node's outcome in its `Partial`.

## Goals / Non-Goals

**Goals:** Kafka as a first-class system; the Kafka screens an operator on call needs; no Artemis
assumption left in a shared path; an add-cluster flow that works for every driver.

**Non-Goals:** Kafka Streams, Connect, schemas; reassignment, broker configuration, quotas;
IAM-signed SASL; clusters before 3.0; resetting streams groups; shift-by-N resets; browsing all
partitions merged; mobile layouts.

## Decisions

### D1. The system is the cluster's, end to end
`BrokerGateway` reads `cluster.system` and asks the system's `ConnectionOptions` extension
(ADR-0208) for the connect options. `BrokerGateway.SYSTEM` is removed; Artemis-only code names
`ArtemisModule.SYSTEM`, Kafka-only code `KafkaModule.SYSTEM`. `GET /api/v1/systems` lists the loaded
drivers as the standard paged view `{data: [{id, name, supportedVersions, connection: "jolokia-seeds"|"bootstrap"}], page, pageSize, count}`.
- Rejected: one gateway per system. The gateway's caching, probing and reconfiguration are neutral.

### D2. Registration is per system, on its own page (ADR-0213)
`POST /api/v1/clusters[?dryRun=true]` takes:

```json
{ "system": "kafka", "name": "events", "description": null, "environmentId": null, "adopt": false,
  "connection": { "system": "kafka",
    "bootstrapServers": ["k1:9092", "k2:9092"],
    "securityProtocol": "PLAINTEXT|SSL|SASL_PLAINTEXT|SASL_SSL",
    "saslMechanism": "PLAIN|SCRAM-SHA-256|SCRAM-SHA-512|OAUTHBEARER",
    "credentials": {"username": "u", "password": "p"} | {"token": "t"} | null,
    "tlsBundle": null, "clientId": null } }
```

Artemis's `connection` is `{system:"artemis", seedUrls, credentials, coreCredentials, tlsBundle,
managementUrlPattern}`. A `connection.system` other than `system`, or a system that is not loaded,
answers `400 unsupported-system`. Bootstrap servers are `host:port` (port defaults to 9092), never a
scheme or userinfo. `saslMechanism` is required exactly for `SASL_*`; credentials match the mechanism
(username and password for PLAIN and SCRAM, a token for OAUTHBEARER). Secrets are sealed per kind
(`vault.seal(aad(clusterId, kind))`) and never returned. `PATCH /api/v1/clusters/{id}` takes the same
`connection` (secrets omitted keep the stored one; a new host needs the secret again, as today).
Both systems use the same shapes: `PATCH` takes `{name?, description?, environmentId?, connection?}`
for Artemis and Kafka alike, and both dry runs (register and edit) return `ConnectionCheck`. A check
that ran answers `200` with its findings, a connection failure included (`failure`); only a malformed
request is a `400` problem. `supportedVersions` is the driver's range string (`[3.0.0,)`), which the
web renders in words ("3.0 and later"). The Artemis check fills the same fields from its per-node
probes, and adds its adoption preview as `adoption`.

The dry run returns the `ConnectionCheck` view:

```json
{ "system": "kafka", "version": {"text": "at least 3.5", "mode": "ZooKeeper", "exact": false},
  "supported": true, "clusterId": "Mk3OEYBSD34fcwNTJENDM2Qk",
  "alreadyRegistered": null | {"id": "…", "name": "…"},
  "nodes": [{"id": "1", "address": "k1:9092", "functions": ["BROKER","CONTROLLER"],
             "outcome": "OK|UNREACHABLE", "cause": "DNS|REFUSED|TIMEOUT|TLS|AUTHENTICATION|null",
             "message": "…"}],
  "quorum": {"available": true, "leader": "1", "voters": 3} | {"available": false, "reason": "…"},
  "access": [{"capability": "…", "status": "AVAILABLE|UNAVAILABLE|UNKNOWN", "reason": "…"}],
  "failure": null | {"cause": "DNS|REFUSED|TIMEOUT|TLS|AUTHENTICATION|AUTHORIZATION|UNSUPPORTED_VERSION",
                     "message": "…", "next": "…"} }
```

A cluster is already registered when its driver-reported cluster id matches a stored one. Partial
reach may be registered; the estate then shows it degraded.

The UI is `/clusters/new?system=<id>`, outside a cluster, `GlobalContext` as its way back: choose the
system (radio cards from `/systems`), the system's connection form, **Check connection**, then name,
description, environment and **Register** (disabled with its reason until a check passed on these
exact inputs). The Settings section, the cluster switcher and the empty estate link to it; the modal
goes. Seed and bootstrap fields parse every token (comma, space, newline) and show them as chips.

- Rejected: overloading the Jolokia form (a Kafka cluster is not a list of seed URLs); a modal
  wizard (too cramped for per-broker results, and not linkable).

### D3. Kind labels from the SDK (ADR-0214)
`ResourceIds.label` and `kindLabelOf` are removed. `KindView.label` becomes
`{singular, plural}` from `Kind.label`; every server caller (`ResourceViewMapper`,
`SubscriptionConfigurationReader`, `RoutingService`, `TriageViews`, `BulkRunMapper`) takes it from
`ClusterKinds`. Views that carried `kindLabel: string` carry `kindLabel: {singular, plural}`. The
web's `plural()` goes.

### D4. Health per system (ADR-0215)
A `ClusterHealthEvaluator` system extension. Artemis wraps `HaStateEvaluator` unchanged. Kafka maps
node conditions: CRITICAL on offline partitions, a lost quorum majority or no broker reachable;
DEGRADED on a fenced or unreachable broker, under-replicated partitions or a lagging voter; the
quorum part is UNKNOWN on ZooKeeper mode and stated in `notes`, never read as OK. `ClusterHealth`
gains `Level.UNKNOWN` only for "no verdict could be formed"; a ZooKeeper cluster with every broker
serving is OK with a note.

### D5. Backlog accuracy (in ADR-0216)
`SubscriptionView.totalMessageCount` is replaced by
`backlog: {value: long|null, accuracy: "EXACT"|"ESTIMATE"|"UNAVAILABLE", reason: string|null,
breakdown: boolean}`. Artemis is EXACT. `GET /api/v1/clusters/{c}/subscriptions/{kind}/{path}/backlog`
returns `{sampledAt, partitions: [{partition, committed|null, end, lag|null, reason|null}]}`, slowest
first. The web column "Depth" becomes "Backlog": "≈ 12 400" with "estimate" in its accessible name and
the reason in a popover; UNAVAILABLE reads "Unavailable" with the reason, never 0.

### D6. Shared screens on a partitioned log
- **Browse:** `GET …/messages?partition=<n>&from=earliest|latest|offset|time&value=<offset|ISO-8601>&size`
  where the destination's kind declares partitions. The page states the end offset when an offset is
  past it, a notice when retention moved the start, tombstones as "Tombstone (no value)", and that
  uncommitted and aborted records are not shown. `filter` on such a kind answers `400` with the
  driver's reason; the web disables the input with it.
- **Purge:** as today (`subscription:purge`), with the preview naming members or the rebalancing
  state from the driver's refusal; confirm disabled with that reason.
- **Truncate (new):** gated operation `destination.truncate`, permission `destination:truncate`, on a
  kind with the TRUNCATE verb. `POST /api/v1/clusters/{c}/destinations/{kind}/{path}:truncate[?dryRun]`
  with `{before: {offsets: {"0": 120}} | {time: ISO-8601}}`; the effect lists every group losing
  unread records with counts; refused on compacted topics with the driver's reason; audited with
  log-start offsets before and after.
- **Per-record move, copy, delete:** disabled with the driver's reason on the row and in bulk; the
  API refuses with it. Artemis-only actions (retry, expire, divert creation, by-selector retry) are
  gated by kind verbs from the server, so no shared component names a system.

### D7. The `system/kafka` module and its API (ADR-0216)
Backend `system/kafka` (`KafkaModule`, its read services and gated operations); web
`features/kafka` with views `system: 'kafka'` in the cluster's navigation. One table, `kafka_connection` (ADR-0204 prefix), holds a cluster's non-secret connection settings (bootstrap servers, protocol, mechanism, client id); the account is the cluster's `broker_credential` (a token held as its password), the TLS bundle `broker_tls`, and the cluster id a `broker_identity` of kind `CLUSTER_ID`. Routes are
under `/api/v1/clusters/{c}/kafka/`; `SystemNamespaceInterceptor` answers other systems 404 before
any broker call. Lists page with an opaque `cursor` and `limit` (default 100, max 500).

| Method and path | Body / answer | Permission |
|---|---|---|
| `GET groups?type&state&q&cursor` | `{items: [{id, type, state, members, lag: Backlog, readOnly: reason?}], next}` | `subscription:read` |
| `GET groups/{g}` | `{id, type, state, readOnly?, topics: [{topic, deleted, partitions: [{partition, committed?, end, lag?, reason?}]}], members: [{id, clientId, host, assignments}], sampledAt}` | `subscription:read` |
| `POST groups/{g}/offsets:preview` | `{topics?: [..], to: "earliest"\|"latest"\|"time"\|"offset", value?}` → `{partitions: [{topic, partition, current?, next, skipped, reread, note?}], members: [..], blocked: reason?, etag}` | `subscription:seek` |
| `POST groups/{g}/offsets:reset` | preview request + `etag` → result; `409 FAILED_PRECONDITION` (members, rebalancing), `412` stale, `400` out of range per partition | `subscription:seek` |
| `GET topics/{t}/config` | `{internal, entries: [{name, value?, source: TOPIC\|BROKER_DEFAULT\|STATIC\|DEFAULT, default?, readOnly, sensitive, unit?}]}`; sensitive values never sent | `destination:read` |
| `POST topics/{t}/config:preview` / `:apply` | `{set: {name: value}, remove: [name]}` → per-entry before/after, fallback value on remove, driver validation errors, `etag` | `destination:update` |
| `GET acls?principal&resource&cursor` | `{available, reason?, items: [{principal, host, operation, permission, resourceType, pattern, name}], next}` | `access-rule:read` |
| `POST acls:preview` / `:apply` | `{add: [acl], remove: [acl]}` → effective access before/after per principal and resource, `losses: ["User:app loses READ on topic orders"]`, DENY notes, `etag` | `access-rule:write` |
| `GET transactions` | `{items: [{id, state, startedAt?, timeout, hanging, blocked: [{topic, partition}]}]}` | `transaction:read` |
| `POST transactions/{id}:abort-preview` / `:abort` | effect names the blocked partitions and that the producer is fenced; `etag` | `transaction:abort` |
| `GET brokers` | `{brokers: [{id, rack?, address, reachable, reason?, leaders, underReplicated, offline}], quorum: {available, reason?, leader?, voters: [{id, lag, caughtUp}], majority}}` | `cluster:read` |

Every mutation is a `GatedOperation` with an `Effect` whose etag is the state key, so the hold, the
approval gate and the audit (before and after) apply as for shared operations; an approved request
re-checks its etag when it runs. Problems use the existing problem-details shape with the driver's
AIP-193 reason. Built-in roles: operator gains `subscription:seek`, `destination:update`,
`destination:truncate`, `transaction:read`, `transaction:abort`, `access-rule:read`; admin also
`access-rule:write`; viewer gains `transaction:read` and `access-rule:read`.

### D8. Plugin messaging on a partitioned log
- **Source.** `RegistrationSpec.source` becomes a new `@PluginApi` record `SubscriptionId(kind, path)`,
  the shared API's canonical id (`of(kind, segments…)`, `parse(id)`, `id()`), validated with the SDK's
  `ResourceId.parse` and exposing no SDK type. `ResourceRef` stays the permission key and send target.
  `Contract.VERSION` becomes 18; the guide's migration note: `ResourceRef.subscription(q)` →
  `SubscriptionId.of("artemis.queue", q)`.
- **Stored.** `plugin_message_registration` holds `source_kind`, `source_path` (baseline edited, ADR-0192);
  `plugin_message_registration_node` gets a uuid `id` key, a nullable `node_id` with
  `UNIQUE NULLS NOT DISTINCT (registration_id, node_id)`, and `releases_counted boolean`.
- **Per node or cluster-wide.** The reconciler asks `ClusterKinds.partitioned(cluster, kind)` (SDK-derived).
  A partitioned source gets one drain at `Scope.whole()` on the replica that owns the cluster (existing
  `ClusterOwnership` and the `PLUGIN_MESSAGING` lock), its state in one row with a null node ("whole
  cluster"). Start failures map by status: NOT_FOUND → PENDING; FAILED_PRECONDITION, UNIMPLEMENTED,
  INVALID_ARGUMENT → FAILED with the SDK's message; others PENDING; retried every pass.
- **Settlement.** `RetryPolicy.defaults()` (SYSTEM on Artemis and share groups; on a consumer group 10
  attempts in place, then the SDK's `<topic>.dlt`, so the offset passes a rejected record only once
  dead-lettered). Ordering GROUP where the kind declares it (on Kafka: by group id, else record key,
  within a partition), else NONE (share groups). A cluster-wide drain uses
  `FlowControl(concurrency, 0, 0)`. ACCEPT → Accept; REJECT and handler failures → counted Release;
  RELEASE → uncounted Release.
  On a consumer group only rejections count: a rejected record is retried in place and then
  dead-lettered, and an uncounted release is redelivered in place without counting (the plugin sees
  delivery count 1 on each in-place retry). Only a share group counts a release.
- **Counted releases.** OpenBroker 0.6.0's `SubscriptionCapabilities` declares whether a release counts
  as a delivery attempt; an ACTIVE registration on such a subscription says so in its state and sets
  `releases_counted`.
- **Access.** The permission resource is the subscription's first path segment (the queue; on Kafka the
  group), as for the log purge and offsets.
- **Taps.** Refused at registration where the cluster's system has no `MessageTaps`, with the reason
  naming the system from `ClusterKinds`; the reconciler marks any stored tap there FAILED as a guard.

### D9. MCP
`kafka_list_groups`, `kafka_describe_group`, `kafka_preview_offset_reset`, `kafka_reset_offsets`,
`kafka_topic_config`, `kafka_set_topic_config`, `kafka_list_acls`, `kafka_abort_transaction`, with
`.forSystem(KafkaModule.SYSTEM)`; mutating tools are `MUTATE`, previewed first and held by `McpGate`.
Shared enums go into `McpToolCatalog`; `McpToolSchemaBudgetTest` is raised with a comment.

### D10. Stacks and demo
Dev compose adds one `apache/kafka` 4.4 KRaft broker with share groups on. The demo compose adds three
KRaft brokers; `scripts/demo-seed.sh` registers them and creates topics (one compacted), two groups
(one lagging), a share group and one hanging transaction, with the image's CLI, bash 3.2 only.

## Risks / Trade-offs

- [Lag polling on large clusters] → one batched Admin read per cycle on the scrape schedule; screens
  show the sample's age.
- [Offset reset and truncate are irreversible in effect] → empty group, preview with etag, held
  confirmation, approval gate, audit before and after (an operator can reset back).
- [A shared screen still assumes Artemis] → the reviewer's pass and the sweep cover every shared
  screen on a Kafka cluster; no shared component names a system.
- [ZooKeeper-mode versions are a lower bound] → shown as "at least X", never exact.
- [Breaking JSON] → registration, kind labels, backlog; marked `!` with the migration in the commits.
