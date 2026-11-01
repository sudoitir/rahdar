# Design: RabbitMQ in Rahdar

The driver is `openbroker-rabbitmq` (system id `rabbitmq`) from OpenBroker
0.9.0. This document was read against Rahdar `feat/kafka-2-8-support` @ `11082049` (openbroker 0.8.0) and the
OpenBroker design `openspec/changes/19f-openbroker-rabbitmq/design.md`. It covers only the Rahdar repo.


---

## 0. Approach in a few sentences

A new `system/rabbitmq` module, built on the Kafka module's shape: a `ClusterRegistrar`, `ConnectionOptions`,
`ClusterScrape` and `ClusterHealthEvaluator` for RabbitMQ, two tables (`rabbitmq_connection`,
`rabbitmq_health`), and a `/clusters/{c}/rabbitmq/…` namespace for virtual hosts, bindings, policies,
permissions, shovels and federation links, with web views in `features/rabbitmq` tagged `system: 'rabbitmq'`.
Everything the SDK models by role (exchanges as destinations, the three queue kinds as subscriptions, consumers,
connections, channels) appears in the shared screens with no RabbitMQ word in shared code.

The one shared-core change this needs is the **whole-cluster operation path** (D5). Rahdar's shared write paths
(delete, purge, send, bulk) are still keyed by a queue *name* and fanned out *per node* over management URLs
(`QueueLifecycleService`, `BrokerCommands.targets`, `BrokerMessages.queueId` → `ResourceIds.of(c, SUBSCRIPTION,
name)`). That breaks on RabbitMQ twice: it has three subscription kinds (`ResourceIds.kind` takes the first) and
two-segment paths `[vhost, name]`. So, for a cluster that is *read as one* (its stored nodes have no management
URL, the rule `PagedListService.gather` already uses), the shared controllers pass the `ResourceId` they already
resolved (`ResourceTargets.require`) to SDK operations at `Scope.whole()`: preview, etag, gate, audit, execute.
The same gated operation types (`subscription.delete`, `subscription.purge`, `destination.delete`) carry an
optional id, so an approval policy on `subscription.delete` holds a RabbitMQ delete too.

**Rejected: RabbitMQ-only routes for queue and exchange delete, purge and send** (`/rabbitmq/queues/…:purge`).
Each shared action would then be either broken or hidden on the shared Subscriptions and Destinations screens,
ADR-0206 says the next system's resources use the same routes, and an approval policy on `subscription.delete`
would silently not hold a RabbitMQ delete. The whole-cluster path also repairs the same latent break for Kafka
(a topic delete through the shared route hits "This cluster has no node with a management URL").

**Rejected: a get-and-requeue browse for classic and quorum queues.** It redelivers, bumps delivery counts and
reorders; the driver never offers it and Rahdar never asks (ADR-0033 in OpenBroker).

---

## 1. What was verified in the code (facts the design leans on)

| Fact | Where |
|---|---|
| The registration union is a sealed, Jackson-discriminated `Connection` / `ConnectionUpdate` in **platform** (`ClusterRequests`), not in the system module; `ClusterRegistrar.detail` returns `KafkaConnectionDetail` (a Kafka type in the platform contract) | `platform/clusters/web/ClusterRequests.java:113-209,278-366`, `ClusterRegistrar.java:69`, `ClusterViews.java:270-302`, `ClusterService.java:694-713` |
| `GET /systems` `connection` is Rahdar's `ConnectionOptions.Entry` (`jolokia-seeds`, `bootstrap`), not the driver's; the web picks the form on it | `platform/broker/ConnectionOptions.java`, `SystemsController.java`, `web/.../RegisterClusterPage.tsx:19` |
| The account is one `broker_credential` row of kind DATA, sealed; TLS is `broker_tls` (`StoredClusterAccount`) | `SystemRegistration.java` |
| The cluster identity claim is `broker_identity` kind `CLUSTER_ID` = `ConnectionCheck.clusterId` | `SystemRegistration.save` |
| A cluster whose stored nodes have no management URL is read as one by its first node | `PagedListService.java:214-231` |
| `BrokerCommands.targets` refuses a cluster with no management-URL node | `BrokerCommands.java:369-392` |
| `ResourceIds.of(c, class, name)` takes the **first** kind of the class and one segment | `ResourceIds.java` |
| `ClusterKinds.partitioned`: a kind is a log only when the system has a PARTITION kind; the RabbitMQ driver declares none | `ClusterKinds.java:67-90` |
| `BrokerMessages.browseLog` parses the next offset out of the driver's cursor token (`substring(indexOf(':')+1)`) — the token is opaque by contract; RabbitMQ's is `base64url("<offset>|<first_timestamp>")` | `BrokerMessages.java:126-132` |
| `QueueRow.of` gives an ESTIMATE its reason only from a `*.backlog_estimate` condition | `QueueRow.java:65-81` |
| `SendMessageRequest` has no routing key; `sendTarget` builds `SendTarget.destination(id)` without one | `MessageRequests.java:24`, `BrokerMessages.java:473-478` |
| `MessageService.requireOffered` hard-codes `"messages.move"`/`"messages.delete"` (SDK 0.9 adds constants) | `MessageService.java:423-431` |
| Two tests construct SDK `Preview` (gets a `basis` component in 0.9) | `TruncateServiceTest`, `KafkaHangingTransactionsTest` |
| `access-rule:read|write` are defined by `KafkaModule`, but they are role-named and RabbitMQ needs them too | `KafkaModule.java` |
| Built-in role grants live in the security baseline | `db/changelog/kernel/security/changes/0001-baseline.sql:367-430` |
| `/clusters/{c}/{system}/**` answers 404 for other systems automatically once the driver is loaded | `SystemNamespaceInterceptor` |
| CI runs every test class round-robin over three shards; Kafka real-broker tests pick their image from a system property default; Testcontainers is 2.0.5 and `org.testcontainers:testcontainers-rabbitmq:2.0.5` exists on Central (pom 200) | `.github/workflows/ci.yml:130-177`, `pom.xml` |
| Web: nav entries take `system` or `resourceClass`; a cluster lands on its first offered `observe` entry; verbs gate controls (`useKindVerbRefusal`); slot contributions take `system` | `kernel/nav`, `kernel/auth/kindVerbs.ts`, `kernel/slots.ts:150-170` |

Not verified here (no Docker): the Testcontainers 2.x class name of the RabbitMQ container (expected
`org.testcontainers.rabbitmq.RabbitMQContainer`; fall back to `GenericContainer` as OpenBroker's tests do).

---

## 2. Decisions

### D1. Dependency

`pom.xml`: `openbroker.version` 0.8.0 → **0.9.0**, add `io.github.sudoitir:openbroker-rabbitmq`. Rahdar's CI
refuses a SNAPSHOT `openbroker.version`, so Rahdar merges only once 0.9.0 is on Central; until then implementers
install OpenBroker into `~/.m2` as `0.9.0-SNAPSHOT` and keep the pom on the SNAPSHOT on the branch only.
0.9.0's breaks Rahdar must absorb: `Preview` gains `basis` (two tests), `Capability.MOVE_MESSAGES /
COPY_MESSAGES / DELETE_MESSAGES` replace the strings in `MessageService.requireOffered`.

### D2. Registration (ADR-0219 applied to RabbitMQ)

**Wire shape** (`platform/clusters/web/ClusterRequests`):

```java
public record RabbitMqConnection(
        @NotBlank @Schema(allowableValues = "rabbitmq") String system,
        @NotEmpty @Size(max = 16) List<@NotBlank String> managementUrls, // http(s)://host:port[/prefix], no user info
        @Size(max = 32) List<@NotBlank String> amqpEndpoints,           // amqp(s)://host:port; empty = no data plane
        @Valid RabbitMqCredentials credentials,                          // username+password, or token
        String tlsBundle,                                                // trust store and client certificate
        String eventVhost,                                               // default "/"
        Integer watchIntervalSeconds)                                    // default 10, at least 1
        implements Connection {}
public record RabbitMqCredentials(String username, String password, String token) { /* toString masks */ }
public record RabbitMqConnectionUpdate(...same fields, all optional...) implements ConnectionUpdate {}
```

One account serves both planes (HTTP Basic or Bearer; AMQP PLAIN, or PLAIN with an empty user and the token as
password, which is how the OAuth 2 plugin takes it). `@AssertTrue`: no user info in any URL; schemes in
`{http,https}` / `{amqp,amqps}`; an `https`/`amqps` entry without a TLS bundle is allowed (JVM trust) but an
`amqps` endpoint with a bundle verifies host names. A secret is required again when the management URLs, the
AMQP endpoints or the user name change (the Kafka rule).

**`ConnectionOptions.Entry`** gains `MANAGEMENT_API("management-api")`; `GET /systems` lists
`{id: 'rabbitmq', name: 'RabbitMQ', supportedVersions: '[3.13.0,)', connection: 'management-api'}` (name and
range come from the driver's `BrokerSystem`).

**`ClusterRegistrar.detail`** returns a sealed `SystemConnectionDetail` (permits `KafkaConnectionDetail`,
`RabbitMqConnectionDetail`) instead of `KafkaConnectionDetail`; `ClusterConnectionDetail` gains a nullable
`rabbitmq` beside `kafka`, filled by pattern match in `ClusterService.connectionView`.
`RabbitMqConnectionDetail(managementUrls, amqpEndpoints, username, tokenAuth, secretStored, tlsBundle,
eventVhost, watchIntervalSeconds)`, never a secret.

**Table** (`db/changelog/system/rabbitmq/changes/0001-baseline.sql`, ADR-0192, column order per
non-negotiable 7):

```sql
CREATE TABLE rabbitmq_connection (
    watch_interval_seconds integer NOT NULL DEFAULT 10,
    management_urls text NOT NULL,         -- comma-separated, as given, no user info
    amqp_endpoints text,                   -- comma-separated amqp(s)://host:port, null = no data plane
    auth text NOT NULL,                    -- PASSWORD | TOKEN
    event_vhost text NOT NULL DEFAULT '/',
    cluster_id uuid NOT NULL,
    CONSTRAINT ck_rabbitmq_connection_auth CHECK (auth = ANY (ARRAY['PASSWORD','TOKEN'])),
    CONSTRAINT ck_rabbitmq_connection_watch CHECK (watch_interval_seconds >= 1));
CREATE TABLE rabbitmq_health ( evaluated_at timestamptz NOT NULL, level text NOT NULL, serving jsonb NOT NULL,
    notes jsonb NOT NULL, cluster_id uuid NOT NULL, behind boolean NOT NULL, CHECK level ...)
    WITH (fillfactor='50', autovacuum_vacuum_scale_factor='0.0', autovacuum_vacuum_threshold='50');
-- PKs on cluster_id, FKs to cluster(id) ON DELETE CASCADE, COMMENT ON TABLE for both (copy kafka's wording).
```

Plus `changelog.xml` (`includeAll changes/`) and one include line in `db.changelog-master.xml` after Kafka's.
Secrets stay in `broker_credential` (kind DATA, the token held as the password) and `broker_tls`, unchanged.

**The check** (`RabbitMqRegistrar.check`, nothing stored, never throws for an unreachable cluster):

1. Validate and normalise (`ManagementUrls`, `AmqpEndpoints`: parse every token, default ports 15672/15671,
   5672/5671, refuse user info; `MAX_MANAGEMENT = 16`, `MAX_AMQP = 32`).
2. `gateway.verify("rabbitmq", spec, credentials, tls)` with every given endpoint as a cluster endpoint: the
   driver tries the management URLs in order, describes every node from `/api/nodes`, and returns the access
   probe. A `BrokerException` becomes `Failure(cause, message, next)` by reason: UNREACHABLE → REFUSED / DNS /
   TIMEOUT, TLS_HANDSHAKE_FAILED → TLS, CREDENTIALS_REJECTED → AUTHENTICATION, NOT_AUTHORIZED → AUTHORIZATION
   (next = the driver's remedy, e.g. `rabbitmqctl set_user_tags <user> monitoring`), ENDPOINT_NOT_FOUND /
   UNEXPECTED_SYSTEM → **`WRONG_ENDPOINT`** (new: "answers, but is not a RabbitMQ management API"; next names
   `rabbitmq-plugins enable rabbitmq_management` or the path prefix), UNSUPPORTED_VERSION → UNSUPPORTED_VERSION.
3. **Every endpoint is named** (new nullable `ConnectionCheck.endpoints: List<EndpointReach(plane, address,
   outcome, cause, message)>`, system-neutral): each management URL is verified alone in parallel (bounded
   executor, the verify's own deadline), so "15672 on rabbit-2 refused" is visible even when rabbit-1 answered;
   each AMQP endpoint gets a TCP probe (`AmqpProbe`, a copy of Kafka's `BootstrapProbe`). Every node row
   (`NodeReach`, id = native node name `rabbit@host`) says running or not, its version, alarms (`FULL`
   conditions), and partitions (`OUT_OF_SYNC`).
4. Failure when no management URL answers; **`DATA_UNREACHABLE`** (new) when AMQP endpoints were given and none
   accepts a connection ("browse, send and receive need one; register without AMQP endpoints to manage only");
   a partly reached set registers after the warning (ADR-0219).
5. `version` = the lowest node version (`exact` true); `mode` = "Cluster of N nodes" (or "Single node");
   `quorum` null; `access` = the driver's assessments (management.read/write, notifications path, browse
   "Streams only…", event exchange, shovels, federation, streams) with remedies.
6. `clusterId` = the `cluster_name` the driver reports on every node's `Membership` (see open question 1).

**Stored nodes** take DATA endpoints only (the AMQP listeners), never MANAGEMENT ones, exactly as Kafka stores
`dataEndpoints(node)`: a stored management URL would make Artemis-only paths (`BrokerCommands`, Jolokia pollers)
treat the node as theirs, and it keeps the "read as one" rule of D5 true.

### D3. Connection options

`RabbitMqConnectionOptions implements ConnectionOptions`: `entry()` MANAGEMENT_API; `spec(clusterId, nodes)` =
`ClusterSpec(nodes without endpoints, management URLs as MANAGEMENT/http(s) + amqp endpoints as DATA/amqp(s),
{rabbitmq.watch_interval: PT<n>S, rabbitmq.event_vhost: v})` (the stored nodes' listener addresses are often
`::` and are for display only); `credentials` asked on every connect: `usernamePassword` or
`bearerToken(token, now + 1 day)`; `tls` from `broker_tls`.

### D4. Health and scrape (ADR-0215)

`RabbitMqScrape implements ClusterScrape` (copy of `KafkaScrape`'s structure, meter
`rahdar.rabbitmq.scrape.rounds`):

- `nodes(cluster, cycle)`: one `nodes().describe(Scope.whole())`; apply each stored node by native name
  (`recorder.applyTierA`), adopt unstored nodes, retire a node unlisted for 10 minutes with a
  `NODE_LEFT_CLUSTER` audit row; a different `cluster_name` than registered stops the read and publishes
  CRITICAL "now reports cluster name X; if it was renamed, check and save the connection again to claim it".
- `subscriptions(cluster)`: pages of `ResourceQuery.of(SUBSCRIPTION).limit(200)` at `Scope.whole()`; each row
  tagged with the stored node that leads or hosts the queue (placement LEADER or INSTANCE, by native name),
  else the first stored node. On the same pass it counts quorum queues and streams that are UNDER_REPLICATED
  and those with fewer than a majority of members online, kept per cluster for the next `nodes` verdict
  (Kafka's `KafkaPartitions.due/refresh` idea, without a second read).
- `RabbitMqHealth implements ClusterHealthEvaluator` reads `rabbitmq_health` only (stale after 2 minutes →
  UNKNOWN with the reason). `judge(...)`:
  - **CRITICAL**: no management URL answers; any node reports a network partition (`OUT_OF_SYNC`); any memory or
    disk alarm (`FULL`: every publisher in the cluster is blocked); a quorum queue or stream without a majority
    online (counted, named up to 5).
  - **DEGRADED**: a node not running; under-replicated quorum queues or streams; unsynchronised classic mirrors
    (3.13).
  - What could not be read (queue placements before the first subscriptions pass) is a note, never OK.
- `RabbitMqHealthStore` (a copy of `KafkaHealthStore` on `rabbitmq_health`), `RabbitMqClusterDeleted` (forget
  meters and caches on `ClusterDeleted`, as `KafkaClusterDeleted`).

### D5. The whole-cluster operation path (shared; ADR-0224)

- `ClusterKinds.readAsOne(clusterId)`: true when every stored node of the cluster lacks a management URL (the
  rule `PagedListService.gather` inlines today; it is refactored to call this). True for RabbitMQ and Kafka,
  false for Artemis.
- New `platform/broker/WholeClusterOperations` (id-addressed, no gate, no audit; the features own those):
  `Preview preview(cluster, ResourceId, Operation)` and `OperationResult execute(cluster, ResourceId,
  Operation, String etag)` at `Scope.whole()` with `Precondition.ifMatch(etag)`; a stale etag maps to `412
  stale` with "it changed since the preview". A small `IdentityBasis basis(Preview)` exposes the SDK's
  `EtagBasis` to the views (D9).
- **Delete** (`ResourceLifecycleController.delete`): when `readAsOne`, `QueueLifecycleService.deleteQueue` /
  `deleteAddress` take the resolved `Target` and run `Operation.Delete(ifEmpty=false, disconnectConsumers)`
  through `WholeClusterOperations`: dry run = the preview's estimate (messages lost, consumers cut, bindings
  removed) and basis; real run = audit begin, re-preview, compare etag, execute, audit finish. A delete with
  consumers and no `disconnectConsumers` is refused from the preview (`HAS_CONSUMERS`) with no broker change.
- **Purge** (`MessageController.purge`): when `readAsOne` and not `partitioned`, `MessageService.purge` takes
  the id: `Operation.Purge()`, estimate = `messages_ready` (ESTIMATE), reason "Unacknowledged messages stay".
  The bulk cap applies to the estimate as today.
- **Gated params** gain the id: `QueueDeleteParams(clusterId, queue, id, override, disconnectConsumers)`,
  `AddressDeleteParams(clusterId, address, id)`, `QueuePurgeParams(clusterId, queue, id, nodeId, override)`,
  with `id` the canonical `ResourceId` text or null on a per-node system; each op's `version()` goes to 2, its
  `estimate` uses the preview's count and etag on the id path, and `display` adds a "Virtual host"-free generic
  row "Kind" (the kind's label). `queue` stays the display name (`ResourceId.name()`).
- **Bulk** (`BulkService`, `BulkRunner`): an item of a `readAsOne` cluster runs through the same single-id
  commands (the branch already exists for `partitioned`).
- **Plugin messaging** (`PluginMessagingReconciler:168`): a source on a `readAsOne` cluster gets the one
  whole-cluster drain that partitioned sources get. A stream source is refused by the driver (`UNIMPLEMENTED`)
  and lands FAILED with its message (existing mapping).
- **Access key**: on the id path the permission resource is `ResourceId.name()` (the queue or exchange), as on
  Artemis. Team patterns do not see the vhost in this change (open question 2).

### D6. Shared screens on a RabbitMQ cluster

| Screen / route | On RabbitMQ | Change needed |
|---|---|---|
| Estate, cluster rail, health | `system {rabbitmq, RabbitMQ}`, health from D4 | none beyond D4 |
| Subscriptions (`resourceClass: SUBSCRIPTION`) | classic, quorum and stream queues, each with its kind label from the driver; backlog column per D10 | QueueRow fallback reason (D10), stream masking (D10) |
| Subscription detail drawer | per-node breakdown shows the hosting / leading node | RabbitMQ panel contributed to `subscription.detail.panels` with `system: 'rabbitmq'` (type, arguments, effective policy, members/leader/online, delivery limit; streams: retained messages, committed offset, first timestamp, max age) |
| Destinations (`DESTINATION`) | exchanges (type, durable, internal); `amq.*` delete refused by the driver with its reason | "Publish" action contributed to `destination.actions` with `system: 'rabbitmq'` (D8) |
| Consumers / Channels / Connections | work unchanged from the kinds; connection close through the existing `:close` route (id-addressed already); channels show "RabbitMQ channels cannot be closed" from the verb gate | none |
| Producers (`PRODUCER`) | not listed: no kind of that class | none (ADR-0222) |
| Messages of a queue | classic/quorum: **no fetch**; the page shows the kind's refusal and the driver's `messages.browse` reason from the cluster's access findings; Send and Purge stay. Stream: read as a log (D7) | D7, plus `kindVerbs` `browse` refusal words |
| Create subscription / destination | a RabbitMQ form contributed to two new slots (D11/D13); the Artemis form is not shown on a system that contributes one | new slots `subscriptions.create`, `destinations.create` |
| Truncate, pause/resume, reset counters, move/copy/delete messages, retry, expire | disabled with the verb refusal (no verb) | none |
| Artemis-only views (Topology, Flow, Requests, Routes, SQL, Transfers, Setup review, Dead letters, Broker configuration) and Kafka-only views (Consumer groups, Transactions, Brokers, ACLs) | not listed (ADR-0222) | none |
| Metrics, Alerts | system-neutral; BACKLOG/IN_FLIGHT/ENQUEUED/ACKNOWLEDGED/CONSUMER_COUNT from the driver | none |
| Consumer health (triage, untagged) | shows its existing "reads Artemis consumers" unknown state | none in this change (pre-existing gap, also on Kafka; noted in the guide) |

`KafkaSharedRoutesRealBrokerTest`'s twin, `RabbitMqSharedRoutesRealBrokerTest`, walks every shared read route of
a RabbitMQ cluster and asserts no 5xx.

### D7. Browse: streams as a one-partition log

- `LogBounds` gains `Set<KindId> logs(UUID clusterId)` (default empty). `ClusterKinds.kindsOf` marks a kind
  `partitioned` when the existing rule says so **or** the system's `LogBounds.logs` names it.
  `RabbitMqLogBounds` names `rabbitmq.stream`; `of(cluster, stream, 0)` = start: the position of the first
  message of a one-message browse from `Earliest` (or end when empty); end: `rabbitmq.committed_offset + 1` from
  one `resources().get`. Partition must be 0, else 400.
- `BrokerMessages.browseLog` stops parsing the cursor token: next = the last returned message's
  `ref().position()` + 1 when `result.next()` is present (Kafka's behaviour is unchanged; compacted gaps read
  forward). A test with a token that is not `p:<n>` pins it.
- `MessageService.browseLog`'s access check uses `ResourceRef.destination(log.name())` today; on a stream (a
  SUBSCRIPTION) it becomes `ResourceRef.subscription(...)` for a SUBSCRIPTION target. The page says "A stream is
  read by offset; reading never removes a message." instead of the Kafka compaction sentence when the kind is a
  subscription log, and `uncommittedHidden` is false.
- Web: `PartitionControls` hides the partition field when `partitions === 1`; `MessagesView` heading reads
  "Stream" for a one-partition log; `isPartitionedLog`'s pre-load guess stays Kafka's (a stream's first paint
  shows the queue toolbar until kinds load; acceptable).
- Classic/quorum: `MessageController.browse` refuses a kind without the `browse` verb with `409
  unavailable-on-kind` and the driver's reason (`access().check(BROWSE, id)` answers from the kind, no I/O)
  **before** any broker read. Test: the gateway's `messages()` is never called.

### D8. Send and NOT_ROUTED

- `SendMessageRequest` gains nullable `routingKey`. `MessageController.send` accepts SUBSCRIPTION and, on a
  `readAsOne` cluster, DESTINATION targets; `MessageService.send` on that path sends by id:
  `SendTarget.subscription(id)` or `SendTarget.destination(id, routingKey)`. A routing key on a subscription
  target, or on a system whose driver refuses it, is 400 with the driver's words.
- `BrokerFailures.translate`: `ErrorReasons.NOT_ROUTED` → `409 not-routed` "No queue received the message: the
  exchange has no binding that matches routing key '<k>'. Nothing was stored." (the key from the request, never
  the body); `DESTINATION_FULL` → `409 destination-full`; IN_DOUBT keeps `SendInDoubtException`.
- Web: the RabbitMQ "Publish" dialog (exchange row action; body, content type, headers, routing key,
  persistent) shows `not-routed` inline as a warning Notice with a link to Bindings filtered by that exchange;
  the shared queue Send works unchanged (default exchange; never unrouted).

### D9. Etag basis shown honestly

- The views that carry a preview's etag gain `identityBasis: 'INCARNATION' | 'CONFIGURATION'`:
  `LifecycleOutcomeView`, `DryRunView`, and the RabbitMQ namespace previews. Artemis and Kafka topics say
  INCARNATION; RabbitMQ always CONFIGURATION.
- The gate's `Effect.detail` for a CONFIGURATION basis carries the sentence, so a held request and its approver
  read it too: "RabbitMQ does not tell a recreated resource apart: if it is deleted and created again with the
  same configuration before this runs, this applies to the new one."
- Web: one shared `IdentityBasisNote` in `ui/` (a `Notice`, info) rendered by the delete and purge
  confirmations (`SubscriptionLifecycleActions`, `PurgeSubscription`) and the RabbitMQ previews when the basis is
  CONFIGURATION. No system name in the shared component: it says "This system does not tell a recreated
  resource apart…", the RabbitMQ views say RabbitMQ.

### D10. Backlog accuracy

- Classic and quorum: BACKLOG = `messages_ready`, ESTIMATE (management statistics are sampled). `QueueRow.of`
  falls back to the reason "The system's counters are sampled, so this is an estimate." when an ESTIMATE has no
  `*.backlog_estimate` condition (shared, system-neutral).
- Streams: a stream keeps every message until retention removes it, so `messages_ready` is its size, not work
  waiting. `RabbitMqScrape` replaces a stream row's backlog with UNAVAILABLE and the reason "A stream keeps its
  messages until retention removes them; it has no backlog. Its size is on the queue's panel." (new
  `QueueRow.withoutBacklog(reason)`), so streams never top a backlog sort. See note 9.3.

### D11. The `system/rabbitmq` namespace (ADR-0223)

Routes under `/api/v1/clusters/{c}/rabbitmq/`, lists paged by opaque `cursor` and `limit` (default 100, max
500). Every mutation: `:preview` → `{…, effect, etag, identityBasis: 'CONFIGURATION'}`, then the real call with
`etag` → a `GatedOperation` (preview, held confirmation in the UI, approval gate, audit before and after,
etag checked again on run; stale → 412).

| Method and path | Answer / body | Permission | Gated op |
|---|---|---|---|
| `GET nodes` | `{nodes: [{name, version, running, uptime, alarms: [memory\|disk], partitions: [], listeners: [{protocol, port}], plugins: []}], clusterName}` | `cluster:read` | — |
| `GET vhosts` | `[{name, description, tags, defaultQueueType, queues, messages, limits}]` | `namespace:read` | — |
| `POST vhosts:preview` / `vhosts` | `{name, description?, tags?, defaultQueueType?}` | `namespace:write` | `namespace.create` |
| `PATCH vhosts/{v}:preview` / `vhosts/{v}` | description, tags, default queue type | `namespace:write` | `namespace.update` |
| `DELETE vhosts/{v}:preview` / `vhosts/{v}` | effect names every queue and its messages (the driver's preview) | `namespace:delete` | `namespace.delete` (DESTRUCTIVE) |
| `POST queues:preview` / `queues` (the create slot's backend) | `{vhost, name, type: classic\|quorum\|stream, durable, autoDelete, arguments}` → `Operation.Create` on the kind | `subscription:create` | — (create is not gated today; kept so) |
| `POST exchanges:preview` / `exchanges` | `{vhost, name, type, durable, autoDelete, internal, arguments}` | `destination:create` | — |
| `GET bindings?vhost&source&destination&cursor` | `[{id, vhost, source, destination, destinationType, routingKey, arguments}]` (queue and exchange bindings) | `destination:read` | — |
| `POST bindings:preview` / `bindings`, `DELETE bindings/{id}` (+`:preview`) | create/delete a binding or exchange-binding | `route:write` | `route.create`, `route.delete` |
| `GET policies?vhost` | `[{id, vhost, name, pattern, applyTo, priority, definition, operator: bool, matches: n}]` | `policy:read` | — |
| `POST policies:preview` / `PUT policies/{vhost}/{name}` / `DELETE` | preview lists the queues and exchanges the pattern matches now (Rahdar matches with Java regex over the listings: stated as "as Rahdar matches it") and those another policy of higher priority already wins | `policy:write` | `policy.set`, `policy.clear` |
| `GET users` | `[{name, tags}]` (never a hash) | `access-rule:read` | — |
| `GET permissions?vhost&user` | `[{vhost, user, configure, write, read, topic: [{exchange, write, read}]}]` | `access-rule:read` | — |
| `PUT permissions/{vhost}/{user}` / `DELETE` (+`:preview`) (topic permissions likewise) | preview: the user's rights before and after | `access-rule:write` | `access-rule.set`, `access-rule.clear` (ACCESS_CONTROL trait) |
| `GET shovels` | `[{id, vhost, name, state, source, destination, managedBy}]`; URIs redacted by the driver; `available:false` + remedy without the plugin | `destination:read` | — |
| `DELETE shovels/{id}` (+`:preview`) | a static shovel refused with the driver's reason | `route:write` | `route.delete` |
| `GET federation-links` | `[{vhost, upstream, type, name, status, source}]`; `available:false` + remedy without the plugin | `destination:read` | — |

Service classes (package-private unless a controller needs them): `RabbitMqNodes`, `RabbitMqVhosts`,
`RabbitMqQueues` (create), `RabbitMqExchanges` (create), `RabbitMqBindings`, `RabbitMqPolicies` (+ pure
`PolicyMatches`), `RabbitMqPermissions`, `RabbitMqShovels`, `RabbitMqFederation`, `RabbitMqGatedOperations`
(the beans, like `KafkaGatedOperations`), `RabbitMqReservedNames implements ReservedNames` (`amq.` prefix for
exchanges and queues), `RabbitMqLogBounds`, web `RabbitMqController`, `RabbitMqViews`.
Every read and write goes through the SDK's `resources()` / `operations()`; nothing calls `unwrap`.

**Permissions** (ADR-0209 naming). `namespace:read` (under `cluster:read`), `namespace:write`,
`namespace:delete`, `policy:read`, `policy:write` defined in `RabbitMqModule.DESCRIPTOR`.
`access-rule:read|write` move from `KafkaModule` to `feature/resources/ResourcePermissions` (shared, now two
systems use them; same ids, no data change). Built-in roles (security baseline, edited in place): operator +
`namespace:read`, `namespace:write`, `policy:read`, `policy:write`; viewer + `namespace:read`, `policy:read`;
admin holds all. `namespace:delete` and `access-rule:write` stay admin-only (a vhost delete destroys every queue
in it without asking; access is access control).

**Audit** action names: `CREATE_NAMESPACE`, `UPDATE_NAMESPACE`, `DELETE_NAMESPACE`, `CREATE_ROUTE`,
`DELETE_ROUTE`, `SET_POLICY`, `CLEAR_POLICY`, `SET_ACCESS_RULE`, `CLEAR_ACCESS_RULE`, `CREATE_SUBSCRIPTION`,
`CREATE_DESTINATION`; params never hold a secret (no shovel URI is ever taken).

### D12. MCP

Read-only tools with `.forSystem("rabbitmq")`: `rabbitmq_nodes`, `rabbitmq_list_vhosts`,
`rabbitmq_list_bindings`, `rabbitmq_list_policies`, `rabbitmq_list_permissions`, `rabbitmq_list_shovels`
(shovels and federation links). Mutations stay in the UI and REST in this change (default; open question 4).
`McpToolSchemaBudgetTest` raised with a comment if needed.

### D13. Web

- `features/clusters`: `RabbitMqRegistrationForm.tsx`, `RabbitMqConnectionFields.tsx`,
  `RabbitMqConnectionSettings.tsx`, `rabbitmqConnection.ts` (fields, defaults, parse URLs, `secretRequired`).
  `RegisterClusterPage` picks the form by `system.connection` (`'management-api'`); `ClusterSettings` renders
  `data.connection.rabbitmq` and its `connectionWords`; `connectionCheck.ts` `SYSTEM_NAMES.rabbitmq =
  'RabbitMQ'`; `checkColumns.nodeName` names a node by its id unless the system numbers brokers (Kafka keeps
  "Broker 1"; RabbitMQ shows `rabbit@host`); `ConnectionCheckPanel` shows the new `endpoints` list and the
  cluster name ("Cluster name" for RabbitMQ, "Cluster id" for Kafka, from the system); `RegistrationFailure`
  and `checkColumns` gain words for `WRONG_ENDPOINT` and `DATA_UNREACHABLE`.
- `features/rabbitmq` (`feature.ts`, `api.ts`, `words.ts`, views). Nav, all `system: 'rabbitmq'`:

  | Group | Order | Label | Path | Permission |
  |---|---|---|---|---|
  | observe | 10 | Nodes | `rabbitmq/nodes` | `cluster:read` (**the landing view**) |
  | messaging | 30 | Bindings | `rabbitmq/bindings` | `destination:read` |
  | messaging | 35 | Shovels and federation | `rabbitmq/links` | `destination:read` |
  | configuration | 20 | Virtual hosts | `rabbitmq/vhosts` | `namespace:read` |
  | configuration | 30 | Policies | `rabbitmq/policies` | `policy:read` |
  | configuration | 40 | Permissions | `rabbitmq/permissions` | `access-rule:read` |

  Slots: `subscription.detail.panels` (QueuePanel), `destination.actions` (Publish, section `open`; "Bindings"
  link), `subscriptions.create` (CreateQueueForm: vhost select, name, type with each type's one-line meaning,
  durable, arguments rows, delivery limit for quorum, max age for streams), `destinations.create`
  (CreateExchangeForm). Every mutation uses the held confirmation (ADR-0186) for destructive ones, a preview
  first, and the approval-gate response (`HeldResponse`) like the Kafka views.
- Shared web: `kernel/slots.ts` adds `subscriptions.create` and `destinations.create`
  (`{clusterId, opened, onClose}`); `SubscriptionsView` / Destinations view render the cluster's system
  contribution when one exists, else today's form, and gate the button with `useKindVerbRefusal` on `create`
  across the class's kinds (this also stops offering the Artemis form on Kafka); `kindVerbs.ts` adds `browse`
  words; `ui/IdentityBasisNote.tsx` (D9); `kernel/feature.ts` `FEATURE_IDS` + `'rabbitmq'`;
  `app/features.ts` lists `rabbitmqFeature`. The OpenAPI snapshot and `schema.d.ts` are regenerated.
- `web/src/test/wire.ts`: `RABBITMQ_KINDS` (the 15 kinds of the driver's section 2.3 with their verbs:
  vhost, exchange, classic-queue, quorum-queue, stream with `partitioned: true`, binding, exchange-binding,
  shovel, federation-link, policy, operator-policy, permission, topic-permission, user, connection, channel,
  consumer), `RABBITMQ_SYSTEM = { id: 'rabbitmq', name: 'RabbitMQ' }`, `LOADED_SYSTEMS` + the RabbitMQ row with
  `connection: 'management-api'`.

### D14. Stacks, seeds, sweep

- `deploy/compose/dev/compose.dev.yaml`: one `rabbitmq:4.4.0-management` node (`hostname: rabbitmq`,
  plugins `rabbitmq_management,rabbitmq_shovel,rabbitmq_shovel_management,rabbitmq_federation,
  rabbitmq_federation_management,rabbitmq_event_exchange` via a mounted `enabled_plugins`, default user
  `rahdar`/`rahdar` with `administrator`, `RABBITMQ_SERVER_ADDITIONAL_ERL_ARGS` for a small memory high
  watermark, ports 15672 and 5672 on localhost). `compose.demo.yaml`: a three-node cluster (`rabbitmq-1..3`,
  classic peer discovery, shared Erlang cookie) replacing the single node through a profile, as Kafka does.
  `compose.isolated.yaml` gets the dev node.
- `scripts/lib/rabbitmq-seed.sh` (bash 3.2, `rabbitmqadmin` from the image or `curl` to the management API):
  vhosts `/` and `payments`; exchanges `orders` (topic), `audit` (fanout); classic `orders.eu`, quorum
  `orders.us` (lagging, no consumer), stream `audit-log` (200 messages), `orders.unrouted-test` exchange with no
  binding; a policy, a permission, a dynamic shovel; a 200-character queue name. `qa-seed.sh` and
  `demo-seed.sh` register the cluster "rabbit" like "events".
- `web/scripts/sweep/rabbitmq.ts` (`:rabbit` cluster id, resolved in `sweep.ts` like `:kafka`):
  registration (pass, wrong endpoint, auth refused, partly reached, management plugin missing as a `-stub`),
  Nodes (healthy, alarm `-stub`, partition `-stub`), Virtual hosts (+ delete preview held), Bindings, Policies
  (+ preview), Permissions, Shovels and federation (+ plugin missing `-stub`), and the shared screens on the
  RabbitMQ cluster: Subscriptions (three kinds, stream backlog unavailable), classic queue messages (browse
  refused), stream messages, Destinations + Publish with `not-routed`, Connections, Channels, Consumers, delete
  confirmation with the CONFIGURATION note. Light and dark, empty and error states. Listed in `routes.ts`.

### D15. Docs and OpenSpec

- `README.md`: support table row "RabbitMQ | 3.13 and later (4.x tested) | [RabbitMQ](https://rahdar.dev/guide/rabbitmq)"
  replacing "Coming next"; tagline and feature list mention RabbitMQ; a "### RabbitMQ" feature list like
  Kafka's; the trademark line stays ("RabbitMQ is a trademark of its owner" is already there). `NOTICE` gains a
  RabbitMQ sentence beside the Artemis one; `Branding.java` / `branding.ts` `trademarkNotice` likewise (the two
  files that may name products).
- `docs/dockerhub.md`: the RabbitMQ bullet, the registration sentence, docs link.
- `site/src/guide/rabbitmq.md` (registration, what each screen shows, browse only on streams, NOT_ROUTED,
  identity basis, health rules, permissions, plugins needed and their remedies), sidebar in
  `site/src/.vitepress/config.ts`, `supported-versions.md`, the three locale descriptions in `config.ts`, and the
  `fa` pages that list the supported systems.
- ADR-0223 "The RabbitMQ namespace, its operations and its permissions" (shape of ADR-0216: namespace and
  tables, cluster name as identity, operations and the CONFIGURATION basis, permissions and the `access-rule`
  move, streams as logs, stream backlog unavailable). ADR-0224 "A cluster read as one is changed as one" (D5;
  amends ADR-0084's per-node fan-out for such systems; same gate types with an id).
- `openspec/changes/19f-rabbitmq/` in Rahdar: `proposal.md` (Rahdar's terms only), `design.md` (this document
  minus section 9), `tasks.md` (section 4), `specs/system-rabbitmq/spec.md` (new) and deltas:
  `cluster-registration` (RabbitMQ connection, endpoints list, new causes), `queue-lifecycle` (whole-cluster
  delete/purge), `message-operations` (stream browse, browse refusal before any read, routing key, not-routed),
  `estate-view` (health rules), `authorization` (namespace and policy permissions, access-rule shared),
  `operator-ui` (identity basis note, create slots), `plugin-messaging` (whole-cluster drains on a cluster read
  as one), `mcp-server` (RabbitMQ read tools).

### D16. Rahdar rules this respects

- Commits are release notes:
  `feat(rabbitmq): register and manage RabbitMQ clusters` (body for upgraders: the new connection, permissions
  added to built-in roles, recreate the database), `feat(resources)!: change a cluster read as one as one`
  (`BREAKING CHANGE:` the gated params of `subscription.delete|purge`, `destination.delete` gain `id`; the
  views gain `identityBasis`; `access-rule:*` now defined by resources), `build(deps): take openbroker 0.9.0`,
  `docs(rabbitmq): …`, `test(rabbitmq): …`.
- One baseline per module (ADR-0192): a new `system/rabbitmq/changes/0001-baseline.sql`; the security
  baseline is edited in place for role grants; no new changeset files in old modules.
- `Contract.VERSION` (18) and the web `CONTRACT` change only if japicmp (`api-compat` job) flags a break.
  Expected: none in `@PluginApi` (no plugin type changes). The npm plugin SDK re-exports `SlotProps`, so the two
  new slots are an additive type change: no bump unless the job says otherwise.
- `BoundaryRulesTest`: add "only the RabbitMQ module knows `io.github.sudoitir.openbroker.rabbitmq..`" (and the
  same for Kafka, which is missing); `system.rabbitmq` is **not** added to the `unwrap` allow-list.

---

## 3. Files to change

Paths are under `/home/user/rahdar/` (implement in a worktree, never the main checkout). `S` = shared code.

**Build**
- `pom.xml`: openbroker 0.9.0, `openbroker-rabbitmq`, test `testcontainers-rabbitmq`.

**Backend, shared (S)**
- `platform/broker/ConnectionOptions.java`: `Entry.MANAGEMENT_API`.
- `platform/broker/LogBounds.java`: `default Set<KindId> logs(UUID)`.
- `platform/broker/WholeClusterOperations.java` (new), `platform/broker/IdentityBasis.java` (new).
- `platform/broker/BrokerMessages.java`: `browseLog` next offset; `sendTo(cluster, ResourceId target, Optional<String> routingKey, message)` overload at `Scope.whole()`; capability ids from SDK constants.
- `platform/broker/BrokerFailures.java`: NOT_ROUTED, DESTINATION_FULL.
- `platform/broker/QueueRow.java`: ESTIMATE fallback reason; `withoutBacklog(reason)`.
- `platform/clusters/ClusterKinds.java`: `readAsOne`; `LogBounds.logs` in `partitioned`.
- `platform/clusters/ClusterRegistrar.java`: `detail` returns `SystemConnectionDetail`.
- `platform/clusters/SystemRegistration.java`, `ClusterService.java`: the sealed detail.
- `platform/clusters/web/ClusterRequests.java`: `RabbitMqConnection`, `RabbitMqCredentials`, `RabbitMqConnectionUpdate`, union mappings.
- `platform/clusters/web/ClusterViews.java`: `SystemConnectionDetail`, `RabbitMqConnectionDetail`, `ConnectionCheck.endpoints`, `EndpointReach`, Failure cause docs.
- `feature/resources/QueueLifecycleService.java`, `QueueGatedOperations.java`, `PagedListService.java` (call `readAsOne`), `ResourcePermissions.java` (`ACCESS_RULE_*`), `web/ResourceLifecycleController.java`, `web/…LifecycleOutcomeView` (identityBasis).
- `feature/messages/MessageService.java`, `MessageActionParams.java`, `web/MessageController.java`, `web/MessageRequests.java` (routingKey), `web/MessageViews.java` (identityBasis).
- `feature/bulk/BulkService.java`, `BulkRunner.java`.
- `feature/plugins/messaging/internal/PluginMessagingReconciler.java`.
- `system/kafka/KafkaModule.java` (drop access-rule defs, reference shared), `KafkaRegistrar.java` (detail type).
- `app/RahdarFeatures.java`: `RabbitMqModule.DESCRIPTOR`.
- `resources/db/changelog/db.changelog-master.xml`; `kernel/security/changes/0001-baseline.sql` (grants).

**Backend, new module** `src/main/java/io/github/sudoitir/rahdar/system/rabbitmq/`
- `package-info.java` (allowedDependencies = Kafka's list), `RabbitMqModule.java`, `RabbitMqConnectionOptions.java`,
  `RabbitMqRegistrar.java`, `ManagementUrls.java`, `AmqpEndpoints.java`, `AmqpProbe.java`, `RabbitMqFailures.java`,
  `RabbitMqClusterIdentity.java`, `RabbitMqScrape.java`, `RabbitMqHealth.java`, `RabbitMqHealthStore.java`,
  `RabbitMqClusterDeleted.java`, `RabbitMqReservedNames.java`, `RabbitMqLogBounds.java`, `RabbitMqNodes.java`,
  `RabbitMqVhosts.java`, `RabbitMqQueues.java`, `RabbitMqExchanges.java`, `RabbitMqBindings.java`,
  `RabbitMqPolicies.java`, `PolicyMatches.java`, `RabbitMqPermissions.java`, `RabbitMqShovels.java`,
  `RabbitMqFederation.java`, `RabbitMqGatedOperations.java`, `internal/persistence/RabbitMqConnectionEntity.java`,
  `internal/persistence/RabbitMqConnectionRepository.java`, `web/RabbitMqController.java`, `web/RabbitMqViews.java`,
  `mcp/RabbitMqMcpToolDefs.java`, `mcp/RabbitMqMcpReadTools.java`, `mcp/RabbitMqMcpViews.java`.
- `resources/db/changelog/system/rabbitmq/changelog.xml`, `changes/0001-baseline.sql`.

**Tests** (`src/test/java/io/github/sudoitir/rahdar/…`)
- `system/rabbitmq/`: `RabbitMqRegistrarTest`, `ManagementUrlsTest`, `RabbitMqHealthTest`, `RabbitMqHealthStoreTest`,
  `RabbitMqScrapeTest`, `RabbitMqReservedNamesTest`, `RabbitMqLogBoundsTest`, `PolicyMatchesTest`,
  `RabbitMqPoliciesTest`, `RabbitMqPermissionsTest`, `RabbitMqGatedOperationsTest`, `RabbitMqAuditClosureTest`,
  `RabbitMqModuleTest` (`@ApplicationModuleTest`), `mcp/RabbitMqMcpViewsTest`; real broker (CI only):
  `RabbitMqRegisterRealBrokerTest`, `RabbitMqNamespaceRealBrokerTest`, `RabbitMqSharedRoutesRealBrokerTest`,
  `RabbitMqMessagesRealBrokerTest` (stream browse, classic refusal with no read, send, not-routed, purge, delete
  with etag), `RabbitMqBrokers.java` (the container helper: `System.getProperty("rabbitmq.image",
  "rabbitmq:4.4.0-management")`, plugins file, `withStartupTimeout`).
- Shared: `platform/clusters/ClusterKindsTest` (readAsOne, logs), `platform/broker/BrokerMessagesTest`
  (opaque cursor), `platform/broker/QueueRowTest` (new), `platform/broker/WholeClusterOperationsTest` (new),
  `feature/resources/QueueLifecycleServiceWholeClusterTest` (new), `feature/bulk/BulkWholeClusterTest` (new), `feature/messages/MessageServiceTest` (+ cases), `platform/clusters/web/ClusterRequestsJsonTest` (new),
  `feature/messages/web/MessageControllerWholeClusterTest` (new: browse refusal makes no read), `architecture/BoundaryRulesTest`,
  `TruncateServiceTest` and `KafkaHangingTransactionsTest` (Preview basis), `McpToolSchemaBudgetTest`.

**Web** (`web/src/`)
- Shared: `kernel/slots.ts`, `kernel/feature.ts`, `kernel/auth/kindVerbs.ts`, `app/features.ts`,
  `ui/IdentityBasisNote.tsx`, `features/resources/SubscriptionsView.tsx` and the Destinations view,
  `SubscriptionLifecycleActions.tsx`, `features/messages/{MessagesView,PartitionControls,PurgeSubscription}.tsx`,
  `features/clusters/{RegisterClusterPage,ClusterSettings,ConnectionCheckPanel,RegistrationFailure,checkColumns,connectionCheck}.ts(x)`,
  `kernel/api/schema.d.ts` (generated), `test/wire.ts`.
- New: `features/clusters/{RabbitMqRegistrationForm,RabbitMqConnectionFields,RabbitMqConnectionSettings}.tsx`,
  `features/clusters/rabbitmqConnection.ts`; `features/rabbitmq/{feature.ts,api.ts,words.ts,NodesView.tsx,
  VhostsView.tsx,BindingsView.tsx,PoliciesView.tsx,PermissionsView.tsx,LinksView.tsx,QueuePanel.tsx,
  PublishToExchange.tsx,CreateQueueForm.tsx,CreateExchangeForm.tsx}` and their `*.test.tsx`.
- `web/scripts/sweep/rabbitmq.ts`, `web/scripts/sweep/routes.ts`, `web/scripts/sweep.ts`.

**Stacks and docs**
- `deploy/compose/dev/compose.dev.yaml`, `compose.demo.yaml`, `compose.isolated.yaml`, `deploy/compose/dev/rabbitmq/enabled_plugins` (+ `rabbitmq.conf`).
- `scripts/lib/rabbitmq-seed.sh`, `scripts/qa-seed.sh`, `scripts/demo-seed.sh`.
- `README.md`, `NOTICE`, `docs/dockerhub.md`, `site/src/guide/rabbitmq.md`, `site/src/guide/supported-versions.md`,
  `site/src/.vitepress/config.ts`, `site/src/fa/**` pages naming the systems, `docs/adr/0223-…md`, `docs/adr/0224-…md`,
  `src/main/java/…/kernel/core/Branding.java` + `web/src/branding.ts` (trademark notice only),
  `openspec/changes/19f-rabbitmq/**`.

---

## 4. Task list (file-disjoint units)

Rules for every implementer: work in the change's worktree, read narrowly (grep, then the lines), run only the
narrow check named below inside `systemd-run --user --scope -q -p MemoryMax=3G -p MemorySwapMax=2G --`, at most
two heavy commands across all agents, Java at `/opt/jdk25` (`JAVA_HOME=/opt/jdk25`), `./mvnw -o` once
dependencies are cached. **No Docker on this machine**: real-broker tests are written and compiled
(`./mvnw -q test-compile`) and run only on CI. Web: `cd web && npx vitest run --project unit <path>`. Commit on
the unit's branch with the commit types of D16; no PRs.

**Wave 0 (lead, inline).** `openspec/changes/19f-rabbitmq/**`, `docs/adr/0223-*.md`, `docs/adr/0224-*.md`;
OpenBroker 0.9.0-SNAPSHOT installed into `~/.m2` (`cd openbroker && ./mvnw -q -DskipTests install`).

| # | Unit | Files it owns | Depends on | Finish line (local) |
|---|---|---|---|---|
| B1 | SDK 0.9 bump | `pom.xml`; the two tests building `Preview`; `MessageService.requireOffered` lines only (capability constants) | W0 | `./mvnw -q test-compile` and `./mvnw -o test -Dtest='TruncateServiceTest,KafkaHangingTransactionsTest'` green |
| B2 | Registration contract (shared) | `ConnectionOptions.java`, `ClusterRegistrar.java`, `SystemRegistration.java`, `ClusterService.java` (connectionView), `ClusterRequests.java`, `ClusterViews.java`, `KafkaRegistrar.java` (detail type only) | B1 | `./mvnw -o test -Dtest='KafkaRegistrarTest,SystemsControllerTest,ClusterRequestsJsonTest'` green (`ClusterRequestsJsonTest` is new: the RabbitMQ union round trip and the redacted toString) |
| B3 | Whole-cluster path (shared) | `WholeClusterOperations.java`, `IdentityBasis.java`, `ClusterKinds.java`, `PagedListService.java`, `QueueLifecycleService.java`, `QueueGatedOperations.java`, `ResourceLifecycleController.java`, lifecycle outcome view, `BulkService.java`, `BulkRunner.java`, `PluginMessagingReconciler.java`, their tests | B1 | `-Dtest='ClusterKindsTest,WholeClusterOperationsTest,QueueLifecycleServiceTest,QueueLifecycleServiceWholeClusterTest,BulkLogPurgeTest,BulkWholeClusterTest,PluginMessagingReconcilerChangeTest'` green (`WholeClusterOperationsTest`, `QueueLifecycleServiceWholeClusterTest`, `BulkWholeClusterTest` are new) |
| B4 | Messages on a cluster read as one (shared) | `BrokerMessages.java` (minus B1 lines), `BrokerFailures.java`, `QueueRow.java`, `LogBounds.java`, `MessageService.java` (minus B1 lines), `MessageActionParams.java`, `MessageController.java`, `MessageRequests.java`, `MessageViews.java`, their tests | B1, B3 (`readAsOne`, `WholeClusterOperations` signatures fixed by this doc, so B4 may start on stubs) | `-Dtest='BrokerMessagesTest,QueueRowTest,MessageServiceTest,MessageControllerWholeClusterTest,BacklogTest'` green (`QueueRowTest` and `MessageControllerWholeClusterTest` are new; the latter proves a classic-queue browse makes no broker read) |
| B5 | RabbitMQ module core | `system/rabbitmq/{package-info,RabbitMqModule,RabbitMqConnectionOptions,RabbitMqRegistrar,ManagementUrls,AmqpEndpoints,AmqpProbe,RabbitMqFailures,RabbitMqClusterIdentity,RabbitMqReservedNames}.java`, `internal/persistence/**`, the changelog files, master include, `RahdarFeatures.java`, `BoundaryRulesTest.java`, unit tests and `RabbitMqModuleTest` | B2 | `-Dtest='RabbitMqRegistrarTest,ManagementUrlsTest,RabbitMqReservedNamesTest,RabbitMqModuleTest,BoundaryRulesTest,ModularityTest'` green (`ModularityTest` and `SchemaLintTest` need Docker → CI; run `ModularityTest` if it does not) |
| B6 | Scrape and health | `RabbitMqScrape.java`, `RabbitMqHealth.java`, `RabbitMqHealthStore.java`, `RabbitMqClusterDeleted.java`, `RabbitMqLogBounds.java`, tests | B4 (QueueRow helper), B5 | `-Dtest='RabbitMqScrapeTest,RabbitMqHealthTest,RabbitMqLogBoundsTest'` green |
| B7 | Namespace API | `RabbitMqNodes, RabbitMqVhosts, RabbitMqQueues, RabbitMqExchanges, RabbitMqBindings, RabbitMqPolicies, PolicyMatches, RabbitMqPermissions, RabbitMqShovels, RabbitMqFederation, RabbitMqGatedOperations`, `web/RabbitMqController`, `web/RabbitMqViews`, `ResourcePermissions.java` (access-rule), `KafkaModule.java`, security baseline grants, tests | B3, B5 | `-Dtest='PolicyMatchesTest,RabbitMqPoliciesTest,RabbitMqPermissionsTest,RabbitMqGatedOperationsTest,RabbitMqAuditClosureTest'` green; `OpenApiSnapshotTest` (or the test that writes `web/openapi.json`) run once at the end of this unit |
| B8 | MCP | `system/rabbitmq/mcp/**`, `McpToolSchemaBudgetTest` | B7 | `-Dtest='RabbitMqMcpViewsTest,McpToolSchemaBudgetTest'` green |
| B9 | Real-broker tests (CI) | `RabbitMqBrokers.java`, the four `*RealBrokerTest`s | B5–B7 | `./mvnw -q test-compile` green; CI runs them |
| W1 | Shared web | `kernel/slots.ts`, `kernel/feature.ts`, `kernel/auth/kindVerbs.ts`, `ui/IdentityBasisNote.tsx`, `features/resources/*` touched files, `features/messages/{MessagesView,PartitionControls,PurgeSubscription}.tsx`, `test/wire.ts`, `kernel/api/schema.d.ts` (regenerate from B7's snapshot) | B7 (snapshot) | `npx vitest run --project unit src/features/resources src/features/messages src/kernel` green; `npm run typecheck` (or `tsc -b`) green |
| W2 | Registration web | `features/clusters/{RegisterClusterPage,ClusterSettings,ConnectionCheckPanel,RegistrationFailure,checkColumns,connectionCheck}`, new `RabbitMq*` and `rabbitmqConnection.ts` with tests | W1 (wire.ts) | `npx vitest run --project unit src/features/clusters` green |
| W3 | RabbitMQ feature web | `features/rabbitmq/**`, `app/features.ts` | W1 | `npx vitest run --project unit src/features/rabbitmq` green |
| S1 | Stacks and seed | compose files, `deploy/compose/dev/rabbitmq/*`, `scripts/lib/rabbitmq-seed.sh`, `qa-seed.sh`, `demo-seed.sh` | B5 | `bash -n` on each script; `docker compose -f … config -q` where Docker CLI exists, else review; shellcheck if installed |
| S2 | Sweep | `web/scripts/sweep/rabbitmq.ts`, `routes.ts`, `sweep.ts` | W2, W3, S1 | `npx tsc --noEmit -p web/scripts` (or the repo's sweep typecheck); captures on a machine with Docker |
| D1 | Docs | README, NOTICE, Branding pair, dockerhub, guide pages, site config, fa pages | W3 | `npm run format:check` in `web` and the site's build if quick (`cd site && npm run build`) |

Order: **W0 → B1 → (B2 ∥ B3) → (B4 ∥ B5) → (B6 ∥ B7) → (B8 ∥ B9 ∥ W1 ∥ S1) → (W2 ∥ W3) → (S2 ∥ D1) → lead**.
The lead merges the unit branches into one session branch, runs `just fmt`, regenerates the OpenAPI snapshot
once, rebases on `main`, opens **one** PR with auto-merge after OpenBroker 0.9.0 is on Central and the pom
points at it, and fixes every Sonar issue on the PR.

---

## 5. Finish line

Local (no Docker), in the worktree:

```bash
export JAVA_HOME=/opt/jdk25
systemd-run --user --scope -q -p MemoryMax=3G -p MemorySwapMax=2G -- ./mvnw -q -o test-compile
systemd-run --user --scope -q -p MemoryMax=3G -p MemorySwapMax=2G -- ./mvnw -o test \
  -Dtest='RabbitMq*Test,!RabbitMq*RealBrokerTest,ClusterKindsTest,WholeClusterOperationsTest,QueueLifecycleServiceTest,QueueLifecycleServiceWholeClusterTest,BrokerMessagesTest,QueueRowTest,MessageServiceTest,MessageControllerWholeClusterTest,BulkLogPurgeTest,BulkWholeClusterTest,PluginMessagingReconcilerChangeTest,ClusterRequestsJsonTest,SystemsControllerTest,KafkaRegistrarTest,TruncateServiceTest,KafkaHangingTransactionsTest,BoundaryRulesTest,McpToolSchemaBudgetTest' \
  -Dsurefire.failIfNoSpecifiedTests=false
cd web && npx vitest run --project unit src/features/rabbitmq src/features/clusters src/features/resources src/features/messages src/kernel
cd web && npm run lint && npm run format:check
git diff --exit-code web/openapi.json   # after the snapshot test regenerated it and it was committed
```

CI (the proof): the PR's `ci-ok` green, which includes every shard running `RabbitMq*RealBrokerTest`
(Testcontainers `rabbitmq:4.4.0-management`), `ModularityTest`, `SchemaLintTest`, Liquibase against
Testcontainers Postgres, `api-compat` (japicmp: expect no `Contract.VERSION` bump), the frontend job, and an
empty SonarQube Cloud issue list for the PR. Then the screenshot sweep on a Docker machine with
`just qa-up` (RabbitMQ next to Artemis and Kafka), light and dark.

---

## 6. Open questions only the user can answer

1. **Cluster identity.** The management API exposes no permanent cluster id, only `cluster_name` (defaults to
   `rabbit@<first host>`, renamable with `rabbitmqctl set_cluster_name`). Default: the claim is
   `cluster_name`, two estates with the same name cannot both be registered (the check names the holder), and a
   rename turns health CRITICAL until the connection is checked and saved again. Alternative: claim nothing for
   RabbitMQ and allow duplicates.
2. **Vhost in access patterns.** Default: team patterns and shares match the queue or exchange name only, so a
   share on `orders*` covers `orders` in every vhost of that cluster. Scoping by vhost needs a new pattern
   dimension across teams, shares and tokens: a follow-up change.
3. **Built-in role grants.** Default: operator gets `namespace:read|write` and `policy:read|write`; only admin
   gets `namespace:delete` and `access-rule:write`.
4. **MCP mutations.** Default: read-only RabbitMQ tools in this change; policy and binding changes through MCP
   follow.

Defaulted without asking: one account for both planes; AMQP endpoints optional (management-only clusters
register, and messages screens say why they cannot read); broker events from RabbitMQ (and Kafka) are not
streamed into the Events view in this change (needs a per-cluster notification stream; follow-up); classic
mirroring shown only on 3.13; consumer-health stays Artemis-only.

---
